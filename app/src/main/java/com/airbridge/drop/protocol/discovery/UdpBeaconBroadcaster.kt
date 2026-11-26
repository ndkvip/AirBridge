package com.airbridge.drop.protocol.discovery

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.nio.charset.StandardCharsets

/**
 * Broadcasts a lightweight UDP discovery beacon on port 8772 so the macOS AirBridge app
 * instantly discovers this phone by name and auto-reconnects even if its Wi-Fi IP changes.
 */
class UdpBeaconBroadcaster(
    private val deviceId: String,
    private val deviceNameProvider: () -> String,
    private val modelName: String,
    private val webPortProvider: () -> Int,
    private val tlsPortProvider: () -> Int = { 0 },
    private val certSha256Provider: () -> String = { "" }
) {
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        stop()
        job = scope.launch(Dispatchers.IO) {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket().apply {
                    broadcast = true
                }
                var burstCount = 0
                while (isActive) {
                    val targets = collectBroadcastAddresses()
                    if (targets.isNotEmpty()) {
                        val payload = JSONObject()
                            .put("app", "AirBridge")
                            .put("deviceId", deviceId)
                            .put("name", deviceNameProvider())
                            .put("model", modelName)
                            .put("webPort", webPortProvider())
                            .put("tlsPort", tlsPortProvider())
                            .put("certSha256", certSha256Provider())
                            .toString()
                            .toByteArray(StandardCharsets.UTF_8)

                        for (addr in targets) {
                            runCatching {
                                val packet = DatagramPacket(payload, payload.size, addr, DISCOVERY_UDP_PORT)
                                socket.send(packet)
                            }
                        }
                    }
                    // Fast discovery burst on startup, then low-power 10s interval
                    val intervalMs = if (burstCount < 3) {
                        burstCount++
                        2_000L
                    } else {
                        10_000L
                    }
                    delay(intervalMs)
                }
            } catch (_: Exception) {
                // Ignore socket close on stop
            } finally {
                runCatching { socket?.close() }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun collectBroadcastAddresses(): List<InetAddress> {
        val list = LinkedHashSet<InetAddress>()
        runCatching {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching
            for (iface in ifaces.toList()) {
                if (!iface.isUp || iface.isLoopback) continue
                for (ia in iface.interfaceAddresses) {
                    ia.broadcast?.let { list.add(it) }
                }
            }
        }
        runCatching { list.add(InetAddress.getByName("255.255.255.255")) }
        return list.toList()
    }

    companion object {
        const val DISCOVERY_UDP_PORT = 8772
    }
}
