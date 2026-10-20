package com.saltrip.rip.meta

import com.saltrip.rip.Diag
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.zip.GZIPInputStream

/** HTTP 结果。状态码与字节都由调用方判断，避免把 404 当异常。 */
data class HttpResult(val status: Int, val body: ByteArray, val contentType: String?) {
    val text: String get() = body.toString(Charsets.UTF_8)
}

/**
 * 最小 HTTP 客户端。宿主 JRE 未打包 `java.net.http`，因此只能用 `HttpURLConnection`。
 * 提供：超时、UA、gzip、有限重试（503/429/连接失败）、按主机限速。
 */
object Http {

    const val DEFAULT_UA = "SaltAudioCopy/0.23 (Salt Player CD ripper plugin)"

    private val throttleLock = Any()
    private val lastCall = HashMap<String, Long>()

    /** 同一主机两次请求之间的最小间隔（MusicBrainz 要求 1 req/s）。 */
    fun throttle(host: String, minGapMs: Long) {
        synchronized(throttleLock) {
            val now = System.currentTimeMillis()
            val prev = lastCall[host] ?: 0L
            val wait = minGapMs - (now - prev)
            if (wait > 0) Thread.sleep(wait)
            lastCall[host] = System.currentTimeMillis()
        }
    }

    fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        retries: Int = 3,
        accept: String = "application/json"
    ): HttpResult {
        var attempt = 0
        var backoff = 600L
        while (true) {
            attempt++
            try {
                val conn = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
                    readTimeout = 20_000
                    connectTimeout = 10_000
                    requestMethod = "GET"
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", DEFAULT_UA)
                    setRequestProperty("Accept", accept)
                    setRequestProperty("Accept-Encoding", "gzip")
                    for ((k, v) in headers) setRequestProperty(k, v)
                }
                val code = conn.responseCode
                if ((code == 503 || code == 429) && attempt <= retries) {
                    val retryAfter = conn.getHeaderField("Retry-After")?.toLongOrNull()?.times(1000)
                    Diag.log("http $code from $url, retry $attempt in ${retryAfter ?: backoff}ms")
                    Thread.sleep(retryAfter ?: backoff)
                    backoff = (backoff * 2).coerceAtMost(8000)
                    runCatching { conn.disconnect() }
                    continue
                }
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val bytes = stream?.let { s ->
                    val raw = if (conn.contentEncoding?.contains("gzip", true) == true) GZIPInputStream(s) else s
                    raw.use { it.readBytes() }
                } ?: ByteArray(0)
                val ct = conn.contentType
                runCatching { conn.disconnect() }
                return HttpResult(code, bytes, ct)
            } catch (e: Exception) {
                if (attempt > retries) {
                    Diag.log("http failed after $attempt attempts: $url", e)
                    throw java.io.IOException("网络请求失败: ${e.message}", e)
                }
                Diag.log("http attempt $attempt failed: $url -> ${e.message}")
                Thread.sleep(backoff)
                backoff = (backoff * 2).coerceAtMost(8000)
            }
        }
    }

    /** 下载可能较大的二进制（封面），带 Content-Type 与大小上限。 */
    fun getBytes(url: String, maxBytes: Long = 8L * 1024 * 1024): HttpResult {
        val r = get(url, retries = 2, accept = "image/*")
        if (r.body.size > maxBytes) throw java.io.IOException("响应过大: ${r.body.size} 字节")
        return r
    }

    fun encodeQuery(params: List<Pair<String, String>>): String =
        params.joinToString("&") { (k, v) ->
            java.net.URLEncoder.encode(k, "UTF-8") + "=" + java.net.URLEncoder.encode(v, "UTF-8")
        }

    fun ByteArrayOutputStream.utf8(s: String) = write(s.toByteArray(Charsets.UTF_8))
}
