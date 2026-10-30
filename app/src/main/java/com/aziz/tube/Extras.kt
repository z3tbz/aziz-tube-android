package com.aziz.tube

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

// Updates: the app looks at the release's manifest (android.json next to the Windows one), downloads a newer app in the
// background and asks Android to install it over this one (same key: nothing is lost).
object Updater {
    private const val MANIFEST = "https://github.com/z3tbz/AZIZ-Tube/raw/main/android.json"

    fun currentCode(c: Context): Long = try {
        val p = c.packageManager.getPackageInfo(c.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else @Suppress("DEPRECATION") p.versionCode.toLong()
    } catch (e: Exception) { 0 }

    // A newer version's name and file, or null.
    fun check(c: Context): Pair<String, File>? {
        val m = try { JSONObject(Net.get(MANIFEST) ?: return null) } catch (e: Exception) { return null }
        if (m.optLong("versionCode") <= currentCode(c)) return null
        val f = File(c.cacheDir, "update.apk")
        Net.http.newCall(Request.Builder().url(m.getString("apk")).build()).execute().use { r ->
            if (!r.isSuccessful) return null
            r.body?.byteStream()?.use { i -> f.outputStream().use { i.copyTo(it) } } ?: return null
        }
        return m.optString("name") to f
    }

    fun install(c: Context, f: File) {
        val uri = FileProvider.getUriForFile(c, c.packageName + ".files", f)
        c.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

// Notices: new videos from the library (checked every 6 hours by Android, battery-friendly), and saves.
object Notices {
    private const val CHANNEL = "aziz"

    fun channel(c: Context) {
        if (Build.VERSION.SDK_INT >= 26) c.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "AZIZ Tube", NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun show(c: Context, id: Int, title: String, text: String) {
        channel(c)
        val open = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        try {
            c.getSystemService(NotificationManager::class.java).notify(id, NotificationCompat.Builder(c, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_more).setContentTitle(title).setContentText(text).setContentIntent(open).setAutoCancel(true).build())
        } catch (_: SecurityException) {}
    }

    fun schedule(c: Context) {
        WorkManager.getInstance(c).enqueueUniquePeriodicWork("new-videos", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<NewVideos>(6, TimeUnit.HOURS).build())
    }

    class NewVideos(c: Context, p: WorkerParameters) : CoroutineWorker(c, p) {
        override suspend fun doWork(): Result {
            val before = Store.readFile("notified.txt")?.toLongOrNull() ?: 0
            val list = Library.refresh()
            val newest = list.maxOfOrNull { it.published } ?: return Result.success()
            val fresh = list.filter { it.published > before }
            if (before > 0 && fresh.isNotEmpty()) {
                val first = fresh.maxBy { it.published }
                show(applicationContext, 7, tr("جديد في مكتبتك", "New in your library"),
                    if (fresh.size == 1) "${first.channel}: ${first.title}" else tr("${fresh.size} مقاطع جديدة · ", "${fresh.size} new videos · ") + first.title)
            }
            Store.writeFile("notified.txt", newest.toString())
            return Result.success()
        }
    }
}

// Saving a video: its sound as it is (no re-encoding) to Music, or picture and sound put together (H.264 + AAC, no
// re-encoding) to Movies. Read in 8 MB pieces (YouTube slows one long read down to play speed).
object Saver {
    private fun fetch(url: String, to: File, progress: (Int) -> Unit, total: Long) {
        var at = 0L
        to.outputStream().use { out ->
            while (true) {
                val end = at + 8 * 1024 * 1024 - 1
                val r = Net.http.newCall(Request.Builder().url(url).header("User-Agent", Direct.AGENT).header("Range", "bytes=$at-$end").build()).execute()
                val n = r.use { resp ->
                    if (resp.code != 206 && resp.code != 200) throw Exception("HTTP ${resp.code}")
                    resp.body?.byteStream()?.use { it.copyTo(out) } ?: 0
                }
                if (n <= 0) break
                at += n
                if (total > 0) progress((at * 100 / total).toInt())
                if (total in 1..at || n < 8 * 1024 * 1024) break
            }
        }
    }

    private fun publish(c: Context, file: File, name: String, mime: String, music: Boolean) {
        val v = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.MediaColumns.RELATIVE_PATH, (if (music) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES) + "/AZIZ Tube")
        }
        val col = if (music) MediaStore.Audio.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val uri = c.contentResolver.insert(col, v) ?: throw Exception("no place to save")
        c.contentResolver.openOutputStream(uri)?.use { o -> file.inputStream().use { it.copyTo(o) } }
    }

    private fun safe(s: String) = s.replace(Regex("[\\\\/:*?\"<>|]"), " ").trim().take(80)

    fun audio(c: Context, v: Video, progress: (Int) -> Unit) {
        val a = v.streams.filter { !it.video && it.mime == "audio/mp4" && it.original }.maxByOrNull { it.bitrate }
            ?: v.streams.filter { !it.video && it.mime == "audio/mp4" }.maxByOrNull { it.bitrate } ?: throw Exception("no sound")
        val f = File(c.cacheDir, "save.m4a")
        fetch(a.url, f, progress, a.contentLength)
        publish(c, f, safe(v.title) + ".m4a", "audio/mp4", true)
        f.delete()
    }

    fun video(c: Context, v: Video, progress: (Int) -> Unit) {
        val pic = v.streams.filter { it.video && it.mime == "video/mp4" && it.codecs.startsWith("avc1") && it.height <= 1080 }.maxByOrNull { it.height * 1000 + it.fps }
            ?: throw Exception("no H.264 picture")
        val snd = v.streams.filter { !it.video && it.mime == "audio/mp4" && it.original }.maxByOrNull { it.bitrate }
            ?: v.streams.filter { !it.video && it.mime == "audio/mp4" }.maxByOrNull { it.bitrate } ?: throw Exception("no sound")
        val vf = File(c.cacheDir, "save-v.mp4")
        val af = File(c.cacheDir, "save-a.m4a")
        val total = pic.contentLength + snd.contentLength
        fetch(pic.url, vf, { progress((it * pic.contentLength / total.coerceAtLeast(1)).toInt()) }, pic.contentLength)
        fetch(snd.url, af, { progress(((pic.contentLength + it * snd.contentLength / 100) * 100 / total.coerceAtLeast(1)).toInt()) }, snd.contentLength)
        val out = File(c.cacheDir, "save.mp4")
        val mux = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val ex = listOf(vf, af).map { f -> MediaExtractor().apply { setDataSource(f.path) } }
        val tracks = ex.map { e -> e.selectTrack(0); mux.addTrack(e.getTrackFormat(0)) }
        mux.start()
        val buf = ByteBuffer.allocate(4 * 1024 * 1024)
        val info = MediaCodec.BufferInfo()
        ex.forEachIndexed { k, e ->
            while (true) {
                val size = e.readSampleData(buf, 0)
                if (size < 0) break
                info.set(0, size, e.sampleTime, if (e.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                mux.writeSampleData(tracks[k], buf, info)
                e.advance()
            }
            e.release()
        }
        mux.stop()
        mux.release()
        publish(c, out, safe(v.title) + ".mp4", "video/mp4", false)
        listOf(vf, af, out).forEach { it.delete() }
    }
}

// Backup and restore of everything kept (library, lists, history, settings), as one file.
object Backup {
    fun save(c: Context, to: Uri): Int {
        var n = 0
        c.contentResolver.openOutputStream(to)?.use { o ->
            ZipOutputStream(o).use { z ->
                val prefs = File(c.applicationInfo.dataDir, "shared_prefs/aziz.xml")
                for (f in (c.filesDir.listFiles() ?: emptyArray()).filter { it.isFile } + listOf(prefs).filter { it.exists() }) {
                    z.putNextEntry(ZipEntry(if (f == prefs) "prefs/aziz.xml" else f.name))
                    f.inputStream().use { it.copyTo(z) }
                    z.closeEntry()
                    n++
                }
            }
        }
        return n
    }

    fun restore(c: Context, from: Uri): Int {
        var n = 0
        c.contentResolver.openInputStream(from)?.use { i ->
            ZipInputStream(i).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    val name = File(e.name).name
                    val to = if (e.name.startsWith("prefs/")) File(c.applicationInfo.dataDir, "shared_prefs/$name") else File(c.filesDir, name)
                    to.parentFile?.mkdirs()
                    to.outputStream().use { z.copyTo(it) }
                    n++
                }
            }
        }
        return n
    }

    // YouTube's subscriptions file (Google Takeout, subscriptions.csv): its channels added to the library.
    fun import(c: Context, from: Uri): Int {
        val text = c.contentResolver.openInputStream(from)?.bufferedReader()?.readText() ?: return 0
        var n = 0
        for (line in text.lines().drop(1)) {
            val parts = line.split(",")
            if (parts.size < 3) continue
            val url = parts[1].trim().replace("http://", "https://")
            val name = parts.drop(2).joinToString(",").trim().trim('"')
            if (url.contains("/channel/") && name.isNotEmpty() && !Library.has(name, url)) { Library.add(name, url); n++ }
        }
        return n
    }
}

// The connection is metered (mobile data): pictures kept at 480p at most when the data saver says so.
object Data {
    fun metered(c: Context) = try { c.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered } catch (e: Exception) { false }
}
