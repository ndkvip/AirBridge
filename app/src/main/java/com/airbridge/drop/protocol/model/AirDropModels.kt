package com.airbridge.drop.protocol.model

import android.net.Network
import android.net.Uri
import android.net.wifi.aware.PeerHandle

enum class DevicePlatform {
    IPHONE,
    IPAD,
    MAC,
    ANDROID,
    UNKNOWN
}

enum class TransportType {
    WIFI_AWARE,
    MDNS_LAN,
    BLE_ONLY
}

enum class VisibilityMode(val labelVi: String, val durationMs: Long?) {
    OFF("Tắt", null),
    EVERYONE_10_MIN("Mọi người (10 phút)", 10 * 60 * 1000L),
    ALWAYS_ON("Luôn mở (Mọi người)", null)
}

data class AirDropPeer(
    val id: String,
    val displayName: String,
    val modelName: String,
    val platform: DevicePlatform,
    val transport: TransportType,
    val hostAddress: String? = null,
    val port: Int = DEFAULT_AIRDROP_PORT,
    val flags: Int = DEFAULT_AIRDROP_FLAGS,
    val rssi: Int? = null,
    val bleAddress: String? = null,
    val awarePeerHandle: PeerHandle? = null,
    val boundNetwork: Network? = null,
    val lastSeenMs: Long = System.currentTimeMillis()
) {
    val isConnectable: Boolean
        get() = hostAddress != null || awarePeerHandle != null

    companion object {
        const val DEFAULT_AIRDROP_PORT = 8770
        // Standard AirDrop receiver flags:
        // Supports Discover, Ask, Upload, Pipelining, Mixed Types, Media Capabilities
        const val DEFAULT_AIRDROP_FLAGS = 0x03FB // 1019

        fun inferPlatform(modelName: String?, displayName: String?): DevicePlatform {
            val combined = "${modelName.orEmpty()} ${displayName.orEmpty()}".lowercase()
            return when {
                "iphone" in combined -> DevicePlatform.IPHONE
                "ipad" in combined -> DevicePlatform.IPAD
                "mac" in combined || "imac" in combined -> DevicePlatform.MAC
                "pixel" in combined || "galaxy" in combined || "android" in combined || "airbridge" in combined -> DevicePlatform.ANDROID
                else -> DevicePlatform.UNKNOWN
            }
        }
    }
}

data class OutgoingFile(
    val uri: Uri,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long
) {
    val uti: String
        get() = UtiMapper.fromFileNameAndMime(fileName, mimeType)
}

data class IncomingFileMetadata(
    val fileName: String,
    val uti: String,
    val bomPath: String,
    val isDirectory: Boolean = false
)

data class IncomingTransferRequest(
    val requestId: String,
    val senderName: String,
    val senderModel: String,
    val senderPlatform: DevicePlatform,
    val files: List<IncomingFileMetadata>,
    val totalSizeBytes: Long?,
    val iconJpeg: ByteArray? = null
)

enum class TransferDirection { SENDING, RECEIVING }

enum class TransferState {
    DISCOVERING,
    WAITING_ACCEPTANCE,
    TRANSFERRING,
    COMPLETED,
    DECLINED,
    FAILED
}

data class TransferProgress(
    val id: String,
    val peerName: String,
    val direction: TransferDirection,
    val state: TransferState,
    val fileNames: List<String>,
    val bytesTransferred: Long,
    val totalBytes: Long,
    val speedBytesPerSec: Long = 0L,
    val message: String? = null,
    val savedPaths: List<String> = emptyList(),
    val timestampMs: Long = System.currentTimeMillis()
) {
    val fraction: Float
        get() = if (totalBytes > 0L) {
            (bytesTransferred.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
        } else {
            if (state == TransferState.COMPLETED) 1f else 0f
        }
}

data class ProtocolDiagnostics(
    val bleSupported: Boolean = false,
    val bleScanning: Boolean = false,
    val bleAdvertising: Boolean = false,
    val wifiAwareSupported: Boolean = false,
    val wifiAwareAttached: Boolean = false,
    val mdnsRegistered: Boolean = false,
    val mdnsDiscovering: Boolean = false,
    val httpsPort: Int = AirDropPeer.DEFAULT_AIRDROP_PORT,
    val localIp: String? = null,
    val deviceName: String = "Android"
)

object UtiMapper {
    fun fromFileNameAndMime(fileName: String, mimeType: String?): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val mime = mimeType?.lowercase().orEmpty()
        return when {
            ext in setOf("jpg", "jpeg") || mime == "image/jpeg" -> "public.jpeg"
            ext == "png" || mime == "image/png" -> "public.png"
            ext in setOf("heic", "heif") || mime == "image/heic" -> "public.heic"
            ext == "gif" || mime == "image/gif" -> "com.compuserve.gif"
            ext == "webp" || mime == "image/webp" -> "org.webmproject.webp"
            ext == "mp4" || mime == "video/mp4" -> "public.mpeg-4"
            ext == "mov" || mime == "video/quicktime" -> "com.apple.quicktime-movie"
            ext == "mp3" || mime == "audio/mpeg" -> "public.mp3"
            ext == "m4a" || mime == "audio/mp4" -> "public.mpeg-4-audio"
            ext == "wav" || mime == "audio/wav" -> "com.microsoft.waveform-audio"
            ext == "pdf" || mime == "application/pdf" -> "com.adobe.pdf"
            ext == "txt" || mime == "text/plain" -> "public.plain-text"
            ext == "zip" || mime == "application/zip" -> "public.zip-archive"
            ext == "html" || ext == "htm" -> "public.html"
            ext == "json" -> "public.json"
            else -> "public.data"
        }
    }
}
