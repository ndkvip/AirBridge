package com.airbridge.drop.protocol.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.airbridge.drop.protocol.model.AirDropPeer
import com.airbridge.drop.protocol.model.DevicePlatform
import com.airbridge.drop.protocol.model.TransportType
import java.security.MessageDigest

/**
 * Handles Apple Continuity AirDrop BLE discovery and advertising:
 * - Company ID: 0x004C (Apple, Inc.)
 * - Continuity Message Type: 0x05 (AirDrop)
 * - Payload Length: 0x12 (18 bytes): 8 zero padding bytes + version (0x01) +
 *   4 x 2-byte truncated SHA-256 identity hashes + 1 trailing zero byte.
 */
class AirDropBleEngine(
    private val context: Context,
    private val onBlePeerDetected: (AirDropPeer) -> Unit,
    private val onStatusChanged: (scanning: Boolean, advertising: Boolean) -> Unit
) {
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private var scanner: BluetoothLeScanner? = null
    private var advertiser: BluetoothLeAdvertiser? = null

    @Volatile
    var isScanning: Boolean = false
        private set

    @Volatile
    var isAdvertising: Boolean = false
        private set

    val isSupported: Boolean
        get() = context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) &&
            bluetoothManager?.adapter != null

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handleScanResult(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach(::handleScanResult)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "BLE scan failed with errorCode=$errorCode")
            isScanning = false
            onStatusChanged(isScanning, isAdvertising)
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            isAdvertising = true
            onStatusChanged(isScanning, isAdvertising)
        }

        override fun onStartFailure(errorCode: Int) {
            Log.w(TAG, "BLE advertise failed with errorCode=$errorCode")
            isAdvertising = false
            onStatusChanged(isScanning, isAdvertising)
        }
    }

    @SuppressLint("MissingPermission")
    fun startScanning() {
        if (!isSupported || isScanning) return
        val adapter = bluetoothManager?.adapter ?: return
        if (!adapter.isEnabled) return

        runCatching {
            scanner = adapter.bluetoothLeScanner
            val filter = ScanFilter.Builder()
                .setManufacturerData(APPLE_COMPANY_ID, byteArrayOf(CONTINUITY_TYPE_AIRDROP))
                .build()
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            scanner?.startScan(listOf(filter), settings, scanCallback)
            isScanning = true
            onStatusChanged(isScanning, isAdvertising)
        }.onFailure { e ->
            Log.w(TAG, "Unable to start BLE scan: ${e.message}")
            isScanning = false
            onStatusChanged(isScanning, isAdvertising)
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScanning() {
        if (!isScanning) return
        runCatching { scanner?.stopScan(scanCallback) }
        isScanning = false
        onStatusChanged(isScanning, isAdvertising)
    }

    @SuppressLint("MissingPermission")
    fun startAdvertising(identitySeed: String) {
        if (!isSupported || isAdvertising) return
        val adapter = bluetoothManager?.adapter ?: return
        if (!adapter.isEnabled) return

        runCatching {
            advertiser = adapter.bluetoothLeAdvertiser ?: return
            val payload = buildAirDropBeaconPayload(identitySeed)
            val data = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .addManufacturerData(APPLE_COMPANY_ID, payload)
                .build()
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(false)
                .build()
            advertiser?.startAdvertising(settings, data, advertiseCallback)
        }.onFailure { e ->
            Log.w(TAG, "Unable to start BLE advertising: ${e.message}")
            isAdvertising = false
            onStatusChanged(isScanning, isAdvertising)
        }
    }

    @SuppressLint("MissingPermission")
    fun stopAdvertising() {
        if (!isAdvertising) return
        runCatching { advertiser?.stopAdvertising(advertiseCallback) }
        isAdvertising = false
        onStatusChanged(isScanning, isAdvertising)
    }

    @SuppressLint("MissingPermission")
    private fun handleScanResult(result: ScanResult) {
        val mfg = result.scanRecord?.getManufacturerSpecificData(APPLE_COMPANY_ID) ?: return
        if (!isAirDropAdvertisement(mfg)) return

        val mac = result.device?.address ?: return
        val bleName = result.scanRecord?.deviceName ?: result.device?.name
        val shortId = mac.replace(":", "").takeLast(4)
        val displayName = bleName?.takeIf { it.isNotBlank() } ?: "Apple AirDrop ($shortId)"

        onBlePeerDetected(
            AirDropPeer(
                id = "ble:$mac",
                displayName = displayName,
                modelName = "Apple Device (BLE Beacon)",
                platform = AirDropPeer.inferPlatform(bleName, displayName)
                    .let { if (it == DevicePlatform.UNKNOWN) DevicePlatform.IPHONE else it },
                transport = TransportType.BLE_ONLY,
                rssi = result.rssi,
                bleAddress = mac,
                lastSeenMs = System.currentTimeMillis()
            )
        )
    }

    companion object {
        private const val TAG = "AirDropBleEngine"
        const val APPLE_COMPANY_ID = 0x004C
        const val CONTINUITY_TYPE_AIRDROP: Byte = 0x05
        const val AIRDROP_PAYLOAD_LENGTH: Byte = 0x12 // 18 bytes

        /**
         * Builds the 20-byte Apple Continuity AirDrop TLV:
         * [0x05, 0x12, 8x 0x00, 0x01, hash1(2B), hash2(2B), hash3(2B), hash4(2B), 0x00]
         */
        fun buildAirDropBeaconPayload(identitySeed: String): ByteArray {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(identitySeed.toByteArray(Charsets.UTF_8))
            val out = ByteArray(20)
            out[0] = CONTINUITY_TYPE_AIRDROP
            out[1] = AIRDROP_PAYLOAD_LENGTH
            // bytes 2..9 = 0x00 (reserved)
            out[10] = 0x01 // AirDrop protocol version 1
            // 4 x 2-byte truncated identity hashes (bytes 11..18)
            for (i in 0 until 8) {
                out[11 + i] = digest[i]
            }
            out[19] = 0x00
            return out
        }

        fun isAirDropAdvertisement(mfgData: ByteArray): Boolean {
            var idx = 0
            while (idx + 1 < mfgData.size) {
                val type = mfgData[idx]
                val len = mfgData[idx + 1].toInt() and 0xFF
                if (type == CONTINUITY_TYPE_AIRDROP && len >= 18 && idx + 2 + len <= mfgData.size) {
                    return true
                }
                if (len <= 0) break
                idx += 2 + len
            }
            return false
        }
    }
}
