package com.xcluice.ytgrab

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import kotlin.math.min

object Net {
    const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun length(url: String): Long {
        Regex("[?&]clen=(\\d+)").find(url)?.groupValues?.get(1)?.toLongOrNull()?.let { return it }
        return try {
            val req = Request.Builder().url(url).header("User-Agent", UA).header("Range", "bytes=0-0").build()
            client.newCall(req).execute().use { r ->
                r.header("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: 0L
            }
        } catch (e: Exception) {
            0L
        }
    }

    fun bytes(url: String): ByteArray? = try {
        client.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute().use { it.body?.bytes() }
    } catch (e: Exception) {
        null
    }

    /** Chunked download (googlevideo throttles long single requests). */
    suspend fun download(url: String, file: File, len: Long, from: Long = 0L, append: Boolean = false, onBytes: (Long) -> Unit) =
        withContext(Dispatchers.IO) {
            val buf = ByteArray(64 * 1024)
            FileOutputStream(file, append).buffered().use { out ->
                if (len <= 0) {
                    val req = Request.Builder().url(url).header("User-Agent", UA).build()
                    client.newCall(req).execute().use { r ->
                        if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                        val src = r.body!!.byteStream()
                        while (true) {
                            val n = src.read(buf)
                            if (n < 0) break
                            ensureActive()
                            out.write(buf, 0, n)
                            onBytes(n.toLong())
                        }
                    }
                } else {
                    val chunk = 9_900_000L
                    var start = from
                    while (start < len) {
                        ensureActive()
                        val end = min(start + chunk, len) - 1
                        val req = Request.Builder().url("$url&range=$start-$end").header("User-Agent", UA).build()
                        client.newCall(req).execute().use { r ->
                            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                            val src = r.body!!.byteStream()
                            while (true) {
                                val n = src.read(buf)
                                if (n < 0) break
                                ensureActive()
                                out.write(buf, 0, n)
                                onBytes(n.toLong())
                            }
                        }
                        start = end + 1
                    }
                }
            }
        }

    class Seg(val init: ByteArray, val offset: Long)

    /**
     * Reads the MP4 header + sidx index of a DASH stream and returns the header bytes
     * (without sidx) plus the byte offset of the segment that contains startSec.
     */
    suspend fun findStart(url: String, len: Long, startSec: Long): Seg? = withContext(Dispatchers.IO) {
        try {
            if (len <= 0) return@withContext null
            val headLen = min(len, 600_000L)
            val req = Request.Builder().url("$url&range=0-${headLen - 1}").header("User-Agent", UA).build()
            val head = client.newCall(req).execute().use { r -> if (r.isSuccessful) r.body!!.bytes() else null }
                ?: return@withContext null
            val bb = ByteBuffer.wrap(head)
            var pos = 0L
            var moovSeen = false
            while (pos + 8 <= head.size) {
                val p = pos.toInt()
                var size = bb.getInt(p).toLong() and 0xffffffffL
                val type = String(head, p + 4, 4, Charsets.ISO_8859_1)
                var hdr = 8
                if (size == 1L) {
                    if (p + 16 > head.size) break
                    size = bb.getLong(p + 8); hdr = 16
                }
                if (size < 8) break
                if (type == "moov") moovSeen = true
                if (type == "sidx") {
                    if (!moovSeen || pos + size > head.size) return@withContext null
                    var q = p + hdr
                    val version = head[q].toInt() and 0xff
                    q += 8
                    val timescale = bb.getInt(q).toLong() and 0xffffffffL
                    q += 4
                    val ept: Long
                    val first: Long
                    if (version == 0) {
                        ept = bb.getInt(q).toLong() and 0xffffffffL
                        first = bb.getInt(q + 4).toLong() and 0xffffffffL
                        q += 8
                    } else {
                        ept = bb.getLong(q); first = bb.getLong(q + 8); q += 16
                    }
                    q += 2
                    val count = bb.getShort(q).toInt() and 0xffff
                    q += 2
                    val startTicks = startSec * timescale
                    var t = ept
                    var off = pos + size + first
                    for (i in 0 until count) {
                        val sz = (bb.getInt(q).toLong() and 0x7fffffffL)
                        val dur = bb.getInt(q + 4).toLong() and 0xffffffffL
                        if (t + dur > startTicks) return@withContext Seg(head.copyOf(p), off)
                        off += sz; t += dur; q += 12
                    }
                    return@withContext null
                }
                pos += size
            }
            null
        } catch (e: Exception) {
            null
        }
    }
}
