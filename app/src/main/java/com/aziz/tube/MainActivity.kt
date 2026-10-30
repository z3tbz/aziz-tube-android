package com.aziz.tube

import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Bg = Color(0xFF0F0F0F)
private val Surface = Color(0xFF212121)
private val Muted = Color(0xFFAAAAAA)
private val Accent = Color(0xFFFF0033)

@UnstableApi
class MainActivity : ComponentActivity() {
    private var controller: MediaController? = null
    private val playerOpen = mutableStateOf(false)
    private val inPip = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Playback.init(applicationContext)
        handle(intent)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Bg, surface = Bg)) {
                CompositionLocalProvider(LocalLayoutDirection provides if (Store.en) LayoutDirection.Ltr else LayoutDirection.Rtl) {
                    Root(playerOpen, inPip)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Connecting to the service keeps the sound going in the background with its notification.
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val f = MediaController.Builder(this, token).buildAsync()
        f.addListener({ controller = try { f.get() } catch (e: Exception) { null } }, mainExecutor)
    }

    override fun onStop() {
        controller?.release()
        controller = null
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    // A YouTube link shared to the app or opened with it.
    private fun handle(i: Intent?) {
        val text = i?.dataString ?: i?.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val id = Regex("(?:[?&]v=|youtu\\.be/|/shorts/|/live/)([0-9A-Za-z_-]{11})").find(text)?.groupValues?.get(1) ?: return
        Playback.play(Item(id, ""))
        playerOpen.value = true
    }

    // Leaving the app while a video plays on its page: it goes on in a small window.
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (playerOpen.value && Playback.player.isPlaying && Build.VERSION.SDK_INT >= 26) {
            try { enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()) } catch (_: Exception) {}
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip.value = isInPictureInPictureMode
    }
}

enum class Tab { Home, Search, Library, Settings }

@UnstableApi
@Composable
fun Root(playerOpen: MutableState<Boolean>, inPip: State<Boolean>) {
    var tab by remember { mutableStateOf(Tab.Home) }
    var channel by remember { mutableStateOf<Item?>(null) }
    val current by Playback.current.collectAsState()
    val open: (Item, List<Item>) -> Unit = { i, l ->
        if (i.isChannel) channel = i else { Playback.play(i, l); playerOpen.value = true }
    }
    if (inPip.value) {
        VideoSurface(Modifier.fillMaxSize())
        return
    }
    Box(Modifier.fillMaxSize().background(Bg)) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                when {
                    channel != null -> ChannelPage(channel!!, open) { channel = null }
                    tab == Tab.Home -> HomePage(open)
                    tab == Tab.Search -> SearchPage(open)
                    tab == Tab.Library -> LibraryPage(open) { channel = it }
                    else -> SettingsPage()
                }
            }
            if (current != null && !playerOpen.value) MiniBar { playerOpen.value = true }
            NavigationBar(containerColor = Bg) {
                listOf(
                    Triple(Tab.Home, Icons.Filled.Home, tr("الرئيسية", "Home")),
                    Triple(Tab.Search, Icons.Filled.Search, tr("بحث", "Search")),
                    Triple(Tab.Library, Icons.Filled.VideoLibrary, tr("المكتبة", "Library")),
                    Triple(Tab.Settings, Icons.Filled.Settings, tr("الإعدادات", "Settings")),
                ).forEach { (t, icon, label) ->
                    NavigationBarItem(selected = tab == t && channel == null, onClick = { tab = t; channel = null }, icon = { Icon(icon, null) }, label = { Text(label) })
                }
            }
        }
        if (playerOpen.value && current != null) PlayerPage(open, { channel = it; playerOpen.value = false }) { playerOpen.value = false }
        if (channel != null) BackHandler { channel = null }
    }
}

@UnstableApi
@Composable
fun VideoSurface(modifier: Modifier) {
    AndroidView(factory = { c ->
        PlayerView(c).apply {
            player = Playback.player
            setShowSubtitleButton(true)
            setShowNextButton(true)
            setShowPreviousButton(false)
            setBackgroundColor(android.graphics.Color.BLACK)
            keepScreenOn = true
        }
    }, modifier = modifier, onRelease = { it.player = null })
}

@UnstableApi
@Composable
fun MiniBar(onOpen: () -> Unit) {
    val item by Playback.current.collectAsState()
    val info by Playback.info.collectAsState()
    var playing by remember { mutableStateOf(Playback.player.isPlaying) }
    LaunchedEffect(Unit) { while (true) { playing = Playback.player.isPlaying; kotlinx.coroutines.delay(500) } }
    Row(Modifier.fillMaxWidth().background(Surface).clickable { onOpen() }.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        AsyncImage(model = item?.picture, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(72.dp, 40.dp).clip(RoundedCornerShape(6.dp)))
        Text(info?.title ?: item?.title ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color.White, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
        IconButton(onClick = { if (Playback.player.isPlaying) Playback.player.pause() else Playback.player.play() }) {
            Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, null, tint = Color.White)
        }
        IconButton(onClick = { Playback.stop() }) { Icon(Icons.Filled.Close, null, tint = Color.White) }
    }
}

@Composable
fun VideoCard(i: Item, onClick: () -> Unit) {
    val watched = remember(i.id) { Store.watched.contains(i.id) }
    Column(Modifier.fillMaxWidth().clickable { onClick() }.padding(bottom = 14.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Surface)) {
            AsyncImage(model = i.picture, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            if (i.duration != null) Text(i.duration, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).background(Color(0xCC000000), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
            if (watched) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Accent))
        }
        Text(i.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp))
        val line = listOfNotNull(i.channel, i.views, ago(i.published)).joinToString(" · ")
        if (line.isNotEmpty()) Text(line, color = Muted, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp))
    }
}

@Composable
fun ChannelRow(i: Item, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        AsyncImage(model = i.thumb, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(48.dp).clip(CircleShape).background(Surface))
        Column(Modifier.padding(start = 14.dp)) {
            Text(i.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            if (i.followers != null) Text(i.followers + tr(" مشترك", " subscribers"), color = Muted, fontSize = 12.5.sp)
        }
    }
}

@UnstableApi
@Composable
fun ItemList(items: List<Item>, open: (Item, List<Item>) -> Unit, header: (@Composable () -> Unit)? = null) {
    LazyColumn(Modifier.fillMaxSize()) {
        if (header != null) item { header() }
        items(items, key = { it.id + it.isChannel }) { i ->
            if (i.isChannel) ChannelRow(i) { open(i, items) } else VideoCard(i) { open(i, items) }
        }
    }
}

@UnstableApi
@Composable
fun HomePage(open: (Item, List<Item>) -> Unit) {
    var items by remember { mutableStateOf(Library.cached().first) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val refresh = {
        if (!busy) {
            busy = true
            scope.launch {
                val fresh = Library.refresh()
                if (fresh.isNotEmpty()) items = fresh
                busy = false
                Playback.prefetch(items)
            }
        }
    }
    LaunchedEffect(Unit) {
        val (_, at) = Library.cached()
        Playback.prefetch(items)
        if (items.isEmpty() || System.currentTimeMillis() - at > 30 * 60_000) refresh()
    }
    ItemList(items, open) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("AZIZ", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(" Tube", color = Accent, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else IconButton(onClick = { refresh() }) { Icon(Icons.Filled.Refresh, tr("تحديث", "Refresh"), tint = Color.White) }
        }
        if (items.isEmpty() && !busy) Text(tr("ما فيه مقاطع للحين، اسحب للتحديث", "No videos yet, tap refresh"), color = Muted, modifier = Modifier.padding(16.dp))
    }
}

@UnstableApi
@Composable
fun SearchPage(open: (Item, List<Item>) -> Unit) {
    var q by rememberSaveable { mutableStateOf("") }
    var channels by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<Item>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var none by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val go = {
        if (q.isNotBlank()) {
            busy = true
            scope.launch {
                // A pasted video link plays at once.
                val id = Regex("(?:[?&]v=|youtu\\.be/|/shorts/)([0-9A-Za-z_-]{11})").find(q)?.groupValues?.get(1)
                if (id != null) { busy = false; open(Item(id, ""), emptyList()); return@launch }
                val r = withContext(Dispatchers.IO) { Tube.search(q.trim(), channels) } ?: emptyList()
                results = r
                none = r.isEmpty()
                busy = false
                Playback.prefetch(r)
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = q, onValueChange = { q = it }, singleLine = true,
            placeholder = { Text(tr("بحث أو رابط مقطع", "Search, or paste a video link")) },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { go() }),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth().padding(12.dp),
        )
        Row(Modifier.padding(horizontal = 12.dp)) {
            FilterChip(selected = !channels, onClick = { channels = false; go() }, label = { Text(tr("مقاطع", "Videos")) })
            Spacer(Modifier.width(8.dp))
            FilterChip(selected = channels, onClick = { channels = true; go() }, label = { Text(tr("قنوات", "Channels")) })
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        if (none) Text(tr("ما فيه نتائج", "No results"), color = Muted, modifier = Modifier.padding(16.dp))
        Spacer(Modifier.height(8.dp))
        ItemList(results, open)
    }
}

@UnstableApi
@Composable
fun ChannelPage(ch: Item, open: (Item, List<Item>) -> Unit, back: () -> Unit) {
    var items by remember(ch.id) { mutableStateOf<List<Item>?>(null) }
    var inLib by remember(ch.id) { mutableStateOf(Library.has(ch.title, ch.channelUrl)) }
    LaunchedEffect(ch.id) {
        items = withContext(Dispatchers.IO) {
            val id = if (ch.id.startsWith("UC")) ch.id else Library.channelId(ch.title, ch.channelUrl)
            id?.let { Tube.feed(it) } ?: emptyList()
        }
        items?.let { Playback.prefetch(it) }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White) }
            Text(ch.title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            val url = ch.channelUrl ?: "https://www.youtube.com/channel/${ch.id}"
            if (inLib) OutlinedButton(onClick = { Library.remove(ch.title); inLib = Library.has(ch.title, url) }) { Text(tr("في مكتبتك ✓", "In library ✓")) }
            else Button(onClick = { Library.add(ch.title, url); inLib = true }) { Text(tr("إضافة للمكتبة", "Add to library")) }
        }
        if (items == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        ItemList(items ?: emptyList(), open)
    }
}

@UnstableApi
@Composable
fun LibraryPage(open: (Item, List<Item>) -> Unit, openChannel: (Item) -> Unit) {
    val chans = remember { Library.channels() }
    var history by remember { mutableStateOf(false) }
    if (history) {
        BackHandler { history = false }
        ItemList(Store.history(), open) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
                IconButton(onClick = { history = false }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White) }
                Text(tr("السجل", "History"), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.fillMaxWidth().clickable { history = true }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.History, null, tint = Color.White)
                Text(tr("السجل", "History"), color = Color.White, fontSize = 16.sp, modifier = Modifier.padding(start = 14.dp))
            }
        }
        for ((sec, list) in chans.groupBy { it.section }) {
            item { Text(Library.section(sec), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 6.dp)) }
            items(list, key = { sec + it.name }) { c ->
                Text(c.name, color = Color.White, fontSize = 15.sp, modifier = Modifier.fillMaxWidth()
                    .clickable { openChannel(Item(c.name, c.name, c.name, c.url, isChannel = true)) }.padding(horizontal = 16.dp, vertical = 12.dp))
            }
        }
    }
}

@UnstableApi
@Composable
fun SettingsPage() {
    var lang by remember { mutableStateOf(Store.language) }
    var silence by remember { mutableStateOf(Store.skipSilence) }
    var next by remember { mutableStateOf(Store.autoNext) }
    var maxH by remember { mutableStateOf(Store.maxHeight) }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(tr("الإعدادات", "Settings"), color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Text(tr("اللغة · Language", "Language · اللغة"), color = Color.White, fontWeight = FontWeight.SemiBold)
        Row {
            listOf("auto" to tr("حسب الجوال", "Follow the phone"), "ar" to "العربية", "en" to "English").forEach { (k, n) ->
                FilterChip(selected = lang == k, onClick = { lang = k; Store.language = k }, label = { Text(n) }, modifier = Modifier.padding(end = 8.dp))
            }
        }
        Text(tr("تتغير بعد إعادة فتح التطبيق", "Applies after reopening the app"), color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(16.dp))
        Text(tr("أعلى جودة", "Highest quality"), color = Color.White, fontWeight = FontWeight.SemiBold)
        Row {
            listOf(0 to tr("تلقائي", "Auto"), 1080 to "1080p", 720 to "720p", 480 to "480p").forEach { (k, n) ->
                FilterChip(selected = maxH == k, onClick = { maxH = k; Store.maxHeight = k }, label = { Text(n) }, modifier = Modifier.padding(end = 8.dp))
            }
        }
        Text(tr("تلقائي: على قد شاشتك ونتك، وفك الترميز دايمًا بكرت الجوال", "Auto: fits your screen and network, always decoded by the phone's video chip"), color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tr("تخطي الصمت", "Skip silence"), color = Color.White, fontWeight = FontWeight.SemiBold)
                Text(tr("يقص فترات السكوت", "Cuts silent stretches"), color = Muted, fontSize = 12.sp)
            }
            Switch(checked = silence, onCheckedChange = { silence = it; Playback.setSkipSilence(it) })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tr("التشغيل التلقائي", "Autoplay"), color = Color.White, fontWeight = FontWeight.SemiBold)
                Text(tr("يشغّل التالي عند النهاية", "Plays the next one at the end"), color = Muted, fontSize = 12.sp)
            }
            Switch(checked = next, onCheckedChange = { next = it; Store.autoNext = it })
        }
        Spacer(Modifier.height(24.dp))
        Text("AZIZ Tube · " + tr("الإصدار ", "Version ") + BuildConfigVersion.NAME, color = Muted)
    }
}

object BuildConfigVersion {
    val NAME: String get() = try {
        App.instance.packageManager.getPackageInfo(App.instance.packageName, 0).versionName ?: "?"
    } catch (e: Exception) { "?" }
}

@UnstableApi
@Composable
fun PlayerPage(open: (Item, List<Item>) -> Unit, openChannel: (Item) -> Unit, close: () -> Unit) {
    val item by Playback.current.collectAsState()
    val info by Playback.info.collectAsState()
    val err by Playback.error.collectAsState()
    val loading by Playback.loading.collectAsState()
    val decoder by Playback.decoder.collectAsState()
    val fam by Playback.family.collectAsState()
    val h by Playback.height.collectAsState()
    val land = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var related by remember(item?.id) { mutableStateOf<List<Item>>(emptyList()) }
    var menu by remember { mutableStateOf(0) }
    LaunchedEffect(item?.id) {
        val id = item?.id ?: return@LaunchedEffect
        related = withContext(Dispatchers.IO) { Tube.related(id) }
        if (Playback.queue.none { it.id == id }) Playback.queue = listOf(item!!) + related
    }
    BackHandler { close() }
    if (land) {
        VideoSurface(Modifier.fillMaxSize().background(Color.Black))
        return
    }
    Column(Modifier.fillMaxSize().background(Bg)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)) {
            VideoSurface(Modifier.fillMaxSize())
            if (loading) CircularProgressIndicator(Modifier.align(Alignment.Center))
            IconButton(onClick = close, modifier = Modifier.align(Alignment.TopStart)) { Icon(Icons.Filled.KeyboardArrowDown, null, tint = Color.White) }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Column(Modifier.padding(12.dp)) {
                    if (err != null) Text(err!!, color = Accent, modifier = Modifier.padding(bottom = 6.dp))
                    Text(info?.title ?: item?.title ?: "", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    // What plays now: its height, its format and who decodes it (✓ the phone's video chip).
                    val hw = Codecs.hardwareName(decoder)
                    val badge = listOfNotNull(if (h > 0) "${h}p" else null, fam, if (decoder == null) null else if (hw) "✓ " + tr("كرت الجوال", "video chip") else tr("المعالج", "processor")).joinToString(" · ")
                    if (badge.isNotEmpty()) Text(badge, color = if (hw) Accent else Muted, fontSize = 12.5.sp, modifier = Modifier.padding(top = 4.dp))
                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(info?.channel ?: item?.channel ?: "", color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).clickable {
                            val cid = info?.channelId
                            if (cid != null) openChannel(Item(cid, info?.channel ?: "", info?.channel, "https://www.youtube.com/channel/$cid", isChannel = true))
                        })
                        AssistChip(onClick = { menu = 1 }, label = { Text(tr("الجودة", "Quality")) }, modifier = Modifier.padding(end = 6.dp))
                        AssistChip(onClick = { menu = 2 }, label = { Text(tr("السرعة", "Speed")) })
                    }
                }
            }
            items(related, key = { "r" + it.id }) { r -> VideoCard(r) { open(r, listOf(r) + related.filter { it.id != r.id }) } }
        }
    }
    if (menu == 1) {
        val hs = remember { Playback.heights() }
        AlertDialog(onDismissRequest = { menu = 0 }, confirmButton = {}, title = { Text(tr("الجودة", "Quality")) }, text = {
            Column {
                Text(tr("تلقائي", "Auto"), modifier = Modifier.fillMaxWidth().clickable { Playback.chooseHeight(0); menu = 0 }.padding(12.dp))
                hs.forEach { v -> Text("${v}p", modifier = Modifier.fillMaxWidth().clickable { Playback.chooseHeight(v); menu = 0 }.padding(12.dp)) }
            }
        })
    }
    if (menu == 2) {
        AlertDialog(onDismissRequest = { menu = 0 }, confirmButton = {}, title = { Text(tr("السرعة", "Speed")) }, text = {
            Column {
                listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f).forEach { v ->
                    Text(if (v == 1f) tr("عادية", "Normal") else "${v}x", modifier = Modifier.fillMaxWidth().clickable { Playback.setSpeed(v); menu = 0 }.padding(12.dp))
                }
            }
        })
    }
}
