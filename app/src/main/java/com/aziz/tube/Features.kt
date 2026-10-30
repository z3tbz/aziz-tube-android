package com.aziz.tube

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

// Small JSON stores on the phone.
object Json {
    fun list(name: String): MutableList<Item> = try {
        val a = JSONArray(Store.readFile(name) ?: "[]")
        (0 until a.length()).map { Item.from(a.getJSONObject(it)) }.toMutableList()
    } catch (e: Exception) { mutableListOf() }

    fun saveList(name: String, l: List<Item>) = Store.writeFile(name, JSONArray(l.map { it.json() }).toString())

    fun obj(name: String): JSONObject = try { JSONObject(Store.readFile(name) ?: "{}") } catch (e: Exception) { JSONObject() }

    fun strings(name: String): MutableSet<String> = try {
        val a = JSONArray(Store.readFile(name) ?: "[]"); (0 until a.length()).map { a.getString(it) }.toMutableSet()
    } catch (e: Exception) { mutableSetOf() }

    fun saveStrings(name: String, s: Collection<String>) = Store.writeFile(name, JSONArray(s.toList()).toString())
}

// Lists: "watch later", the user's own lists, and the queue (what plays next).
object Lists {
    const val LATER = "شاهد لاحقًا"
    fun name(n: String) = if (n == LATER) tr(LATER, "Watch later") else n

    fun all(): MutableMap<String, MutableList<Item>> {
        val o = Json.obj("lists.json")
        val m = linkedMapOf<String, MutableList<Item>>(LATER to mutableListOf())
        for (k in o.keys()) {
            val a = o.getJSONArray(k)
            m[k] = (0 until a.length()).map { Item.from(a.getJSONObject(it)) }.toMutableList()
        }
        return m
    }

    private fun save(m: Map<String, List<Item>>) = Store.writeFile("lists.json", JSONObject().apply { m.forEach { (k, v) -> put(k, JSONArray(v.map { it.json() })) } }.toString())

    fun add(list: String, i: Item) { val m = all(); val l = m.getOrPut(list) { mutableListOf() }; l.removeAll { it.id == i.id }; l.add(i); save(m) }
    fun remove(list: String, id: String) { val m = all(); m[list]?.removeAll { it.id == id }; save(m) }
    fun delete(list: String) { val m = all(); m.remove(list); save(m) }
}

// What the user does not want: channels blocked, videos "not interested", and a length filter.
object Hide {
    val blocked: MutableSet<String> get() = Json.strings("blocked.json")
    val notInterested: MutableSet<String> get() = Json.strings("not-interested.json")
    fun block(ch: String) { val s = blocked; s.add(ch); Json.saveStrings("blocked.json", s) }
    fun unblock(ch: String) { val s = blocked; s.remove(ch); Json.saveStrings("blocked.json", s) }
    fun notInterested(id: String) { val s = notInterested; s.add(id); Json.saveStrings("not-interested.json", s) }

    // 0 all, 1 hide under a minute, 2 only 20 minutes and longer.
    var lengthFilter: Int
        get() = Store.readFile("length.txt")?.toIntOrNull() ?: 0
        set(v) = Store.writeFile("length.txt", v.toString())

    fun seconds(clock: String?): Int = clock?.split(":")?.fold(0) { t, p -> t * 60 + (p.toIntOrNull() ?: return 0) } ?: 0

    fun allowed(items: List<Item>): List<Item> {
        val b = blocked
        val n = notInterested
        val f = lengthFilter
        return items.filter { i ->
            if (i.isChannel) return@filter true
            if (i.channel != null && i.channel in b) return@filter false
            if (i.id in n) return@filter false
            val s = seconds(i.duration)
            !(f == 1 && s in 1..59) && !(f == 2 && s in 1..1199)
        }
    }
}

// Series: videos whose titles differ only by a part number ("الجزء 2", "ج3", "الحلقة 4", "Part 5", "الجزء الثاني").
object Series {
    private val numbered = Regex("(?:^|[\\s(\\[\\-|])(?:الجزء|جزء|ج|الحلقه|حلقه|ح|البارت|بارت|part|pt\\.?|ep\\.?|episode|#)\\s*[:\\-.]?\\s*([0-9]{1,3})(?![0-9])", RegexOption.IGNORE_CASE)
    private val ordinals = listOf("", "الاول", "الثاني", "الثالث", "الرابع", "الخامس", "السادس", "السابع", "الثامن", "التاسع", "العاشر")
    private val worded = Regex("(?:الجزء|الحلقه|البارت)\\s+(الاول|الاولي|الثاني|الثانيه|الثالث|الثالثه|الرابع|الرابعه|الخامس|الخامسه|السادس|السادسه|السابع|السابعه|الثامن|الثامنه|التاسع|التاسعه|العاشر|العاشره)")

    fun plain(s: String) = s.lowercase().replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ة', 'ه').replace('ى', 'ي')

    // The part number and the series' name, or 0.
    fun part(title: String?): Pair<Int, String?> {
        val t = plain(title ?: "")
        var m = numbered.find(t)
        var n = m?.groupValues?.get(1)?.toIntOrNull() ?: 0
        if (m == null) {
            m = worded.find(t)
            if (m != null) n = ordinals.indexOf(m.groupValues[1].trimEnd('ه').replace("الاولي", "الاول"))
        }
        if (m == null || n <= 0) return 0 to null
        val name = t.removeRange(m.range).replace(Regex("[0-9\\p{P}\\p{S}|]+"), " ").replace(Regex("\\s+"), " ").trim()
        return if (name.length >= 4) n to name else 0 to null
    }

    // For each series watched lately: the next part not watched yet, from the home page's videos.
    fun following(feed: List<Item>): List<Item> {
        val watched = Store.watched
        val out = mutableListOf<Item>()
        val seen = HashSet<String>()
        for (h in Store.history().take(60)) {
            val (n, name) = part(h.title)
            if (n == 0 || name == null || !seen.add(name)) continue
            val next = (feed + Store.history()).filter { part(it.title).let { p -> p.second == name && p.first > n } && it.id !in watched }
                .minByOrNull { part(it.title).first }
            val cur = if (h.id !in watched && Store.resume(h.id) > 0) h else next
            if (cur != null && out.none { it.id == cur.id }) out.add(cur)
        }
        return out
    }
}

// Skipping a series' opening: a jump forward early in a part is kept as where that series' opening ends.
object Intros {
    fun end(title: String?): Long = Series.part(title).second?.let { Json.obj("intros.json").optLong(it, 0) } ?: 0

    fun learn(title: String?, from: Long, to: Long) {
        val name = Series.part(title).second ?: return
        if (from < 150_000 && to - from >= 8_000 && to < 240_000) {
            val o = Json.obj("intros.json")
            o.put(name, to)
            Store.writeFile("intros.json", o.toString())
        }
    }
}

// SponsorBlock: the community's marks of sponsor and self-promotion parts, skipped.
object Sponsor {
    fun segments(id: String): List<Pair<Long, Long>> {
        val hash = MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }
        val text = Net.get("https://sponsor.ajay.app/api/skipSegments/${hash.take(4)}?categories=%5B%22sponsor%22%2C%22selfpromo%22%2C%22interaction%22%5D") ?: return emptyList()
        return try {
            val a = JSONArray(text)
            (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.optString("videoID") == id }?.optJSONArray("segments")?.let { s ->
                (0 until s.length()).mapNotNull { k ->
                    s.getJSONObject(k).optJSONArray("segment")?.let { (it.getDouble(0) * 1000).toLong() to (it.getDouble(1) * 1000).toLong() }
                }
            } ?: emptyList()
        } catch (e: Exception) { emptyList() }
    }
}

// Chapters written in the description ("0:00 Intro").
object Chapters {
    fun of(description: String): List<Pair<Long, String>> {
        val l = Regex("(?m)^\\s*\\(?((?:\\d{1,2}:)?\\d{1,2}:\\d{2})\\)?\\s*[-–—:]?\\s*(.+)$").findAll(description)
            .map { (Hide.seconds(it.groupValues[1]) * 1000L) to it.groupValues[2].trim() }.toList()
        return if (l.size >= 2 && l[0].first == 0L) l else emptyList()
    }
}

// The comments under a video (YouTube's "top comments", about 20).
object Comments {
    data class Comment(val author: String, val text: String, val likes: String?, val whenText: String?)

    fun load(videoId: String): List<Comment> {
        val first = Tube.next(videoId) ?: return emptyList()
        var token: String? = null
        fun find(o: Any?) {
            when (o) {
                is JSONObject -> {
                    if (o.optString("sectionIdentifier") == "comment-item-section") {
                        Regex("\"token\":\"([^\"]+)\"").find(o.toString())?.let { token = it.groupValues[1] }
                    }
                    for (k in o.keys()) if (token == null) find(o.opt(k))
                }
                is JSONArray -> for (i in 0 until o.length()) if (token == null) find(o.opt(i))
            }
        }
        find(first)
        val t = token ?: return emptyList()
        val r = Tube.continuation(t) ?: return emptyList()
        val out = mutableListOf<Comment>()
        fun walk(o: Any?) {
            when (o) {
                is JSONObject -> {
                    o.optJSONObject("commentEntityPayload")?.let { c ->
                        val text = c.optJSONObject("properties")?.optJSONObject("content")?.optString("content") ?: ""
                        if (text.isNotBlank()) out.add(Comment(c.optJSONObject("author")?.optString("displayName") ?: "", text,
                            c.optJSONObject("toolbar")?.optString("likeCountNotliked"), c.optJSONObject("properties")?.optString("publishedTime")))
                    }
                    for (k in o.keys()) if (k != "commentEntityPayload") walk(o.opt(k))
                }
                is JSONArray -> for (i in 0 until o.length()) walk(o.opt(i))
            }
        }
        walk(r)
        return out
    }
}

// Your taste: clubs (two at most, any league), three favorite channels and what you like.
object Taste {
    val leagues = listOf(
        listOf("الدوري السعودي", "Saudi League", "الهلال", "Al Hilal", "النصر", "Al Nassr", "الاتحاد", "Al Ittihad", "الأهلي", "Al Ahli", "الشباب", "Al Shabab", "الاتفاق", "Al Ettifaq", "التعاون", "Al Taawoun", "القادسية", "Al Qadsiah"),
        listOf("الدوري الإنجليزي", "Premier League", "مانشستر سيتي", "Manchester City", "ليفربول", "Liverpool", "أرسنال", "Arsenal", "مانشستر يونايتد", "Manchester United", "تشيلسي", "Chelsea", "توتنهام", "Tottenham"),
        listOf("الدوري الإسباني", "La Liga", "ريال مدريد", "Real Madrid", "برشلونة", "Barcelona", "أتلتيكو مدريد", "Atletico Madrid"),
        listOf("الدوري الإيطالي", "Serie A", "يوفنتوس", "Juventus", "ميلان", "AC Milan", "إنتر ميلان", "Inter Milan"),
        listOf("ألمانيا وفرنسا", "Germany & France", "بايرن ميونخ", "Bayern Munich", "بوروسيا دورتموند", "Borussia Dortmund", "باريس سان جيرمان", "Paris Saint-Germain"),
        listOf("الدوريات العربية", "Arab leagues", "الأهلي المصري", "Al Ahly", "الزمالك", "Zamalek", "الوداد", "Wydad", "الرجاء", "Raja"),
    )
    val topics = listOf(
        listOf("games", "ألعاب", "Gaming", "قيم بلاي", "gameplay"), listOf("tech", "تقنية", "Tech", "مراجعة تقنية", "tech review"),
        listOf("football", "كورة", "Football", "تحليل مباراة", "football analysis"), listOf("podcast", "بودكاست", "Podcasts", "بودكاست", "podcast"),
        listOf("stories", "قصص", "Stories", "قصة واقعية", "true story"), listOf("nasheed", "شيلات وأناشيد", "Nasheeds", "شيلة", "nasheed"),
        listOf("quran", "قرآن ودروس", "Quran & lessons", "تلاوة خاشعة", "quran recitation"), listOf("cooking", "طبخ", "Cooking", "طريقة عمل", "recipe"),
        listOf("cars", "سيارات", "Cars", "مراجعة سيارة", "car review"), listOf("science", "علوم ووثائقيات", "Science", "وثائقي", "documentary"),
        listOf("comedy", "كوميديا", "Comedy", "كوميدي", "comedy"), listOf("anime", "أنمي", "Anime", "أنمي", "anime"),
        listOf("history", "تاريخ", "History", "تاريخ", "history documentary"), listOf("travel", "سفر ورحلات", "Travel", "رحلة سفر", "travel vlog"),
        listOf("fitness", "رياضة ولياقة", "Fitness", "تمارين", "workout"),
    )

    fun english(club: String): String? { for (l in leagues) { var n = 2; while (n < l.size) { if (l[n] == club) return l[n + 1]; n += 2 } }; return null }
    fun clubName(c: String) = english(c)?.let { tr(c, it) } ?: c

    var clubs: List<String>
        get() = Json.strings("taste-clubs.json").toList()
        set(v) = Json.saveStrings("taste-clubs.json", v)
    var favorites: List<String>
        get() = Json.strings("taste-favorites.json").toList()
        set(v) = Json.saveStrings("taste-favorites.json", v)
    var interests: List<String>
        get() = Json.strings("taste-interests.json").toList()
        set(v) = Json.saveStrings("taste-interests.json", v)

    private fun filter(upload: Int): String {
        val inner = byteArrayOf(8, upload.toByte(), 16, 1)
        return android.util.Base64.encodeToString(byteArrayOf(18, inner.size.toByte()) + inner, android.util.Base64.NO_WRAP)
    }

    // The clubs' matches this week, read again every 3 hours.
    fun clubRow(): List<Item> = cached("club", clubs.joinToString(",") + (if (Store.en) "en" else "ar"), 3) {
        clubs.take(2).flatMap { c ->
            val q = if (Store.en) "${english(c) ?: c} highlights" else "ملخص مباراة $c"
            (Tube.search(q, false, filter(3)) ?: emptyList()).filter { !it.isChannel && it.duration != null }.take(6)
        }
    }

    // New channels on what you like, twice a day.
    fun discoverRow(libraryIds: Set<String>): List<Item> = cached("discover", interests.sorted().joinToString(",") + (if (Store.en) "en" else "ar"), 12) {
        topics.filter { it[0] in interests }.take(4).flatMap { t ->
            (Tube.search(if (Store.en) t[4] else t[3], false, filter(4)) ?: emptyList())
                .filter { !it.isChannel && it.duration != null && it.channelUrl?.substringAfterLast('/') !in libraryIds }.take(4)
        }.distinctBy { it.channel }
    }

    private fun cached(name: String, key: String, hours: Int, read: () -> List<Item>): List<Item> {
        if (key.length <= 2) return emptyList()
        val o = Json.obj("row-$name.json")
        if (o.optString("key") == key && System.currentTimeMillis() - o.optLong("at") < hours * 3_600_000L) {
            val a = o.optJSONArray("items") ?: JSONArray()
            return (0 until a.length()).map { Item.from(a.getJSONObject(it)) }
        }
        val l = read()
        if (l.isNotEmpty()) Store.writeFile("row-$name.json", JSONObject().put("key", key).put("at", System.currentTimeMillis()).put("items", JSONArray(l.map { it.json() })).toString())
        return l
    }
}
