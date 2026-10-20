package com.doggybear233.saltaudiocopy.meta

import com.doggybear233.saltaudiocopy.Diag
import com.doggybear233.saltaudiocopy.album.AlbumJsonWriter
import com.doggybear233.saltaudiocopy.scsi.DiscToc
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * CDDB（gnudb）查询结果缓存。
 *
 * 为什么单独一份：gnudb 在部分网络完全不通，而 CDDB 的条目一旦存在就基本不变。
 * 缓存用 `album.json` 的格式落盘，读回来直接走 [MetaOverride.load]，
 * 这样用户也能自己打开文件改——和「导出 album.json」是同一套东西。
 *
 * 位置：`<数据目录>/cache/cddb-<discid>.json`，TTL 默认 30 天。
 */
object CddbCache {

    @Volatile
    var dirOverride: Path? = null

    private const val DIR_NAME = "cache"
    const val DEFAULT_TTL_MS = 30L * 24 * 60 * 60 * 1000

    internal fun safeId(id: String): String =
        id.map { if (it.isLetterOrDigit() || it == '-' || it == '_' || it == '.') it else '_' }
            .joinToString("")

    private fun dir(): Path? {
        dirOverride?.let { return it }
        val base = Diag.dataDir() ?: return null
        return base.resolve(DIR_NAME)
    }

    fun file(id: String): Path? {
        if (id.isBlank()) return null
        return dir()?.resolve("cddb-${safeId(id)}.json")
    }

    fun read(id: String, ttlMs: Long = DEFAULT_TTL_MS): AlbumMeta? {
        val f = file(id) ?: return null
        return runCatching {
            if (!Files.isRegularFile(f)) return null
            val age = System.currentTimeMillis() - Files.getLastModifiedTime(f).toMillis()
            if (age > ttlMs) {
                Diag.log("cddb cache: expired ${f.fileName}")
                return null
            }
            MetaOverride.load(f)?.copy(source = "gnudb")?.also {
                Diag.log("cddb cache: hit ${f.fileName}（${it.tracks.size} 轨，${age / 1000}s 前）")
            }
        }.getOrNull()
    }

    fun write(id: String, album: AlbumMeta, toc: DiscToc) {
        val f = file(id) ?: return
        runCatching {
            Files.createDirectories(f.parent)
            writeTextAtomically(f, AlbumJsonWriter.build(album, toc))
        }.onFailure { Diag.log("cddb cache: write failed ${it.message}") }
    }

    fun clear(): Int {
        val d = dir() ?: return 0
        val files = runCatching {
            Files.list(d).use { s -> s.filter { it.fileName.toString().startsWith("cddb-") }.toList() }
        }.getOrDefault(emptyList())
        var n = 0
        for (f in files) if (runCatching { Files.deleteIfExists(f) }.getOrDefault(false)) n++
        Diag.log("cddb cache: cleared $n file(s)")
        return n
    }
}
