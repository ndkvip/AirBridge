package com.airbridge.drop

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import com.airbridge.drop.protocol.AirBridgeCoordinator
import com.airbridge.drop.protocol.model.OutgoingFile
import com.airbridge.drop.service.AirBridgeService
import com.airbridge.drop.ui.AirBridgeScreen
import com.airbridge.drop.ui.theme.AirBridgeTheme

class MainActivity : ComponentActivity() {

    private lateinit var coordinator: AirBridgeCoordinator
    private val selectedFilesState = mutableStateOf<List<OutgoingFile>>(emptyList())
    private val permissionsGrantedState = mutableStateOf(false)
    private val allFilesAccessState = mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val granted = hasAllRequiredPermissions()
        permissionsGrantedState.value = granted
        if (granted) {
            AirBridgeService.start(this)
        }
    }

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) addUrisToSelection(uris)
    }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            AirBridgeService.startScreenCast(this, result.resultCode, result.data!!)
            Toast.makeText(this, "Đang truyền màn hình lên Mac/Web Portal", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        coordinator = AirBridgeCoordinator.getInstance(applicationContext)

        permissionsGrantedState.value = hasAllRequiredPermissions()
        allFilesAccessState.value = hasAllFilesAccess()

        if (permissionsGrantedState.value) {
            AirBridgeService.start(this)
        } else {
            requestRuntimePermissions()
        }

        handleShareIntent(intent)

        setContent {
            AirBridgeTheme {
                val deviceName by coordinator.deviceName.collectAsState()
                val visibilityMode by coordinator.visibilityMode.collectAsState()
                val remainingSec by coordinator.visibilityRemainingSec.collectAsState()
                val peers by coordinator.peers.collectAsState()
                val activeTransfer by coordinator.activeTransfer.collectAsState()
                val history by coordinator.transferHistory.collectAsState()
                val pendingIncoming by coordinator.pendingIncoming.collectAsState()
                val diagnostics by coordinator.diagnostics.collectAsState()
                val webPortalUrl by coordinator.webPortalUrl.collectAsState()
                val webPin by coordinator.webPin.collectAsState()
                val isScreenStreaming by coordinator.isScreenStreaming.collectAsState()

                AirBridgeScreen(
                    deviceName = deviceName,
                    visibilityMode = visibilityMode,
                    visibilityRemainingSec = remainingSec,
                    peers = peers,
                    selectedFiles = selectedFilesState.value,
                    activeTransfer = activeTransfer,
                    history = history,
                    pendingIncoming = pendingIncoming,
                    diagnostics = diagnostics,
                    permissionsGranted = permissionsGrantedState.value,
                    allFilesAccessGranted = allFilesAccessState.value,
                    webPortalUrl = webPortalUrl,
                    webPin = webPin,
                    isScreenStreaming = isScreenStreaming,
                    onRequestPermissions = { requestRuntimePermissions() },
                    onRequestAllFilesAccess = { requestAllFilesAccess() },
                    onToggleScreenStream = { toggleScreenStreaming(isScreenStreaming) },
                    onSyncClipboard = {
                        val txt = coordinator.syncClipboardNow()
                        val msg = if (txt != null) "Đã đồng bộ Clipboard sang Mac!" else "Clipboard hiện đang trống"
                        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                    },
                    onRegeneratePin = { coordinator.regeneratePin() },
                    onScanQr = { startActivity(Intent(this, QrScannerActivity::class.java)) },
                    onPickFiles = { filePickerLauncher.launch(arrayOf("*/*")) },
                    onRemoveFile = { file ->
                        selectedFilesState.value = selectedFilesState.value.filterNot { it.uri == file.uri }
                    },
                    onClearFiles = { selectedFilesState.value = emptyList() },
                    onSelectVisibility = { coordinator.setVisibilityMode(it) },
                    onRenameDevice = { coordinator.setDeviceName(it) },
                    onSendToPeer = { peer ->
                        val files = selectedFilesState.value
                        if (files.isNotEmpty()) coordinator.sendFilesToPeer(peer, files)
                    },
                    onRespondIncoming = { reqId, accept -> coordinator.respondToIncoming(reqId, accept) },
                    onDismissTransfer = { coordinator.dismissActiveTransfer() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        allFilesAccessState.value = hasAllFilesAccess()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            coordinator.syncClipboardNow()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    private fun toggleScreenStreaming(currentlyStreaming: Boolean) {
        if (currentlyStreaming) {
            AirBridgeService.stopScreenCast(this)
        } else {
            val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            screenCaptureLauncher.launch(pm.createScreenCaptureIntent())
        }
    }

    private fun hasAllFilesAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }

    private fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }.onFailure {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        }
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            AirBridgeCoordinator.ACTION_REMOTE_START_SCREEN_CAST -> {
                if (!coordinator.isScreenStreaming.value) {
                    toggleScreenStreaming(false)
                }
            }
            AirBridgeCoordinator.ACTION_REMOTE_REQUEST_STORAGE -> {
                if (!hasAllFilesAccess()) {
                    requestAllFilesAccess()
                }
            }
            Intent.ACTION_SEND -> {
                val uri = getParcelableExtraCompat<Uri>(intent, Intent.EXTRA_STREAM)
                if (uri != null) addUrisToSelection(listOf(uri))
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = getParcelableArrayListExtraCompat<Uri>(intent, Intent.EXTRA_STREAM)
                if (!uris.isNullOrEmpty()) addUrisToSelection(uris)
            }
        }
    }

    private fun addUrisToSelection(uris: List<Uri>) {
        val resolved = uris.mapNotNull(::resolveOutgoingFile)
        val existing = selectedFilesState.value.associateBy { it.uri }.toMutableMap()
        for (item in resolved) existing[item.uri] = item
        selectedFilesState.value = existing.values.toList()
    }

    private fun resolveOutgoingFile(uri: Uri): OutgoingFile? {
        var name = "shared_file"
        var size = 0L
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (nameIdx >= 0) cursor.getString(nameIdx)?.let { name = it }
                        if (sizeIdx >= 0) size = cursor.getLong(sizeIdx).coerceAtLeast(0L)
                    }
                }
        }
        val mime = contentResolver.getType(uri) ?: "application/octet-stream"
        return OutgoingFile(uri = uri, fileName = name, mimeType = mime, sizeBytes = size)
    }

    private fun requiredPermissions(): Array<String> {
        val list = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list += Manifest.permission.BLUETOOTH_SCAN
            list += Manifest.permission.BLUETOOTH_ADVERTISE
            list += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list += Manifest.permission.NEARBY_WIFI_DEVICES
            list += Manifest.permission.POST_NOTIFICATIONS
        } else {
            list += Manifest.permission.ACCESS_FINE_LOCATION
        }
        return list.toTypedArray()
    }

    private fun hasAllRequiredPermissions(): Boolean = requiredPermissions().all { perm ->
        ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestRuntimePermissions() {
        permissionLauncher.launch(requiredPermissions())
    }

    @Suppress("DEPRECATION")
    private inline fun <reified T : android.os.Parcelable> getParcelableExtraCompat(intent: Intent, key: String): T? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(key, T::class.java)
        } else {
            intent.getParcelableExtra(key) as? T
        }

    @Suppress("DEPRECATION")
    private inline fun <reified T : android.os.Parcelable> getParcelableArrayListExtraCompat(intent: Intent, key: String): ArrayList<T>? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(key, T::class.java)
        } else {
            intent.getParcelableArrayListExtra(key)
        }
}
