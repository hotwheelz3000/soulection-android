package com.hotwheelz3000.soulection

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.net.HttpURLConnection
import java.net.URL

/**
 * Keeps the music going with the screen off (a media foreground service), and shows what's
 * playing in the notification, on the lock screen and to headphones / Bluetooth / Android Auto
 * buttons, through a media session. Buttons go back to the page through MediaHub.
 */
class PlaybackService : Service() {

    companion object {
        private const val CHANNEL = "playback"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_PLAY_PAUSE = "sr.playpause"
        private const val ACTION_NEXT = "sr.next"
        private const val ACTION_PREVIOUS = "sr.previous"

        @SuppressLint("StaticFieldLeak")
        private var instance: PlaybackService? = null

        fun start(context: Context?) {
            if (instance != null || context == null) return
            try {
                ContextCompat.startForegroundService(context, Intent(context, PlaybackService::class.java))
            } catch (e: Exception) {
                // Android may refuse while the app is in the background; it starts next time.
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PlaybackService::class.java))
        }

        fun refresh() {
            instance?.render()
        }
    }

    private lateinit var session: MediaSessionCompat
    private val main = Handler(Looper.getMainLooper())
    private var artKey = ""
    private var artBitmap: Bitmap? = null
    private var foreground = false

    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Headphones unplugged or Bluetooth dropped: pause instead of blasting the speaker.
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) MediaHub.action("pause")
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Now playing", NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                }
            )
        }
        session = MediaSessionCompat(this, "SoulectionRadio").apply {
            setSessionActivity(openAppIntent())
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = MediaHub.action("play")
                override fun onPause() = MediaHub.action("pause")
                override fun onStop() = MediaHub.action("pause")
                override fun onSkipToNext() = MediaHub.action("nexttrack")
                override fun onSkipToPrevious() = MediaHub.action("previoustrack")
                override fun onSeekTo(pos: Long) = MediaHub.action("seekto", pos / 1000.0)
            })
            isActive = true
        }
        ContextCompat.registerReceiver(
            this, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> MediaHub.playPause()
            ACTION_NEXT -> MediaHub.action("nexttrack")
            ACTION_PREVIOUS -> MediaHub.action("previoustrack")
        }
        render()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiped away from recent apps: the page (and its music) is gone, so stop too.
        stopSelf()
    }

    override fun onDestroy() {
        instance = null
        try { unregisterReceiver(noisy) } catch (e: Exception) { }
        session.isActive = false
        session.release()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun openAppIntent(): PendingIntent =
        PendingIntent.getActivity(
            this, 0, MediaHub.openApp(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun serviceIntent(action: String, code: Int): PendingIntent =
        PendingIntent.getService(
            this, code, Intent(this, PlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    fun render() {
        val s = MediaHub.snapshot
        val title = s?.title ?: "Soulection Radio"
        val artist = s?.artist.orEmpty()
        val album = s?.album.orEmpty()
        val playing = s?.playing == true

        loadArt(s?.art.orEmpty())

        val meta = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, album)
        if (s != null && s.durMs > 0) meta.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, s.durMs)
        artBitmap?.let { meta.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it) }
        session.setMetadata(meta.build())

        var actions = PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_SEEK_TO
        if (s?.canNext == true) actions = actions or PlaybackStateCompat.ACTION_SKIP_TO_NEXT
        if (s?.canPrevious == true) actions = actions or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(
                    if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    s?.posMs ?: 0L,
                    if (playing) 1f else 0f,
                    s?.posAt ?: android.os.SystemClock.elapsedRealtime()
                )
                .build()
        )

        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_music)
            .setContentTitle(title)
            .setContentText(artist)
            .setSubText(album)
            .setLargeIcon(artBitmap)
            .setContentIntent(openAppIntent())
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setOngoing(playing)
            .addAction(R.drawable.ic_previous, "Previous", serviceIntent(ACTION_PREVIOUS, 1))
            .addAction(
                if (playing) R.drawable.ic_pause else R.drawable.ic_play,
                if (playing) "Pause" else "Play",
                serviceIntent(ACTION_PLAY_PAUSE, 2)
            )
            .addAction(R.drawable.ic_next, "Next", serviceIntent(ACTION_NEXT, 3))
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
        val notification = builder.build()

        if (!foreground) {
            try {
                ServiceCompat.startForeground(
                    this, NOTIFICATION_ID, notification,
                    if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
                )
                foreground = true
            } catch (e: Exception) {
                stopSelf()
            }
        } else {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
        }
    }

    /** The cover: a data: URL (made by the page) or a web address. Loaded off the main thread. */
    private fun loadArt(src: String) {
        if (src == artKey) return
        artKey = src
        if (src.isEmpty()) {
            artBitmap = null
            return
        }
        Thread {
            val bitmap = try {
                if (src.startsWith("data:")) {
                    val bytes = Base64.decode(src.substringAfter(","), Base64.DEFAULT)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                } else if (src.startsWith("https://")) {
                    val conn = URL(src).openConnection() as HttpURLConnection
                    conn.connectTimeout = 10000
                    conn.readTimeout = 10000
                    conn.inputStream.use { BitmapFactory.decodeStream(it) }
                } else null
            } catch (e: Exception) { null }
            main.post {
                if (artKey != src || bitmap == null) return@post
                artBitmap = bitmap
                render()
            }
        }.start()
    }
}
