package com.example

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OverlayService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private val capturedBitmaps = mutableListOf<Bitmap>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, "overlay_channel")
            .setContentTitle("Screen Capture Active")
            .setContentText("Tap the floating camera to capture")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notification)
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        setupOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopSelf()
            return START_NOT_STICKY
        }
        val resultCode = intent?.getIntExtra("resultCode", 0) ?: 0
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>("data")
        if (resultCode != 0 && data != null && mediaProjection == null) {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpm.getMediaProjection(resultCode, data)
        }
        return START_NOT_STICKY
    }

    private fun setupOverlay() {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) 
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY 
            else 
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        params.y = 300

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setPadding(16, 16, 16, 16)
        }
        
        val closeBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setBackgroundColor(android.graphics.Color.parseColor("#E0FF5252"))
            setPadding(32, 32, 32, 32)
            setOnClickListener { stopSelf() }
        }

        val captureBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_camera)
            setBackgroundColor(android.graphics.Color.parseColor("#E02196F3"))
            setPadding(32, 32, 32, 32)
            setOnClickListener { takeScreenshot() }
        }

        val saveBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_save)
            setBackgroundColor(android.graphics.Color.parseColor("#E04CAF50"))
            setPadding(32, 32, 32, 32)
            setOnClickListener { stitchAndSave() }
        }

        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, 0, 0, 24)
        
        val buttonLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        buttonLp.setMargins(0, 0, 0, 16)

        layout.addView(closeBtn, buttonLp)
        layout.addView(captureBtn, buttonLp)
        layout.addView(saveBtn, buttonLp)
        
        overlayView = layout

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        overlayView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(overlayView, params)
                    true
                }
                else -> false
            }
        }

        windowManager.addView(overlayView, params)
    }

    private fun takeScreenshot() {
        if (mediaProjection == null) {
            Toast.makeText(this, "Screen capture not initialized.", Toast.LENGTH_SHORT).show()
            return
        }
        
        mediaProjection?.let { mp ->
            overlayView.visibility = View.INVISIBLE
            Handler(Looper.getMainLooper()).postDelayed({
                val metrics = resources.displayMetrics
                val width = metrics.widthPixels
                val height = metrics.heightPixels
                val density = metrics.densityDpi

                imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
                virtualDisplay = mp.createVirtualDisplay("ScreenCapture",
                    width, height, density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader!!.surface, null, null)

                var isCaught = false
                imageReader!!.setOnImageAvailableListener({ reader ->
                    if (isCaught) return@setOnImageAvailableListener
                    try {
                        val image: Image? = reader.acquireLatestImage()
                        image?.let {
                            isCaught = true
                            val planes = it.planes
                            val buffer = planes[0].buffer
                            val pixelStride = planes[0].pixelStride
                            val rowStride = planes[0].rowStride
                            val rowPadding = rowStride - pixelStride * width
                            val bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
                            bitmap.copyPixelsFromBuffer(buffer)
                            val finalBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height)
                            it.close()
                            
                            teardownVirtualDisplay()
                            
                            scope.launch(Dispatchers.Main) {
                                capturedBitmaps.add(finalBitmap)
                                overlayView.visibility = View.VISIBLE
                                Toast.makeText(this@OverlayService, "Captured part ${capturedBitmaps.size}. Scroll and capture again, or tap Save.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        teardownVirtualDisplay()
                        overlayView.visibility = View.VISIBLE
                    }
                }, null)
            }, 300) 
        }
    }

    private fun stitchAndSave() {
        if (capturedBitmaps.isEmpty()) {
            Toast.makeText(this, "No images captured.", Toast.LENGTH_SHORT).show()
            return
        }
        
        Toast.makeText(this, "Stitching images...", Toast.LENGTH_SHORT).show()
        scope.launch {
            try {
                val width = capturedBitmaps.first().width
                var totalHeight = capturedBitmaps.sumOf { it.height }
                
                if (totalHeight > 15000) totalHeight = 15000
                
                val resultBitmap = Bitmap.createBitmap(width, totalHeight, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(resultBitmap)
                var currentY = 0f
                for (bmp in capturedBitmaps) {
                    if (currentY + bmp.height > totalHeight) break
                    canvas.drawBitmap(bmp, 0f, currentY, null)
                    currentY += bmp.height
                }
                
                saveBitmapToMediaStore(this@OverlayService, resultBitmap)
                capturedBitmaps.forEach { it.recycle() }
                capturedBitmaps.clear()
                
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@OverlayService, "Long screenshot stitched & saved!", Toast.LENGTH_LONG).show()
                    stopSelf()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@OverlayService, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun teardownVirtualDisplay() {
        virtualDisplay?.release()
        imageReader?.close()
        virtualDisplay = null
        imageReader = null
    }

    private fun saveBitmapToMediaStore(context: Context, bitmap: Bitmap) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "screenshot_${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Screenshots")
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        uri?.let { destUri ->
            context.contentResolver.openOutputStream(destUri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("overlay_channel", "Overlay Service", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::windowManager.isInitialized && overlayView.windowToken != null) {
            windowManager.removeView(overlayView)
        }
        teardownVirtualDisplay()
        mediaProjection?.stop()
        mediaProjection = null
        capturedBitmaps.forEach { it.recycle() }
        capturedBitmaps.clear()
    }
}
