package com.example.webtrafficmonitor.monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.example.webtrafficmonitor.MainActivity
import com.example.webtrafficmonitor.R
import com.example.webtrafficmonitor.data.MonitorEntry
import com.example.webtrafficmonitor.data.MonitorStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

/**
 * Captures periodic, downscaled screenshots of the whole screen using
 * MediaProjection. Frames are drained continuously (cheap) but only saved once
 * every [CAPTURE_INTERVAL_MS], to keep battery, CPU and storage use sane.
 *
 * The user must grant the one-time "Start recording?" consent; we receive the
 * resulting permission token in the start intent.
 */
class ScreenCaptureService : Service() {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private val captureThread = HandlerThread("screen-capture").apply { start() }
    private val captureHandler = Handler(captureThread.looper)
    private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var lastSavedAt = 0L

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, RESULT_INVALID) ?: RESULT_INVALID
        val data: Intent? =
            if (intent == null) null
            else IntentCompat.getParcelableExtra(intent, EXTRA_DATA, Intent::class.java)
        if (resultCode == RESULT_INVALID || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        // On Android 10+ the foreground service must be running before we get the projection.
        startForeground()

        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = manager.getMediaProjection(resultCode, data)
        if (mp == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        projection = mp
        mp.registerCallback(projectionCallback, captureHandler)

        startCapturing(mp)
        isRunning = true
        return START_STICKY
    }

    private fun startCapturing(mp: MediaProjection) {
        val (screenWidth, screenHeight, densityDpi) = screenMetrics()
        val (width, height) = targetSize(screenWidth, screenHeight)

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, MAX_BUFFERED_IMAGES)
        reader.setOnImageAvailableListener({ onFrame(it) }, captureHandler)
        imageReader = reader

        virtualDisplay = mp.createVirtualDisplay(
            "monitor-capture",
            width,
            height,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            captureHandler,
        )
    }

    /** Runs on the capture thread for every screen frame. Cheap unless it is time to save. */
    private fun onFrame(reader: ImageReader) {
        val image = reader.acquireLatestImage() ?: return
        try {
            val now = System.currentTimeMillis()
            if (now - lastSavedAt < CAPTURE_INTERVAL_MS) return
            lastSavedAt = now

            val bitmap = image.toBitmap()
            val appPackage = ForegroundApp.packageName
            saveScope.launch { saveCapture(bitmap, now, appPackage) }
        } finally {
            image.close()
        }
    }

    private fun saveCapture(bitmap: Bitmap, timestamp: Long, appPackage: String?) {
        val dir = File(filesDir, CAPTURE_DIR).apply { mkdirs() }
        val file = File(dir, "$timestamp.jpg")
        try {
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
        } finally {
            bitmap.recycle()
        }

        MonitorStore.record(
            this,
            MonitorEntry(
                timestamp = timestamp,
                kind = MonitorEntry.KIND_SCREEN,
                packageName = appPackage,
                screenshotPath = file.absolutePath,
            ),
        )
    }

    private fun startForeground() {
        val channelId = "screen_capture"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                getString(R.string.capture_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.capture_notification_title))
            .setContentText(getString(R.string.capture_notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIF_ID, notification, type)
    }

    private fun screenMetrics(): Triple<Int, Int, Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            Triple(bounds.width(), bounds.height(), resources.configuration.densityDpi)
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            Triple(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
        }
    }

    /** Scales the longest side down to [MAX_DIMEN], keeping the aspect ratio. */
    private fun targetSize(width: Int, height: Int): Pair<Int, Int> {
        val longest = max(width, height)
        if (longest <= MAX_DIMEN) return evenPair(width, height)
        val scale = MAX_DIMEN.toFloat() / longest
        return evenPair((width * scale).toInt(), (height * scale).toInt())
    }

    private fun evenPair(width: Int, height: Int): Pair<Int, Int> =
        Pair(width - (width % 2), height - (height % 2))

    override fun onDestroy() {
        isRunning = false
        virtualDisplay?.release()
        imageReader?.close()
        projection?.unregisterCallback(projectionCallback)
        projection?.stop()
        captureThread.quitSafely()
        super.onDestroy()
    }

    companion object {
        @Volatile
        var isRunning = false
            private set

        private const val NOTIF_ID = 1001
        private const val RESULT_INVALID = 0
        private const val CAPTURE_DIR = "captures"
        private const val CAPTURE_INTERVAL_MS = 3000L
        private const val MAX_DIMEN = 720
        private const val MAX_BUFFERED_IMAGES = 2
        private const val JPEG_QUALITY = 60

        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_DATA = "data"
        private const val ACTION_STOP = "stop"

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_DATA, data)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}

/** Copies an RGBA screen frame into a Bitmap, handling row padding. */
private fun Image.toBitmap(): Bitmap {
    val plane = planes[0]
    val pixelStride = plane.pixelStride
    val rowStride = plane.rowStride
    val rowPadding = rowStride - pixelStride * width

    val bitmap = Bitmap.createBitmap(
        width + rowPadding / pixelStride,
        height,
        Bitmap.Config.ARGB_8888,
    )
    bitmap.copyPixelsFromBuffer(plane.buffer)

    return if (rowPadding == 0) {
        bitmap
    } else {
        // Crop the padding columns off the right edge.
        Bitmap.createBitmap(bitmap, 0, 0, width, height).also { bitmap.recycle() }
    }
}
