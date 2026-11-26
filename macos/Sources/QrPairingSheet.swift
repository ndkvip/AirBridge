import SwiftUI
import AppKit
import CoreImage
import Network
import Darwin

struct QrPairPayloadFromPhone: Codable {
    let secret: String
    let deviceId: String
    let deviceName: String
    let model: String
    let host: String
    let webPort: Int
    let tlsPort: Int
    let certSha256: String
    let token: String
    let pin: String
}

/// Hosts a short-lived local TCP listener while the QR code sheet is open.
/// When the Android phone scans the QR code, it POSTs its permanent 256-bit token,
/// deviceId, and TLS SHA-256 cert fingerprint directly to this Mac.
@MainActor
final class MacQrPairingSession: ObservableObject {
    @Published var qrImage: NSImage? = nil
    @Published var statusText: String = "Mở AirBridge trên điện thoại → bấm biểu tượng Quét QR ở góc trên"
    @Published var pairedSuccess: Bool = false
    @Published var errorText: String? = nil

    private var listener: NWListener?
    private let secret: String
    private let client: AirBridgeMacClient
    private let onPaired: () -> Void

    init(client: AirBridgeMacClient, onPaired: @escaping () -> Void) {
        self.client = client
        self.onPaired = onPaired
        self.secret = UUID().uuidString.replacingOccurrences(of: "-", with: "")
    }

    func start() {
        stop()
        errorText = nil
        pairedSuccess = false

        guard let macIp = Self.localLanIPv4() else {
            errorText = "Không tìm thấy địa chỉ IP Wi-Fi/LAN của Mac"
            return
        }

        do {
            let l = try NWListener(using: .tcp, on: .any)
            l.stateUpdateHandler = { [weak self] newState in
                Task { @MainActor in
                    guard let self else { return }
                    switch newState {
                    case .ready:
                        if let port = l.port?.rawValue {
                            let qrString = self.buildQrUri(macIp: macIp, port: Int(port))
                            self.qrImage = Self.generateQRCode(from: qrString)
                        }
                    case .failed(let err):
                        self.errorText = "Không mở được cổng ghép nối: \(err.localizedDescription)"
                    default:
                        break
                    }
                }
            }
            l.newConnectionHandler = { [weak self] conn in
                self?.handleIncoming(conn)
            }
            l.start(queue: DispatchQueue.global(qos: .userInitiated))
            self.listener = l
        } catch {
            errorText = "Lỗi khởi tạo QR: \(error.localizedDescription)"
        }
    }

    func stop() {
        listener?.cancel()
        listener = nil
    }

    private func buildQrUri(macIp: String, port: Int) -> String {
        let encName = client.clientName.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? "Mac"
        return "airbridge://pair?clientId=\(client.clientId)&clientName=\(encName)&macIp=\(macIp)&macPort=\(port)&secret=\(secret)"
    }

    private nonisolated func handleIncoming(_ conn: NWConnection) {
        conn.start(queue: DispatchQueue.global(qos: .userInitiated))
        var accumulated = Data()

        func receiveNext() {
            conn.receive(minimumIncompleteLength: 1, maximumLength: 16384) { [weak self] data, _, isComplete, _ in
                if let data { accumulated.append(data) }
                guard let self else { conn.cancel(); return }

                if let range = accumulated.range(of: Data("\r\n\r\n".utf8)) {
                    let headerData = accumulated[..<range.lowerBound]
                    let bodyData = accumulated[range.upperBound...]
                    let headerStr = String(data: headerData, encoding: .utf8) ?? ""
                    var contentLength = 0
                    for line in headerStr.components(separatedBy: "\r\n") {
                        if line.lowercased().hasPrefix("content-length:") {
                            contentLength = Int(line.dropFirst("content-length:".count).trimmingCharacters(in: .whitespaces)) ?? 0
                        }
                    }
                    if bodyData.count >= contentLength || isComplete {
                        let jsonSlice = contentLength > 0 ? Data(bodyData.prefix(contentLength)) : Data(bodyData)
                        self.processPayload(jsonSlice, on: conn)
                        return
                    }
                }

                if isComplete {
                    conn.cancel()
                } else {
                    receiveNext()
                }
            }
        }
        receiveNext()
    }

    private nonisolated func processPayload(_ body: Data, on conn: NWConnection) {
        guard let payload = try? JSONDecoder().decode(QrPairPayloadFromPhone.self, from: body) else {
            sendHttp(conn, status: "400 Bad Request", json: "{\"ok\":false,\"error\":\"BAD_JSON\"}")
            return
        }
        Task { @MainActor in
            guard payload.secret == self.secret else {
                self.sendHttp(conn, status: "403 Forbidden", json: "{\"ok\":false,\"error\":\"BAD_SECRET\"}")
                return
            }
            self.sendHttp(conn, status: "200 OK", json: "{\"ok\":true}")
            self.client.completePermanentQrPairing(payload)
            self.pairedSuccess = true
            self.statusText = "✅ Đã ghép nối vĩnh viễn với \(payload.deviceName)!"
            self.stop()
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.1) {
                self.onPaired()
            }
        }
    }

    private nonisolated func sendHttp(_ conn: NWConnection, status: String, json: String) {
        let bodyData = Data(json.utf8)
        let header = "HTTP/1.1 \(status)\r\nContent-Type: application/json\r\nContent-Length: \(bodyData.count)\r\nConnection: close\r\n\r\n"
        var resp = Data(header.utf8)
        resp.append(bodyData)
        conn.send(content: resp, completion: .contentProcessed { _ in
            conn.cancel()
        })
    }

    static func generateQRCode(from text: String) -> NSImage? {
        guard let data = text.data(using: .utf8),
              let filter = CIFilter(name: "CIQRCodeGenerator") else { return nil }
        filter.setValue(data, forKey: "inputMessage")
        filter.setValue("M", forKey: "inputCorrectionLevel")
        guard let output = filter.outputImage else { return nil }
        let scaled = output.transformed(by: CGAffineTransform(scaleX: 10, y: 10))
        let rep = NSCIImageRep(ciImage: scaled)
        let nsImage = NSImage(size: rep.size)
        nsImage.addRepresentation(rep)
        return nsImage
    }

    static func localLanIPv4() -> String? {
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddr) == 0, let first = ifaddr else { return nil }
        defer { freeifaddrs(ifaddr) }

        var fallback: String? = nil
        var ptr: UnsafeMutablePointer<ifaddrs>? = first
        while let current = ptr {
            let flags = Int32(current.pointee.ifa_flags)
            let isUp = (flags & IFF_UP) != 0 && (flags & IFF_LOOPBACK) == 0
            if isUp, let addr = current.pointee.ifa_addr, addr.pointee.sa_family == UInt8(AF_INET) {
                let name = String(cString: current.pointee.ifa_name)
                var hostBuf = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                if getnameinfo(addr, socklen_t(addr.pointee.sa_len), &hostBuf, socklen_t(hostBuf.count), nil, 0, NI_NUMERICHOST) == 0 {
                    let ip = String(cString: hostBuf)
                    if !ip.hasPrefix("169.254.") {
                        if name == "en0" || name == "en1" {
                            return ip
                        }
                        if fallback == nil { fallback = ip }
                    }
                }
            }
            ptr = current.pointee.ifa_next
        }
        return fallback
    }
}

struct QrPairingSheet: View {
    @ObservedObject var client: AirBridgeMacClient
    let onClose: () -> Void
    @StateObject private var session: MacQrPairingSession

    init(client: AirBridgeMacClient, onClose: @escaping () -> Void) {
        self.client = client
        self.onClose = onClose
        _session = StateObject(wrappedValue: MacQrPairingSession(client: client, onPaired: onClose))
    }

    var body: some View {
        VStack(spacing: 16) {
            HStack {
                Label("Ghép nối 1 chạm bằng mã QR", systemImage: "qrcode.viewfinder")
                    .font(.headline)
                Spacer()
                Button("Đóng") {
                    session.stop()
                    onClose()
                }
                .keyboardShortcut(.escape, modifiers: [])
            }

            if let err = session.errorText {
                Text(err)
                    .foregroundStyle(.red)
                    .padding()
            } else if session.pairedSuccess {
                VStack(spacing: 12) {
                    Image(systemName: "checkmark.seal.fill")
                        .font(.system(size: 56))
                        .foregroundStyle(.green)
                    Text(session.statusText)
                        .font(.title3.bold())
                }
                .frame(width: 240, height: 240)
            } else if let img = session.qrImage {
                Image(nsImage: img)
                    .interpolation(.none)
                    .resizable()
                    .scaledToFit()
                    .frame(width: 230, height: 230)
                    .padding(14)
                    .background(Color.white, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                    .shadow(color: .black.opacity(0.15), radius: 8, y: 4)
            } else {
                ProgressView("Đang tạo mã QR...")
                    .frame(width: 240, height: 240)
            }

            Text(session.statusText)
                .font(.callout)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .frame(maxWidth: 320)

            Text("Quét 1 lần duy nhất • Tự lưu Token 256-bit & vân tay TLS • Không cần nhập lại PIN kể cả khi đổi Wi-Fi.")
                .font(.caption)
                .foregroundStyle(.tertiary)
                .multilineTextAlignment(.center)
                .frame(maxWidth: 320)
        }
        .padding(24)
        .frame(width: 390)
        .onAppear { session.start() }
        .onDisappear { session.stop() }
    }
}
