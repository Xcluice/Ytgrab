package com.xcluice.ytgrab

import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.VideoStream

data class VideoInfo(
    val url: String,
    val title: String,
    val thumb: String?,
    val duration: Long,
    val heights: List<Int>,
    val muxed: List<VideoStream>,
    val vOnly: List<VideoStream>,
    val audio: List<AudioStream>,
)

data class DlItem(
    val id: String,
    val title: String,
    val file: String,
    val thumb: String?,
    val label: String,
    val bytes: Long,
    val at: Long,
)

data class JobState(
    val active: Boolean = false,
    val paused: Boolean = false,
    val title: String = "",
    val phase: String = "",
    val progress: Float = 0f,
    val finished: Boolean = false,
    val error: String? = null,
)

object Ts {
    /** Clock-style input: digits "135" -> "1:35", "10210" -> "1:02:10". */
    fun format(d: String): String {
        if (d.isEmpty()) return ""
        val s = d.padStart(3, '0')
        val ss = s.takeLast(2)
        val rest = s.dropLast(2)
        return if (rest.length <= 2) {
            "${rest.trimStart('0').ifEmpty { "0" }}:$ss"
        } else {
            val mm = rest.takeLast(2)
            val h = rest.dropLast(2).trimStart('0').ifEmpty { "0" }
            "$h:$mm:$ss"
        }
    }

    fun seconds(d: String): Long? {
        if (d.isEmpty()) return 0
        val s = d.padStart(3, '0')
        val ss = s.takeLast(2).toInt()
        val rest = s.dropLast(2)
        val mm = rest.takeLast(2).toInt()
        val h = rest.dropLast(2).ifEmpty { "0" }.toInt()
        if (ss >= 60) return null
        if (h > 0 && mm >= 60) return null
        return h * 3600L + mm * 60L + ss
    }

    fun clock(sec: Long): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
