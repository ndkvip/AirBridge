import SwiftUI
import AppKit
import AVKit
import WebKit

enum RemoteMediaKind {
    case image
    case video
    case audio
    case webPreview
    case other

    static func from(fileName: String) -> RemoteMediaKind {
        let ext = (fileName as NSString).pathExtension.lowercased()
        switch ext {
        case "jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "bmp", "tif", "tiff":
            return .image
        case "mp4", "mov", "m4v", "mkv", "avi", "webm":
            return .video
        case "mp3", "m4a", "wav", "aac", "flac", "ogg":
            return .audio
        case "pdf", "txt", "md", "json", "xml", "log", "html", "htm", "csv":
            return .webPreview
        default:
            return .other
        }
    }
}

final class ThumbnailMemoryCache {
    static let shared = ThumbnailMemoryCache()
    private let cache = NSCache<NSString, NSImage>()

    private init() {
        cache.countLimit = 250
    }

    func image(for key: String) -> NSImage? {
        cache.object(forKey: key as NSString)
    }

    func store(_ image: NSImage, for key: String) {
        cache.setObject(image, forKey: key as NSString)
    }
}

struct RemoteThumbnailView: View {
    let url: URL?
    let kind: RemoteMediaKind
    let isDir: Bool
    let size: CGSize

    @State private var image: NSImage? = nil
    @State private var isLoading: Bool = false

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 8, style: .continuous)
                .fill(Color.secondary.opacity(0.12))

            if let image {
                Image(nsImage: image)
                    .resizable()
                    .scaledToFill()
                    .frame(width: size.width, height: size.height)
                    .clipped()
            } else {
                Image(systemName: fallbackIcon)
                    .font(.system(size: min(size.width, size.height) * 0.42))
                    .foregroundStyle(fallbackColor)
            }

            if kind == .video {
                Image(systemName: "play.circle.fill")
                    .font(.system(size: min(size.width, size.height) * 0.36))
                    .foregroundStyle(.white.opacity(0.92))
                    .shadow(radius: 3)
            }
        }
        .frame(width: size.width, height: size.height)
        .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
        .task(id: url?.absoluteString) {
            await loadIfNeeded()
        }
    }

    private var fallbackIcon: String {
        if isDir { return "folder.fill" }
        switch kind {
        case .image: return "photo"
        case .video: return "film"
        case .audio: return "waveform"
        case .webPreview: return "doc.text"
        case .other: return "doc"
        }
    }

    private var fallbackColor: Color {
        if isDir { return .blue }
        switch kind {
        case .image: return .teal
        case .video: return .purple
        case .audio: return .pink
        case .webPreview: return .orange
        case .other: return .secondary
        }
    }

    private func loadIfNeeded() async {
        guard !isDir, kind == .image, let url else { return }
        let key = url.absoluteString
        if let cached = ThumbnailMemoryCache.shared.image(for: key) {
            self.image = cached
            return
        }
        guard !isLoading else { return }
        isLoading = true
        defer { isLoading = false }

        do {
            var req = URLRequest(url: url)
            req.timeoutInterval = 8
            let (data, resp) = try await URLSession.shared.data(for: req)
            guard let http = resp as? HTTPURLResponse, (200...299).contains(http.statusCode),
                  let thumb = downsampleImage(data: data, maxPixel: 260) else { return }
            ThumbnailMemoryCache.shared.store(thumb, for: key)
            await MainActor.run {
                self.image = thumb
            }
        } catch {}
    }

    private func downsampleImage(data: Data, maxPixel: CGFloat) -> NSImage? {
        guard let src = CGImageSourceCreateWithData(data as CFData, nil) else {
            return NSImage(data: data)
        }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceThumbnailMaxPixelSize: maxPixel,
            kCGImageSourceCreateThumbnailWithTransform: true
        ]
        if let cgImg = CGImageSourceCreateThumbnailAtIndex(src, 0, options as CFDictionary) {
            return NSImage(cgImage: cgImg, size: NSSize(width: cgImg.width, height: cgImg.height))
        }
        return NSImage(data: data)
    }
}

struct MediaPreviewSheet: View {
    @ObservedObject var client: AirBridgeMacClient
    let entries: [PhoneFileEntry]
    @State var currentIndex: Int
    let onClose: () -> Void

    @State private var loadedImage: NSImage? = nil
    @State private var isLoadingImage: Bool = false
    @State private var imageZoom: CGFloat = 1.0
    @State private var player: AVPlayer? = nil
    @State private var toastText: String? = nil

    private var currentEntry: PhoneFileEntry? {
        guard entries.indices.contains(currentIndex) else { return nil }
        return entries[currentIndex]
    }

    var body: some View {
        VStack(spacing: 0) {
            // Header bar
            HStack(spacing: 12) {
                Button {
                    navigate(-1)
                } label: {
                    Image(systemName: "chevron.left")
                }
                .buttonStyle(.bordered)
                .disabled(currentIndex <= 0)
                .keyboardShortcut(.leftArrow, modifiers: [])

                Button {
                    navigate(1)
                } label: {
                    Image(systemName: "chevron.right")
                }
                .buttonStyle(.bordered)
                .disabled(currentIndex >= entries.count - 1)
                .keyboardShortcut(.rightArrow, modifiers: [])

                if let entry = currentEntry {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(entry.name)
                            .font(.headline)
                            .lineLimit(1)
                        Text("\(currentIndex + 1) / \(entries.count) • \(formatSize(entry.size))")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }

                Spacer()

                if let toastText {
                    Text(toastText)
                        .font(.caption.bold())
                        .padding(.horizontal, 10)
                        .padding(.vertical, 4)
                        .background(Color.green.opacity(0.2), in: Capsule())
                        .foregroundStyle(.green)
                }

                if let entry = currentEntry, RemoteMediaKind.from(fileName: entry.name) == .image {
                    HStack(spacing: 4) {
                        Button { imageZoom = max(0.5, imageZoom - 0.25) } label: { Image(systemName: "minus.magnifyingglass") }
                        Button { imageZoom = 1.0 } label: { Text("\(Int(imageZoom * 100))%").font(.caption.monospacedDigit()) }
                        Button { imageZoom = min(4.0, imageZoom + 0.25) } label: { Image(systemName: "plus.magnifyingglass") }
                    }
                    .buttonStyle(.bordered)

                    Button {
                        copyCurrentImage()
                    } label: {
                        Label("Copy ảnh", systemImage: "doc.on.doc")
                    }
                    .buttonStyle(.bordered)
                    .disabled(loadedImage == nil)
                }

                if let entry = currentEntry {
                    Button {
                        Task {
                            await client.downloadFile(entry)
                            showToast("Đã tải về Downloads")
                        }
                    } label: {
                        Label("Tải về Mac", systemImage: "arrow.down.circle.fill")
                    }
                    .buttonStyle(.borderedProminent)
                }

                Button("Đóng") {
                    cleanupPlayer()
                    onClose()
                }
                .keyboardShortcut(.escape, modifiers: [])
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .background(.bar)

            Divider()

            // Main preview surface
            ZStack {
                Color.black.opacity(0.92)

                if let entry = currentEntry {
                    let kind = RemoteMediaKind.from(fileName: entry.name)
                    let previewURL = client.filePreviewURL(path: entry.path)

                    switch kind {
                    case .image:
                        if let img = loadedImage {
                            ScrollView([.horizontal, .vertical]) {
                                Image(nsImage: img)
                                    .resizable()
                                    .scaledToFit()
                                    .scaleEffect(imageZoom)
                                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                                    .padding(16)
                            }
                        } else if isLoadingImage {
                            ProgressView("Đang tải ảnh...")
                                .tint(.white)
                                .foregroundStyle(.white)
                        } else {
                            Text("Không thể hiển thị ảnh")
                                .foregroundStyle(.white.opacity(0.75))
                        }

                    case .video, .audio:
                        if let player {
                            VideoPlayer(player: player)
                                .frame(maxWidth: .infinity, maxHeight: .infinity)
                        } else {
                            ProgressView("Đang tải video...")
                                .tint(.white)
                                .foregroundStyle(.white)
                        }

                    case .webPreview:
                        if let previewURL {
                            PreviewWebView(url: previewURL)
                                .background(Color.white)
                        }

                    case .other:
                        VStack(spacing: 14) {
                            Image(systemName: "doc.fill")
                                .font(.system(size: 56))
                                .foregroundStyle(.white.opacity(0.7))
                            Text(entry.name)
                                .font(.title3.bold())
                                .foregroundStyle(.white)
                            Text("Định dạng này không hỗ trợ xem trực tiếp. Bấm \"Tải về Mac\" để mở.")
                                .foregroundStyle(.white.opacity(0.7))
                        }
                    }
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .frame(minWidth: 840, minHeight: 600)
        .task(id: currentIndex) {
            await loadCurrentMedia()
        }
        .onDisappear {
            cleanupPlayer()
        }
    }

    private func navigate(_ delta: Int) {
        let next = currentIndex + delta
        guard entries.indices.contains(next) else { return }
        cleanupPlayer()
        imageZoom = 1.0
        currentIndex = next
    }

    private func cleanupPlayer() {
        player?.pause()
        player = nil
    }

    private func loadCurrentMedia() async {
        cleanupPlayer()
        loadedImage = nil
        imageZoom = 1.0
        guard let entry = currentEntry, let url = client.filePreviewURL(path: entry.path) else { return }

        switch RemoteMediaKind.from(fileName: entry.name) {
        case .image:
            isLoadingImage = true
            defer { isLoadingImage = false }
            do {
                var req = URLRequest(url: url)
                req.timeoutInterval = 15
                let (data, resp) = try await URLSession.shared.data(for: req)
                if let http = resp as? HTTPURLResponse, (200...299).contains(http.statusCode),
                   let img = NSImage(data: data) {
                    loadedImage = img
                }
            } catch {}

        case .video, .audio:
            let avPlayer = AVPlayer(url: url)
            self.player = avPlayer
            avPlayer.play()

        case .webPreview, .other:
            break
        }
    }

    private func copyCurrentImage() {
        guard let img = loadedImage else { return }
        let pb = NSPasteboard.general
        pb.clearContents()
        if let tiff = img.tiffRepresentation,
           let rep = NSBitmapImageRep(data: tiff),
           let png = rep.representation(using: .png, properties: [:]) {
            pb.setData(png, forType: .png)
            pb.setData(tiff, forType: .tiff)
        } else {
            pb.writeObjects([img])
        }
        showToast("Đã copy ảnh vào Clipboard")
    }

    private func showToast(_ text: String) {
        toastText = text
        DispatchQueue.main.asyncAfter(deadline: .now() + 2.0) {
            if toastText == text { toastText = nil }
        }
    }

    private func formatSize(_ bytes: Int64) -> String {
        let f = ByteCountFormatter()
        f.countStyle = .file
        return f.string(fromByteCount: bytes)
    }
}

private struct PreviewWebView: NSViewRepresentable {
    let url: URL

    func makeNSView(context: Context) -> WKWebView {
        let web = WKWebView()
        web.load(URLRequest(url: url))
        return web
    }

    func updateNSView(_ web: WKWebView, context: Context) {
        if web.url != url {
            web.load(URLRequest(url: url))
        }
    }
}
