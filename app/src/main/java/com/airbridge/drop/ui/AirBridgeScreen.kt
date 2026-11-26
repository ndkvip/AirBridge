package com.airbridge.drop.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LaptopMac
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PhoneIphone
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material.icons.rounded.TabletMac
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.airbridge.drop.protocol.model.AirDropPeer
import com.airbridge.drop.protocol.model.DevicePlatform
import com.airbridge.drop.protocol.model.IncomingTransferRequest
import com.airbridge.drop.protocol.model.OutgoingFile
import com.airbridge.drop.protocol.model.ProtocolDiagnostics
import com.airbridge.drop.protocol.model.TransferDirection
import com.airbridge.drop.protocol.model.TransferProgress
import com.airbridge.drop.protocol.model.TransferState
import com.airbridge.drop.protocol.model.TransportType
import com.airbridge.drop.protocol.model.VisibilityMode
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AirBridgeScreen(
    deviceName: String,
    visibilityMode: VisibilityMode,
    visibilityRemainingSec: Int,
    peers: List<AirDropPeer>,
    selectedFiles: List<OutgoingFile>,
    activeTransfer: TransferProgress?,
    history: List<TransferProgress>,
    pendingIncoming: IncomingTransferRequest?,
    diagnostics: ProtocolDiagnostics,
    permissionsGranted: Boolean,
    allFilesAccessGranted: Boolean,
    webPortalUrl: String,
    webPin: String,
    isScreenStreaming: Boolean,
    onRequestPermissions: () -> Unit,
    onRequestAllFilesAccess: () -> Unit,
    onToggleScreenStream: () -> Unit,
    onSyncClipboard: () -> Unit,
    onRegeneratePin: () -> Unit,
    onScanQr: () -> Unit = {},
    onPickFiles: () -> Unit,
    onRemoveFile: (OutgoingFile) -> Unit,
    onClearFiles: () -> Unit,
    onSelectVisibility: (VisibilityMode) -> Unit,
    onRenameDevice: (String) -> Unit,
    onSendToPeer: (AirDropPeer) -> Unit,
    onRespondIncoming: (requestId: String, accept: Boolean) -> Unit,
    onDismissTransfer: () -> Unit
) {
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDiagnosticsDialog by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
                title = {
                    Column {
                        Text(
                            text = "AirBridge",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "$deviceName • Kết nối Mac tiết kiệm pin",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onScanQr) {
                        Icon(Icons.Rounded.QrCodeScanner, contentDescription = "Quét QR ghép nối Mac")
                    }
                    IconButton(onClick = { showRenameDialog = true }) {
                        Icon(Icons.Rounded.Edit, contentDescription = "Đổi tên thiết bị")
                    }
                    IconButton(onClick = { showDiagnosticsDialog = true }) {
                        Icon(Icons.Rounded.Info, contentDescription = "Trạng thái giao thức")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (!permissionsGranted) {
                item {
                    PermissionBannerCard(onRequestPermissions = onRequestPermissions)
                }
            }

            item {
                MacPortalControlCard(
                    webPortalUrl = webPortalUrl,
                    webPin = webPin,
                    isScreenStreaming = isScreenStreaming,
                    allFilesAccessGranted = allFilesAccessGranted,
                    onToggleScreenStream = onToggleScreenStream,
                    onSyncClipboard = onSyncClipboard,
                    onRequestAllFilesAccess = onRequestAllFilesAccess,
                    onRegeneratePin = onRegeneratePin
                )
            }

            item {
                Button(
                    onClick = onScanQr,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Rounded.QrCodeScanner, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Quét mã QR ghép nối Mac (1 lần duy nhất)",
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (activeTransfer != null) {
                item {
                    ActiveTransferCard(
                        transfer = activeTransfer,
                        onDismiss = onDismissTransfer
                    )
                }
            }

            if (history.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Lịch sử truyền gần đây",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                items(history, key = { "${it.id}-${it.timestampMs}" }) { item ->
                    HistoryRowCard(item)
                }
            }
        }
    }

    if (pendingIncoming != null) {
        IncomingRequestDialog(
            request = pendingIncoming,
            onAccept = { onRespondIncoming(pendingIncoming.requestId, true) },
            onDecline = { onRespondIncoming(pendingIncoming.requestId, false) }
        )
    }

    if (showRenameDialog) {
        RenameDeviceDialog(
            currentName = deviceName,
            onDismiss = { showRenameDialog = false },
            onConfirm = { newName ->
                onRenameDevice(newName)
                showRenameDialog = false
            }
        )
    }

    if (showDiagnosticsDialog) {
        DiagnosticsDialog(
            diagnostics = diagnostics,
            onDismiss = { showDiagnosticsDialog = false }
        )
    }
}

@Composable
private fun MacPortalControlCard(
    webPortalUrl: String,
    webPin: String,
    isScreenStreaming: Boolean,
    allFilesAccessGranted: Boolean,
    onToggleScreenStream: () -> Unit,
    onSyncClipboard: () -> Unit,
    onRequestAllFilesAccess: () -> Unit,
    onRegeneratePin: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(22.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Kết nối Mac (AirBridge TLS)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Mã hóa HTTPS/TLS • Tự nhận diện & nối lại trên Wi-Fi",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onRegeneratePin) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "Đổi mã PIN")
                }
            }

            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(14.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Địa chỉ Web Portal",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = webPortalUrl,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "PIN",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = webPin,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onToggleScreenStream,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                ) {
                    Icon(
                        imageVector = if (isScreenStreaming) Icons.Rounded.StopCircle else Icons.Rounded.Cast,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (isScreenStreaming) "Dừng phát" else "Phản chiếu",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                OutlinedButton(
                    onClick = onSyncClipboard,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ContentPaste,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Đẩy Clipboard",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            if (!allFilesAccessGranted) {
                OutlinedButton(
                    onClick = onRequestAllFilesAccess,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Cấp quyền đọc toàn bộ tệp trên điện thoại")
                }
            }
        }
    }
}

@Composable
private fun PermissionBannerCard(onRequestPermissions: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.Bluetooth,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Cần quyền Bluetooth & Thiết bị lân cận",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Để quét AirDrop BLE, Wi-Fi Aware và mDNS trên máy bạn.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                )
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = onRequestPermissions) {
                Text("Cấp quyền")
            }
        }
    }
}

@Composable
private fun VisibilityControlCard(
    mode: VisibilityMode,
    remainingSec: Int,
    onSelectMode: (VisibilityMode) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Chế độ hiển thị (Nhận tệp)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                if (mode == VisibilityMode.EVERYONE_10_MIN && remainingSec > 0) {
                    val m = remainingSec / 60
                    val s = remainingSec % 60
                    Text(
                        text = String.format(Locale.US, "Còn %02d:%02d", m, s),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(VisibilityMode.entries) { item ->
                    FilterChip(
                        selected = mode == item,
                        onClick = { onSelectMode(item) },
                        label = { Text(item.labelVi) }
                    )
                }
            }
        }
    }
}

@Composable
private fun RadarHeroCard(
    peerCount: Int,
    isVisible: Boolean,
    diagnostics: ProtocolDiagnostics
) {
    val transition = rememberInfiniteTransition(label = "radar")
    val pulse by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulse"
    )
    val primary = MaterialTheme.colorScheme.primary

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(24.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(84.dp),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val maxR = size.minDimension / 2f
                    drawCircle(
                        color = primary.copy(alpha = (1f - pulse) * 0.45f),
                        radius = maxR * pulse,
                        style = Stroke(width = 3f)
                    )
                    drawCircle(
                        color = primary.copy(alpha = 0.18f),
                        radius = maxR * 0.65f,
                        style = Stroke(width = 2f)
                    )
                }
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.WifiTethering,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isVisible) "Đang mở nhận AirDrop & Quick Share" else "Chỉ đang quét thiết bị gửi",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Trên iPhone / Mac: bật AirDrop → \"Mọi người trong 10 phút\" để hiển thị.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatusBadge("BLE", diagnostics.bleScanning)
                    StatusBadge("Wi-Fi Aware", diagnostics.wifiAwareAttached)
                    StatusBadge("mDNS", diagnostics.mdnsDiscovering)
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(label: String, active: Boolean) {
    val bg = if (active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (active) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(color = bg, shape = RoundedCornerShape(8.dp)) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = fg
        )
    }
}

@Composable
private fun SelectedFilesCard(
    files: List<OutgoingFile>,
    onPickFiles: () -> Unit,
    onRemoveFile: (OutgoingFile) -> Unit,
    onClearFiles: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = if (files.isEmpty()) "Chưa chọn tệp gửi" else "Đã chọn ${files.size} tệp (${formatBytes(files.sumOf { it.sizeBytes })})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Hỗ trợ chọn ảnh, video, tài liệu hoặc từ nút Chia sẻ hệ thống",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (files.isNotEmpty()) {
                    IconButton(onClick = onClearFiles) {
                        Icon(Icons.Rounded.Delete, contentDescription = "Xoá tất cả")
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            if (files.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(files, key = { it.uri.toString() }) { f ->
                        AssistChip(
                            onClick = { onRemoveFile(f) },
                            label = {
                                Text(
                                    text = f.fileName,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            trailingIcon = {
                                Icon(Icons.Rounded.Close, contentDescription = "Bỏ", modifier = Modifier.size(16.dp))
                            }
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
            Button(
                onClick = onPickFiles,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (files.isEmpty()) "Chọn ảnh, video hoặc tệp để gửi" else "Thêm tệp khác")
            }
        }
    }
}

@Composable
private fun ActiveTransferCard(
    transfer: TransferProgress,
    onDismiss: () -> Unit
) {
    val isFinished = transfer.state in setOf(TransferState.COMPLETED, TransferState.DECLINED, TransferState.FAILED)
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val icon = when (transfer.state) {
                        TransferState.COMPLETED -> Icons.Rounded.CheckCircle
                        TransferState.FAILED, TransferState.DECLINED -> Icons.Rounded.Error
                        else -> Icons.AutoMirrored.Rounded.Send
                    }
                    val tint = when (transfer.state) {
                        TransferState.COMPLETED -> MaterialTheme.colorScheme.secondary
                        TransferState.FAILED, TransferState.DECLINED -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.primary
                    }
                    Icon(icon, contentDescription = null, tint = tint)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "${if (transfer.direction == TransferDirection.SENDING) "Gửi tới" else "Nhận từ"} ${transfer.peerName}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = transfer.message ?: transfer.state.name,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (isFinished) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.Close, contentDescription = "Đóng")
                    }
                }
            }
            if (!isFinished) {
                Spacer(Modifier.height(12.dp))
                if (transfer.state == TransferState.TRANSFERRING && transfer.totalBytes > 0L) {
                    LinearProgressIndicator(
                        progress = { transfer.fraction },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                    )
                    Spacer(Modifier.height(6.dp))
                    val speed = if (transfer.speedBytesPerSec > 0) " • ${formatBytes(transfer.speedBytesPerSec)}/s" else ""
                    Text(
                        text = "${formatBytes(transfer.bytesTransferred)} / ${formatBytes(transfer.totalBytes)}$speed",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                    )
                }
            }
        }
    }
}

@Composable
private fun PeerDeviceCard(
    peer: AirDropPeer,
    hasFilesSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = platformIcon(peer.platform),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = peer.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = peer.modelName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                val transportLabel = when (peer.transport) {
                    TransportType.WIFI_AWARE -> "Wi-Fi Aware (P2P)"
                    TransportType.MDNS_LAN -> "AirDrop HTTPS (${peer.hostAddress}:${peer.port})"
                    TransportType.BLE_ONLY -> "BLE Beacon (${peer.rssi ?: -60} dBm)"
                }
                Text(
                    text = transportLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (peer.isConnectable) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.tertiary
                )
            }
            Spacer(Modifier.width(8.dp))
            Surface(
                color = if (hasFilesSelected && peer.isConnectable) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = if (hasFilesSelected) "Gửi" else "Chọn tệp",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (hasFilesSelected && peer.isConnectable) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    }
}

@Composable
private fun EmptyPeersCard() {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Rounded.Wifi,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(36.dp)
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Đang tìm iPhone, Mac & Android gần đây...",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Hãy bật Wi-Fi, Bluetooth và đặt AirDrop ở chế độ \"Mọi người trong 10 phút\" trên thiết bị Apple hoặc mở AirBridge trên máy Android khác.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun HistoryRowCard(item: TransferProgress) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (item.state == TransferState.COMPLETED) Icons.Rounded.CheckCircle else Icons.Rounded.Error,
                contentDescription = null,
                tint = if (item.state == TransferState.COMPLETED) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${if (item.direction == TransferDirection.SENDING) "→" else "←"} ${item.peerName}",
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = item.message ?: item.fileNames.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = formatBytes(item.totalBytes),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun IncomingRequestDialog(
    request: IncomingTransferRequest,
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDecline,
        icon = { Icon(platformIcon(request.senderPlatform), contentDescription = null) },
        title = { Text("${request.senderName} muốn chia sẻ") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Thiết bị: ${request.senderModel}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val summary = if (request.files.isEmpty()) {
                    "Tệp gửi qua AirDrop"
                } else {
                    request.files.take(5).joinToString("\n") { "• ${it.fileName}" } +
                        if (request.files.size > 5) "\n...và ${request.files.size - 5} tệp khác" else ""
                }
                Text(text = summary, style = MaterialTheme.typography.bodyMedium)
                request.totalSizeBytes?.takeIf { it > 0 }?.let { total ->
                    Text(
                        text = "Tổng dung lượng: ${formatBytes(total)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onAccept) { Text("Chấp nhận") }
        },
        dismissButton = {
            OutlinedButton(onClick = onDecline) { Text("Từ chối") }
        }
    )
}

@Composable
private fun RenameDeviceDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tên thiết bị hiển thị") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text("Tên máy") }
            )
        },
        confirmButton = {
            Button(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text("Lưu") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Huỷ") }
        }
    )
}

@Composable
private fun DiagnosticsDialog(
    diagnostics: ProtocolDiagnostics,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Chẩn đoán giao thức AirDrop") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DiagLine("BLE (0x004C / 0x05)", if (diagnostics.bleScanning) "Đang quét" else if (diagnostics.bleSupported) "Sẵn sàng" else "Không hỗ trợ")
                DiagLine("BLE Beacon phát", if (diagnostics.bleAdvertising) "Đang phát" else "Đang tắt")
                DiagLine("Wi-Fi Aware (NAN)", if (diagnostics.wifiAwareAttached) "Đã kết nối cụm NAN" else if (diagnostics.wifiAwareSupported) "Hỗ trợ phần cứng" else "Chip Wi-Fi không hỗ trợ")
                DiagLine("mDNS (_airdrop._tcp)", if (diagnostics.mdnsRegistered) "Đang quảng bá & quét" else if (diagnostics.mdnsDiscovering) "Đang quét" else "Tắt")
                DiagLine("AirDrop HTTPS/TLS", "${diagnostics.localIp ?: "0.0.0.0"}:${diagnostics.httpsPort}")
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Lưu ý: Giao tiếp trực tiếp không cần app phụ với AirDrop chuẩn yêu cầu thiết bị Apple chạy iOS 26 / macOS 26 (hỗ trợ Wi-Fi Aware) bật \"Mọi người trong 10 phút\" và chip Wi-Fi Android hỗ trợ Wi-Fi Aware.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text("Đóng") }
        }
    )
}

@Composable
private fun DiagLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
    }
}

private fun platformIcon(platform: DevicePlatform): ImageVector = when (platform) {
    DevicePlatform.IPHONE -> Icons.Rounded.PhoneIphone
    DevicePlatform.IPAD -> Icons.Rounded.TabletMac
    DevicePlatform.MAC -> Icons.Rounded.LaptopMac
    DevicePlatform.ANDROID -> Icons.Rounded.PhoneAndroid
    DevicePlatform.UNKNOWN -> Icons.Rounded.Computer
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return String.format(Locale.US, "%.1f MB", mb)
    return String.format(Locale.US, "%.2f GB", mb / 1024.0)
}
