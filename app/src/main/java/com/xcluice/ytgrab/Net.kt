package com.xcluice.ytgrab

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
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
    suspend fun download(url: String, file: File, len: Long, onBytes: (Long) -> Unit) =
        withContext(Dispatchers.IO) {
            val buf = ByteArray(64 * 1024)
            file.outputStream().buffered().use { out ->
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
                    var start = 0L
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
}
