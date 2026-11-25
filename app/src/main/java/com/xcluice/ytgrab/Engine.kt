package com.xcluice.ytgrab

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLException

private data class SavedReq(
    val url: String,
    val title: String,
    val thumb: String?,
    val height: Int?,
    val startSec: Long,
    val info: VideoInfo? = null,
)

object Engine {
    lateinit var app: Application
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var running: Job? = null
    private var lastReq: SavedReq? = null
    private var pausing = false

    val job = MutableStateFlow(JobState())
    val items = MutableStateFlow<List<DlItem>>(emptyList())
    val autoGallery = MutableStateFlow(false)

    val dir: File
        get() = File(app.filesDir, "videos").apply {
            mkdirs()
            runCatching { File(this, ".nomedia").createNewFile() }
        }

    private val index get() = File(dir, "index.json")
    private val pausedFile get() = File(dir, "paused.json")
    private fun prefs() = app.getSharedPreferences("ytgrab_prefs", Context.MODE_PRIVATE)

    fun setAutoGallery(enabled: Boolean) {
        autoGallery.value = enabled
        runCatching { prefs().edit().putBoolean("auto_gallery", enabled).apply() }
    }

    private fun partialDir(url: String, height: Int?, startSec: Long): File {
        val key = "$url|${height ?: 0}|$startSec".hashCode().toUInt().toString(16)
        return File(app.filesDir, "partial_$key")
    }

    private fun cleanOtherPartials(keep: File?) {
        app.filesDir.listFiles()?.filter { it.name.startsWith("partial_") && it != keep }
            ?.forEach { it.deleteRecursively() }
    }

    private fun fmtSpeed(bps: Long): String = when {
        bps >= 1_048_576L -> String.format(Locale.US, "%.1f MB/s", bps / 1_048_576.0)
        bps >= 1024L -> "${bps / 1024L} KB/s"
        else -> ""
    }

    private fun savePaused(req: SavedReq, progress: Float, reason: String?) {
        runCatching {
            pausedFile.writeText(
                JSONObject()
                    .put("url", req.url)
                    .put("title", req.title)
                    .put("thumb", req.thumb ?: "")
                    .put("height", req.height ?: 0)
                    .put("startSec", req.startSec)
                    .put("progress", progress.toDouble())
                    .put("reason", reason ?: "")
                    .toString()
            )
        }
    }

    private fun clearPaused() {
        runCatching { pausedFile.delete() }
    }

    fun init(application: Application) {
        app = application
        autoGallery.value = runCatching { prefs().getBoolean("auto_gallery", false) }.getOrDefault(false)
        items.value = runCatching {
            val arr = JSONArray(index.readText())
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                DlItem(
                    o.getString("id"), o.getString("title"), o.getString("file"),
                    o.optString("thumb").ifEmpty { null }, o.getString("label"),
                    o.getLong("bytes"), o.getLong("at"),
                )
            }.filter { File(dir, it.file).exists() }
        }.getOrDefault(emptyList())

        // Restore any paused download from disk so user can Resume even after closing the app.
        runCatching {
            if (pausedFile.exists()) {
                val o = JSONObject(pausedFile.readText())
                val url = o.getString("url")
                val title = o.getString("title")
                val thumb = o.optString("thumb").ifEmpty { null }
                val h = o.optInt("height", 0).takeIf { it > 0 }
                val startSec = o.optLong("startSec", 0L)
                val progress = o.optDouble("progress", 0.0).toFloat()
                val reason = o.optString("reason").ifEmpty { null }
                val work = partialDir(url, h, startSec)
                if (work.exists()) {
                    lastReq = SavedReq(url, title, thumb, h, startSec, info = null)
                    job.value = JobState(
                        active = false,
                        paused = true,
                        title = title,
                        phase = "Paused — progress saved",
                        progress = progress,
                        error = reason,
                    )
                } else {
                    clearPaused()
                }
            }
        }
    }

    private fun save() {
        val arr = JSONArray()
        items.value.forEach {
            arr.put(JSONObject().put("id", it.id).put("title", it.title).put("file", it.file)
                .put("thumb", it.thumb ?: "").put("label", it.label).put("bytes", it.bytes).put("at", it.at))
        }
        runCatching { index.writeText(arr.toString()) }
    }

    fun delete(item: DlItem) {
        File(dir, item.file).delete()
        item.thumb?.let { File(dir, it).delete() }
        items.update { l -> l.filter { it.id != item.id } }
        save()
    }

    /** Copies a private download into public Movies/Ytgrab (or Music/Ytgrab) so it appears in Gallery. */
    suspend fun exportToGallery(ctx: Context, item: DlItem): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val src = File(dir, item.file)
            if (!src.exists()) error("File not found")
            val isAudio = item.label == "Audio"
            val ext = if (isAudio) "m4a" else "mp4"
            val mime = if (isAudio) "audio/mp4" else "video/mp4"
            val safeName = item.title
                .replace(Regex("[^a-zA-Z0-9._ -]"), "")
                .trim()
                .take(60)
                .ifEmpty { "Ytgrab_${item.id}" }
            val displayName = "$safeName.$ext"

            if (Build.VERSION.SDK_INT >= 29) {
                val relDir = (if (isAudio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES) + "/Ytgrab"
                val collection = if (isAudio) MediaStore.Audio.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relDir)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val resolver = ctx.contentResolver
                val uri = resolver.insert(collection, values) ?: error("Couldn't create gallery entry")
                try {
                    resolver.openOutputStream(uri)?.use { out ->
                        src.inputStream().use { inp -> inp.copyTo(out, 64 * 1024) }
                    } ?: error("Couldn't write gallery file")
                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                } catch (e: Throwable) {
                    runCatching { resolver.delete(uri, null, null) }
                    throw e
                }
                if (isAudio) "Saved to Music/Ytgrab" else "Saved to Gallery"
            } else {
                @Suppress("DEPRECATION")
                val pubRoot = Environment.getExternalStoragePublicDirectory(
                    if (isAudio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES
                )
                val outDir = File(pubRoot, "Ytgrab").apply { mkdirs() }
                var dst = File(outDir, displayName)
                if (dst.exists()) dst = File(outDir, "${safeName}_${item.id}.$ext")
                src.copyTo(dst, overwrite = true)
                MediaScannerConnection.scanFile(ctx, arrayOf(dst.absolutePath), arrayOf(mime), null)
                if (isAudio) "Saved to Music/Ytgrab" else "Saved to Gallery"
            }
        }
    }

    fun ack() {
        if (!job.value.active) {
            if (!job.value.paused) {
                lastReq = null
                clearPaused()
            }
            job.value = JobState()
        }
    }

    fun pause() {
        if (!job.value.active) return
        pausing = true
        running?.cancel()
    }

    fun cancel() {
        pausing = false
        running?.cancel()
        lastReq?.let { partialDir(it.url, it.height, it.startSec).deleteRecursively() }
        lastReq = null
        clearPaused()
        job.value = JobState()
    }

    fun resume() {
        val r = lastReq ?: return
        launchReq(r, refreshUrls = true)
    }

    fun start(info: VideoInfo, height: Int?, startSec: Long) {
        val req = SavedReq(info.url, info.title, info.thumb, height, startSec, info = info)
        launchReq(req, refreshUrls = false)
    }

    private fun launchReq(req: SavedReq, refreshUrls: Boolean) {
        if (job.value.active) return
        pausing = false
        val work = partialDir(req.url, req.height, req.startSec).apply { mkdirs() }
        cleanOtherPartials(keep = work)
        lastReq = req
        val prevProgress = if (job.value.paused && job.value.title == req.title) job.value.progress else 0f
        job.value = JobState(
            active = true,
            title = req.title,
            phase = if (prevProgress > 0f) "Resuming" else "Starting",
            progress = prevProgress,
        )
        runCatching { ContextCompat.startForegroundService(app, Intent(app, DownloadService::class.java)) }
        running = scope.launch {
            try {
                val activeInfo = if (refreshUrls || req.info == null) {
                    Extractor.fetch(req.url)
                } else req.info
                val updated = req.copy(info = activeInfo)
                lastReq = updated
                run(activeInfo, req.height, req.startSec, work)
                work.deleteRecursively()
                clearPaused()
                lastReq = null
            } catch (e: CancellationException) {
                if (pausing) {
                    pausing = false
                    val p = job.value.progress
                    savePaused(req, p, null)
                    job.update {
                        it.copy(
                            active = false,
                            paused = true,
                            phase = "Paused — progress saved",
                            speed = "",
                            error = null,
                        )
                    }
                } else {
                    work.deleteRecursively()
                    clearPaused()
                    job.value = JobState()
                }
                throw e
            } catch (e: IOException) {
                val p = job.value.progress
                val reason = "Connection dropped. Progress saved — tap Resume to continue."
                savePaused(req, p, reason)
                job.update {
                    it.copy(
                        active = false,
                        paused = true,
                        phase = "Paused — low network",
                        speed = "",
                        error = reason,
                    )
                }
            } catch (e: Throwable) {
                work.deleteRecursively()
                clearPaused()
                lastReq = null
                job.value = JobState(error = friendly(e))
            }
        }
    }

    private fun friendly(e: Throwable): String = when {
        e is UnknownHostException || e is SocketTimeoutException || e is ConnectException || e is SSLException ->
            "Network problem. Check your connection and try again."
        e is IOException && e.message?.startsWith("HTTP ") == true ->
            "Network problem (${e.message}). Check your connection and try again."
        e.javaClass.name.contains("ReCaptcha") -> "YouTube asked for a check. Try again in a few minutes."
        e.javaClass.name.contains("extractor") ->
            e.message?.take(140) ?: "YouTube changed something. Update Ytgrab and try again."
        else -> e.message?.take(140) ?: "Something went wrong. Try again."
    }

    private fun moveToFinal(src: File, dst: File) {
        if (dst.exists()) dst.delete()
        if (!src.renameTo(dst)) {
            src.copyTo(dst, overwrite = true)
            src.delete()
        }
    }

    private suspend fun run(info: VideoInfo, height: Int?, startSec: Long, work: File) = coroutineScope {
        val plan = Extractor.plan(info, height, preferSplit = startSec > 0)
        val id = System.currentTimeMillis().toString(36)
        val ext = if (plan.video == null) "m4a" else "mp4"
        val out = File(dir, "$id.$ext")

        val thumbDeferred = async(Dispatchers.IO) {
            info.thumb?.let { u ->
                Net.bytes(u)?.let { b ->
                    File(dir, "$id.jpg").also { it.writeBytes(b) }.name
                }
            }
        }

        var partialOk = false
        if (startSec > 0) {
            job.update { it.copy(phase = "Finding your start point") }
            partialOk = try {
                runPartial(plan, startSec, work, out, ext)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                throw e
            } catch (_: Exception) {
                false
            }
        }
        if (!partialOk) runFull(plan, startSec, work, out, ext)

        val thumbName = runCatching { thumbDeferred.await() }.getOrNull()
        val label = if (height == null) "Audio" else "${height}p"
        val item = DlItem(id, info.title, out.name, thumbName, label, out.length(), System.currentTimeMillis())
        items.update { listOf(item) + it }
        save()
        if (autoGallery.value) {
            exportToGallery(app, item)
        }
        job.value = JobState(finished = true, title = info.title, progress = 1f)
    }

    private suspend fun trackSpeed(done: AtomicLong, block: suspend () -> Unit) = coroutineScope {
        val meter = launch {
            var prevBytes = done.get()
            var prevTime = SystemClock.elapsedRealtime()
            while (true) {
                delay(700)
                val now = SystemClock.elapsedRealtime()
                val curBytes = done.get()
                val dt = (now - prevTime).coerceAtLeast(1L)
                val bps = ((curBytes - prevBytes) * 1000L) / dt
                prevBytes = curBytes
                prevTime = now
                job.update { it.copy(speed = fmtSpeed(bps)) }
            }
        }
        try {
            block()
        } finally {
            meter.cancel()
            job.update { it.copy(speed = "") }
        }
    }

    /** Downloads ONLY the data from startSec onward using parallel HTTP/1.1 sockets + chunk-exact resume. */
    private suspend fun runPartial(plan: Plan, startSec: Long, work: File, out: File, ext: String): Boolean = coroutineScope {
        val metaFile = File(work, "partial_meta.json")
        val saved = runCatching { JSONObject(metaFile.readText()) }.getOrNull()

        val vLenJob = async { saved?.optLong("vLen", 0L)?.takeIf { it > 0L } ?: plan.video?.let { Net.length(it) } ?: 0L }
        val aLenJob = async { saved?.optLong("aLen", 0L)?.takeIf { it > 0L } ?: plan.audio?.let { Net.length(it) } ?: 0L }
        val vLen = vLenJob.await()
        val aLen = aLenJob.await()

        val vFile = if (plan.video != null) File(work, "v.mp4") else null
        val aFile = if (plan.audio != null) File(work, "a.m4a") else null
        val vDone = File(work, "v.done")
        val aDone = File(work, "a.done")

        val vSegJob = async {
            if (plan.video == null || vFile == null) return@async 0L to 0
            val savedOff = saved?.optLong("vOff", -1L) ?: -1L
            val savedInit = saved?.optInt("vInit", -1) ?: -1
            if (savedOff >= 0 && savedInit > 0 && vFile.exists() && vFile.length() >= savedInit && vDone.exists()) {
                savedOff to savedInit
            } else {
                val seg = Net.findStart(plan.video, vLen, startSec) ?: return@async null
                vFile.writeBytes(seg.init)
                vDone.delete()
                seg.offset to seg.init.size
            }
        }
        val aSegJob = async {
            if (plan.audio == null || aFile == null) return@async 0L to 0
            val savedOff = saved?.optLong("aOff", -1L) ?: -1L
            val savedInit = saved?.optInt("aInit", -1) ?: -1
            if (savedOff >= 0 && savedInit > 0 && aFile.exists() && aFile.length() >= savedInit && aDone.exists()) {
                savedOff to savedInit
            } else {
                val seg = Net.findStart(plan.audio, aLen, startSec) ?: return@async null
                aFile.writeBytes(seg.init)
                aDone.delete()
                seg.offset to seg.init.size
            }
        }

        val vPair = vSegJob.await() ?: return@coroutineScope false
        val aPair = aSegJob.await() ?: return@coroutineScope false
        val (vOffset, vInitSize) = vPair
        val (aOffset, aInitSize) = aPair

        runCatching {
            metaFile.writeText(
                JSONObject()
                    .put("vLen", vLen).put("aLen", aLen)
                    .put("vOff", vOffset).put("vInit", vInitSize)
                    .put("aOff", aOffset).put("aInit", aInitSize)
                    .toString()
            )
        }

        val vPayload = if (plan.video != null) (vLen - vOffset).coerceAtLeast(0L) else 0L
        val aPayload = if (plan.audio != null) (aLen - aOffset).coerceAtLeast(0L) else 0L
        val total = (vPayload + aPayload).coerceAtLeast(1L)
        val initialDone = Net.completedBytes(vDone, vPayload) + Net.completedBytes(aDone, aPayload)
        val done = AtomicLong(initialDone)
        val lastPct = AtomicInteger(((initialDone.toFloat() / total).coerceIn(0f, 1f) * 90).toInt())
        job.update {
            it.copy(
                phase = if (plan.video == null) "Downloading audio from your start" else "Downloading from your start",
                progress = lastPct.get() / 100f,
            )
        }

        fun onBytes(n: Long) {
            val cur = done.addAndGet(n)
            val p = ((cur.toFloat() / total).coerceIn(0f, 1f) * 90).toInt()
            if (p != lastPct.getAndSet(p)) {
                job.update { it.copy(progress = p / 100f) }
            }
        }

        trackSpeed(done) {
            coroutineScope {
                if (plan.video != null && vFile != null) {
                    launch {
                        Net.downloadParallel(
                            url = plan.video, file = vFile, doneFile = vDone, len = vLen,
                            from = vOffset, fileHeaderBytes = vInitSize.toLong(), workers = 6, onBytes = ::onBytes,
                        )
                    }
                }
                if (plan.audio != null && aFile != null) {
                    launch {
                        Net.downloadParallel(
                            url = plan.audio, file = aFile, doneFile = aDone, len = aLen,
                            from = aOffset, fileHeaderBytes = aInitSize.toLong(),
                            workers = if (plan.video == null) 6 else 2, onBytes = ::onBytes,
                        )
                    }
                }
            }
        }

        job.update { it.copy(phase = if (vFile != null && aFile != null) "Merging" else "Finalizing", progress = 0.9f) }
        val tmp = File(work, "out.$ext")
        remux(vFile, aFile, 0L, tmp) { p -> job.update { it.copy(progress = 0.9f + 0.1f * p) } }
        moveToFinal(tmp, out)
        true
    }

    /** Full download using multi-socket HTTP/1.1 parallel chunks + chunk-exact resume. */
    private suspend fun runFull(plan: Plan, startSec: Long, work: File, out: File, ext: String) = coroutineScope {
        val partialMeta = File(work, "partial_meta.json")
        if (partialMeta.exists()) {
            work.deleteRecursively(); work.mkdirs()
        }
        val vFile = plan.video?.let { File(work, "v.mp4") }
        val aFile = plan.audio?.let { File(work, "a.m4a") }
        val vDone = File(work, "v.done")
        val aDone = File(work, "a.done")

        val vLenJob = async { plan.video?.let { Net.length(it) } ?: 0L }
        val aLenJob = async { plan.audio?.let { Net.length(it) } ?: 0L }
        val vLen = vLenJob.await()
        val aLen = aLenJob.await()
        val total = (vLen + aLen).coerceAtLeast(1L)

        val initialDone = Net.completedBytes(vDone, vLen) + Net.completedBytes(aDone, aLen)
        val done = AtomicLong(initialDone)
        val lastPct = AtomicInteger(((initialDone.toFloat() / total).coerceIn(0f, 1f) * 90).toInt())
        job.update {
            it.copy(
                phase = if (plan.video == null) "Downloading audio" else "Downloading video",
                progress = lastPct.get() / 100f,
            )
        }

        fun onBytes(n: Long) {
            val cur = done.addAndGet(n)
            val p = ((cur.toFloat() / total).coerceIn(0f, 1f) * 90).toInt()
            if (p != lastPct.getAndSet(p)) {
                job.update { it.copy(progress = p / 100f) }
            }
        }

        trackSpeed(done) {
            coroutineScope {
                if (plan.video != null && vFile != null) {
                    launch {
                        Net.downloadParallel(
                            url = plan.video, file = vFile, doneFile = vDone, len = vLen,
                            from = 0L, fileHeaderBytes = 0L, workers = 6, onBytes = ::onBytes,
                        )
                    }
                }
                if (plan.audio != null && aFile != null) {
                    launch {
                        Net.downloadParallel(
                            url = plan.audio, file = aFile, doneFile = aDone, len = aLen,
                            from = 0L, fileHeaderBytes = 0L,
                            workers = if (plan.video == null) 6 else 2, onBytes = ::onBytes,
                        )
                    }
                }
            }
        }

        val needTransform = (vFile != null && aFile != null) || startSec > 0
        if (needTransform) {
            job.update { it.copy(phase = if (startSec > 0) "Trimming and merging" else "Merging", progress = 0.9f) }
            val tmp = File(work, "out.$ext")
            remux(vFile, aFile, startSec * 1000, tmp) { p -> job.update { it.copy(progress = 0.9f + 0.1f * p) } }
            moveToFinal(tmp, out)
        } else {
            moveToFinal((vFile ?: aFile)!!, out)
        }
    }

    private fun isSupportedMp4Mime(mime: String): Boolean = when (mime.lowercase()) {
        "video/avc", "video/hevc", "video/mp4v-es", "video/3gpp",
        "audio/mp4a-latm", "audio/3gpp", "audio/amr-wb" -> true
        else -> false
    }

    /**
     * Copies (never re-encodes) the video/audio samples into one MP4, starting from the
     * keyframe at or before startMs. Uses direct ByteBuffer for fast zero-copy remuxing.
     */
    private suspend fun remux(
        video: File?, audio: File?, startMs: Long, out: File, onProgress: (Float) -> Unit,
    ) = withContext(Dispatchers.IO) {
        class T(val ex: MediaExtractor) {
            val outIdx = HashMap<Int, Int>()
            var isVideo = false
            var maxSize = 2 shl 20
        }

        val extractors = mutableListOf<MediaExtractor>()
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false
        try {
            val sources = mutableListOf<T>()
            var durationUs = 0L
            val split = video != null && audio != null
            for (f in listOfNotNull(video, audio)) {
                val ex = MediaExtractor().apply { setDataSource(f.absolutePath) }
                extractors += ex
                val t = T(ex)
                for (i in 0 until ex.trackCount) {
                    val fmt = ex.getTrackFormat(i)
                    val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                    if (!isSupportedMp4Mime(mime)) continue
                    val isV = mime.startsWith("video/")
                    val isA = mime.startsWith("audio/")
                    val take = when {
                        split && f == video -> isV
                        split && f == audio -> isA
                        else -> isV || isA
                    }
                    if (!take) continue
                    ex.selectTrack(i)
                    if (isV) {
                        t.isVideo = true
                        if (fmt.containsKey(MediaFormat.KEY_ROTATION)) {
                            runCatching { muxer.setOrientationHint(fmt.getInteger(MediaFormat.KEY_ROTATION)) }
                        }
                    }
                    if (fmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                        t.maxSize = maxOf(t.maxSize, fmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                    }
                    if (fmt.containsKey(MediaFormat.KEY_DURATION)) {
                        durationUs = maxOf(durationUs, fmt.getLong(MediaFormat.KEY_DURATION))
                    }
                    t.outIdx[i] = muxer.addTrack(fmt)
                }
                if (t.outIdx.isNotEmpty()) sources += t
            }
            if (sources.isEmpty()) error("No compatible audio/video tracks found to merge.")

            val v = sources.firstOrNull { it.isVideo }
            val startUs = startMs * 1000
            if (startUs > 0) {
                val seekTarget = if (v != null) {
                    v.ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    v.ex.sampleTime.takeIf { it >= 0L } ?: startUs
                } else startUs
                sources.filter { it !== v }.forEach { it.ex.seekTo(seekTarget, MediaExtractor.SEEK_TO_CLOSEST_SYNC) }
            } else if (v != null && sources.size > 1) {
                val vStart = v.ex.sampleTime
                if (vStart > 0L) {
                    sources.filter { it !== v }.forEach { a ->
                        while (a.ex.sampleTime in 0L until vStart) {
                            ensureActive()
                            if (!a.ex.advance()) break
                        }
                    }
                }
            }

            val originUs = sources.map { it.ex.sampleTime }.filter { it >= 0L }.minOrNull() ?: 0L

            muxer.start(); started = true
            val buf = ByteBuffer.allocateDirect(sources.maxOf { it.maxSize })
            val bi = MediaCodec.BufferInfo()
            val total = (durationUs - originUs).coerceAtLeast(1L)
            val lastPtsByTrack = HashMap<Int, Long>()
            val monotonicFix = Build.VERSION.SDK_INT < 29
            var lastPct = -1
            while (true) {
                ensureActive()
                var next: T? = null
                var nextT = Long.MAX_VALUE
                for (t in sources) {
                    val st = t.ex.sampleTime
                    if (st >= 0 && st < nextT) { next = t; nextT = st }
                }
                val t = next ?: break
                val dst = t.outIdx[t.ex.sampleTrackIndex]
                if (dst == null) { t.ex.advance(); continue }
                buf.clear()
                val n = t.ex.readSampleData(buf, 0)
                if (n < 0) { t.ex.advance(); continue }
                val rawPts = (nextT - originUs).coerceAtLeast(0L)
                val pts = if (monotonicFix) {
                    val prev = lastPtsByTrack[dst]
                    val fixed = if (prev != null && rawPts <= prev) prev + 1L else rawPts
                    lastPtsByTrack[dst] = fixed
                    fixed
                } else rawPts
                bi.set(
                    0, n, pts,
                    if (t.ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0,
                )
                muxer.writeSampleData(dst, buf, bi)
                t.ex.advance()
                val pct = ((nextT - originUs).toFloat() / total * 100).toInt().coerceIn(0, 100)
                if (pct != lastPct) { lastPct = pct; onProgress(pct / 100f) }
            }
        } finally {
            if (started) runCatching { muxer.stop() }
            runCatching { muxer.release() }
            extractors.forEach { runCatching { it.release() } }
        }
    }
}
