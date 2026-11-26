package com.airbridge.drop.protocol.web

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import java.util.Locale

/**
 * Displays a live file-receive capsule in Android 16/17 Live Updates & OPPO ColorOS 17 Fluid Cloud
 * (Dynamic Island) using promoted ongoing notifications + Notification.ProgressStyle.
 *
 * Android 16/17 Live Updates requirements:
 * - POST_PROMOTED_NOTIFICATIONS permission granted
 * - setOngoing(true)
 * - setRequestPromotedOngoing(true) / extra "android.requestPromotedOngoing" = true
 * - setShortCriticalText(...) (short <= 7 chars for the compact capsule)
 * - NO setColorized(true) (colorized notifications are rejected from promotion!)
 * - Standard template or Notification.ProgressStyle (with a Segment(100))
 */
class FluidCloudTransferNotifier(private val context: Context) {

    private val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var dismissRunnable: Runnable? = null

    init {
        ensureChannel()
    }

    fun showProgress(fileName: String, writtenBytes: Long, totalBytes: Long) {
        cancelScheduledDismiss()
        ensureChannel()
        val percent = if (totalBytes > 0L) {
            ((writtenBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
        } else {
            0
        }
        val subtitle = if (totalBytes > 0L) {
            "$fileName • ${formatSize(writtenBytes)} / ${formatSize(totalBytes)}"
        } else {
            "$fileName • ${formatSize(writtenBytes)}"
        }

        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Đang nhận từ Mac • $percent%")
            .setContentText(subtitle)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(true)
            .setWhen(System.currentTimeMillis())

        val appliedProgressStyle = applyLiveUpdatePromotion(
            builder = builder,
            shortText = "$percent%",
            progress = percent,
            accentColor = Color.parseColor("#0A84FF")
        )
        if (!appliedProgressStyle) {
            builder.setProgress(100, percent, totalBytes <= 0L)
        }

        val notification = builder.build()
        logPromotionStatus("progress($percent%)", notification)
        runCatching { nm.notify(NOTIFICATION_ID, notification) }
    }

    fun showCompleted(file: File, totalBytes: Long) {
        cancelScheduledDismiss()
        ensureChannel()

        val openPending = runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, guessMime(file.name))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            PendingIntent.getActivity(
                context,
                NOTIFICATION_ID,
                viewIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }.getOrNull()

        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Đã nhận từ Mac")
            .setContentText("${file.name} (${formatSize(totalBytes)})")
            .setOngoing(true)
            .setOnlyAlertOnce(false)
            .setShowWhen(true)
            .setWhen(System.currentTimeMillis())
            .apply { if (openPending != null) setContentIntent(openPending) }

        val appliedProgressStyle = applyLiveUpdatePromotion(
            builder = builder,
            shortText = "Đã nhận",
            progress = 100,
            accentColor = Color.parseColor("#34C759")
        )
        if (!appliedProgressStyle) {
            builder.setProgress(100, 100, false)
        }

        val notification = builder.build()
        logPromotionStatus("completed", notification)
        runCatching { nm.notify(NOTIFICATION_ID, notification) }

        // Keep the Dynamic Island / Fluid Cloud completion pill visible for 5s, then convert
        // to a normal dismissible notification so it doesn't stay pinned forever.
        val runnable = Runnable {
            val doneBuilder = Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Đã nhận: ${file.name}")
                .setContentText("Lưu tại Download/AirBridge (${formatSize(totalBytes)})")
                .setOngoing(false)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .apply { if (openPending != null) setContentIntent(openPending) }
            runCatching { nm.notify(NOTIFICATION_ID, doneBuilder.build()) }
        }
        dismissRunnable = runnable
        mainHandler.postDelayed(runnable, 5000L)
    }

    private fun applyLiveUpdatePromotion(
        builder: Notification.Builder,
        shortText: String,
        progress: Int,
        accentColor: Int
    ): Boolean {
        if (Build.VERSION.SDK_INT < 35) return false

        // 1. Request promoted ongoing (Android 16/17 Live Updates -> OPPO Fluid Cloud)
        val extraBundle = Bundle().apply {
            putBoolean("android.requestPromotedOngoing", true)
            putString("android.shortCriticalText", shortText)
        }
        builder.addExtras(extraBundle)

        runCatching {
            Notification.Builder::class.java
                .getMethod("setRequestPromotedOngoing", Boolean::class.javaPrimitiveType)
                .invoke(builder, true)
        }
        runCatching {
            Notification.Builder::class.java
                .getMethod("setShortCriticalText", String::class.java)
                .invoke(builder, shortText)
        }

        // 2. Attach Android 16/17 Notification.ProgressStyle with a 100-unit segment
        return runCatching {
            val styleClass = Class.forName("android.app.Notification\$ProgressStyle")
            val segmentClass = Class.forName("android.app.Notification\$ProgressStyle\$Segment")
            val style = styleClass.getDeclaredConstructor().newInstance()
            val segment = segmentClass.getDeclaredConstructor(Int::class.javaPrimitiveType)
                .newInstance(100)
            runCatching {
                segmentClass.getMethod("setColor", Int::class.javaPrimitiveType)
                    .invoke(segment, accentColor)
            }
            styleClass.getMethod("addProgressSegment", segmentClass).invoke(style, segment)
            styleClass.getMethod("setProgress", Int::class.javaPrimitiveType).invoke(style, progress)
            runCatching {
                styleClass.getMethod("setStyledByProgress", Boolean::class.javaPrimitiveType)
                    .invoke(style, true)
            }
            builder.setStyle(style as Notification.Style)
            true
        }.getOrDefault(false)
    }

    private fun logPromotionStatus(stage: String, notification: Notification) {
        runCatching {
            val canPost = NotificationManager::class.java
                .getMethod("canPostPromotedNotifications")
                .invoke(nm)
            val hasChars = Notification::class.java
                .getMethod("hasPromotableCharacteristics")
                .invoke(notification)
            Log.d(TAG, "$stage canPostPromoted=$canPost hasPromotableChars=$hasChars")
        }
    }

    private fun cancelScheduledDismiss() {
        dismissRunnable?.let { mainHandler.removeCallbacks(it) }
        dismissRunnable = null
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    "AirBridge Nhận tệp (Fluid Cloud)",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Hiển thị tiến trình nhận tệp từ Mac trên Dynamic Island / Fluid Cloud"
                    setSound(null, null)
                    enableVibration(false)
                }
                nm.createNotificationChannel(ch)
            }
        }
    }

    private fun formatSize(bytes: Long): String {
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        return when {
            mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
            kb >= 1.0 -> String.format(Locale.US, "%.0f KB", kb)
            else -> "$bytes B"
        }
    }

    private fun guessMime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "heic", "heif" -> "image/heic"
            "mp4", "m4v" -> "video/mp4"
            "mov" -> "video/quicktime"
            "mkv" -> "video/x-matroska"
            "avi" -> "video/x-msvideo"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "wav" -> "audio/wav"
            "flac" -> "audio/flac"
            "pdf" -> "application/pdf"
            "txt", "log", "md" -> "text/plain"
            "apk" -> "application/vnd.android.package-archive"
            "zip" -> "application/zip"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
        }
    }

    companion object {
        private const val TAG = "AirBridgeFluid"
        const val CHANNEL_ID = "airbridge_fluid_transfer_channel"
        const val NOTIFICATION_ID = 8779
    }
}
