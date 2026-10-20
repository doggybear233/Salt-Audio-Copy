package com.saltrip.rip.album

import java.nio.file.Path

/**
 * 每张盘一份元数据副本，放在输出根的隐藏目录里。
 *
 * 位置选在输出根（而不是插件数据目录），是为了让「换个输出目录重抓」也能带上同批元数据，
 * 用户想整体搬走专辑目录时不会因为缓存散落在别处而丢信息。
 */
object DiscCache {

    const val DIR_NAME = ".saltaudiocopy"

    fun path(root: Path, discId: String): Path {
        val safe = discId.filter { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }
            .ifBlank { "unknown" }
        return root.resolve(DIR_NAME).resolve("album-$safe.json")
    }
}
