package com.aziz.tube

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

// Small settings and lists kept on the phone: language, history, where each video was left, the user's channels.
object Store {
    private lateinit var prefs: SharedPreferences
    lateinit var dir: File

    fun init(c: Context) {
        prefs = c.getSharedPreferences("aziz", Context.MODE_PRIVATE)
        dir = c.filesDir
    }

    // "auto" (by the phone's language), "ar" or "en". Decided at start.
    var language: String
        get() = prefs.getString("lang", "auto") ?: "auto"
        set(v) = prefs.edit().putString("lang", v).apply()

    val en: Boolean by lazy {
        when (language) {
            "ar" -> false
            "en" -> true
            else -> Locale.getDefault().language != "ar"
        }
    }

    var skipSilence: Boolean
        get() = prefs.getBoolean("silence", false)
        set(v) = prefs.edit().putBoolean("silence", v).apply()

    var autoNext: Boolean
        get() = prefs.getBoolean("autonext", true)
        set(v) = prefs.edit().putBoolean("autonext", v).apply()

    // The most a phone screen shows; "auto" follows the screen.
    var maxHeight: Int
        get() = prefs.getInt("maxh", 0)
        set(v) = prefs.edit().putInt("maxh", v).apply()

    fun resume(id: String): Long = prefs.getLong("at:$id", 0)

    fun setResume(id: String, ms: Long, durationMs: Long) {
        val e = prefs.edit()
        if (durationMs < 5 * 60_000 || ms < 60_000 || ms > durationMs - 60_000) e.remove("at:$id") else e.putLong("at:$id", ms)
        if (durationMs > 0 && ms >= durationMs * 0.9) {
            val w = watched.toMutableSet()
            w.add(id)
            prefs.edit().putStringSet("watched", w.toList().takeLast(3000).toSet()).apply()
        }
        e.apply()
    }

    val watched: Set<String> get() = prefs.getStringSet("watched", emptySet()) ?: emptySet()

    // Speed kept per channel (as on Windows).
    fun speed(channel: String?): Float = if (channel == null) 1f else prefs.getFloat("speed:$channel", 1f)

    fun setSpeed(channel: String?, v: Float) {
        if (channel != null) prefs.edit().putFloat("speed:$channel", v).apply()
    }

    fun readFile(name: String): String? = try {
        File(dir, name).takeIf { it.exists() }?.readText()
    } catch (e: Exception) {
        null
    }

    fun writeFile(name: String, text: String) {
        try {
            File(dir, name).writeText(text)
        } catch (_: Exception) {
        }
    }

    // History: the last 200 videos opened, newest first.
    fun history(): List<Item> = try {
        val a = JSONArray(readFile("history.json") ?: "[]")
        (0 until a.length()).map { Item.from(a.getJSONObject(it)) }
    } catch (e: Exception) {
        emptyList()
    }

    fun addHistory(i: Item) {
        val list = (listOf(i) + history().filter { it.id != i.id }).take(200)
        writeFile("history.json", JSONArray(list.map { it.json() }).toString())
    }

    // Channels the user added to the library (name, link).
    fun myChannels(): List<Pair<String, String>> = try {
        val a = JSONArray(readFile("my-channels.json") ?: "[]")
        (0 until a.length()).map { a.getJSONObject(it).let { o -> o.getString("name") to o.getString("url") } }
    } catch (e: Exception) {
        emptyList()
    }

    fun setMyChannels(l: List<Pair<String, String>>) {
        writeFile("my-channels.json", JSONArray(l.map { JSONObject().put("name", it.first).put("url", it.second) }).toString())
    }
}

fun tr(ar: String, en: String) = if (Store.en) en else ar
