package com.xcluice.ytgrab

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

class HttpStatusException(val code: Int) : IOException("HTTP $code")

object Net {
    const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .build()

    private fun rangeUrl(url: String, start: Long, end: Long): String {
        val sep = if ('?' in url) '&' else '?'
        return "$url${sep}range=$start-$end"
    }

    suspend fun length(url: String): Long = withContext(Dispatchers.IO) {
        Regex("[?&]clen=(\\d+)").find(url)?.groupValues?.get(1)?.toLongOrNull()?.let { return@withContext it }
        repeat(3) { attempt ->
            try {
                val req = Request.Builder().url(url).header("User-Agent", UA).header("Range", "bytes=0-0").build()
                client.newCall(req).execute().use { r ->
                    val len = r.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
                    if (len != null && len > 0) return@withContext len
                }
            } catch (_: Exception) {
                if (attempt < 2) delay(1500L * (attempt + 1))
            }
        }
        0L
    }

    fun bytes(url: String): ByteArray? = try {
        client.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute().use { it.body?.bytes() }
    } catch (e: Exception) {
        null
    }

    /**
     * YouTube-style resilient chunked download:
     * - Uses 2 MB chunks to survive slow/unstable mobile connections.
     * - Automatically retries from the exact last written byte on network drops (0 wasted MBs).
     */
    suspend fun download(
        url: String,
        file: File,
        len: Long,
        from: Long = 0L,
        append: Boolean = false,
        onRetry: (Boolean) -> Unit = {},
        onBytes: (Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val buf = ByteArray(32 * 1024)
        var pos = from
        var shouldAppend = append
        val chunk = 2_000_000L
        var consecutiveErrors = 0
        val maxRetries = 12

        while (len <= 0L || pos < len) {
            ensureActive()
            try {
                val end = if (len > 0L) min(pos + chunk, len) - 1 else -1L
                val reqBuilder = Request.Builder().header("User-Agent", UA)
                if (len > 0L) {
                    reqBuilder.url(rangeUrl(url, pos, end))
                } else {
                    reqBuilder.url(url)
                    if (pos > 0L) reqBuilder.header("Range", "bytes=$pos-")
                }

                client.newCall(reqBuilder.build()).execute().use { r ->
                    if (!r.isSuccessful) {
                        if (r.code in listOf(403, 404, 410)) throw HttpStatusException(r.code)
                        throw IOException("HTTP ${r.code}")
                    }
                    val src = r.body!!.byteStream()
                    FileOutputStream(file, shouldAppend).buffered().use { out ->
                        shouldAppend = true
                        while (true) {
                            val n = src.read(buf)
                            if (n < 0) break
                            ensureActive()
                            out.write(buf, 0, n)
                            pos += n
                            if (consecutiveErrors > 0) {
                                consecutiveErrors = 0
                                onRetry(false)
                            }
                            onBytes(n.toLong())
                        }
                        out.flush()
                    }
                }
                // Unknown total length finished cleanly.
                if (len <= 0L) break
            } catch (e: HttpStatusException) {
                throw e
            } catch (e: IOException) {
                ensureActive()
                consecutiveErrors++
                if (consecutiveErrors > maxRetries) throw e
                onRetry(true)
                val waitMs = min(1500L * consecutiveErrors, 8000L)
                delay(waitMs)
            }
        }
        onRetry(false)
    }

    class Seg(val init: ByteArray, val offset: Long)

    /**
     * Reads the MP4 header + sidx index of a DASH stream and returns the header bytes
     * (without sidx) plus the byte offset of the segment that contains startSec.
     */
    suspend fun findStart(url: String, len: Long, startSec: Long): Seg? = withContext(Dispatchers.IO) {
        if (len <= 0) return@withContext null
        val headLen = min(len, 600_000L)
        var head: ByteArray? = null
        var lastErr: IOException? = null
        repeat(4) { attempt ->
            try {
                val req = Request.Builder().url(rangeUrl(url, 0, headLen - 1)).header("User-Agent", UA).build()
                head = client.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                    r.body!!.bytes()
                }
                return@repeat
            } catch (e: IOException) {
                lastErr = e
                if (attempt < 3) delay(1500L * (attempt + 1))
            }
        }
        val bytes = head ?: throw (lastErr ?: IOException("Couldn't read video index"))
        try {
            val bb = ByteBuffer.wrap(bytes)
            var pos = 0L
            var moovSeen = false
            while (pos + 8 <= bytes.size) {
                val p = pos.toInt()
                var size = bb.getInt(p).toLong() and 0xffffffffL
                val type = String(bytes, p + 4, 4, Charsets.ISO_8859_1)
                var hdr = 8
                if (size == 1L) {
                    if (p + 16 > bytes.size) break
                    size = bb.getLong(p + 8); hdr = 16
                }
                if (size < 8) break
                if (type == "moov") moovSeen = true
                if (type == "sidx") {
                    if (!moovSeen || pos + size > bytes.size) return@withContext null
                    var q = p + hdr
                    val version = bytes[q].toInt() and 0xff
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
                        if (t + dur > startTicks) return@withContext Seg(bytes.copyOf(p), off)
                        off += sz; t += dur; q += 12
                    }
                    return@withContext null
                }
                pos += size
            }
            null
        } catch (_: Exception) {
            null
        }
    }
}
