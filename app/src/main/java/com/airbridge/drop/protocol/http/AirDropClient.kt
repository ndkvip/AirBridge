package com.airbridge.drop.protocol.http

import android.net.Network
import com.airbridge.drop.protocol.codec.BinaryPlistCodec
import com.airbridge.drop.protocol.codec.CpioArchiveCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/**
 * AirDrop-compatible HTTPS/1.1 client implementing the exact 3-phase protocol
 * over a single persistent TLS connection:
 *   1. POST /Discover
 *   2. POST /Ask
 *   3. POST /Upload (Content-Encoding: gzip, application/x-cpio, Transfer-Encoding: chunked)
 */
class AirDropClient(
    private val sslContext: SSLContext,
    private val senderNameProvider: () -> String,
    private val senderModel: String
) {
    data class DiscoverInfo(
        val receiverName: String,
        val receiverModel: String
    )

    data class SendFileDescriptor(
        val fileName: String,
        val uti: String,
        val sizeBytes: Long,
        val openStream: () -> InputStream
    )

    sealed class SendResult {
        data object Completed : SendResult()
        data object Declined : SendResult()
        data class Failed(val reason: String) : SendResult()
    }

    suspend fun discoverPeer(
        host: String,
        port: Int,
        boundNetwork: Network? = null
    ): DiscoverInfo? = withContext(Dispatchers.IO) {
        runCatching {
            openTlsSocket(host, port, boundNetwork).use { socket ->
                val input = BufferedInputStream(socket.inputStream, 32 * 1024)
                val output = socket.outputStream
                performDiscover(host, port, input, output)
            }
        }.getOrNull()
    }

    suspend fun sendFiles(
        host: String,
        port: Int,
        files: List<SendFileDescriptor>,
        boundNetwork: Network? = null,
        onStateChanged: (phase: String) -> Unit = {},
        onProgress: (bytesSent: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): SendResult = withContext(Dispatchers.IO) {
        val totalBytes = files.sumOf { it.sizeBytes }
        try {
            openTlsSocket(host, port, boundNetwork).use { socket ->
                val input = BufferedInputStream(socket.inputStream, 64 * 1024)
                val output = socket.outputStream

                // Phase 1: POST /Discover
                onStateChanged("DISCOVERING")
                performDiscover(host, port, input, output)

                // Phase 2: POST /Ask
                onStateChanged("WAITING_ACCEPTANCE")
                val askPayload = BinaryPlistCodec.encode(
                    mapOf(
                        "SenderComputerName" to senderNameProvider(),
                        "SenderModelName" to senderModel,
                        "SenderID" to "airbridge-android",
                        "ConvertMediaFormats" to false,
                        "TotalSize" to totalBytes,
                        "Files" to files.map { f ->
                            mapOf(
                                "FileName" to f.fileName,
                                "FileType" to f.uti,
                                "FileBomPath" to "./${f.fileName}",
                                "FileIsDirectory" to false,
                                "ConvertMediaFormats" to false
                            )
                        }
                    )
                )
                val askResp = postBinary(host, port, "/Ask", askPayload, input, output)
                if (askResp.statusCode == 401 || askResp.statusCode == 403) {
                    return@withContext SendResult.Declined
                }
                if (askResp.statusCode !in 200..299) {
                    return@withContext SendResult.Failed("Thiết bị nhận trả về lỗi HTTP ${askResp.statusCode} ở bước /Ask")
                }

                // Phase 3: POST /Upload (gzipped CPIO over chunked transfer encoding)
                onStateChanged("TRANSFERRING")
                val uploadHeaders = buildString {
                    append("POST /Upload HTTP/1.1\r\n")
                    append("Host: ${formatHostHeader(host, port)}\r\n")
                    append("User-Agent: AirDrop/1.0\r\n")
                    append("Content-Type: application/x-cpio\r\n")
                    append("Content-Encoding: gzip\r\n")
                    append("Transfer-Encoding: chunked\r\n")
                    append("Connection: close\r\n")
                    append("\r\n")
                }
                output.write(uploadHeaders.toByteArray(StandardCharsets.US_ASCII))
                output.flush()

                val chunkedOut = ChunkedOutputStream(output)
                val cpioEntries = files.map { f ->
                    CpioArchiveCodec.CpioSourceEntry(
                        name = f.fileName,
                        sizeBytes = f.sizeBytes,
                        openStream = f.openStream
                    )
                }
                CpioArchiveCodec.writeGzippedCpio(cpioEntries, chunkedOut) { bytesWritten ->
                    onProgress(bytesWritten, totalBytes)
                }
                chunkedOut.finish()

                val uploadResp = readHttpResponse(input)
                if (uploadResp.statusCode in 200..299) {
                    SendResult.Completed
                } else {
                    SendResult.Failed("Lỗi truyền dữ liệu HTTP ${uploadResp.statusCode} ở bước /Upload")
                }
            }
        } catch (e: Exception) {
            SendResult.Failed(e.message ?: "Không thể kết nối tới thiết bị nhận")
        }
    }

    private fun openTlsSocket(host: String, port: Int, boundNetwork: Network?): SSLSocket {
        val rawSocket = boundNetwork?.socketFactory?.createSocket() ?: java.net.Socket()
        rawSocket.connect(InetSocketAddress(host, port), 10_000)
        val sslSocket = sslContext.socketFactory.createSocket(rawSocket, host, port, true) as SSLSocket
        sslSocket.soTimeout = 120_000
        sslSocket.startHandshake()
        return sslSocket
    }

    private fun performDiscover(
        host: String,
        port: Int,
        input: InputStream,
        output: OutputStream
    ): DiscoverInfo? {
        val reqBody = BinaryPlistCodec.encode(
            mapOf("SenderRecordData" to ByteArray(0))
        )
        val resp = postBinary(host, port, "/Discover", reqBody, input, output)
        if (resp.statusCode !in 200..299 || resp.body.isEmpty()) return null
        val dict = runCatching { BinaryPlistCodec.decode(resp.body) as? Map<*, *> }.getOrNull() ?: return null
        return DiscoverInfo(
            receiverName = dict["ReceiverComputerName"]?.toString() ?: host,
            receiverModel = dict["ReceiverModelName"]?.toString() ?: "AirDrop Device"
        )
    }

    private data class HttpResponse(val statusCode: Int, val body: ByteArray)

    private fun postBinary(
        host: String,
        port: Int,
        path: String,
        body: ByteArray,
        input: InputStream,
        output: OutputStream
    ): HttpResponse {
        val header = buildString {
            append("POST $path HTTP/1.1\r\n")
            append("Host: ${formatHostHeader(host, port)}\r\n")
            append("User-Agent: AirDrop/1.0\r\n")
            append("Content-Type: application/octet-stream\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Connection: keep-alive\r\n")
            append("\r\n")
        }
        output.write(header.toByteArray(StandardCharsets.US_ASCII))
        output.write(body)
        output.flush()
        return readHttpResponse(input)
    }

    private fun readHttpResponse(input: InputStream): HttpResponse {
        val statusLine = readAsciiLine(input) ?: throw IOException("Connection closed before HTTP status")
        val statusCode = statusLine.split(" ").getOrNull(1)?.toIntOrNull() ?: 500
        var contentLength = 0
        while (true) {
            val line = readAsciiLine(input) ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) {
                val k = line.substring(0, colon).trim().lowercase()
                val v = line.substring(colon + 1).trim()
                if (k == "content-length") contentLength = v.toIntOrNull() ?: 0
            }
        }
        val body = if (contentLength > 0) {
            val buf = ByteArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = input.read(buf, read, contentLength - read)
                if (n < 0) break
                read += n
            }
            buf
        } else {
            ByteArray(0)
        }
        return HttpResponse(statusCode, body)
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

    private fun formatHostHeader(host: String, port: Int): String =
        if (':' in host && !host.startsWith("[")) "[$host]:$port" else "$host:$port"

    private class ChunkedOutputStream(private val delegate: OutputStream) : OutputStream() {
        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (len <= 0) return
            val prefix = "${len.toString(16)}\r\n".toByteArray(StandardCharsets.US_ASCII)
            delegate.write(prefix)
            delegate.write(b, off, len)
            delegate.write("\r\n".toByteArray(StandardCharsets.US_ASCII))
            delegate.flush()
        }

        fun finish() {
            delegate.write("0\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
            delegate.flush()
        }
    }
}
