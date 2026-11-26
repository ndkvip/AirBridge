package com.airbridge.drop.protocol.web

import android.content.Context
import android.os.Environment
import com.airbridge.drop.protocol.clipboard.ClipboardSyncManager
import com.airbridge.drop.protocol.http.AirDropTlsContext
import com.airbridge.drop.protocol.media.ScreenStreamManager
import com.airbridge.drop.service.AirBridgeControlService
import javax.net.ssl.SSLServerSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Embedded HTTP/1.1 Web Portal & API server (O+ Connect style) on port 8771.
 * Zero-install on Mac & iPhone (works in Safari/Chrome without touching macOS Home directory):
 * - GET  /                       -> O+ Connect-style Web Portal UI
 * - GET  /api/status             -> Device info, storage root, screen streaming status
 * - GET  /api/files?path=...     -> Browse phone directories & files
 * - GET  /api/file?path=...      -> Stream/preview/download any file (supports HTTP Range for video)
 * - POST /api/upload?path=...&name=... -> Upload files directly to phone folder
 * - DELETE /api/file?path=...    -> Delete file (optional)
 * - GET  /api/clipboard          -> Get phone clipboard + history
 * - POST /api/clipboard          -> Push Mac/iPhone clipboard directly into Android clipboard
 * - GET  /api/screen/stream      -> Live MJPEG screen mirroring stream from Android
 */
class WebBridgeServer(
    private val context: Context,
    private val deviceNameProvider: () -> String,
    private val modelName: String,
    private val pinProvider: () -> String,
    private val clipboardManager: ClipboardSyncManager,
    private val screenStreamManager: ScreenStreamManager,
    private val onRequestScreenCastFromRemote: () -> Unit,
    private val onRequestStorageAccessFromRemote: () -> Unit,
    private val onRequestControlAccessFromRemote: () -> Unit,
    private val onFileUploaded: (fileName: String, sizeBytes: Long) -> Unit
) {
    private val transferNotifier = FluidCloudTransferNotifier(context)
    private val security = AirBridgeSecurityManager.getInstance(context)
    private var serverSocket: ServerSocket? = null
    private var tlsServerSocket: SSLServerSocket? = null
    private var acceptJob: Job? = null
    private var tlsAcceptJob: Job? = null
    private var executor: ExecutorService? = null

    @Volatile
    var certSha256Hex: String = ""
        private set

    val boundPort: Int
        get() = serverSocket?.localPort ?: DEFAULT_WEB_PORT

    val boundTlsPort: Int
        get() = tlsServerSocket?.localPort ?: 0

    fun start(scope: CoroutineScope, preferredPort: Int = DEFAULT_WEB_PORT): Int {
        stop()
        val socket = ServerSocket().apply {
            reuseAddress = true
            try {
                bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), preferredPort), 64)
            } catch (_: IOException) {
                bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), 0), 64)
            }
        }
        serverSocket = socket
        val pool = Executors.newCachedThreadPool()
        executor = pool

        acceptJob = scope.launch(Dispatchers.IO) {
            while (isActive && !socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (_: IOException) {
                    break
                }
                pool.execute {
                    runCatching {
                        client.tcpNoDelay = true
                        handleClient(client)
                    }
                }
            }
        }

        // Start HTTPS/TLS listener on port 8773 with persistent pinned self-signed cert
        runCatching {
            val tlsIdentity = AirDropTlsContext.createPersistentSslContext(context, "AirBridge")
            certSha256Hex = tlsIdentity.certSha256Hex
            val sslFactory = tlsIdentity.sslContext.serverSocketFactory
            val sslSock = (sslFactory.createServerSocket() as SSLServerSocket).apply {
                reuseAddress = true
                try {
                    bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), DEFAULT_TLS_PORT), 64)
                } catch (_: IOException) {
                    bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), 0), 64)
                }
            }
            tlsServerSocket = sslSock
            tlsAcceptJob = scope.launch(Dispatchers.IO) {
                while (isActive && !sslSock.isClosed) {
                    val client = try {
                        sslSock.accept()
                    } catch (_: IOException) {
                        break
                    }
                    pool.execute {
                        runCatching {
                            client.tcpNoDelay = true
                            handleClient(client)
                        }
                    }
                }
            }
        }
        return socket.localPort
    }

    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        tlsAcceptJob?.cancel()
        tlsAcceptJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        runCatching { tlsServerSocket?.close() }
        tlsServerSocket = null
        runCatching { executor?.shutdownNow() }
        executor = null
    }

    private fun handleClient(socket: Socket) {
        socket.use { conn ->
            conn.soTimeout = 120_000
            val input = BufferedInputStream(conn.getInputStream(), 64 * 1024)
            val output = conn.getOutputStream()

            val requestLine = readAsciiLine(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0].uppercase(Locale.US)
            val rawTarget = parts[1]
            val path = rawTarget.substringBefore('?')
            val query = parseQuery(rawTarget.substringAfter('?', ""))

            val headers = LinkedHashMap<String, String>()
            while (true) {
                val line = readAsciiLine(input) ?: return
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) {
                    headers[line.substring(0, colon).trim().lowercase(Locale.US)] =
                        line.substring(colon + 1).trim()
                }
            }

            if (method == "OPTIONS") {
                writeResponse(output, 204, "No Content", "text/plain", ByteArray(0))
                return
            }

            // Serve Web UI HTML without PIN (UI itself prompts PIN if enabled)
            if (method == "GET" && (path == "/" || path == "/index.html")) {
                val html = runCatching {
                    context.assets.open("web/index.html").use { it.readBytes() }
                }.getOrElse {
                    "<h1>AirBridge Web Portal</h1>".toByteArray(StandardCharsets.UTF_8)
                }
                writeResponse(output, 200, "OK", "text/html; charset=utf-8", html)
                return
            }

            val remoteIp = conn.inetAddress?.hostAddress ?: "unknown"

            // 1. Brute-force rate-limit lockout per remote IP
            val lockoutSec = security.checkLockoutSeconds(remoteIp)
            if (lockoutSec > 0) {
                val err = JSONObject()
                    .put("error", "TOO_MANY_ATTEMPTS")
                    .put("retryAfterSec", lockoutSec)
                    .toString().toByteArray(StandardCharsets.UTF_8)
                writeResponse(output, 429, "Too Many Requests", "application/json; charset=utf-8", err)
                return
            }

            // 2. Pairing endpoints (PIN-verified + user approval notification -> 256-bit token)
            val requiredPin = pinProvider()
            if (method == "POST" && path == "/api/pair") {
                handlePairRequest(input, output, headers, remoteIp, requiredPin)
                return
            }
            if (method == "GET" && path == "/api/pair/status") {
                handlePairStatus(output, query, headers, remoteIp, requiredPin)
                return
            }

            // 3. Authenticate all other /api/* endpoints via 256-bit session token OR valid PIN
            val providedToken = headers["x-airbridge-token"] ?: query["token"]
            val providedPin = headers["x-airbridge-pin"] ?: query["pin"]
            val tokenValid = security.isValidToken(providedToken)
            val pinValid = requiredPin.isEmpty() || providedPin == requiredPin

            if (!tokenValid && !pinValid) {
                val lockedFor = if (!providedPin.isNullOrBlank() || !providedToken.isNullOrBlank()) {
                    security.recordAuthFailure(remoteIp)
                } else {
                    0
                }
                if (lockedFor > 0) {
                    val err = JSONObject()
                        .put("error", "TOO_MANY_ATTEMPTS")
                        .put("retryAfterSec", lockedFor)
                        .toString().toByteArray(StandardCharsets.UTF_8)
                    writeResponse(output, 429, "Too Many Requests", "application/json; charset=utf-8", err)
                } else {
                    val err = JSONObject().put("error", "PIN_REQUIRED").toString().toByteArray(StandardCharsets.UTF_8)
                    writeResponse(output, 401, "Unauthorized", "application/json; charset=utf-8", err)
                }
                return
            }
            security.recordAuthSuccess(remoteIp)

            when {
                method == "GET" && path == "/api/status" -> handleStatus(output)
                method == "GET" && path == "/api/files" -> handleListFiles(output, query["path"].orEmpty())
                method == "GET" && path == "/api/file" -> handleGetFile(output, query["path"].orEmpty(), headers["range"], query["download"] == "1")
                method == "POST" && path == "/api/upload" -> handleUpload(input, output, headers, query)
                method == "GET" && path == "/api/clipboard" -> handleGetClipboard(output)
                method == "GET" && path == "/api/clipboard/image" -> handleGetClipboardImage(output, query)
                method == "POST" && path == "/api/clipboard" -> handleSetClipboard(input, output, headers)
                method == "POST" && path == "/api/clipboard/image" -> handleSetClipboardImage(input, output, headers, query)
                method == "GET" && path == "/api/screen/stream" -> handleScreenStream(output)
                method == "POST" && path == "/api/screen/input" -> handleScreenInput(input, output, headers)
                method == "POST" && path == "/api/screen/request" -> {
                    if (!screenStreamManager.isStreaming.value) {
                        onRequestScreenCastFromRemote()
                    }
                    writeJson(output, 200, JSONObject().put("ok", true).put("streaming", screenStreamManager.isStreaming.value))
                }
                method == "POST" && path == "/api/storage/request" -> {
                    onRequestStorageAccessFromRemote()
                    writeJson(output, 200, JSONObject().put("ok", true))
                }
                method == "POST" && path == "/api/control/request" -> {
                    onRequestControlAccessFromRemote()
                    writeJson(output, 200, JSONObject().put("ok", true))
                }
                else -> writeJson(output, 404, JSONObject().put("error", "Not found"))
            }
        }
    }

    private fun handlePairRequest(
        input: InputStream,
        output: OutputStream,
        headers: Map<String, String>,
        remoteIp: String,
        requiredPin: String
    ) {
        val len = headers["content-length"]?.toIntOrNull()?.coerceIn(0, 16 * 1024) ?: 0
        val bodyBytes = ByteArray(len)
        var offset = 0
        while (offset < len) {
            val n = input.read(bodyBytes, offset, len - offset)
            if (n <= 0) break
            offset += n
        }
        val bodyStr = if (offset > 0) String(bodyBytes, 0, offset, StandardCharsets.UTF_8) else "{}"
        val json = runCatching { JSONObject(bodyStr) }.getOrElse { JSONObject() }
        val pin = json.optString("pin", headers["x-airbridge-pin"].orEmpty())
        val clientId = json.optString("clientId", headers["x-airbridge-client-id"].orEmpty()).trim()
        val clientName = json.optString("clientName", headers["x-airbridge-client-name"].orEmpty()).trim()

        if (requiredPin.isNotEmpty() && pin != requiredPin) {
            val lockedFor = security.recordAuthFailure(remoteIp)
            if (lockedFor > 0) {
                writeJson(output, 429, JSONObject().put("error", "TOO_MANY_ATTEMPTS").put("retryAfterSec", lockedFor))
            } else {
                writeJson(output, 401, JSONObject().put("error", "PIN_REQUIRED"))
            }
            return
        }
        if (clientId.length < 8) {
            writeJson(output, 400, JSONObject().put("error", "INVALID_CLIENT_ID"))
            return
        }
        security.recordAuthSuccess(remoteIp)
        val req = security.startOrGetPairing(clientId, clientName, remoteIp)
        val resp = JSONObject().put("status", req.decision.name)
        if (req.decision == AirBridgeSecurityManager.PairDecision.APPROVED && req.issuedToken != null) {
            resp.put("token", req.issuedToken)
        }
        writeJson(output, 200, resp)
    }

    private fun handlePairStatus(
        output: OutputStream,
        query: Map<String, String>,
        headers: Map<String, String>,
        remoteIp: String,
        requiredPin: String
    ) {
        val pin = headers["x-airbridge-pin"] ?: query["pin"].orEmpty()
        val clientId = (query["clientId"] ?: headers["x-airbridge-client-id"]).orEmpty().trim()
        if (requiredPin.isNotEmpty() && pin != requiredPin) {
            val lockedFor = security.recordAuthFailure(remoteIp)
            if (lockedFor > 0) {
                writeJson(output, 429, JSONObject().put("error", "TOO_MANY_ATTEMPTS").put("retryAfterSec", lockedFor))
            } else {
                writeJson(output, 401, JSONObject().put("error", "PIN_REQUIRED"))
            }
            return
        }
        val req = security.getPairingStatus(clientId)
        if (req == null) {
            writeJson(output, 404, JSONObject().put("status", "EXPIRED"))
            return
        }
        val resp = JSONObject().put("status", req.decision.name)
        if (req.decision == AirBridgeSecurityManager.PairDecision.APPROVED && req.issuedToken != null) {
            resp.put("token", req.issuedToken)
        }
        writeJson(output, 200, resp)
    }

    private fun handleStatus(output: OutputStream) {
        val root = getStorageRoot()
        val json = JSONObject().apply {
            put("deviceName", deviceNameProvider())
            put("modelName", modelName)
            put("screenStreaming", screenStreamManager.isStreaming.value)
            put("remoteControlEnabled", AirBridgeControlService.isEnabled)
            put("storageAccessible", root.canRead())
            put("storageRootName", root.name.ifEmpty { "Internal Storage" })
            put("clipboardLatest", clipboardManager.latestText.value)
        }
        writeJson(output, 200, json)
    }

    private fun handleScreenInput(
        input: InputStream,
        output: OutputStream,
        headers: Map<String, String>
    ) {
        val len = headers["content-length"]?.toIntOrNull()?.coerceIn(0, 65_536) ?: 0
        val body = ByteArray(len)
        var read = 0
        while (read < len) {
            val n = input.read(body, read, len - read)
            if (n <= 0) break
            read += n
        }
        val ctrl = AirBridgeControlService.instance
        if (ctrl == null) {
            writeJson(output, 503, JSONObject().put("error", "CONTROL_DISABLED"))
            return
        }
        val ok = runCatching {
            val j = JSONObject(String(body, 0, read, StandardCharsets.UTF_8))
            when (j.optString("type")) {
                "tap" -> ctrl.performTap(j.optDouble("x", 0.5).toFloat(), j.optDouble("y", 0.5).toFloat())
                "swipe" -> ctrl.performSwipe(
                    j.optDouble("x1", 0.5).toFloat(),
                    j.optDouble("y1", 0.5).toFloat(),
                    j.optDouble("x2", 0.5).toFloat(),
                    j.optDouble("y2", 0.5).toFloat(),
                    j.optLong("durationMs", 240L)
                )
                "action" -> ctrl.performNavAction(j.optString("action"))
                else -> false
            }
        }.getOrDefault(false)
        writeJson(output, 200, JSONObject().put("ok", ok))
    }

    private fun handleListFiles(output: OutputStream, relativePath: String) {
        val root = getStorageRoot()
        val target = resolveSafeFile(root, relativePath)
        if (target == null || !target.exists() || !target.isDirectory) {
            writeJson(output, 404, JSONObject().put("error", "Thư mục không tồn tại hoặc chưa cấp quyền đọc bộ nhớ"))
            return
        }

        val entries = target.listFiles()
            ?.filter { !it.name.startsWith(".") }
            ?.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase(Locale.US) })
            ?: emptyList()

        val arr = JSONArray()
        for (f in entries) {
            val rel = f.relativeTo(root).path.replace('\\', '/')
            arr.put(
                JSONObject().apply {
                    put("name", f.name)
                    put("path", rel)
                    put("isDir", f.isDirectory)
                    put("size", if (f.isDirectory) 0L else f.length())
                    put("modifiedMs", f.lastModified())
                    put("mime", guessMime(f.name))
                }
            )
        }

        val relCur = if (target == root) "" else target.relativeTo(root).path.replace('\\', '/')
        val resp = JSONObject().apply {
            put("path", relCur)
            put("entries", arr)
        }
        writeJson(output, 200, resp)
    }

    private fun handleGetFile(
        output: OutputStream,
        relativePath: String,
        rangeHeader: String?,
        forceDownload: Boolean
    ) {
        val root = getStorageRoot()
        val file = resolveSafeFile(root, relativePath)
        if (file == null || !file.exists() || !file.isFile) {
            writeJson(output, 404, JSONObject().put("error", "Không tìm thấy tệp"))
            return
        }

        val totalLen = file.length()
        val mime = guessMime(file.name)
        var start = 0L
        var end = (totalLen - 1L).coerceAtLeast(0L)
        var partial = false

        if (!rangeHeader.isNullOrBlank() && rangeHeader.startsWith("bytes=")) {
            val spec = rangeHeader.removePrefix("bytes=").substringBefore(',')
            val s = spec.substringBefore('-').toLongOrNull()
            val e = spec.substringAfter('-', "").toLongOrNull()
            if (s != null && s in 0 until totalLen) {
                start = s
                end = (e ?: (totalLen - 1L)).coerceAtMost(totalLen - 1L)
                partial = true
            }
        }

        val contentLen = if (totalLen == 0L) 0L else (end - start + 1L)
        val status = if (partial) "206 Partial Content" else "200 OK"
        val header = buildString {
            append("HTTP/1.1 $status\r\n")
            append("Content-Type: $mime\r\n")
            append("Accept-Ranges: bytes\r\n")
            append("Content-Length: $contentLen\r\n")
            if (partial) {
                append("Content-Range: bytes $start-$end/$totalLen\r\n")
            }
            if (forceDownload) {
                val safeName = file.name.replace("\"", "_")
                append("Content-Disposition: attachment; filename=\"$safeName\"\r\n")
            }
            append("Access-Control-Allow-Origin: *\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(header.toByteArray(StandardCharsets.US_ASCII))

        if (contentLen > 0L) {
            FileInputStream(file).use { fis ->
                if (start > 0L) fis.skip(start)
                val buf = ByteArray(64 * 1024)
                var remaining = contentLen
                while (remaining > 0L) {
                    val n = fis.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                    if (n <= 0) break
                    output.write(buf, 0, n)
                    remaining -= n
                }
            }
        }
        output.flush()
    }

    private fun handleUpload(
        input: InputStream,
        output: OutputStream,
        headers: Map<String, String>,
        query: Map<String, String>
    ) {
        val contentLength = headers["content-length"]?.toLongOrNull() ?: 0L
        val rawName = query["name"]?.takeIf { it.isNotBlank() } ?: "upload_${System.currentTimeMillis()}.bin"
        val safeName = rawName.replace('\\', '/').substringAfterLast('/').ifBlank { "upload.bin" }
        val targetRelDir = query["path"].orEmpty().ifBlank { "Download/AirBridge" }

        val root = getStorageRoot()
        val dir = resolveSafeFile(root, targetRelDir) ?: File(root, "Download/AirBridge")
        if (!dir.exists()) dir.mkdirs()

        var outFile = File(dir, safeName)
        if (outFile.exists()) {
            val base = outFile.nameWithoutExtension
            val ext = outFile.extension
            var i = 1
            while (outFile.exists()) {
                val nextName = if (ext.isEmpty()) "${base}_$i" else "${base}_$i.$ext"
                outFile = File(dir, nextName)
                i++
            }
        }

        var written = 0L
        var lastNotifyMs = 0L
        transferNotifier.showProgress(outFile.name, 0L, contentLength)
        FileOutputStream(outFile).use { fos ->
            val buf = ByteArray(64 * 1024)
            var remaining = contentLength
            while (remaining > 0L) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                if (n <= 0) break
                fos.write(buf, 0, n)
                remaining -= n
                written += n
                val now = System.currentTimeMillis()
                if (now - lastNotifyMs >= 180L) {
                    lastNotifyMs = now
                    transferNotifier.showProgress(outFile.name, written, contentLength)
                }
            }
        }

        transferNotifier.showCompleted(outFile, written)
        onFileUploaded(outFile.name, written)
        writeJson(
            output,
            200,
            JSONObject()
                .put("ok", true)
                .put("fileName", outFile.name)
                .put("size", written)
                .put("path", outFile.relativeTo(root).path.replace('\\', '/'))
        )
    }

    private fun handleGetClipboard(output: OutputStream) {
        val arr = JSONArray()
        for (item in clipboardManager.history.value) {
            arr.put(
                JSONObject()
                    .put("id", item.id)
                    .put("text", item.text)
                    .put("source", item.source)
                    .put("timestampMs", item.timestampMs)
                    .put("isImage", item.isImage)
            )
        }
        writeJson(
            output,
            200,
            JSONObject()
                .put("latest", clipboardManager.latestText.value)
                .put("history", arr)
        )
    }

    private fun handleGetClipboardImage(output: OutputStream, query: Map<String, String>) {
        val id = query["id"]?.toLongOrNull()
        val file = if (id != null) clipboardManager.getImageFileById(id) else null
        if (file == null || !file.exists()) {
            writeJson(output, 404, JSONObject().put("error", "IMAGE_NOT_FOUND"))
            return
        }
        val bytes = file.readBytes()
        writeResponse(output, 200, "OK", guessMime(file.name), bytes)
    }

    private fun handleSetClipboard(
        input: InputStream,
        output: OutputStream,
        headers: Map<String, String>
    ) {
        val len = headers["content-length"]?.toIntOrNull()?.coerceIn(0, 1_000_000) ?: 0
        val body = ByteArray(len)
        var read = 0
        while (read < len) {
            val n = input.read(body, read, len - read)
            if (n <= 0) break
            read += n
        }
        val text = runCatching {
            JSONObject(String(body, 0, read, StandardCharsets.UTF_8)).optString("text", "")
        }.getOrDefault("")

        if (text.isNotEmpty()) {
            clipboardManager.applyFromRemote(text, "Mac / Web")
        }
        writeJson(output, 200, JSONObject().put("ok", true))
    }

    private fun handleSetClipboardImage(
        input: InputStream,
        output: OutputStream,
        headers: Map<String, String>,
        query: Map<String, String>
    ) {
        val len = headers["content-length"]?.toIntOrNull()?.coerceIn(0, 20 * 1024 * 1024) ?: 0
        if (len <= 0) {
            writeJson(output, 400, JSONObject().put("error", "EMPTY_IMAGE"))
            return
        }
        val body = ByteArray(len)
        var read = 0
        while (read < len) {
            val n = input.read(body, read, len - read)
            if (n <= 0) break
            read += n
        }
        val ext = query["ext"]?.takeIf { it.isNotBlank() } ?: "png"
        val exact = if (read == len) body else body.copyOf(read)
        clipboardManager.applyImageFromRemote(exact, ext, "Mac / Web")
        writeJson(output, 200, JSONObject().put("ok", true).put("bytes", exact.size))
    }

    private fun handleScreenStream(output: OutputStream) {
        if (!screenStreamManager.isStreaming.value) {
            writeJson(output, 503, JSONObject().put("error", "Chưa bật chia sẻ màn hình trên điện thoại"))
            return
        }

        val boundary = "airbridgeframe"
        val header = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: multipart/x-mixed-replace; boundary=$boundary\r\n")
            append("Cache-Control: no-cache, no-store, must-revalidate\r\n")
            append("Pragma: no-cache\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(header.toByteArray(StandardCharsets.US_ASCII))
        output.flush()

        var lastSentTs = -1L
        try {
            while (screenStreamManager.isStreaming.value) {
                val (frame, ts) = screenStreamManager.getLatestJpegFrame()
                if (frame != null && ts != lastSentTs) {
                    lastSentTs = ts
                    val partHeader = "--$boundary\r\nContent-Type: image/jpeg\r\nContent-Length: ${frame.size}\r\n\r\n"
                    output.write(partHeader.toByteArray(StandardCharsets.US_ASCII))
                    output.write(frame)
                    output.write("\r\n".toByteArray(StandardCharsets.US_ASCII))
                    output.flush()
                } else {
                    Thread.sleep(25)
                }
            }
        } catch (_: Exception) {
            // Client closed stream
        }
    }

    private fun getStorageRoot(): File = Environment.getExternalStorageDirectory()

    private fun resolveSafeFile(root: File, relativePath: String): File? {
        val clean = relativePath.replace('\\', '/').trimStart('/')
        val candidate = if (clean.isEmpty()) root else File(root, clean)
        val canonRoot = root.canonicalPath
        val canonTarget = runCatching { candidate.canonicalPath }.getOrNull() ?: return null
        return if (canonTarget == canonRoot || canonTarget.startsWith("$canonRoot/")) candidate else null
    }

    private fun writeJson(output: OutputStream, statusCode: Int, json: JSONObject) {
        val reason = when (statusCode) {
            200 -> "OK"
            401 -> "Unauthorized"
            404 -> "Not Found"
            503 -> "Service Unavailable"
            else -> "Error"
        }
        writeResponse(output, statusCode, reason, "application/json; charset=utf-8", json.toString().toByteArray(StandardCharsets.UTF_8))
    }

    private fun writeResponse(
        output: OutputStream,
        statusCode: Int,
        reason: String,
        contentType: String,
        body: ByteArray
    ) {
        val header = buildString {
            append("HTTP/1.1 $statusCode $reason\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Headers: Content-Type, X-AirBridge-Pin\r\n")
            append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(header.toByteArray(StandardCharsets.US_ASCII))
        if (body.isNotEmpty()) output.write(body)
        output.flush()
    }

    private fun parseQuery(qs: String): Map<String, String> {
        if (qs.isEmpty()) return emptyMap()
        val map = LinkedHashMap<String, String>()
        for (pair in qs.split('&')) {
            val eq = pair.indexOf('=')
            if (eq > 0) {
                val k = URLDecoder.decode(pair.substring(0, eq), "UTF-8")
                val v = URLDecoder.decode(pair.substring(eq + 1), "UTF-8")
                map[k] = v
            }
        }
        return map
    }

    private fun readAsciiLine(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) return if (out.size() == 0) null else out.toString("US-ASCII")
            if (b == '\n'.code) {
                val arr = out.toByteArray()
                val len = if (arr.isNotEmpty() && arr.last() == '\r'.code.toByte()) arr.size - 1 else arr.size
                return String(arr, 0, len, StandardCharsets.US_ASCII)
            }
            out.write(b)
        }
    }

    private fun guessMime(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase(Locale.US)) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "heic" -> "image/heic"
        "mp4" -> "video/mp4"
        "mov" -> "video/quicktime"
        "mkv" -> "video/x-matroska"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "wav" -> "audio/wav"
        "pdf" -> "application/pdf"
        "txt", "log", "md" -> "text/plain; charset=utf-8"
        "json" -> "application/json; charset=utf-8"
        "zip" -> "application/zip"
        "apk" -> "application/vnd.android.package-archive"
        else -> "application/octet-stream"
    }

    companion object {
        const val DEFAULT_WEB_PORT = 8771
        const val DEFAULT_TLS_PORT = 8773
    }
}
