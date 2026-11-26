package com.airbridge.drop.protocol.discovery

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.aware.AttachCallback
import android.net.wifi.aware.DiscoverySession
import android.net.wifi.aware.DiscoverySessionCallback
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.PublishConfig
import android.net.wifi.aware.PublishDiscoverySession
import android.net.wifi.aware.SubscribeConfig
import android.net.wifi.aware.SubscribeDiscoverySession
import android.net.wifi.aware.WifiAwareManager
import android.net.wifi.aware.WifiAwareNetworkInfo
import android.net.wifi.aware.WifiAwareNetworkSpecifier
import android.net.wifi.aware.WifiAwareSession
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.airbridge.drop.protocol.model.AirDropPeer
import com.airbridge.drop.protocol.model.DevicePlatform
import com.airbridge.drop.protocol.model.TransportType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.charset.StandardCharsets

/**
 * Wi-Fi Aware (Neighbor Awareness Networking / NAN) engine for direct, router-free
 * peer-to-peer discovery and high-throughput IPv6 data paths (Android 10+ & iOS 26+/macOS 26+).
 */
class WifiAwareEngine(
    private val context: Context,
    private val onPeerDiscovered: (AirDropPeer) -> Unit,
    private val onStatusChanged: (supported: Boolean, attached: Boolean) -> Unit
) {
    private val wifiAwareManager = context.getSystemService(Context.WIFI_AWARE_SERVICE) as? WifiAwareManager
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var awareSession: WifiAwareSession? = null
    private var publishSession: PublishDiscoverySession? = null
    private var subscribeSession: SubscribeDiscoverySession? = null
    private val activeNetworkCallbacks = mutableListOf<ConnectivityManager.NetworkCallback>()

    val isSupported: Boolean
        get() = context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE) &&
            wifiAwareManager != null

    val isAvailable: Boolean
        get() = isSupported && (wifiAwareManager?.isAvailable == true)

    @Volatile
    var isAttached: Boolean = false
        private set

    fun start(deviceName: String, modelName: String, httpsPort: Int, publishVisible: Boolean) {
        onStatusChanged(isSupported, false)
        if (!isAvailable) {
            Log.i(TAG, "Wi-Fi Aware not supported or unavailable on this hardware")
            return
        }

        wifiAwareManager?.attach(object : AttachCallback() {
            override fun onAttached(session: WifiAwareSession) {
                awareSession = session
                isAttached = true
                onStatusChanged(isSupported, true)
                startSubscribing(session)
                if (publishVisible) {
                    startPublishing(session, deviceName, modelName, httpsPort)
                }
            }

            override fun onAttachFailed() {
                Log.w(TAG, "Wi-Fi Aware attach failed")
                isAttached = false
                onStatusChanged(isSupported, false)
            }
        }, mainHandler)
    }

    fun updatePublishVisibility(
        visible: Boolean,
        deviceName: String,
        modelName: String,
        httpsPort: Int
    ) {
        val session = awareSession ?: return
        if (visible && publishSession == null) {
            startPublishing(session, deviceName, modelName, httpsPort)
        } else if (!visible && publishSession != null) {
            runCatching { publishSession?.close() }
            publishSession = null
        }
    }

    @SuppressLint("MissingPermission")
    private fun startPublishing(
        session: WifiAwareSession,
        deviceName: String,
        modelName: String,
        httpsPort: Int
    ) {
        val ssi = "$deviceName|$modelName|$httpsPort".toByteArray(StandardCharsets.UTF_8)
        val config = PublishConfig.Builder()
            .setServiceName(SERVICE_NAME)
            .setServiceSpecificInfo(ssi)
            .build()

        runCatching {
            session.publish(config, object : DiscoverySessionCallback() {
                override fun onPublishStarted(session: PublishDiscoverySession) {
                    publishSession = session
                    Log.i(TAG, "Wi-Fi Aware publishing started on $SERVICE_NAME")
                }

                override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                    // Responder opens a NAN data path when initiator requests transfer
                    val pub = publishSession ?: return
                    openResponderDataPath(pub, peerHandle)
                }
            }, mainHandler)
        }.onFailure { e ->
            Log.w(TAG, "Failed to start Wi-Fi Aware publish: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startSubscribing(session: WifiAwareSession) {
        val config = SubscribeConfig.Builder()
            .setServiceName(SERVICE_NAME)
            .build()

        runCatching {
            session.subscribe(config, object : DiscoverySessionCallback() {
                override fun onSubscribeStarted(session: SubscribeDiscoverySession) {
                    subscribeSession = session
                    Log.i(TAG, "Wi-Fi Aware subscribing started on $SERVICE_NAME")
                }

                override fun onServiceDiscovered(
                    peerHandle: PeerHandle,
                    serviceSpecificInfo: ByteArray?,
                    matchFilter: List<ByteArray>?
                ) {
                    val infoStr = serviceSpecificInfo?.toString(StandardCharsets.UTF_8).orEmpty()
                    val parts = infoStr.split('|')
                    val name = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: "Wi-Fi Aware Device"
                    val model = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: "NAN Peer"
                    val port = parts.getOrNull(2)?.toIntOrNull() ?: AirDropPeer.DEFAULT_AIRDROP_PORT

                    onPeerDiscovered(
                        AirDropPeer(
                            id = "aware:${peerHandle.hashCode()}",
                            displayName = name,
                            modelName = model,
                            platform = AirDropPeer.inferPlatform(model, name),
                            transport = TransportType.WIFI_AWARE,
                            port = port,
                            awarePeerHandle = peerHandle,
                            lastSeenMs = System.currentTimeMillis()
                        )
                    )
                }
            }, mainHandler)
        }.onFailure { e ->
            Log.w(TAG, "Failed to start Wi-Fi Aware subscribe: ${e.message}")
        }
    }

    /**
     * Establishes a direct Wi-Fi Aware Neighbor Data Path (NDP) to [peerHandle] and returns
     * the bound [Network] + peer IPv6 address + port for TLS socket connection.
     */
    suspend fun establishDataPath(peerHandle: PeerHandle, fallbackPort: Int): Pair<Network, Pair<String, Int>>? {
        val sub = subscribeSession ?: return null
        // Wake responder to accept NDP
        runCatching { sub.sendMessage(peerHandle, 1, "AIRDROP_NDP".toByteArray()) }

        val deferred = CompletableDeferred<Pair<Network, Pair<String, Int>>?>()
        val specifier = WifiAwareNetworkSpecifier.Builder(sub, peerHandle).build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
            .setNetworkSpecifier(specifier)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val info = caps.transportInfo as? WifiAwareNetworkInfo
                val ipv6 = info?.peerIpv6Addr?.hostAddress
                val port = info?.port?.takeIf { it > 0 } ?: fallbackPort
                if (ipv6 != null && !deferred.isCompleted) {
                    deferred.complete(network to (ipv6 to port))
                }
            }

            override fun onUnavailable() {
                if (!deferred.isCompleted) deferred.complete(null)
            }
        }

        synchronized(activeNetworkCallbacks) { activeNetworkCallbacks.add(callback) }
        connectivityManager.requestNetwork(request, callback, 10_000)

        return withTimeoutOrNull(12_000L) { deferred.await() }
    }

    private fun openResponderDataPath(pub: DiscoverySession, peerHandle: PeerHandle) {
        val specifier = WifiAwareNetworkSpecifier.Builder(pub, peerHandle).build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
            .setNetworkSpecifier(specifier)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {}
        synchronized(activeNetworkCallbacks) { activeNetworkCallbacks.add(callback) }
        runCatching { connectivityManager.requestNetwork(request, callback, 15_000) }
    }

    fun stop() {
        runCatching { publishSession?.close() }
        runCatching { subscribeSession?.close() }
        runCatching { awareSession?.close() }
        publishSession = null
        subscribeSession = null
        awareSession = null
        synchronized(activeNetworkCallbacks) {
            activeNetworkCallbacks.forEach { cb ->
                runCatching { connectivityManager.unregisterNetworkCallback(cb) }
            }
            activeNetworkCallbacks.clear()
        }
        isAttached = false
        onStatusChanged(isSupported, false)
    }

    companion object {
        private const val TAG = "WifiAwareEngine"
        const val SERVICE_NAME = "_airdrop._tcp"
    }
}
