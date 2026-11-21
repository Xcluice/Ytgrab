package com.xcluice.ytgrab

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.Stream
import org.schabi.newpipe.extractor.stream.StreamInfo
import okhttp3.Request as OkRequest
import org.schabi.newpipe.extractor.downloader.Request as NpRequest

class NpDownloader : Downloader() {
    override fun execute(request: NpRequest): Response {
        val method = request.httpMethod()
        val url = request.url()
        val data = request.dataToSend()
        val body = when {
            data != null -> data.toRequestBody()
            method == "POST" || method == "PUT" -> ByteArray(0).toRequestBody()
            else -> null
        }
        val b = OkRequest.Builder().method(method, body).url(url).header("User-Agent", Net.UA)
        request.headers().forEach { (name, values) ->
            b.removeHeader(name)
            values.forEach { b.addHeader(name, it) }
        }
        val resp = Net.client.newCall(b.build()).execute()
        if (resp.code == 429) {
            resp.close()
            throw ReCaptchaException("reCaptcha challenge requested", url)
        }
        val text = resp.body?.string()
        return Response(resp.code, resp.message, resp.headers.toMultimap(), text, resp.request.url.toString())
    }
}

class Plan(val video: String?, val audio: String?)

object Extractor {
    private var ready = false
    private val allowed = listOf(144, 240, 360, 480, 720, 1080)

    fun init() {
        if (!ready) {
            NewPipe.init(NpDownloader())
            ready = true
        }
    }

    fun looksLikeYoutube(s: String) = Regex("(youtu\\.be/|youtube\\.com/)", RegexOption.IGNORE_CASE).containsMatchIn(s)

    private fun prog(s: Stream) = s.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP

    suspend fun fetch(url: String): VideoInfo = withContext(Dispatchers.IO) {
        init()
        val si = StreamInfo.getInfo(url.trim())
        val muxed = si.videoStreams.filter { prog(it) && it.format == MediaFormat.MPEG_4 }
        val vOnly = si.videoOnlyStreams.filter { prog(it) && it.format == MediaFormat.MPEG_4 }
        val aud = si.audioStreams.filter { prog(it) && it.format == MediaFormat.M4A }
        val heights = (muxed + vOnly).map { it.height }.filter { it in allowed }.distinct().sorted()
        VideoInfo(
            url = url.trim(),
            title = si.name,
            thumb = si.thumbnails.maxByOrNull { it.height }?.url,
            duration = si.duration,
            heights = heights,
            muxed = muxed,
            vOnly = vOnly,
            audio = aud,
        )
    }

    /** height == null means audio only. */
    fun plan(info: VideoInfo, height: Int?): Plan {
        val bestAudio = info.audio.maxByOrNull { it.averageBitrate }
        if (height == null) {
            val a = bestAudio ?: error("No audio stream available for this video")
            return Plan(null, a.content)
        }
        val m = info.muxed.filter { it.height == height }.maxByOrNull { it.fps }
        val v = info.vOnly.filter { it.height == height }.maxByOrNull { it.fps }
        return when {
            m != null && (height <= 360 || v == null || bestAudio == null) -> Plan(m.content, null)
            v != null && bestAudio != null -> Plan(v.content, bestAudio.content)
            else -> error("${height}p is not available for this video")
        }
    }
}
