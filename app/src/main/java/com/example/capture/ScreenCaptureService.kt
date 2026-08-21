package com.example.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.MainActivity
import com.example.R

class ScreenCaptureService : Service() {

    companion object {
        const val CHANNEL_ID = "screen_capture_service_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.example.aiscreenanalyzer.ACTION_START"
        const val ACTION_STOP = "com.example.aiscreenanalyzer.ACTION_STOP"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_DATA = "extra_data"

        fun startService(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            // Stopping a service must not start/revive it just to deliver a stop command.
            // Directly stopping the service also works when the app is in the background.
            context.stopService(Intent(context, ScreenCaptureService::class.java))
        }
    }

    private var mediaProjection: MediaProjection? = null
    private var isCleaningUp = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_DATA)
                }

                if (resultCode != 0 && data != null) {
                    startForegroundNotification()
                    initMediaProjection(resultCode, data, startId)
                } else {
                    stopSelfResult(startId)
                }
            }
            ACTION_STOP -> {
                cleanupAndStop(startId)
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundNotification() {
        val stopIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val appIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val appPendingIntent = PendingIntent.getActivity(
            this,
            0,
            appIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AI Screen Analyzer Active")
            .setContentText("Capturing screen for multimodal AI analysis")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(appPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Monitoring", stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        val foregroundServiceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else {
            0
        }

        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, foregroundServiceType)
    }

    /**
     * Starts a new MediaProjection session without ever leaving the previous projection alive.
     * This makes repeated START commands idempotent from a resource/lifecycle perspective.
     */
    private fun initMediaProjection(resultCode: Int, data: Intent, startId: Int) {
        stopCurrentProjection()

        try {
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = projectionManager.getMediaProjection(resultCode, data)
            mediaProjection = projection

            if (projection != null) {
                ScreenCaptureEngine.initialize(
                    context = applicationContext,
                    projection = projection,
                    onStopCallback = {
                        stopSelfResult(startId)
                    }
                )
            } else {
                mediaProjection = null
                stopSelfResult(startId)
            }
        } catch (_: SecurityException) {
            mediaProjection = null
            ScreenCaptureEngine.stop()
            stopSelfResult(startId)
        } catch (_: Exception) {
            mediaProjection = null
            ScreenCaptureEngine.stop()
            stopSelfResult(startId)
        }
    }

    /**
     * Stops only the active capture session. Does not call stopSelf(), so it is safe to use
     * while replacing a projection during an ACTION_START without recursively destroying
     * and recreating the service.
     */
    private fun stopCurrentProjection() {
        ScreenCaptureEngine.stop()
        try {
            mediaProjection?.stop()
        } catch (_: Exception) {
            // MediaProjection.stop() is idempotent enough for our lifecycle purposes; the
            // engine cleanup is the authoritative resource cleanup path.
        }
        mediaProjection = null
    }

    private fun cleanupAndStop(startId: Int? = null) {
        if (isCleaningUp) {
            startId?.let(::stopSelfResult)
            return
        }
        isCleaningUp = true
        try {
            stopCurrentProjection()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            if (startId != null) {
                stopSelfResult(startId)
            } else {
                stopSelf()
            }
        } finally {
            isCleaningUp = false
        }
    }

    override fun onDestroy() {
        // onDestroy can happen without ACTION_STOP (system/user/service termination), so
        // resource cleanup must remain authoritative here as well.
        stopCurrentProjection()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screen Capture Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows ongoing status when AI Screen Analyzer is capturing screen frames"
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
}
