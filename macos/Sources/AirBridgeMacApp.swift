import SwiftUI
import AppKit

@main
struct AirBridgeMacApp: App {
    @StateObject private var client: AirBridgeMacClient
    private let dropShelf: MenuBarDropShelfController
    @Environment(\.openWindow) private var openWindow

    init() {
        let c = AirBridgeMacClient()
        _client = StateObject(wrappedValue: c)
        self.dropShelf = MenuBarDropShelfController(client: c)
    }

    var body: some Scene {
        MenuBarExtra {
            VStack(alignment: .leading, spacing: 6) {
                Text(client.isConnected ? "🟢 Đã kết nối: \(client.deviceName)" : "🟠 \(client.statusMessage)")
                    .font(.caption.bold())
                if client.gboardSyncEnabled {
                    Text(client.isGboardConnected ? "⌨️ Gboard Web Clipboard: Đang đồng bộ tự động" : "⌨️ Gboard Web Clipboard: Chưa bật trên điện thoại")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }

                Divider()

                Toggle("Tự động đồng bộ Clipboard", isOn: $client.autoSyncClipboard)

                Divider()

                Button("Mở AirBridge (Ảnh, Tệp & Clipboard)") {
                    NSApp.activate(ignoringOtherApps: true)
                    openWindow(id: "main-window")
                }

                Divider()

                Button("Thoát AirBridge") {
                    NSApp.terminate(nil)
                }
                .keyboardShortcut("q")
            }
            .padding(8)
        } label: {
            Image(systemName: client.isConnected ? "iphone.radiowaves.left.and.right" : "iphone")
        }

        Window("AirBridge", id: "main-window") {
            ContentView(client: client)
        }
        .windowStyle(.titleBar)

        Window("OnePlus Mirror", id: "phone-mirror") {
            PhoneMirrorWindowView(client: client)
        }
        .windowStyle(.hiddenTitleBar)
        .windowResizability(.contentSize)
        .defaultSize(width: 380, height: 820)
    }
}
