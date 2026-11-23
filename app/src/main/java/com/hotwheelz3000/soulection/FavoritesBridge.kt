package com.hotwheelz3000.soulection

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The Android side of favorites.js (the iPhone app's FavoritesBridge).
 *
 * Page → app: `SRBridge.postMessage(JSON.stringify({op, ...}))`, handled in [handle].
 * App → page: `window.__soulectionFavorites.receive(list, {...})`, `.toast(text)` and friends.
 */
class FavoritesBridge(private val activity: MainActivity) {

    companion object {
        const val BACKUP_FILE_NAME = "Soulection Radio Backup.json"
        private const val FILE_KEY = "Backup.fileUri"
        private const val LAST_SAVED_KEY = "Backup.lastSaved"
        private const val RESTORE_OFFERED_KEY = "Backup.restoreOffered"
        private const val WELCOME_SEEN_KEY = "App.welcomeHidden"
        private const val LOCAL_URI_KEY = "Backup.localUri"
        private const val FADE_LENGTH = 30.0
        private const val BLEND_FLOOR = 0.15
        private const val BLEND_IN_LENGTH = 3.0
        private const val BLEND_HOLD_LIMIT = 12.0
    }

    enum class Picker { IMPORT, SAVE_BACKUP, RESTORE_BACKUP, RECONNECT, EXPORT }

    val store = FavoritesStore(activity)
    private val prefs = activity.getSharedPreferences("app", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private var pickerPurpose = Picker.IMPORT
    private var autoBackupError = false
    private var lastWriteFailure: String? = null

    init {
        writeLocalBackupInBackground()
    }

    private fun js(script: String) = activity.runJs(script)

    private fun quote(text: String) = JSONObject.quote(text)

    fun handle(body: JSONObject) {
        val op = body.optString("op")
        when (op) {
            "toggle" -> FavoritesStore.song(body.optJSONObject("favorite"))?.let { store.toggle(it) } ?: return
            "remove" -> store.remove(body.optString("id").ifEmpty { return })
            "backupLater" -> store.addedSinceBackup = 0
            "played" -> FavoritesStore.song(body.optJSONObject("favorite"))?.let { store.recordPlay(it) }
            "historyRemove" -> store.removeFromHistory(body.optString("id").ifEmpty { return })
            "historyClear" -> store.clearHistory()
            "continue" -> {
                val list = FavoritesStore.continues(body.optJSONArray("list"))
                if (store.setContinue(list)) scheduleAutoBackup()
                return
            }
            "sleepStart" -> {
                val seconds = body.optDouble("seconds", 0.0)
                if (seconds > 0) startSleepTimer(seconds)
                return
            }
            "sleepCancel" -> {
                sleepRunnable?.let { main.removeCallbacks(it) }
                sleepRunnable = null
                cancelFade(restore = true)
                return
            }
            "sleepFade" -> {
                val seconds = body.optDouble("seconds", 0.0)
                if (seconds > 0) startFade(seconds, null)
                return
            }
            "sleepNow" -> {
                sleepRunnable?.let { main.removeCallbacks(it) }
                sleepRunnable = null
                pauseAndRestoreVolume()
                return
            }
            "blendOut" -> {
                blendOut(body.optDouble("seconds", 3.0).coerceIn(0.5, 4.0))
                return
            }
            "blendIn" -> {
                blendIn()
                return
            }
            "backupCreate" -> { saveBackupToFiles(); return }
            "restoreBackup" -> { pick(Picker.RESTORE_BACKUP); return }
            "backupReconnect" -> { pick(Picker.RECONNECT); return }
            "restoreDismissed" -> prefs.edit().putBoolean(RESTORE_OFFERED_KEY, true).apply()
            "welcomeSeen" -> prefs.edit().putBoolean(WELCOME_SEEN_KEY, true).apply()
            "goBack" -> { activity.goBack(); return }
            "goForward" -> { activity.goForward(); return }
            "backState" -> { activity.sendCanGoBack(); return }
            "airplay" -> { showOutputSwitcher(); return }
            "notifyPermission" -> { askNotificationsOnce(); return }
            "share" -> { share(body.optString("text"), body.optString("url")); return }
            "findSong" -> {
                val song = body.optString("song").trim()
                val artist = body.optString("artist").trim()
                if (song.isEmpty() && artist.isEmpty()) return
                when (body.optString("service")) {
                    "spotify" -> openInSpotify(song, artist)
                    "apple" -> openInAppleMusic(song, artist)
                }
                return
            }
            "export" -> { exportFavorites(); return }
            "import" -> { pick(Picker.IMPORT); return }
        }
        if (op in setOf("toggle", "remove", "played", "historyRemove", "historyClear")) scheduleAutoBackup()
        send()
    }

    // ---------------------------------------------------------------------------------
    // Sending the lists to the page
    // ---------------------------------------------------------------------------------

    fun send() {
        val due = if (store.backupDue) "true" else "false"
        val welcome = if (prefs.getBoolean(WELCOME_SEEN_KEY, false)) "false" else "true"
        js(
            "window.__soulectionFavorites && window.__soulectionFavorites.receive(${store.json()}, " +
                "{backupDue: $due, history: ${store.historyJSON()}, continueListening: ${store.continueJSON()}, " +
                "autoBackup: ${autoBackupStatusJSON()}, welcome: $welcome, build: ${quote(buildLabel())}, " +
                "newShowCheck: ${NewShows.lastCheckJSON(activity)}});"
        )
    }

    private fun buildLabel(): String {
        val code = BuildConfig.VERSION_CODE
        return "Version ${BuildConfig.VERSION_NAME}" + if (code > 1) " (build $code)" else ""
    }

    fun toast(text: String) {
        js("window.__soulectionFavorites && window.__soulectionFavorites.toast && window.__soulectionFavorites.toast(${quote(text)});")
    }

    // ---------------------------------------------------------------------------------
    // Backups
    //
    // On this phone, always: Downloads/Soulection/Soulection Radio Backup.json, rewritten
    // after every change. Optional second copy anywhere the user picks (Google Drive is
    // best): the app keeps permission to that file and rewrites it too, so it survives
    // deleting the app. After a reinstall, picking it brings the songs back.
    // ---------------------------------------------------------------------------------

    private val autoBackupOn get() = prefs.getString(FILE_KEY, null) != null
    private val backupUri get() = prefs.getString(FILE_KEY, null)?.let { Uri.parse(it) }
    private var backupTask: Runnable? = null

    private fun scheduleAutoBackup() {
        backupTask?.let { main.removeCallbacks(it) }
        backupTask = Runnable {
            backupTask = null
            writeLocalBackupInBackground()
            if (autoBackupOn) writeAutoBackupInBackground()
        }.also { main.postDelayed(it, 2000) }
    }

    private fun writeLocalBackupInBackground() {
        val payload = store.backupText()
        val ctx = activity.applicationContext
        Thread { writeLocal(ctx, payload) }.start()
    }

    private fun writeLocal(ctx: Context, payload: String) {
        val bytes = payload.toByteArray()
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = ctx.contentResolver
            prefs.getString(LOCAL_URI_KEY, null)?.let { saved ->
                try {
                    resolver.openOutputStream(Uri.parse(saved), "wt")?.use { it.write(bytes) }
                    return
                } catch (e: Exception) {
                    prefs.edit().remove(LOCAL_URI_KEY).apply()
                }
            }
            try {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, BACKUP_FILE_NAME)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/Soulection")
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
                resolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                prefs.edit().putString(LOCAL_URI_KEY, uri.toString()).apply()
            } catch (e: Exception) { }
        } else {
            try {
                val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
                File(dir, BACKUP_FILE_NAME).writeBytes(bytes)
            } catch (e: Exception) { }
        }
    }

    /** Writes the backup into the picked file. Returns why it failed, or null. */
    private fun writeTo(uri: Uri, payload: String): String? {
        val resolver = activity.contentResolver
        return try {
            val out = try { resolver.openOutputStream(uri, "wt") } catch (e: Exception) { resolver.openOutputStream(uri, "w") }
            out?.use { it.write(payload.toByteArray()) } ?: return "The file couldn't be opened"
            null
        } catch (e: Exception) {
            e.localizedMessage ?: e.javaClass.simpleName
        }
    }

    private fun finishWrite(failure: String?): Boolean {
        lastWriteFailure = failure
        autoBackupError = failure != null
        if (failure == null) {
            prefs.edit().putLong(LAST_SAVED_KEY, System.currentTimeMillis()).apply()
            store.addedSinceBackup = 0
        }
        return failure == null
    }

    private fun writeAutoBackup(): Boolean {
        writeLocalBackupInBackground()
        val uri = backupUri ?: return finishWrite("The app lost track of the backup file")
        return finishWrite(writeTo(uri, store.backupText()))
    }

    private fun writeAutoBackupInBackground() {
        val uri = backupUri ?: return
        val payload = store.backupText()
        Thread {
            val failure = writeTo(uri, payload)
            main.post {
                val wasError = autoBackupError
                finishWrite(failure)
                if (wasError != autoBackupError) send()
            }
        }.start()
    }

    private fun fileName(uri: Uri): String? = try {
        activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    } catch (e: Exception) { null }

    /** Where the picked file is, in words. */
    private fun folderName(uri: Uri): String {
        val auth = uri.authority.orEmpty()
        return when {
            "google.android.apps.docs" in auth -> "Google Drive"
            "downloads" in auth -> "Downloads"
            "externalstorage" in auth -> {
                val id = try { DocumentsContract.getDocumentId(uri) } catch (e: Exception) { "" }
                id.substringAfter(":").substringBeforeLast("/", "").substringAfterLast("/").ifEmpty { "your phone" }
            }
            "dropbox" in auth -> "Dropbox"
            "onedrive" in auth || "skydrive" in auth -> "OneDrive"
            else -> "your folder"
        }
    }

    private fun hasPermission(uri: Uri): Boolean =
        activity.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }

    private fun autoBackupStatusJSON(): String {
        val uri = backupUri
        val on = uri != null
        val lost = uri != null && !hasPermission(uri)
        val reconnect = on && (lost || autoBackupError)
        val status = JSONObject().put("on", on).put("error", autoBackupError).put("reconnect", reconnect)
        if (uri != null) {
            status.put("folder", folderName(uri))
            status.put("file", prefs.getString("Backup.fileName", null) ?: BACKUP_FILE_NAME)
        }
        if (reconnect) (if (lost) "The app no longer has access to that file" else lastWriteFailure)?.let { status.put("why", it) }
        val last = prefs.getLong(LAST_SAVED_KEY, 0)
        if (last > 0) status.put("lastSaved", last)
        status.put("restoreOffer", store.isEmpty && !prefs.getBoolean(RESTORE_OFFERED_KEY, false))
        return status.toString()
    }

    private fun takePermission(uri: Uri): Boolean = try {
        activity.contentResolver.takePersistableUriPermission(
            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        true
    } catch (e: Exception) { false }

    private fun rememberBackupFile(uri: Uri): Boolean {
        val ok = takePermission(uri)
        prefs.edit()
            .putString(FILE_KEY, uri.toString())
            .putString("Backup.fileName", fileName(uri) ?: BACKUP_FILE_NAME)
            .putBoolean(RESTORE_OFFERED_KEY, true)
            .apply()
        return ok
    }

    private fun saveBackupToFiles() {
        pickerPurpose = Picker.SAVE_BACKUP
        activity.createDocument(BACKUP_FILE_NAME)
    }

    private fun exportFavorites() {
        if (store.favorites.isEmpty() && store.history.isEmpty()) {
            toast("Nothing to export yet.")
            return
        }
        pickerPurpose = Picker.EXPORT
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        activity.createDocument("Soulection Favorites $day.json")
    }

    private fun pick(purpose: Picker) {
        pickerPurpose = purpose
        activity.openDocument()
    }

    private fun readText(uri: Uri): String? = try {
        activity.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
    } catch (e: Exception) { null }

    /** The save screen closed: [uri] is null when cancelled. */
    fun onCreated(uri: Uri?) {
        if (uri == null) {
            when (pickerPurpose) {
                Picker.SAVE_BACKUP -> toast(if (autoBackupOn) "Backup is still paused. Tap Reconnect in Likes when you're ready." else "Auto-backup wasn't turned on.")
                else -> {}
            }
            return
        }
        when (pickerPurpose) {
            Picker.EXPORT -> {
                val failure = writeTo(uri, store.backupText())
                if (failure == null) {
                    store.addedSinceBackup = 0
                    toast("Your Likes and History are backed up.")
                } else {
                    toast("Couldn't create the backup file.")
                }
                send()
            }
            else -> {
                rememberBackupFile(uri)
                val ok = writeAutoBackup()
                send()
                toast(if (ok) "Auto-backup on: saving to ${folderName(uri)}." else "Auto-backup couldn't save to that file. (" + (lastWriteFailure ?: "No reason given") + ")")
            }
        }
    }

    /** The open screen closed: [uri] is null when cancelled. */
    fun onOpened(uri: Uri?) {
        if (uri == null) {
            when (pickerPurpose) {
                Picker.RESTORE_BACKUP -> toast("Nothing restored.")
                Picker.RECONNECT -> toast("Backup is still paused. Tap Reconnect in Likes when you're ready.")
                else -> {}
            }
            return
        }
        val incoming = readText(uri)?.let { FavoritesStore.readBackup(it) }
        if (incoming == null) {
            toast(
                if (pickerPurpose == Picker.IMPORT) "That file isn't a Soulection Radio backup."
                else "That file isn't a Soulection Radio backup, or it couldn't be opened. If it's in Google Drive, wait a moment for it to download and try again."
            )
            return
        }
        val added = store.merge(incoming.favorites)
        val addedHistory = if (incoming.history.isEmpty()) 0 else store.mergeHistory(incoming.history)
        if (incoming.continueListening.isNotEmpty()) store.mergeContinue(incoming.continueListening)
        prefs.edit().putBoolean(RESTORE_OFFERED_KEY, true).apply()

        if (pickerPurpose == Picker.IMPORT) {
            scheduleAutoBackup()
            send()
            val parts = mutableListOf<String>()
            if (added > 0) parts.add(if (added == 1) "1 favorite" else "$added favorites")
            if (addedHistory > 0) parts.add(if (addedHistory == 1) "1 history song" else "$addedHistory history songs")
            toast(if (parts.isEmpty()) "Everything in that backup is already saved." else "Imported " + parts.joinToString(" and ") + ".")
            return
        }

        // Restore / reconnect: keep backing up to this same file.
        val restored = added + addedHistory
        val reconnecting = pickerPurpose == Picker.RECONNECT
        if (rememberBackupFile(uri) && writeAutoBackup()) {
            send()
            val n = if (restored == 1) "1 song" else "$restored songs"
            toast(
                if (reconnecting) (if (restored > 0) "Backup is back on. $n from the file added." else "Backup is back on.")
                else (if (restored > 0) "Restored $n. Backup is on." else "Your songs are all here. Backup is on.")
            )
            return
        }
        // That file can't be written to: save a new one instead.
        val why = lastWriteFailure
        prefs.edit().remove(FILE_KEY).apply()
        autoBackupError = false
        send()
        js("window.__soulectionFavorites && window.__soulectionFavorites.pickSavedFile && window.__soulectionFavorites.pickSavedFile($restored, ${quote(why ?: "")});")
        main.postDelayed({ saveBackupToFiles() }, 1500)
    }

    // ---------------------------------------------------------------------------------
    // Sleep timer and blends. The players' own volume is lowered (not the phone's), so
    // nothing pops up on screen and your volume setting is left alone.
    // ---------------------------------------------------------------------------------

    private var sleepRunnable: Runnable? = null
    private var fadeRunnable: Runnable? = null
    private var fading = false
    private var blendRunnable: Runnable? = null
    private var blending = false

    private fun startSleepTimer(seconds: Double) {
        sleepRunnable?.let { main.removeCallbacks(it) }
        cancelFade(restore = true)
        val fade = minOf(FADE_LENGTH, seconds)
        val r = Runnable {
            sleepRunnable = null
            startFade(fade) { sleepTimerFired() }
        }
        sleepRunnable = r
        main.postDelayed(r, (maxOf(0.05, seconds - fade) * 1000).toLong())
    }

    private fun startFade(seconds: Double, done: (() -> Unit)?) {
        cancelFade(restore = false)
        cancelBlend()
        fading = true
        val began = SystemClock.elapsedRealtime()
        val start = MediaHub.volume
        val r = object : Runnable {
            override fun run() {
                val progress = minOf(1.0, (SystemClock.elapsedRealtime() - began) / 1000.0 / maxOf(seconds, 0.1))
                val level = 1 - progress
                MediaHub.setVolume(start * level * level)
                if (progress >= 1) {
                    fadeRunnable = null
                    if (done != null) done() else pauseAndRestoreVolume()
                    return
                }
                main.postDelayed(this, 100)
            }
        }
        fadeRunnable = r
        main.postDelayed(r, 100)
    }

    private fun cancelFade(restore: Boolean) {
        fadeRunnable?.let { main.removeCallbacks(it) }
        fadeRunnable = null
        if (restore && fading) MediaHub.setVolume(1.0)
        fading = false
    }

    private fun pauseAndRestoreVolume() {
        fadeRunnable?.let { main.removeCallbacks(it) }
        fadeRunnable = null
        MediaHub.pauseAll()
        main.postDelayed({
            fading = false
            MediaHub.setVolume(1.0)
        }, 1000)
    }

    private fun sleepTimerFired() {
        pauseAndRestoreVolume()
        js("window.__soulectionFavorites && window.__soulectionFavorites.sleepFired && window.__soulectionFavorites.sleepFired();")
    }

    private fun blendOut(seconds: Double) {
        if (fading || blending) return
        blending = true
        runBlend(MediaHub.volume, BLEND_FLOOR, seconds) {
            // Low until the new song plays (the page says so), but not forever.
            val r = Runnable { blendRunnable = null; blendIn() }
            blendRunnable = r
            main.postDelayed(r, (BLEND_HOLD_LIMIT * 1000).toLong())
        }
    }

    private fun blendIn() {
        if (!blending) return
        runBlend(MediaHub.volume, 1.0, BLEND_IN_LENGTH) { blending = false }
    }

    private fun cancelBlend() {
        blendRunnable?.let { main.removeCallbacks(it) }
        blendRunnable = null
        blending = false
    }

    private fun runBlend(from: Double, to: Double, seconds: Double, done: () -> Unit) {
        blendRunnable?.let { main.removeCallbacks(it) }
        val began = SystemClock.elapsedRealtime()
        val r = object : Runnable {
            override fun run() {
                if (!blending) return
                val p = minOf(1.0, (SystemClock.elapsedRealtime() - began) / 1000.0 / maxOf(seconds, 0.1))
                val eased = p * p * (3 - 2 * p)
                MediaHub.setVolume(from + (to - from) * eased)
                if (p >= 1) {
                    blendRunnable = null
                    done()
                    return
                }
                main.postDelayed(this, 100)
            }
        }
        blendRunnable = r
        main.postDelayed(r, 100)
    }

    // ---------------------------------------------------------------------------------
    // Speaker (AirPlay on iPhone): Android's output switcher, or Bluetooth settings.
    // ---------------------------------------------------------------------------------

    private fun showOutputSwitcher() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                val intent = Intent("com.android.systemui.action.LAUNCH_MEDIA_OUTPUT_DIALOG")
                    .setPackage("com.android.systemui")
                    .putExtra("package_name", activity.packageName)
                activity.sendBroadcast(intent)
                return
            } catch (e: Exception) { }
        }
        try {
            activity.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        } catch (e: Exception) {
            toast("Couldn't open the speaker list.")
        }
    }

    // ---------------------------------------------------------------------------------
    // New-show notifications
    // ---------------------------------------------------------------------------------

    private fun askNotificationsOnce() {
        NewShows.schedule(activity)
        if (Build.VERSION.SDK_INT < 33) return
        if (prefs.getBoolean("NewShows.asked", false)) return
        prefs.edit().putBoolean("NewShows.asked", true).apply()
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            activity.askNotificationPermission()
        }
    }

    // ---------------------------------------------------------------------------------
    // Share, Spotify, Apple Music
    // ---------------------------------------------------------------------------------

    private fun share(text: String, link: String) {
        var body = text
        val uri = Uri.parse(link)
        val host = uri.host.orEmpty()
        if (uri.scheme == "https" && (host == "soulection.com" || host.endsWith(".soulection.com"))) {
            body = if (body.isEmpty()) link else "$body\n$link"
        }
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, body)
        activity.startActivity(Intent.createChooser(send, null))
    }

    private fun encoded(text: String) = URLEncoder.encode(text, "UTF-8").replace("+", "%20")

    private fun openUrl(url: String): Boolean = try {
        activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        true
    } catch (e: ActivityNotFoundException) { false }

    private fun openInSpotify(song: String, artist: String) {
        val query = encoded(listOf(song, artist).filter { it.isNotEmpty() }.joinToString(" "))
        if (!openUrl("spotify:search:$query")) openUrl("https://open.spotify.com/search/$query")
    }

    /** Looks the song up in Apple's public catalog and opens that exact song when both the
     *  title and the artist really match; otherwise Apple Music's search for it. */
    private fun openInAppleMusic(song: String, artist: String) {
        val terms = listOf(song, artist).filter { it.isNotEmpty() }.joinToString(" ")
        val country = (Locale.getDefault().country.ifEmpty { "US" }).lowercase(Locale.US)
        val search = "https://music.apple.com/$country/search?term=" + encoded(terms)
        val lookup = "https://itunes.apple.com/search?media=music&entity=song&limit=25&country=$country&term=" + encoded(terms)
        Thread {
            var exact: String? = null
            try {
                val conn = URL(lookup).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val results = json.optJSONArray("results") ?: JSONArray()
                for (i in 0 until results.length()) {
                    val r = results.optJSONObject(i) ?: continue
                    if (sameSong(r.optString("trackName"), song) && sameArtist(r.optString("artistName"), artist)) {
                        val u = Uri.parse(r.optString("trackViewUrl"))
                        if (u.scheme == "https" && u.host.orEmpty().endsWith("apple.com")) exact = u.toString()
                        break
                    }
                }
            } catch (e: Exception) { }
            main.post { openUrl(exact ?: search) }
        }.start()
    }

    private fun plain(text: String): String {
        val folded = Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase(Locale.ROOT)
        return folded.map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("")
            .split(' ').filter { it.isNotEmpty() }.joinToString(" ")
    }

    private fun coreTitle(text: String): String {
        var t = text.replace(Regex("\\s*[(\\[][^)\\]]*[)\\]]"), "")
        t = t.replace(Regex("(?i)\\s+(feat\\.?|ft\\.?|featuring)\\s+.*$"), "")
        return plain(t)
    }

    private fun sameSong(found: String, wanted: String): Boolean {
        val a = coreTitle(found)
        val b = coreTitle(wanted)
        if (a.isEmpty() || b.isEmpty()) return false
        return a == b || a.startsWith("$b ") || b.startsWith("$a ")
    }

    private fun sameArtist(found: String, wanted: String): Boolean {
        val have = " " + plain(found) + " "
        val names = wanted.split(Regex("[,&+/]"))
            .flatMap { it.split(" x ", " X ") }
            .map { plain(it.replace(Regex("(?i)\\b(feat\\.?|ft\\.?|featuring|and|with)\\b"), " ")) }
            .filter { it.length >= 2 }
        return names.any { have.contains(" $it ") }
    }
}
