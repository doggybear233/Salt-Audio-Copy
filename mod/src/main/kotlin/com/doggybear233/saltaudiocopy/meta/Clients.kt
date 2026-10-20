package com.doggybear233.saltaudiocopy.meta

import com.doggybear233.saltaudiocopy.Diag
import com.doggybear233.saltaudiocopy.scsi.DiscToc

/**
 * MusicBrainz discid 查询。一次请求带回 release 及其 media/tracks（`inc=recordings`），
 * 因此不需要对每个候选二次请求曲目。
 *
 * 注意 MB 限速 1 req/s：所有请求都经过 [Http.throttle]。
 */
object MusicBrainzClient {

    private const val BASE = "https://musicbrainz.org/ws/2"

    private val MB_THROTTLE_MS = 1100L

    fun lookup(toc: DiscToc, discId: String = DiscId.musicBrainz(toc)): List<AlbumMeta> {
        // 先看七天内的缓存：MB 限速 1 req/s，重抓/改设置时没必要再打一次
        MbCache.read(discId)?.let { cached ->
            val hit = runCatching {
                parseDiscidResponse(Json.parse(cached), toc, discId, DiscId.cddb(toc))
            }.getOrDefault(emptyList())
            if (hit.isNotEmpty()) return hit
        }
        val url = "$BASE/discid/$discId?fmt=json&inc=artists+recordings+release-groups+labels+isrcs&cdstubs=no"
        Http.throttle("musicbrainz.org", MB_THROTTLE_MS)
        val r = Http.get(url)
        if (r.status == 404) {
            Diag.log("mb: discid $discId not found (404)")
            return emptyList()
        }
        if (r.status != 200) {
            Diag.log("mb: discid $discId -> HTTP ${r.status}: ${r.text.take(200)}")
            return emptyList()
        }
        MbCache.write(discId, r.text)
        val root = Json.parse(r.text)
        val out = parseDiscidResponse(root, toc, discId, DiscId.cddb(toc))
        Diag.log("mb: discid $discId -> ${out.size} release(s)")
        return out
    }

    /** 解析 `/ws/2/discid/<id>` 的 JSON；抽出来便于用固定样本做单测。 */
    internal fun parseDiscidResponse(root: Any?, toc: DiscToc, discId: String, cddb: String): List<AlbumMeta> {
        val releases = Json.arr(Json.obj(root)["releases"])
        val out = ArrayList<AlbumMeta>(releases.size)
        for (rel in releases) {
            val o = Json.obj(rel)
            val id = Json.str(o["id"]) ?: continue
            val title = Json.str(o["title"]) ?: continue
            val artist = artistCredit(o) ?: "Unknown Artist"
            val date = Json.str(o["date"])
            val media = Json.arr(o["media"])
            val trackCount = toc.tracks.size
            // 优先选择轨数与光盘一致的 medium
            val medium = media.map { Json.obj(it) }
                .firstOrNull { (Json.long(Json.obj(it)["track-count"]) ?: -1L).toInt() == trackCount }
                ?: media.firstOrNull()?.let { Json.obj(it) }
            val tracks = ArrayList<TrackMeta>()
            if (medium != null) {
                for (t in Json.arr(medium["tracks"])) {
                    val to = Json.obj(t)
                    val num = (Json.long(to["number"]) ?: (tracks.size + 1).toLong()).toInt()
                    val tt = Json.str(to["title"]) ?: continue
                    val len = Json.long(to["length"])
                    val ta = artistCredit(to)
                    val isrc = Json.arr(Json.obj(to["recording"])["isrcs"])
                        .mapNotNull { Json.str(it) }.firstOrNull()
                    tracks += TrackMeta(num, tt, ta ?: artist, len, isrc)
                }
            }
            val rg = Json.obj(o["release-group"])
            val primary = Json.str(rg["primary-type"])
            val secondary = Json.arr(rg["secondary-types"]).mapNotNull { Json.str(it) }
            val genre = buildList {
                primary?.let { add(it) }
                secondary.take(2).forEach { add(it) }
            }.joinToString("/").ifBlank { null }
            out += AlbumMeta(
                albumArtist = artist,
                albumTitle = title,
                year = date?.take(4),
                genre = genre,
                discId = discId,
                cddbId = cddb,
                source = "musicbrainz",
                tracks = tracks,
                releaseId = id,
                totalDiscs = (Json.long(medium?.get("position")) ?: 1L).toInt().let { maxOf(it, 1) }
            )
        }
        return out
    }

    private fun artistCredit(node: Map<String, Any?>): String? {
        val credit = Json.arr(node["artist-credit"]).mapNotNull { c ->
            val co = Json.obj(c)
            val name = Json.str(co["name"]) ?: Json.str(Json.obj(co["artist"])["name"])
            val join = Json.str(co["joinphrase"]) ?: ""
            name?.let { it + join }
        }
        return credit.joinToString("").trim().ifBlank { null }
    }
}

/** Cover Art Archive：`front-<size>` 直接给缩略图，避免下载原图。 */
object CoverArtClient {

    /** size <= 0 表示取**原图**（用户要求默认原图，缩略图只在候选列表里用）。 */
    internal fun urlFor(releaseId: String, size: Int): String =
        if (size <= 0) "https://coverartarchive.org/release/$releaseId/front"
        else "https://coverartarchive.org/release/$releaseId/front-$size"

    fun front(releaseId: String, size: Int = 0): Pair<ByteArray, String>? {
        val url = urlFor(releaseId, size)
        return try {
            val r = Http.getBytes(url)
            if (r.status !in 200..299) {
                Diag.log("caa: $url -> HTTP ${r.status}")
                return null
            }
            val mime = r.contentType?.substringBefore(';')?.trim()
                ?: if (r.body.size > 3 && r.body[0] == 0xFF.toByte() && r.body[1] == 0xD8.toByte()) "image/jpeg" else "image/png"
            Diag.log("caa: $url -> ${r.body.size} B $mime")
            r.body to mime
        } catch (e: Exception) {
            Diag.log("caa: $releaseId front($size) failed", e)
            null
        }
    }
}

/**
 * gnudb（freedb 继任者）。纯文本协议：
 * `cddb query <discid> <tracks> <totalSecs>` → `210 <category> <discid>` 或 `211/202`。
 * `hello=` 参数必须是**已注册的客户端名**，否则返回 500（实测 `cddb`/`eac`/`ripper` 可用）。
 */
object GnudbClient {

    /**
     * CDDB 服务地址，按顺序试。
     *
     * 为什么要有镜像：gnudb 托管在 Hetzner，部分网络（本机实测）TCP 直接超时，
     * `Test-NetConnection gnudb.gnudb.org -Port 80` 23 秒都不通——不是插件写错。
     * dbpoweramp 的老镜像还能连（协议同样是 cddb.cgi）。
     */
    private val MIRRORS = listOf(
        "http://gnudb.gnudb.org/~cddb/cddb.cgi",
        "https://gnudb.gnudb.org/~cddb/cddb.cgi",
        "http://freedb.dbpoweramp.com/~cddb/cddb.cgi"
    )

    private val THROTTLE_MS = 500L

    /** 本次运行里已经证明连不上的镜像，别再每次都等一遍超时。 */
    private val deadMirrors: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

    /** 上一次实际成功的镜像（诊断用）。 */
    @Volatile
    var lastMirror: String? = null
        private set

    /**
     * CDDB 的 `hello` 参数在不同服务端实现里要的段数不一样：
     * gnudb 收 `名 版本 邮箱`（3 段），freedb 系老实现要 `user host client version`（4 段）。
     * 500 时换下一个变体再试同一镜像——比固定一个格式活得多。
     */
    private val HELLOS = listOf(
        "ripper 0.2 saltaudiocopy@example.invalid",
        "saltaudiocopy user saltaudiocopy 1.0",
        "ripper saltaudiocopy 0.2 saltaudiocopy@example.invalid"
    )

    private fun request(cmd: String): HttpResult {
        var lastError: Throwable? = null
        for (host in MIRRORS) {
            if (host in deadMirrors) continue
            var tried500: HttpResult? = null
            for (hello in HELLOS) {
                try {
                    val q = Http.encodeQuery(
                        listOf("cmd" to cmd, "hello" to hello, "proto" to "6")
                    )
                    Http.throttle(java.net.URI(host).host ?: host, THROTTLE_MS)
                    val r = Http.get("$host?$q", accept = "text/plain", connectMs = 2_500)
                    val first = r.text.lineSequence().firstOrNull().orEmpty()
                    if (first.startsWith("500")) {
                        Diag.log("cddb 500 from $host (hello=\"$hello\"): ${first.take(80)}")
                        tried500 = r
                        continue
                    }
                    lastMirror = host
                    return r
                } catch (e: Throwable) {
                    lastError = e
                    deadMirrors += host
                    Diag.log("cddb mirror unreachable: $host -> ${e.message}")
                    break
                }
            }
            // 连得上但握手一直 500：把最后一个响应交回去，让上层记日志。
            if (tried500 != null) {
                lastMirror = host
                return tried500
            }
        }
        throw java.io.IOException(
            "CDDB 服务全部连不上（${MIRRORS.size} 个镜像都失败：" +
                (lastError?.message ?: "未知原因") + "）",
            lastError
        )
    }

    data class Entry(val category: String, val discId: String)

    fun query(toc: DiscToc, cddbId: String = DiscId.cddb(toc)): List<Entry> {
        val total = toc.tracks.indices.sumOf { (toc.sectorsOf(it).second / 75) }
        val lines = request("cddb query $cddbId ${toc.tracks.size} $total").text.lines()
        if (lines.isEmpty()) return emptyList()
        val first = lines[0]
        val entries = ArrayList<Entry>()
        when {
            first.startsWith("210") -> {
                val parts = first.trim().split(Regex("\\s+"))
                if (parts.size >= 3) entries += Entry(parts[1], parts[2])
            }
            first.startsWith("211") -> {
                for (line in lines.drop(1)) {
                    if (line.startsWith(".")) break
                    val parts = line.trim().split(Regex("\\s+"))
                    if (parts.size >= 2 && parts[0].startsWith("21")) {
                        // 211 行里的格式是 <status> <category> <discid> <title>
                        if (parts.size >= 3) entries += Entry(parts[1], parts[2])
                    }
                }
            }
            else -> {
                Diag.log("gnudb query: ${first.take(120)}")
                if (first.startsWith("500")) {
                    Diag.log("gnudb 拒绝请求：hello 必须是已注册客户端「名 版本 邮箱」（当前 ripper 0.2 …）")
                }
            }
        }
        Diag.log("gnudb: $cddbId -> ${entries.size} entr(ies) ${entries.joinToString { "${it.category}/${it.discId}" }}")
        return entries
    }

    /** 读取一条记录，返回 (DTITLE, [track titles], 额外字段)。 */
    fun read(entry: Entry): AlbumMeta? {
        // 先看缓存：CDDB 条目几乎不变，而 gnudb 可能根本连不上。
        CddbCache.read(entry.discId)?.let { return it }
        return parseReadResponse(request("cddb read ${entry.category} ${entry.discId}").text.lines(), entry.discId)
    }

    /** 解析 `cddb read` 的响应文本；抽出来便于单测。 */
    internal fun parseReadResponse(lines: List<String>, cddbId: String = ""): AlbumMeta? {
        if (lines.firstOrNull()?.startsWith("210") != true) {
            Diag.log("gnudb read failed: ${lines.firstOrNull()?.take(120)}")
            return null
        }
        var dtitle = ""
        var dyear: String? = null
        var dgenre: String? = null
        val trackTitles = sortedMapOf<Int, StringBuilder>()
        for (line in lines) {
            when {
                line.startsWith("DTITLE=") -> dtitle += line.removePrefix("DTITLE=")
                line.startsWith("DYEAR=") -> dyear = line.removePrefix("DYEAR=").trim().ifBlank { null }
                line.startsWith("DGENRE=") -> dgenre = line.removePrefix("DGENRE=").trim().ifBlank { null }
                line.startsWith("TTITLE") -> {
                    val rest = line.removePrefix("TTITLE")
                    val eq = rest.indexOf('=')
                    if (eq > 0) {
                        val idx = rest.substring(0, eq).trim().toIntOrNull() ?: continue
                        trackTitles.getOrPut(idx) { StringBuilder() }.append(rest.substring(eq + 1))
                    }
                }
            }
        }
        val (artist, album) = splitTitle(dtitle)
        val tracks = trackTitles.map { (i, sb) ->
            val (tArtist, tTitle) = splitTitle(sb.toString())
            TrackMeta(i + 1, tTitle.ifBlank { "Track ${i + 1}" }, tArtist.ifBlank { null })
        }
        return AlbumMeta(
            albumArtist = artist.ifBlank { "Unknown Artist" },
            albumTitle = album.ifBlank { "Unknown Album" },
            year = dyear,
            genre = dgenre,
            discId = "",
            cddbId = cddbId,
            source = "gnudb",
            tracks = tracks
        )
    }

    /** freedb 的 `Artist / Album` 约定。 */
    private fun splitTitle(s: String): Pair<String, String> {
        val idx = s.indexOf(" / ")
        return if (idx > 0) s.substring(0, idx).trim() to s.substring(idx + 3).trim()
        else "" to s.trim()
    }
}
