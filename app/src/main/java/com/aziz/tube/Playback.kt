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
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.SingleSampleMediaSource
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
    // What the user added to play next (before the list's own next).
    val upcoming = mutableListOf<Item>()
    val notice = MutableStateFlow<String?>(null)
    val introEnd = MutableStateFlow(0L)
    val chapters = MutableStateFlow<List<Pair<Long, String>>>(emptyList())
    private var sponsors: List<Pair<Long, Long>> = emptyList()
    private var sleepAt = 0L
    private var sleepAtEnd = false
    private lateinit var app: Context
    private var opened = 0L

    // Reads made ahead (the next video, the first ones of a page): an id's streams, for 5 hours.
    private val ahead = ConcurrentHashMap<String, Pair<Long, Video>>()

    fun init(c: Context) {
        if (::player.isInitialized) return
        app = c.applicationContext
        selector = DefaultTrackSelector(c)
        // The subtitle language chosen last time.
        Store.readFile("subs.txt")?.let { selector.setParameters(selector.buildUponParameters().setPreferredTextLanguage(it)) }
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
        // One sound session for the whole app, so the volume boost stays on it across videos.
        val sid = androidx.media3.common.util.Util.generateAudioSessionIdV21(c)
        player.setAudioSessionId(sid)
        Loud.attach(sid)
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
            // A speed chosen in the player's gear is kept for this channel.
            override fun onPlaybackParametersChanged(p: androidx.media3.common.PlaybackParameters) {
                Store.setSpeed(info.value?.channel, p.speed)
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    save()
                    if (sleepAtEnd) { sleepAtEnd = false; notice.value = tr("مؤقت النوم · انتهى المقطع", "Sleep timer · the video ended"); return }
                    if (Store.autoNext) next()
                }
            }
            // A jump forward early in a part of a series: where its opening ends, for its next parts.
            override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) Intros.learn(info.value?.title, old.positionMs, new.positionMs)
            }
            // The subtitle language picked is kept for next time.
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_TEXT && it.isSelected }?.let { g ->
                    (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { k -> g.getTrackFormat(k).language?.let { Store.writeFile("subs.txt", it) } }
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
        // Twice a second: sponsor parts skipped, the opening's button, the sleep timer.
        main.postDelayed(object : Runnable {
            override fun run() {
                if (player.isPlaying) {
                    val at = player.currentPosition
                    // The opening skipped by itself, when chosen; its button gone once past it.
                    val ie = introEnd.value
                    if (ie > 0 && Intros.auto && at < ie - 1000) { player.seekTo(ie); introEnd.value = 0; notice.value = tr("تخطّى المقدمة", "Skipped the intro") }
                    else if (ie > 0 && at >= ie - 1000) introEnd.value = 0
                    sponsors.firstOrNull { at >= it.first && at < it.second - 500 }?.let {
                        player.seekTo(it.second)
                        notice.value = tr("تخطي جزء إعلاني (SponsorBlock)", "Skipped a sponsor part (SponsorBlock)")
                    }
                    if (sleepAt in 1..System.currentTimeMillis()) { sleepAt = 0; player.pause(); notice.value = tr("مؤقت النوم · توقف التشغيل", "Sleep timer · playback stopped") }
                }
                main.postDelayed(this, 500)
            }
        }, 500)
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
                // Data saver on mobile data: 480p at most.
                val cap = if (Store.dataSaver && Data.metered(app)) 480 else Store.maxHeight
                val (fam, video) = Codecs.pick(v.streams, cap)
                family.value = fam.label
                val mpd = Dash.manifest(v.lengthSeconds, video, Codecs.audio(v.streams))
                val manifest = DashManifestParser().parse(Uri.parse("https://www.youtube.com/"), ByteArrayInputStream(mpd.toByteArray()))
                val meta = MediaMetadata.Builder().setTitle(v.title).setArtist(v.channel).setArtworkUri(Uri.parse("https://i.ytimg.com/vi/${v.id}/hqdefault.jpg")).build()
                val data = OkHttpDataSource.Factory(Net.http).setUserAgent(Direct.AGENT)
                val dash = DashMediaSource.Factory(data).createMediaSource(manifest, MediaItem.Builder().setMediaId(v.id).setMediaMetadata(meta).build())
                // Subtitles beside it, each read only when chosen.
                val subs = v.captions.map { cap ->
                    SingleSampleMediaSource.Factory(data).createMediaSource(
                        MediaItem.SubtitleConfiguration.Builder(Uri.parse(cap.url)).setMimeType(MimeTypes.TEXT_VTT).setLanguage(cap.lang).setLabel(cap.name).build(), C.TIME_UNSET)
                }
                val source: MediaSource = if (subs.isEmpty()) dash else MergingMediaSource(dash, *subs.toTypedArray())
                val start = if (at >= 0) at else Store.resume(v.id)
                player.setMediaSource(source, start)
                player.setPlaybackSpeed(Store.speed(v.channel))
                // Even volume: a loud video brought down to the others' level (YouTube says how loud it is).
                Loud.base = if (Store.evenVolume && v.loudnessDb > 0) Math.pow(10.0, -v.loudnessDb / 20.0).toFloat() else 1f
                chapters.value = Chapters.of(v.description)
                introEnd.value = Intros.end(v.title, v.description)
                sponsors = emptyList()
                scope.launch(Dispatchers.IO) {
                    val s = Sponsor.segments(v.id)
                    val m = if (introEnd.value == 0L) Intros.marked(v.id) else 0
                    if (current.value?.id == v.id) { sponsors = s; if (m > 0 && introEnd.value == 0L) introEnd.value = m }
                }
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
        if (upcoming.isNotEmpty()) return upcoming.first()
        val id = current.value?.id ?: return null
        val n = queue.indexOfFirst { it.id == id }
        return if (n >= 0 && n + 1 < queue.size) queue[n + 1] else null
    }

    fun next() {
        val n = upNext() ?: return
        upcoming.remove(n)
        open(n, -1)
    }

    fun enqueue(i: Item) { upcoming.add(i); notice.value = tr("أُضيف لقائمة الانتظار (${upcoming.size})", "Added to the queue (${upcoming.size})") }

    // Sleep timer: after some minutes (0 cancels), or at the end of this video (-1).
    fun sleep(minutes: Int) {
        sleepAtEnd = minutes == -1
        sleepAt = if (minutes > 0) System.currentTimeMillis() + minutes * 60_000L else 0
        notice.value = when {
            minutes > 0 -> tr("مؤقت النوم · يتوقف بعد $minutes دقيقة", "Sleep timer · stops in $minutes minutes")
            minutes == -1 -> tr("مؤقت النوم · يتوقف بنهاية المقطع", "Sleep timer · stops at the end of the video")
            else -> tr("مؤقت النوم ملغى", "Sleep timer cancelled")
        }
    }

    fun skipIntro() { if (introEnd.value > player.currentPosition) player.seekTo(introEnd.value); introEnd.value = 0 }

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
