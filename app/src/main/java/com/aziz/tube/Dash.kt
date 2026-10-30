package com.aziz.tube

// The streams written as a DASH manifest, so the player reads each by small byte ranges (YouTube slows an open-ended
// read down to play speed) and moves between qualities seamlessly by itself.
object Dash {
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun set(streams: List<Stream>): String {
        val sb = StringBuilder()
        for ((mime, group) in streams.groupBy { it.mime }) {
            sb.append("<AdaptationSet mimeType=\"").append(mime).append("\" subsegmentAlignment=\"true\">")
            for (s in group) {
                sb.append("<Representation id=\"").append(s.itag).append("\" codecs=\"").append(esc(s.codecs)).append("\" bandwidth=\"").append(s.bitrate.coerceAtLeast(1)).append('"')
                if (s.video) sb.append(" width=\"").append(s.width).append("\" height=\"").append(s.height).append("\" frameRate=\"").append(s.fps.coerceAtLeast(1)).append('"')
                else sb.append(" audioSamplingRate=\"48000\"")
                sb.append('>')
                if (!s.video) sb.append("<AudioChannelConfiguration schemeIdUri=\"urn:mpeg:dash:23003:3:audio_channel_configuration:2011\" value=\"2\"/>")
                sb.append("<BaseURL>").append(esc(s.url)).append("</BaseURL>")
                sb.append("<SegmentBase indexRange=\"").append(s.indexStart).append('-').append(s.indexEnd).append("\"><Initialization range=\"")
                    .append(s.initStart).append('-').append(s.initEnd).append("\"/></SegmentBase>")
                sb.append("</Representation>")
            }
            sb.append("</AdaptationSet>")
        }
        return sb.toString()
    }

    fun manifest(seconds: Long, video: List<Stream>, audio: List<Stream>): String =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?><MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" profiles=\"urn:mpeg:dash:profile:isoff-on-demand:2011\" type=\"static\" minBufferTime=\"PT1.5S\" mediaPresentationDuration=\"PT${seconds.coerceAtLeast(1)}S\"><Period>" +
            set(video) + set(audio) + "</Period></MPD>"
}
