package com.airbridge.drop.protocol.codec

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * Pure-Kotlin encoder and decoder for Apple Binary Property Lists ("bplist00"),
 * used by Apple AirDrop's /Discover, /Ask, and /Upload HTTP endpoints.
 */
object BinaryPlistCodec {
    private val MAGIC = "bplist00".toByteArray(StandardCharsets.US_ASCII)

    fun encode(root: Any): ByteArray {
        val objects = ArrayList<Any?>()
        val refMap = IdentityHashMapWrapper()

        fun register(value: Any?): Int {
            // Reuse identical primitive strings/values safely for small tables
            val existing = refMap.get(value)
            if (existing != null) return existing
            val idx = objects.size
            objects.add(value)
            refMap.put(value, idx)
            when (value) {
                is Map<*, *> -> {
                    for ((k, _) in value) register(k.toString())
                    for ((_, v) in value) register(v)
                }
                is List<*> -> {
                    for (item in value) register(item)
                }
            }
            return idx
        }

        val topRef = register(root)
        val numObjects = objects.size
        val objectRefSize = bytesNeeded(numObjects.toLong())

        val out = ByteArrayOutputStream()
        out.write(MAGIC)

        val offsets = LongArray(numObjects)
        for (i in 0 until numObjects) {
            offsets[i] = out.size().toLong()
            writeObject(out, objects[i], objectRefSize) { v ->
                refMap.get(v) ?: error("Unregistered plist object: $v")
            }
        }

        val offsetTableOffset = out.size().toLong()
        val offsetIntSize = bytesNeeded(offsetTableOffset)

        for (offset in offsets) {
            writeIntBE(out, offset, offsetIntSize)
        }

        // 32-byte trailer: 6 unused bytes + offsetIntSize (1) + objectRefSize (1) +
        // numObjects (8) + topObject (8) + offsetTableOffset (8)
        val trailer = ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN)
        trailer.put(ByteArray(6))
        trailer.put(offsetIntSize.toByte())
        trailer.put(objectRefSize.toByte())
        trailer.putLong(numObjects.toLong())
        trailer.putLong(topRef.toLong())
        trailer.putLong(offsetTableOffset)
        out.write(trailer.array())

        return out.toByteArray()
    }

    fun decode(bytes: ByteArray): Any? {
        require(bytes.size >= 40) { "Binary plist too short: ${bytes.size}" }
        for (i in MAGIC.indices) {
            require(bytes[i] == MAGIC[i]) { "Invalid bplist00 magic header" }
        }

        val trailer = ByteBuffer.wrap(bytes, bytes.size - 32, 32).order(ByteOrder.BIG_ENDIAN)
        trailer.position(trailer.position() + 6)
        val offsetIntSize = trailer.get().toInt() and 0xFF
        val objectRefSize = trailer.get().toInt() and 0xFF
        val numObjects = trailer.long.toInt()
        val topObject = trailer.long.toInt()
        val offsetTableOffset = trailer.long.toInt()

        require(offsetIntSize in 1..8 && objectRefSize in 1..8) {
            "Invalid bplist trailer sizes: offsetIntSize=$offsetIntSize, objectRefSize=$objectRefSize"
        }

        val offsets = IntArray(numObjects)
        for (i in 0 until numObjects) {
            val pos = offsetTableOffset + i * offsetIntSize
            offsets[i] = readUIntBE(bytes, pos, offsetIntSize).toInt()
        }

        fun parseObject(ref: Int): Any? {
            val offset = offsets[ref]
            val marker = bytes[offset].toInt() and 0xFF
            val high = (marker shr 4) and 0x0F
            val low = marker and 0x0F

            return when (high) {
                0x0 -> when (low) {
                    0x0 -> null
                    0x8 -> false
                    0x9 -> true
                    else -> null
                }
                0x1 -> {
                    val byteCount = 1 shl low
                    if (byteCount == 16) {
                        // 128-bit int: take lower 64 bits
                        readSignedLongBE(bytes, offset + 9, 8)
                    } else if (byteCount == 8) {
                        readSignedLongBE(bytes, offset + 1, 8)
                    } else {
                        readUIntBE(bytes, offset + 1, byteCount)
                    }
                }
                0x2 -> {
                    val byteCount = 1 shl low
                    val buf = ByteBuffer.wrap(bytes, offset + 1, byteCount).order(ByteOrder.BIG_ENDIAN)
                    when (byteCount) {
                        4 -> buf.float.toDouble()
                        8 -> buf.double
                        else -> error("Unsupported real byteCount: $byteCount")
                    }
                }
                0x3 -> {
                    // Apple NSDate (seconds since 2001-01-01T00:00:00Z)
                    ByteBuffer.wrap(bytes, offset + 1, 8).order(ByteOrder.BIG_ENDIAN).double
                }
                0x4 -> {
                    val (count, dataStart) = readCount(bytes, offset, low)
                    bytes.copyOfRange(dataStart, dataStart + count)
                }
                0x5 -> {
                    val (count, dataStart) = readCount(bytes, offset, low)
                    String(bytes, dataStart, count, StandardCharsets.US_ASCII)
                }
                0x6 -> {
                    val (count, dataStart) = readCount(bytes, offset, low)
                    String(bytes, dataStart, count * 2, StandardCharsets.UTF_16BE)
                }
                0xA -> {
                    val (count, dataStart) = readCount(bytes, offset, low)
                    val list = ArrayList<Any?>(count)
                    for (i in 0 until count) {
                        val elemRef = readUIntBE(bytes, dataStart + i * objectRefSize, objectRefSize).toInt()
                        list.add(parseObject(elemRef))
                    }
                    list
                }
                0xD -> {
                    val (count, dataStart) = readCount(bytes, offset, low)
                    val map = LinkedHashMap<String, Any?>(count)
                    val valStart = dataStart + count * objectRefSize
                    for (i in 0 until count) {
                        val keyRef = readUIntBE(bytes, dataStart + i * objectRefSize, objectRefSize).toInt()
                        val valRef = readUIntBE(bytes, valStart + i * objectRefSize, objectRefSize).toInt()
                        val key = parseObject(keyRef)?.toString() ?: ""
                        map[key] = parseObject(valRef)
                    }
                    map
                }
                else -> error("Unsupported bplist marker: 0x${marker.toString(16)}")
            }
        }

        return parseObject(topObject)
    }

    private fun writeObject(
        out: ByteArrayOutputStream,
        value: Any?,
        objectRefSize: Int,
        refOf: (Any?) -> Int
    ) {
        when (value) {
            null -> out.write(0x00)
            is Boolean -> out.write(if (value) 0x09 else 0x08)
            is Int -> writeInteger(out, value.toLong())
            is Long -> writeInteger(out, value)
            is Float -> {
                out.write(0x22)
                out.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putFloat(value).array())
            }
            is Double -> {
                out.write(0x23)
                out.write(ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putDouble(value).array())
            }
            is ByteArray -> {
                writeMarkerAndCount(out, 0x40, value.size)
                out.write(value)
            }
            is String -> {
                val isAscii = value.all { it.code < 128 }
                if (isAscii) {
                    val raw = value.toByteArray(StandardCharsets.US_ASCII)
                    writeMarkerAndCount(out, 0x50, raw.size)
                    out.write(raw)
                } else {
                    val raw = value.toByteArray(StandardCharsets.UTF_16BE)
                    writeMarkerAndCount(out, 0x60, value.length)
                    out.write(raw)
                }
            }
            is List<*> -> {
                writeMarkerAndCount(out, 0xA0, value.size)
                for (item in value) {
                    writeIntBE(out, refOf(item).toLong(), objectRefSize)
                }
            }
            is Map<*, *> -> {
                writeMarkerAndCount(out, 0xD0, value.size)
                for ((k, _) in value) {
                    writeIntBE(out, refOf(k.toString()).toLong(), objectRefSize)
                }
                for ((_, v) in value) {
                    writeIntBE(out, refOf(v).toLong(), objectRefSize)
                }
            }
            else -> {
                val s = value.toString()
                val raw = s.toByteArray(StandardCharsets.UTF_16BE)
                writeMarkerAndCount(out, 0x60, s.length)
                out.write(raw)
            }
        }
    }

    private fun writeInteger(out: ByteArrayOutputStream, value: Long) {
        when {
            value in 0..0xFF -> {
                out.write(0x10)
                out.write(value.toInt())
            }
            value in 0..0xFFFF -> {
                out.write(0x11)
                writeIntBE(out, value, 2)
            }
            value in 0..0xFFFFFFFFL -> {
                out.write(0x12)
                writeIntBE(out, value, 4)
            }
            else -> {
                out.write(0x13)
                writeIntBE(out, value, 8)
            }
        }
    }

    private fun writeMarkerAndCount(out: ByteArrayOutputStream, typeNibble: Int, count: Int) {
        if (count < 15) {
            out.write(typeNibble or count)
        } else {
            out.write(typeNibble or 0x0F)
            writeInteger(out, count.toLong())
        }
    }

    private fun readCount(bytes: ByteArray, offset: Int, low: Int): Pair<Int, Int> {
        if (low < 0x0F) return low to (offset + 1)
        val intMarker = bytes[offset + 1].toInt() and 0xFF
        require((intMarker shr 4) == 0x1) { "Expected int marker for extended count" }
        val byteCount = 1 shl (intMarker and 0x0F)
        val count = readUIntBE(bytes, offset + 2, byteCount).toInt()
        return count to (offset + 2 + byteCount)
    }

    private fun bytesNeeded(value: Long): Int = when {
        value <= 0xFF -> 1
        value <= 0xFFFF -> 2
        value <= 0xFFFFFFFFL -> 4
        else -> 8
    }

    private fun writeIntBE(out: ByteArrayOutputStream, value: Long, byteCount: Int) {
        for (i in (byteCount - 1) downTo 0) {
            out.write(((value ushr (i * 8)) and 0xFF).toInt())
        }
    }

    private fun readUIntBE(bytes: ByteArray, offset: Int, byteCount: Int): Long {
        var result = 0L
        for (i in 0 until byteCount) {
            result = (result shl 8) or (bytes[offset + i].toLong() and 0xFFL)
        }
        return result
    }

    private fun readSignedLongBE(bytes: ByteArray, offset: Int, byteCount: Int): Long {
        var result = 0L
        for (i in 0 until byteCount) {
            result = (result shl 8) or (bytes[offset + i].toLong() and 0xFFL)
        }
        return result
    }

    private class IdentityHashMapWrapper {
        private val refs = java.util.IdentityHashMap<Any, Int>()
        private var nullRef: Int? = null

        fun get(key: Any?): Int? = if (key == null) nullRef else refs[key]
        fun put(key: Any?, value: Int) {
            if (key == null) nullRef = value else refs[key] = value
        }
    }
}
