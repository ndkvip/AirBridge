package com.airbridge.drop

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.airbridge.drop.protocol.AirBridgeCoordinator
import com.airbridge.drop.service.AirBridgeService

/**
 * Appears as "Copy & gửi Mac" in Android's native text selection menu.
 * Copies text into the local Android clipboard AND syncs it straight to the Mac clipboard.
 */
class SendToMacTextActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val selectedText = intent?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
        if (!selectedText.isNullOrBlank()) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("AirBridge", selectedText))

            AirBridgeService.start(applicationContext)
            val coordinator = AirBridgeCoordinator.getInstance(applicationContext)
            coordinator.clipboardManager.pushLocalText(selectedText)
            Toast.makeText(this, "Đã copy & gửi sang Mac", Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
