package com.aziz.tube

import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.flow.MutableStateFlow

// The app's own volume, on top of the phone's: 0 to 100, 50 is the sound as it comes. Above 50 the sound is raised past
// what the phone gives with Android's loudness enhancer (on every phone): up to +10 dB.
// "Stronger boost" (weak speakers) is on at all times: the heavy bass a small speaker can't play is cut (it only eats
// the room and rattles), speech is lifted, everything is compressed hard and raised, and a limiter holds the top:
// about +14 dB even at 50, up to +26 dB at 100. Louder for real, with distortion possible near the top.
@UnstableApi
object Loud {
    val level = MutableStateFlow(Store.appVolume)

    private var session = 0
    private var dp: DynamicsProcessing? = null
    private var le: LoudnessEnhancer? = null

    // The video's own level (even volume), 1 when left as it is.
    var base = 1f
        set(v) { field = v; apply() }

    fun attach(audioSession: Int) {
        if (audioSession == session) return
        release()
        session = audioSession
        apply()
    }

    fun set(v: Int) {
        val l = v.coerceIn(0, 100)
        level.value = l
        Store.appVolume = l
        apply()
    }

    fun setStrong(on: Boolean) {
        Store.strongBoost = on
        release()
        apply()
    }

    // The raise in dB over the sound as it comes.
    private fun boostDb(): Float {
        val over = ((level.value - 50).coerceAtLeast(0)) / 50f
        return if (Store.strongBoost) 14f + over * 12f else over * 10f
    }

    fun apply() {
        val l = level.value
        val quiet = if (l >= 50) 1f else (l / 50f).let { it * it }
        // Even volume never takes a boosted sound down.
        val b = if (boostDb() > 0f) 1f else base
        try { Playback.player.volume = (b * quiet).coerceIn(0f, 1f) } catch (_: Exception) {}
        if (session == 0) return
        val db = boostDb()
        try {
            val e = le ?: LoudnessEnhancer(session).also { le = it }
            e.setTargetGain((db * 100).toInt())
            e.enabled = db > 0f
        } catch (_: Exception) {
        }
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                if (Store.strongBoost) { if (dp == null) dp = shaping(); dp?.enabled = true } else dp?.enabled = false
            } catch (_: Exception) {
            }
        }
    }

    // Weak-speaker shaping: bass under 150 Hz down, presence (2 to 5 kHz) up, a hard compressor, the limiter on top.
    private fun shaping(): DynamicsProcessing? {
        if (Build.VERSION.SDK_INT < 28) return null
        return try {
            val cfg = DynamicsProcessing.Config.Builder(DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2, true, 4, true, 1, false, 0, true).build()
            val p = DynamicsProcessing(0, session, cfg)
            val eq = DynamicsProcessing.Eq(true, true, 4)
            eq.setBand(0, DynamicsProcessing.EqBand(true, 150f, -10f))
            eq.setBand(1, DynamicsProcessing.EqBand(true, 1500f, 0f))
            eq.setBand(2, DynamicsProcessing.EqBand(true, 5000f, 5f))
            eq.setBand(3, DynamicsProcessing.EqBand(true, 20000f, 2f))
            p.setPreEqAllChannelsTo(eq)
            // Everything over -36 dB pressed 6:1 and raised back by 18 dB: quiet and loud parts come close together.
            p.setMbcBandAllChannelsTo(0, DynamicsProcessing.MbcBand(true, 20000f, 2f, 120f, 6f, -36f, 6f, -90f, 1f, 0f, 18f))
            p.setLimiterAllChannelsTo(DynamicsProcessing.Limiter(true, true, 0, 0.5f, 50f, 20f, -0.5f, 0f))
            p
        } catch (_: Exception) {
            null
        }
    }

    private fun release() {
        try { dp?.release() } catch (_: Exception) {}
        try { le?.release() } catch (_: Exception) {}
        dp = null
        le = null
    }
}
