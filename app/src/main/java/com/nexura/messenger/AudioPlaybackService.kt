package com.nexura.messenger

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat

/**
 * Owns voice playback independently from Compose/message-card lifecycles.
 * The service remains in the foreground while audio is active.
 */
class AudioPlaybackService : Service() {
    private var player: MediaPlayer? = null
    private var title: String = "Голосовое сообщение"
    private var subtitle: String = "Nexura"
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            val current = player
            if (current != null) {
                playing = runCatching { current.isPlaying }.getOrDefault(false)
                positionMs = runCatching { current.currentPosition.toLong() }.getOrDefault(positionMs)
                durationMs = runCatching { current.duration.toLong() }.getOrDefault(durationMs)
                handler.postDelayed(this, 250)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> {
                val uri = intent.getStringExtra(EXTRA_URI)?.let(Uri::parse)
                if (uri != null) {
                    title = intent.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() } ?: "Голосовое сообщение"
                    subtitle = intent.getStringExtra(EXTRA_SUBTITLE)?.takeIf { it.isNotBlank() } ?: "Nexura"
                    startForeground(NOTIFICATION_ID, buildNotification())
                    startPlayback(uri)
                }
            }
            ACTION_PAUSE -> pausePlayback()
            ACTION_RESUME -> resumePlayback()
            ACTION_SEEK -> seekTo(intent.getLongExtra(EXTRA_POSITION, 0L))
            ACTION_STOP -> stopPlayback(true)
        }
        return START_NOT_STICKY
    }

    private fun startPlayback(uri: Uri) {
        stopPlayerOnly()
        activeUri = uri.toString()
        loading = true
        playing = false
        positionMs = 0L
        durationMs = 0L
        try {
            val next = MediaPlayer()
            player = next
            next.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            next.setOnPreparedListener { mp ->
                durationMs = mp.duration.toLong().coerceAtLeast(0L)
                loading = false
                positionMs = 0L
                mp.start()
                playing = true
                updateNotification()
                handler.removeCallbacks(tick)
                handler.post(tick)
            }
            next.setOnCompletionListener {
                playing = false
                positionMs = 0L
                updateNotification()
                stopPlayback(false)
            }
            next.setOnErrorListener { _, _, _ ->
                stopPlayback(true)
                true
            }
            next.setDataSource(this, uri)
            next.prepareAsync()
            updateNotification()
        } catch (_: Exception) {
            stopPlayback(true)
        }
    }

    private fun pausePlayback() {
        runCatching { player?.pause() }
        playing = false
        positionMs = runCatching { player?.currentPosition?.toLong() ?: positionMs }.getOrDefault(positionMs)
        updateNotification()
    }

    private fun resumePlayback() {
        val current = player ?: return
        runCatching {
            if (durationMs > 0L && positionMs >= durationMs) current.seekTo(0)
            current.start()
            playing = true
            handler.removeCallbacks(tick)
            handler.post(tick)
            updateNotification()
        }
    }

    private fun seekTo(position: Long) {
        val current = player ?: return
        val target = position.coerceIn(0L, durationMs.coerceAtLeast(0L))
        runCatching { current.seekTo(target.toInt()) }
        positionMs = target
    }

    private fun stopPlayerOnly() {
        handler.removeCallbacks(tick)
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
    }

    private fun stopPlayback(removeNotification: Boolean) {
        stopPlayerOnly()
        playing = false
        loading = false
        activeUri = null
        positionMs = 0L
        durationMs = 0L
        if (removeNotification) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun buildNotification(): Notification {
        val pauseOrResume = if (playing) ACTION_PAUSE else ACTION_RESUME
        val pauseIcon = if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val pauseLabel = if (playing) "Пауза" else "Продолжить"
        val pauseIntent = PendingIntent.getService(
            this, 2, Intent(this, AudioPlaybackService::class.java).setAction(pauseOrResume),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 3, Intent(this, AudioPlaybackService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = PendingIntent.getActivity(
            this, 4, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setContentIntent(openIntent)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(playing)
            .setOnlyAlertOnce(true)
            .addAction(pauseIcon, pauseLabel, pauseIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Закрыть", stopIntent)
            .build()
    }

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Аудиопроигрыватель", NotificationManager.IMPORTANCE_LOW)
            channel.description = "Управление голосовыми сообщениями Nexura"
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        stopPlayerOnly()
        playing = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_PLAY = "com.nexura.messenger.audio.PLAY"
        const val ACTION_PAUSE = "com.nexura.messenger.audio.PAUSE"
        const val ACTION_RESUME = "com.nexura.messenger.audio.RESUME"
        const val ACTION_SEEK = "com.nexura.messenger.audio.SEEK"
        const val ACTION_STOP = "com.nexura.messenger.audio.STOP"
        const val EXTRA_URI = "uri"
        const val EXTRA_TITLE = "title"
        const val EXTRA_SUBTITLE = "subtitle"
        const val EXTRA_POSITION = "position"
        private const val CHANNEL_ID = "nexura_audio_playback"
        private const val NOTIFICATION_ID = 4107

        @Volatile var activeUri: String? = null
            private set
        @Volatile var loading: Boolean = false
            private set
        @Volatile var playing: Boolean = false
            private set
        @Volatile var positionMs: Long = 0L
            private set
        @Volatile var durationMs: Long = 0L
            private set
    }
}
