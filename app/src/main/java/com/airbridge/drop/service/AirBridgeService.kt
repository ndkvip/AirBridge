package com.airbridge.drop.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.airbridge.drop.MainActivity
import com.airbridge.drop.protocol.AirBridgeCoordinator

class AirBridgeService : Service() {

    private lateinit var coordinator: AirBridgeCoordinator
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        coordinator = AirBridgeCoordinator.getInstance(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                coordinator.stopAll()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START_SCREEN_CAST -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                @Suppress("DEPRECATION")
                val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                } else {
                    intent.getParcelableExtra(EXTRA_DATA)
                }
                promoteForeground(withMediaProjection = true)
                acquireWifiLock()
                coordinator.startAll()
                if (data != null && resultCode == Activity.RESULT_OK) {
                    coordinator.startScreenStreaming(resultCode, data)
                }
                return START_STICKY
            }

            ACTION_STOP_SCREEN_CAST -> {
                coordinator.stopScreenStreaming()
                releaseWifiLock()
                promoteForeground(withMediaProjection = false)
                return START_STICKY
            }
        }

        promoteForeground(withMediaProjection = false)
        coordinator.startAll()
        return START_STICKY
    }

    override fun onDestroy() {
        releaseWifiLock()
        coordinator.stopAll()
        super.onDestroy()
    }

    private fun acquireWifiLock() {
        if (wifiLock?.isHeld == true) return
        runCatching {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wm.createWifiLock(mode, "AirBridgeWifiLock").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseWifiLock() {
        runCatching {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        }
        wifiLock = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun promoteForeground(withMediaProjection: Boolean) {
        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (withMediaProjection) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            }
        } else {
            0
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(withMediaProjection),
            fgsType
        )
    }

    private fun buildNotification(casting: Boolean): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPending = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, AirBridgeService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(if (casting) "AirBridge đang truyền màn hình lên Mac" else "AirBridge đang hoạt động")
            .setContentText("Web Portal: ${coordinator.webPortalUrl.value} • PIN: ${coordinator.webPin.value}")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setContentIntent(openPending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Tắt", stopPending)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AirBridge Sharing & Mirroring",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Giữ kết nối Web Portal, Clipboard và Truyền màn hình hoạt động nền"
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "airbridge_service_channel"
        private const val NOTIFICATION_ID = 8770
        const val ACTION_STOP = "com.airbridge.drop.ACTION_STOP"
        const val ACTION_START_SCREEN_CAST = "com.airbridge.drop.ACTION_START_SCREEN_CAST"
        const val ACTION_STOP_SCREEN_CAST = "com.airbridge.drop.ACTION_STOP_SCREEN_CAST"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data_intent"

        fun start(context: Context) {
            val intent = Intent(context, AirBridgeService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun startScreenCast(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, AirBridgeService::class.java).apply {
                action = ACTION_START_SCREEN_CAST
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopScreenCast(context: Context) {
            val intent = Intent(context, AirBridgeService::class.java).apply {
                action = ACTION_STOP_SCREEN_CAST
            }
            context.startService(intent)
        }
    }
}
