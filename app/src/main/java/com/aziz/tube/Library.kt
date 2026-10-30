package com.aziz.tube

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

// The library (the app's channels by section, and the user's own) and the home page made of their newest videos.
object Library {
    data class Channel(val name: String, val url: String?, val query: String?, val section: String)

    fun channels(): List<Channel> {
        val out = mutableListOf<Channel>()
        try {
            val root = JSONObject(App.instance.assets.open("library.json").bufferedReader().readText())
            for (sec in root.keys()) {
                val a = root.getJSONArray(sec)
                for (n in 0 until a.length()) {
                    val o = a.getJSONObject(n)
                    out.add(Channel(o.getString("name"), o.optString("url").ifEmpty { null }, o.optString("query").ifEmpty { null }, sec))
                }
            }
        } catch (_: Exception) {
        }
        for ((name, url) in Store.myChannels()) out.add(Channel(name, url, null, "اشتراكاتي"))
        return out
    }

    fun section(name: String) = when (name) {
        "قصص" -> tr("قصص", "Stories")
        "شيلات" -> tr("شيلات", "Nasheeds")
        "البودكاست" -> tr("البودكاست", "Podcasts")
        "اشتراكاتي" -> tr("اشتراكاتي", "My channels")
        else -> name
    }

    fun has(name: String?, url: String?) = channels().any { it.name == name || (url != null && it.url == url) }

    fun add(name: String, url: String) {
        if (!has(name, url)) Store.setMyChannels(Store.myChannels() + (name to url))
    }

    fun remove(name: String) = Store.setMyChannels(Store.myChannels().filter { it.first != name })

    // Each channel's id, found once and kept.
    private val ids: MutableMap<String, String> by lazy {
        try {
            val o = JSONObject(Store.readFile("ids.json") ?: "{}")
            o.keys().asSequence().associateWith { o.getString(it) }.toMutableMap()
        } catch (e: Exception) {
            mutableMapOf()
        }
    }

    private fun idOf(c: Channel): String? {
        synchronized(ids) { ids[c.name] }?.let { return it }
        val id = c.url?.let { Tube.channelId(it) } ?: c.query?.let { q -> Tube.search(q, channels = true)?.firstOrNull { it.isChannel }?.id }
        if (id != null) synchronized(ids) {
            ids[c.name] = id
            Store.writeFile("ids.json", JSONObject(ids as Map<*, *>).toString())
        }
        return id
    }

    fun channelId(name: String, url: String?) = idOf(Channel(name, url, null, ""))

    // The home page from the copy on the phone (shown at once), and its age.
    fun cached(): Pair<List<Item>, Long> = try {
        val o = JSONObject(Store.readFile("home.json") ?: "{}")
        val a = o.optJSONArray("items") ?: JSONArray()
        (0 until a.length()).map { Item.from(a.getJSONObject(it)) } to o.optLong("at")
    } catch (e: Exception) {
        emptyList<Item>() to 0L
    }

    // Every channel's newest videos (6 read at a time), laid out in turns: first the newest of every channel (newest
    // first), then the second of every channel, and so on, so a channel that posts a lot never fills the page.
    suspend fun refresh(): List<Item> = withContext(Dispatchers.IO) {
        val gate = Semaphore(6)
        val per = coroutineScope {
            channels().map { c -> async { gate.withPermit { idOf(c)?.let { Tube.feed(it) } ?: emptyList() } } }.awaitAll()
        }.filter { it.isNotEmpty() }.map { l -> l.sortedByDescending { it.published } }
        val order = per.sortedByDescending { it.first().published }
        val out = mutableListOf<Item>()
        val seen = HashSet<String>()
        val rounds = order.maxOfOrNull { it.size } ?: 0
        for (r in 0 until rounds) for (l in order) if (l.size > r && seen.add(l[r].id)) out.add(l[r])
        val list = out.take(120)
        if (list.isNotEmpty()) Store.writeFile("home.json", JSONObject().put("at", System.currentTimeMillis()).put("items", JSONArray(list.map { it.json() })).toString())
        list
    }
}
