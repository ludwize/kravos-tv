package net.kravos.tv.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

class HttpException(val code: Int, val body: String) : IOException("HTTP $code: ${body.take(200)}")

object Http {
    const val TAG = "KravosTV"
    const val BROWSER_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Mobile Safari/537.36"

    @Volatile private var client: OkHttpClient? = null
    @Volatile private var lenient: OkHttpClient? = null

    fun init(context: Context) {
        if (client != null) return
        client = OkHttpClient.Builder()
            .connectionPool(ConnectionPool(16, 5, TimeUnit.MINUTES))
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .followSslRedirects(true)
            .addInterceptor { chain ->
                val r = chain.request()
                val b = r.newBuilder()
                if (r.header("User-Agent") == null) b.header("User-Agent", BROWSER_UA)
                chain.proceed(b.build())
            }
            .build()
    }

    fun client(): OkHttpClient = client ?: OkHttpClient()

    /**
     * Client for media streams only: small broadcasters often run HTTPS with expired or self-signed
     * certificates. Only video bytes travel over it, never credentials.
     */
    fun mediaClient(): OkHttpClient {
        lenient?.let { return it }
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), SecureRandom()) }
        val c = client().newBuilder()
            .sslSocketFactory(ssl.socketFactory, tm)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
        lenient = c
        return c
    }

    suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        open(url).use { resp -> resp.body?.string().orEmpty() }
    }

    /** Opens a GET and throws on non-2xx. Caller must close the response. */
    fun open(url: String, headers: Map<String, String> = emptyMap(), c: OkHttpClient = client()): Response {
        val b = Request.Builder().url(url)
        headers.forEach { (k, v) -> b.header(k, v) }
        val resp = c.newCall(b.build()).execute()
        if (!resp.isSuccessful) {
            val body = runCatching { resp.body?.string()?.take(300) }.getOrNull().orEmpty()
            resp.close()
            Log.w(TAG, "GET ${url.take(160)} -> ${resp.code}")
            throw HttpException(resp.code, body)
        }
        return resp
    }

    /** Downloads to [dest] atomically. */
    suspend fun download(url: String, dest: File) = withContext(Dispatchers.IO) {
        val tmp = File(dest.parentFile, dest.name + ".part")
        open(url).use { resp ->
            resp.body!!.byteStream().use { inp -> tmp.outputStream().use { inp.copyTo(it, 64 * 1024) } }
        }
        if (!tmp.renameTo(dest)) { dest.delete(); tmp.renameTo(dest) }
        Log.i(TAG, "downloaded ${url.take(120)} -> ${dest.length()} b")
    }
}
