#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
APP_BUNDLE="$SCRIPT_DIR/build/AirBridge.app"
MACOS_DIR="$APP_BUNDLE/Contents/MacOS"
RESOURCES_DIR="$APP_BUNDLE/Contents/Resources"

rm -rf "$APP_BUNDLE"
mkdir -p "$MACOS_DIR" "$RESOURCES_DIR"

swiftc \
  -O \
  -target arm64-apple-macos14.0 \
  -parse-as-library \
  -framework SwiftUI \
  -framework AppKit \
  -framework Network \
  -framework WebKit \
  -framework AVKit \
  -framework AVFoundation \
  -framework CryptoKit \
  -framework Security \
  -framework CoreImage \
  -framework UniformTypeIdentifiers \
  -o "$MACOS_DIR/AirBridge" \
  "$SCRIPT_DIR/Sources/AirBridgeClient.swift" \
  "$SCRIPT_DIR/Sources/MediaPreviewSheet.swift" \
  "$SCRIPT_DIR/Sources/QrPairingSheet.swift" \
  "$SCRIPT_DIR/Sources/ContentView.swift" \
  "$SCRIPT_DIR/Sources/MenuBarDropShelf.swift" \
  "$SCRIPT_DIR/Sources/AirBridgeMacApp.swift"

cat > "$APP_BUNDLE/Contents/Info.plist" << 'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleExecutable</key>
    <string>AirBridge</string>
    <key>CFBundleIdentifier</key>
    <string>com.airbridge.mac</string>
    <key>CFBundleName</key>
    <string>AirBridge</string>
    <key>CFBundleDisplayName</key>
    <string>AirBridge</string>
    <key>CFBundleVersion</key>
    <string>1</string>
    <key>CFBundleShortVersionString</key>
    <string>1.0.0</string>
    <key>CFBundlePackageType</key>
    <string>APPL</string>
    <key>LSMinimumSystemVersion</key>
    <string>14.0</string>
    <key>NSHighResolutionCapable</key>
    <true/>
    <key>LSUIElement</key>
    <true/>
    <key>NSAppTransportSecurity</key>
    <dict>
        <key>NSAllowsArbitraryLoads</key>
        <true/>
        <key>NSAllowsLocalNetworking</key>
        <true/>
    </dict>
    <key>NSLocalNetworkUsageDescription</key>
    <string>AirBridge sử dụng mạng Wi-Fi nội bộ để tìm và kết nối trực tiếp với điện thoại Android.</string>
    <key>NSBonjourServices</key>
    <array>
        <string>_airdrop._tcp</string>
    </array>
</dict>
</plist>
EOF

codesign --force --deep --sign - "$APP_BUNDLE"
echo "Built: $APP_BUNDLE"
