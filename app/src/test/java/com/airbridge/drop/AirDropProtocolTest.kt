package com.airbridge.drop

import com.airbridge.drop.protocol.ble.AirDropBleEngine
import com.airbridge.drop.protocol.codec.BinaryPlistCodec
import com.airbridge.drop.protocol.codec.CpioArchiveCodec
import com.airbridge.drop.protocol.http.AirDropClient
import com.airbridge.drop.protocol.http.AirDropServer
import com.airbridge.drop.protocol.http.AirDropTlsContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

class AirDropProtocolTest {

    @Test
    fun binaryPlistRoundTripSupportsAirDropAskPayload() {
        val original = mapOf(
            "SenderComputerName" to "Khánh's Pixel",
            "SenderModelName" to "Google Pixel 10",
            "ConvertMediaFormats" to false,
            "TotalSize" to 123456L,
            "FileIcon" to byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01, 0x02),
            "Files" to listOf(
                mapOf(
                    "FileName" to "ảnh_du_lịch.jpg",
                    "FileType" to "public.jpeg",
                    "FileBomPath" to "./ảnh_du_lịch.jpg",
                    "FileIsDirectory" to false
                )
            )
        )

        val encoded = BinaryPlistCodec.encode(original)
        assertEquals("bplist00", String(encoded, 0, 8, Charsets.US_ASCII))

        @Suppress("UNCHECKED_CAST")
        val decoded = BinaryPlistCodec.decode(encoded) as Map<String, Any?>
        assertEquals("Khánh's Pixel", decoded["SenderComputerName"])
        assertEquals("Google Pixel 10", decoded["SenderModelName"])
        assertEquals(false, decoded["ConvertMediaFormats"])
        assertEquals(123456L, decoded["TotalSize"])
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01, 0x02), decoded["FileIcon"] as ByteArray)

        @Suppress("UNCHECKED_CAST")
        val files = decoded["Files"] as List<Map<String, Any?>>
        assertEquals(1, files.size)
        assertEquals("ảnh_du_lịch.jpg", files[0]["FileName"])
        assertEquals("public.jpeg", files[0]["FileType"])
    }

    @Test
    fun cpioGzipRoundTripExtractsMultipleFilesAndSanitizesPaths() {
        val tempDir = Files.createTempDirectory("airbridge-cpio").toFile()
        try {
            val data1 = "Xin chào AirDrop từ Android!".toByteArray(Charsets.UTF_8)
            val data2 = ByteArray(100_000) { (it % 251).toByte() }

            val out = ByteArrayOutputStream()
            CpioArchiveCodec.writeGzippedCpio(
                entries = listOf(
                    CpioArchiveCodec.CpioSourceEntry("../hello.txt", data1.size.toLong()) {
                        ByteArrayInputStream(data1)
                    },
                    CpioArchiveCodec.CpioSourceEntry("nested/photo.bin", data2.size.toLong()) {
                        ByteArrayInputStream(data2)
                    }
                ),
                output = out
            )

            val extracted = CpioArchiveCodec.extractGzippedCpio(
                input = ByteArrayInputStream(out.toByteArray()),
                destinationDir = tempDir,
                isGzipped = true
            )

            assertEquals(2, extracted.size)
            assertArrayEquals(data1, File(tempDir, "hello.txt").readBytes())
            assertArrayEquals(data2, File(tempDir, "nested/photo.bin").readBytes())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun bleBeaconPayloadMatchesAppleContinuityAirDropFormat() {
        val payload = AirDropBleEngine.buildAirDropBeaconPayload("KhanhPixel")
        assertEquals(20, payload.size)
        assertEquals(AirDropBleEngine.CONTINUITY_TYPE_AIRDROP, payload[0])
        assertEquals(AirDropBleEngine.AIRDROP_PAYLOAD_LENGTH, payload[1])
        assertEquals(0x01.toByte(), payload[10])
        assertTrue(AirDropBleEngine.isAirDropAdvertisement(payload))
        assertFalse(AirDropBleEngine.isAirDropAdvertisement(byteArrayOf(0x10, 0x05, 0x01, 0x02)))
    }

    @Test
    fun endToEndAirDropTlsDiscoverAskUploadAndDecline() = runBlocking {
        val tempDir = Files.createTempDirectory("airbridge-e2e").toFile()
        val sslContext = AirDropTlsContext.createSslContext("AirBridgeTest")
        var shouldAccept = true
        val receivedDeferred = CompletableDeferred<List<CpioArchiveCodec.ExtractedFile>>()

        val server = AirDropServer(
            sslContext = sslContext,
            deviceNameProvider = { "MacBook Pro Test" },
            modelName = "MacBookPro18,1",
            destinationDirProvider = { tempDir },
            onAskRequest = { shouldAccept },
            onReceiveProgress = { _, _, _, _ -> },
            onReceiveComplete = { _, _, files ->
                if (!receivedDeferred.isCompleted) receivedDeferred.complete(files)
            },
            onReceiveError = { _, _, err ->
                if (!receivedDeferred.isCompleted) receivedDeferred.completeExceptionally(RuntimeException(err))
            }
        )

        val port = server.start(this, preferredPort = 0)
        try {
            val client = AirDropClient(
                sslContext = sslContext,
                senderNameProvider = { "Android Test" },
                senderModel = "Pixel 10"
            )

            // 1. Verify /Discover
            val info = client.discoverPeer("127.0.0.1", port)
            assertNotNull(info)
            assertEquals("MacBook Pro Test", info!!.receiverName)
            assertEquals("MacBookPro18,1", info.receiverModel)

            // 2. Verify /Ask -> Accept -> /Upload
            val payload = "Nội dung gửi qua AirDrop TLS + CPIO".toByteArray(Charsets.UTF_8)
            val acceptResult = client.sendFiles(
                host = "127.0.0.1",
                port = port,
                files = listOf(
                    AirDropClient.SendFileDescriptor(
                        fileName = "test_note.txt",
                        uti = "public.plain-text",
                        sizeBytes = payload.size.toLong(),
                        openStream = { ByteArrayInputStream(payload) }
                    )
                )
            )
            assertEquals(AirDropClient.SendResult.Completed, acceptResult)
            val extracted = receivedDeferred.await()
            assertEquals(1, extracted.size)
            assertArrayEquals(payload, extracted[0].file.readBytes())

            // 3. Verify /Ask -> Decline
            shouldAccept = false
            val declineResult = client.sendFiles(
                host = "127.0.0.1",
                port = port,
                files = listOf(
                    AirDropClient.SendFileDescriptor(
                        fileName = "declined.txt",
                        uti = "public.plain-text",
                        sizeBytes = payload.size.toLong(),
                        openStream = { ByteArrayInputStream(payload) }
                    )
                )
            )
            assertEquals(AirDropClient.SendResult.Declined, declineResult)
        } finally {
            server.stop()
            tempDir.deleteRecursively()
        }
    }
}
