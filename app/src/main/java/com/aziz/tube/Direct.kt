package com.aziz.tube

import org.json.JSONObject

// One stream of a video, as YouTube gives it.
data class Stream(
    val itag: Int,
    val url: String,
    val mime: String,
    val codecs: String,
    val bitrate: Int,
    val width: Int,
    val height: Int,
    val fps: Int,
    val initStart: Long,
    val initEnd: Long,
    val indexStart: Long,
    val indexEnd: Long,
    val contentLength: Long,
    val language: String?,
    val original: Boolean,
) {
    val video get() = mime.startsWith("video/")
}

class Video(val id: String, val title: String, val channel: String?, val channelId: String?, val lengthSeconds: Long, val description: String, val streams: List<Stream>)

// A video's streams asked from YouTube directly, in one request (about half a second), as YouTube's visionOS app asks:
// its answer has plain stream links (nothing to solve), so a video starts at once. The same way the Windows app reads.
object Direct {
    private const val VERSION = "1.02"
    const val AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15"

    @Volatile private var visitor: String? = null
    @Volatile private var visitorAt = 0L

    // The visitor's identity YouTube expects (without it: "confirm you're not a bot"), from YouTube's home page, kept 6 hours.
    private fun visitor(): String? {
        visitor?.let { if (System.currentTimeMillis() - visitorAt < 6 * 3_600_000L) return it }
        Store.readFile("visitor.txt")?.split(" ")?.takeIf { it.size == 2 }?.let { (t, v) ->
            if (System.currentTimeMillis() - (t.toLongOrNull() ?: 0) < 6 * 3_600_000L && v.isNotEmpty()) {
                visitor = v; visitorAt = t.toLong(); return v
            }
        }
        val page = Net.get("https://www.youtube.com/?hl=en", AGENT) ?: return null
        val v = (Regex("\"VISITOR_DATA\"\\s*:\\s*\"([^\"]+)\"").find(page) ?: Regex("\"visitorData\"\\s*:\\s*\"([^\"]+)\"").find(page))?.groupValues?.get(1) ?: return null
        visitor = v
        visitorAt = System.currentTimeMillis()
        Store.writeFile("visitor.txt", "$visitorAt $v")
        return v
    }

    class Refused(why: String) : Exception(why)

    fun read(id: String): Video {
        val vd = visitor() ?: throw Refused(tr("ما قدر يوصل ليوتيوب", "Couldn't reach YouTube"))
        val body = """{"context":{"client":{"visitorData":"$vd","clientName":"VISIONOS","clientVersion":"$VERSION","deviceMake":"Apple","deviceModel":"RealityDevice17,1","userAgent":"$AGENT","osName":"visionOS","osVersion":"26.5.23O471","hl":"en"}},"videoId":"$id","playbackContext":{"contentPlaybackContext":{"html5Preference":"HTML5_PREF_WANTS"}},"contentCheckOk":true,"racyCheckOk":true}"""
        val (code, text) = Net.post(
            "https://www.youtube.com/youtubei/v1/player?prettyPrint=false", body,
            mapOf("User-Agent" to AGENT, "X-YouTube-Client-Name" to "101", "X-YouTube-Client-Version" to VERSION, "Origin" to "https://www.youtube.com", "X-Goog-Visitor-Id" to vd),
        )
        if (code != 200 || text == null) throw Refused("HTTP $code")
        val r = JSONObject(text)
        val ps = r.optJSONObject("playabilityStatus")
        if (ps?.optString("status") != "OK") {
            val reason = ps?.optString("reason") ?: ""
            if (reason.contains("bot", true)) { visitor = null; Store.writeFile("visitor.txt", "") }
            throw Refused(reason.ifEmpty { ps?.optString("status") ?: "?" })
        }
        val d = r.optJSONObject("videoDetails") ?: throw Refused("no details")
        if (d.optBoolean("isLive")) throw Refused(tr("البث المباشر غير مدعوم", "Live streams aren't supported"))
        val list = mutableListOf<Stream>()
        val a = r.optJSONObject("streamingData")?.optJSONArray("adaptiveFormats")
        if (a != null) for (n in 0 until a.length()) {
            val o = a.getJSONObject(n)
            val url = o.optString("url")
            if (url.isEmpty()) continue
            if (o.has("drmFamilies")) continue
            val m = Regex("^((?:video|audio)/[a-z0-9]+);\\s*codecs=\"([^\"]+)\"").find(o.optString("mimeType")) ?: continue
            val init = o.optJSONObject("initRange") ?: continue
            val index = o.optJSONObject("indexRange") ?: continue
            val track = o.optJSONObject("audioTrack")
            list.add(Stream(
                o.optInt("itag"), url, m.groupValues[1], m.groupValues[2], o.optInt("bitrate"), o.optInt("width"), o.optInt("height"), o.optInt("fps"),
                init.optString("start").toLong(), init.optString("end").toLong(), index.optString("start").toLong(), index.optString("end").toLong(),
                o.optString("contentLength").toLongOrNull() ?: 0, track?.optString("id")?.substringBefore('.'),
                track == null || track.optString("displayName").contains("original", true) || track.optBoolean("audioIsDefault"),
            ))
        }
        if (list.none { it.video } || list.none { !it.video }) throw Refused(tr("ما فيه صورة أو صوت", "No picture or sound"))
        return Video(id, d.optString("title"), d.optString("author").ifEmpty { null }, d.optString("channelId").ifEmpty { null },
            d.optString("lengthSeconds").toLongOrNull() ?: 0, d.optString("shortDescription"), list)
    }
}
