package com.doggybear233.saltaudiocopy.meta

import com.doggybear233.saltaudiocopy.Diag
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.zip.GZIPInputStream

/** HTTP 结果。状态码与字节都由调用方判断，避免把 404 当异常。 */
data class HttpResult(val status: Int, val body: ByteArray, val contentType: String?) {
    val text: String get() = body.toString(Charsets.UTF_8)
}

/**
 * 一次可取消的请求批次。
 *
 * 为什么要显式对象：以前取消是 `Http` 上一个全局布尔，只有一个地方复位（`MetadataCandidates.load`）。
 * 用户取消一次抓轨后，之后所有联网查询都直接抛「请求已取消」，而且 CDDB 镜像会被当成「连不上」
 * 永久拉黑。现在每次查询开头 [Http.beginScope] 拿一个新 scope，`abort()` 只影响当前这一个。
 */
class Scope internal constructor() {
    private val flag = java.util.concurrent.atomic.AtomicBoolean(false)
    val cancelled: Boolean get() = flag.get()
    fun cancel() {
        flag.set(true)
    }
}

/**
 * 用户取消导致的失败。
 *
 * 必须与 `SocketTimeoutException` 区分开——后者也是 `InterruptedIOException` 的子类，
 * 若按类型一刀切回退，会把「网络慢」当成「用户取消」直接放弃。
 */
class RequestCancelled(message: String = "请求已取消") : java.io.InterruptedIOException(message)


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
     * 当前请求批次。所有 `get`/`ping` 默认用它；[beginScope] 会换成一个干净的。
     */
    @Volatile
    private var current = Scope()

    /** 单次 Retry-After 的最大等待：服务器说「24 小时后再来」时不能真睡。 */
    const val MAX_RETRY_WAIT_MS = 15_000L

    /** 单次查询的总预算：镜像轮询 + 退避 + 重试加起来不超过这个时间。 */
    const val DEFAULT_DEADLINE_MS = 60_000L

    /** 实际发出的尝试次数（含第一次）。测试用它区分「重试了」和「一次就放弃」。 */
    private val attempts = java.util.concurrent.atomic.AtomicInteger(0)

    internal fun resetAttemptCounter() {
        attempts.set(0)
    }

    internal fun attemptCount(): Int = attempts.get()

    /** 开一个新的可取消批次。每次用户发起查询都该调一次。 */
    fun beginScope(): Scope = Scope().also { current = it }

    /** 兼容旧调用点：等价于开新批次。 */
    fun startRequest(): Scope = beginScope()

    fun activeScope(): Scope = current

    /** 取消当前批次；已经在跑的请求会在下一个检查点抛 [RequestCancelled]。 */
    fun abort() {
        current.cancel()
    }

    /**
     * 可中断的等待。每 100ms 检查一次取消标志，避免用户点了取消还要干等退避。
     */
    private fun sleepCancellable(ms: Long, scope: Scope) {
        val end = System.currentTimeMillis() + ms
        while (true) {
            if (scope.cancelled) throw RequestCancelled()
            val left = end - System.currentTimeMillis()
            if (left <= 0) return
            Thread.sleep(minOf(100L, left))
        }
    }

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
            com.doggybear233.saltaudiocopy.Diag.log("proxy '$proxyUrl' 无法解析（需要 host:port 或 http://host:port），本次走直连")
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
    fun throttle(host: String, minGapMs: Long, scope: Scope = current) {
        synchronized(throttleLock) {
            val now = System.currentTimeMillis()
            val prev = lastCall[host] ?: 0L
            val wait = minGapMs - (now - prev)
            if (wait > 0) sleepCancellable(wait, scope)
            lastCall[host] = System.currentTimeMillis()
        }
    }

    fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        retries: Int = 3,
        accept: String = "application/json",
        connectMs: Int = connectTimeoutMs,
        readMs: Int = readTimeoutMs,
        maxBytes: Long = Long.MAX_VALUE,
        scope: Scope = current,
        deadlineMs: Long = DEFAULT_DEADLINE_MS
    ): HttpResult {
        val startedAt = System.currentTimeMillis()
        var attempt = 0
        var backoff = 600L
        // 上一次「服务器明确回了话但让我们等等」（503/429）。预算耗尽时要把它交回调用方：
        // 用户需要看到「服务器限流」，而不是一句「网络请求超时」。
        var lastResponse: HttpResult? = null
        while (true) {
            attempt++
            attempts.incrementAndGet()
            if (scope.cancelled) throw RequestCancelled()
            val spent = System.currentTimeMillis() - startedAt
            if (spent > deadlineMs) {
                Diag.log("http deadline ${deadlineMs}ms exceeded: $url")
                lastResponse?.let {
                    Diag.log("http deadline hit, returning last status ${it.status} for $url")
                    return it
                }
                throw java.io.IOException("网络请求超时：${deadlineMs / 1000} 秒内没完成（$url）")
            }
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
                    // Retry-After 可能是「3600」这种离谱值（甚至远超一次查询的耐心），
                    // 必须夹到上限，否则单线程队列会被一个响应堵死。
                    val asked = conn.getHeaderField("Retry-After")?.toLongOrNull()?.times(1000)
                    val left = (deadlineMs - (System.currentTimeMillis() - startedAt)).coerceAtLeast(0)
                    val wait = (asked ?: backoff).coerceIn(0L, MAX_RETRY_WAIT_MS).coerceAtMost(left)
                    Diag.log("http $code from $url, retry $attempt in ${wait}ms (asked ${asked ?: backoff})")
                    lastResponse = HttpResult(code, ByteArray(0), conn.contentType)
                    runCatching { conn.disconnect() }
                    sleepCancellable(wait, scope)
                    backoff = (backoff * 2).coerceAtMost(8000)
                    continue
                }
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                // 先用 Content-Length 挡一次：真到读完再判，几 GB 的响应已经进内存了。
                val declared = conn.contentLengthLong
                if (declared > maxBytes) {
                    runCatching { stream?.close(); conn.disconnect() }
                    throw java.io.IOException("响应过大: 声明 $declared 字节，上限 $maxBytes 字节")
                }
                val bytes = stream?.let { s ->
                    val raw = if (conn.contentEncoding?.contains("gzip", true) == true) GZIPInputStream(s) else s
                    raw.use { readCapped(it, maxBytes, scope) }
                } ?: ByteArray(0)
                val ct = conn.contentType
                runCatching { conn.disconnect() }
                return HttpResult(code, bytes, ct)
            } catch (e: RequestCancelled) {
                // 用户取消：不要重试、不要包装成「网络请求失败」，也不要被上层当成镜像挂掉。
                Diag.log("http cancelled by user: $url")
                throw e
            } catch (e: Exception) {
                // 连不上（拒绝连接 / 无路由 / connect timed out / UnknownHost）重试也没用，只会把面板卡更久。
                val unreachable = e is java.net.UnknownHostException ||
                    e is java.net.ConnectException ||
                    e is java.net.NoRouteToHostException ||
                    (e is java.net.SocketTimeoutException && e.message?.contains("connect", true) == true)
                if (attempt > retries || unreachable) {
                    Diag.log("http failed after $attempt attempts: $url", e)
                    throw java.io.IOException("网络请求失败: ${e.message}", e)
                }
                Diag.log("http attempt $attempt failed: $url -> ${e.message}")
                val left = (deadlineMs - (System.currentTimeMillis() - startedAt)).coerceAtLeast(0)
                sleepCancellable(minOf(backoff, left), scope)
                backoff = (backoff * 2).coerceAtMost(8000)
            }
        }
    }

    /**
     * 只探测可达性：任何 HTTP 状态码都算「通」，只有连不上才抛。
     *
     * 用途是网络自检——MusicBrainz 对不存在的 discid 返回 404，那也是通的。
     */
    fun ping(
        url: String,
        connectMs: Int = 3_000,
        readMs: Int = 5_000,
        scope: Scope = current
    ): Long {
        val started = System.currentTimeMillis()
        get(url, retries = 0, accept = "*/*", connectMs = connectMs, readMs = readMs, scope = scope)
        return System.currentTimeMillis() - started
    }

    /** 边读边计数，超上限立刻放弃（服务器没给 Content-Length 时的兜底）。 */
    private fun readCapped(stream: java.io.InputStream, maxBytes: Long, scope: Scope): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            if (scope.cancelled) throw RequestCancelled()
            val n = stream.read(buf)
            if (n < 0) break
            total += n
            if (total > maxBytes) throw java.io.IOException("响应过大: 超过上限 $maxBytes 字节")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** 下载可能较大的二进制（封面），带 Content-Type 与大小上限。 */
    fun getBytes(
        url: String,
        maxBytes: Long = 8L * 1024 * 1024,
        scope: Scope = current,
        retries: Int = 2,
        connectMs: Int = connectTimeoutMs,
        readMs: Int = readTimeoutMs
    ): HttpResult = get(
        url,
        retries = retries,
        accept = "image/*",
        maxBytes = maxBytes,
        scope = scope,
        connectMs = connectMs,
        readMs = readMs
    )

    fun encodeQuery(params: List<Pair<String, String>>): String =
        params.joinToString("&") { (k, v) ->
            java.net.URLEncoder.encode(k, "UTF-8") + "=" + java.net.URLEncoder.encode(v, "UTF-8")
        }

    fun ByteArrayOutputStream.utf8(s: String) = write(s.toByteArray(Charsets.UTF_8))
}
