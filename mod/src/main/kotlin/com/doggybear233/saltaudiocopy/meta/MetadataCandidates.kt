package com.doggybear233.saltaudiocopy.meta

import com.doggybear233.saltaudiocopy.ui.Strings
import com.doggybear233.saltaudiocopy.Diag
import com.doggybear233.saltaudiocopy.scsi.CdText
import com.doggybear233.saltaudiocopy.scsi.DiscToc
import com.doggybear233.saltaudiocopy.settings.RipSettings

/**
 * 候选元数据：同一张盘在 MusicBrainz 里常对应多个发行版（日版/港版/再版/多碟盒）。
 *
 * 以前只能靠 `releaseIndex` 盲选第 N 个——用户反馈「查到的是错版本」时无从下手。
 * 现在把候选整表交给面板，用户点一下就能换，选择结果落进 `album.json` 缓存，
 * 之后再抓同一张盘直接沿用。
 */
object MetadataCandidates {

    /** 一个可选发行版；[cover] 为缩略图字节，取不到就是 null（面板显示占位）。 */
    data class Candidate(
        val index: Int,
        val albumArtist: String,
        val albumTitle: String,
        val year: String?,
        val trackCount: Int,
        val releaseId: String?,
        val source: String,
        val cover: ByteArray? = null,
        val coverMime: String? = null
    ) {
        fun label(): String = buildString {
            append(albumArtist)
            append(" · ")
            append(albumTitle)
            if (!year.isNullOrBlank()) append("（").append(year).append("）")
            append(" · ").append(trackCount).append(" 轨")
        }
    }

    data class Result(
        val discId: String,
        val cddbId: String,
        val candidates: List<Candidate>,
        val notes: List<String>
    ) {
        val best: Candidate? get() = candidates.firstOrNull()
    }

    private const val THUMB_SIZE = 250

    /**
     * 拉取候选列表（**不下载原图**，只给缩略图，避免用户点开面板时卡住）。
     * CD-TEXT / gnudb 的命中作为单候选追加在 MusicBrainz 之后。
     */
    fun load(
        toc: DiscToc,
        settings: RipSettings,
        cdtext: CdText.Info? = null
    ): Result {
        // 设置里的代理每次查询前同步一次：用户改完设置不必重启宿主。
        Http.configure(settings.proxyUrl)
        // 每次查询一个新批次：上一次的取消不该影响这一次（旧实现是全局粘性开关）。
        val scope = Http.beginScope()
        if (Http.proxyInUse()) Diag.log("http proxy in use: ${settings.proxyUrl}")
        val notes = ArrayList<String>()
        val discId = runCatching { DiscId.musicBrainz(toc) }
            .onFailure { Diag.log("discid failed", it) }.getOrNull().orEmpty()
        val cddb = runCatching { DiscId.cddb(toc) }.getOrDefault("")

        val out = ArrayList<Candidate>()

        if (discId.isNotBlank()) {
            var mbError: Throwable? = null
            val mb = runCatching { MusicBrainzClient.lookup(toc, discId) }
                .onFailure {
                    mbError = it
                    Diag.log("mb lookup failed", it)
                }
                .getOrNull()
            // runCatching 会把取消也吞成「查询失败」，所以每条通道后都要显式放行取消。
            if (scope.cancelled) throw RequestCancelled()
            val releases = mb?.releases ?: emptyList()
            val ranked = MetadataService.rank(releases, toc.tracks.size, settings.preferTrackCount)
            if (releases.isEmpty()) {
                // 区分「库里确实没有」与「服务暂时不可用」：后者要说出来，
                // 否则用户以为这张盘真的没收录。失败说明只取本次查询的（F-288）。
                notes += mb?.failure
                    ?: mbError?.let {
                        Strings.fmt("MusicBrainz 查询失败：%s", it.message ?: it.javaClass.simpleName)
                    }
                    ?: Strings.message("MusicBrainz 没有这张盘的记录")
            }
            val top = ranked.take(12)
            // 12 张封面串行下载会把候选面板拖住十几秒（共享单线程上更糟）；
            // 并行取，顺序按候选排列保持不变。
            val covers = coverThumbs(top.map { it.releaseId }, scope)
            top.forEachIndexed { i, m ->
                val cover = covers.getOrNull(i)
                out += Candidate(
                    index = i,
                    albumArtist = m.albumArtist,
                    albumTitle = m.albumTitle,
                    year = m.year,
                    trackCount = m.tracks.size,
                    releaseId = m.releaseId,
                    source = m.source,
                    cover = cover?.first,
                    coverMime = cover?.second
                )
            }
            if (releases.isNotEmpty()) notes += Strings.fmt(
                    "MusicBrainz 命中 %s 个发行版（显示前 %s 个）", releases.size, out.size
                )
        }

        if (cdtext?.hasText == true) {
            val m = cdtext.toAlbumMeta(toc, discId, cddb)
            out += Candidate(
                index = out.size,
                albumArtist = m.albumArtist,
                albumTitle = m.albumTitle,
                year = null,
                trackCount = m.tracks.size,
                releaseId = null,
                source = "cd-text"
            )
            notes += Strings.message("CD-TEXT 命中（盘上自带曲名）")
        }

        // 先看 CDDB 缓存：gnudb 可能整段网络不通，但以前查过的盘不该再查一次。
        if (out.isEmpty()) {
            val cached = runCatching { CddbCache.read(cddb) }.getOrNull()
            if (cached != null) {
                out += Candidate(
                    index = 0,
                    albumArtist = cached.albumArtist,
                    albumTitle = cached.albumTitle,
                    year = cached.year,
                    trackCount = cached.tracks.size,
                    releaseId = null,
                    source = "gnudb"
                )
                notes += Strings.message("CDDB 缓存命中（离线也能用）")
            }
        }

        if (out.isEmpty()) {
            val entries = runCatching { GnudbClient.query(toc, cddb) }
                .onFailure { notes += "gnudb 查询失败：${it.message}" }
                .getOrDefault(emptyList())
            val pick = entries.firstOrNull { it.discId.equals(cddb, true) } ?: entries.firstOrNull()
            if (pick != null) {
                val album = runCatching { GnudbClient.read(pick) }.getOrNull()
                if (album != null) {
                    runCatching { CddbCache.write(pick.discId, album, toc) }
                    out += Candidate(
                        index = 0,
                        albumArtist = album.albumArtist,
                        albumTitle = album.albumTitle,
                        year = album.year,
                        trackCount = album.tracks.size,
                        releaseId = null,
                        source = album.source
                    )
                    notes += "gnudb 命中（${pick.category}/${pick.discId}）"
                }
            } else {
                notes += "gnudb 没有这张盘的记录"
            }
        }

        if (out.isEmpty()) notes += "没有可用元数据：将使用占位名称，可导出 album.json 手工补"
        return Result(discId, cddb, out, notes)
    }

    /**
     * 并行取候选缩略图，失败当没有封面。
     *
     * 三条硬约束（都是真机踩过的）：
     * 1. **有总预算**：12 张封面各等 20 秒，候选面板能卡几分钟；[budgetMs] 到点就交卷。
     * 2. **线程是 daemon 且命名**：以前默认线程工厂是非 daemon，`shutdownNow` 也不等，
     *    用户关面板后 JVM 里还挂着 `pool-N-thread-M`，宿主退出时会被它们拖住。
     * 3. **取消直达**：用户点取消时正在等封面，要立刻返回（返回 null 图，不留半截任务）。
     */
    private fun coverThumbs(
        ids: List<String?>,
        scope: Scope,
        budgetMs: Long = 4_000
    ): List<Pair<ByteArray, String>?> {
        if (ids.isEmpty()) return emptyList()
        val threads = java.util.concurrent.atomic.AtomicInteger(0)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(4) { r ->
            Thread(r, "saltaudiocopy-cover-${threads.incrementAndGet()}").apply { isDaemon = true }
        }
        return try {
            val tasks = ids.map { id ->
                java.util.concurrent.Callable {
                    if (scope.cancelled) return@Callable null
                    id?.let {
                        runCatching {
                            CoverArtClient.front(
                                it,
                                THUMB_SIZE,
                                scope = scope,
                                retries = 0,
                                connectMs = 1_500,
                                readMs = 3_000
                            )
                        }.getOrNull()
                    }
                }
            }
            val futures = pool.invokeAll(tasks, budgetMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            futures.map { f ->
                if (f.isCancelled) null else runCatching { f.get() }.getOrNull()
            }
        } catch (e: Throwable) {
            Diag.log("cover thumbs failed", e)
            ids.map { null }
        } finally {
            pool.shutdownNow()
            runCatching { pool.awaitTermination(250, java.util.concurrent.TimeUnit.MILLISECONDS) }
        }
    }

    /** 用户点选后取完整元数据（含原图尺寸由 [settings] 决定）。 */
    fun materialize(
        toc: DiscToc,
        c: Candidate,
        settings: RipSettings,
        cdtext: CdText.Info? = null,
        notes: MutableList<String> = ArrayList()
    ): AlbumMeta? {
        Http.configure(settings.proxyUrl)
        val discId = runCatching { DiscId.musicBrainz(toc) }.getOrDefault("")
        val cddb = runCatching { DiscId.cddb(toc) }.getOrDefault("")

        val base = when {
            c.source == "cd-text" && cdtext?.hasText == true -> cdtext.toAlbumMeta(toc, discId, cddb)
            c.source.startsWith("gnudb") -> {
                // 候选可能来自 CDDB 本地缓存（网络当时不通）；点选时若先去联网，
                // 断网环境下必然失败。缓存能读就用缓存。
                val cached = runCatching { CddbCache.read(cddb) }.getOrNull()
                if (cached != null) {
                    notes += "CDDB 缓存命中（离线可用）"
                    cached.withIds(discId, cddb)
                } else {
                    val entries = runCatching { GnudbClient.query(toc, cddb) }.getOrDefault(emptyList())
                    val pick = entries.firstOrNull { it.discId.equals(cddb, true) } ?: entries.firstOrNull()
                    val album = pick?.let { runCatching { GnudbClient.read(it) }.getOrNull() }
                    album?.withIds(discId, cddb)
                }
            }
            else -> {
                val releases = runCatching { MusicBrainzClient.lookup(toc, discId).releases }
                    .getOrDefault(emptyList())
                MetadataService.rank(releases, toc.tracks.size, settings.preferTrackCount)
                    .firstOrNull { it.releaseId != null && it.releaseId == c.releaseId }
                    ?: releases.firstOrNull { it.releaseId == c.releaseId }
            }
        } ?: return null

        val merged = MetadataService.mergeCdText(cdtext, base)
        val cover = merged.releaseId?.let { id ->
            runCatching { CoverArtClient.front(id, settings.coverSize) }
                .onFailure { Diag.log("caa failed", it) }.getOrNull()
        }
        val withCover = if (cover != null) {
            notes += "封面：${cover.first.size} 字节（${cover.second}）"
            merged.withCover(cover.first, cover.second)
        } else {
            notes += "封面：无"
            merged
        }
        return MetadataService.applyOverride(withCover, toc, discId, settings, notes)
    }
}
