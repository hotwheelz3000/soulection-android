package com.hotwheelz3000.soulection

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Notifications when a new show is posted, without a server: the app reads the site's home
 * page about every 15 minutes (Android decides exactly when, also with the app closed) and
 * when you open or leave the app, and notifies when the newest show number goes up.
 * Tapping the notification opens that show.
 */
object NewShows {
    private const val WORK = "newshow"
    private const val CHANNEL = "newshows"
    private const val PREFS = "newshows"
    private const val LAST_NUMBER = "lastNumber"
    private const val LAST_CHECK = "lastCheck"
    const val EXTRA_PATH = "episodePath"

    data class Episode(val number: Int, val path: String)

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<CheckWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf("how" to "background"))
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** A check now (opening or leaving the app). */
    fun checkSoon(context: Context, how: String) {
        val request = OneTimeWorkRequestBuilder<CheckWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf("how" to how))
            .build()
        WorkManager.getInstance(context).enqueue(request)
    }

    class CheckWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
        override fun doWork(): Result {
            check(applicationContext, inputData.getString("how") ?: "background")
            return Result.success()
        }
    }

    /** The first check only remembers the newest show (no alert for shows already out). */
    @Synchronized
    fun check(context: Context, how: String) {
        val html = try {
            val conn = URL("https://radio.soulection.com/").openConnection() as HttpURLConnection
            conn.connectTimeout = 20000
            conn.readTimeout = 20000
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) { return }
        val newest = newest(html) ?: return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = prefs.getInt(LAST_NUMBER, 0)
        prefs.edit()
            .putInt(LAST_NUMBER, maxOf(last, newest.number))
            .putString(LAST_CHECK, JSONObject().put("at", System.currentTimeMillis()).put("how", how).put("number", newest.number).toString())
            .apply()
        if (last > 0 && newest.number > last) notify(context, newest)
    }

    /** The newest show on the home page; its episode list is JSON escaped inside a script. */
    fun newest(html: String): Episode? {
        val text = html.replace("\\\"", "\"")
        val regex = Regex("\"id\":\"([0-9a-fA-F-]{36})\"[^{}]*?\"title\":\"Soulection Radio #(\\d+)\"")
        var best: Episode? = null
        for (m in regex.findAll(text)) {
            val n = m.groupValues[2].toIntOrNull() ?: continue
            if (n > (best?.number ?: 0)) best = Episode(n, "/episodes/" + m.groupValues[1])
        }
        return best
    }

    private fun notify(context: Context, episode: Episode) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "New shows", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val open = MediaHub.openApp(context).putExtra(EXTRA_PATH, episode.path)
        val tap = PendingIntent.getActivity(
            context, episode.number, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_music)
            .setContentTitle("New show: Soulection Radio #${episode.number}")
            .setContentText("Tap to listen.")
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(1000 + episode.number, n)
        } catch (e: SecurityException) { }
    }

    /** The last check that reached the site, for Help: {"at": ms, "how": ..., "number": ...}. */
    fun lastCheckJSON(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(LAST_CHECK, null) ?: "null"
}
