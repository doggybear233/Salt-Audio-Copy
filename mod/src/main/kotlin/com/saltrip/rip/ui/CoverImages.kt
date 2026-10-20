package com.saltrip.rip.ui

import androidx.compose.ui.graphics.ImageBitmap
import com.saltrip.rip.Diag
import java.io.ByteArrayInputStream

/**
 * 封面缩略图解码。用 Compose Desktop 自带的 `loadImageBitmap`（宿主运行时里有 skiko），
 * 不额外引入图片库。
 *
 * 解码缓存按**字节内容的哈希**做键：同一次查询里同一张封面会被面板反复取用，
 * 每次重新解码（几十 KB JPEG）会拖慢滚动与重组。缓存只放缩略图，原图不进内存。
 */
object CoverImages {

    private const val MAX_ENTRIES = 24
    private val cache = object : LinkedHashMap<Int, ImageBitmap?>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ImageBitmap?>?): Boolean =
            size > MAX_ENTRIES
    }

    @Synchronized
    fun decode(bytes: ByteArray?): ImageBitmap? {
        if (bytes == null || bytes.isEmpty()) return null
        val key = bytes.contentHashCode()
        if (cache.containsKey(key)) return cache[key]
        val img = runCatching {
            androidx.compose.ui.res.loadImageBitmap(ByteArrayInputStream(bytes))
        }.onFailure { Diag.log("cover decode failed (${bytes.size} B)", it) }.getOrNull()
        cache[key] = img
        return img
    }

    @Synchronized
    fun clear() = cache.clear()
}
