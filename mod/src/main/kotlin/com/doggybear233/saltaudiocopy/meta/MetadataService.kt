package com.doggybear233.saltaudiocopy.meta

import com.doggybear233.saltaudiocopy.ui.Strings
import com.doggybear233.saltaudiocopy.Diag
import com.doggybear233.saltaudiocopy.scsi.CdText
import com.doggybear233.saltaudiocopy.scsi.DiscToc
import com.doggybear233.saltaudiocopy.settings.RipSettings

/**
 * 元数据编排，优先级从高到低：
 *
 * 1. **CD-TEXT**（盘上自带，最权威的曲名/艺术家来源）
 * 2. **MusicBrainz**（DiscID 精确匹配，提供年份/流派/发行版/封面）
 * 3. **gnudb**（老 freedb 数据，日版/老盘有时只有它有）
 * 4. **album.json** 手工覆盖（永远最后应用，压在最上面）
 *
 * 候选选择：同一 DiscID 常对应多个发行版（日版/港版/再版/多碟盒），
 * [RipSettings.preferTrackCount] 让轨数与光盘一致的优先，[RipSettings.releaseIndex] 再取第 N 个。
 */
object MetadataService {

    fun lookup(
        toc: DiscToc,
        settings: RipSettings,
        notes: MutableList<String> = ArrayList(),
        cdtext: CdText.Info? = null
    ): AlbumMeta? {
        // 抓轨路径也会走这里：代理必须在这里同步一次，否则「候选/自检能通、抓轨不能通」。
        Http.configure(settings.proxyUrl)
        val discId = runCatching { DiscId.musicBrainz(toc) }
            .onFailure { Diag.log("discid failed", it) }.getOrNull()
        val cddb = runCatching { DiscId.cddb(toc) }.getOrDefault("")
        notes += "DiscID: ${discId ?: "-"} / CDDB: $cddb"

        val fromCdText = cdtext?.takeIf { it.hasText }?.also {
            notes += "CD-TEXT: 「${it.albumTitle ?: "-"}」/ ${it.albumPerformer ?: "-"}，" +
                Strings.fmt("%s 轨标题（字符集 %s）", it.titles.size, it.charset)
        }

        // ---- MusicBrainz ----
        if (discId != null) {
            val releases = runCatching { MusicBrainzClient.lookup(toc, discId).releases }
                .onFailure {
                    Diag.log("mb lookup failed", it)
                    notes += Strings.fmt("MusicBrainz 查询失败: %s", it.message)
                }
                .getOrDefault(emptyList())
            val ranked = rank(releases, toc.tracks.size, settings.preferTrackCount)
            if (releases.isNotEmpty()) {
                val shown = ranked.take(8).mapIndexed { i, m ->
                    "  [$i] ${m.albumArtist} - ${m.albumTitle}" +
                        listOfNotNull(m.year, "轨数 " + m.tracks.size)
                            .joinToString("，", prefix = "（", postfix = "）")
                }
                notes += Strings.fmt("MusicBrainz: %s 个候选", releases.size) +
                    if (ranked.size > shown.size) Strings.fmt("（只列前 %s）", shown.size) else ""
                notes += shown
            }
            val idx = settings.releaseIndex.coerceIn(0, (ranked.size - 1).coerceAtLeast(0))
            val best = ranked.getOrNull(idx)
            if (best != null) {
                notes += "选用候选 [$idx]：「${best.albumArtist} - ${best.albumTitle}」"
                val cover = best.releaseId?.let {
                    runCatching { CoverArtClient.front(it, settings.coverSize) }
                        .onFailure { e -> Diag.log("caa failed", e) }.getOrNull()
                }
                val withCover = if (cover != null) {
                    notes += "封面: ${cover.first.size} 字节 (${cover.second})"
                    best.withCover(cover.first, cover.second)
                } else {
                    notes += "封面: 无"
                    best
                }
                return applyOverride(mergeCdText(fromCdText, withCover), toc, discId, settings, notes)
            }
            notes += "MusicBrainz 无此盘记录"
        }

        // ---- 只有 CD-TEXT 可用 ----
        if (fromCdText != null) {
            return applyOverride(
                fromCdText.toAlbumMeta(toc, discId ?: "", cddb), toc, discId, settings, notes
            )
        }

        // ---- gnudb ----
        val entries = runCatching { GnudbClient.query(toc, cddb) }
            .onFailure {
                Diag.log("gnudb query failed", it)
                notes += "gnudb 查询失败: ${it.message}"
            }
            .getOrDefault(emptyList())
        val pick = entries.firstOrNull { it.discId.equals(cddb, true) } ?: entries.firstOrNull()
        if (pick != null) {
            val album = runCatching { GnudbClient.read(pick) }
                .onFailure { Diag.log("gnudb read failed", it) }.getOrNull()
            if (album != null) {
                notes += "gnudb 命中（${pick.category}/${pick.discId}）"
                return applyOverride(album.withIds(discId ?: "", cddb), toc, discId, settings, notes)
            }
        } else {
            notes += "gnudb 无此盘记录"
        }
        notes += "未找到元数据，将使用占位名称（可导出 album.json 手工补）"
        return applyOverride(null, toc, discId, settings, notes)
    }

    /**
     * CD-TEXT 的曲名/艺术家压过 MusicBrainz；年份/流派/发行版/封面/时长仍取 MB（CD-TEXT 不含时长）。
     */
    internal fun mergeCdText(cd: CdText.Info?, mb: AlbumMeta): AlbumMeta {
        if (cd == null || !cd.hasText) return mb
        val tracks = mb.tracks.map { t ->
            t.copy(
                title = cd.titles[t.number]?.takeIf { it.isNotBlank() } ?: t.title,
                artist = cd.performers[t.number]?.takeIf { it.isNotBlank() } ?: t.artist
            )
        }
        return mb.copy(
            albumArtist = cd.albumPerformer?.takeIf { it.isNotBlank() } ?: mb.albumArtist,
            albumTitle = cd.albumTitle?.takeIf { it.isNotBlank() } ?: mb.albumTitle,
            genre = cd.genre?.takeIf { it.isNotBlank() } ?: mb.genre,
            tracks = tracks,
            source = "cd-text+" + mb.source
        )
    }

    /** 轨数一致的发行版排前面；[preferTrackCount] 关闭时保持 MB 原顺序。 */
    internal fun rank(list: List<AlbumMeta>, trackCount: Int, preferTrackCount: Boolean): List<AlbumMeta> {
        if (!preferTrackCount) return list
        val (match, rest) = list.partition { it.tracks.size == trackCount }
        return match + rest
    }

    /** 手工元数据覆盖：命中就用它；解析失败只记备注。 */
    internal fun applyOverride(
        base: AlbumMeta?,
        toc: DiscToc,
        discId: String?,
        settings: RipSettings,
        notes: MutableList<String>
    ): AlbumMeta? {
        val root = runCatching { settings.effectiveRoot }.getOrNull() ?: return base
        val path = MetaOverride.resolvePath(root, discId, settings.overrideFile) ?: return base
        val ov = MetaOverride.load(path) ?: run {
            notes += "album.json 存在但解析失败（已忽略）：$path"
            return base
        }
        val merged = MetaOverride.merge(base, ov)
        notes += "已应用手工覆盖：$path"
        Diag.log("metadata override applied: $path")
        return merged
    }
}
