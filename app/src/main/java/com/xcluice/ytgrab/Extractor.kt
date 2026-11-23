package com.xcluice.ytgrab

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.Stream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream
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
    private fun isAv1(s: VideoStream) = s.codec?.startsWith("av01", ignoreCase = true) == true
    private fun isH264(s: VideoStream) = s.codec?.startsWith("avc1", ignoreCase = true) == true

    /**
     * Picks the original-language audio at the sweet-spot ~128 kbps AAC bitrate:
     * crisp YouTube quality without wasting mobile data on 256+ kbps tracks.
     */
    private fun pickOriginalAudio(streams: List<AudioStream>): AudioStream? {
        if (streams.isEmpty()) return null
        val pool = streams.filter { it.audioTrackType == AudioTrackType.ORIGINAL }.ifEmpty {
            streams.filter { it.audioTrackType == null }.ifEmpty {
                streams.filter {
                    it.audioTrackType != AudioTrackType.DUBBED && it.audioTrackType != AudioTrackType.DESCRIPTIVE
                }.ifEmpty { streams }
            }
        }
        val efficient = pool.filter { it.averageBitrate in 1..140 }
        return efficient.maxByOrNull { it.averageBitrate }
            ?: pool.minByOrNull { if (it.averageBitrate > 0) it.averageBitrate else Int.MAX_VALUE }
    }

    /**
     * Prefers H.264 at standard <=30fps and lowest bitrate at the requested height
     * to keep sharp quality while saving 30-50% mobile data and downloading faster.
     */
    private fun pickEfficientVideo(candidates: List<VideoStream>): VideoStream? {
        if (candidates.isEmpty()) return null
        val h264 = candidates.filter(::isH264).ifEmpty { candidates }
        val standardFps = h264.filter { it.fps in 1..30 }.ifEmpty { h264 }
        return standardFps.minByOrNull { if (it.bitrate > 0) it.bitrate else Int.MAX_VALUE }
    }

    suspend fun fetch(url: String): VideoInfo = withContext(Dispatchers.IO) {
        init()
        val si = StreamInfo.getInfo(url.trim())
        val muxed = si.videoStreams.filter { prog(it) && it.format == MediaFormat.MPEG_4 && !isAv1(it) }
        val vOnly = si.videoOnlyStreams.filter { prog(it) && it.format == MediaFormat.MPEG_4 && !isAv1(it) }
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
    fun plan(info: VideoInfo, height: Int?, preferSplit: Boolean = false): Plan {
        val bestAudio = pickOriginalAudio(info.audio)
        if (height == null) {
            val a = bestAudio ?: error("No audio stream available for this video")
            return Plan(null, a.content)
        }
        val m = pickEfficientVideo(info.muxed.filter { it.height == height })
        val v = pickEfficientVideo(info.vOnly.filter { it.height == height })
        // If multi-language/dubbed audio tracks exist, avoid muxed 360p which YouTube may auto-dub.
        val hasMultiAudio = info.audio.any {
            it.audioTrackType == AudioTrackType.ORIGINAL || it.audioTrackType == AudioTrackType.DUBBED
        }
        return when {
            m != null && !preferSplit && !hasMultiAudio && (height <= 360 || v == null || bestAudio == null) ->
                Plan(m.content, null)
            v != null && bestAudio != null -> Plan(v.content, bestAudio.content)
            m != null -> Plan(m.content, null)
            else -> error("${height}p is not available for this video")
        }
    }
}
