package com.xcluice.ytgrab

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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
        e is IOException -> "Network problem. Check your connection and try again."
        e.javaClass.name.contains("ReCaptcha") -> "YouTube asked for a check. Try again in a few minutes."
        e.javaClass.name.contains("extractor") ->
            e.message?.take(120) ?: "YouTube changed something. Update Ytgrab and try again."
        else -> e.message?.take(120) ?: "Something went wrong. Try again."
    }

    private suspend fun run(info: VideoInfo, height: Int?, startSec: Long, work: File) {
        work.deleteRecursively(); work.mkdirs()
        val plan = Extractor.plan(info, height)
        val id = System.currentTimeMillis().toString(36)

        val vFile = plan.video?.let { File(work, "v.mp4") }
        val aFile = plan.audio?.let { File(work, if (plan.video == null) "a.m4a" else "a.m4a") }
        val vLen = plan.video?.let { withContext(Dispatchers.IO) { Net.length(it) } } ?: 0L
        val aLen = plan.audio?.let { withContext(Dispatchers.IO) { Net.length(it) } } ?: 0L
        val total = (vLen + aLen).coerceAtLeast(1L)
        var done = 0L

        suspend fun grab(url: String, f: File, len: Long, label: String) {
            job.update { it.copy(phase = label) }
            Net.download(url, f, len) { n ->
                done += n
                job.update { it.copy(progress = (done.toFloat() / total).coerceIn(0f, 1f) * 0.9f) }
            }
        }
        if (plan.video != null && vFile != null) grab(plan.video, vFile, vLen, "Downloading video")
        if (plan.audio != null && aFile != null) {
            grab(plan.audio, aFile, aLen, if (plan.video == null) "Downloading audio" else "Downloading audio track")
        }

        val ext = if (plan.video == null) "m4a" else "mp4"
        val out = File(dir, "$id.$ext")
        val needTransform = (vFile != null && aFile != null) || startSec > 0
        if (needTransform) {
            job.update { it.copy(phase = if (startSec > 0) "Trimming and merging" else "Merging", progress = 0.9f) }
            val tmp = File(work, "out.$ext")
            transcode(vFile, aFile, startSec * 1000, tmp) { p -> job.update { it.copy(progress = 0.9f + 0.1f * p) } }
            tmp.copyTo(out, overwrite = true)
        } else {
            (vFile ?: aFile)!!.copyTo(out, overwrite = true)
        }

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

    private suspend fun transcode(
        video: File?, audio: File?, startMs: Long, out: File, onProgress: (Float) -> Unit,
    ) = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine<Unit> { cont ->
            val clip = MediaItem.ClippingConfiguration.Builder().setStartPositionMs(startMs).build()
            fun item(f: File, noAudio: Boolean, noVideo: Boolean) = EditedMediaItem.Builder(
                MediaItem.Builder().setUri(Uri.fromFile(f)).setClippingConfiguration(clip).build()
            ).setRemoveAudio(noAudio).setRemoveVideo(noVideo).build()

            val seqs = mutableListOf<EditedMediaItemSequence>()
            if (video != null && audio != null) {
                seqs += EditedMediaItemSequence(item(video, noAudio = true, noVideo = false))
                seqs += EditedMediaItemSequence(item(audio, noAudio = false, noVideo = true))
            } else {
                val f = video ?: audio!!
                seqs += EditedMediaItemSequence(item(f, noAudio = false, noVideo = false))
            }
            val composition = Composition.Builder(*seqs.toTypedArray()).build()

            val handler = Handler(Looper.getMainLooper())
            val holder = ProgressHolder()
            var finished = false
            val transformer = Transformer.Builder(app)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        finished = true
                        if (cont.isActive) cont.resume(Unit)
                    }

                    override fun onError(
                        composition: Composition, exportResult: ExportResult, exportException: ExportException,
                    ) {
                        finished = true
                        if (cont.isActive) cont.resumeWithException(exportException)
                    }
                }).build()
            val poll = object : Runnable {
                override fun run() {
                    if (finished) return
                    if (transformer.getProgress(holder) != Transformer.PROGRESS_STATE_NOT_STARTED) {
                        onProgress(holder.progress / 100f)
                    }
                    handler.postDelayed(this, 300)
                }
            }
            transformer.start(composition, out.absolutePath)
            handler.post(poll)
            cont.invokeOnCancellation {
                handler.post { runCatching { transformer.cancel() } }
                handler.removeCallbacks(poll)
            }
        }
    }
}
