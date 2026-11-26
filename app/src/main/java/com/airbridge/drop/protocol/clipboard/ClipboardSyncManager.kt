package com.airbridge.drop.protocol.clipboard

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.content.ContentUris
import java.util.concurrent.Executors
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.airbridge.drop.ApplyClipboardActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.FileOutputStream

data class ClipboardEntry(
    val id: Long = System.currentTimeMillis(),
    val text: String,
    val source: String,
    val timestampMs: Long = System.currentTimeMillis(),
    val imagePath: String? = null
) {
    val isImage: Boolean get() = !imagePath.isNullOrBlank()
}

/**
 * Manages two-way clipboard sharing (text + images) between Android and Mac
 * with ZERO AccessibilityService and ZERO Developer Options (100% banking-app friendly).
 */
class ClipboardSyncManager(private val appContext: Context) {

    private val clipboardManager = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _history = MutableStateFlow<List<ClipboardEntry>>(emptyList())
    val history: StateFlow<List<ClipboardEntry>> = _history.asStateFlow()

    private val _latestText = MutableStateFlow("")
    val latestText: StateFlow<String> = _latestText.asStateFlow()

    @Volatile
    private var lastSetFromRemote: String? = null

    @Volatile
    private var lastSyncedScreenshotId: Long = -1L

    private val ioExecutor = Executors.newSingleThreadExecutor()

    private val checkScreenshotRunnable = Runnable {
        ioExecutor.execute { checkLatestScreenshot() }
    }

    private val screenshotObserver = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            super.onChange(selfChange, uri)
            mainHandler.removeCallbacks(checkScreenshotRunnable)
            mainHandler.postDelayed(checkScreenshotRunnable, 450L)
        }
    }

    private val listener = ClipboardManager.OnPrimaryClipChangedListener {
        syncFromAndroidClipboard()
    }

    fun start() {
        ensureNotificationChannel()
        ioExecutor.execute { seedLatestScreenshotId() }
        mainHandler.post {
            runCatching { clipboardManager.addPrimaryClipChangedListener(listener) }
            runCatching {
                appContext.contentResolver.registerContentObserver(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    true,
                    screenshotObserver
                )
            }
            syncFromAndroidClipboard()
        }
    }

    fun stop() {
        mainHandler.removeCallbacks(checkScreenshotRunnable)
        mainHandler.post {
            runCatching { clipboardManager.removePrimaryClipChangedListener(listener) }
            runCatching { appContext.contentResolver.unregisterContentObserver(screenshotObserver) }
        }
    }

    private fun seedLatestScreenshotId() {
        runCatching {
            val latest = queryLatestScreenshot()
            if (latest != null) {
                lastSyncedScreenshotId = latest.first
            }
        }
    }

    private fun checkLatestScreenshot() {
        runCatching {
            val latest = queryLatestScreenshot() ?: return
            val (id, uri) = latest
            if (id <= lastSyncedScreenshotId) return
            lastSyncedScreenshotId = id
            pushLocalImageUri(uri)
        }
    }

    private fun queryLatestScreenshot(): Pair<Long, Uri>? {
        val projection = mutableListOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            projection.add(MediaStore.Images.Media.RELATIVE_PATH)
            projection.add(MediaStore.Images.Media.IS_PENDING)
        }
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        appContext.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection.toTypedArray(),
            null,
            null,
            sortOrder
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndex(MediaStore.Images.Media._ID)
            val nameIdx = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
            val dateIdx = cursor.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
            val pathIdx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
            } else -1
            val pendingIdx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                cursor.getColumnIndex(MediaStore.Images.Media.IS_PENDING)
            } else -1

            var checked = 0
            while (cursor.moveToNext() && checked < 6) {
                checked++
                if (pendingIdx >= 0 && cursor.getInt(pendingIdx) != 0) continue
                val name = if (nameIdx >= 0) cursor.getString(nameIdx).orEmpty() else ""
                val relPath = if (pathIdx >= 0) cursor.getString(pathIdx).orEmpty() else ""
                val lowerPath = relPath.lowercase()
                val lowerName = name.lowercase()
                if ("airbridge" in lowerPath || "gboardsync" in lowerPath) continue
                val isScreenshot = "screenshot" in lowerPath || lowerName.startsWith("screenshot")
                if (!isScreenshot) continue

                val dateAddedSec = if (dateIdx >= 0) cursor.getLong(dateIdx) else 0L
                val nowSec = System.currentTimeMillis() / 1000L
                if (lastSyncedScreenshotId >= 0L && nowSec - dateAddedSec > 30L) {
                    return null
                }
                val id = cursor.getLong(idIdx)
                val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                return id to uri
            }
        }
        return null
    }

    fun syncFromAndroidClipboard(): String? {
        val clip = runCatching { clipboardManager.primaryClip }.getOrNull() ?: return null
        if (clip.itemCount <= 0) return null
        val item = clip.getItemAt(0)

        // Check image URI in Android clipboard first
        val uri = item.uri
        if (uri != null) {
            val mime = appContext.contentResolver.getType(uri).orEmpty()
            if (mime.startsWith("image/") && uri.authority != "${appContext.packageName}.fileprovider") {
                pushLocalImageUri(uri)
                return "[Ảnh từ Android]"
            }
        }

        val text = item.text?.toString()?.takeIf { it.isNotBlank() } ?: return null
        if (text == lastSetFromRemote) {
            lastSetFromRemote = null
            return text
        }
        recordEntry(text, "Android")
        return text
    }

    fun pushLocalImageUri(uri: android.net.Uri): Boolean {
        val bytes = runCatching {
            appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return false
        if (bytes.isEmpty()) return false

        val dir = File(appContext.cacheDir, "clipboard_images").apply { if (!exists()) mkdirs() }
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(9)?.forEach { it.delete() }

        val mime = appContext.contentResolver.getType(uri).orEmpty().lowercase()
        val ext = if ("jpeg" in mime || "jpg" in mime) "jpg" else "png"
        val id = System.currentTimeMillis()
        val file = File(dir, "android_clip_$id.$ext")
        FileOutputStream(file).use { it.write(bytes) }

        val label = "[Ảnh từ Android • ${bytes.size / 1024} KB]"
        _latestText.value = label
        _history.update { list ->
            (listOf(ClipboardEntry(id = id, text = label, source = "Android", timestampMs = id, imagePath = file.absolutePath)) + list).take(30)
        }
        return true
    }

    fun getImageFileById(id: Long): File? {
        val path = _history.value.firstOrNull { it.id == id }?.imagePath ?: return null
        return File(path).takeIf { it.exists() }
    }

    fun pushLocalText(text: String) {
        val clean = text.trimEnd()
        if (clean.isEmpty()) return
        _latestText.value = clean
        _history.update { list ->
            (listOf(ClipboardEntry(text = clean, source = "Android")) +
                list.filterNot { it.text == clean && it.source == "Android" }).take(30)
        }
    }

    fun applyFromRemote(text: String, source: String = "Mac / Web") {
        val clean = text.trimEnd()
        if (clean.isEmpty()) return
        lastSetFromRemote = clean
        mainHandler.post {
            runCatching {
                clipboardManager.setPrimaryClip(ClipData.newPlainText("AirBridge", clean))
            }
        }
        recordEntry(clean, source)
        showApplyClipboardNotification(
            title = "📋 Chữ mới từ Mac (chạm để sao chép)",
            preview = clean.take(120),
            text = clean,
            imageUriStr = null
        )
    }

    fun applyImageFromRemote(imageBytes: ByteArray, ext: String = "png", source: String = "Mac / Web") {
        if (imageBytes.isEmpty()) return

        val cleanExt = if (ext.lowercase() in setOf("jpg", "jpeg")) "jpg" else "png"
        val mime = if (cleanExt == "jpg") "image/jpeg" else "image/png"
        val fileName = "AirBridge_${System.currentTimeMillis()}.$cleanExt"

        // Save into public MediaStore (Pictures/AirBridge) so Gboard, ColorOS Keyboard,
        // Zalo, and Messenger have full read access to render thumbnail & paste real image!
        val mediaUri: Uri? = saveToMediaStore(fileName, mime, imageBytes)
        val finalUri: Uri = mediaUri ?: run {
            val dir = File(appContext.cacheDir, "clipboard_images").apply { if (!exists()) mkdirs() }
            val file = File(dir, fileName)
            FileOutputStream(file).use { it.write(imageBytes) }
            FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", file)
        }

        val clip = ClipData.newUri(appContext.contentResolver, "AirBridge Image", finalUri)
        mainHandler.post {
            runCatching { clipboardManager.setPrimaryClip(clip) }
        }
        val label = "[Ảnh từ Mac • ${imageBytes.size / 1024} KB]"
        recordEntry(label, source)
        showApplyClipboardNotification(
            title = "🖼️ Ảnh mới từ Mac (chạm để sao chép)",
            preview = "Dung lượng ${imageBytes.size / 1024} KB — chạm để dán vào Zalo/Messenger",
            text = null,
            imageUriStr = finalUri.toString()
        )
    }

    private fun saveToMediaStore(fileName: String, mime: String, bytes: ByteArray): Uri? = runCatching {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/AirBridge")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val resolver = appContext.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@runCatching null
        resolver.openOutputStream(uri)?.use { it.write(bytes) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        }
        uri
    }.getOrNull()

    private fun showApplyClipboardNotification(
        title: String,
        preview: String,
        text: String?,
        imageUriStr: String?
    ) {
        ensureNotificationChannel()
        val intent = Intent(appContext, ApplyClipboardActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (text != null) putExtra(ApplyClipboardActivity.EXTRA_TEXT, text)
            if (imageUriStr != null) putExtra(ApplyClipboardActivity.EXTRA_IMAGE_URI, imageUriStr)
        }
        val pi = PendingIntent.getActivity(
            appContext,
            CLIPBOARD_NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val noti = NotificationCompat.Builder(appContext, CLIPBOARD_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle(title)
            .setContentText(preview)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()

        runCatching {
            val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(CLIPBOARD_NOTIFICATION_ID, noti)
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CLIPBOARD_CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CLIPBOARD_CHANNEL_ID,
                    "AirBridge Clipboard từ Mac",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Chạm vào thông báo để chép chữ/ảnh từ Mac vào Clipboard điện thoại"
                }
                nm.createNotificationChannel(ch)
            }
        }
    }

    private fun recordEntry(text: String, source: String) {
        _latestText.value = text
        _history.update { list ->
            if (list.firstOrNull()?.text == text) list
            else (listOf(ClipboardEntry(text = text, source = source)) + list).take(30)
        }
    }

    companion object {
        const val CLIPBOARD_CHANNEL_ID = "airbridge_clipboard_channel"
        const val CLIPBOARD_NOTIFICATION_ID = 8775
    }
}
