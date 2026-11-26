package com.airbridge.drop.protocol.codec

import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * POSIX Portable ASCII CPIO ("070707" ODC format) + GZIP archive codec
 * matching Apple AirDrop's /Upload payload format (`Content-Encoding: gzip`, `application/x-cpio`).
 */
object CpioArchiveCodec {
    private const val ODC_MAGIC = "070707"
    private const val ODC_HEADER_SIZE = 76
    private const val TRAILER_NAME = "TRAILER!!!"
    private const val MODE_REGULAR_FILE = 0b1000000110100100 // 0100644 octal

    data class CpioSourceEntry(
        val name: String,
        val sizeBytes: Long,
        val openStream: () -> InputStream
    )

    data class ExtractedFile(
        val relativeName: String,
        val file: File,
        val sizeBytes: Long
    )

    fun writeGzippedCpio(
        entries: List<CpioSourceEntry>,
        output: OutputStream,
        onBytesWritten: (Long) -> Unit = {}
    ) {
        var totalWritten = 0L
        val gzipOut = GZIPOutputStream(output, 64 * 1024)
        try {
            val nowSec = System.currentTimeMillis() / 1000L
            entries.forEachIndexed { index, entry ->
                val cleanName = "./" + sanitizeRelativePath(entry.name)
                val nameBytes = (cleanName + "\u0000").toByteArray(StandardCharsets.UTF_8)
                val header = buildOdcHeader(
                    ino = index + 1,
                    mode = MODE_REGULAR_FILE,
                    mtime = nowSec,
                    nameSize = nameBytes.size,
                    fileSize = entry.sizeBytes
                )
                gzipOut.write(header)
                gzipOut.write(nameBytes)

                val buffer = ByteArray(64 * 1024)
                var remaining = entry.sizeBytes
                entry.openStream().use { input ->
                    while (remaining > 0L) {
                        val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                        val read = input.read(buffer, 0, toRead)
                        if (read <= 0) break
                        gzipOut.write(buffer, 0, read)
                        remaining -= read
                        totalWritten += read
                        onBytesWritten(totalWritten)
                    }
                }
                // Pad if stream ended early so archive stays aligned
                if (remaining > 0L) {
                    val zeros = ByteArray(minOf(remaining, 8192L).toInt())
                    while (remaining > 0L) {
                        val chunk = minOf(zeros.size.toLong(), remaining).toInt()
                        gzipOut.write(zeros, 0, chunk)
                        remaining -= chunk
                    }
                }
            }

            // Write TRAILER!!! sentinel
            val trailerNameBytes = (TRAILER_NAME + "\u0000").toByteArray(StandardCharsets.US_ASCII)
            val trailerHeader = buildOdcHeader(
                ino = 0,
                mode = 0,
                mtime = 0L,
                nameSize = trailerNameBytes.size,
                fileSize = 0L
            )
            gzipOut.write(trailerHeader)
            gzipOut.write(trailerNameBytes)
            gzipOut.finish()
            gzipOut.flush()
        } finally {
            // Do not close underlying socket stream here; caller owns socket lifecycle
        }
    }

    fun extractGzippedCpio(
        input: InputStream,
        destinationDir: File,
        isGzipped: Boolean = true,
        onBytesRead: (Long) -> Unit = {}
    ): List<ExtractedFile> {
        if (!destinationDir.exists()) destinationDir.mkdirs()

        val rawStream: InputStream = if (isGzipped) {
            GZIPInputStream(BufferedInputStream(input, 64 * 1024), 64 * 1024)
        } else {
            BufferedInputStream(input, 64 * 1024)
        }

        val extracted = ArrayList<ExtractedFile>()
        val headerBuf = ByteArray(ODC_HEADER_SIZE)
        val copyBuf = ByteArray(64 * 1024)
        var totalRead = 0L

        while (true) {
            if (!readFully(rawStream, headerBuf, 0, ODC_HEADER_SIZE)) break
            val headerStr = String(headerBuf, StandardCharsets.US_ASCII)
            require(headerStr.startsWith(ODC_MAGIC)) {
                "Unsupported CPIO header magic: '${headerStr.take(6)}'"
            }

            val mode = headerStr.substring(18, 24).toInt(8)
            val nameSize = headerStr.substring(59, 65).toInt(8)
            val fileSize = headerStr.substring(65, 76).toLong(8)

            val nameBuf = ByteArray(nameSize)
            if (!readFully(rawStream, nameBuf, 0, nameSize)) break
            val rawName = String(nameBuf, 0, (nameSize - 1).coerceAtLeast(0), StandardCharsets.UTF_8)

            if (rawName == TRAILER_NAME) break

            val safeRel = sanitizeRelativePath(rawName)
            val isDir = (mode and 0xF000) == 0x4000

            if (isDir || safeRel.isEmpty()) {
                if (safeRel.isNotEmpty()) File(destinationDir, safeRel).mkdirs()
                skipExact(rawStream, fileSize)
                continue
            }

            val outFile = uniqueTargetFile(destinationDir, safeRel)
            outFile.parentFile?.mkdirs()

            FileOutputStream(outFile).use { fos ->
                var remaining = fileSize
                while (remaining > 0L) {
                    val toRead = minOf(copyBuf.size.toLong(), remaining).toInt()
                    val n = rawStream.read(copyBuf, 0, toRead)
                    if (n <= 0) break
                    fos.write(copyBuf, 0, n)
                    remaining -= n
                    totalRead += n
                    onBytesRead(totalRead)
                }
            }

            extracted.add(ExtractedFile(relativeName = outFile.name, file = outFile, sizeBytes = fileSize))
        }

        return extracted
    }

    private fun buildOdcHeader(
        ino: Int,
        mode: Int,
        mtime: Long,
        nameSize: Int,
        fileSize: Long
    ): ByteArray {
        val header = buildString(ODC_HEADER_SIZE) {
            append(ODC_MAGIC)                                 // 6: magic
            append(octal(0L, 6))                              // 6: dev
            append(octal(ino.toLong(), 6))                    // 6: ino
            append(octal(mode.toLong(), 6))                   // 6: mode
            append(octal(0L, 6))                              // 6: uid
            append(octal(0L, 6))                              // 6: gid
            append(octal(1L, 6))                              // 6: nlink
            append(octal(0L, 6))                              // 6: rdev
            append(octal(mtime, 11))                          // 11: mtime
            append(octal(nameSize.toLong(), 6))               // 6: namesize
            append(octal(fileSize, 11))                       // 11: filesize
        }
        return header.toByteArray(StandardCharsets.US_ASCII)
    }

    private fun octal(value: Long, width: Int): String =
        value.coerceAtLeast(0L).toString(8).padStart(width, '0').takeLast(width)

    internal fun sanitizeRelativePath(raw: String): String {
        val normalized = raw.replace('\\', '/').trimStart('/').removePrefix("./")
        val safeParts = normalized.split('/').filter { part ->
            part.isNotEmpty() && part != "." && part != ".."
        }
        return safeParts.joinToString("/")
    }

    private fun uniqueTargetFile(dir: File, relativePath: String): File {
        val candidate = File(dir, relativePath)
        if (!candidate.exists()) return candidate
        val parent = candidate.parentFile ?: dir
        val base = candidate.nameWithoutExtension
        val ext = candidate.extension
        var counter = 1
        while (true) {
            val name = if (ext.isEmpty()) "${base}_$counter" else "${base}_$counter.$ext"
            val next = File(parent, name)
            if (!next.exists()) return next
            counter++
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray, offset: Int, length: Int): Boolean {
        var readTotal = 0
        while (readTotal < length) {
            val n = input.read(buf, offset + readTotal, length - readTotal)
            if (n < 0) return false
            readTotal += n
        }
        return true
    }

    private fun skipExact(input: InputStream, count: Long) {
        var remaining = count
        val discard = ByteArray(4096)
        while (remaining > 0L) {
            val n = input.read(discard, 0, minOf(discard.size.toLong(), remaining).toInt())
            if (n <= 0) break
            remaining -= n
        }
    }
}
