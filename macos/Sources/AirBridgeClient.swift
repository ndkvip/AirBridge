import Foundation
import AppKit
import Network
import Combine
import Darwin
import CryptoKit
import Security

struct PhoneFileEntry: Identifiable, Codable, Hashable {
    var id: String { path }
    let name: String
    let path: String
    let isDir: Bool
    let size: Int64
    let modifiedMs: Int64
    let mime: String
}

struct ClipboardItem: Identifiable, Codable, Hashable {
    let id: Int64
    let text: String
    let source: String
    let timestampMs: Int64
    let isImage: Bool?
}

struct DiscoveredPhone: Identifiable, Hashable {
    let id: String // stable deviceId
    let name: String
    let model: String
    let host: String
    let webPort: Int
    let tlsPort: Int
    let certSha256: String
    var lastSeen: Date
}

struct UdpBeaconPayload: Codable {
    let app: String
    let deviceId: String
    let name: String
    let model: String
    let webPort: Int
    let tlsPort: Int?
    let certSha256: String?
}

struct PairStatusResponse: Codable {
    let status: String
    let token: String?
}

/// Validates self-signed AirBridge TLS certificates strictly by SHA-256 fingerprint pinning (TOFU).
final class PinnedTlsSessionDelegate: NSObject, URLSessionDelegate {
    private let lock = NSLock()
    private var expectedSha256: String = ""

    func updatePinnedFingerprint(_ hex: String) {
        lock.lock()
        expectedSha256 = hex.lowercased()
        lock.unlock()
    }

    func urlSession(
        _ session: URLSession,
        didReceive challenge: URLAuthenticationChallenge,
        completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void
    ) {
        guard challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust,
              let trust = challenge.protectionSpace.serverTrust else {
            completionHandler(.performDefaultHandling, nil)
            return
        }
        lock.lock()
        let pinned = expectedSha256
        lock.unlock()

        guard !pinned.isEmpty,
              let certChain = SecTrustCopyCertificateChain(trust) as? [SecCertificate],
              let leaf = certChain.first else {
            completionHandler(.cancelAuthenticationChallenge, nil)
            return
        }
        let derData = SecCertificateCopyData(leaf) as Data
        let digest = SHA256.hash(data: derData)
        let actualHex = digest.map { String(format: "%02x", $0) }.joined()
        if actualHex == pinned {
            completionHandler(.useCredential, URLCredential(trust: trust))
        } else {
            // Certificate mismatch (possible MITM on cafe Wi-Fi) -> REJECT!
            completionHandler(.cancelAuthenticationChallenge, nil)
        }
    }
}

struct DeviceStatusResponse: Codable {
    let deviceName: String
    let modelName: String
    let screenStreaming: Bool
    let remoteControlEnabled: Bool?
    let storageAccessible: Bool
    let clipboardLatest: String
}

struct FileListResponse: Codable {
    let path: String
    let entries: [PhoneFileEntry]
}

struct ClipboardResponse: Codable {
    let latest: String
    let history: [ClipboardItem]
}

struct GboardStateResponse: Codable {
    let text: String
    let updatedAtMs: Int64
    let imageUpdatedAtMs: Int64?
    let imageMime: String?
}

@MainActor
final class AirBridgeMacClient: ObservableObject {
    @Published var host: String {
        didSet { UserDefaults.standard.set(host, forKey: "airbridge_host") }
    }
    @Published var port: Int = 8080
    @Published var tlsPort: Int = 0
    @Published var pinnedCertSha256: String {
        didSet {
            UserDefaults.standard.set(pinnedCertSha256, forKey: "airbridge_cert_sha256")
            tlsDelegate.updatePinnedFingerprint(pinnedCertSha256)
        }
    }
    @Published var authToken: String {
        didSet { UserDefaults.standard.set(authToken, forKey: "airbridge_auth_token") }
    }
    @Published var isTlsEncrypted: Bool = false
    @Published var isAwaitingPhoneApproval: Bool = false
    let clientId: String
    let clientName: String

    @Published var pin: String {
        didSet { UserDefaults.standard.set(pin, forKey: "airbridge_pin") }
    }
    @Published var pairedDeviceId: String {
        didSet { UserDefaults.standard.set(pairedDeviceId, forKey: "airbridge_device_id") }
    }
    @Published var gboardSyncEnabled: Bool {
        didSet { UserDefaults.standard.set(gboardSyncEnabled, forKey: "airbridge_gboard_enabled") }
    }
    @Published var gboardPort: Int {
        didSet { UserDefaults.standard.set(gboardPort, forKey: "airbridge_gboard_port") }
    }
    @Published var gboardPairingCode: String {
        didSet { UserDefaults.standard.set(gboardPairingCode, forKey: "airbridge_gboard_code") }
    }
    @Published var isGboardConnected: Bool = false

    @Published var isConnected: Bool = false
    @Published var deviceName: String = "Đang tìm điện thoại..."
    @Published var modelName: String = ""
    @Published var isScreenStreaming: Bool = false
    @Published var isRemoteControlEnabled: Bool = false
    @Published var statusMessage: String = "Đang quét điện thoại AirBridge trên Wi-Fi..."
    @Published var errorMessage: String?
    @Published var needsPinPrompt: Bool = false

    @Published var discoveredPhones: [DiscoveredPhone] = []
    @Published var currentPath: String = ""
    @Published var files: [PhoneFileEntry] = []
    @Published var clipboardHistory: [ClipboardItem] = []
    @Published var autoSyncClipboard: Bool = true
    @Published var uploadProgress: Double = 0
    @Published var isUploading: Bool = false
    @Published var uploadStatus: String = ""
    @Published var lastReceivedFileURL: URL?
    @Published var lastReceivedSummary: String = ""

    let macReceiver = MacFileReceiver()

    private var pollTimer: Timer?
    private var clipTimer: Timer?
    private var appNapActivity: NSObjectProtocol?
    private var lastMacPasteboardCount: Int = NSPasteboard.general.changeCount
    private var lastSyncedText: String = ""
    private var lastAppliedPhoneClipId: Int64 = -1
    private var lastGboardUpdatedAtMs: Int64 = -1
    private var lastGboardImageUpdatedAtMs: Int64 = -1
    private var isPushingClipboard: Bool = false
    private var udpSocketFd: Int32 = -1
    private var udpReadSource: DispatchSourceRead?

    private let tlsDelegate = PinnedTlsSessionDelegate()

    lazy var apiSession: URLSession = {
        let cfg = URLSessionConfiguration.ephemeral
        cfg.timeoutIntervalForRequest = 4
        cfg.timeoutIntervalForResource = 15
        cfg.httpMaximumConnectionsPerHost = 16
        return URLSession(configuration: cfg, delegate: tlsDelegate, delegateQueue: nil)
    }()

    init() {
        self.host = UserDefaults.standard.string(forKey: "airbridge_host") ?? ""
        self.pin = UserDefaults.standard.string(forKey: "airbridge_pin") ?? ""
        self.pairedDeviceId = UserDefaults.standard.string(forKey: "airbridge_device_id") ?? ""
        self.pinnedCertSha256 = UserDefaults.standard.string(forKey: "airbridge_cert_sha256") ?? ""
        self.authToken = UserDefaults.standard.string(forKey: "airbridge_auth_token") ?? ""
        if let savedCid = UserDefaults.standard.string(forKey: "airbridge_client_id"), !savedCid.isEmpty {
            self.clientId = savedCid
        } else {
            let newCid = UUID().uuidString
            UserDefaults.standard.set(newCid, forKey: "airbridge_client_id")
            self.clientId = newCid
        }
        self.clientName = Host.current().localizedName ?? "Mac"
        self.gboardSyncEnabled = UserDefaults.standard.object(forKey: "airbridge_gboard_enabled") as? Bool ?? true
        let savedGboardPort = UserDefaults.standard.integer(forKey: "airbridge_gboard_port")
        self.gboardPort = savedGboardPort > 0 ? savedGboardPort : 8080
        self.gboardPairingCode = UserDefaults.standard.string(forKey: "airbridge_gboard_code") ?? ""
        self.tlsDelegate.updatePinnedFingerprint(self.pinnedCertSha256)
        // Prevent macOS App Nap from pausing background clipboard sync when AirBridge is behind other apps
        self.appNapActivity = ProcessInfo.processInfo.beginActivity(
            options: [.userInitiatedAllowingIdleSystemSleep],
            reason: "AirBridge real-time clipboard & screen sync"
        )
        startUdpDiscoveryListener()
        startMacReceiver()
        startPolling()
        if !host.isEmpty && !pin.isEmpty {
            Task { await connect() }
        }
    }

    var baseURLString: String {
        let cleanHost = host.trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "http://", with: "")
            .replacingOccurrences(of: "https://", with: "")
            .components(separatedBy: "/").first ?? ""
        if cleanHost.contains(":") {
            return "http://\(cleanHost)"
        }
        if tlsPort > 0 && !pinnedCertSha256.isEmpty {
            return "https://\(cleanHost):\(tlsPort)"
        }
        return "http://\(cleanHost):\(port)"
    }

    var httpMediaBaseURLString: String {
        let cleanHost = host.trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "http://", with: "")
            .replacingOccurrences(of: "https://", with: "")
            .components(separatedBy: "/").first ?? ""
        if cleanHost.contains(":") {
            return "http://\(cleanHost)"
        }
        return "http://\(cleanHost):\(port)"
    }

    private func authQueryParam() -> String {
        if !authToken.isEmpty {
            let enc = authToken.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? authToken
            return "token=\(enc)"
        }
        let enc = pin.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? pin
        return "pin=\(enc)"
    }

    private func applyAuthHeader(to req: inout URLRequest) {
        req.setValue("\(macReceiver.port)", forHTTPHeaderField: "X-AirBridge-Mac-Port")
        if !authToken.isEmpty {
            req.setValue(authToken, forHTTPHeaderField: "X-AirBridge-Token")
        } else if !gboardPairingCode.isEmpty {
            req.setValue(gboardPairingCode, forHTTPHeaderField: "X-Pairing-Code")
            req.setValue(gboardPairingCode, forHTTPHeaderField: "X-AirBridge-Pin")
        } else if !pin.isEmpty {
            req.setValue(pin, forHTTPHeaderField: "X-AirBridge-Pin")
        }
    }

    private func startMacReceiver() {
        macReceiver.onFileReceived = { [weak self] url, name, size in
            Task { @MainActor [weak self] in
                guard let self = self else { return }
                self.lastReceivedFileURL = url
                let sizeText = ByteCountFormatter.string(fromByteCount: size, countStyle: .file)
                self.lastReceivedSummary = "Đã nhận: \(name) (\(sizeText))"
                self.statusMessage = self.lastReceivedSummary
                self.postLocalNotification(title: "AirBridge — Đã nhận từ Android", body: "\(name) (\(sizeText)) • Lưu tại Downloads/AirBridge")
                NSWorkspace.shared.activateFileViewerSelecting([url])
            }
        }
        macReceiver.onTextReceived = { [weak self] text in
            Task { @MainActor [weak self] in
                guard let self = self else { return }
                self.lastMacPasteboardCount = NSPasteboard.general.changeCount
                self.lastSyncedText = text
                let preview = text.count > 60 ? String(text.prefix(60)) + "…" : text
                self.lastReceivedSummary = "Đã nhận vào Clipboard: \(preview)"
                self.statusMessage = self.lastReceivedSummary
                self.postLocalNotification(title: "AirBridge — Đã chép vào Clipboard", body: preview)
            }
        }
        macReceiver.start()
    }

    private func postLocalNotification(title: String, body: String) {
        let escapedTitle = title.replacingOccurrences(of: "\"", with: "\\\"")
        let escapedBody = body.replacingOccurrences(of: "\"", with: "\\\"")
            .replacingOccurrences(of: "\n", with: " ")
        let script = "display notification \"\(escapedBody)\" with title \"\(escapedTitle)\""
        DispatchQueue.global(qos: .utility).async {
            let proc = Process()
            proc.executableURL = URL(fileURLWithPath: "/usr/bin/osascript")
            proc.arguments = ["-e", script]
            try? proc.run()
        }
    }

    func screenStreamURL() -> URL? {
        guard !host.isEmpty else { return nil }
        return URL(string: "\(httpMediaBaseURLString)/api/screen/stream?\(authQueryParam())&t=\(Int(Date().timeIntervalSince1970))")
    }

    func filePreviewURL(path: String, download: Bool = false) -> URL? {
        guard !host.isEmpty else { return nil }
        let encPath = path.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? path
        let dl = download ? "&download=1" : ""
        let base = download ? baseURLString : httpMediaBaseURLString
        return URL(string: "\(base)/api/file?path=\(encPath)&\(authQueryParam())\(dl)")
    }

    func completePermanentQrPairing(_ payload: QrPairPayloadFromPhone) {
        pairedDeviceId = payload.deviceId
        deviceName = payload.deviceName
        modelName = payload.model
        host = payload.host
        port = payload.webPort
        if payload.deviceId.hasPrefix("gb-"), payload.webPort > 0 {
            gboardPort = payload.webPort
        }
        tlsPort = payload.tlsPort
        pinnedCertSha256 = payload.certSha256
        authToken = payload.token
        pin = payload.pin
        isTlsEncrypted = (payload.tlsPort > 0 && !payload.certSha256.isEmpty)
        needsPinPrompt = false
        errorMessage = nil
        statusMessage = "Đã ghép nối vĩnh viễn với \(payload.deviceName)"
        Task { await connect() }
    }

    func selectPhone(_ phone: DiscoveredPhone) async {
        let switchingDevice = !pairedDeviceId.isEmpty && pairedDeviceId != phone.id
        host = phone.host
        port = phone.webPort
        if phone.id.hasPrefix("gb-"), phone.webPort > 0 {
            gboardPort = phone.webPort
        }
        if phone.tlsPort > 0 && !phone.certSha256.isEmpty {
            tlsPort = phone.tlsPort
            pinnedCertSha256 = phone.certSha256
            isTlsEncrypted = true
        } else {
            tlsPort = 0
            pinnedCertSha256 = ""
            isTlsEncrypted = false
        }
        if switchingDevice {
            authToken = ""
        }
        pairedDeviceId = phone.id
        deviceName = phone.name
        modelName = phone.model
        let isGboardCompanion = phone.id.hasPrefix("gb-")
        if !isGboardCompanion && authToken.isEmpty && pin.count < 6 {
            needsPinPrompt = true
            statusMessage = "Nhập mã PIN 6 số trên \(phone.name) (chỉ cần nhập 1 lần)"
            return
        }
        await connect()
    }

    /// Listens on UDP port 8772 for AirBridge Android discovery beacons.
    /// Automatically updates IP and reconnects if the phone's Wi-Fi IP changes.
    private func startUdpDiscoveryListener() {
        let fd = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
        guard fd >= 0 else { return }

        var yes: Int32 = 1
        setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &yes, socklen_t(MemoryLayout<Int32>.size))
        setsockopt(fd, SOL_SOCKET, SO_REUSEPORT, &yes, socklen_t(MemoryLayout<Int32>.size))
        setsockopt(fd, SOL_SOCKET, SO_BROADCAST, &yes, socklen_t(MemoryLayout<Int32>.size))

        let flags = fcntl(fd, F_GETFL, 0)
        _ = fcntl(fd, F_SETFL, flags | O_NONBLOCK)

        var addr = sockaddr_in()
        addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = in_port_t(8772).bigEndian
        addr.sin_addr = in_addr(s_addr: INADDR_ANY)

        let bindRes = withUnsafePointer(to: &addr) { ptr in
            ptr.withMemoryRebound(to: sockaddr.self, capacity: 1) { sa in
                Darwin.bind(fd, sa, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        guard bindRes == 0 else {
            Darwin.close(fd)
            return
        }

        udpSocketFd = fd
        let source = DispatchSource.makeReadSource(fileDescriptor: fd, queue: DispatchQueue.global(qos: .utility))
        source.setEventHandler { [weak self] in
            var buf = [UInt8](repeating: 0, count: 2048)
            var senderAddr = sockaddr_in()
            var addrLen = socklen_t(MemoryLayout<sockaddr_in>.size)

            let n = withUnsafeMutablePointer(to: &senderAddr) { ptr in
                ptr.withMemoryRebound(to: sockaddr.self, capacity: 1) { sa in
                    recvfrom(fd, &buf, buf.count, 0, sa, &addrLen)
                }
            }
            guard n > 0 else { return }
            let data = Data(buf.prefix(Int(n)))

            var ipBuf = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
            var sinAddr = senderAddr.sin_addr
            inet_ntop(AF_INET, &sinAddr, &ipBuf, socklen_t(INET_ADDRSTRLEN))
            let senderIp = String(cString: ipBuf)

            guard let payload = try? JSONDecoder().decode(UdpBeaconPayload.self, from: data),
                  payload.app == "AirBridge",
                  !senderIp.isEmpty else { return }

            Task { @MainActor [weak self] in
                self?.handleDiscoveredBeacon(payload: payload, senderIp: senderIp)
            }
        }
        source.setCancelHandler {
            Darwin.close(fd)
        }
        source.resume()
        udpReadSource = source
    }

    private func handleDiscoveredBeacon(payload: UdpBeaconPayload, senderIp: String) {
        let phone = DiscoveredPhone(
            id: payload.deviceId,
            name: payload.name,
            model: payload.model,
            host: senderIp,
            webPort: payload.webPort,
            tlsPort: payload.tlsPort ?? 0,
            certSha256: payload.certSha256 ?? "",
            lastSeen: Date()
        )
        if let idx = discoveredPhones.firstIndex(where: { $0.id == phone.id }) {
            discoveredPhones[idx] = phone
        } else {
            discoveredPhones.append(phone)
        }

        // Auto-reconnect paired phone (or seamlessly switch to integrated Gboard companion!)
        let isGboardBeacon = phone.id.hasPrefix("gb-")
        let shouldAutoAdopt = (!pairedDeviceId.isEmpty && phone.id == pairedDeviceId)
            || (isGboardBeacon && !isConnected)
            || (pairedDeviceId.isEmpty && discoveredPhones.count == 1)

        if shouldAutoAdopt {
            let ipChanged = (host != phone.host || port != phone.webPort || tlsPort != phone.tlsPort)
            if ipChanged {
                host = phone.host
                port = phone.webPort
                if isGboardBeacon, phone.webPort > 0 {
                    gboardPort = phone.webPort
                }
            }
            if isGboardBeacon && pairedDeviceId != phone.id {
                pairedDeviceId = phone.id
                authToken = ""
            }
            if phone.tlsPort > 0 && !phone.certSha256.isEmpty {
                tlsPort = phone.tlsPort
                if pinnedCertSha256.isEmpty || pairedDeviceId == phone.id {
                    pinnedCertSha256 = phone.certSha256
                }
                isTlsEncrypted = true
            } else {
                tlsPort = 0
                pinnedCertSha256 = ""
                isTlsEncrypted = false
            }
            if !isConnected && (isGboardBeacon || !authToken.isEmpty || !pin.isEmpty) && !isAwaitingPhoneApproval {
                Task { await connect() }
            } else if !isConnected && authToken.isEmpty && pin.isEmpty {
                statusMessage = "Đã thấy \(phone.name) — bấm vào tên máy ở cột trái để kết nối"
            }
        }
    }

    private func startPolling() {
        pollTimer?.invalidate()
        pollTimer = Timer.scheduledTimer(withTimeInterval: 2.0, repeats: true) { [weak self] _ in
            Task { @MainActor [weak self] in
                guard let self else { return }
                let cutoff = Date().addingTimeInterval(-15)
                self.discoveredPhones.removeAll { $0.lastSeen < cutoff }
                if self.isConnected {
                    await self.refreshStatus(silent: true)
                }
            }
        }

        clipTimer?.invalidate()
        clipTimer = Timer.scheduledTimer(withTimeInterval: 0.7, repeats: true) { [weak self] _ in
            Task { @MainActor [weak self] in
                guard let self, (self.isConnected || (self.gboardSyncEnabled && !self.host.isEmpty)) else { return }
                await self.syncClipboardTick()
            }
        }
    }

    func connect() async {
        errorMessage = nil
        needsPinPrompt = false
        // Only run device-approval pairing handshake for standalone AirBridge TLS (not Gboard mode)
        let isGboardMode = pairedDeviceId.hasPrefix("gb-")
        if !isGboardMode && authToken.isEmpty && !pin.isEmpty && !host.isEmpty {
            let paired = await requestDevicePairingApproval()
            if !paired { return }
        }
        await refreshStatus(silent: false)
        if isConnected {
            await loadFiles(path: currentPath)
            await loadClipboard()
        }
    }

    private func requestDevicePairingApproval() async -> Bool {
        guard let url = URL(string: "\(baseURLString)/api/pair") else { return false }
        isAwaitingPhoneApproval = true
        defer { isAwaitingPhoneApproval = false }

        do {
            var req = URLRequest(url: url)
            req.httpMethod = "POST"
            req.timeoutInterval = 8
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
            let body: [String: Any] = ["pin": pin, "clientId": clientId, "clientName": clientName]
            req.httpBody = try JSONSerialization.data(withJSONObject: body)
            let (data, resp) = try await apiSession.data(for: req)
            guard let http = resp as? HTTPURLResponse else { return false }
            if http.statusCode == 429 {
                errorMessage = "Nhập sai PIN quá 5 lần — bị khóa 60 giây"
                return false
            }
            if http.statusCode == 401 {
                needsPinPrompt = true
                errorMessage = "Sai mã PIN 6 số"
                return false
            }
            guard (200...299).contains(http.statusCode),
                  let pairResp = try? JSONDecoder().decode(PairStatusResponse.self, from: data) else {
                return false
            }
            if pairResp.status == "APPROVED", let token = pairResp.token, !token.isEmpty {
                authToken = token
                return true
            }
            if pairResp.status == "DENIED" {
                errorMessage = "Điện thoại đã từ chối kết nối"
                return false
            }

            // Poll for up to 60 seconds while user taps "Cho phép" on the phone notification
            statusMessage = "⏳ Bấm \"Cho phép\" trên thông báo điện thoại..."
            let encCid = clientId.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? clientId
            for _ in 0..<60 {
                try? await Task.sleep(nanoseconds: 1_000_000_000)
                guard let statusUrl = URL(string: "\(baseURLString)/api/pair/status?clientId=\(encCid)") else { break }
                var sreq = URLRequest(url: statusUrl)
                sreq.timeoutInterval = 5
                sreq.setValue(pin, forHTTPHeaderField: "X-AirBridge-Pin")
                guard let (sdata, sresp) = try? await apiSession.data(for: sreq),
                      let shttp = sresp as? HTTPURLResponse,
                      (200...299).contains(shttp.statusCode),
                      let st = try? JSONDecoder().decode(PairStatusResponse.self, from: sdata) else { continue }
                if st.status == "APPROVED", let token = st.token, !token.isEmpty {
                    authToken = token
                    statusMessage = "Đã được điện thoại chấp nhận (TLS + Token 256-bit)"
                    return true
                }
                if st.status == "DENIED" {
                    errorMessage = "Điện thoại đã từ chối kết nối"
                    return false
                }
            }
            errorMessage = "Hết thời gian chờ xác nhận trên điện thoại"
            return false
        } catch {
            errorMessage = "Lỗi ghép nối: \(error.localizedDescription)"
            return false
        }
    }

    func refreshStatus(silent: Bool) async {
        guard !host.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            isConnected = false
            return
        }
        do {
            let data = try await request(path: "/api/status")
            let status = try JSONDecoder().decode(DeviceStatusResponse.self, from: data)
            isConnected = true
            needsPinPrompt = false
            deviceName = status.deviceName
            modelName = status.modelName
            isScreenStreaming = status.screenStreaming
            isRemoteControlEnabled = status.remoteControlEnabled ?? false
            statusMessage = "Đã kết nối: \(status.deviceName)"
            errorMessage = nil
        } catch let err as NSError {
            if err.code == 401 {
                isConnected = false
                needsPinPrompt = true
                errorMessage = "Sai mã PIN 6 số"
            } else if !silent {
                isConnected = false
                errorMessage = err.localizedDescription
            }
        }
    }

    func loadFiles(path: String) async {
        currentPath = path
        let enc = path.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? path
        do {
            let data = try await request(path: "/api/files?path=\(enc)")
            let resp = try JSONDecoder().decode(FileListResponse.self, from: data)
            files = resp.entries
            errorMessage = nil
        } catch {
            errorMessage = "Không đọc được thư mục: \(error.localizedDescription)"
        }
    }

    func downloadFile(_ entry: PhoneFileEntry) async {
        guard let url = filePreviewURL(path: entry.path, download: true) else { return }
        do {
            var req = URLRequest(url: url)
            applyAuthHeader(to: &req)
            let (tempURL, resp) = try await apiSession.download(for: req)
            guard let http = resp as? HTTPURLResponse, (200...299).contains(http.statusCode) else {
                errorMessage = "Tải tệp thất bại"
                return
            }
            let downloadsDir = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask).first!
            var dest = downloadsDir.appendingPathComponent(entry.name)
            var counter = 1
            while FileManager.default.fileExists(atPath: dest.path) {
                let base = (entry.name as NSString).deletingPathExtension
                let ext = (entry.name as NSString).pathExtension
                let alt = ext.isEmpty ? "\(base)_\(counter)" : "\(base)_\(counter).\(ext)"
                dest = downloadsDir.appendingPathComponent(alt)
                counter += 1
            }
            try FileManager.default.moveItem(at: tempURL, to: dest)
            statusMessage = "Đã tải về: \(dest.lastPathComponent)"
            NSWorkspace.shared.activateFileViewerSelecting([dest])
        } catch {
            errorMessage = "Lỗi tải tệp: \(error.localizedDescription)"
        }
    }

    func uploadFiles(_ urls: [URL], targetDir: String = "Download/AirBridge") async {
        guard !urls.isEmpty, isConnected else { return }
        isUploading = true
        uploadProgress = 0
        defer { isUploading = false }

        for (index, fileURL) in urls.enumerated() {
            let scoped = fileURL.startAccessingSecurityScopedResource()
            defer { if scoped { fileURL.stopAccessingSecurityScopedResource() } }

            let name = fileURL.lastPathComponent
            uploadStatus = "Đang gửi (\(index + 1)/\(urls.count)): \(name)"
            let encDir = targetDir.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? targetDir
            let encName = name.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? name
            guard let url = URL(string: "\(baseURLString)/api/upload?path=\(encDir)&name=\(encName)") else { continue }

            do {
                let fileData = try Data(contentsOf: fileURL)
                var req = URLRequest(url: url)
                req.httpMethod = "POST"
                applyAuthHeader(to: &req)
                req.setValue("application/octet-stream", forHTTPHeaderField: "Content-Type")
                let (_, resp) = try await URLSession.shared.upload(for: req, from: fileData)
                if let http = resp as? HTTPURLResponse, (200...299).contains(http.statusCode) {
                    uploadProgress = Double(index + 1) / Double(urls.count)
                }
            } catch {
                errorMessage = "Lỗi gửi \(name): \(error.localizedDescription)"
            }
        }
        uploadStatus = "Đã gửi xong \(urls.count) tệp vào \(targetDir)"
        statusMessage = uploadStatus
    }

    func loadClipboard() async {
        do {
            let data = try await request(path: "/api/clipboard")
            let resp = try JSONDecoder().decode(ClipboardResponse.self, from: data)
            clipboardHistory = resp.history
            if let newest = resp.history.first, newest.source == "Android", !newest.text.isEmpty {
                if lastAppliedPhoneClipId == -1 {
                    lastAppliedPhoneClipId = newest.id
                } else if autoSyncClipboard && newest.id != lastAppliedPhoneClipId {
                    lastAppliedPhoneClipId = newest.id
                    if newest.isImage == true {
                        if let imgData = try? await request(path: "/api/clipboard/image?id=\(newest.id)"),
                           let nsImage = NSImage(data: imgData) {
                            let pb = NSPasteboard.general
                            pb.clearContents()
                            if let tiff = nsImage.tiffRepresentation,
                               let rep = NSBitmapImageRep(data: tiff),
                               let png = rep.representation(using: .png, properties: [:]) {
                                pb.setData(png, forType: .png)
                                pb.setData(tiff, forType: .tiff)
                            } else {
                                pb.writeObjects([nsImage])
                            }
                            lastMacPasteboardCount = pb.changeCount
                            statusMessage = "Đã nhận ảnh Clipboard từ \(deviceName) (bấm ⌘V để dán)"
                        }
                    } else {
                        lastSyncedText = newest.text
                        NSPasteboard.general.clearContents()
                        NSPasteboard.general.setString(newest.text, forType: .string)
                        lastMacPasteboardCount = NSPasteboard.general.changeCount
                        statusMessage = "Đã nhận Clipboard từ \(deviceName)"
                    }
                }
            } else if lastAppliedPhoneClipId == -1 {
                lastAppliedPhoneClipId = 0
            }
        } catch {}
    }

    func requestScreenCastOnPhone() async {
        guard isConnected else { return }
        _ = try? await request(path: "/api/screen/request", method: "POST")
        statusMessage = "Đã gửi yêu cầu bật màn hình — bấm \"Bắt đầu ngay\" trên điện thoại"
    }

    func requestStorageAccessOnPhone() async {
        guard isConnected else { return }
        _ = try? await request(path: "/api/storage/request", method: "POST")
        statusMessage = "Hãy bật quyền đọc toàn bộ tệp vừa hiện trên điện thoại"
    }

    func requestControlAccessOnPhone() async {
        guard isConnected else { return }
        _ = try? await request(path: "/api/control/request", method: "POST")
        statusMessage = "Hãy bật \"AirBridge Điều khiển từ Mac\" trong Trợ năng trên điện thoại"
    }

    func sendNavAction(_ action: String) async {
        guard isConnected else { return }
        if let payload = try? JSONSerialization.data(withJSONObject: ["type": "action", "action": action]) {
            _ = try? await request(path: "/api/screen/input", method: "POST", body: payload)
        }
    }

    private var gboardBaseURLString: String {
        let cleanHost = host.trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "http://", with: "")
            .replacingOccurrences(of: "https://", with: "")
            .components(separatedBy: ":").first?
            .components(separatedBy: "/").first ?? ""
        return "http://\(cleanHost):\(gboardPort)"
    }

    private func pollGboardClipboard() async {
        guard gboardSyncEnabled, !host.isEmpty,
              let url = URL(string: "\(gboardBaseURLString)/state") else {
            isGboardConnected = false
            return
        }
        var req = URLRequest(url: url)
        req.timeoutInterval = 2.5
        if !gboardPairingCode.isEmpty {
            req.setValue(gboardPairingCode, forHTTPHeaderField: "X-Pairing-Code")
        }
        do {
            let (data, resp) = try await apiSession.data(for: req)
            guard let http = resp as? HTTPURLResponse, (200...299).contains(http.statusCode) else {
                isGboardConnected = false
                return
            }
            let state = try JSONDecoder().decode(GboardStateResponse.self, from: data)
            isGboardConnected = true

            if !state.text.isEmpty {
                if lastGboardUpdatedAtMs == -1 {
                    lastGboardUpdatedAtMs = state.updatedAtMs
                } else if autoSyncClipboard && state.updatedAtMs > lastGboardUpdatedAtMs && state.text != lastSyncedText {
                    lastGboardUpdatedAtMs = state.updatedAtMs
                    lastSyncedText = state.text
                    NSPasteboard.general.clearContents()
                    NSPasteboard.general.setString(state.text, forType: .string)
                    lastMacPasteboardCount = NSPasteboard.general.changeCount
                    statusMessage = "Đã nhận Clipboard tự động từ Gboard"
                }
            } else if lastGboardUpdatedAtMs == -1 {
                lastGboardUpdatedAtMs = 0
            }

            // Also sync phone image clipboard automatically from forked Gboard Web Clipboard!
            if let imgTs = state.imageUpdatedAtMs, imgTs > 0 {
                if lastGboardImageUpdatedAtMs == -1 {
                    lastGboardImageUpdatedAtMs = imgTs
                } else if autoSyncClipboard && imgTs > lastGboardImageUpdatedAtMs {
                    lastGboardImageUpdatedAtMs = imgTs
                    if let imgUrl = URL(string: "\(gboardBaseURLString)/clipboard/image") {
                        var imgReq = URLRequest(url: imgUrl)
                        imgReq.timeoutInterval = 8
                        if !gboardPairingCode.isEmpty {
                            imgReq.setValue(gboardPairingCode, forHTTPHeaderField: "X-Pairing-Code")
                        }
                        if let (imgData, imgResp) = try? await apiSession.data(for: imgReq),
                           let httpImg = imgResp as? HTTPURLResponse,
                           (200...299).contains(httpImg.statusCode),
                           let nsImage = NSImage(data: imgData) {
                            let pb = NSPasteboard.general
                            pb.clearContents()
                            if let tiff = nsImage.tiffRepresentation,
                               let rep = NSBitmapImageRep(data: tiff),
                               let png = rep.representation(using: .png, properties: [:]) {
                                pb.setData(png, forType: .png)
                                pb.setData(tiff, forType: .tiff)
                            } else {
                                pb.writeObjects([nsImage])
                            }
                            lastMacPasteboardCount = pb.changeCount
                            statusMessage = "Đã nhận ảnh tự động từ Gboard (bấm ⌘V để dán)"
                        }
                    }
                }
            } else if lastGboardImageUpdatedAtMs == -1 {
                lastGboardImageUpdatedAtMs = 0
            }
        } catch {
            isGboardConnected = false
        }
    }

    private func pushToGboardClipboard(_ text: String) async -> Bool {
        guard gboardSyncEnabled, !host.isEmpty,
              let url = URL(string: "\(gboardBaseURLString)/clipboard") else { return false }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.timeoutInterval = 3
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        if !gboardPairingCode.isEmpty {
            req.setValue(gboardPairingCode, forHTTPHeaderField: "X-Pairing-Code")
        }
        let body: [String: Any] = ["text": text, "clientId": "airbridge-mac"]
        guard let payload = try? JSONSerialization.data(withJSONObject: body) else { return false }
        req.httpBody = payload
        do {
            let (_, resp) = try await apiSession.data(for: req)
            if let http = resp as? HTTPURLResponse, (200...299).contains(http.statusCode) {
                isGboardConnected = true
                return true
            }
        } catch {}
        return false
    }

    @discardableResult
    func pushClipboardToPhone(_ text: String) async -> Bool {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return false }
        // 1. Prefer Gboard Web Clipboard (direct IME clipboard write — zero notification tap!)
        let gboardOk = await pushToGboardClipboard(trimmed)
        if gboardOk {
            lastSyncedText = trimmed
            statusMessage = "Đã đẩy thẳng Clipboard vào Gboard"
            return true
        }
        // 2. Fallback to AirBridge Android server
        do {
            let payload = try JSONSerialization.data(withJSONObject: ["text": trimmed])
            _ = try await request(path: "/api/clipboard", method: "POST", body: payload)
            lastSyncedText = trimmed
            statusMessage = "Đã gửi Clipboard sang \(deviceName)"
            await loadClipboard()
            return true
        } catch {
            errorMessage = "Lỗi gửi Clipboard: \(error.localizedDescription)"
            return false
        }
    }

    private func pushImageToGboardClipboard(_ pngData: Data) async -> Bool {
        guard gboardSyncEnabled, !host.isEmpty,
              let url = URL(string: "\(gboardBaseURLString)/clipboard/image") else { return false }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.timeoutInterval = 10
        req.setValue("image/png", forHTTPHeaderField: "Content-Type")
        if !gboardPairingCode.isEmpty {
            req.setValue(gboardPairingCode, forHTTPHeaderField: "X-Pairing-Code")
        }
        do {
            let (_, resp) = try await apiSession.upload(for: req, from: pngData)
            if let http = resp as? HTTPURLResponse, (200...299).contains(http.statusCode) {
                isGboardConnected = true
                return true
            }
        } catch {}
        return false
    }

    @discardableResult
    func pushClipboardImageToPhone(_ pngData: Data) async -> Bool {
        guard !pngData.isEmpty else { return false }
        // 1. Push directly to forked Gboard Web Clipboard (direct IME image write)
        let gboardOk = await pushImageToGboardClipboard(pngData)
        // 2. Also push to AirBridge Android server so the MediaStore + tap-to-copy
        //    notification & AirBridge history are always available on ColorOS.
        var airBridgeOk = false
        if let url = URL(string: "\(baseURLString)/api/clipboard/image?ext=png") {
            do {
                var req = URLRequest(url: url)
                req.httpMethod = "POST"
                req.timeoutInterval = 12
                applyAuthHeader(to: &req)
                req.setValue("image/png", forHTTPHeaderField: "Content-Type")
                let (_, resp) = try await apiSession.upload(for: req, from: pngData)
                if let http = resp as? HTTPURLResponse, (200...299).contains(http.statusCode) {
                    airBridgeOk = true
                    await loadClipboard()
                }
            } catch {
                if !gboardOk {
                    errorMessage = "Lỗi gửi ảnh Clipboard: \(error.localizedDescription)"
                }
            }
        }
        if gboardOk || airBridgeOk {
            statusMessage = gboardOk
                ? "Đã đẩy ảnh vào Clipboard Gboard + AirBridge"
                : "Đã gửi ảnh Clipboard sang \(deviceName)"
            return true
        }
        return false
    }

    private func extractPasteboardPngData() -> Data? {
        let pb = NSPasteboard.general

        // 1. If user pressed ⌘C on a file in Finder, pb.data(forType: .png/.tiff) is ONLY
        //    Finder's generic file icon! Read the actual image file from .fileURL instead.
        if let urls = pb.readObjects(forClasses: [NSURL.self], options: [.urlReadingFileURLsOnly: true]) as? [URL],
           let fileURL = urls.first {
            let ext = fileURL.pathExtension.lowercased()
            let imageExts: Set<String> = ["png", "jpg", "jpeg", "webp", "gif", "heic", "heif", "bmp", "tif", "tiff"]
            guard imageExts.contains(ext) else {
                // Non-image file copied in Finder: do NOT send Finder's icon as a clipboard image
                return nil
            }
            if ext == "png", let raw = try? Data(contentsOf: fileURL), !raw.isEmpty {
                return raw
            }
            if let img = NSImage(contentsOf: fileURL),
               let tiff = img.tiffRepresentation,
               let rep = NSBitmapImageRep(data: tiff),
               let png = rep.representation(using: .png, properties: [:]) {
                return png
            }
            return nil
        }

        // 2. Real in-memory image (e.g. ⌘⇧⌃4 screenshot or "Copy Image" from Safari/Chrome/Preview)
        if let png = pb.data(forType: .png), !png.isEmpty {
            return png
        }
        if let tiff = pb.data(forType: .tiff),
           let rep = NSBitmapImageRep(data: tiff),
           let png = rep.representation(using: .png, properties: [:]) {
            return png
        }
        return nil
    }

    private func syncClipboardTick() async {
        if autoSyncClipboard && !isPushingClipboard {
            let currentCount = NSPasteboard.general.changeCount
            if currentCount != lastMacPasteboardCount {
                // 1. Check for copied image / screenshot first (PNG or TIFF)
                if let pngData = extractPasteboardPngData() {
                    isPushingClipboard = true
                    let ok = await pushClipboardImageToPhone(pngData)
                    isPushingClipboard = false
                    if ok {
                        lastMacPasteboardCount = currentCount
                    }
                    return
                }
                // 2. Otherwise check for copied plain text
                if let macText = NSPasteboard.general.string(forType: .string) {
                    let trimmed = macText.trimmingCharacters(in: .whitespacesAndNewlines)
                    if trimmed.isEmpty || trimmed == lastSyncedText {
                        lastMacPasteboardCount = currentCount
                    } else {
                        isPushingClipboard = true
                        let ok = await pushClipboardToPhone(trimmed)
                        isPushingClipboard = false
                        if ok {
                            lastMacPasteboardCount = currentCount
                        }
                        return
                    }
                } else {
                    lastMacPasteboardCount = currentCount
                }
            }
        }
        await pollGboardClipboard()
        await loadClipboard()
    }

    private func request(path: String, method: String = "GET", body: Data? = nil) async throws -> Data {
        guard let url = URL(string: baseURLString + path) else {
            throw NSError(domain: "AirBridge", code: -1, userInfo: [NSLocalizedDescriptionKey: "Địa chỉ không hợp lệ"])
        }
        var req = URLRequest(url: url)
        req.httpMethod = method
        req.timeoutInterval = 8
        applyAuthHeader(to: &req)
        if let body {
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
            req.httpBody = body
        }
        let (data, resp) = try await apiSession.data(for: req)
        guard let http = resp as? HTTPURLResponse else {
            throw NSError(domain: "AirBridge", code: -2, userInfo: [NSLocalizedDescriptionKey: "Không nhận được phản hồi"])
        }
        if http.statusCode == 429 {
            throw NSError(domain: "AirBridge", code: 429, userInfo: [NSLocalizedDescriptionKey: "Bị khóa 60 giây do nhập sai PIN quá nhiều lần"])
        }
        if http.statusCode == 401 {
            authToken = ""
            throw NSError(domain: "AirBridge", code: 401, userInfo: [NSLocalizedDescriptionKey: "Sai mã PIN hoặc phiên hết hạn"])
        }
        guard (200...299).contains(http.statusCode) else {
            throw NSError(domain: "AirBridge", code: http.statusCode, userInfo: [NSLocalizedDescriptionKey: "Lỗi HTTP \(http.statusCode)"])
        }
        return data
    }
}
