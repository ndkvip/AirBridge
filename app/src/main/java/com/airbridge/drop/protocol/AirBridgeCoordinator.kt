package com.airbridge.drop.protocol

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import com.airbridge.drop.protocol.ble.AirDropBleEngine
import com.airbridge.drop.protocol.clipboard.ClipboardEntry
import com.airbridge.drop.protocol.clipboard.ClipboardSyncManager
import com.airbridge.drop.protocol.discovery.AirDropMdnsEngine
import com.airbridge.drop.protocol.discovery.UdpBeaconBroadcaster
import com.airbridge.drop.protocol.discovery.WifiAwareEngine
import com.airbridge.drop.protocol.http.AirDropClient
import com.airbridge.drop.protocol.http.AirDropServer
import com.airbridge.drop.protocol.http.AirDropTlsContext
import com.airbridge.drop.protocol.media.ScreenStreamManager
import com.airbridge.drop.protocol.model.AirDropPeer
import com.airbridge.drop.protocol.model.IncomingTransferRequest
import com.airbridge.drop.protocol.model.OutgoingFile
import com.airbridge.drop.protocol.model.ProtocolDiagnostics
import com.airbridge.drop.protocol.model.TransferDirection
import com.airbridge.drop.protocol.model.TransferProgress
import com.airbridge.drop.protocol.model.TransferState
import com.airbridge.drop.protocol.model.TransportType
import com.airbridge.drop.protocol.model.VisibilityMode
import com.airbridge.drop.protocol.web.WebBridgeServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Central coordinator that unifies:
 * - AirDrop protocol stack (BLE 0x004C/0x05, Wi-Fi Aware, mDNS _airdrop._tcp, HTTPS/TLS 8770)
 * - O+ Connect-style Web Portal (HTTP 8771: File Explorer, 2-way Drop, 2-way Clipboard, Live Screen Mirroring)
 */
class AirBridgeCoordinator private constructor(private val appContext: Context) {

    private val prefs = appContext.getSharedPreferences("airbridge_prefs", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val localServiceId: String = prefs.getString("device_id", null)
        ?: UUID.randomUUID().toString().replace("-", "").take(12).also {
            prefs.edit().putString("device_id", it).apply()
        }
    private val deviceModel = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"

    private val _deviceName = MutableStateFlow(
        prefs.getString("device_name", null) ?: (Build.MODEL ?: "Android")
    )
    val deviceName: StateFlow<String> = _deviceName.asStateFlow()

    private val _visibilityMode = MutableStateFlow(VisibilityMode.EVERYONE_10_MIN)
    val visibilityMode: StateFlow<VisibilityMode> = _visibilityMode.asStateFlow()

    private val _visibilityRemainingSec = MutableStateFlow(600)
    val visibilityRemainingSec: StateFlow<Int> = _visibilityRemainingSec.asStateFlow()

    private val _peers = MutableStateFlow<List<AirDropPeer>>(emptyList())
    val peers: StateFlow<List<AirDropPeer>> = _peers.asStateFlow()

    private val _pendingIncoming = MutableStateFlow<IncomingTransferRequest?>(null)
    val pendingIncoming: StateFlow<IncomingTransferRequest?> = _pendingIncoming.asStateFlow()

    private val _activeTransfer = MutableStateFlow<TransferProgress?>(null)
    val activeTransfer: StateFlow<TransferProgress?> = _activeTransfer.asStateFlow()

    private val _transferHistory = MutableStateFlow<List<TransferProgress>>(emptyList())
    val transferHistory: StateFlow<List<TransferProgress>> = _transferHistory.asStateFlow()

    private val _diagnostics = MutableStateFlow(ProtocolDiagnostics(deviceName = _deviceName.value))
    val diagnostics: StateFlow<ProtocolDiagnostics> = _diagnostics.asStateFlow()

    private val _webPin = MutableStateFlow(
        prefs.getString("web_pin", null) ?: generateSixDigitPin().also {
            prefs.edit().putString("web_pin", it).apply()
        }
    )
    val webPin: StateFlow<String> = _webPin.asStateFlow()

    private val _webPortalUrl = MutableStateFlow("http://0.0.0.0:${WebBridgeServer.DEFAULT_WEB_PORT}")
    val webPortalUrl: StateFlow<String> = _webPortalUrl.asStateFlow()

    val clipboardManager: ClipboardSyncManager = ClipboardSyncManager(appContext)
    val screenStreamManager: ScreenStreamManager = ScreenStreamManager(appContext)

    val isScreenStreaming: StateFlow<Boolean> = screenStreamManager.isStreaming
    val clipboardHistory: StateFlow<List<ClipboardEntry>> = clipboardManager.history

    private val pendingApprovals = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val peerMap = ConcurrentHashMap<String, AirDropPeer>()

    private var visibilityTimerJob: Job? = null
    private var pruneJob: Job? = null
    @Volatile
    private var started = false

    private val sslContext by lazy { AirDropTlsContext.createSslContext(_deviceName.value) }

    private val client: AirDropClient by lazy {
        AirDropClient(
            sslContext = sslContext,
            senderNameProvider = { _deviceName.value },
            senderModel = deviceModel
        )
    }

    private val server: AirDropServer by lazy {
        AirDropServer(
            sslContext = sslContext,
            deviceNameProvider = { _deviceName.value },
            modelName = deviceModel,
            destinationDirProvider = { getReceiveDirectory() },
            onAskRequest = { req -> awaitUserApproval(req) },
            onReceiveProgress = { reqId, senderName, bytesRead, totalBytes ->
                _activeTransfer.update { current ->
                    (current ?: TransferProgress(
                        id = reqId,
                        peerName = senderName,
                        direction = TransferDirection.RECEIVING,
                        state = TransferState.TRANSFERRING,
                        fileNames = emptyList(),
                        bytesTransferred = 0L,
                        totalBytes = totalBytes
                    )).copy(
                        state = TransferState.TRANSFERRING,
                        bytesTransferred = bytesRead,
                        totalBytes = maxOf(totalBytes, bytesRead)
                    )
                }
            },
            onReceiveComplete = { reqId, senderName, extracted ->
                val done = TransferProgress(
                    id = reqId,
                    peerName = senderName,
                    direction = TransferDirection.RECEIVING,
                    state = TransferState.COMPLETED,
                    fileNames = extracted.map { it.relativeName },
                    bytesTransferred = extracted.sumOf { it.sizeBytes },
                    totalBytes = extracted.sumOf { it.sizeBytes },
                    savedPaths = extracted.map { it.file.absolutePath },
                    message = "Đã lưu ${extracted.size} tệp vào Downloads/AirBridge"
                )
                _activeTransfer.value = done
                _transferHistory.update { listOf(done) + it.take(29) }
            },
            onReceiveError = { reqId, senderName, err ->
                val failed = TransferProgress(
                    id = reqId,
                    peerName = senderName,
                    direction = TransferDirection.RECEIVING,
                    state = TransferState.FAILED,
                    fileNames = emptyList(),
                    bytesTransferred = 0L,
                    totalBytes = 0L,
                    message = err
                )
                _activeTransfer.value = failed
                _transferHistory.update { listOf(failed) + it.take(29) }
            }
        )
    }

    private val webServer: WebBridgeServer by lazy {
        WebBridgeServer(
            context = appContext,
            deviceNameProvider = { _deviceName.value },
            modelName = deviceModel,
            pinProvider = { _webPin.value },
            clipboardManager = clipboardManager,
            screenStreamManager = screenStreamManager,
            onRequestScreenCastFromRemote = {
                val intent = Intent().apply {
                    setClassName(appContext.packageName, "com.airbridge.drop.MainActivity")
                    action = ACTION_REMOTE_START_SCREEN_CAST
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                runCatching { appContext.startActivity(intent) }
            },
            onRequestStorageAccessFromRemote = {
                val intent = Intent().apply {
                    setClassName(appContext.packageName, "com.airbridge.drop.MainActivity")
                    action = ACTION_REMOTE_REQUEST_STORAGE
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                runCatching { appContext.startActivity(intent) }
            },
            onRequestControlAccessFromRemote = {
                val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                runCatching { appContext.startActivity(intent) }
            },
            onFileUploaded = { fileName, sizeBytes ->
                val item = TransferProgress(
                    id = UUID.randomUUID().toString(),
                    peerName = "Mac / iOS (Web Portal)",
                    direction = TransferDirection.RECEIVING,
                    state = TransferState.COMPLETED,
                    fileNames = listOf(fileName),
                    bytesTransferred = sizeBytes,
                    totalBytes = sizeBytes,
                    message = "Đã nhận $fileName từ Mac/iOS"
                )
                _transferHistory.update { listOf(item) + it.take(29) }
            }
        )
    }

    private val bleEngine: AirDropBleEngine by lazy {
        AirDropBleEngine(
            context = appContext,
            onBlePeerDetected = { peer -> upsertPeer(peer) },
            onStatusChanged = { scanning, advertising ->
                _diagnostics.update {
                    it.copy(
                        bleSupported = bleEngine.isSupported,
                        bleScanning = scanning,
                        bleAdvertising = advertising
                    )
                }
            }
        )
    }

    private val wifiAwareEngine: WifiAwareEngine by lazy {
        WifiAwareEngine(
            context = appContext,
            onPeerDiscovered = { peer -> upsertPeer(peer) },
            onStatusChanged = { supported, attached ->
                _diagnostics.update {
                    it.copy(wifiAwareSupported = supported, wifiAwareAttached = attached)
                }
            }
        )
    }

    private val mdnsEngine: AirDropMdnsEngine by lazy {
        AirDropMdnsEngine(
            context = appContext,
            localServiceId = localServiceId,
            onPeerResolved = { peer ->
                upsertPeer(peer)
                val host = peer.hostAddress ?: return@AirDropMdnsEngine
                scope.launch {
                    val info = client.discoverPeer(host, peer.port)
                    if (info != null) {
                        upsertPeer(
                            peer.copy(
                                displayName = info.receiverName,
                                modelName = info.receiverModel,
                                platform = AirDropPeer.inferPlatform(info.receiverModel, info.receiverName),
                                lastSeenMs = System.currentTimeMillis()
                            )
                        )
                    }
                }
            },
            onPeerLost = { id ->
                peerMap.remove(id)
                publishPeers()
            },
            onStatusChanged = { registered, discovering ->
                _diagnostics.update {
                    it.copy(mdnsRegistered = registered, mdnsDiscovering = discovering)
                }
            }
        )
    }

    private val udpBeacon by lazy {
        UdpBeaconBroadcaster(
            deviceId = localServiceId,
            deviceNameProvider = { _deviceName.value },
            modelName = deviceModel,
            webPortProvider = { webServer.boundPort },
            tlsPortProvider = { webServer.boundTlsPort },
            certSha256Provider = { webServer.certSha256Hex }
        )
    }

    fun buildQrPairReplyJson(secret: String, token: String): String {
        val ip = _webPortalUrl.value
            .removePrefix("http://")
            .removePrefix("https://")
            .substringBefore(':')
            .substringBefore('/')
        return org.json.JSONObject()
            .put("secret", secret)
            .put("deviceId", localServiceId)
            .put("deviceName", _deviceName.value)
            .put("model", deviceModel)
            .put("host", ip)
            .put("webPort", webServer.boundPort)
            .put("tlsPort", webServer.boundTlsPort)
            .put("certSha256", webServer.certSha256Hex)
            .put("token", token)
            .put("pin", _webPin.value)
            .toString()
    }

    fun startAll() {
        if (started) return
        started = true
        val webPort = webServer.start(scope, WebBridgeServer.DEFAULT_WEB_PORT)
        clipboardManager.start()
        udpBeacon.start(scope)

        val ip = findLocalIpv4()
        _webPortalUrl.value = "http://${ip ?: "192.168.x.x"}:$webPort"
        _diagnostics.update {
            it.copy(
                bleSupported = false,
                wifiAwareSupported = false,
                httpsPort = webServer.boundTlsPort,
                localIp = ip,
                deviceName = _deviceName.value
            )
        }

        // Battery-friendly IP check every 30s (BLE, mDNS, Wi-Fi Aware & AirDrop :8770 disabled)
        pruneJob?.cancel()
        pruneJob = scope.launch {
            while (isActive) {
                delay(30_000)
                val freshIp = findLocalIpv4()
                if (freshIp != null) {
                    _webPortalUrl.value = "http://$freshIp:${webServer.boundPort}"
                    _diagnostics.update { it.copy(localIp = freshIp) }
                }
            }
        }
    }

    fun stopAll() {
        started = false
        visibilityTimerJob?.cancel()
        pruneJob?.cancel()
        udpBeacon.stop()
        screenStreamManager.stop()
        clipboardManager.stop()
        webServer.stop()
        bleEngine.stopAdvertising()
        bleEngine.stopScanning()
        wifiAwareEngine.stop()
        mdnsEngine.stopAll()
        server.stop()
    }

    fun startScreenStreaming(resultCode: Int, data: Intent) {
        screenStreamManager.start(resultCode, data)
    }

    fun stopScreenStreaming() {
        screenStreamManager.stop()
    }

    fun regeneratePin() {
        val next = generateSixDigitPin()
        prefs.edit().putString("web_pin", next).apply()
        _webPin.value = next
    }

    fun syncClipboardNow(): String? = clipboardManager.syncFromAndroidClipboard()

    fun setDeviceName(name: String) {
        val clean = name.trim().takeIf { it.isNotEmpty() } ?: return
        prefs.edit().putString("device_name", clean).apply()
        _deviceName.value = clean
        _diagnostics.update { it.copy(deviceName = clean) }
        if (_visibilityMode.value != VisibilityMode.OFF) {
            mdnsEngine.unregisterReceiver()
            mdnsEngine.registerReceiver(server.boundPort, clean, deviceModel)
        }
    }

    fun setVisibilityMode(mode: VisibilityMode) {
        _visibilityMode.value = mode
        if (started) applyVisibilityMode(mode)
    }

    private fun applyVisibilityMode(mode: VisibilityMode) {
        visibilityTimerJob?.cancel()
        when (mode) {
            VisibilityMode.OFF -> {
                _visibilityRemainingSec.value = 0
                bleEngine.stopAdvertising()
                mdnsEngine.unregisterReceiver()
                wifiAwareEngine.updatePublishVisibility(false, _deviceName.value, deviceModel, server.boundPort)
            }
            VisibilityMode.EVERYONE_10_MIN -> {
                bleEngine.startAdvertising(_deviceName.value)
                mdnsEngine.registerReceiver(server.boundPort, _deviceName.value, deviceModel)
                wifiAwareEngine.updatePublishVisibility(true, _deviceName.value, deviceModel, server.boundPort)
                _visibilityRemainingSec.value = 600
                visibilityTimerJob = scope.launch {
                    var rem = 600
                    while (isActive && rem > 0) {
                        delay(1_000)
                        rem--
                        _visibilityRemainingSec.value = rem
                    }
                    if (isActive) setVisibilityMode(VisibilityMode.OFF)
                }
            }
            VisibilityMode.ALWAYS_ON -> {
                _visibilityRemainingSec.value = -1
                bleEngine.startAdvertising(_deviceName.value)
                mdnsEngine.registerReceiver(server.boundPort, _deviceName.value, deviceModel)
                wifiAwareEngine.updatePublishVisibility(true, _deviceName.value, deviceModel, server.boundPort)
            }
        }
    }

    fun respondToIncoming(requestId: String, accept: Boolean) {
        pendingApprovals.remove(requestId)?.complete(accept)
        if (_pendingIncoming.value?.requestId == requestId) {
            _pendingIncoming.value = null
        }
    }

    fun dismissActiveTransfer() {
        val current = _activeTransfer.value ?: return
        if (current.state in setOf(TransferState.COMPLETED, TransferState.DECLINED, TransferState.FAILED)) {
            _activeTransfer.value = null
        }
    }

    fun sendFilesToPeer(peer: AirDropPeer, files: List<OutgoingFile>) {
        if (files.isEmpty()) return
        val transferId = UUID.randomUUID().toString()
        val totalBytes = files.sumOf { it.sizeBytes }
        val fileNames = files.map { it.fileName }

        _activeTransfer.value = TransferProgress(
            id = transferId,
            peerName = peer.displayName,
            direction = TransferDirection.SENDING,
            state = TransferState.DISCOVERING,
            fileNames = fileNames,
            bytesTransferred = 0L,
            totalBytes = totalBytes,
            message = "Đang kết nối tới ${peer.displayName}..."
        )

        scope.launch {
            var targetHost = peer.hostAddress
            var targetPort = peer.port
            var boundNetwork = peer.boundNetwork

            if (peer.transport == TransportType.WIFI_AWARE && peer.awarePeerHandle != null && targetHost == null) {
                val ndp = wifiAwareEngine.establishDataPath(peer.awarePeerHandle, peer.port)
                if (ndp == null) {
                    finishSend(transferId, peer.displayName, fileNames, totalBytes, TransferState.FAILED, "Không thể thiết lập kênh Wi-Fi Aware (NAN)")
                    return@launch
                }
                boundNetwork = ndp.first
                targetHost = ndp.second.first
                targetPort = ndp.second.second
            }

            if (targetHost == null) {
                finishSend(
                    transferId,
                    peer.displayName,
                    fileNames,
                    totalBytes,
                    TransferState.FAILED,
                    "Chỉ mới phát hiện BLE beacon. Hãy mở ${_webPortalUrl.value} trên Mac/iPhone để gửi/nhận ngay."
                )
                return@launch
            }

            val descriptors = files.map { f ->
                AirDropClient.SendFileDescriptor(
                    fileName = f.fileName,
                    uti = f.uti,
                    sizeBytes = f.sizeBytes,
                    openStream = {
                        appContext.contentResolver.openInputStream(f.uri)
                            ?: throw IllegalStateException("Không đọc được tệp ${f.fileName}")
                    }
                )
            }

            val startTime = System.currentTimeMillis()
            val result = client.sendFiles(
                host = targetHost,
                port = targetPort,
                files = descriptors,
                boundNetwork = boundNetwork,
                onStateChanged = { phase ->
                    val mapped = when (phase) {
                        "DISCOVERING" -> TransferState.DISCOVERING to "Đang bắt tay /Discover..."
                        "WAITING_ACCEPTANCE" -> TransferState.WAITING_ACCEPTANCE to "Đang chờ ${peer.displayName} chấp nhận..."
                        else -> TransferState.TRANSFERRING to "Đang truyền qua TLS + CPIO..."
                    }
                    _activeTransfer.update { it?.copy(state = mapped.first, message = mapped.second) }
                },
                onProgress = { sent, total ->
                    val elapsedSec = ((System.currentTimeMillis() - startTime) / 1000.0).coerceAtLeast(0.1)
                    val speed = (sent / elapsedSec).toLong()
                    _activeTransfer.update {
                        it?.copy(
                            bytesTransferred = sent,
                            totalBytes = total,
                            speedBytesPerSec = speed
                        )
                    }
                }
            )

            when (result) {
                AirDropClient.SendResult.Completed -> finishSend(
                    transferId, peer.displayName, fileNames, totalBytes,
                    TransferState.COMPLETED, "Đã gửi thành công ${files.size} tệp"
                )
                AirDropClient.SendResult.Declined -> finishSend(
                    transferId, peer.displayName, fileNames, totalBytes,
                    TransferState.DECLINED, "${peer.displayName} đã từ chối nhận tệp"
                )
                is AirDropClient.SendResult.Failed -> finishSend(
                    transferId, peer.displayName, fileNames, totalBytes,
                    TransferState.FAILED, result.reason
                )
            }
        }
    }

    private fun finishSend(
        id: String,
        peerName: String,
        fileNames: List<String>,
        totalBytes: Long,
        state: TransferState,
        message: String
    ) {
        val progress = TransferProgress(
            id = id,
            peerName = peerName,
            direction = TransferDirection.SENDING,
            state = state,
            fileNames = fileNames,
            bytesTransferred = if (state == TransferState.COMPLETED) totalBytes else 0L,
            totalBytes = totalBytes,
            message = message
        )
        _activeTransfer.value = progress
        _transferHistory.update { listOf(progress) + it.take(29) }
    }

    private suspend fun awaitUserApproval(request: IncomingTransferRequest): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        pendingApprovals[request.requestId] = deferred
        _pendingIncoming.value = request
        return deferred.await()
    }

    private fun upsertPeer(peer: AirDropPeer) {
        peerMap[peer.id] = peer
        publishPeers()
    }

    private fun publishPeers() {
        val sorted = peerMap.values.sortedWith(
            compareByDescending<AirDropPeer> { it.isConnectable }
                .thenBy { it.transport.ordinal }
                .thenBy { it.displayName }
        )
        _peers.value = sorted
    }

    private fun getReceiveDirectory(): File {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return File(downloads, "AirBridge").apply { if (!exists()) mkdirs() }
    }

    private fun findLocalIpv4(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress
    }.getOrNull()

    private fun generateSixDigitPin(): String =
        (100000 + SecureRandom().nextInt(900000)).toString()

    companion object {
        const val ACTION_REMOTE_START_SCREEN_CAST = "com.airbridge.drop.REMOTE_START_SCREEN_CAST"
        const val ACTION_REMOTE_REQUEST_STORAGE = "com.airbridge.drop.REMOTE_REQUEST_STORAGE"

        @Volatile
        private var instance: AirBridgeCoordinator? = null

        fun getInstance(context: Context): AirBridgeCoordinator =
            instance ?: synchronized(this) {
                instance ?: AirBridgeCoordinator(context.applicationContext).also { instance = it }
            }
    }
}
