package com.fason.app.features.screen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.fason.app.R
import com.fason.app.core.Protocol
import com.fason.app.core.network.SocketClient
import org.json.JSONObject

class ScreenCaptureService : Service() {
    private var mediaProjection: MediaProjection? = null
    private var screenEncoder: ScreenEncoder? = null
    private var frameDispatcher: ScreenFrameDispatcher? = null
    private var actionController: RemoteActionController? = null

    @Volatile
    private var stopping = false

    @Volatile
    private var streamGeneration = 0L

    companion object {
        @Volatile
        var isStreaming = false
            private set

        @Volatile
        var screenWidth = 0
            private set

        @Volatile
        var screenHeight = 0
            private set

        @Volatile
        var screenDensityDpi = 0
            private set

        @Volatile
        private var activeService: ScreenCaptureService? = null

        /** Routes commands arriving on the normal `order` channel to this service. */
        @JvmStatic
        fun handleRemoteAction(message: String): Boolean {
            val service = activeService ?: return false
            val controller = service.actionController ?: return false
            controller.handleAction(message)
            return true
        }

        @JvmStatic
        fun isRemoteControlAvailable(): Boolean = RemoteControlService.instance != null

        private const val ACTION_START = "START"
        private const val ACTION_STOP = "STOP"
        private const val EXTRA_RESULT_CODE = "RESULT_CODE"
        private const val EXTRA_DATA = "DATA"
        private const val NOTIFICATION_CHANNEL = "ScreenCaptureChannel"
        private const val NOTIFICATION_ID = 1
        private const val ENCODER_FPS = 30
    }

    private val socketListener = io.socket.emitter.Emitter.Listener { args ->
        val message = when (val payload = args.firstOrNull()) {
            is String -> payload
            is JSONObject -> payload.toString()
            else -> null
        }
        if (message != null) handleRemoteAction(message)
    }

    override fun onCreate() {
        super.onCreate()
        activeService = this
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                createNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                @Suppress("DEPRECATION")
                val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)
                if (resultCode != 0 && data != null) {
                    startStreaming(resultCode, data)
                } else {
                    emitError("Screen capture permission was not granted")
                    stopSelf()
                }
            }

            ACTION_STOP -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startStreaming(resultCode: Int, data: Intent) {
        stopStreaming(notifyPanel = false)
        val generation = ++streamGeneration
        stopping = false
        actionController = RemoteActionController()

        val metrics = readDisplayMetrics()
        // AVC requires even dimensions. Android display sizes are normally already even.
        screenWidth = metrics.width.coerceAtLeast(2).let { it - (it % 2) }
        screenHeight = metrics.height.coerceAtLeast(2).let { it - (it % 2) }
        screenDensityDpi = metrics.densityDpi

        val socket = SocketClient.getInstance().socket
        socket?.off(Protocol.SCREEN_CTRL, socketListener)
        socket?.on(Protocol.SCREEN_CTRL, socketListener)

        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = projectionManager.getMediaProjection(resultCode, data)
        mediaProjection = projection

        frameDispatcher = ScreenFrameDispatcher().also { it.start() }
        screenEncoder = ScreenEncoder(
            mediaProjection = projection,
            videoWidth = screenWidth,
            videoHeight = screenHeight,
            videoDpi = screenDensityDpi,
            onReady = {
                if (generation == streamGeneration && !stopping) {
                    isStreaming = true
                    emitStatus(streaming = true)
                }
            },
            onFrameEncoded = { frame ->
                if (generation == streamGeneration && !stopping) frameDispatcher?.offer(frame)
            },
            onError = { message ->
                if (generation == streamGeneration && !stopping) {
                    emitError(message)
                    stopSelf()
                }
            },
            onProjectionStopped = {
                if (generation == streamGeneration && !stopping) stopSelf()
            },
        ).also { it.start() }
    }

    private fun stopStreaming(notifyPanel: Boolean = true) {
        val hadStream = isStreaming || screenEncoder != null || mediaProjection != null
        streamGeneration++
        stopping = true
        isStreaming = false

        screenEncoder?.stop()
        screenEncoder = null
        frameDispatcher?.stop()
        frameDispatcher = null

        try {
            mediaProjection?.stop()
        } catch (_: Exception) {
        }
        mediaProjection = null

        SocketClient.getInstance().socket?.let { socket ->
            socket.off(Protocol.SCREEN_CTRL, socketListener)
            if (notifyPanel && hadStream) emitStatus(streaming = false)
        }
        actionController = null
    }

    private fun emitStatus(streaming: Boolean) {
        val status = JSONObject().apply {
            put(Protocol.KEY_TYPE, Protocol.KEY_STATUS)
            put(Protocol.KEY_STREAMING, streaming)
            put(Protocol.KEY_SCREEN_W, screenWidth)
            put(Protocol.KEY_SCREEN_H, screenHeight)
            put("densityDpi", screenDensityDpi)
            put("fps", ENCODER_FPS)
            put("codec", "video/avc")
            put("frameTransport", "binary")
            put(Protocol.KEY_ACCESSIBLE, isRemoteControlAvailable())
        }
        SocketClient.getInstance().socket?.emit(Protocol.SCREEN, status)
    }

    private fun emitError(message: String) {
        val error = JSONObject().apply {
            put(Protocol.KEY_TYPE, Protocol.KEY_ERROR)
            put(Protocol.KEY_ERROR, message)
        }
        SocketClient.getInstance().socket?.emit(Protocol.SCREEN, error)
    }

    private fun readDisplayMetrics(): NativeDisplayMetrics {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds: Rect = windowManager.maximumWindowMetrics.bounds
            NativeDisplayMetrics(
                width = bounds.width(),
                height = bounds.height(),
                densityDpi = resources.configuration.densityDpi,
            )
        } else {
            @Suppress("DEPRECATION")
            val display = windowManager.defaultDisplay
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)
            NativeDisplayMetrics(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
        }
    }

    override fun onDestroy() {
        stopStreaming()
        if (activeService === this) activeService = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL,
                "Screen Capture",
                NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val stopIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL)
            .setContentTitle("Remote desktop active")
            .setContentText("Streaming the device screen to the web panel")
            .setSmallIcon(R.mipmap.ic_launcher)
            .addAction(0, "Stop", stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    private data class NativeDisplayMetrics(
        val width: Int,
        val height: Int,
        val densityDpi: Int,
    )

}
