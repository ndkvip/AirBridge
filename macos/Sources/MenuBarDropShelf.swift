import SwiftUI
import AppKit

@MainActor
final class DropShelfState: ObservableObject {
    @Published var isVisible: Bool = false
    @Published var isTargeted: Bool = false
    @Published var hoveredFileCount: Int = 0
    @Published var isSending: Bool = false
    @Published var finishedMessage: String? = nil
}

/// Floating frosted-glass drop shelf that expands beneath AirBridge's Menu Bar icon
/// whenever the user drags files anywhere on macOS. Requires zero Accessibility permissions.
@MainActor
final class MenuBarDropShelfController {
    private let client: AirBridgeMacClient
    private let state = DropShelfState()
    private var panel: NSPanel?
    private var globalMonitor: Any?
    private var localMonitor: Any?
    private var pollTimer: Timer?
    private var lastSeenDragChangeCount: Int = NSPasteboard(name: .drag).changeCount
    private var isDragSessionActive: Bool = false
    private var hideWorkItem: DispatchWorkItem?

    private let panelWidth: CGFloat = 290
    private let panelHeight: CGFloat = 76

    init(client: AirBridgeMacClient) {
        self.client = client
        setupPanel()
        startMonitoring()
    }

    deinit {
        if let globalMonitor { NSEvent.removeMonitor(globalMonitor) }
        if let localMonitor { NSEvent.removeMonitor(localMonitor) }
        pollTimer?.invalidate()
    }

    private func setupPanel() {
        let p = NSPanel(
            contentRect: NSRect(x: 0, y: 0, width: panelWidth, height: panelHeight),
            styleMask: [.borderless, .nonactivatingPanel],
            backing: .buffered,
            defer: false
        )
        p.level = .statusBar
        p.isOpaque = false
        p.backgroundColor = .clear
        p.hasShadow = false
        p.isMovable = false
        p.hidesOnDeactivate = false
        p.ignoresMouseEvents = false
        p.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .ignoresCycle]

        let dropView = FileDropContainerView(
            frame: NSRect(x: 0, y: 0, width: panelWidth, height: panelHeight),
            onHoverChanged: { [weak self] hovering, count in
                Task { @MainActor in
                    self?.state.isTargeted = hovering
                    self?.state.hoveredFileCount = count
                }
            },
            onFilesDropped: { [weak self] urls in
                Task { @MainActor in
                    self?.handleDroppedFiles(urls)
                }
            }
        )

        let hosting = NSHostingView(rootView: MenuBarDropShelfView(client: client, state: state))
        hosting.frame = dropView.bounds
        hosting.autoresizingMask = [.width, .height]
        dropView.addSubview(hosting)

        p.contentView = dropView
        self.panel = p
    }

    private func startMonitoring() {
        globalMonitor = NSEvent.addGlobalMonitorForEvents(matching: [.leftMouseDragged, .leftMouseUp]) { [weak self] event in
            Task { @MainActor in
                self?.handleMouseEvent(event.type)
            }
        }
        localMonitor = NSEvent.addLocalMonitorForEvents(matching: [.leftMouseDragged, .leftMouseUp]) { [weak self] event in
            Task { @MainActor in
                self?.handleMouseEvent(event.type)
            }
            return event
        }
        pollTimer = Timer.scheduledTimer(withTimeInterval: 0.14, repeats: true) { [weak self] _ in
            Task { @MainActor in
                self?.pollDragState()
            }
        }
    }

    private func handleMouseEvent(_ type: NSEvent.EventType) {
        if type == .leftMouseUp {
            if isDragSessionActive && !state.isSending && state.finishedMessage == nil {
                isDragSessionActive = false
                hideShelf()
            }
            return
        }
        pollDragState()
    }

    private func pollDragState() {
        let mouseDown = (NSEvent.pressedMouseButtons & 1) != 0
        if !mouseDown {
            if isDragSessionActive {
                isDragSessionActive = false
                if !state.isSending && state.finishedMessage == nil {
                    hideShelf()
                }
            }
            return
        }

        let dragPb = NSPasteboard(name: .drag)
        let currentCount = dragPb.changeCount
        if currentCount != lastSeenDragChangeCount {
            lastSeenDragChangeCount = currentCount
            let hasFiles = dragPb.canReadObject(
                forClasses: [NSURL.self],
                options: [.urlReadingFileURLsOnly: true]
            )
            if hasFiles {
                isDragSessionActive = true
                showShelf()
            }
        } else if isDragSessionActive && !state.isVisible && !state.isSending {
            showShelf()
        }
    }

    private func targetOrigin() -> NSPoint {
        // 1. Anchor directly centered under AirBridge's MenuBarExtra NSStatusBarWindow
        if let statusWin = NSApp.windows.first(where: { win in
            let name = String(describing: type(of: win))
            return name.contains("StatusBar") && win.frame.width > 0 && win.frame.width < 120
        }) {
            let screen = statusWin.screen ?? NSScreen.main ?? NSScreen.screens.first!
            let visible = screen.visibleFrame
            let desiredX = statusWin.frame.midX - panelWidth / 2
            let clampedX = min(max(desiredX, visible.minX + 8), visible.maxX - panelWidth - 8)
            let y = visible.maxY - panelHeight - 6
            return NSPoint(x: clampedX, y: y)
        }

        // 2. Fallback: top-right beneath menu bar on the active mouse screen
        let mouseLoc = NSEvent.mouseLocation
        let screen = NSScreen.screens.first(where: { $0.frame.contains(mouseLoc) })
            ?? NSScreen.main
            ?? NSScreen.screens.first!
        let visible = screen.visibleFrame
        return NSPoint(x: visible.maxX - panelWidth - 24, y: visible.maxY - panelHeight - 6)
    }

    private func showShelf() {
        hideWorkItem?.cancel()
        hideWorkItem = nil
        guard let panel else { return }

        let target = targetOrigin()
        if !panel.isVisible {
            panel.setFrameOrigin(NSPoint(x: target.x, y: target.y + 14))
            panel.alphaValue = 0
            panel.orderFrontRegardless()
            NSAnimationContext.runAnimationGroup { ctx in
                ctx.duration = 0.20
                ctx.timingFunction = CAMediaTimingFunction(name: .easeOut)
                panel.animator().setFrameOrigin(target)
                panel.animator().alphaValue = 1
            }
        } else {
            panel.setFrameOrigin(target)
        }
        state.isVisible = true
    }

    private func hideShelf() {
        guard let panel, panel.isVisible else {
            state.isVisible = false
            state.isTargeted = false
            return
        }
        let current = panel.frame.origin
        NSAnimationContext.runAnimationGroup({ ctx in
            ctx.duration = 0.16
            ctx.timingFunction = CAMediaTimingFunction(name: .easeIn)
            panel.animator().setFrameOrigin(NSPoint(x: current.x, y: current.y + 12))
            panel.animator().alphaValue = 0
        }, completionHandler: { [weak self] in
            Task { @MainActor in
                guard let self else { return }
                if !self.isDragSessionActive && !self.state.isSending && self.state.finishedMessage == nil {
                    self.panel?.orderOut(nil)
                    self.state.isVisible = false
                    self.state.isTargeted = false
                    self.state.hoveredFileCount = 0
                }
            }
        })
    }

    private func handleDroppedFiles(_ urls: [URL]) {
        guard !urls.isEmpty else {
            hideShelf()
            return
        }
        isDragSessionActive = false
        state.isTargeted = false
        state.finishedMessage = nil

        if !client.isConnected {
            state.finishedMessage = "Chưa kết nối điện thoại"
            scheduleAutoHide(after: 2.0)
            return
        }

        state.isSending = true
        showShelf()
        Task {
            await client.uploadFiles(urls)
            state.isSending = false
            state.finishedMessage = "Đã gửi \(urls.count) tệp sang \(client.deviceName)"
            scheduleAutoHide(after: 1.9)
        }
    }

    private func scheduleAutoHide(after seconds: Double) {
        hideWorkItem?.cancel()
        let item = DispatchWorkItem { [weak self] in
            Task { @MainActor in
                self?.state.finishedMessage = nil
                self?.hideShelf()
            }
        }
        hideWorkItem = item
        DispatchQueue.main.asyncAfter(deadline: .now() + seconds, execute: item)
    }
}

private final class FileDropContainerView: NSView {
    private let onHoverChanged: (Bool, Int) -> Void
    private let onFilesDropped: ([URL]) -> Void

    init(
        frame: NSRect,
        onHoverChanged: @escaping (Bool, Int) -> Void,
        onFilesDropped: @escaping ([URL]) -> Void
    ) {
        self.onHoverChanged = onHoverChanged
        self.onFilesDropped = onFilesDropped
        super.init(frame: frame)
        registerForDraggedTypes([.fileURL])
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    private func extractURLs(from info: NSDraggingInfo) -> [URL] {
        let pb = info.draggingPasteboard
        return (pb.readObjects(forClasses: [NSURL.self], options: [.urlReadingFileURLsOnly: true]) as? [URL]) ?? []
    }

    override func draggingEntered(_ sender: NSDraggingInfo) -> NSDragOperation {
        let urls = extractURLs(from: sender)
        guard !urls.isEmpty else { return [] }
        onHoverChanged(true, urls.count)
        return .copy
    }

    override func draggingUpdated(_ sender: NSDraggingInfo) -> NSDragOperation {
        let urls = extractURLs(from: sender)
        return urls.isEmpty ? [] : .copy
    }

    override func draggingExited(_ sender: NSDraggingInfo?) {
        onHoverChanged(false, 0)
    }

    override func performDragOperation(_ sender: NSDraggingInfo) -> Bool {
        let urls = extractURLs(from: sender)
        onHoverChanged(false, 0)
        guard !urls.isEmpty else { return false }
        onFilesDropped(urls)
        return true
    }
}

private struct MenuBarDropShelfView: View {
    @ObservedObject var client: AirBridgeMacClient
    @ObservedObject var state: DropShelfState

    var body: some View {
        HStack(spacing: 12) {
            ZStack {
                Circle()
                    .fill(iconBackgroundColor)
                    .frame(width: 40, height: 40)
                if state.isSending {
                    ProgressView()
                        .controlSize(.small)
                } else {
                    Image(systemName: iconName)
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundStyle(iconForegroundColor)
                }
            }

            VStack(alignment: .leading, spacing: 3) {
                Text(titleText)
                    .font(.system(size: 13, weight: .semibold))
                    .lineLimit(1)
                if state.isSending {
                    ProgressView(value: client.uploadProgress)
                        .progressViewStyle(.linear)
                        .frame(maxWidth: .infinity)
                } else {
                    Text(subtitleText)
                        .font(.system(size: 11))
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }

            Spacer(minLength: 0)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .strokeBorder(
                    state.isTargeted ? Color.accentColor : Color.primary.opacity(0.18),
                    style: StrokeStyle(lineWidth: state.isTargeted ? 2.2 : 1, dash: state.isTargeted ? [] : [5, 4])
                )
        )
        .shadow(color: .black.opacity(0.22), radius: 12, x: 0, y: 6)
        .scaleEffect(state.isTargeted ? 1.03 : 1.0)
        .animation(.spring(response: 0.22, dampingFraction: 0.78), value: state.isTargeted)
        .padding(4)
    }

    private var iconName: String {
        if let msg = state.finishedMessage {
            return msg.contains("Chưa") ? "exclamationmark.triangle.fill" : "checkmark.circle.fill"
        }
        if state.isTargeted { return "arrow.down.circle.fill" }
        return client.isConnected ? "iphone.and.arrow.forward" : "iphone.slash"
    }

    private var iconBackgroundColor: Color {
        if let msg = state.finishedMessage {
            return msg.contains("Chưa") ? Color.orange.opacity(0.22) : Color.green.opacity(0.22)
        }
        if state.isTargeted { return Color.accentColor.opacity(0.24) }
        return Color.primary.opacity(0.08)
    }

    private var iconForegroundColor: Color {
        if let msg = state.finishedMessage {
            return msg.contains("Chưa") ? .orange : .green
        }
        return state.isTargeted ? .accentColor : .primary
    }

    private var titleText: String {
        if let msg = state.finishedMessage { return msg }
        if state.isSending { return "Đang gửi sang \(client.deviceName)..." }
        if state.isTargeted {
            let n = max(1, state.hoveredFileCount)
            return "Thả ngay để gửi (\(n) tệp)"
        }
        return client.isConnected ? "Thả tệp gửi sang \(client.deviceName)" : "AirBridge chưa kết nối"
    }

    private var subtitleText: String {
        if state.finishedMessage != nil { return "Lưu trong Download/AirBridge" }
        if !client.isConnected { return "Mở AirBridge trên điện thoại để nhận" }
        return state.isTargeted ? "Sẽ lưu vào Download/AirBridge" : "Kéo thả vào đây để chuyển nhanh"
    }
}
