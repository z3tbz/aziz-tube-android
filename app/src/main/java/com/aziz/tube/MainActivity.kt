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
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.verticalScroll
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
        // New videos from the library, every few hours (Android decides when, battery-friendly).
        Notices.schedule(applicationContext)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        // A newer version: downloaded quietly, then Android asks to install it over this one (once every 6 hours).
        val last = Store.readFile("update-check.txt")?.toLongOrNull() ?: 0
        if (System.currentTimeMillis() - last > 6 * 3_600_000L) {
            Store.writeFile("update-check.txt", System.currentTimeMillis().toString())
            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                val u = try { Updater.check(applicationContext) } catch (e: Exception) { null }
                if (u != null) runOnUiThread { Playback.notice.value = tr("تحديث جديد ", "New update ") + u.first; Updater.install(this@MainActivity, u.second) }
            }
        }
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
            NoticeBar()
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

// A short line at the bottom for what just happened (saved, skipped, added...), gone after 4 seconds.
@UnstableApi
@Composable
fun NoticeBar() {
    val n by Playback.notice.collectAsState()
    LaunchedEffect(n) { if (n != null) { kotlinx.coroutines.delay(4000); if (Playback.notice.value == n) Playback.notice.value = null } }
    if (n != null) Text(n!!, color = Color.White, fontSize = 13.sp, modifier = Modifier.fillMaxWidth().background(Surface).padding(horizontal = 16.dp, vertical = 8.dp))
}

@UnstableApi
@Composable
fun VideoSurface(modifier: Modifier) {
    AndroidView(factory = { c ->
        PlayerView(c).apply {
            player = Playback.player
            // One play/pause in the middle, the next video beside it; the gear holds quality and speed (one place).
            setShowSubtitleButton(true)
            setShowNextButton(true)
            setShowPreviousButton(false)
            setShowRewindButton(false)
            setShowFastForwardButton(false)
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

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@UnstableApi
@Composable
fun VideoCard(i: Item, width: androidx.compose.ui.unit.Dp? = null, onClick: () -> Unit) {
    val watched = remember(i.id) { Store.watched.contains(i.id) }
    val resume = remember(i.id) { Store.resume(i.id) }
    var menu by remember { mutableStateOf(false) }
    val base = if (width != null) Modifier.width(width).padding(end = 10.dp) else Modifier.fillMaxWidth()
    Column(base.combinedClickable(onClick = onClick, onLongClick = { menu = true }).padding(bottom = 14.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Surface)) {
            AsyncImage(model = i.picture, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            if (i.duration != null) Text(i.duration, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).background(Color(0xCC000000), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
            // The red line: how much was watched.
            val done = if (watched) 1f else if (resume > 0) (resume / 1000f / Hide.seconds(i.duration).coerceAtLeast(1)).coerceIn(0.03f, 1f) else 0f
            if (done > 0) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(done).height(4.dp).background(Accent))
        }
        Text(i.title, color = Color.White, fontSize = if (width != null) 13.5.sp else 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = if (width != null) 0.dp else 12.dp, end = 12.dp, top = 8.dp))
        val line = listOfNotNull(i.channel, i.views, ago(i.published)).joinToString(" · ")
        if (line.isNotEmpty()) Text(line, color = Muted, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = if (width != null) 0.dp else 12.dp, vertical = 2.dp))
        ItemMenu(i, menu) { menu = false }
    }
}

// The long-press menu of a video.
@UnstableApi
@Composable
fun ItemMenu(i: Item, open: Boolean, close: () -> Unit) {
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var lists by remember { mutableStateOf(false) }
    DropdownMenu(expanded = open, onDismissRequest = close) {
        @Composable fun item(t: String, f: () -> Unit) = DropdownMenuItem(text = { Text(t) }, onClick = { close(); f() })
        item(tr("شغّله بعد هذا", "Play next")) { Playback.enqueue(i) }
        item(tr("شاهد لاحقًا", "Watch later")) { Lists.add(Lists.LATER, i); Playback.notice.value = tr("أُضيف إلى «شاهد لاحقًا»", "Added to «Watch later»") }
        item(tr("أضف إلى قائمة…", "Add to a list…")) { lists = true }
        item(tr("حفظ صوت", "Save audio")) { save(ctx, scope, i, false) }
        item(tr("حفظ صوت وصورة", "Save video and audio")) { save(ctx, scope, i, true) }
        item(tr("لا يهمني", "Not interested")) { Hide.notInterested(i.id); Playback.notice.value = tr("ما راح يظهر هذا المقطع", "This video won't show again") }
        if (i.channel != null) item(tr("حظر القناة", "Block channel")) { Hide.block(i.channel); Playback.notice.value = tr("حُظرت ", "Blocked ") + i.channel }
    }
    if (lists) ListPicker(i) { lists = false }
}

@Composable
fun ListPicker(i: Item, close: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = close, title = { Text(tr("أضف إلى قائمة", "Add to a list")) }, confirmButton = {
        TextButton(onClick = { if (name.isNotBlank()) { Lists.add(name.trim(), i); close() } }) { Text(tr("قائمة جديدة", "New list")) }
    }, text = {
        Column {
            Lists.all().keys.forEach { n -> Text(Lists.name(n), modifier = Modifier.fillMaxWidth().clickable { Lists.add(n, i); close() }.padding(12.dp)) }
            OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, placeholder = { Text(tr("اسم قائمة جديدة", "New list name")) })
        }
    })
}

// Saving in the background, its progress as a notice.
@UnstableApi
fun save(ctx: android.content.Context, scope: kotlinx.coroutines.CoroutineScope, i: Item, video: Boolean) {
    scope.launch(Dispatchers.IO) {
        try {
            Playback.notice.value = tr("الحفظ بدأ…", "Saving…")
            val v = Direct.read(i.id)
            var last = -1
            val p: (Int) -> Unit = { n -> if (n / 10 != last) { last = n / 10; Playback.notice.value = tr("الحفظ ", "Saving ") + "$n%" } }
            if (video) Saver.video(ctx, v, p) else Saver.audio(ctx, v, p)
            Playback.notice.value = tr("انحفظ · ", "Saved · ") + v.title
            Notices.show(ctx, 9, tr("تم الحفظ", "Saved"), v.title)
        } catch (e: Exception) {
            Playback.notice.value = tr("فشل الحفظ: ", "Couldn't save: ") + (e.message ?: "")
        }
    }
}

// A row of cards across (the home page's rows).
@UnstableApi
@Composable
fun Shelf(title: String, items: List<Item>, open: (Item, List<Item>) -> Unit) {
    if (items.isEmpty()) return
    Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp))
    androidx.compose.foundation.lazy.LazyRow(contentPadding = PaddingValues(horizontal = 16.dp)) {
        items(items, key = { "s" + title + it.id }) { i -> VideoCard(i, 220.dp) { open(i, items) } }
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
            if (i.isChannel) ChannelRow(i) { open(i, items) } else VideoCard(i, null) { open(i, items) }
        }
    }
}

// The views as a number ("120K views" → 120000), for "this week's best".
fun viewsOf(s: String?): Double {
    val m = Regex("([0-9.]+)([KM]?)").find(s ?: "") ?: return 0.0
    return (m.groupValues[1].toDoubleOrNull() ?: 0.0) * when (m.groupValues[2]) { "K" -> 1e3; "M" -> 1e6; else -> 1.0 }
}

@UnstableApi
@Composable
fun HomePage(open: (Item, List<Item>) -> Unit) {
    var all by remember { mutableStateOf(Library.cached().first) }
    var busy by remember { mutableStateOf(false) }
    var shelves by remember { mutableStateOf<List<Pair<String, List<Item>>>>(emptyList()) }
    val scope = rememberCoroutineScope()
    // The page: what is hidden left out; the favorite channels first, then the channels watched lately.
    val items = remember(all, busy) {
        val fav = Taste.favorites.toSet()
        val recent = Store.history().take(40).mapNotNull { it.channel }.groupingBy { it }.eachCount()
        Hide.allowed(all).filter { !(Store.hideWatched && it.id in Store.watched) }
            .withIndex().sortedWith(compareByDescending<IndexedValue<Item>> { if (it.value.channel in fav) 2 else if ((recent[it.value.channel] ?: 0) >= 2) 1 else 0 }.thenBy { it.index })
            .map { it.value }
    }
    suspend fun rows() {
        val r = withContext(Dispatchers.IO) {
            val feed = all
            val fav = Taste.favorites.toSet()
            val week = System.currentTimeMillis() - 7 * 86_400_000L
            val ids = feed.mapNotNull { it.channelUrl?.substringAfterLast('/') }.toSet()
            listOf(
                tr("من قنواتك المفضلة", "From your favorite channels") to feed.filter { it.channel in fav && it.id !in Store.watched }.take(12),
                tr("أكمل المشاهدة", "Continue watching") to Store.history().filter { Store.resume(it.id) > 0 }.take(12),
                tr("تابع السلسلة", "Continue the series") to Series.following(feed).take(12),
                (if (Taste.clubs.size > 1) tr("أنديتك", "Your clubs") else tr("ناديك", "Your club")) to try { Taste.clubRow() } catch (e: Exception) { emptyList() },
                tr("الأبرز هذا الأسبوع", "This week's best") to feed.filter { it.published > week }.sortedByDescending { viewsOf(it.views) }.take(8).let { if (it.size >= 3) it else emptyList() },
                tr("اكتشف على ذوقك", "Discover for you") to try { Taste.discoverRow(ids) } catch (e: Exception) { emptyList() },
            ).map { (t, l) -> t to Hide.allowed(l) }.filter { it.second.isNotEmpty() }
        }
        shelves = r
    }
    val refresh = {
        if (!busy) {
            busy = true
            scope.launch {
                val fresh = Library.refresh()
                if (fresh.isNotEmpty()) all = fresh
                busy = false
                rows()
                Playback.prefetch(items)
            }
        }
    }
    LaunchedEffect(Unit) {
        val (_, at) = Library.cached()
        Playback.prefetch(items)
        rows()
        if (all.isEmpty() || System.currentTimeMillis() - at > 30 * 60_000) refresh()
    }
    ItemList(items, open) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("AZIZ", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(" Tube", color = Accent, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else IconButton(onClick = { refresh() }) { Icon(Icons.Filled.Refresh, tr("تحديث", "Refresh"), tint = Color.White) }
        }
        shelves.forEach { (t, l) -> Shelf(t, l, open) }
        if (items.isNotEmpty()) Text(tr("الجديد من مكتبتك", "New from your library"), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp))
        if (items.isEmpty() && !busy) Text(tr("ما فيه مقاطع للحين، اضغط تحديث", "No videos yet, tap refresh"), color = Muted, modifier = Modifier.padding(16.dp))
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
    var openList by remember { mutableStateOf<String?>(null) }
    if (openList != null) {
        BackHandler { openList = null }
        val l = Lists.all()[openList!!] ?: mutableListOf()
        ItemList(l, open) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
                IconButton(onClick = { openList = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White) }
                Text(Lists.name(openList!!), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (openList != Lists.LATER) TextButton(onClick = { Lists.delete(openList!!); openList = null }) { Text(tr("حذف القائمة", "Delete list")) }
            }
        }
        return
    }
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
        item { Text(tr("قوائمي", "My lists"), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 6.dp)) }
        items(Lists.all().entries.toList(), key = { "l" + it.key }) { (n, l) ->
            Text(Lists.name(n) + "  (" + l.size + ")", color = Color.White, fontSize = 15.sp, modifier = Modifier.fillMaxWidth().clickable { openList = n }.padding(horizontal = 16.dp, vertical = 12.dp))
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

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun Chips(options: List<Pair<String, String>>, selected: (String) -> Boolean, pick: (String) -> Unit) {
    androidx.compose.foundation.layout.FlowRow {
        options.forEach { (k, n) -> FilterChip(selected = selected(k), onClick = { pick(k) }, label = { Text(n) }, modifier = Modifier.padding(end = 8.dp)) }
    }
}

@Composable
fun Toggle(name: String, about: String, value: Boolean, set: (Boolean) -> Unit) {
    var on by remember { mutableStateOf(value) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
        Column(Modifier.weight(1f)) {
            Text(name, color = Color.White, fontWeight = FontWeight.SemiBold)
            Text(about, color = Muted, fontSize = 12.sp)
        }
        Switch(checked = on, onCheckedChange = { on = it; set(it) })
    }
}

@Composable
fun Heading(t: String) = Text(t, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@UnstableApi
@Composable
fun SettingsPage() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var lang by remember { mutableStateOf(Store.language) }
    var maxH by remember { mutableStateOf(Store.maxHeight) }
    var clubs by remember { mutableStateOf(Taste.clubs) }
    var favs by remember { mutableStateOf(Taste.favorites) }
    var likes by remember { mutableStateOf(Taste.interests) }
    var length by remember { mutableStateOf(Hide.lengthFilter) }
    var blocked by remember { mutableStateOf(Hide.blocked.toList()) }
    var msg by remember { mutableStateOf<String?>(null) }
    val backup = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip")) { u ->
        if (u != null) msg = tr("انحفظت النسخة الاحتياطية · ", "Backup saved · ") + Backup.save(ctx, u) + tr(" ملف", " files")
    }
    val restore = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) msg = tr("استُرجع ", "Restored ") + Backup.restore(ctx, u) + tr(" ملف · أعد فتح التطبيق", " files · reopen the app")
    }
    val import = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) msg = tr("أُضيفت ", "Added ") + Backup.import(ctx, u) + tr(" قناة لمكتبتك", " channels to your library")
    }
    androidx.compose.foundation.rememberScrollState().let { sc ->
    Column(Modifier.fillMaxSize().verticalScroll(sc).padding(16.dp)) {
        Text(tr("الإعدادات", "Settings"), color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        if (msg != null) Text(msg!!, color = Accent, modifier = Modifier.padding(top = 8.dp))

        Heading(tr("ذوقك", "Your taste"))
        Text(tr("أنديتك المفضلة · ناديين بالكثير", "Your favorite clubs · up to two"), color = Color.White, fontWeight = FontWeight.SemiBold)
        Taste.leagues.forEach { l ->
            Text(tr(l[0], l[1]), color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            Chips((2 until l.size step 2).map { l[it] to tr(l[it], l[it + 1]) }, { it in clubs }) { c ->
                clubs = if (c in clubs) clubs - c else if (clubs.size < 2) clubs + c else clubs
                Taste.clubs = clubs
            }
        }
        Text(tr("قنواتك المفضلة · اختر 3", "Your favorite channels · pick 3"), color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
        Chips(Library.channels().map { it.name to it.name }.distinct(), { it in favs }) { c ->
            favs = if (c in favs) favs - c else if (favs.size < 3) favs + c else favs
            Taste.favorites = favs
        }
        Text(tr("وش تحب تشوف؟", "What do you like to watch?"), color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
        Chips(Taste.topics.map { it[0] to tr(it[1], it[2]) }, { it in likes }) { k -> likes = if (k in likes) likes - k else likes + k; Taste.interests = likes }

        Heading(tr("التشغيل", "Playback"))
        Text(tr("أعلى جودة", "Highest quality"), color = Color.White, fontWeight = FontWeight.SemiBold)
        Chips(listOf("0" to tr("تلقائي", "Auto"), "1080" to "1080p", "720" to "720p", "480" to "480p"), { it == maxH.toString() }) { maxH = it.toInt(); Store.maxHeight = maxH }
        Toggle(tr("توفير البيانات", "Data saver"), tr("480p بالكثير على بيانات الجوال", "480p at most on mobile data"), Store.dataSaver) { Store.dataSaver = it }
        Toggle(tr("توحيد مستوى الصوت", "Even volume"), tr("المقاطع العالية تنزل لمستوى الباقي", "Loud videos brought down to the others' level"), Store.evenVolume) { Store.evenVolume = it }
        Toggle(tr("تخطي المقدمة تلقائيًا", "Skip intros automatically"), tr("يلقى المقدمة بنفسه ويتخطاها. مقفول: يطلع زر «تخطي المقدمة»", "Finds the intro by itself and skips it. Off: a «Skip intro» button shows"), Intros.auto) { Intros.auto = it }
        Toggle(tr("تخطي الصمت", "Skip silence"), tr("يقص فترات السكوت", "Cuts silent stretches"), Store.skipSilence) { Playback.setSkipSilence(it) }
        Toggle(tr("التشغيل التلقائي", "Autoplay"), tr("يشغّل التالي عند النهاية", "Plays the next one at the end"), Store.autoNext) { Store.autoNext = it }

        Heading(tr("الرئيسية والقوائم", "Home and lists"))
        Toggle(tr("إخفاء اللي شفته", "Hide what I watched"), tr("المقاطع اللي شفتها للنهاية ما تظهر في الرئيسية", "Videos watched to the end don't show on the home page"), Store.hideWatched) { Store.hideWatched = it }
        Text(tr("مدة المقاطع", "Video length"), color = Color.White, fontWeight = FontWeight.SemiBold)
        Chips(listOf("0" to tr("الكل", "All"), "1" to tr("إخفاء أقل من دقيقة", "Hide under a minute"), "2" to tr("20 دقيقة وأكثر فقط", "20 minutes and longer only")), { it == length.toString() }) { length = it.toInt(); Hide.lengthFilter = length }
        Text(tr("القنوات المحظورة", "Blocked channels"), color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
        if (blocked.isEmpty()) Text(tr("لا يوجد · اضغط مطولًا على أي مقطع: «حظر القناة»", "None · long-press any video: «Block channel»"), color = Muted, fontSize = 12.sp)
        blocked.forEach { b -> Row(verticalAlignment = Alignment.CenterVertically) { Text(b, color = Color.White, modifier = Modifier.weight(1f)); TextButton(onClick = { Hide.unblock(b); blocked = Hide.blocked.toList() }) { Text(tr("إلغاء الحظر", "Unblock")) } } }

        Heading(tr("النظام", "System"))
        Text(tr("اللغة · Language", "Language · اللغة"), color = Color.White, fontWeight = FontWeight.SemiBold)
        Chips(listOf("auto" to tr("حسب الجوال", "Follow the phone"), "ar" to "العربية", "en" to "English"), { it == lang }) { lang = it; Store.language = it }
        Text(tr("تتغير بعد إعادة فتح التطبيق", "Applies after reopening the app"), color = Muted, fontSize = 12.sp)
        androidx.compose.foundation.layout.FlowRow(Modifier.padding(top = 10.dp)) {
            OutlinedButton(onClick = {
                msg = tr("يتحقق من التحديث…", "Checking for updates…")
                scope.launch {
                    val u = withContext(Dispatchers.IO) { try { Updater.check(ctx) } catch (e: Exception) { null } }
                    if (u != null) { msg = tr("الإصدار ", "Version ") + u.first + tr(" جاهز", " is ready"); Updater.install(ctx, u.second) } else msg = tr("عندك أحدث إصدار", "You have the latest version")
                }
            }, modifier = Modifier.padding(end = 8.dp)) { Text(tr("تحقق من التحديث", "Check for updates")) }
            OutlinedButton(onClick = { backup.launch("AZIZ-Tube-backup.zip") }, modifier = Modifier.padding(end = 8.dp)) { Text(tr("نسخة احتياطية", "Backup")) }
            OutlinedButton(onClick = { restore.launch(arrayOf("application/zip", "*/*")) }, modifier = Modifier.padding(end = 8.dp)) { Text(tr("استرجاع", "Restore")) }
            OutlinedButton(onClick = { import.launch(arrayOf("text/*", "*/*")) }) { Text(tr("استيراد اشتراكات يوتيوب", "Import YouTube subscriptions")) }
        }
        Text(tr("الاشتراكات من Google Takeout: ملف subscriptions.csv", "Subscriptions from Google Takeout: the subscriptions.csv file"), color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(24.dp))
        Text("AZIZ Tube · " + tr("الإصدار ", "Version ") + BuildConfigVersion.NAME, color = Muted)
    }
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
    val intro by Playback.introEnd.collectAsState()
    val chapters by Playback.chapters.collectAsState()
    var more by remember(item?.id) { mutableStateOf(false) }
    var comments by remember(item?.id) { mutableStateOf<List<Comments.Comment>?>(null) }
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current
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
            // Skip the opening, as you did in the last part.
            if (intro > 0 && !Intros.auto) Button(onClick = { Playback.skipIntro() }, modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp)) { Text(tr("تخطي المقدمة", "Skip intro")) }
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
                    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                    androidx.compose.foundation.layout.FlowRow(Modifier.padding(top = 8.dp)) {
                        val v = info
                        val inLib = v?.channel != null && Library.has(v.channel, "https://www.youtube.com/channel/${v.channelId}")
                        AssistChip(onClick = {
                            if (v?.channel != null) { if (inLib) Library.remove(v.channel) else Library.add(v.channel, "https://www.youtube.com/channel/${v.channelId}") }
                            Playback.notice.value = if (inLib) tr("أُزيلت من المكتبة", "Removed from the library") else tr("أُضيفت للمكتبة", "Added to the library")
                        }, label = { Text(if (inLib) tr("في مكتبتك ✓", "In library ✓") else tr("إضافة للمكتبة", "Add to library")) }, modifier = Modifier.padding(end = 6.dp))
                        AssistChip(onClick = { item?.let { Lists.add(Lists.LATER, it); Playback.notice.value = tr("أُضيف إلى «شاهد لاحقًا»", "Added to «Watch later»") } }, label = { Text(tr("شاهد لاحقًا", "Watch later")) }, modifier = Modifier.padding(end = 6.dp))
                        AssistChip(onClick = { item?.let { save(ctx, scope, it, false) } }, label = { Text(tr("حفظ صوت", "Save audio")) }, modifier = Modifier.padding(end = 6.dp))
                        AssistChip(onClick = { item?.let { save(ctx, scope, it, true) } }, label = { Text(tr("حفظ فيديو", "Save video")) }, modifier = Modifier.padding(end = 6.dp))
                        AssistChip(onClick = { menu = 3 }, label = { Text(tr("مؤقت النوم", "Sleep timer")) }, modifier = Modifier.padding(end = 6.dp))
                        AssistChip(onClick = {
                            val share = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "https://youtu.be/${item?.id}?t=${Playback.player.currentPosition / 1000}")
                            ctx.startActivity(Intent.createChooser(share, null))
                        }, label = { Text(tr("مشاركة", "Share")) })
                    }
                    // Chapters: a tap jumps there.
                    if (chapters.isNotEmpty()) {
                        Text(tr("الفصول", "Chapters"), color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                        chapters.forEach { (t, name) ->
                            Text((t / 1000).let { "%d:%02d".format(it / 60, it % 60) } + "   " + name, color = Color.White, fontSize = 13.5.sp,
                                modifier = Modifier.fillMaxWidth().clickable { Playback.player.seekTo(t) }.padding(vertical = 6.dp))
                        }
                    }
                    // The description (folded), and the comments on demand.
                    val desc = info?.description ?: ""
                    if (desc.isNotBlank()) Text(desc, color = Muted, fontSize = 13.sp, maxLines = if (more) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 10.dp).clickable { more = !more })
                    if (comments == null) TextButton(onClick = {
                        comments = emptyList()
                        scope.launch { comments = withContext(Dispatchers.IO) { item?.id?.let { Comments.load(it) } ?: emptyList() } }
                    }) { Text(tr("عرض التعليقات", "Show comments")) }
                    else {
                        Text(tr("التعليقات", "Comments"), color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
                        if (comments!!.isEmpty()) Text(tr("جارٍ التحميل…", "Loading…"), color = Muted, fontSize = 12.sp)
                        comments!!.take(20).forEach { c ->
                            Column(Modifier.padding(vertical = 6.dp)) {
                                Text(listOfNotNull(c.author, c.whenText).joinToString(" · "), color = Muted, fontSize = 12.sp)
                                Text(c.text, color = Color.White, fontSize = 13.5.sp)
                            }
                        }
                    }
                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(info?.channel ?: item?.channel ?: "", color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).clickable {
                            val cid = info?.channelId
                            if (cid != null) openChannel(Item(cid, info?.channel ?: "", info?.channel, "https://www.youtube.com/channel/$cid", isChannel = true))
                        })
                    }
                }
            }
            items(related, key = { "r" + it.id }) { r -> VideoCard(r, null) { open(r, listOf(r) + related.filter { it.id != r.id }) } }
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
    if (menu == 3) {
        AlertDialog(onDismissRequest = { menu = 0 }, confirmButton = {}, title = { Text(tr("مؤقت النوم", "Sleep timer")) }, text = {
            Column {
                listOf(15, 30, 45, 60).forEach { m -> Text(tr("$m دقيقة", "$m minutes"), modifier = Modifier.fillMaxWidth().clickable { Playback.sleep(m); menu = 0 }.padding(12.dp)) }
                Text(tr("نهاية المقطع", "End of the video"), modifier = Modifier.fillMaxWidth().clickable { Playback.sleep(-1); menu = 0 }.padding(12.dp))
                Text(tr("إلغاء المؤقت", "Cancel the timer"), modifier = Modifier.fillMaxWidth().clickable { Playback.sleep(0); menu = 0 }.padding(12.dp))
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
