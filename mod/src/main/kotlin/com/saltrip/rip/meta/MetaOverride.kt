package com.saltrip.rip.meta

import com.saltrip.rip.Diag
import java.nio.file.Files
import java.nio.file.Path

/**
 * 手工元数据覆盖（`album.json`）。
 *
 * 为什么需要：日版/老盘的 MusicBrainz 命中率不高，gnudb 又常有乱码条目。
 * 与其让用户反复改文件名，不如给他一个「改一次、以后都按它来」的 JSON：
 *
 * ```json
 * {
 *   "albumArtist": "宇多田ヒカル",
 *   "albumTitle": "Fantôme",
 *   "year": "2016",
 *   "genre": "J-Pop",
 *   "discNumber": 1,
 *   "totalDiscs": 1,
 *   "tracks": [
 *     { "number": 1, "title": "道", "artist": "宇多田ヒカル", "isrc": "JPU901601234" }
 *   ]
 * }
 * ```
 *
 * 规则：只覆盖出现的字段；`tracks` 按 `number` 合并，缺的曲目沿用原值。
 * 认三个位置（按顺序，命中第一个就用）：`overrideFile` 设置 → `<输出根>/<discId>.album.json`
 * → `<输出根>/album.json`。解析失败只记日志，绝不让抓轨失败。
 */
object MetaOverride {

    fun resolvePath(settingsRoot: Path, discId: String?, explicit: String): Path? {
        if (explicit.isNotBlank()) {
            val p = runCatching { Path.of(explicit) }.getOrNull() ?: return null
            return if (Files.isRegularFile(p)) p else settingsRoot.resolve(explicit).takeIf { Files.isRegularFile(it) }
        }
        if (discId != null) {
            val p = settingsRoot.resolve("$discId.album.json")
            if (Files.isRegularFile(p)) return p
        }
        val generic = settingsRoot.resolve("album.json")
        return if (Files.isRegularFile(generic)) generic else null
    }

    /**
     * 手填元数据旁的封面文件：`<discId>.album.cover.jpg`。
     * 用户导入什么类型就用什么后缀（jpg/jpeg/png/webp/bmp/gif），取第一个存在的。
     */
    fun coverSibling(json: Path): Path? {
        val fileName = json.fileName.toString()
        val stem = fileName.removeSuffix(".json")
        val dir = json.parent ?: return null
        for (ext in listOf("jpg", "jpeg", "png", "webp", "bmp", "gif")) {
            val candidate = dir.resolve("$stem.cover.$ext")
            if (Files.isRegularFile(candidate)) return candidate
        }
        return null
    }

    fun load(path: Path): AlbumMeta? = runCatching {
        val text = Files.readString(path, Charsets.UTF_8)
        val root = Json.parse(text)
        val o = Json.obj(root)
        val tracks = ArrayList<TrackMeta>()
        for (t in Json.arr(o["tracks"])) {
            val to = Json.obj(t)
            val num = (Json.long(to["number"]) ?: 0L).toInt()
            if (num <= 0) continue
            tracks += TrackMeta(
                number = num,
                title = Json.str(to["title"]) ?: "",
                artist = Json.str(to["artist"]),
                lengthMs = Json.long(to["lengthMs"]),
                isrc = Json.str(to["isrc"])
            )
        }
        AlbumMeta(
            albumArtist = Json.str(o["albumArtist"]) ?: "",
            albumTitle = Json.str(o["albumTitle"]) ?: "",
            year = Json.str(o["year"]),
            genre = Json.str(o["genre"]),
            discId = Json.str(o["discId"]) ?: "",
            cddbId = Json.str(o["cddbId"]) ?: "",
            source = "album.json",
            tracks = tracks,
            releaseId = null,
            // 0 表示「未指定」，merge 时据此保留原值（AlbumMeta 里碟号是非空 Int，默认 1）。
            discNumber = (Json.long(o["discNumber"]) ?: 0L).toInt(),
            totalDiscs = (Json.long(o["totalDiscs"]) ?: 0L).toInt()
        )
    }.map { meta ->
        val cover = coverSibling(path)
        if (cover != null) {
            val bytes = runCatching { Files.readAllBytes(cover) }.getOrNull()
            val mime = when (cover.fileName.toString().substringAfterLast('.').lowercase()) {
                "png" -> "image/png"
                "webp" -> "image/webp"
                "bmp" -> "image/bmp"
                "gif" -> "image/gif"
                else -> "image/jpeg"
            }
            if (bytes != null && bytes.isNotEmpty()) meta.withCover(bytes, mime) else meta
        } else {
            meta
        }
    }.onFailure { Diag.log("album.json 解析失败: ${it.message}") }.getOrNull()

    /** 用 override 覆盖 base 中「有值」的字段；tracks 按轨号逐条覆盖。 */
    fun merge(base: AlbumMeta?, override: AlbumMeta): AlbumMeta {
        val merged = ArrayList<TrackMeta>()
        val byOverride = override.tracks.associateBy { it.number }
        val baseTracks = base?.tracks ?: emptyList()
        for (t in baseTracks) {
            val ov = byOverride[t.number]
            merged += if (ov == null) t else t.copy(
                title = ov.title.ifBlank { t.title },
                artist = ov.artist ?: t.artist,
                isrc = ov.isrc ?: t.isrc,
                lengthMs = ov.lengthMs ?: t.lengthMs
            )
        }
        // override 里有、base 里没有的轨号（例如整盘手工录入）
        for (ov in override.tracks) if (baseTracks.none { it.number == ov.number }) merged += ov
        merged.sortBy { it.number }

        return AlbumMeta(
            albumArtist = override.albumArtist.ifBlank { base?.albumArtist ?: "" },
            albumTitle = override.albumTitle.ifBlank { base?.albumTitle ?: "" },
            year = override.year ?: base?.year,
            genre = override.genre ?: base?.genre,
            discId = base?.discId ?: override.discId,
            cddbId = base?.cddbId ?: override.cddbId,
            source = listOfNotNull(base?.source, "album.json").joinToString("+"),
            tracks = merged,
            releaseId = base?.releaseId,
            discNumber = override.discNumber.takeIf { it > 0 } ?: base?.discNumber ?: 1,
            totalDiscs = override.totalDiscs.takeIf { it > 0 } ?: base?.totalDiscs ?: 1
        ).let { if (base?.cover != null) it.withCover(base.cover!!, base.coverMime) else it }
    }
}
