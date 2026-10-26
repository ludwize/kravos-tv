package net.kravos.tv.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.kravos.tv.BuildConfig
import net.kravos.tv.data.Http
import okhttp3.Request
import org.json.JSONObject
import java.io.File

data class Release(val build: Int, val name: String, val apkUrl: String, val notes: String, val sizeBytes: Long)

/** Self-update from GitHub Releases of the app's own repo (built by GitHub Actions on every push). */
object Updater {
    suspend fun latest(): Release? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("https://api.github.com/repos/${BuildConfig.GITHUB_REPO}/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .build()
            Http.client().newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@use null
                val o = JSONObject(r.body!!.string())
                val build = o.optString("tag_name").substringAfterLast('-').toIntOrNull() ?: return@use null
                val assets = o.optJSONArray("assets") ?: return@use null
                val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                    .firstOrNull { it.optString("name").endsWith(".apk") } ?: return@use null
                Release(build, o.optString("name"), apk.optString("browser_download_url"), o.optString("body"), apk.optLong("size"))
            }
        }.onFailure { Log.w(Http.TAG, "update check failed: ${it.message}") }.getOrNull()
    }

    fun isNewer(r: Release) = r.build > BuildConfig.BUILD_NUMBER

    /** Downloads the APK, reporting progress 0..1. Returns the file. */
    suspend fun download(ctx: Context, r: Release, progress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "KravosTV-${r.build}.apk")
        Http.client().newCall(Request.Builder().url(r.apkUrl).build()).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            val body = resp.body ?: error("prazan odgovor")
            val total = body.contentLength().takeIf { it > 0 } ?: r.sizeBytes
            body.byteStream().use { inp ->
                out.outputStream().use { os ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        os.write(buf, 0, n)
                        done += n
                        if (total > 0) progress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        }
        out
    }

    /** Opens the system installer; if "install unknown apps" isn't allowed yet, opens that settings page first. */
    fun install(ctx: Context, apk: File): Boolean {
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return false
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", apk)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return true
    }
}
