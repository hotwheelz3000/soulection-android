package com.hotwheelz3000.soulection

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.webkit.JavaScriptReplyProxy
import org.json.JSONArray
import org.json.JSONObject

/**
 * Connects the web page's audio to Android.
 *
 * The audio plays inside SoundCloud player iframes on the page. media-shim.js runs in each
 * of them (and in the page itself) and reports here what the page's scripts set as "now
 * playing" (song, artist, cover, next/previous) and whether its audio is playing. The frame
 * whose audio was heard last is the one in charge: its info goes to the notification and
 * lock screen (PlaybackService), and their buttons are sent back to it.
 *
 * It also pauses the music for phone calls and brings it back after, and sets the players'
 * volume (sleep timer fade, dip between shows).
 */
object MediaHub {

    class Frame(val id: String, var proxy: JavaScriptReplyProxy, val top: Boolean) {
        var meta: JSONObject? = null
        var playing = false
        var hasMedia = false
        var pos = 0.0
        var dur = 0.0
        var posAt = 0L
        var actions: Set<String> = emptySet()
        var seen = SystemClock.elapsedRealtime()
    }

    data class Snapshot(
        val title: String,
        val artist: String,
        val album: String,
        val art: String,
        val playing: Boolean,
        val posMs: Long,
        val posAt: Long,
        val durMs: Long,
        val canNext: Boolean,
        val canPrevious: Boolean,
    )

    private val main = Handler(Looper.getMainLooper())
    private val frames = LinkedHashMap<String, Frame>()
    private var activeId: String? = null
    private var appContext: Context? = null

    /** Runs a script in the page (set by MainActivity while it exists). */
    var runJs: ((String) -> Unit)? = null

    /** Latest state, read by PlaybackService. */
    var snapshot: Snapshot? = null
        private set

    /** Volume for all the page's players, 0...1 (sleep fade, blend). */
    var volume = 1.0
        private set

    fun init(context: Context) {
        appContext = context.applicationContext
        startCallWatch()
    }

    /** A message from media-shim.js in some frame. Main thread. */
    fun onMessage(json: String, proxy: JavaScriptReplyProxy) {
        val d = try { JSONObject(json) } catch (e: Exception) { return }
        val id = d.optString("id").ifEmpty { return }
        var frame = frames[id]
        if (frame == null) {
            frame = Frame(id, proxy, d.optBoolean("top"))
            frames[id] = frame
            prune()
            if (volume < 1.0) post(frame, JSONObject().put("volume", volume))
        }
        frame.proxy = proxy
        frame.seen = SystemClock.elapsedRealtime()
        when (d.optString("t")) {
            "meta" -> frame.meta = d.optJSONObject("meta")
            "state" -> {
                frame.playing = d.optBoolean("playing")
                frame.hasMedia = d.optBoolean("hasMedia")
                frame.pos = d.optDouble("pos", 0.0)
                frame.dur = d.optDouble("dur", 0.0)
                frame.posAt = SystemClock.elapsedRealtime()
                val list = d.optJSONArray("actions") ?: JSONArray()
                frame.actions = (0 until list.length()).map { list.optString(it) }.toSet()
                if (frame.playing) {
                    activeId = id
                    lastPlayingAt = SystemClock.elapsedRealtime()
                } else if (activeId == null && frame.hasMedia) {
                    activeId = id
                }
            }
        }
        update()
    }

    /** The page loaded afresh: its frames are gone. */
    fun pageReloaded() {
        frames.clear()
        activeId = null
        update()
    }

    private fun prune() {
        if (frames.size <= 24) return
        val oldest = frames.values.filter { it.id != activeId }.minByOrNull { it.seen } ?: return
        frames.remove(oldest.id)
    }

    private fun update() {
        val active = activeId?.let { frames[it] }
        if (active == null) {
            snapshot = null
            PlaybackService.refresh()
            return
        }
        // The player's own info, or (before the player has any) what the page set.
        val meta = active.meta?.takeIf { it.optString("title").isNotEmpty() }
            ?: frames.values.firstOrNull { it.top }?.meta?.takeIf { it.optString("title").isNotEmpty() }
        val posMs = (active.pos * 1000).toLong()
        snapshot = Snapshot(
            title = meta?.optString("title").orEmpty().ifEmpty { "Soulection Radio" },
            artist = meta?.optString("artist").orEmpty(),
            album = meta?.optString("album").orEmpty(),
            art = meta?.optString("art").orEmpty(),
            playing = active.playing,
            posMs = posMs,
            posAt = active.posAt,
            durMs = (active.dur * 1000).toLong(),
            canNext = "nexttrack" in active.actions,
            canPrevious = "previoustrack" in active.actions,
        )
        if (active.playing) PlaybackService.start(appContext)
        PlaybackService.refresh()
    }

    // ---------------------------------------------------------------------------------
    // From the notification / lock screen / headphones
    // ---------------------------------------------------------------------------------

    fun action(name: String, seekSeconds: Double? = null) {
        val active = activeId?.let { frames[it] } ?: return
        val msg = JSONObject().put("action", name)
        if (seekSeconds != null) msg.put("seekTime", seekSeconds)
        post(active, msg)
    }

    fun playPause() {
        val playing = snapshot?.playing == true
        action(if (playing) "pause" else "play")
    }

    /** Pauses every player on the page (sleep timer, headphones unplugged). */
    fun pauseAll() {
        frames.values.forEach { post(it, JSONObject().put("pauseAll", true)) }
    }

    fun setVolume(value: Double) {
        volume = value.coerceIn(0.0, 1.0)
        val msg = JSONObject().put("volume", volume)
        frames.values.forEach { post(it, msg) }
    }

    private fun post(frame: Frame, msg: JSONObject) {
        val text = msg.toString()
        val run = Runnable { try { frame.proxy.postMessage(text) } catch (e: Exception) { } }
        if (Looper.myLooper() == Looper.getMainLooper()) run.run() else main.post(run)
    }

    // ---------------------------------------------------------------------------------
    // Phone calls: pause, then carry on after if it was playing (the page decides).
    // ---------------------------------------------------------------------------------

    private var lastPlayingAt = 0L
    private var inCall = false

    private fun startCallWatch() {
        val ctx = appContext ?: return
        val audio = ContextCompat.getSystemService(ctx, AudioManager::class.java) ?: return
        val tick = object : Runnable {
            override fun run() {
                val mode = audio.mode
                val call = mode == AudioManager.MODE_IN_CALL ||
                    mode == AudioManager.MODE_IN_COMMUNICATION ||
                    mode == AudioManager.MODE_RINGTONE
                if (call != inCall) {
                    inCall = call
                    if (call) {
                        if (snapshot?.playing == true) lastPlayingAt = SystemClock.elapsedRealtime()
                        runJs?.invoke("window.__soulectionFavorites && window.__soulectionFavorites.interrupted && window.__soulectionFavorites.interrupted(true);")
                        pauseAll()
                    } else {
                        runJs?.invoke("window.__soulectionFavorites && window.__soulectionFavorites.interrupted && window.__soulectionFavorites.interrupted(false);")
                    }
                }
                main.postDelayed(this, 1500)
            }
        }
        main.removeCallbacksAndMessages(null)
        main.post(tick)
    }

    fun openApp(context: Context): Intent =
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
}
