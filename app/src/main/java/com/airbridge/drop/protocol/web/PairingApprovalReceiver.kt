package com.airbridge.drop.protocol.web

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

class PairingApprovalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val clientId = intent.getStringExtra(EXTRA_CLIENT_ID) ?: return
        val security = AirBridgeSecurityManager.getInstance(context)
        when (intent.action) {
            ACTION_APPROVE -> {
                security.approvePairing(clientId)
                Toast.makeText(context, "✅ Đã cho phép máy Mac kết nối", Toast.LENGTH_SHORT).show()
            }
            ACTION_DENY -> {
                security.denyPairing(clientId)
                Toast.makeText(context, "⛔ Đã từ chối kết nối", Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        const val ACTION_APPROVE = "com.airbridge.drop.ACTION_APPROVE_PAIRING"
        const val ACTION_DENY = "com.airbridge.drop.ACTION_DENY_PAIRING"
        const val EXTRA_CLIENT_ID = "client_id"
    }
}
