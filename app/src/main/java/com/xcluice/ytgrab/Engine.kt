package com.xcluice.ytgrab

import android.app.Application
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
import javax.net.ssl.SSLException

object Engine {
    lateinit var app: Application
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var running: Job? = null

    val job = MutableStateFlow(JobState())
    val items = MutableStateFlow<List<DlItem>>(emptyList())

    val dir: File
        get() = File(app.filesDir, "videos").apply {
            mkdirs()
            runCatching { File(this, ".nomedia").createNewFile() }
        }

    private val index get() = File(dir, "index.json")

    fun init(application: Application) {
        app = application
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

    fun ack() {
        if (!job.value.active) job.value = JobState()
    }

    fun cancel() {
        running?.cancel()
    }

    fun start(info: VideoInfo, height: Int?, startSec: Long) {
        if (job.value.active) return
        job.value = JobState(active = true, title = info.title, phase = "Starting")
        runCatching { ContextCompat.startForegroundService(app, Intent(app, DownloadService::class.java)) }
        running = scope.launch {
            val work = File(app.cacheDir, "work")
            try {
                run(info, height, startSec, work)
            } catch (e: CancellationException) {
                job.value = JobState()
                throw e
            } catch (e: Throwable) {
                job.value = JobState(error = friendly(e))
            } finally {
                work.deleteRecursively()
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

    private suspend fun run(info: VideoInfo, height: Int?, startSec: Long, work: File) {
        work.deleteRecursively(); work.mkdirs()
        val plan = Extractor.plan(info, height, preferSplit = startSec > 0)
        val id = System.currentTimeMillis().toString(36)
        val ext = if (plan.video == null) "m4a" else "mp4"
        val out = File(dir, "$id.$ext")

        var partialOk = false
        if (startSec > 0) {
            job.update { it.copy(phase = "Finding your start point") }
            partialOk = try {
                runPartial(plan, startSec, work, out, ext)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
        }
        if (!partialOk) runFull(plan, startSec, work, out, ext)

        val thumbName = info.thumb?.let { u ->
            withContext(Dispatchers.IO) { Net.bytes(u) }?.let { b ->
                File(dir, "$id.jpg").also { it.writeBytes(b) }.name
            }
        }
        val label = if (height == null) "Audio" else "${height}p"
        val item = DlItem(id, info.title, out.name, thumbName, label, out.length(), System.currentTimeMillis())
        items.update { listOf(item) + it }
        save()
        job.value = JobState(finished = true, title = info.title, progress = 1f)
    }

    /** Downloads ONLY the data from the segment containing startSec onward. */
    private suspend fun runPartial(plan: Plan, startSec: Long, work: File, out: File, ext: String): Boolean {
        val vLen = plan.video?.let { withContext(Dispatchers.IO) { Net.length(it) } } ?: 0L
        val aLen = plan.audio?.let { withContext(Dispatchers.IO) { Net.length(it) } } ?: 0L
        val vSeg = plan.video?.let { Net.findStart(it, vLen, startSec) ?: return false }
        val aSeg = plan.audio?.let { Net.findStart(it, aLen, startSec) ?: return false }
        val vNeed = if (vSeg != null) vLen - vSeg.offset + vSeg.init.size else 0L
        val aNeed = if (aSeg != null) aLen - aSeg.offset + aSeg.init.size else 0L
        val total = (vNeed + aNeed).coerceAtLeast(1L)
        var done = 0L

        suspend fun grab(url: String, seg: Net.Seg, len: Long, f: File, label: String) {
            job.update { it.copy(phase = label) }
            f.writeBytes(seg.init)
            done += seg.init.size
            var last = -1
            Net.download(url, f, len, from = seg.offset, append = true) { n ->
                done += n
                val p = ((done.toFloat() / total).coerceIn(0f, 1f) * 90).toInt()
                if (p != last) { last = p; job.update { it.copy(progress = p / 100f) } }
            }
        }
        val vFile = if (plan.video != null && vSeg != null) File(work, "v.mp4").also {
            grab(plan.video, vSeg, vLen, it, "Downloading video from your start")
        } else null
        val aFile = if (plan.audio != null && aSeg != null) File(work, "a.m4a").also {
            grab(plan.audio, aSeg, aLen, it, "Downloading audio from your start")
        } else null

        job.update { it.copy(phase = if (vFile != null && aFile != null) "Merging" else "Finalizing", progress = 0.9f) }
        val tmp = File(work, "out.$ext")
        remux(vFile, aFile, 0L, tmp) { p -> job.update { it.copy(progress = 0.9f + 0.1f * p) } }
        tmp.copyTo(out, overwrite = true)
        return true
    }

    /** Fallback: download everything, then cut from startSec. */
    private suspend fun runFull(plan: Plan, startSec: Long, work: File, out: File, ext: String) {
        work.deleteRecursively(); work.mkdirs()
        val vFile = plan.video?.let { File(work, "v.mp4") }
        val aFile = plan.audio?.let { File(work, "a.m4a") }
        val vLen = plan.video?.let { withContext(Dispatchers.IO) { Net.length(it) } } ?: 0L
        val aLen = plan.audio?.let { withContext(Dispatchers.IO) { Net.length(it) } } ?: 0L
        val total = (vLen + aLen).coerceAtLeast(1L)
        var done = 0L

        suspend fun grab(url: String, f: File, len: Long, label: String) {
            job.update { it.copy(phase = label, progress = 0f) }
            var last = -1
            Net.download(url, f, len) { n ->
                done += n
                val p = ((done.toFloat() / total).coerceIn(0f, 1f) * 90).toInt()
                if (p != last) { last = p; job.update { it.copy(progress = p / 100f) } }
            }
        }
        if (plan.video != null && vFile != null) grab(plan.video, vFile, vLen, "Downloading video")
        if (plan.audio != null && aFile != null) {
            grab(plan.audio, aFile, aLen, if (plan.video == null) "Downloading audio" else "Downloading audio track")
        }
        val needTransform = (vFile != null && aFile != null) || startSec > 0
        if (needTransform) {
            job.update { it.copy(phase = if (startSec > 0) "Trimming and merging" else "Merging", progress = 0.9f) }
            val tmp = File(work, "out.$ext")
            remux(vFile, aFile, startSec * 1000, tmp) { p -> job.update { it.copy(progress = 0.9f + 0.1f * p) } }
            tmp.copyTo(out, overwrite = true)
        } else {
            (vFile ?: aFile)!!.copyTo(out, overwrite = true)
        }
    }

    private fun isSupportedMp4Mime(mime: String): Boolean = when (mime.lowercase()) {
        "video/avc", "video/hevc", "video/mp4v-es", "video/3gpp",
        "audio/mp4a-latm", "audio/3gpp", "audio/amr-wb" -> true
        else -> false
    }

    /**
     * Copies (never re-encodes) the video/audio samples into one MP4, starting from the
     * keyframe at or before startMs. Runs off the main thread and is cancellable.
     */
    private suspend fun remux(
        video: File?, audio: File?, startMs: Long, out: File, onProgress: (Float) -> Unit,
    ) = withContext(Dispatchers.IO) {
        class T(val ex: MediaExtractor) {
            val outIdx = HashMap<Int, Int>()
            var isVideo = false
            var maxSize = 4 shl 20
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
                // Partial DASH downloads can start at slightly different segment times; align audio to video.
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

            // Re-base so the output always starts at 0 (ignoring tracks already at EOF).
            val originUs = sources.map { it.ex.sampleTime }.filter { it >= 0L }.minOrNull() ?: 0L

            muxer.start(); started = true
            val buf = ByteBuffer.allocate(sources.maxOf { it.maxSize })
            val bi = MediaCodec.BufferInfo()
            val total = (durationUs - originUs).coerceAtLeast(1L)
            var lastPct = -1
            while (true) {
                ensureActive()
                // Interleave: always write from the source whose next sample is earliest.
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
                bi.set(
                    0, n, (nextT - originUs).coerceAtLeast(0L),
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
