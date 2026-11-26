import SwiftUI
import AppKit
import WebKit
import UniformTypeIdentifiers

enum MacTab: String, CaseIterable, Identifiable {
    case drop = "Gửi / Nhận"
    case files = "Ảnh & Tệp"
    case clipboard = "Clipboard 2 chiều"

    var id: String { rawValue }
    var icon: String {
        switch self {
        case .drop: return "arrow.up.arrow.down.circle.fill"
        case .files: return "photo.on.rectangle.angled"
        case .clipboard: return "doc.on.clipboard.fill"
        }
    }
}

struct ContentView: View {
    @ObservedObject var client: AirBridgeMacClient
    @Environment(\.openWindow) private var openWindow
    @State private var selectedTab: MacTab = .drop
    @State private var clipDraft: String = ""
    @State private var isDropTargeted: Bool = false
    @State private var screenReloadToken = UUID()
    @State private var showManualPopover = false
    @State private var showQrPairingSheet = false
    @State private var isGridView = false
    @State private var previewTarget: PreviewSelection? = nil

    private struct PreviewSelection: Identifiable {
        let id = UUID()
        let entries: [PhoneFileEntry]
        let index: Int
    }

    var body: some View {
        NavigationSplitView {
            sidebar
        } detail: {
            VStack(spacing: 0) {
                topConnectionBar
                Divider()
                Group {
                    switch selectedTab {
                    case .drop: dropTabView
                    case .files: filesTabView
                    case .clipboard: clipboardTabView
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .frame(minWidth: 880, minHeight: 580)
        .sheet(isPresented: $showQrPairingSheet) {
            QrPairingSheet(client: client, onClose: { showQrPairingSheet = false })
        }
        .onAppear {
            if !client.host.isEmpty && !client.pin.isEmpty {
                Task { await client.connect() }
            }
        }
    }

    private var sidebar: some View {
        List(selection: $selectedTab) {
            Section("AirBridge (O+ Connect)") {
                ForEach(MacTab.allCases) { tab in
                    Label(tab.rawValue, systemImage: tab.icon)
                        .tag(tab)
                }
            }

            Section("Điện thoại gần đây (Tự tìm)") {
                if client.discoveredPhones.isEmpty {
                    HStack(spacing: 8) {
                        ProgressView().controlSize(.small)
                        Text("Đang tìm AirBridge trên Wi-Fi...")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                } else {
                    ForEach(client.discoveredPhones) { phone in
                        Button {
                            Task { await client.selectPhone(phone) }
                        } label: {
                            HStack(spacing: 8) {
                                Circle()
                                    .fill(client.isConnected && client.host == phone.host ? Color.green : Color.blue)
                                    .frame(width: 8, height: 8)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(phone.name).font(.callout.bold()).lineLimit(1)
                                    Text(phone.model).font(.caption2).foregroundStyle(.secondary).lineLimit(1)
                                }
                            }
                            .padding(.vertical, 2)
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
        }
        .listStyle(.sidebar)
        .navigationSplitViewColumnWidth(min: 210, ideal: 230)
    }

    private var topConnectionBar: some View {
        HStack(spacing: 12) {
            Circle()
                .fill(client.isConnected ? Color.green : Color.orange)
                .frame(width: 10, height: 10)

            VStack(alignment: .leading, spacing: 1) {
                Text(client.isConnected ? client.deviceName : "Chưa kết nối điện thoại")
                    .font(.headline)
                if let err = client.errorMessage {
                    Text(err).font(.caption).foregroundStyle(.red).lineLimit(1)
                } else {
                    Text(client.statusMessage).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
            }

            Spacer()

            if client.isConnected {
                HStack(spacing: 6) {
                    Image(systemName: "keyboard.badge.ellipsis")
                        .foregroundStyle(.green)
                    Text(client.pairedDeviceId.hasPrefix("gb-") ? "Gboard Companion" : "AirBridge")
                        .font(.caption.bold())
                        .foregroundStyle(.secondary)
                }
                .padding(.horizontal, 10)
                .padding(.vertical, 5)
                .background(Color.green.opacity(0.12), in: Capsule())
            }

            if client.needsPinPrompt || (!client.isConnected && !client.host.isEmpty) {
                TextField("PIN 6 số", text: $client.pin)
                    .textFieldStyle(.roundedBorder)
                    .frame(width: 100)
                Button("Ghép nối") {
                    Task { await client.connect() }
                }
                .buttonStyle(.borderedProminent)
            }

            Button {
                showQrPairingSheet = true
            } label: {
                Label("Quét QR", systemImage: "qrcode")
            }
            .buttonStyle(.bordered)
            .help("Hiển thị mã QR để điện thoại quét ghép nối 1 lần duy nhất")

            Button {
                showManualPopover.toggle()
            } label: {
                Image(systemName: "gearshape")
            }
            .buttonStyle(.bordered)
            .popover(isPresented: $showManualPopover) {
                VStack(alignment: .leading, spacing: 10) {
                    Text("Kết nối thủ công (Dự phòng)")
                        .font(.headline)
                    TextField("IP điện thoại", text: $client.host)
                        .textFieldStyle(.roundedBorder)
                    TextField("PIN 6 số", text: $client.pin)
                        .textFieldStyle(.roundedBorder)
                    Button("Kết nối") {
                        showManualPopover = false
                        Task { await client.connect() }
                    }
                    .buttonStyle(.borderedProminent)
                }
                .padding(16)
                .frame(width: 240)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .background(.bar)
    }

    // MARK: - Tab 1: Drop
    private var dropTabView: some View {
        VStack(spacing: 20) {
            ZStack {
                RoundedRectangle(cornerRadius: 20)
                    .strokeBorder(
                        isDropTargeted ? Color.accentColor : Color.secondary.opacity(0.35),
                        style: StrokeStyle(lineWidth: 2, dash: [8])
                    )
                    .background(
                        RoundedRectangle(cornerRadius: 20)
                            .fill(isDropTargeted ? Color.accentColor.opacity(0.12) : Color.secondary.opacity(0.05))
                    )

                VStack(spacing: 12) {
                    Image(systemName: "tray.and.arrow.up.fill")
                        .font(.system(size: 46))
                        .foregroundStyle(Color.accentColor)
                    Text("Kéo thả ảnh, video hoặc tệp từ Finder vào đây")
                        .font(.title3.bold())
                    Text("Tệp sẽ bắn thẳng vào Download/AirBridge trên \(client.deviceName)")
                        .font(.callout)
                        .foregroundStyle(.secondary)

                    Button("Chọn tệp từ Mac...") {
                        pickAndUploadFiles(to: "Download/AirBridge")
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(!client.isConnected || client.isUploading)
                }
                .padding(36)
            }
            .frame(maxWidth: 640, maxHeight: 300)
            .onDrop(of: [.fileURL], isTargeted: $isDropTargeted) { providers in
                handleDrop(providers: providers, targetDir: "Download/AirBridge")
            }

            if client.isUploading || !client.uploadStatus.isEmpty {
                VStack(spacing: 6) {
                    if client.isUploading {
                        ProgressView(value: client.uploadProgress)
                            .frame(maxWidth: 420)
                    }
                    Text(client.uploadStatus)
                        .font(.callout)
                        .foregroundStyle(.secondary)
                }
            }
        }
        .padding(24)
    }

    // MARK: - Tab 2: Files
    private var filesTabView: some View {
        VStack(spacing: 0) {
            HStack(spacing: 8) {
                shortcutButton("Tất cả", "")
                shortcutButton("Ảnh & Camera", "DCIM/Camera")
                shortcutButton("Screenshots", "Pictures")
                shortcutButton("Download", "Download/AirBridge")
                Spacer()
                Picker("", selection: $isGridView) {
                    Image(systemName: "list.bullet").tag(false)
                    Image(systemName: "square.grid.2x2").tag(true)
                }
                .pickerStyle(.segmented)
                .frame(width: 74)
                .help("Chuyển chế độ Danh sách / Lưới xem trước ảnh")

                Button("Tải lên thư mục này") {
                    pickAndUploadFiles(to: client.currentPath.isEmpty ? "Download/AirBridge" : client.currentPath)
                }
                .disabled(!client.isConnected)
                Button {
                    Task { await client.loadFiles(path: client.currentPath) }
                } label: {
                    Image(systemName: "arrow.clockwise")
                }
            }
            .padding(12)

            Divider()

            HStack {
                if !client.currentPath.isEmpty {
                    Button {
                        let parent = (client.currentPath as NSString).deletingLastPathComponent
                        Task { await client.loadFiles(path: parent) }
                    } label: {
                        Label("Lên 1 cấp", systemImage: "chevron.left")
                    }
                    .buttonStyle(.bordered)
                }
                Text("/\(client.currentPath)")
                    .font(.caption.monospaced())
                    .foregroundStyle(.secondary)
                Spacer()
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 6)

            if isGridView {
                ScrollView {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 145, maximum: 180), spacing: 12)], spacing: 12) {
                        ForEach(client.files) { entry in
                            VStack(spacing: 6) {
                                RemoteThumbnailView(
                                    url: entry.isDir ? nil : client.filePreviewURL(path: entry.path),
                                    kind: RemoteMediaKind.from(fileName: entry.name),
                                    isDir: entry.isDir,
                                    size: CGSize(width: 140, height: 112)
                                )
                                Text(entry.name)
                                    .font(.caption)
                                    .lineLimit(1)
                                    .truncationMode(.middle)
                                Text(entry.isDir ? "Thư mục" : formatBytes(entry.size))
                                    .font(.caption2)
                                    .foregroundStyle(.secondary)
                            }
                            .padding(8)
                            .background(Color.secondary.opacity(0.08), in: RoundedRectangle(cornerRadius: 12))
                            .contentShape(Rectangle())
                            .onTapGesture {
                                if entry.isDir {
                                    Task { await client.loadFiles(path: entry.path) }
                                } else {
                                    openMediaPreview(for: entry)
                                }
                            }
                            .contextMenu {
                                if !entry.isDir {
                                    Button("Xem trước") { openMediaPreview(for: entry) }
                                    Button("Tải về Mac") { Task { await client.downloadFile(entry) } }
                                }
                            }
                        }
                    }
                    .padding(12)
                }
            } else {
                List(client.files) { entry in
                    HStack(spacing: 12) {
                        RemoteThumbnailView(
                            url: entry.isDir ? nil : client.filePreviewURL(path: entry.path),
                            kind: RemoteMediaKind.from(fileName: entry.name),
                            isDir: entry.isDir,
                            size: CGSize(width: 40, height: 40)
                        )
                        VStack(alignment: .leading) {
                            Text(entry.name).font(.body).lineLimit(1)
                            Text(entry.isDir ? "Thư mục" : formatBytes(entry.size))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        Spacer()
                        if entry.isDir {
                            Button("Mở") {
                                Task { await client.loadFiles(path: entry.path) }
                            }
                            .buttonStyle(.bordered)
                        } else {
                            Button("Xem") {
                                openMediaPreview(for: entry)
                            }
                            .buttonStyle(.bordered)

                            Button("Tải về") {
                                Task { await client.downloadFile(entry) }
                            }
                            .buttonStyle(.borderedProminent)
                        }
                    }
                    .padding(.vertical, 2)
                    .contentShape(Rectangle())
                    .onTapGesture(count: 2) {
                        if entry.isDir {
                            Task { await client.loadFiles(path: entry.path) }
                        } else {
                            openMediaPreview(for: entry)
                        }
                    }
                }
            }
        }
        .sheet(item: $previewTarget) { selection in
            MediaPreviewSheet(
                client: client,
                entries: selection.entries,
                currentIndex: selection.index,
                onClose: { previewTarget = nil }
            )
        }
        .onAppear {
            if client.isConnected {
                Task { await client.loadFiles(path: client.currentPath) }
            }
        }
    }

    private func openMediaPreview(for entry: PhoneFileEntry) {
        let nonDirs = client.files.filter { !$0.isDir }
        let idx = nonDirs.firstIndex(where: { $0.id == entry.id }) ?? 0
        previewTarget = PreviewSelection(entries: nonDirs.isEmpty ? [entry] : nonDirs, index: idx)
    }

    // MARK: - Tab 3: Clipboard
    private var clipboardTabView: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack {
                Toggle("Tự động đồng bộ Clipboard 2 chiều (Copy trên Mac → tự qua điện thoại & ngược lại)", isOn: $client.autoSyncClipboard)
                    .toggleStyle(.switch)
                Spacer()
                Button("Làm mới") {
                    Task { await client.loadClipboard() }
                }
            }

            VStack(alignment: .leading, spacing: 8) {
                HStack(spacing: 10) {
                    Circle()
                        .fill(client.isGboardConnected ? Color.green : Color.secondary)
                        .frame(width: 8, height: 8)
                    Toggle("Đồng bộ trực tiếp qua Gboard Web Clipboard (100% tự động, không cần Trợ năng)", isOn: $client.gboardSyncEnabled)
                        .toggleStyle(.switch)
                }
                if client.gboardSyncEnabled {
                    HStack(spacing: 10) {
                        Text("Port:").font(.caption).foregroundStyle(.secondary)
                        TextField("8080", value: $client.gboardPort, format: .number)
                            .textFieldStyle(.roundedBorder)
                            .frame(width: 70)
                        Text("Mã ghép nối Gboard (nếu bật):").font(.caption).foregroundStyle(.secondary)
                        TextField("4 số", text: $client.gboardPairingCode)
                            .textFieldStyle(.roundedBorder)
                            .frame(width: 90)
                        Text(client.isGboardConnected ? "🟢 Đã kết nối Gboard" : "⚪ Hãy bật Web Clipboard trong cài đặt Gboard")
                            .font(.caption)
                            .foregroundStyle(client.isGboardConnected ? .green : .secondary)
                    }
                }
            }
            .padding(10)
            .background(Color.secondary.opacity(0.08))
            .clipShape(RoundedRectangle(cornerRadius: 10))

            VStack(alignment: .leading, spacing: 8) {
                Text("Gửi nhanh văn bản / link sang điện thoại:")
                    .font(.headline)
                TextEditor(text: $clipDraft)
                    .font(.body)
                    .frame(height: 90)
                    .overlay(RoundedRectangle(cornerRadius: 8).stroke(Color.secondary.opacity(0.3)))
                HStack {
                    Button("Gửi sang Clipboard điện thoại") {
                        let text = clipDraft
                        Task { await client.pushClipboardToPhone(text) }
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(!client.isConnected || clipDraft.isEmpty)

                    Button("Gửi Clipboard hiện tại của Mac") {
                        if let str = NSPasteboard.general.string(forType: .string) {
                            clipDraft = str
                            Task { await client.pushClipboardToPhone(str) }
                        }
                    }
                    .disabled(!client.isConnected)
                }
            }

            Divider()

            Text("Lịch sử Clipboard đồng bộ")
                .font(.headline)

            List(client.clipboardHistory) { item in
                HStack(alignment: .top, spacing: 12) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(item.source)
                            .font(.caption.bold())
                            .foregroundStyle(item.source == "Android" ? .green : .blue)
                        Text(item.text)
                            .font(.body)
                            .textSelection(.enabled)
                    }
                    Spacer()
                    Button("Copy") {
                        NSPasteboard.general.clearContents()
                        NSPasteboard.general.setString(item.text, forType: .string)
                        client.statusMessage = "Đã copy vào Clipboard của Mac"
                    }
                    .buttonStyle(.bordered)
                }
                .padding(.vertical, 4)
            }
        }
        .padding(18)
        .onAppear {
            if client.isConnected {
                Task { await client.loadClipboard() }
            }
        }
    }

    // MARK: - Tab 4: Screen Mirror
    private var screenTabView: some View {
        VStack(spacing: 12) {
            HStack {
                VStack(alignment: .leading) {
                    Text("Truyền màn hình điện thoại trực tiếp")
                        .font(.headline)
                    Text(client.isScreenStreaming ? "Đang nhận hình ảnh trực tiếp từ \(client.deviceName)" : "Bấm \"Yêu cầu bật màn hình\" — điện thoại sẽ tự bật hộp thoại xác nhận.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                if !client.isScreenStreaming {
                    Button("Yêu cầu bật màn hình") {
                        Task { await client.requestScreenCastOnPhone() }
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(!client.isConnected)
                }
                Button {
                    openWindow(id: "phone-mirror")
                } label: {
                    Label("Tách cửa sổ riêng", systemImage: "macwindow.badge.plus")
                }
                .buttonStyle(.borderedProminent)
                .disabled(!client.isConnected)
                Button("Làm mới") {
                    screenReloadToken = UUID()
                }
                .buttonStyle(.bordered)
            }
            .padding(.horizontal, 16)
            .padding(.top, 12)

            if client.isConnected && client.isScreenStreaming, let url = client.screenStreamURL() {
                ScreenWebView(
                    url: url,
                    inputEndpoint: "\(client.baseURLString)/api/screen/input?pin=\(client.pin)"
                )
                .id(screenReloadToken)
                .clipShape(RoundedRectangle(cornerRadius: 14))
                .padding(.horizontal, 16)
                .padding(.bottom, 16)
            } else {
                Spacer()
                VStack(spacing: 10) {
                    Image(systemName: "iphone.radiowaves.left.and.right")
                        .font(.system(size: 44))
                        .foregroundStyle(.secondary)
                    Text(client.isConnected ? "Đang chờ xác nhận chia sẻ màn hình trên \(client.deviceName)..." : "Chưa kết nối tới điện thoại")
                        .foregroundStyle(.secondary)
                    if client.isConnected {
                        Button("Gửi lại yêu cầu lên điện thoại") {
                            Task { await client.requestScreenCastOnPhone() }
                        }
                        .buttonStyle(.borderedProminent)
                    }
                }
                Spacer()
            }
        }
        .onAppear {
            if client.isConnected && !client.isScreenStreaming {
                Task { await client.requestScreenCastOnPhone() }
            }
        }
        .onChange(of: client.isScreenStreaming) { _, streaming in
            if streaming {
                screenReloadToken = UUID()
            }
        }
    }

    private func shortcutButton(_ title: String, _ path: String) -> some View {
        Button(title) {
            Task { await client.loadFiles(path: path) }
        }
        .buttonStyle(.bordered)
        .disabled(!client.isConnected)
    }

    private func pickAndUploadFiles(to targetDir: String) {
        let panel = NSOpenPanel()
        panel.allowsMultipleSelection = true
        panel.canChooseDirectories = false
        panel.canChooseFiles = true
        if panel.runModal() == .OK {
            Task { await client.uploadFiles(panel.urls, targetDir: targetDir) }
        }
    }

    private func handleDrop(providers: [NSItemProvider], targetDir: String) -> Bool {
        var collected: [URL] = []
        let group = DispatchGroup()
        for p in providers {
            group.enter()
            p.loadItem(forTypeIdentifier: UTType.fileURL.identifier, options: nil) { item, _ in
                if let data = item as? Data, let url = URL(dataRepresentation: data, relativeTo: nil) {
                    collected.append(url)
                } else if let url = item as? URL {
                    collected.append(url)
                }
                group.leave()
            }
        }
        group.notify(queue: .main) {
            Task { await client.uploadFiles(collected, targetDir: targetDir) }
        }
        return true
    }

    private func iconFor(_ entry: PhoneFileEntry) -> String {
        if entry.isDir { return "folder.fill" }
        if entry.mime.hasPrefix("image/") { return "photo.fill" }
        if entry.mime.hasPrefix("video/") { return "film.fill" }
        if entry.mime == "application/pdf" { return "doc.richtext.fill" }
        return "doc.fill"
    }

    private func formatBytes(_ bytes: Int64) -> String {
        let fmt = ByteCountFormatter()
        fmt.countStyle = .file
        return fmt.string(fromByteCount: bytes)
    }
}

struct PhoneMirrorWindowView: View {
    @ObservedObject var client: AirBridgeMacClient
    @State private var reloadToken = UUID()
    @State private var alwaysOnTop = false
    @State private var isHovering = false

    var body: some View {
        VStack(spacing: 0) {
            // Compact drag bar like iPhone Mirroring
            HStack(spacing: 8) {
                Circle()
                    .fill(client.isScreenStreaming ? Color.green : Color.orange)
                    .frame(width: 8, height: 8)
                Text(client.isConnected ? client.deviceName : "Chưa kết nối")
                    .font(.caption.bold())
                    .foregroundStyle(.white.opacity(0.9))
                Spacer()

                Button {
                    alwaysOnTop.toggle()
                    setWindowFloating(alwaysOnTop)
                } label: {
                    Image(systemName: alwaysOnTop ? "pin.fill" : "pin")
                        .foregroundStyle(alwaysOnTop ? Color.accentColor : .white.opacity(0.75))
                }
                .buttonStyle(.plain)
                .help("Ghim luôn nổi trên cùng")

                Button {
                    reloadToken = UUID()
                    if !client.isScreenStreaming {
                        Task { await client.requestScreenCastOnPhone() }
                    }
                } label: {
                    Image(systemName: "arrow.clockwise")
                        .foregroundStyle(.white.opacity(0.75))
                }
                .buttonStyle(.plain)
                .help("Làm mới luồng màn hình")
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
            .background(Color(red: 0.06, green: 0.08, blue: 0.13))

            // Phone screen canvas
            ZStack {
                Color(red: 0.02, green: 0.03, blue: 0.06)
                if client.isConnected && client.isScreenStreaming, let url = client.screenStreamURL() {
                    ScreenWebView(
                        url: url,
                        inputEndpoint: "\(client.baseURLString)/api/screen/input?pin=\(client.pin)"
                    )
                    .id(reloadToken)
                } else {
                    VStack(spacing: 12) {
                        Image(systemName: "iphone.gen3")
                            .font(.system(size: 48))
                            .foregroundStyle(.white.opacity(0.35))
                        Text(client.isConnected ? "Đang chờ xác nhận trên \(client.deviceName)..." : "Chưa kết nối điện thoại")
                            .font(.callout)
                            .foregroundStyle(.white.opacity(0.7))
                            .multilineTextAlignment(.center)
                        if client.isConnected {
                            Button("Bật truyền màn hình") {
                                Task { await client.requestScreenCastOnPhone() }
                            }
                            .buttonStyle(.borderedProminent)
                        }
                    }
                    .padding(24)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)

            // Sleek slim navigation bar at bottom
            HStack(spacing: 28) {
                navIconButton("chevron.left", "Quay lại") {
                    Task { await client.sendNavAction("back") }
                }
                navIconButton("circle", "Trang chủ") {
                    Task { await client.sendNavAction("home") }
                }
                navIconButton("square", "Đa nhiệm") {
                    Task { await client.sendNavAction("recents") }
                }
                navIconButton("bell", "Thông báo") {
                    Task { await client.sendNavAction("notifications") }
                }
            }
            .padding(.vertical, 10)
            .frame(maxWidth: .infinity)
            .background(Color(red: 0.06, green: 0.08, blue: 0.13))
        }
        .frame(minWidth: 340, idealWidth: 380, maxWidth: 520, minHeight: 700, idealHeight: 820, maxHeight: 1080)
        .onAppear {
            if client.isConnected && !client.isScreenStreaming {
                Task { await client.requestScreenCastOnPhone() }
            }
        }
        .onChange(of: client.isScreenStreaming) { _, streaming in
            if streaming {
                reloadToken = UUID()
            }
        }
    }

    private func navIconButton(_ icon: String, _ tooltip: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: icon)
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(.white.opacity(0.85))
                .frame(width: 34, height: 26)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .help(tooltip)
    }

    private func setWindowFloating(_ floating: Bool) {
        if let win = NSApp.windows.first(where: { $0.title == "OnePlus Mirror" || $0.identifier?.rawValue.contains("phone-mirror") == true }) {
            win.level = floating ? .floating : .normal
        }
    }
}

struct ScreenWebView: NSViewRepresentable {
    let url: URL
    let inputEndpoint: String

    func makeNSView(context: Context) -> WKWebView {
        let webView = WKWebView()
        webView.setValue(false, forKey: "drawsBackground")
        let html = """
        <!DOCTYPE html>
        <html>
        <body style="margin:0;background:#050810;display:flex;align-items:center;justify-content:center;height:100vh;overflow:hidden;user-select:none;-webkit-user-select:none;">
        <img id="screen" src="\(url.absoluteString)" draggable="false"
             style="max-width:100%;max-height:100vh;object-fit:contain;border-radius:12px;cursor:pointer;"
             onerror="document.body.innerHTML='<div style=color:#94a3b8;font-family:-apple-system;text-align:center>Đang chờ luồng màn hình...</div>'"/>
        <script>
        const endpoint = "\(inputEndpoint)";
        const img = document.getElementById('screen');
        let down = null;
        function sendInput(obj) {
          fetch(endpoint, {
            method: 'POST',
            headers: {'Content-Type':'application/json'},
            body: JSON.stringify(obj)
          }).catch(() => {});
        }
        function normCoords(e) {
          const r = img.getBoundingClientRect();
          const x = Math.max(0, Math.min(1, (e.clientX - r.left) / r.width));
          const y = Math.max(0, Math.min(1, (e.clientY - r.top) / r.height));
          return {x, y};
        }
        img.addEventListener('mousedown', (e) => {
          e.preventDefault();
          const p = normCoords(e);
          down = {x: p.x, y: p.y, t: Date.now()};
        });
        window.addEventListener('mouseup', (e) => {
          if (!down) return;
          const p = normCoords(e);
          const dx = p.x - down.x;
          const dy = p.y - down.y;
          const dist = Math.hypot(dx, dy);
          const dur = Math.max(80, Math.min(800, Date.now() - down.t));
          if (dist < 0.025) {
            sendInput({type:'tap', x: p.x, y: p.y});
          } else {
            sendInput({type:'swipe', x1: down.x, y1: down.y, x2: p.x, y2: p.y, durationMs: dur});
          }
          down = null;
        });
        let wheelCooldown = 0;
        img.addEventListener('wheel', (e) => {
          e.preventDefault();
          const now = Date.now();
          if (now - wheelCooldown < 260) return;
          wheelCooldown = now;
          const p = normCoords(e);
          const dir = e.deltaY > 0 ? -0.25 : 0.25;
          const y2 = Math.max(0.08, Math.min(0.92, p.y + dir));
          sendInput({type:'swipe', x1: p.x, y1: p.y, x2: p.x, y2: y2, durationMs: 180});
        }, {passive: false});
        </script>
        </body></html>
        """
        webView.loadHTMLString(html, baseURL: url)
        return webView
    }

    func updateNSView(_ nsView: WKWebView, context: Context) {}
}
