package com.airbridge.drop

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import com.airbridge.drop.protocol.clipboard.ClipboardSyncManager

/**
 * Invisible trampoline activity invoked when the user taps a "Clipboard từ Mac" notification.
 * Takes window focus for ~50ms, writes the text or public MediaStore image URI to Android's
 * system Clipboard, and closes immediately.
 */
class ApplyClipboardActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val text = intent?.getStringExtra(EXTRA_TEXT)
        val imageUriStr = intent?.getStringExtra(EXTRA_IMAGE_URI)

        window.decorView.postDelayed({
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            var applied = false
            if (!imageUriStr.isNullOrBlank()) {
                val uri = Uri.parse(imageUriStr)
                cm.setPrimaryClip(ClipData.newUri(contentResolver, "AirBridge Image", uri))
                Toast.makeText(this, "Đã lưu ảnh từ Mac vào Clipboard", Toast.LENGTH_SHORT).show()
                applied = true
            } else if (!text.isNullOrEmpty()) {
                cm.setPrimaryClip(ClipData.newPlainText("AirBridge", text))
                Toast.makeText(this, "Đã lưu chữ từ Mac vào Clipboard", Toast.LENGTH_SHORT).show()
                applied = true
            }
            if (applied) {
                runCatching {
                    NotificationManagerCompat.from(this).cancel(ClipboardSyncManager.CLIPBOARD_NOTIFICATION_ID)
                }
            }
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }, 50L)
    }

    companion object {
        const val EXTRA_TEXT = "extra_clip_text"
        const val EXTRA_IMAGE_URI = "extra_clip_image_uri"
    }
}
