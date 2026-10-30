package com.aziz.tube

import org.json.JSONObject

// A video or a channel in a list.
data class Item(
    val id: String,
    val title: String,
    val channel: String? = null,
    val channelUrl: String? = null,
    val duration: String? = null,
    val views: String? = null,
    val published: Long = 0,
    val isChannel: Boolean = false,
    val followers: String? = null,
    val thumb: String? = null,
) {
    val picture: String get() = thumb ?: "https://i.ytimg.com/vi/$id/mqdefault.jpg"

    fun json(): JSONObject = JSONObject().put("id", id).put("title", title).put("channel", channel).put("channelUrl", channelUrl)
        .put("duration", duration).put("views", views).put("published", published).put("isChannel", isChannel)
        .put("followers", followers).put("thumb", thumb)

    companion object {
        fun from(o: JSONObject) = Item(
            o.getString("id"), o.optString("title"), o.optString("channel").ifEmpty { null }, o.optString("channelUrl").ifEmpty { null },
            o.optString("duration").ifEmpty { null }, o.optString("views").ifEmpty { null }, o.optLong("published"),
            o.optBoolean("isChannel"), o.optString("followers").ifEmpty { null }, o.optString("thumb").ifEmpty { null },
        )
    }
}

fun ago(t: Long): String? {
    if (t <= 0) return null
    val d = ((System.currentTimeMillis() - t) / 86_400_000L).toInt()
    return when {
        d <= 0 -> tr("اليوم", "Today")
        d == 1 -> tr("أمس", "Yesterday")
        d < 7 -> tr("قبل $d أيام", "$d days ago")
        d < 30 -> (d / 7).let { if (it == 1) tr("قبل أسبوع", "A week ago") else tr("قبل $it أسابيع", "$it weeks ago") }
        d < 365 -> (d / 30).let { if (it == 1) tr("قبل شهر", "A month ago") else tr("قبل $it أشهر", "$it months ago") }
        else -> (d / 365).let { if (it == 1) tr("قبل سنة", "A year ago") else tr("قبل $it سنوات", "$it years ago") }
    }
}

fun count(n: Double): String = when {
    n >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM", n / 1_000_000).replace(".0M", "M")
    n >= 1000 -> "${(n / 1000).toInt()}K"
    else -> n.toInt().toString()
}
