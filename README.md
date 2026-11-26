# AirBridge (macOS ↔ Android / Gboard Companion)

Banking-app-safe, ultra-low-battery LAN bridge between **macOS** and **Android** (designed to pair directly with the integrated companion inside [`ndkvip/Gboard-patches`](https://github.com/ndkvip/Gboard-patches), or with the standalone Android app in `app/`).

## Features
- **Menu Bar App (`macos/`)**:
  - Silent `LSUIElement` Menu Bar app with automatic UDP discovery (`:8772`) and single-port HTTP connection (shares Gboard Web Clipboard port `:8080`).
  - **Drag-to-Expand Menu Bar Drop Shelf**: drag files toward the Menu Bar icon to drop directly to Android `Download/AirBridge/` (no Accessibility permission required).
  - **2-Way Text & Image Clipboard Sync**: syncs text, images, and Android screenshots seamlessly.
  - **Media Thumbnail Grid & Built-in Viewer**: browse phone photos, screenshots, and downloads with inline preview (images, videos, PDFs).
  - **1-Scan Permanent QR Pairing**: scan from Gboard's built-in **Quét QR** drawer tile to pair permanently.
- **Standalone Android App (`app/`)**:
  - Optional standalone companion with OPPO Fluid Cloud / Android 16–17 Live Updates transfer pill, TLS pinning, and MediaStore file sharing.

## Build macOS App
```bash
cd macos
./build_app.sh
```
The script compiles, signs, and installs `AirBridge.app` to `/Applications/AirBridge.app`.
