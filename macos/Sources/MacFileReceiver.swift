import Foundation
import Network
import AppKit
import UserNotifications

final class MacFileReceiver {
    private var listener: NWListener?
    private let queue = DispatchQueue(label: "com.airbridge.mac.receiver", qos: .userInitiated)
    private(set) var port: Int = 8773

    var onFileReceived: ((URL, String, Int64) -> Void)?
    var onTextReceived: ((String) -> Void)?

    func start() {
        stop()
        do {
            let params = NWParameters.tcp
            params.allowLocalEndpointReuse = true
            let l = try NWListener(using: params, on: NWEndpoint.Port(rawValue: 8773) ?? .any)
            setupListener(l)
        } catch {
            if let fallback = try? NWListener(using: .tcp, on: .any) {
                setupListener(fallback)
            }
        }
    }

    private func setupListener(_ l: NWListener) {
        listener = l
        l.stateUpdateHandler = { [weak self] state in
            if case .ready = state, let actual = l.port?.rawValue {
                self?.port = Int(actual)
            }
        }
        l.newConnectionHandler = { [weak self] conn in
            self?.handleConnection(conn)
        }
        l.start(queue: queue)
    }

    func stop() {
        listener?.cancel()
        listener = nil
    }

    private func handleConnection(_ conn: NWConnection) {
        conn.start(queue: queue)
        let session = ReceiveSession(
            conn: conn,
            queue: queue,
            onFileReceived: onFileReceived,
            onTextReceived: onTextReceived
        )
        session.begin()
    }
}

private final class ReceiveSession {
    private let conn: NWConnection
    private let queue: DispatchQueue
    private let onFileReceived: ((URL, String, Int64) -> Void)?
    private let onTextReceived: ((String) -> Void)?

    private var headerBuffer = Data()
    private var headersParsed = false
    private var isTextMode = false
    private var expectedLength: Int64 = -1
    private var bytesReceived: Int64 = 0
    private var textBuffer = Data()
    private var fileHandle: FileHandle?
    private var targetURL: URL?
    private var targetFileName: String = "file.bin"
    private var finished = false

    init(
        conn: NWConnection,
        queue: DispatchQueue,
        onFileReceived: ((URL, String, Int64) -> Void)?,
        onTextReceived: ((String) -> Void)?
    ) {
        self.conn = conn
        self.queue = queue
        self.onFileReceived = onFileReceived
        self.onTextReceived = onTextReceived
    }

    func begin() {
        receiveNext()
    }

    private func receiveNext() {
        conn.receive(minimumIncompleteLength: 1, maximumLength: 65536) { data, _, isComplete, error in
            guard !self.finished else { return }

            if let chunk = data, !chunk.isEmpty {
                self.processChunk(chunk)
            }

            if self.finished { return }

            if self.headersParsed && self.expectedLength >= 0 && self.bytesReceived >= self.expectedLength {
                self.completeSuccess()
                return
            }

            if isComplete || error != nil {
                if self.headersParsed && (self.expectedLength < 0 || self.bytesReceived >= self.expectedLength) {
                    self.completeSuccess()
                } else {
                    self.abortWithError(status: "400 Bad Request", message: "incomplete_body")
                }
                return
            }

            self.receiveNext()
        }
    }

    private func processChunk(_ chunk: Data) {
        if !headersParsed {
            headerBuffer.append(chunk)
            let sep = Data("\r\n\r\n".utf8)
            guard let range = headerBuffer.range(of: sep) else {
                if headerBuffer.count > 16384 {
                    abortWithError(status: "431 Request Header Fields Too Large", message: "headers_too_large")
                }
                return
            }
            let headerData = headerBuffer.subdata(in: 0..<range.lowerBound)
            let bodyStart = headerBuffer.subdata(in: range.upperBound..<headerBuffer.count)
            headerBuffer.removeAll(keepingCapacity: false)

            guard let headerStr = String(data: headerData, encoding: .utf8) else {
                abortWithError(status: "400 Bad Request", message: "bad_headers")
                return
            }
            let lines = headerStr.components(separatedBy: "\r\n")
            guard let requestLine = lines.first else {
                abortWithError(status: "400 Bad Request", message: "empty_request")
                return
            }
            let parts = requestLine.split(separator: " ")
            guard parts.count >= 2 else {
                abortWithError(status: "400 Bad Request", message: "invalid_request_line")
                return
            }
            let method = String(parts[0]).uppercased()
            let rawTarget = String(parts[1])

            if method == "OPTIONS" {
                sendResponse(status: "204 No Content", body: "")
                return
            }

            var headers: [String: String] = [:]
            for line in lines.dropFirst() {
                if let colon = line.firstIndex(of: ":") {
                    let k = line[..<colon].trimmingCharacters(in: .whitespaces).lowercased()
                    let v = line[line.index(after: colon)...].trimmingCharacters(in: .whitespaces)
                    headers[k] = v
                }
            }
            if let cl = headers["content-length"], let len = Int64(cl) {
                expectedLength = len
            }

            let comps = URLComponents(string: "http://localhost" + rawTarget)
            let path = comps?.path ?? rawTarget

            if method == "POST" && path == "/receive-text" {
                isTextMode = true
                headersParsed = true
                if !bodyStart.isEmpty {
                    appendBody(bodyStart)
                }
                return
            }

            if method == "POST" && path == "/receive" {
                let rawName = comps?.queryItems?.first(where: { $0.name == "name" })?.value ?? "shared_\(Int(Date().timeIntervalSince1970)).bin"
                let sanitized = sanitizeFileName(rawName)
                guard let (url, fh) = openDestinationFile(named: sanitized) else {
                    abortWithError(status: "500 Internal Server Error", message: "cannot_create_file")
                    return
                }
                targetURL = url
                targetFileName = url.lastPathComponent
                fileHandle = fh
                headersParsed = true
                if !bodyStart.isEmpty {
                    appendBody(bodyStart)
                }
                return
            }

            abortWithError(status: "404 Not Found", message: "not_found")
        } else {
            appendBody(chunk)
        }
    }

    private func appendBody(_ data: Data) {
        var toWrite = data
        if expectedLength >= 0 {
            let remaining = expectedLength - bytesReceived
            if remaining <= 0 { return }
            if Int64(toWrite.count) > remaining {
                toWrite = toWrite.prefix(Int(remaining))
            }
        }
        if isTextMode {
            if textBuffer.count + toWrite.count <= 2 * 1024 * 1024 {
                textBuffer.append(toWrite)
            }
        } else {
            do {
                try fileHandle?.write(contentsOf: toWrite)
            } catch {
                abortWithError(status: "500 Internal Server Error", message: "write_failed")
                return
            }
        }
        bytesReceived += Int64(toWrite.count)
    }

    private func completeSuccess() {
        guard !finished else { return }
        finished = true
        try? fileHandle?.close()
        fileHandle = nil

        if isTextMode {
            let text = String(data: textBuffer, encoding: .utf8) ?? ""
            sendResponse(status: "200 OK", body: "{\"ok\":true}")
            if !text.isEmpty {
                DispatchQueue.main.async {
                    NSPasteboard.general.clearContents()
                    NSPasteboard.general.setString(text, forType: .string)
                    self.onTextReceived?(text)
                }
            }
        } else if let url = targetURL {
            let size = bytesReceived
            let name = targetFileName
            sendResponse(status: "200 OK", body: "{\"ok\":true,\"fileName\":\"\(name.replacingOccurrences(of: "\"", with: "_"))\",\"size\":\(size)}")
            DispatchQueue.main.async {
                NSSound(named: NSSound.Name("Glass"))?.play()
                self.onFileReceived?(url, name, size)
            }
        } else {
            sendResponse(status: "200 OK", body: "{\"ok\":true}")
        }
    }

    private func abortWithError(status: String, message: String) {
        guard !finished else { return }
        finished = true
        try? fileHandle?.close()
        fileHandle = nil
        if let url = targetURL {
            try? FileManager.default.removeItem(at: url)
        }
        sendResponse(status: status, body: "{\"ok\":false,\"error\":\"\(message)\"}")
    }

    private func sendResponse(status: String, body: String) {
        let bodyData = Data(body.utf8)
        let resp = "HTTP/1.1 \(status)\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: \(bodyData.count)\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n\(body)"
        conn.send(content: Data(resp.utf8), completion: .contentProcessed { _ in
            self.conn.cancel()
        })
    }

    private func sanitizeFileName(_ raw: String) -> String {
        let base = (raw as NSString).lastPathComponent
        let cleaned = base.replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: ":", with: "_")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return cleaned.isEmpty ? "file_\(Int(Date().timeIntervalSince1970)).bin" : cleaned
    }

    private func openDestinationFile(named name: String) -> (URL, FileHandle)? {
        let downloads = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask).first
            ?? FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Downloads")
        let dir = downloads.appendingPathComponent("AirBridge", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)

        var candidate = dir.appendingPathComponent(name)
        if FileManager.default.fileExists(atPath: candidate.path) {
            let ext = candidate.pathExtension
            let stem = candidate.deletingPathExtension().lastPathComponent
            var idx = 1
            while FileManager.default.fileExists(atPath: candidate.path) && idx < 999 {
                let nextName = ext.isEmpty ? "\(stem) (\(idx))" : "\(stem) (\(idx)).\(ext)"
                candidate = dir.appendingPathComponent(nextName)
                idx += 1
            }
        }
        guard FileManager.default.createFile(atPath: candidate.path, contents: nil),
              let fh = try? FileHandle(forWritingTo: candidate) else {
            return nil
        }
        return (candidate, fh)
    }
}
