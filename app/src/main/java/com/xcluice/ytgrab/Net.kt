package com.xcluice.ytgrab

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

class HttpStatusException(val code: Int) : IOException("HTTP $code")

object Net {
    const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"
    const val CHUNK_SIZE = 2_097_152L // 2 MB chunks: saturates TCP window without hitting YT 10 MB throttle cliff

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * Dedicated HTTP/1.1 download client so parallel workers open genuinely separate TCP sockets
     * to googlevideo.com instead of multiplexing onto a single throttled HTTP/2 connection.
     */
    private val dlClient: OkHttpClient = OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .dispatcher(Dispatcher().apply {
            maxRequests = 20
            maxRequestsPerHost = 12
        })
        .connectionPool(ConnectionPool(12, 2, TimeUnit.MINUTES))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private fun rangeUrl(url: String, start: Long, end: Long, rn: Int? = null): String {
        val sep = if ('?' in url) '&' else '?'
        val base = "$url${sep}range=$start-$end"
        return if (rn != null && !url.contains("rn=")) "$base&rn=$rn" else base
    }

    suspend fun length(url: String): Long = withContext(Dispatchers.IO) {
        Regex("[?&]clen=(\\d+)").find(url)?.groupValues?.get(1)?.toLongOrNull()?.let { return@withContext it }
        try {
            val req = Request.Builder().url(url).header("User-Agent", UA).header("Range", "bytes=0-0").build()
            client.newCall(req).execute().use { r ->
                r.header("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: 0L
            }
        } catch (_: Exception) {
            0L
        }
    }

    fun bytes(url: String): ByteArray? = try {
        client.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute().use { it.body?.bytes() }
    } catch (e: Exception) {
        null
    }

    /** Returns how many payload bytes are already safely completed in doneFile. */
    fun completedBytes(doneFile: File, payloadLen: Long): Long {
        if (payloadLen <= 0L || !doneFile.exists()) return 0L
        val numChunks = ((payloadLen + CHUNK_SIZE - 1) / CHUNK_SIZE).toInt()
        if (doneFile.length() != numChunks.toLong()) return 0L
        val map = runCatching { doneFile.readBytes() }.getOrNull() ?: return 0L
        var total = 0L
        for (i in 0 until numChunks) {
            if (map[i] == 1.toByte()) {
                val start = i * CHUNK_SIZE
                total += min(CHUNK_SIZE, payloadLen - start)
            }
        }
        return total
    }

    /**
     * High-speed multi-socket HTTP/1.1 parallel chunked downloader.
     * Streams 64 KB blocks straight to FileChannel (tiny RAM footprint on Oreo) and marks each
     * finished 2 MB chunk with a single-byte write in doneFile for instant, zero-waste Resume.
     */
    suspend fun downloadParallel(
        url: String,
        file: File,
        doneFile: File,
        len: Long,
        from: Long = 0L,
        fileHeaderBytes: Long = 0L,
        workers: Int = 6,
        onBytes: (Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        if (len <= 0L) {
            downloadSequential(url, file, from = if (file.exists()) file.length() else 0L, append = file.exists() && file.length() > 0L, onBytes = onBytes)
            return@withContext
        }
        val payloadLen = len - from
        if (payloadLen <= 0L) return@withContext

        val numChunks = ((payloadLen + CHUNK_SIZE - 1) / CHUNK_SIZE).toInt()
        val expectedFileLen = fileHeaderBytes + payloadLen
        val done: ByteArray = if (
            doneFile.exists() &&
            doneFile.length() == numChunks.toLong() &&
            file.exists() &&
            file.length() == expectedFileLen
        ) {
            doneFile.readBytes()
        } else {
            RandomAccessFile(file, "rw").use { raf -> raf.setLength(expectedFileLen) }
            ByteArray(numChunks).also { doneFile.writeBytes(it) }
        }

        val pending = (0 until numChunks).filter { done[it] == 0.toByte() }.toIntArray()
        if (pending.isEmpty()) return@withContext

        val cursor = AtomicInteger(0)
        val rnCounter = AtomicInteger(1)
        val doneLock = Any()

        RandomAccessFile(file, "rw").use { raf ->
            RandomAccessFile(doneFile, "rw").use { doneRaf ->
                val channel = raf.channel
                coroutineScope {
                    val nWorkers = min(workers, pending.size).coerceAtLeast(1)
                    repeat(nWorkers) {
                        launch(Dispatchers.IO) {
                            val ioBuf = ByteArray(64 * 1024)
                            while (true) {
                                ensureActive()
                                val pIdx = cursor.getAndIncrement()
                                if (pIdx >= pending.size) break
                                val chunkIdx = pending[pIdx]
                                val streamStart = from + chunkIdx * CHUNK_SIZE
                                val streamEnd = min(streamStart + CHUNK_SIZE, len) - 1
                                val expected = streamEnd - streamStart + 1
                                var writePos = fileHeaderBytes + chunkIdx * CHUNK_SIZE

                                val req = Request.Builder()
                                    .url(rangeUrl(url, streamStart, streamEnd, rn = rnCounter.getAndIncrement()))
                                    .header("User-Agent", UA)
                                    .header("Connection", "keep-alive")
                                    .build()

                                var writtenTotal = 0L
                                dlClient.newCall(req).execute().use { r ->
                                    if (!r.isSuccessful) {
                                        if (r.code in listOf(403, 404, 410)) throw HttpStatusException(r.code)
                                        throw IOException("HTTP ${r.code}")
                                    }
                                    val src = r.body!!.byteStream()
                                    while (writtenTotal < expected) {
                                        val toRead = min(ioBuf.size.toLong(), expected - writtenTotal).toInt()
                                        val n = src.read(ioBuf, 0, toRead)
                                        if (n < 0) break
                                        ensureActive()
                                        val bb = ByteBuffer.wrap(ioBuf, 0, n)
                                        while (bb.hasRemaining()) {
                                            val w = channel.write(bb, writePos)
                                            if (w <= 0) throw IOException("Disk write failed")
                                            writePos += w
                                        }
                                        writtenTotal += n
                                        onBytes(n.toLong())
                                    }
                                }
                                if (writtenTotal != expected) {
                                    throw IOException("Connection interrupted")
                                }

                                synchronized(doneLock) {
                                    done[chunkIdx] = 1
                                    doneRaf.seek(chunkIdx.toLong())
                                    doneRaf.write(1)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /** Fallback sequential download when total stream length is unknown. */
    private suspend fun downloadSequential(
        url: String,
        file: File,
        from: Long = 0L,
        append: Boolean = false,
        onBytes: (Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val buf = ByteArray(64 * 1024)
        val reqBuilder = Request.Builder().url(url).header("User-Agent", UA)
        if (from > 0L) reqBuilder.header("Range", "bytes=$from-")
        dlClient.newCall(reqBuilder.build()).execute().use { r ->
            if (!r.isSuccessful) {
                if (r.code in listOf(403, 404, 410)) throw HttpStatusException(r.code)
                throw IOException("HTTP ${r.code}")
            }
            val src = r.body!!.byteStream()
            FileOutputStream(file, append).buffered().use { out ->
                try {
                    while (true) {
                        val n = src.read(buf)
                        if (n < 0) break
                        ensureActive()
                        out.write(buf, 0, n)
                        onBytes(n.toLong())
                    }
                } finally {
                    runCatching { out.flush() }
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
        if (len <= 0) return@withContext null
        val headLen = min(len, 600_000L)
        val req = Request.Builder().url(rangeUrl(url, 0, headLen - 1)).header("User-Agent", UA).build()
        val bytes = client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            r.body!!.bytes()
        }
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
