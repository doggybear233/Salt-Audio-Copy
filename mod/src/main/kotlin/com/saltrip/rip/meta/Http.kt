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

    /** 可选 HTTP 代理；空 = 直连。由设置里的 `proxyUrl` 决定（见 [configure]）。 */
    @Volatile
    private var proxy: java.net.Proxy? = null

    /** 连接超时压到 4 秒：gnudb 在部分网络下是「丢包黑洞」，等 10 秒纯属干等。 */
    @Volatile
    private var connectTimeoutMs = 4_000

    @Volatile
    private var readTimeoutMs = 20_000

    /**
     * 配置代理与超时。设置改了要重新调；不调就用直连 + 默认超时。
     *
     * 代理写在设置里而不是靠 `-Dhttp.proxyHost`：宿主 JVM 是共享的，
     * 改全局属性会影响宿主自己的网络行为。
     */
    fun configure(proxyUrl: String = "", connectMs: Int = 4_000, readMs: Int = 20_000) {
        connectTimeoutMs = connectMs
        readTimeoutMs = readMs
        proxy = parseProxy(proxyUrl)
        if (proxyUrl.isNotBlank() && proxy == null) {
            com.saltrip.rip.Diag.log("proxy '$proxyUrl' 无法解析（需要 host:port 或 http://host:port），本次走直连")
        }
    }

    /** 接受 `127.0.0.1:7890`、`http://127.0.0.1:7890`；解析不了就当直连。 */
    internal fun parseProxy(raw: String): java.net.Proxy? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        val noScheme = s.removePrefix("http://").removePrefix("https://")
        val host = noScheme.substringBeforeLast(':', "").ifBlank { return null }
        val port = noScheme.substringAfterLast(':', "").toIntOrNull() ?: return null
        if (port !in 1..65535) return null
        return runCatching {
            java.net.Proxy(
                java.net.Proxy.Type.HTTP,
                java.net.InetSocketAddress(host, port)
            )
        }.getOrNull()
    }

    /** 当前是否走代理（诊断/日志用）。 */
    fun proxyInUse(): Boolean = proxy != null

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
        accept: String = "application/json",
        connectMs: Int = connectTimeoutMs,
        readMs: Int = readTimeoutMs
    ): HttpResult {
        var attempt = 0
        var backoff = 600L
        while (true) {
            attempt++
            try {
                val base = URI(url).toURL()
                val conn = ((if (proxy != null) base.openConnection(proxy) else base.openConnection()) as HttpURLConnection).apply {
                    readTimeout = readMs
                    connectTimeout = connectMs
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
                // 连不上（connect timed out / UnknownHost）重试也没用，只会把面板卡更久。
                val unreachable = e is java.net.UnknownHostException ||
                    (e is java.net.SocketTimeoutException && e.message?.contains("connect", true) == true)
                if (attempt > retries || unreachable) {
                    Diag.log("http failed after $attempt attempts: $url", e)
                    throw java.io.IOException("网络请求失败: ${e.message}", e)
                }
                Diag.log("http attempt $attempt failed: $url -> ${e.message}")
                Thread.sleep(backoff)
                backoff = (backoff * 2).coerceAtMost(8000)
            }
        }
    }

    /**
     * 只探测可达性：任何 HTTP 状态码都算「通」，只有连不上才抛。
     *
     * 用途是网络自检——MusicBrainz 对不存在的 discid 返回 404，那也是通的。
     */
    fun ping(url: String, connectMs: Int = 3_000, readMs: Int = 5_000): Long {
        val started = System.currentTimeMillis()
        get(url, retries = 0, accept = "*/*", connectMs = connectMs, readMs = readMs)
        return System.currentTimeMillis() - started
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
