package com.airbridge.drop

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import com.airbridge.drop.protocol.AirBridgeCoordinator
import com.airbridge.drop.protocol.web.AirBridgeSecurityManager
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lightweight offline QR scanner (Camera2 + ZXing) for 1-scan permanent pairing with AirBridge macOS.
 */
class QrScannerActivity : Activity() {

    private lateinit var textureView: TextureView
    private lateinit var statusLabel: TextView
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var bgThread: HandlerThread? = null
    private var bgHandler: Handler? = null
    private val handledScan = AtomicBoolean(false)
    private var lastDecodeMs = 0L

    private val qrReader = MultiFormatReader().apply {
        setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        textureView = TextureView(this)
        root.addView(textureView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        val density = resources.displayMetrics.density
        val frameSize = (260 * density).toInt()
        val scanBox = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = 24 * density
                setStroke((3 * density).toInt(), Color.parseColor("#0A84FF"))
            }
        }
        root.addView(scanBox, FrameLayout.LayoutParams(frameSize, frameSize, Gravity.CENTER))

        statusLabel = TextView(this).apply {
            text = "Đưa camera vào mã QR trên AirBridge Mac"
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding((20 * density).toInt(), (12 * density).toInt(), (20 * density).toInt(), (12 * density).toInt())
            background = GradientDrawable().apply {
                setColor(Color.argb(170, 0, 0, 0))
                cornerRadius = 18 * density
            }
        }
        val labelParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply {
            bottomMargin = (72 * density).toInt()
        }
        root.addView(statusLabel, labelParams)

        val closeBtn = TextView(this).apply {
            text = "✕ Đóng"
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding((16 * density).toInt(), (8 * density).toInt(), (16 * density).toInt(), (8 * density).toInt())
            background = GradientDrawable().apply {
                setColor(Color.argb(160, 0, 0, 0))
                cornerRadius = 16 * density
            }
            setOnClickListener { finish() }
        }
        val closeParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END
        ).apply {
            topMargin = (48 * density).toInt()
            rightMargin = (20 * density).toInt()
        }
        root.addView(closeBtn, closeParams)

        setContentView(root)

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 101)
        } else {
            attachTextureListener()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            attachTextureListener()
        } else {
            Toast.makeText(this, "Cần quyền Camera để quét mã QR", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun attachTextureListener() {
        if (textureView.isAvailable) {
            openCamera()
        } else {
            textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { openCamera() }
                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true
                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        bgThread = HandlerThread("AirBridgeQrCamera").also { it.start() }
        bgHandler = Handler(bgThread!!.looper)

        val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val backCameraId = cm.cameraIdList.firstOrNull { id ->
            cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
        } ?: cm.cameraIdList.firstOrNull() ?: return

        imageReader = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 2).apply {
            setOnImageAvailableListener({ reader ->
                val img = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val now = System.currentTimeMillis()
                    if (handledScan.get() || now - lastDecodeMs < 120L) return@setOnImageAvailableListener
                    lastDecodeMs = now

                    val yPlane = img.planes[0]
                    val yBuf = yPlane.buffer
                    val rowStride = yPlane.rowStride
                    val w = img.width
                    val h = img.height
                    val data = ByteArray(w * h)
                    if (rowStride == w) {
                        yBuf.get(data)
                    } else {
                        for (row in 0 until h) {
                            yBuf.position(row * rowStride)
                            yBuf.get(data, row * w, w)
                        }
                    }

                    val source = PlanarYUVLuminanceSource(data, w, h, 0, 0, w, h, false)
                    val bitmap = BinaryBitmap(HybridBinarizer(source))
                    val result = runCatching { qrReader.decodeWithState(bitmap) }.getOrNull()
                    qrReader.reset()

                    val text = result?.text
                    if (text != null && text.startsWith("airbridge://pair?")) {
                        if (handledScan.compareAndSet(false, true)) {
                            handleScannedQr(text)
                        }
                    }
                } finally {
                    img.close()
                }
            }, bgHandler)
        }

        cm.openCamera(backCameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                val st = textureView.surfaceTexture ?: return
                st.setDefaultBufferSize(1280, 720)
                val previewSurface = Surface(st)
                val readerSurface = imageReader!!.surface

                val reqBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(previewSurface)
                    addTarget(readerSurface)
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                }

                @Suppress("DEPRECATION")
                camera.createCaptureSession(
                    listOf(previewSurface, readerSurface),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            captureSession = session
                            runCatching { session.setRepeatingRequest(reqBuilder.build(), null, bgHandler) }
                        }
                        override fun onConfigureFailed(session: CameraCaptureSession) {}
                    },
                    bgHandler
                )
            }

            override fun onDisconnected(camera: CameraDevice) { camera.close() }
            override fun onError(camera: CameraDevice, error: Int) { camera.close() }
        }, bgHandler)
    }

    private fun handleScannedQr(qrUri: String) {
        runOnUiThread { statusLabel.text = "⏳ Đang ghép nối vĩnh viễn với Mac..." }
        val uri = Uri.parse(qrUri)
        val clientId = uri.getQueryParameter("clientId").orEmpty()
        val clientName = uri.getQueryParameter("clientName").orEmpty().ifBlank { "Mac" }
        val macIp = uri.getQueryParameter("macIp").orEmpty()
        val macPort = uri.getQueryParameter("macPort")?.toIntOrNull() ?: 0
        val secret = uri.getQueryParameter("secret").orEmpty()

        if (clientId.isBlank() || macIp.isBlank() || macPort <= 0 || secret.isBlank()) {
            handledScan.set(false)
            runOnUiThread { statusLabel.text = "Mã QR không hợp lệ, thử lại" }
            return
        }

        Thread {
            try {
                val security = AirBridgeSecurityManager.getInstance(applicationContext)
                val token = security.approvePairingDirectly(clientId, clientName)
                val coordinator = AirBridgeCoordinator.getInstance(applicationContext)
                val payload = coordinator.buildQrPairReplyJson(secret = secret, token = token)

                val conn = (URL("http://$macIp:$macPort/qr-pair").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 5000
                    readTimeout = 5000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                conn.outputStream.use { it.write(payload.toByteArray(StandardCharsets.UTF_8)) }
                val code = conn.responseCode
                conn.disconnect()

                runOnUiThread {
                    if (code in 200..299) {
                        Toast.makeText(this, "✅ Đã ghép nối vĩnh viễn với $clientName!", Toast.LENGTH_LONG).show()
                        finish()
                    } else {
                        handledScan.set(false)
                        statusLabel.text = "Ghép nối thất bại (HTTP $code) — thử quét lại"
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    handledScan.set(false)
                    statusLabel.text = "Không kết nối được Mac ($macIp) — kiểm tra chung Wi-Fi"
                }
            }
        }.start()
    }

    override fun onDestroy() {
        runCatching { captureSession?.close() }
        runCatching { cameraDevice?.close() }
        runCatching { imageReader?.close() }
        runCatching { bgThread?.quitSafely() }
        super.onDestroy()
    }
}
