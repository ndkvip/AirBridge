package com.airbridge.drop.protocol.http

import com.airbridge.drop.protocol.codec.BinaryPlistCodec
import com.airbridge.drop.protocol.codec.CpioArchiveCodec
import com.airbridge.drop.protocol.model.AirDropPeer
import com.airbridge.drop.protocol.model.DevicePlatform
import com.airbridge.drop.protocol.model.IncomingFileMetadata
import com.airbridge.drop.protocol.model.IncomingTransferRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket

/**
 * Embedded AirDrop-compatible HTTPS/1.1 server implementing:
 * - POST /Discover  (bplist00 capabilities exchange)
 * - POST /Ask       (bplist00 transfer approval prompt)
 * - POST /Upload    (gzipped CPIO archive stream extraction)
 */
class AirDropServer(
    private val sslContext: SSLContext,
    private val deviceNameProvider: () -> String,
    private val modelName: String,
    private val destinationDirProvider: () -> File,
    private val onAskRequest: suspend (IncomingTransferRequest) -> Boolean,
    private val onReceiveProgress: (requestId: String, senderName: String, bytesRead: Long, totalBytes: Long) -> Unit,
    private val onReceiveComplete: (requestId: String, senderName: String, files: List<CpioArchiveCodec.ExtractedFile>) -> Unit,
    private val onReceiveError: (requestId: String, senderName: String, error: String) -> Unit
) {
    private var serverSocket: SSLServerSocket? = null
    private var acceptJob: Job? = null

    val boundPort: Int
        get() = serverSocket?.localPort ?: AirDropPeer.DEFAULT_AIRDROP_PORT

    fun start(scope: CoroutineScope, preferredPort: Int = AirDropPeer.DEFAULT_AIRDROP_PORT): Int {
        stop()
        val factory = sslContext.serverSocketFactory
        val socket = try {
            factory.createServerSocket(preferredPort, 16, InetAddress.getByName("0.0.0.0")) as SSLServerSocket
        } catch (_: IOException) {
            factory.createServerSocket(0, 16, InetAddress.getByName("0.0.0.0")) as SSLServerSocket
        }
        socket.needClientAuth = false
        socket.wantClientAuth = false
        serverSocket = socket

        acceptJob = scope.launch(Dispatchers.IO) {
            while (isActive && !socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (_: IOException) {
                    break
                }
                launch(Dispatchers.IO) {
                    runCatching { handleConnection(client) }
                }
            }
        }
        return socket.localPort
    }

    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private suspend fun handleConnection(socket: Socket) {
        socket.use { conn ->
            conn.soTimeout = 120_000
            val input = BufferedInputStream(conn.getInputStream(), 64 * 1024)
            val output = conn.getOutputStream()

            // AirDrop reuses the same TLS connection for /Discover -> /Ask -> /Upload
            var pendingRequest: IncomingTransferRequest? = null
            var approved = false

            while (true) {
                val requestLine = readAsciiLine(input) ?: break
                if (requestLine.isBlank()) continue
                val parts = requestLine.split(" ")
                if (parts.size < 2) break
                val method = parts[0].uppercase(Locale.US)
                val path = parts[1].substringBefore('?')

                val headers = LinkedHashMap<String, String>()
                while (true) {
                    val line = readAsciiLine(input) ?: return
                    if (line.isEmpty()) break
                    val colon = line.indexOf(':')
                    if (colon > 0) {
                        val k = line.substring(0, colon).trim().lowercase(Locale.US)
                        val v = line.substring(colon + 1).trim()
                        headers[k] = v
                    }
                }

                val isChunked = headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true
                val contentLength = headers["content-length"]?.toLongOrNull() ?: 0L
                val isGzipped = headers["content-encoding"]?.contains("gzip", ignoreCase = true) == true
                val bodyStream: InputStream = when {
                    isChunked -> ChunkedInputStream(input)
                    contentLength > 0L -> BoundedInputStream(input, contentLength)
                    else -> BoundedInputStream(input, 0L)
                }

                when {
                    method == "POST" && path.equals("/Discover", ignoreCase = true) -> {
                        // Drain request body
                        bodyStream.readBytes()
                        val responsePlist = BinaryPlistCodec.encode(
                            mapOf(
                                "ReceiverComputerName" to deviceNameProvider(),
                                "ReceiverModelName" to modelName,
                                "ReceiverMediaCapabilities" to """{"Version":1}""".toByteArray(StandardCharsets.UTF_8)
                            )
                        )
                        writeHttpResponse(output, 200, "OK", "application/octet-stream", responsePlist)
                    }

                    method == "POST" && path.equals("/Ask", ignoreCase = true) -> {
                        val reqBytes = bodyStream.readBytes()
                        val parsed = runCatching { BinaryPlistCodec.decode(reqBytes) as? Map<*, *> }.getOrNull()
                        val senderName = parsed?.get("SenderComputerName")?.toString() ?: "Thiết bị gần đây"
                        val senderModel = parsed?.get("SenderModelName")?.toString() ?: "Apple / AirDrop"
                        val iconBytes = parsed?.get("FileIcon") as? ByteArray

                        @Suppress("UNCHECKED_CAST")
                        val rawFiles = parsed?.get("Files") as? List<Map<*, *>> ?: emptyList()
                        val files = rawFiles.map { f ->
                            IncomingFileMetadata(
                                fileName = f["FileName"]?.toString() ?: "received_file",
                                uti = f["FileType"]?.toString() ?: "public.data",
                                bomPath = f["FileBomPath"]?.toString() ?: "./received_file",
                                isDirectory = f["FileIsDirectory"] as? Boolean ?: false
                            )
                        }

                        val req = IncomingTransferRequest(
                            requestId = UUID.randomUUID().toString(),
                            senderName = senderName,
                            senderModel = senderModel,
                            senderPlatform = AirDropPeer.inferPlatform(senderModel, senderName),
                            files = files,
                            totalSizeBytes = (parsed?.get("TotalSize") as? Number)?.toLong(),
                            iconJpeg = iconBytes
                        )
                        pendingRequest = req
                        approved = onAskRequest(req)

                        if (approved) {
                            val okBody = BinaryPlistCodec.encode(
                                mapOf(
                                    "ReceiverComputerName" to deviceNameProvider(),
                                    "ReceiverModelName" to modelName
                                )
                            )
                            writeHttpResponse(output, 200, "OK", "application/octet-stream", okBody)
                        } else {
                            writeHttpResponse(output, 401, "Unauthorized", "text/plain", ByteArray(0))
                            break
                        }
                    }

                    method == "POST" && path.equals("/Upload", ignoreCase = true) -> {
                        val req = pendingRequest
                        if (!approved && req != null) {
                            writeHttpResponse(output, 401, "Unauthorized", "text/plain", ByteArray(0))
                            break
                        }
                        val reqId = req?.requestId ?: UUID.randomUUID().toString()
                        val senderName = req?.senderName ?: "AirDrop Peer"
                        val expectedTotal = req?.totalSizeBytes ?: contentLength

                        try {
                            val extracted = CpioArchiveCodec.extractGzippedCpio(
                                input = bodyStream,
                                destinationDir = destinationDirProvider(),
                                isGzipped = isGzipped
                            ) { bytesRead ->
                                onReceiveProgress(reqId, senderName, bytesRead, expectedTotal)
                            }
                            writeHttpResponse(output, 200, "OK", "application/octet-stream", ByteArray(0))
                            onReceiveComplete(reqId, senderName, extracted)
                        } catch (e: Exception) {
                            onReceiveError(reqId, senderName, e.message ?: "Lỗi giải nén dữ liệu AirDrop")
                            writeHttpResponse(output, 500, "Internal Server Error", "text/plain", ByteArray(0))
                        }
                        break
                    }

                    else -> {
                        bodyStream.readBytes()
                        writeHttpResponse(output, 404, "Not Found", "text/plain", ByteArray(0))
                    }
                }

                if (headers["connection"]?.equals("close", ignoreCase = true) == true) break
            }
        }
    }

    private fun writeHttpResponse(
        out: OutputStream,
        statusCode: Int,
        reason: String,
        contentType: String,
        body: ByteArray
    ) {
        val header = buildString {
            append("HTTP/1.1 $statusCode $reason\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Connection: keep-alive\r\n")
            append("\r\n")
        }
        out.write(header.toByteArray(StandardCharsets.US_ASCII))
        if (body.isNotEmpty()) out.write(body)
        out.flush()
    }

    private fun readAsciiLine(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        var prev = -1
        while (true) {
            val b = input.read()
            if (b == -1) {
                return if (out.size() == 0) null else out.toString(StandardCharsets.US_ASCII.name())
            }
            if (b == '\n'.code) {
                val arr = out.toByteArray()
                val len = if (prev == '\r'.code && arr.isNotEmpty()) arr.size - 1 else arr.size
                return String(arr, 0, len, StandardCharsets.US_ASCII)
            }
            out.write(b)
            prev = b
        }
    }

    private class BoundedInputStream(
        private val delegate: InputStream,
        private var remaining: Long
    ) : InputStream() {
        override fun read(): Int {
            if (remaining <= 0L) return -1
            val b = delegate.read()
            if (b >= 0) remaining--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0L) return -1
            val toRead = minOf(len.toLong(), remaining).toInt()
            val n = delegate.read(b, off, toRead)
            if (n > 0) remaining -= n
            return n
        }
    }

    private class ChunkedInputStream(private val delegate: InputStream) : InputStream() {
        private var chunkRemaining = 0
        private var eof = false

        override fun read(): Int {
            val single = ByteArray(1)
            val n = read(single, 0, 1)
            return if (n <= 0) -1 else (single[0].toInt() and 0xFF)
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (eof) return -1
            if (chunkRemaining == 0) {
                val sizeLine = readLine() ?: run {
                    eof = true
                    return -1
                }
                val hex = sizeLine.substringBefore(';').trim()
                chunkRemaining = hex.toIntOrNull(16) ?: 0
                if (chunkRemaining == 0) {
                    // Consume trailing CRLF/headers
                    while (true) {
                        val trailer = readLine() ?: break
                        if (trailer.isEmpty()) break
                    }
                    eof = true
                    return -1
                }
            }
            val toRead = minOf(len, chunkRemaining)
            val n = delegate.read(b, off, toRead)
            if (n < 0) {
                eof = true
                return -1
            }
            chunkRemaining -= n
            if (chunkRemaining == 0) {
                // Consume CRLF after chunk payload
                readLine()
            }
            return n
        }

        private fun readLine(): String? {
            val out = ByteArrayOutputStream()
            while (true) {
                val c = delegate.read()
                if (c == -1) return if (out.size() == 0) null else out.toString("US-ASCII")
                if (c == '\n'.code) {
                    val bytes = out.toByteArray()
                    val end = if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) bytes.size - 1 else bytes.size
                    return String(bytes, 0, end, StandardCharsets.US_ASCII)
                }
                out.write(c)
            }
        }
    }
}
