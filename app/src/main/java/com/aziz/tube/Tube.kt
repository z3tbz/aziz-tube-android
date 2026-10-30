package com.aziz.tube

import android.util.Xml
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

// YouTube's own web interface (search, channels) and its RSS feeds: small JSON or XML answers, no extra engine.
object Tube {
    private const val WEB = "2.20260708.00.00"

    private val region: String by lazy {
        Locale.getDefault().country.uppercase().takeIf { Regex("^[A-Z]{2}$").matches(it) } ?: "SA"
    }

    private fun context() = """{"client":{"clientName":"WEB","clientVersion":"$WEB","hl":"en","gl":"$region"}}"""

    private fun post(endpoint: String, body: String): JSONObject? {
        val (code, text) = Net.post(
            "https://www.youtube.com/youtubei/v1/$endpoint?prettyPrint=false", body,
            mapOf("User-Agent" to Net.BROWSER, "X-YouTube-Client-Name" to "1", "X-YouTube-Client-Version" to WEB, "Origin" to "https://www.youtube.com"),
        )
        return if (code == 200 && text != null) try { JSONObject(text) } catch (e: Exception) { null } else null
    }

    private fun text(o: Any?): String? {
        if (o !is JSONObject) return null
        o.optString("simpleText").takeIf { it.isNotEmpty() }?.let { return it }
        val runs = o.optJSONArray("runs") ?: return null
        return (0 until runs.length()).joinToString("") { runs.getJSONObject(it).optString("text") }
    }

    private fun agoText(s: String?): Long {
        val m = Regex("([0-9]+)\\s*(second|minute|hour|day|week|month|year)").find(s ?: "") ?: return 0
        val n = m.groupValues[1].toLong()
        val unit = when (m.groupValues[2]) {
            "second" -> 1_000L; "minute" -> 60_000L; "hour" -> 3_600_000L; "day" -> 86_400_000L
            "week" -> 7 * 86_400_000L; "month" -> 30 * 86_400_000L; else -> 365 * 86_400_000L
        }
        return System.currentTimeMillis() - n * unit
    }

    private fun number(s: String?): Double {
        val m = Regex("([0-9][0-9.,]*)\\s*([KMB]?)").find(s ?: "") ?: return 0.0
        val v = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: return 0.0
        return v * when (m.groupValues[2]) { "K" -> 1e3; "M" -> 1e6; "B" -> 1e9; else -> 1.0 }
    }

    private fun collect(o: Any?, out: MutableList<Item>, seen: MutableSet<String>) {
        when (o) {
            is JSONObject -> for (k in o.keys()) {
                val v = o.opt(k)
                var i: Item? = null
                if (v is JSONObject) {
                    when (k) {
                        "videoRenderer", "gridVideoRenderer", "compactVideoRenderer" -> i = video(v)
                        "channelRenderer" -> i = channel(v)
                        "lockupViewModel" -> i = lockup(v)
                        "reelItemRenderer", "shortsLockupViewModel", "reelShelfRenderer", "shelfRenderer", "richShelfRenderer",
                        "horizontalCardListRenderer", "searchPyvRenderer", "adSlotRenderer", "promotedSparklesWebRenderer" -> continue
                    }
                }
                if (i != null) {
                    if (seen.add(i.id)) out.add(i)
                } else collect(v, out, seen)
            }
            is JSONArray -> for (n in 0 until o.length()) collect(o.opt(n), out, seen)
        }
    }

    private fun video(v: JSONObject): Item? {
        val id = v.optString("videoId").ifEmpty { return null }
        val owner = v.optJSONObject("ownerText") ?: v.optJSONObject("longBylineText") ?: v.optJSONObject("shortBylineText")
        val browse = owner?.optJSONArray("runs")?.optJSONObject(0)?.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
        val cid = browse?.optString("browseId")
        val views = number(text(v.opt("viewCountText")))
        return Item(
            id = id,
            title = text(v.opt("title")) ?: "",
            channel = text(owner),
            channelUrl = cid?.let { "https://www.youtube.com/channel/$it" },
            duration = text(v.opt("lengthText")),
            views = if (views > 0) count(views) + tr(" مشاهدة", " views") else null,
            published = agoText(text(v.opt("publishedTimeText"))),
        )
    }

    // The newer layout (beside a video): an id, a title and lines of metadata.
    private fun lockup(l: JSONObject): Item? {
        if (l.optString("contentType") != "LOCKUP_CONTENT_TYPE_VIDEO") return null
        val id = l.optString("contentId").ifEmpty { return null }
        val meta = l.optJSONObject("metadata")?.optJSONObject("lockupMetadataViewModel") ?: return null
        val title = meta.optJSONObject("title")?.optString("content") ?: ""
        val rows = meta.optJSONObject("metadata")?.optJSONObject("contentMetadataViewModel")?.optJSONArray("metadataRows")
        val parts = mutableListOf<String>()
        if (rows != null) for (r in 0 until rows.length()) {
            val ps = rows.optJSONObject(r)?.optJSONArray("metadataParts") ?: continue
            for (k in 0 until ps.length()) ps.optJSONObject(k)?.optJSONObject("text")?.optString("content")?.let { parts.add(it) }
        }
        val views = parts.firstOrNull { it.contains("view", true) }?.let { number(it) } ?: 0.0
        return Item(id, title, channel = parts.firstOrNull(), views = if (views > 0) count(views) + tr(" مشاهدة", " views") else null,
            published = agoText(parts.firstOrNull { it.contains("ago") }))
    }

    private fun channel(c: JSONObject): Item? {
        val id = c.optString("channelId").ifEmpty { return null }
        val subs = listOf(text(c.opt("subscriberCountText")), text(c.opt("videoCountText"))).firstOrNull { it != null && it.contains("subscriber", true) }
        val thumbs = c.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
        var pic = thumbs?.optJSONObject((thumbs.length() - 1).coerceAtLeast(0))?.optString("url")
        if (pic != null && pic.startsWith("//")) pic = "https:$pic"
        return Item(id = id, title = text(c.opt("title")) ?: "", channel = text(c.opt("title")), channelUrl = "https://www.youtube.com/channel/$id",
            isChannel = true, followers = number(subs).takeIf { it > 0 }?.let { count(it) }, thumb = pic)
    }

    // YouTube's search with its "Videos" filter (or "Channels").
    fun search(q: String, channels: Boolean = false): List<Item>? {
        val r = post("search", """{"context":${context()},"query":${JSONObject.quote(q)},"params":"${if (channels) "EgIQAg==" else "EgIQAQ=="}"}""") ?: return null
        val out = mutableListOf<Item>()
        collect(r, out, HashSet())
        return out
    }

    // A channel's id from any of its links (an @handle, /c/, /user/), by YouTube's resolver or else its page.
    fun channelId(url: String): String? {
        Regex("/channel/(UC[0-9A-Za-z_-]{22})").find(url)?.let { return it.groupValues[1] }
        val root = url.replace(Regex("/(videos|playlists|streams|featured|shorts)$"), "").trimEnd('/')
        post("navigation/resolve_url", """{"context":${context()},"url":${JSONObject.quote(root)}}""")
            ?.optJSONObject("endpoint")?.optJSONObject("browseEndpoint")?.optString("browseId")
            ?.takeIf { it.startsWith("UC") }?.let { return it }
        val page = Net.get("$root?hl=en") ?: return null
        return Regex("\"externalId\":\"(UC[0-9A-Za-z_-]{22})\"").find(page)?.groupValues?.get(1)
    }

    // A channel's newest 15 videos from its RSS feed: one small request.
    fun feed(channelId: String): List<Item>? {
        val xml = Net.get("https://www.youtube.com/feeds/videos.xml?channel_id=$channelId") ?: return null
        return try {
            val p = Xml.newPullParser()
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            p.setInput(xml.reader())
            val out = mutableListOf<Item>()
            var feedTitle: String? = null
            var inEntry = false
            var id: String? = null
            var title: String? = null
            var published = 0L
            var views: String? = null
            val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType == XmlPullParser.START_TAG) {
                    when (p.name) {
                        "entry" -> { inEntry = true; id = null; title = null; published = 0; views = null }
                        "yt:videoId" -> id = p.nextText()
                        "title" -> if (inEntry) title = p.nextText() else if (feedTitle == null) feedTitle = p.nextText()
                        "published" -> if (inEntry) published = try { fmt.parse(p.nextText())?.time ?: 0 } catch (e: Exception) { 0 }
                        "media:statistics" -> views = p.getAttributeValue(null, "views")?.toDoubleOrNull()?.takeIf { it > 0 }?.let { count(it) + tr(" مشاهدة", " views") }
                    }
                } else if (p.eventType == XmlPullParser.END_TAG && p.name == "entry") {
                    inEntry = false
                    if (id != null) out.add(Item(id, title ?: "", feedTitle, "https://www.youtube.com/channel/$channelId", views = views, published = published))
                }
            }
            out
        } catch (e: Exception) {
            null
        }
    }

    // The videos YouTube lists beside this one.
    fun related(videoId: String): List<Item> {
        val r = post("next", """{"context":${context()},"videoId":"$videoId"}""") ?: return emptyList()
        val side = r.optJSONObject("contents")?.optJSONObject("twoColumnWatchNextResults")?.optJSONObject("secondaryResults")
        val out = mutableListOf<Item>()
        collect(side, out, HashSet())
        return out.filter { !it.isChannel && it.id != videoId }
    }
}
