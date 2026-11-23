package com.xcluice.ytgrab

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException

sealed interface Update {
    data object Unknown : Update
    data object Current : Update
    data class Required(val version: String, val apkUrl: String, val notes: String) : Update
}

object Updater {
    private const val API = "https://api.github.com/repos/Xcluice/Ytgrab/releases/latest"
    val state = MutableStateFlow<Update>(Update.Unknown)

    private fun newer(remote: String, local: String): Boolean {
        val a = remote.removePrefix("v").split('.').map { it.toIntOrNull() ?: 0 }
        val b = local.removePrefix("v").split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun cleanNotes(raw: String): String {
        val lines = raw.lines()
            .map { it.trim() }
            .filter { line ->
                line.isNotEmpty() &&
                    !line.startsWith("#") &&
                    !line.startsWith("**Full Changelog**")
            }
            .map { line ->
                line.replace(Regex("^[-*]\\s+"), "• ")
                    .replace(Regex("\\s+by\\s+@\\S+\\s+in\\s+https?://\\S+"), "")
                    .replace(Regex("\\s+in\\s+https?://\\S+"), "")
                    .trim()
            }
            .filter { it.isNotEmpty() }
        return if (lines.isNotEmpty()) lines.joinToString("\n") else "Bug fixes and improvements."
    }

    suspend fun check() = withContext(Dispatchers.IO) {
        state.value = try {
            val req = Request.Builder().url(API).header("Accept", "application/vnd.github+json").build()
            val json = JSONObject(Net.client.newCall(req).execute().use { it.body!!.string() })
            val tag = json.getString("tag_name")
            val notes = cleanNotes(json.optString("body", ""))
            val assets = json.getJSONArray("assets")
            var apk: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name").endsWith(".apk")) apk = a.getString("browser_download_url")
            }
            if (apk != null && newer(tag, BuildConfig.VERSION_NAME)) {
                Update.Required(tag.removePrefix("v"), apk, notes)
            } else {
                Update.Current
            }
        } catch (e: Exception) {
            Update.Current
        }
    }

    suspend fun download(ctx: Context, url: String, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = File(ctx.cacheDir, "update").apply { deleteRecursively(); mkdirs() }
            val f = File(dir, "Ytgrab.apk")
            Net.client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                val body = r.body!!
                val total = body.contentLength().coerceAtLeast(1L)
                var read = 0L
                body.byteStream().use { src ->
                    f.outputStream().use { out ->
                        val buf = ByteArray(32 * 1024)
                        while (true) {
                            val n = src.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            read += n
                            onProgress((read.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
            }
            f
        }

    /** Returns false if the user must first allow installs from this app. */
    fun install(ctx: Context, f: File): Boolean {
        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return false
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return true
    }
}
