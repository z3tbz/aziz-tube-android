package com.aziz.tube

import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.flow.MutableStateFlow

// The app's own volume, on top of the phone's: 0 to 100, 50 is the sound as it comes. Above 50 the sound is raised past
// what the phone gives (up to +12 dB, +20 dB with "Stronger boost"), through a limiter that holds the peaks under the
// top instead of clipping them: louder without the crackle. A gentle compressor (stronger boost only) lifts the quiet
// parts too, so speech gets through a weak speaker.
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

    // Under 50: quieter, along the ear's curve. Over 50: the boost in dB.
    private fun boostDb(): Float {
        val l = level.value
        if (l <= 50) return 0f
        val top = if (Store.strongBoost) 20f else 12f
        return (l - 50) / 50f * top
    }

    fun apply() {
        val l = level.value
        val quiet = if (l >= 50) 1f else (l / 50f).let { it * it }
        try { Playback.player.volume = (base * quiet).coerceIn(0f, 1f) } catch (_: Exception) {}
        if (session == 0) return
        val db = boostDb()
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                val p = dp ?: make()?.also { dp = it } ?: return
                p.setInputGainAllChannelsTo(db)
                p.enabled = db > 0f || Store.strongBoost
            } else {
                val e = le ?: LoudnessEnhancer(session).also { le = it }
                e.setTargetGain((db * 100).toInt())
                e.enabled = db > 0f
            }
        } catch (_: Exception) {
        }
    }

    private fun make(): DynamicsProcessing? {
        if (Build.VERSION.SDK_INT < 28) return null
        return try {
            val strong = Store.strongBoost
            val cfg = DynamicsProcessing.Config.Builder(DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2, false, 0, strong, 1, false, 0, true).build()
            val p = DynamicsProcessing(0, session, cfg)
            if (strong) {
                // One band: the quiet parts lifted, the loud ones held (3:1 over -24 dB, +6 dB back).
                val band = DynamicsProcessing.MbcBand(true, 20000f, 3f, 80f, 3f, -24f, 0f, -90f, 1f, 0f, 6f)
                p.setMbcBandAllChannelsTo(0, band)
            }
            // The limiter: nothing goes past -1 dB, quick to catch a peak, quick to let go.
            p.setLimiterAllChannelsTo(DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, 10f, -1f, 0f))
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
