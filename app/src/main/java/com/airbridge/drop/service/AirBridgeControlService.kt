package com.airbridge.drop.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.airbridge.drop.protocol.AirBridgeCoordinator

/**
 * AccessibilityService enabling:
 * 1. Wireless touch, swipe, scroll, and navigation (Back / Home / Recents / Notifications)
 * 2. Reliable 2-way background clipboard sync on ColorOS/Android 15+ without ADB or Root
 *    (automatically reads normal Copy/Sao chép clicks and writes Mac clipboard via 1x1 invisible overlay).
 */
class AirBridgeControlService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var clipboardManager: ClipboardManager? = null

    @Volatile
    private var lastRemoteWriteMs: Long = 0L
    @Volatile
    private var lastReadAttemptMs: Long = 0L
    @Volatile
    private var lastPushedLocalText: String = ""

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        if (System.currentTimeMillis() - lastRemoteWriteMs < 1_500L) return@OnPrimaryClipChangedListener
        scheduleReadClipboardWithFocus(delayMs = 80L)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboardManager = cm
        runCatching { cm.addPrimaryClipChangedListener(clipListener) }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            val label = buildString {
                event.text?.forEach { append(it).append(' ') }
                event.contentDescription?.let { append(it) }
            }.lowercase()
            if (isCopyActionLabel(label)) {
                scheduleReadClipboardWithFocus(delayMs = 200L)
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        runCatching { clipboardManager?.removePrimaryClipChangedListener(clipListener) }
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun performTap(normX: Float, normY: Float): Boolean {
        val (w, h) = screenDimensions()
        val x = (normX.coerceIn(0f, 1f) * w).coerceIn(1f, (w - 1).toFloat())
        val y = (normY.coerceIn(0f, 1f) * h).coerceIn(1f, (h - 1).toFloat())

        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 45L)
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    fun performSwipe(normX1: Float, normY1: Float, normX2: Float, normY2: Float, durationMs: Long = 240L): Boolean {
        val (w, h) = screenDimensions()
        val x1 = (normX1.coerceIn(0f, 1f) * w).coerceIn(1f, (w - 1).toFloat())
        val y1 = (normY1.coerceIn(0f, 1f) * h).coerceIn(1f, (h - 1).toFloat())
        val x2 = (normX2.coerceIn(0f, 1f) * w).coerceIn(1f, (w - 1).toFloat())
        val y2 = (normY2.coerceIn(0f, 1f) * h).coerceIn(1f, (h - 1).toFloat())

        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val clampedDuration = durationMs.coerceIn(60L, 1200L)
        val stroke = GestureDescription.StrokeDescription(path, 0L, clampedDuration)
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    fun writeClipboardWithFocus(text: String) {
        lastPushedLocalText = text
        writeClipDataWithFocus(ClipData.newPlainText("AirBridge", text))
    }

    fun writeClipDataWithFocus(clip: ClipData) {
        lastRemoteWriteMs = System.currentTimeMillis()
        withTemporaryFocusOverlay { cm ->
            cm.setPrimaryClip(clip)
        }
    }

    private fun scheduleReadClipboardWithFocus(delayMs: Long) {
        val now = System.currentTimeMillis()
        if (now - lastRemoteWriteMs < 1_500L) return
        if (now - lastReadAttemptMs < 350L) return
        lastReadAttemptMs = now

        mainHandler.postDelayed({
            if (System.currentTimeMillis() - lastRemoteWriteMs < 1_500L) return@postDelayed
            withTemporaryFocusOverlay { cm ->
                val clip = cm.primaryClip ?: return@withTemporaryFocusOverlay
                if (clip.itemCount <= 0) return@withTemporaryFocusOverlay
                val text = clip.getItemAt(0).coerceToText(this)?.toString()?.trimEnd()
                if (!text.isNullOrEmpty() && text != lastPushedLocalText) {
                    lastPushedLocalText = text
                    AirBridgeCoordinator.getInstance(applicationContext)
                        .clipboardManager
                        .pushLocalText(text)
                }
            }
        }, delayMs)
    }

    private fun withTemporaryFocusOverlay(action: (ClipboardManager) -> Unit) {
        mainHandler.post {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val view = View(this)
            val params = WindowManager.LayoutParams(
                1,
                1,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
            }
            runCatching {
                wm.addView(view, params)
                view.postDelayed({
                    runCatching { action(cm) }
                    runCatching { wm.removeViewImmediate(view) }
                }, 70L)
            }.onFailure {
                runCatching { action(cm) }
            }
        }
    }

    private fun isCopyActionLabel(label: String): Boolean {
        if (label.isBlank()) return false
        return "copy" in label ||
            "sao chép" in label ||
            "chép" in label ||
            "cut" in label ||
            "cắt" in label ||
            "复制" in label
    }

    fun performNavAction(action: String): Boolean = when (action.lowercase()) {
        "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
        "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
        "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
        "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
        else -> false
    }

    private fun screenDimensions(): Pair<Int, Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        return metrics.widthPixels.coerceAtLeast(1) to metrics.heightPixels.coerceAtLeast(1)
    }

    companion object {
        @Volatile
        var instance: AirBridgeControlService? = null
            private set

        val isEnabled: Boolean
            get() = instance != null
    }
}
