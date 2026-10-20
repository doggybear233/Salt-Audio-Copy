package com.saltrip.rip.meta

import com.saltrip.rip.Diag
import com.saltrip.rip.scsi.CdText
import com.saltrip.rip.scsi.DiscToc
import com.saltrip.rip.settings.RipSettings

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
        val notes = ArrayList<String>()
        val discId = runCatching { DiscId.musicBrainz(toc) }
            .onFailure { Diag.log("discid failed", it) }.getOrNull().orEmpty()
        val cddb = runCatching { DiscId.cddb(toc) }.getOrDefault("")

        val out = ArrayList<Candidate>()

        if (discId.isNotBlank()) {
            val releases = runCatching { MusicBrainzClient.lookup(toc, discId) }
                .onFailure {
                    Diag.log("mb lookup failed", it)
                    notes += "MusicBrainz 查询失败：${it.message ?: it.javaClass.simpleName}"
                }
                .getOrDefault(emptyList())
            val ranked = MetadataService.rank(releases, toc.tracks.size, settings.preferTrackCount)
            if (releases.isEmpty()) notes += "MusicBrainz 没有这张盘的记录"
            ranked.take(12).forEachIndexed { i, m ->
                val cover = m.releaseId?.let { id ->
                    runCatching { CoverArtClient.front(id, THUMB_SIZE) }.getOrNull()
                }
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
            if (releases.isNotEmpty()) notes += "MusicBrainz 命中 ${releases.size} 个发行版（显示前 ${out.size} 个）"
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
            notes += "CD-TEXT 命中（盘上自带曲名）"
        }

        if (out.isEmpty()) {
            val entries = runCatching { GnudbClient.query(toc, cddb) }
                .onFailure { notes += "gnudb 查询失败：${it.message}" }
                .getOrDefault(emptyList())
            val pick = entries.firstOrNull { it.discId.equals(cddb, true) } ?: entries.firstOrNull()
            if (pick != null) {
                val album = runCatching { GnudbClient.read(pick) }.getOrNull()
                if (album != null) {
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

    /** 用户点选后取完整元数据（含原图尺寸由 [settings] 决定）。 */
    fun materialize(
        toc: DiscToc,
        c: Candidate,
        settings: RipSettings,
        cdtext: CdText.Info? = null,
        notes: MutableList<String> = ArrayList()
    ): AlbumMeta? {
        val discId = runCatching { DiscId.musicBrainz(toc) }.getOrDefault("")
        val cddb = runCatching { DiscId.cddb(toc) }.getOrDefault("")

        val base = when {
            c.source == "cd-text" && cdtext?.hasText == true -> cdtext.toAlbumMeta(toc, discId, cddb)
            c.source.startsWith("gnudb") -> {
                val entries = runCatching { GnudbClient.query(toc, cddb) }.getOrDefault(emptyList())
                val pick = entries.firstOrNull { it.discId.equals(cddb, true) } ?: entries.firstOrNull()
                val album = pick?.let { runCatching { GnudbClient.read(it) }.getOrNull() }
                album?.withIds(discId, cddb)
            }
            else -> {
                val releases = runCatching { MusicBrainzClient.lookup(toc, discId) }.getOrDefault(emptyList())
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
