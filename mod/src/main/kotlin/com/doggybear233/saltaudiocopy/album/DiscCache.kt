package com.doggybear233.saltaudiocopy.album

import com.doggybear233.saltaudiocopy.Diag
import com.doggybear233.saltaudiocopy.meta.AlbumMeta
import com.doggybear233.saltaudiocopy.meta.MetaOverride
import java.nio.file.Files
import java.nio.file.Path

/**
 * 每张盘一份元数据副本，放在输出根的隐藏目录里。
 *
 * 位置选在输出根（而不是插件数据目录），是为了让「换个输出目录重抓」也能带上同批元数据，
 * 用户想整体搬走专辑目录时不会因为缓存散落在别处而丢信息。
 *
 * 但「用户改输出根」是另一回事：改完之后新根里没有缓存，专辑名/手填信息会瞬间变成「未知」。
 * 所以除了输出根，还有一个**不随输出根移动**的保险副本（[vaultPath]），查找顺序见 [searchPaths]。
 */
object DiscCache {

    const val DIR_NAME = ".saltaudiocopy"

    fun path(root: Path, discId: String): Path =
        root.resolve(DIR_NAME).resolve("album-${safeId(discId)}.json")

    /** 测试缝：把保险副本目录指到临时目录。 */
    internal var vaultOverride: Path? = null

    /** 插件数据目录里的保险副本；宿主数据目录不可用时返回 null。 */
    fun vaultPath(discId: String): Path? =
        (vaultOverride ?: Diag.dataDir()?.resolve("album-cache"))
            ?.resolve("album-${safeId(discId)}.json")

    /** 系统「音乐」文件夹——很多用户第一次抓轨都落在那里，换根后仍能找到。 */
    private fun musicPath(discId: String): Path? =
        runCatching { path(com.doggybear233.saltaudiocopy.scsi.Win32.musicFolder(), discId) }.getOrNull()

    /**
     * 查元数据时的搜索顺序：当前输出根 → 保险副本 → 系统音乐文件夹。
     *
     * 顺序有讲究：输出根里的副本是用户最近一次操作的产物，优先信它。
     */
    fun searchPaths(root: Path?, discId: String): List<Path> {
        if (discId.isBlank()) return emptyList()
        val list = ArrayList<Path>(3)
        if (root != null) list.add(path(root, discId))
        vaultPath(discId)?.let { list.add(it) }
        musicPath(discId)?.let { list.add(it) }
        return list.distinct()
    }

    /** 按 [searchPaths] 顺序读第一份能解析的元数据。 */
    fun load(root: Path?, discId: String): AlbumMeta? {
        for (p in searchPaths(root, discId)) {
            if (!Files.isRegularFile(p)) continue
            val meta = MetaOverride.load(p)
            if (meta != null) {
                if (root != null && p != path(root, discId)) {
                    Diag.log("album cache: ${path(root, discId)} 没有，改用 $p")
                }
                return meta
            }
        }
        return null
    }

    /**
     * 把已有的副本也写一份到保险目录。
     *
     * 手填元数据保存时调用：这样即使用户之后换输出根、甚至删掉整个输出目录，
     * 这张盘的歌手/专辑/歌名都还在。
     */
    fun mirrorToVault(root: Path, discId: String): Boolean {
        val src = path(root, discId)
        if (!Files.isRegularFile(src)) return false
        val dst = vaultPath(discId) ?: return false
        return runCatching {
            Files.createDirectories(dst.parent)
            Files.copy(src, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            // 封面是 JSON 的兄弟文件，一起搬
            MetaOverride.coverSibling(src)?.let { cover ->
                val target = dst.resolveSibling(cover.fileName.toString())
                Files.copy(cover, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
            true
        }.onFailure { Diag.log("album cache: 写保险副本失败 $dst", it) }.getOrDefault(false)
    }

    private fun safeId(discId: String): String =
        discId.filter { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }.ifBlank { "unknown" }
}
