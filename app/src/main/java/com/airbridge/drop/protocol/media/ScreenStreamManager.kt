package com.airbridge.drop.protocol.media

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicReference

/**
 * Captures the Android screen via MediaProjection into JPEG frames
 * for real-time MJPEG streaming to Mac/iOS browsers over local Wi-Fi.
 */
class ScreenStreamManager(private val context: Context) {

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val latestFrame = AtomicReference<ByteArray?>(null)
    @Volatile
    private var lastFrameTimestampMs = 0L

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null

    fun start(resultCode: Int, data: Intent, maxHeight: Int = 1080, jpegQuality: Int = 60) {
        stop()

        val pm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = pm.getMediaProjection(resultCode, data) ?: return
        mediaProjection = projection

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)

        val scale = if (metrics.heightPixels > maxHeight) {
            maxHeight.toFloat() / metrics.heightPixels.toFloat()
        } else 1f
        val width = ((metrics.widthPixels * scale).toInt() / 2) * 2
        val height = ((metrics.heightPixels * scale).toInt() / 2) * 2
        val density = metrics.densityDpi

        val thread = HandlerThread("AirBridgeScreenCapture").apply { start() }
        val h = Handler(thread.looper)
        handlerThread = thread
        handler = h

        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stop()
            }
        }, h)

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader

        val jpegOut = ByteArrayOutputStream(128 * 1024)
        var lastEncodeMs = 0L

        reader.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val now = System.currentTimeMillis()
                // Target ~15 FPS so MJPEG stays smooth without saturating Wi-Fi API requests
                if (now - lastEncodeMs < 65L) return@setOnImageAvailableListener
                lastEncodeMs = now

                val plane = image.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * width

                val bitmapWidth = width + rowPadding / pixelStride
                val fullBitmap = Bitmap.createBitmap(bitmapWidth, height, Bitmap.Config.ARGB_8888)
                fullBitmap.copyPixelsFromBuffer(buffer)

                val cropped = if (bitmapWidth != width) {
                    Bitmap.createBitmap(fullBitmap, 0, 0, width, height).also { fullBitmap.recycle() }
                } else {
                    fullBitmap
                }

                jpegOut.reset()
                cropped.compress(Bitmap.CompressFormat.JPEG, jpegQuality, jpegOut)
                cropped.recycle()

                latestFrame.set(jpegOut.toByteArray())
                lastFrameTimestampMs = now
            } catch (_: Throwable) {
                // Ignore transient frame errors
            } finally {
                image.close()
            }
        }, h)

        virtualDisplay = projection.createVirtualDisplay(
            "AirBridgeMirror",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            h
        )
        _isStreaming.value = true
    }

    fun getLatestJpegFrame(): Pair<ByteArray?, Long> = latestFrame.get() to lastFrameTimestampMs

    fun stop() {
        _isStreaming.value = false
        latestFrame.set(null)
        runCatching { virtualDisplay?.release() }
        runCatching { imageReader?.close() }
        runCatching { mediaProjection?.stop() }
        runCatching { handlerThread?.quitSafely() }
        virtualDisplay = null
        imageReader = null
        mediaProjection = null
        handlerThread = null
        handler = null
    }
}
