package com.airbridge.drop.protocol.web

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Cafe-safe security manager for AirBridge:
 * 1. Per-IP PIN brute-force rate limiting (5 consecutive failures -> 60s lockout).
 * 2. Per-client device approval via heads-up notification (Approve / Deny).
 * 3. Persistent 256-bit session tokens for approved Macs so the 6-digit PIN is never reused.
 */
class AirBridgeSecurityManager(private val appContext: Context) {

    data class AttemptRecord(var failures: Int = 0, var lockedUntilMs: Long = 0L)

    enum class PairDecision { PENDING, APPROVED, DENIED }

    data class PendingRequest(
        val clientId: String,
        val clientName: String,
        val remoteIp: String,
        val createdAtMs: Long = System.currentTimeMillis(),
        @Volatile var decision: PairDecision = PairDecision.PENDING,
        @Volatile var issuedToken: String? = null
    )

    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val attemptsByIp = ConcurrentHashMap<String, AttemptRecord>()
    private val pendingByClient = ConcurrentHashMap<String, PendingRequest>()
    private val secureRandom = SecureRandom()

    init {
        ensureNotificationChannel()
    }

    /** Returns remaining lockout seconds (>0) if this IP is currently rate-limited, else 0. */
    fun checkLockoutSeconds(remoteIp: String): Int {
        val rec = attemptsByIp[remoteIp] ?: return 0
        val now = System.currentTimeMillis()
        if (rec.lockedUntilMs > now) {
            return ((rec.lockedUntilMs - now + 999L) / 1000L).toInt().coerceAtLeast(1)
        }
        return 0
    }

    fun recordAuthFailure(remoteIp: String): Int {
        val now = System.currentTimeMillis()
        val rec = attemptsByIp.computeIfAbsent(remoteIp) { AttemptRecord() }
        synchronized(rec) {
            if (rec.lockedUntilMs > now) {
                return ((rec.lockedUntilMs - now + 999L) / 1000L).toInt()
            }
            rec.failures++
            if (rec.failures >= MAX_FAILURES) {
                rec.failures = 0
                rec.lockedUntilMs = now + LOCKOUT_MS
                return (LOCKOUT_MS / 1000L).toInt()
            }
            return 0
        }
    }

    fun recordAuthSuccess(remoteIp: String) {
        attemptsByIp.remove(remoteIp)
    }

    fun isValidToken(token: String?): Boolean {
        if (token.isNullOrBlank() || token.length < 32) return false
        val all = prefs.all
        for ((key, value) in all) {
            if (key.startsWith("client_") && value is String) {
                val savedToken = runCatching { JSONObject(value).optString("token") }.getOrNull()
                if (savedToken != null && constantTimeEquals(savedToken, token)) {
                    return true
                }
            }
        }
        return false
    }

    fun getTrustedTokenForClient(clientId: String): String? {
        if (clientId.isBlank()) return null
        val raw = prefs.getString("client_$clientId", null) ?: return null
        return runCatching { JSONObject(raw).optString("token").takeIf { it.isNotBlank() } }.getOrNull()
    }

    fun startOrGetPairing(clientId: String, clientName: String, remoteIp: String): PendingRequest {
        // Already trusted? Return immediately as approved with existing token.
        getTrustedTokenForClient(clientId)?.let { existingToken ->
            return PendingRequest(
                clientId = clientId,
                clientName = clientName,
                remoteIp = remoteIp,
                decision = PairDecision.APPROVED,
                issuedToken = existingToken
            )
        }

        val now = System.currentTimeMillis()
        val existing = pendingByClient[clientId]
        if (existing != null && now - existing.createdAtMs < PAIR_TTL_MS && existing.decision == PairDecision.PENDING) {
            return existing
        }

        val req = PendingRequest(
            clientId = clientId,
            clientName = clientName.ifBlank { "Mac ($remoteIp)" },
            remoteIp = remoteIp
        )
        pendingByClient[clientId] = req
        showApprovalNotification(req)
        return req
    }

    fun getPairingStatus(clientId: String): PendingRequest? {
        getTrustedTokenForClient(clientId)?.let { token ->
            return PendingRequest(
                clientId = clientId,
                clientName = "",
                remoteIp = "",
                decision = PairDecision.APPROVED,
                issuedToken = token
            )
        }
        val req = pendingByClient[clientId] ?: return null
        if (System.currentTimeMillis() - req.createdAtMs > PAIR_TTL_MS) {
            pendingByClient.remove(clientId)
            return null
        }
        return req
    }

    /** Permanently trusts a Mac client scanned via QR code and returns its 256-bit token. */
    fun approvePairingDirectly(clientId: String, clientName: String): String {
        getTrustedTokenForClient(clientId)?.let { return it }
        val token = generate256BitToken()
        val json = JSONObject()
            .put("clientId", clientId)
            .put("clientName", clientName.ifBlank { "Mac" })
            .put("token", token)
            .put("approvedAtMs", System.currentTimeMillis())
            .toString()
        prefs.edit().putString("client_$clientId", json).apply()
        return token
    }

    fun approvePairing(clientId: String) {
        val req = pendingByClient[clientId]
        val token = generate256BitToken()
        val name = req?.clientName ?: "Mac"
        val json = JSONObject()
            .put("clientId", clientId)
            .put("clientName", name)
            .put("token", token)
            .put("approvedAtMs", System.currentTimeMillis())
            .toString()
        prefs.edit().putString("client_$clientId", json).apply()

        if (req != null) {
            req.issuedToken = token
            req.decision = PairDecision.APPROVED
        }
        dismissApprovalNotification(clientId)
    }

    fun denyPairing(clientId: String) {
        pendingByClient[clientId]?.decision = PairDecision.DENIED
        dismissApprovalNotification(clientId)
    }

    fun revokeAllTrustedClients() {
        prefs.edit().clear().apply()
        pendingByClient.clear()
    }

    private fun generate256BitToken(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) {
            diff = diff or (a[i].code xor b[i].code)
        }
        return diff == 0
    }

    private fun showApprovalNotification(req: PendingRequest) {
        ensureNotificationChannel()
        val notiId = notificationIdFor(req.clientId)

        val approveIntent = Intent(appContext, PairingApprovalReceiver::class.java).apply {
            action = PairingApprovalReceiver.ACTION_APPROVE
            putExtra(PairingApprovalReceiver.EXTRA_CLIENT_ID, req.clientId)
        }
        val denyIntent = Intent(appContext, PairingApprovalReceiver::class.java).apply {
            action = PairingApprovalReceiver.ACTION_DENY
            putExtra(PairingApprovalReceiver.EXTRA_CLIENT_ID, req.clientId)
        }

        val approvePi = PendingIntent.getBroadcast(
            appContext,
            notiId * 2,
            approveIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val denyPi = PendingIntent.getBroadcast(
            appContext,
            notiId * 2 + 1,
            denyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val noti = NotificationCompat.Builder(appContext, PAIR_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("🔐 Yêu cầu kết nối AirBridge mới")
            .setContentText("${req.clientName} (${req.remoteIp}) muốn ghép nối")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("${req.clientName} (${req.remoteIp}) đã nhập đúng mã PIN và muốn kết nối với điện thoại của bạn.")
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .addAction(android.R.drawable.ic_menu_send, "Cho phép", approvePi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Từ chối", denyPi)
            .build()

        runCatching {
            val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(notiId, noti)
        }
    }

    private fun dismissApprovalNotification(clientId: String) {
        runCatching {
            val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(notificationIdFor(clientId))
        }
    }

    private fun notificationIdFor(clientId: String): Int =
        8800 + (clientId.hashCode() and 0x7FFF) % 500

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(PAIR_CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    PAIR_CHANNEL_ID,
                    "AirBridge Xác nhận thiết bị mới",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Hỏi duyệt khi có máy Mac mới muốn kết nối qua Wi-Fi"
                }
                nm.createNotificationChannel(ch)
            }
        }
    }

    companion object {
        private const val PREFS_NAME = "airbridge_trusted_clients"
        private const val PAIR_CHANNEL_ID = "airbridge_pair_approval_channel"
        private const val MAX_FAILURES = 5
        private const val LOCKOUT_MS = 60_000L
        private const val PAIR_TTL_MS = 90_000L

        @Volatile
        private var instance: AirBridgeSecurityManager? = null

        fun getInstance(context: Context): AirBridgeSecurityManager =
            instance ?: synchronized(this) {
                instance ?: AirBridgeSecurityManager(context.applicationContext).also { instance = it }
            }
    }
}
