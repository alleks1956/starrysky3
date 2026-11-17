package com.example.timelapsecamera

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import android.provider.MediaStore
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

class CameraForegroundService : LifecycleService() {

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_TARGET_TIME_MS = "EXTRA_TARGET_TIME_MS"
        const val EXTRA_INTERVAL_SEC = "EXTRA_INTERVAL_SEC"
        const val EXTRA_TOTAL_COUNT = "EXTRA_TOTAL_COUNT"
        const val EXTRA_WIDTH = "EXTRA_WIDTH"
        const val EXTRA_HEIGHT = "EXTRA_HEIGHT"

        private const val CHANNEL_ID = "camera_timer_channel"
        private const val NOTIFICATION_ID = 101

        private val _statusFlow = MutableStateFlow("Готов к работе")
        val statusFlow = _statusFlow.asStateFlow()

        private val _isRunningFlow = MutableStateFlow(false)
        val isRunningFlow = _isRunningFlow.asStateFlow()
    }

    private var imageCapture: ImageCapture? = null
    private var modeLabel = "[JPG]"
    private var shootingJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_START -> {
                val targetTimeMs = intent.getLongExtra(EXTRA_TARGET_TIME_MS, System.currentTimeMillis())
                val intervalSec = intent.getLongExtra(EXTRA_INTERVAL_SEC, 1L)
                val totalCount = intent.getIntExtra(EXTRA_TOTAL_COUNT, 1)
                val width = intent.getIntExtra(EXTRA_WIDTH, 0)
                val height = intent.getIntExtra(EXTRA_HEIGHT, 0)
                val size = if (width > 0 && height > 0) Size(width, height) else null

                startForegroundServiceWithNotification("Подготовка камеры...")
                acquireWakeLock()
                initCameraAndStartSequence(targetTimeMs, intervalSec, totalCount, size)
            }
            ACTION_STOP -> {
                stopSequence("Съемка отменена")
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundServiceWithNotification(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun initCameraAndStartSequence(
        targetTimeMs: Long,
        intervalSec: Long,
        totalCount: Int,
        size: Size?
    ) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            imageCapture = ImageCapture.Builder()
                .setResolutionSelector(CameraResolution.buildCaptureSelector(size))
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setOutputFormat(ImageCapture.OUTPUT_FORMAT_JPEG)
                .build()

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, imageCapture)

                val actual = imageCapture?.resolutionInfo?.resolution
                modeLabel = if (actual != null) "[JPG ${actual.width}×${actual.height}]" else "[JPG]"

                runShootingLoop(targetTimeMs, intervalSec, totalCount)
            } catch (e: Exception) {
                stopSequence("Ошибка камеры: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun runShootingLoop(targetTimeMs: Long, intervalSec: Long, totalCount: Int) {
        shootingJob?.cancel()
        _isRunningFlow.value = true

        shootingJob = lifecycleScope.launch {
            while (System.currentTimeMillis() < targetTimeMs) {
                val remainingMs = targetTimeMs - System.currentTimeMillis()
                updateStatus("$modeLabel До старта: ${formatRemainingTime(remainingMs)}")
                delay(500L)
            }

            for (currentShot in 1..totalCount) {
                updateStatus("$modeLabel Съемка кадра $currentShot из $totalCount...")
                takePhoto(currentShot)

                if (currentShot < totalCount) {
                    for (sec in intervalSec downTo 1) {
                        updateStatus("$modeLabel Кадр $currentShot/$totalCount. След. через $sec с...")
                        delay(1000L)
                    }
                }
            }

            // Даем время дописать последний файл на диск
            delay(3000L)
            stopSequence("Готово! Сохранено кадров: $totalCount $modeLabel")
        }
    }

    private fun takePhoto(current: Int) {
        val capture = imageCapture ?: return
        val baseName = "IMG_" + SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US)
            .format(System.currentTimeMillis())

        capture.takePicture(
            createOutputOptions(baseName, "image/jpeg"),
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    updateStatus("Ошибка кадра $current: ${exc.message}")
                }
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {}
            }
        )
    }

    private fun createOutputOptions(displayName: String, mimeType: String): ImageCapture.OutputFileOptions {
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/IntervalCamera")
            }
        }

        return ImageCapture.OutputFileOptions.Builder(
            contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ).build()
    }

    private fun stopSequence(finalMessage: String) {
        shootingJob?.cancel()
        _isRunningFlow.value = false
        _statusFlow.value = finalMessage
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun updateStatus(text: String) {
        _statusFlow.value = text
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(contentText: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, CameraForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Таймер фотокамеры")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(openPendingIntent)
            .addAction(android.R.drawable.ic_delete, "Остановить", stopPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Фоновая съемка по таймеру",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "IntervalCamera::ShootingWakeLock"
        ).apply {
            acquire(12 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        wakeLock = null
    }

    private fun formatRemainingTime(millis: Long): String {
        val totalSeconds = (millis + 999) / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }
}
