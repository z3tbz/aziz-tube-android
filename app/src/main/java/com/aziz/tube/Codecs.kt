package com.aziz.tube

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build

// What this phone's video chip decodes itself (hardware), and up to what size: the format chosen is the best one it
// decodes in hardware (AV1, else VP9, else H.264), never one left to the processor when another is decoded by the chip.
object Codecs {
    enum class Family(val mime: String, val label: String) {
        AV1("video/av01", "AV1"), VP9("video/x-vnd.on2.vp9", "VP9"), H264("video/avc", "H.264");
    }

    fun family(codecs: String): Family? = when {
        codecs.startsWith("av01") -> Family.AV1
        codecs.startsWith("vp9") || codecs.startsWith("vp09") -> Family.VP9
        codecs.startsWith("avc1") -> Family.H264
        else -> null
    }

    private val list: Array<MediaCodecInfo> by lazy {
        try { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos } catch (e: Exception) { emptyArray() }
    }

    fun hardware(info: MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated && !info.isSoftwareOnly
        else !(info.name.startsWith("OMX.google.") || info.name.startsWith("c2.android.") || info.name.contains("ffmpeg", true))

    fun hardwareName(name: String?): Boolean = name != null && list.firstOrNull { it.name == name }?.let { hardware(it) } ?: false

    private fun decoders(f: Family) = list.filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals(f.mime, true) } }

    // The chip decodes this size and rate itself.
    fun hardwareFits(f: Family, w: Int, h: Int, fps: Int): Boolean = decoders(f).filter { hardware(it) }.any {
        try {
            val v = it.getCapabilitiesForType(f.mime).videoCapabilities
            if (fps > 0) v.areSizeAndRateSupported(w, h, fps.toDouble()) else v.isSizeSupported(w, h)
        } catch (e: Exception) {
            false
        }
    }

    fun anyHardware(f: Family) = decoders(f).any { hardware(it) }

    // The streams to play: the best family the chip decodes, its sizes the chip handles, up to the most asked.
    fun pick(streams: List<Stream>, maxHeight: Int): Pair<Family, List<Stream>> {
        val video = streams.filter { it.video }
        for (f in listOf(Family.AV1, Family.VP9, Family.H264)) {
            if (!anyHardware(f)) continue
            val ok = video.filter { family(it.codecs) == f && (maxHeight <= 0 || it.height <= maxHeight) && hardwareFits(f, it.width, it.height, it.fps) }
            if (ok.isNotEmpty()) return f to ok
        }
        // No chip decoder at all (very rare): H.264 on the processor, the lightest there is.
        val h = video.filter { family(it.codecs) == Family.H264 && (maxHeight <= 0 || it.height <= maxHeight.coerceAtMost(720)) }
        return Family.H264 to h.ifEmpty { video.filter { family(it.codecs) == Family.H264 }.sortedBy { it.height }.take(1) }
    }

    // The sound: the original track, Opus (lighter) before AAC, up to two qualities.
    fun audio(streams: List<Stream>): List<Stream> {
        val a = streams.filter { !it.video }
        val orig = a.filter { it.original }.ifEmpty { a }
        val opus = orig.filter { it.codecs.startsWith("opus") }
        return (opus.ifEmpty { orig.filter { it.codecs.startsWith("mp4a") } }.ifEmpty { orig }).sortedByDescending { it.bitrate }.take(2)
    }
}
