package com.hotwheelz3000.soulection

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Likes, History and Continue listening, kept in the app (not the website's storage, so
 * clearing site data doesn't lose them). Same data and backup file format as the iPhone app,
 * so a backup from one restores on the other.
 *
 * A song: {id, song, artist, show, episodeURL, cover, start, end?, favoritedAt}.
 * A Continue listening entry: {path, show, cover, t, d, at}.
 */
class FavoritesStore(context: Context) {

    companion object {
        const val CONTINUE_LIMIT = 6
        const val HISTORY_LIMIT = 500
        const val BACKUP_REMINDER_EVERY = 4

        private const val KEY = "Favorites.v1"
        private const val HISTORY_KEY = "History.v1"
        private const val CONTINUE_KEY = "Continue.v1"
        private const val SINCE_BACKUP_KEY = "Favorites.addedSinceBackup"

        private fun now() = System.currentTimeMillis().toDouble()

        /** Lenient: only `id` is needed; anything missing gets a default. */
        fun song(raw: Any?): JSONObject? {
            val o = raw as? JSONObject ?: return null
            val id = o.optString("id").ifEmpty { return null }
            val out = JSONObject()
                .put("id", id)
                .put("song", o.optString("song"))
                .put("artist", o.optString("artist"))
                .put("show", o.optString("show"))
                .put("episodeURL", o.optString("episodeURL"))
                .put("cover", o.optString("cover"))
                .put("start", o.optDouble("start", 0.0).let { if (it.isNaN()) 0.0 else it })
                .put("favoritedAt", o.optDouble("favoritedAt", now()).let { if (it.isNaN()) now() else it })
            val end = o.optDouble("end", Double.NaN)
            if (!end.isNaN()) out.put("end", end)
            return out
        }

        fun continueEntry(raw: Any?): JSONObject? {
            val o = raw as? JSONObject ?: return null
            val path = o.optString("path").ifEmpty { return null }
            fun num(k: String) = o.optDouble(k, 0.0).let { if (it.isNaN()) 0.0 else it }
            return JSONObject()
                .put("path", path)
                .put("show", o.optString("show"))
                .put("cover", o.optString("cover"))
                .put("t", num("t"))
                .put("d", num("d"))
                .put("at", num("at"))
        }

        fun songs(arr: JSONArray?): List<JSONObject> =
            if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { song(arr.opt(it)) }

        fun continues(arr: JSONArray?): List<JSONObject> =
            if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { continueEntry(arr.opt(it)) }

        data class Backup(
            val favorites: List<JSONObject>,
            val history: List<JSONObject>,
            val continueListening: List<JSONObject>,
        )

        /** A backup file: the app's format, or a plain list of songs. */
        fun readBackup(text: String): Backup? {
            val t = text.trim()
            return try {
                if (t.startsWith("[")) {
                    Backup(songs(JSONArray(t)), emptyList(), emptyList())
                } else {
                    val o = JSONObject(t)
                    if (!o.has("favorites")) return null
                    Backup(songs(o.optJSONArray("favorites")), songs(o.optJSONArray("history")), continues(o.optJSONArray("continueListening")))
                }
            } catch (e: Exception) { null }
        }
    }

    private val prefs = context.getSharedPreferences("store", Context.MODE_PRIVATE)

    var favorites: MutableList<JSONObject> = load(KEY).let { songs(it).toMutableList() }
        private set
    /** Oldest first; favoritedAt = when it was played. */
    var history: MutableList<JSONObject> = load(HISTORY_KEY).let { songs(it).toMutableList() }
        private set
    /** Newest first. */
    var continueList: MutableList<JSONObject> = load(CONTINUE_KEY).let { continues(it).toMutableList() }
        private set

    private fun load(key: String): JSONArray? =
        prefs.getString(key, null)?.let { try { JSONArray(it) } catch (e: Exception) { null } }

    private fun save(key: String, list: List<JSONObject>) {
        prefs.edit().putString(key, JSONArray(list).toString()).apply()
    }

    var addedSinceBackup: Int
        get() = prefs.getInt(SINCE_BACKUP_KEY, 0)
        set(v) = prefs.edit().putInt(SINCE_BACKUP_KEY, v).apply()

    val backupDue: Boolean get() = favorites.isNotEmpty() && addedSinceBackup >= BACKUP_REMINDER_EVERY
    val isEmpty: Boolean get() = favorites.isEmpty() && history.isEmpty()

    fun json() = JSONArray(favorites).toString()
    fun historyJSON() = JSONArray(history).toString()
    fun continueJSON() = JSONArray(continueList).toString()

    fun toggle(f: JSONObject) {
        val id = f.optString("id")
        val i = favorites.indexOfFirst { it.optString("id") == id }
        if (i >= 0) {
            favorites.removeAt(i)
        } else {
            favorites.add(JSONObject(f.toString()).put("favoritedAt", now()))
            addedSinceBackup += 1
        }
        save(KEY, favorites)
    }

    fun remove(id: String) {
        favorites.removeAll { it.optString("id") == id }
        save(KEY, favorites)
    }

    fun merge(incoming: List<JSONObject>): Int {
        val known = favorites.map { it.optString("id") }.toMutableSet()
        var added = 0
        for (f in incoming) {
            if (known.add(f.optString("id"))) {
                favorites.add(f)
                added++
            }
        }
        if (added > 0) save(KEY, favorites)
        return added
    }

    fun recordPlay(entry: JSONObject) {
        val played = JSONObject(entry.toString()).put("favoritedAt", now())
        val id = played.optString("id")
        history.removeAll { it.optString("id") == id }
        history.add(played)
        while (history.size > HISTORY_LIMIT) history.removeAt(0)
        save(HISTORY_KEY, history)
    }

    fun removeFromHistory(id: String) {
        history.removeAll { it.optString("id") == id }
        save(HISTORY_KEY, history)
    }

    fun clearHistory() {
        history.clear()
        save(HISTORY_KEY, history)
    }

    /** Keeps the latest play of each song. Returns how many songs were new. */
    fun mergeHistory(incoming: List<JSONObject>): Int {
        val byId = LinkedHashMap<String, JSONObject>()
        for (h in history) byId[h.optString("id")] = h
        var added = 0
        for (e in incoming) {
            val id = e.optString("id")
            val existing = byId[id]
            if (existing == null) {
                byId[id] = e
                added++
            } else if (e.optDouble("favoritedAt", 0.0) > existing.optDouble("favoritedAt", 0.0)) {
                byId[id] = e
            }
        }
        history = byId.values.sortedBy { it.optDouble("favoritedAt", 0.0) }.toMutableList()
        while (history.size > HISTORY_LIMIT) history.removeAt(0)
        save(HISTORY_KEY, history)
        return added
    }

    /** Returns whether it changed. */
    fun setContinue(list: List<JSONObject>): Boolean {
        val trimmed = list.take(CONTINUE_LIMIT)
        if (JSONArray(trimmed).toString() == JSONArray(continueList).toString()) return false
        continueList = trimmed.toMutableList()
        save(CONTINUE_KEY, continueList)
        return true
    }

    /** For a show in both, the more recent spot wins. */
    fun mergeContinue(incoming: List<JSONObject>) {
        val byPath = HashMap<String, JSONObject>()
        for (e in continueList + incoming) {
            val p = e.optString("path")
            val existing = byPath[p]
            if (existing != null && existing.optDouble("at", 0.0) >= e.optDouble("at", 0.0)) continue
            byPath[p] = e
        }
        continueList = byPath.values.sortedByDescending { it.optDouble("at", 0.0) }.take(CONTINUE_LIMIT).toMutableList()
        save(CONTINUE_KEY, continueList)
    }

    /** The backup file's contents (same as the iPhone app's). */
    fun backupText(): String {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return JSONObject()
            .put("app", "SoulectionRadio")
            .put("version", 1)
            .put("exportedAt", iso.format(Date()))
            .put("favorites", JSONArray(favorites))
            .put("history", JSONArray(history))
            .put("continueListening", JSONArray(continueList))
            .toString(2)
    }
}
