package com.airbridge.drop.protocol.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import com.airbridge.drop.protocol.model.AirDropPeer
import com.airbridge.drop.protocol.model.TransportType
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/**
 * Standard mDNS / DNS-SD engine registering and discovering `_airdrop._tcp.` services
 * with AirDrop-compatible TXT records (`flags=1019`).
 */
class AirDropMdnsEngine(
    private val context: Context,
    private val localServiceId: String,
    private val onPeerResolved: (AirDropPeer) -> Unit,
    private val onPeerLost: (serviceId: String) -> Unit,
    private val onStatusChanged: (registered: Boolean, discovering: Boolean) -> Unit
) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val resolveExecutor = Executors.newSingleThreadExecutor()

    private var multicastLock: WifiManager.MulticastLock? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    @Volatile
    var isRegistered: Boolean = false
        private set

    @Volatile
    var isDiscovering: Boolean = false
        private set

    fun startDiscovery() {
        if (isDiscovering) return
        acquireMulticastLock()

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                isDiscovering = true
                onStatusChanged(isRegistered, isDiscovering)
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (!serviceInfo.serviceType.contains("_airdrop")) return
                if (serviceInfo.serviceName.equals(localServiceId, ignoreCase = true)) return
                resolveService(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                onPeerLost("mdns:${serviceInfo.serviceName}")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                isDiscovering = false
                onStatusChanged(isRegistered, isDiscovering)
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "mDNS discovery start failed: $errorCode")
                isDiscovering = false
                onStatusChanged(isRegistered, isDiscovering)
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                isDiscovering = false
                onStatusChanged(isRegistered, isDiscovering)
            }
        }

        discoveryListener = listener
        runCatching {
            nsdManager.discoverServices(AIRDROP_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure { e ->
            Log.w(TAG, "Failed to start mDNS discovery: ${e.message}")
        }
    }

    fun registerReceiver(port: Int, deviceName: String, modelName: String) {
        if (isRegistered) return
        acquireMulticastLock()

        val info = NsdServiceInfo().apply {
            serviceName = localServiceId
            serviceType = AIRDROP_SERVICE_TYPE
            setPort(port)
            setAttribute("flags", AirDropPeer.DEFAULT_AIRDROP_FLAGS.toString())
            setAttribute("name", deviceName)
            setAttribute("model", modelName)
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                isRegistered = true
                onStatusChanged(isRegistered, isDiscovering)
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "mDNS registration failed: $errorCode")
                isRegistered = false
                onStatusChanged(isRegistered, isDiscovering)
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                isRegistered = false
                onStatusChanged(isRegistered, isDiscovering)
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                isRegistered = false
                onStatusChanged(isRegistered, isDiscovering)
            }
        }

        registrationListener = listener
        runCatching {
            nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure { e ->
            Log.w(TAG, "Failed to register mDNS service: ${e.message}")
        }
    }

    fun unregisterReceiver() {
        val listener = registrationListener ?: return
        registrationListener = null
        runCatching { nsdManager.unregisterService(listener) }
        isRegistered = false
        onStatusChanged(isRegistered, isDiscovering)
    }

    fun stopAll() {
        unregisterReceiver()
        discoveryListener?.let { listener ->
            discoveryListener = null
            runCatching { nsdManager.stopServiceDiscovery(listener) }
        }
        isDiscovering = false
        releaseMulticastLock()
        onStatusChanged(false, false)
    }

    @Suppress("DEPRECATION")
    private fun resolveService(serviceInfo: NsdServiceInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val cb = object : NsdManager.ServiceInfoCallback {
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {}
                override fun onServiceUpdated(info: NsdServiceInfo) {
                    emitResolved(info)
                    runCatching { nsdManager.unregisterServiceInfoCallback(this) }
                }
                override fun onServiceLost() {}
                override fun onServiceInfoCallbackUnregistered() {}
            }
            runCatching { nsdManager.registerServiceInfoCallback(serviceInfo, resolveExecutor, cb) }
        } else {
            resolveExecutor.execute {
                runCatching {
                    nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                            Log.d(TAG, "mDNS resolve failed for ${info.serviceName}: $errorCode")
                        }

                        override fun onServiceResolved(info: NsdServiceInfo) {
                            emitResolved(info)
                        }
                    })
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun emitResolved(info: NsdServiceInfo) {
        val host = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            info.hostAddresses.firstOrNull()?.hostAddress
        } else {
            info.host?.hostAddress
        } ?: return

        val attrs = info.attributes
        val flags = attrs["flags"]?.toString(StandardCharsets.UTF_8)?.toIntOrNull()
            ?: AirDropPeer.DEFAULT_AIRDROP_FLAGS
        val txtName = attrs["name"]?.toString(StandardCharsets.UTF_8)?.takeIf { it.isNotBlank() }
        val txtModel = attrs["model"]?.toString(StandardCharsets.UTF_8)?.takeIf { it.isNotBlank() }

        val displayName = txtName ?: "AirDrop (${info.serviceName.take(8)})"
        val modelName = txtModel ?: "AirDrop Peer"

        onPeerResolved(
            AirDropPeer(
                id = "mdns:${info.serviceName}",
                displayName = displayName,
                modelName = modelName,
                platform = AirDropPeer.inferPlatform(modelName, displayName),
                transport = TransportType.MDNS_LAN,
                hostAddress = host,
                port = info.port.takeIf { it > 0 } ?: AirDropPeer.DEFAULT_AIRDROP_PORT,
                flags = flags,
                lastSeenMs = System.currentTimeMillis()
            )
        )
    }

    private fun acquireMulticastLock() {
        if (multicastLock?.isHeld == true) return
        runCatching {
            multicastLock = wifiManager?.createMulticastLock("AirBridgeMdnsLock")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseMulticastLock() {
        runCatching {
            if (multicastLock?.isHeld == true) multicastLock?.release()
        }
        multicastLock = null
    }

    companion object {
        private const val TAG = "AirDropMdnsEngine"
        const val AIRDROP_SERVICE_TYPE = "_airdrop._tcp."
    }
}
