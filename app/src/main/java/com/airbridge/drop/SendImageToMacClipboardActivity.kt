package com.airbridge.drop

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import com.airbridge.drop.protocol.AirBridgeCoordinator
import com.airbridge.drop.service.AirBridgeService

/**
 * Appears as "Copy ảnh sang Mac" in Android's Share Sheet for images.
 * Sends the image directly into Mac's clipboard (⌘V ready) with zero AccessibilityService.
 */
class SendImageToMacClipboardActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        @Suppress("DEPRECATION")
        val uri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            intent?.getParcelableExtra(Intent.EXTRA_STREAM)
        }

        if (uri != null) {
            AirBridgeService.start(applicationContext)
            val ok = AirBridgeCoordinator.getInstance(applicationContext)
                .clipboardManager
                .pushLocalImageUri(uri)
            val msg = if (ok) "Đã copy ảnh sang Clipboard của Mac" else "Không đọc được ảnh này"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
