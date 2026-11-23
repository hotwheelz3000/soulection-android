package com.hotwheelz3000.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// Checks the repo's latest release for a newer build on launch.
// Fails silently: no internet or no release just means no prompt.
object UpdateChecker {

    fun check(activity: Activity) {
        if (BuildConfig.UPDATE_URL.isEmpty()) return
        Thread {
            try {
                val conn = URL(BuildConfig.UPDATE_URL).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.instanceFollowRedirects = true
                if (conn.responseCode != 200) return@Thread
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                conn.disconnect()

                val latest = json.optInt("versionCode", 0)
                val name = json.optString("versionName", "")
                val url = json.optString("url", "")
                if (latest <= BuildConfig.VERSION_CODE || url.isEmpty()) return@Thread

                activity.runOnUiThread {
                    if (activity.isFinishing) return@runOnUiThread
                    AlertDialog.Builder(activity)
                        .setTitle("Update available")
                        .setMessage("Version $name (build $latest) is ready.")
                        .setPositiveButton("Download") { _, _ ->
                            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        }
                        .setNegativeButton("Later", null)
                        .show()
                }
            } catch (e: Exception) {
                // ignore
            }
        }.start()
    }
}
