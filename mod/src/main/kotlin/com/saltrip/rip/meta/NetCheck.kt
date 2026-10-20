package com.saltrip.rip.meta

import com.saltrip.rip.Diag
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 网络自检：告诉用户「查不到元数据」到底是盘没记录，还是网根本不通。
 *
 * 背景：gnudb 托管在 Hetzner，本机实测 TCP 80/443 都 connect timeout，
 * 而 MusicBrainz 1 秒内就能回 404。没有这个自检时，用户只能看到「没查到专辑信息」，
 * 完全分不清是软件坏了、盘没有、还是被墙了。
 */
object NetCheck {

    data class Item(
        val name: String,
        val url: String,
        val ok: Boolean,
        val ms: Long,
        val note: String = ""
    )

    internal data class Target(val name: String, val url: String)

    /** 每个都选「能区分通/不通」的地址：404 也算通。 */
    internal val TARGETS = listOf(
        Target("MusicBrainz", "https://musicbrainz.org/ws/2/discid/jCz7JPOv0e14IuzcyS.StppLPio-?fmt=json"),
        Target("Cover Art Archive", "https://coverartarchive.org/"),
        Target("CDDB gnudb", "http://gnudb.gnudb.org/~cddb/cddb.cgi?cmd=stat&hello=ripper+0.2+saltrip%40example.invalid&proto=6"),
        Target("CDDB gnudb (HTTPS)", "https://gnudb.gnudb.org/~cddb/cddb.cgi?cmd=stat&hello=ripper+0.2+saltrip%40example.invalid&proto=6"),
        Target("CDDB dbpoweramp", "http://freedb.dbpoweramp.com/~cddb/cddb.cgi?cmd=stat&hello=ripper+0.2+saltrip%40example.invalid&proto=6")
    )

    /**
     * 并发探测全部目标；总耗时由最慢的一个决定，不会 5 个串着等。
     * 代理在这里也生效，方便用户「填完代理点一下自检」立即看到效果。
     */
    fun run(
        proxyUrl: String = "",
        connectMs: Int = 2_500,
        readMs: Int = 6_000,
        deadlineMs: Long = 9_000
    ): List<Item> {
        Http.configure(proxyUrl)
        val pool = Executors.newFixedThreadPool(TARGETS.size) { r ->
            Thread(r, "saltaudiocopy-netcheck").apply { isDaemon = true }
        }
        return try {
            val tasks = TARGETS.map { t ->
                Callable {
                    try {
                        val ms = Http.ping(t.url, connectMs = connectMs, readMs = readMs)
                        Item(t.name, t.url, ok = true, ms = ms)
                    } catch (e: Throwable) {
                        Item(t.name, t.url, ok = false, ms = 0, note = brief(e))
                    }
                }
            }
            val futures = pool.invokeAll(tasks, deadlineMs, TimeUnit.MILLISECONDS)
            futures.mapIndexed { i, f ->
                if (f.isCancelled) {
                    Item(TARGETS[i].name, TARGETS[i].url, ok = false, ms = deadlineMs, note = "超时未返回")
                } else {
                    runCatching { f.get() }.getOrElse {
                        Item(TARGETS[i].name, TARGETS[i].url, ok = false, ms = 0, note = brief(it))
                    }
                }
            }.also { items ->
                for (it in items) Diag.log("netcheck: ${it.name} ok=${it.ok} ${it.ms}ms ${it.note}")
            }
        } finally {
            pool.shutdownNow()
        }
    }

    internal fun brief(e: Throwable): String {
        val m = e.message ?: e.javaClass.simpleName
        return when {
            m.contains("timed out", true) || m.contains("超时") -> "连接超时"
            e is java.net.UnknownHostException -> "域名解析失败"
            m.contains("refused", true) -> "连接被拒绝"
            else -> m.take(60)
        }
    }

    /** 通知条上的一句话结论；纯函数，便于单测。 */
    internal fun summary(items: List<Item>): String {
        if (items.isEmpty()) return "网络自检：没有可检测的目标"
        val ok = items.filter { it.ok }
        val bad = items.filterNot { it.ok }
        if (bad.isEmpty()) {
            val worst = ok.maxOf { it.ms }
            return "网络自检：全部可达（最慢 ${worst}ms）"
        }
        val names = bad.joinToString("、") { it.name }
        return if (ok.isEmpty()) {
            "网络自检：全部不通（$names）——检查代理或网络"
        } else {
            "网络自检：${ok.size}/${items.size} 可达，不通的是 $names"
        }
    }
}
