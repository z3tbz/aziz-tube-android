package com.aziz.tube

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap

// The player: Google's ExoPlayer (Media3) decoding on the phone's video chip (MediaCodec), fed a DASH manifest made
// from the direct read, so it starts small and steps up to the best quality the screen and the network allow by itself,
// without a break. One for the whole app (the notification and picture-in-picture use the same).
@UnstableApi
object Playback {
    lateinit var player: ExoPlayer
        private set
    lateinit var selector: DefaultTrackSelector
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val main = Handler(Looper.getMainLooper())

    val current = MutableStateFlow<Item?>(null)
    val info = MutableStateFlow<Video?>(null)
    val error = MutableStateFlow<String?>(null)
    val loading = MutableStateFlow(false)
    val decoder = MutableStateFlow<String?>(null)
    val family = MutableStateFlow<String?>(null)
    val height = MutableStateFlow(0)
    var queue: List<Item> = emptyList()
    private var opened = 0L

    // Reads made ahead (the next video, the first ones of a page): an id's streams, for 5 hours.
    private val ahead = ConcurrentHashMap<String, Pair<Long, Video>>()

    fun init(c: Context) {
        if (::player.isInitialized) return
        selector = DefaultTrackSelector(c)
        if (Store.maxHeight > 0) selector.setParameters(selector.buildUponParameters().setMaxVideoSize(Int.MAX_VALUE, Store.maxHeight))
        // Starts after under a second of reserve, keeps 15 to 60 seconds ahead.
        val load = DefaultLoadControl.Builder().setBufferDurationsMs(15_000, 60_000, 800, 2_000).build()
        player = ExoPlayer.Builder(c, DefaultRenderersFactory(c).setEnableDecoderFallback(true))
            .setTrackSelector(selector)
            .setLoadControl(load)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        player.setSeekParameters(SeekParameters.CLOSEST_SYNC)
        player.skipSilenceEnabled = Store.skipSilence
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                decoder.value = decoderName
            }
            override fun onVideoInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: androidx.media3.common.Format, decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?) {
                height.value = format.height
            }
        })
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    save()
                    if (Store.autoNext) next()
                }
            }
            override fun onPlayerError(e: PlaybackException) {
                // A link that expired or was refused: read again once, from the same second.
                val item = current.value ?: return
                if (System.currentTimeMillis() - opened > 3_000) {
                    val at = player.currentPosition
                    ahead.remove(item.id)
                    open(item, at)
                } else error.value = tr("تعذر التشغيل: ", "Couldn't play: ") + (e.errorCodeName)
            }
        })
        // Where the video was left, every 10 seconds.
        main.postDelayed(object : Runnable {
            override fun run() {
                if (player.isPlaying) save()
                main.postDelayed(this, 10_000)
            }
        }, 10_000)
    }

    private fun save() {
        val id = current.value?.id ?: return
        Store.setResume(id, player.currentPosition, player.duration.coerceAtLeast(0))
    }

    private fun readCached(id: String): Video {
        ahead[id]?.let { (t, v) -> if (System.currentTimeMillis() - t < 5 * 3_600_000L) return v }
        val v = try { Direct.read(id) } catch (e: Direct.Refused) { Direct.read(id) }
        ahead[id] = System.currentTimeMillis() to v
        return v
    }

    // Read ahead in the background (nothing played).
    fun prefetch(items: List<Item>) {
        scope.launch(Dispatchers.IO) {
            for (i in items.filter { !it.isChannel }.take(4)) {
                if (ahead.containsKey(i.id)) continue
                try { readCached(i.id) } catch (_: Exception) {}
            }
        }
    }

    fun play(item: Item, list: List<Item> = emptyList()) {
        if (list.isNotEmpty()) queue = list
        save()
        open(item, -1)
    }

    private fun open(item: Item, at: Long) {
        current.value = item
        error.value = null
        loading.value = true
        decoder.value = null
        opened = System.currentTimeMillis()
        Store.addHistory(item)
        scope.launch {
            try {
                val v = withContext(Dispatchers.IO) { readCached(item.id) }
                if (current.value?.id != item.id) return@launch
                info.value = v
                val (fam, video) = Codecs.pick(v.streams, Store.maxHeight)
                family.value = fam.label
                val mpd = Dash.manifest(v.lengthSeconds, video, Codecs.audio(v.streams))
                val manifest = DashManifestParser().parse(Uri.parse("https://www.youtube.com/"), ByteArrayInputStream(mpd.toByteArray()))
                val meta = MediaMetadata.Builder().setTitle(v.title).setArtist(v.channel).setArtworkUri(Uri.parse("https://i.ytimg.com/vi/${v.id}/hqdefault.jpg")).build()
                val source = DashMediaSource.Factory(OkHttpDataSource.Factory(Net.http).setUserAgent(Direct.AGENT))
                    .createMediaSource(manifest, MediaItem.Builder().setMediaId(v.id).setMediaMetadata(meta).build())
                val start = if (at >= 0) at else Store.resume(v.id)
                player.setMediaSource(source, start)
                player.setPlaybackSpeed(Store.speed(v.channel))
                player.prepare()
                player.play()
                // The next one read ahead a little later.
                main.postDelayed({ upNext()?.let { prefetch(listOf(it)) } }, 8_000)
            } catch (e: Exception) {
                if (current.value?.id == item.id) error.value = e.message ?: tr("تعذر التشغيل", "Couldn't play")
            } finally {
                loading.value = false
            }
        }
    }

    fun upNext(): Item? {
        val id = current.value?.id ?: return null
        val n = queue.indexOfFirst { it.id == id }
        return if (n >= 0 && n + 1 < queue.size) queue[n + 1] else null
    }

    fun next() {
        upNext()?.let { open(it, -1) }
    }

    fun setSpeed(v: Float) {
        player.setPlaybackSpeed(v)
        Store.setSpeed(info.value?.channel, v)
    }

    fun setSkipSilence(on: Boolean) {
        Store.skipSilence = on
        player.skipSilenceEnabled = on
    }

    // The qualities on offer now (heights), and a choice: 0 is automatic.
    fun heights(): List<Int> {
        val g = player.currentTracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO } ?: return emptyList()
        return (0 until g.length).map { g.getTrackFormat(it).height }.distinct().sortedDescending()
    }

    fun chooseHeight(h: Int) {
        val b = selector.buildUponParameters().clearOverridesOfType(C.TRACK_TYPE_VIDEO)
        if (h > 0) {
            val g = player.currentTracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO }
            if (g != null) {
                val idx = (0 until g.length).filter { g.getTrackFormat(it).height == h }
                if (idx.isNotEmpty()) b.addOverride(TrackSelectionOverride(g.mediaTrackGroup, idx))
            }
        }
        selector.setParameters(b)
    }

    fun stop() {
        save()
        player.stop()
        current.value = null
        info.value = null
    }
}
