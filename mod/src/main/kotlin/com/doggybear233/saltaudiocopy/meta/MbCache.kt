package com.doggybear233.saltaudiocopy.meta

import com.doggybear233.saltaudiocopy.Diag
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * MusicBrainz 响应的磁盘缓存。
 *
 * 动机很实际：MB 限速 1 请求/秒，抓一张盘要查 DiscID + Cover Art 至少两次；重抓、改设置、跑测试
 * 都会重复请求，很容易撞 503。DiscID 对应的响应在几天内是稳定的，缓存 7 天既省时间也避免被限流。
 *
 * 位置：`<插件数据目录>/cache/mb-<discId>.json`（不写用户音乐目录）。
 * 失败一律静默降级——缓存坏了就当没有。
 */
object MbCache {

    /** 测试可注入；默认用 [Diag.dataDir]。 */
    @Volatile
    var dirOverride: Path? = null

    private const val DIR_NAME = "cache"
    const val DEFAULT_TTL_MS = 7L * 24 * 60 * 60 * 1000

    private fun dir(): Path? {
        dirOverride?.let { return it }
        val base = Diag.dataDir() ?: return null
        return base.resolve(DIR_NAME)
    }

    private fun file(discId: String): Path? {
        if (discId.isBlank()) return null
        val safe = discId.map { if (it.isLetterOrDigit() || it == '-' || it == '_' || it == '.') it else '_' }
            .joinToString("")
        return dir()?.resolve("mb-$safe.json")
    }

    /** 命中且未过期时返回缓存的响应正文。 */
    fun read(discId: String, ttlMs: Long = DEFAULT_TTL_MS): String? {
        val f = file(discId) ?: return null
        return runCatching {
            if (!Files.isRegularFile(f)) return null
            val age = System.currentTimeMillis() - Files.getLastModifiedTime(f).toMillis()
            if (age > ttlMs) {
                Diag.log("mb cache: expired ${f.fileName}")
                return null
            }
            Files.readString(f, StandardCharsets.UTF_8).also {
                Diag.log("mb cache: hit ${f.fileName}（${it.length} 字符，${age / 1000}s 前）")
            }
        }.getOrNull()
    }

    fun write(discId: String, text: String) {
        val f = file(discId) ?: return
        runCatching {
            Files.createDirectories(f.parent)
            Files.writeString(f, text, StandardCharsets.UTF_8)
        }.onFailure { Diag.log("mb cache: write failed ${it.message}") }
    }

    /** 清空缓存（诊断用）。 */
    fun clear(): Int {
        val d = dir() ?: return 0
        return runCatching {
            if (!Files.isDirectory(d)) return 0
            var n = 0
            Files.list(d).use { s ->
                s.filter { it.fileName.toString().startsWith("mb-") }.forEach { p ->
                    if (runCatching { Files.deleteIfExists(p) }.getOrDefault(false)) n++
                }
            }
            n
        }.getOrDefault(0)
    }
}
