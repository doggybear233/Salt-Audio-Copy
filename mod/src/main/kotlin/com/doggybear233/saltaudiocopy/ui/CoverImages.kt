package com.doggybear233.saltaudiocopy.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.doggybear233.saltaudiocopy.Diag
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 封面缩略图解码。
 *
 * 缓存键是「字节内容哈希 + 目标尺寸」：同一次查询里同一张封面会被面板反复取用，
 * 每次重新解码会拖慢滚动与重组。
 *
 * **只缓存缩略图**：以前直接把原图整张解码进缓存（24 张 1200² 位图约 138 MB 堆），
 * 宿主本身是播放器，大封面下很容易 OOM 到面板消失。现在解码时就按 [TARGET_PX] 下采样。
 *
 * 解码是纯 CPU 活（几十 ms 级），调用方应放到 [kotlinx.coroutines.Dispatchers.IO]，
 * 不要直接在组合里调用。
 */
object CoverImages {

    /** 缩略图上限（像素）。面板里最大显示约 56dp，128 已足够且留了高 DPI 余量。 */
    const val TARGET_PX = 128

    private const val MAX_ENTRIES = 24
    private val cache = object : LinkedHashMap<Int, ImageBitmap?>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ImageBitmap?>?): Boolean =
            size > MAX_ENTRIES
    }

    @Synchronized
    fun decode(bytes: ByteArray?, targetPx: Int = TARGET_PX): ImageBitmap? {
        if (bytes == null || bytes.isEmpty()) return null
        val key = bytes.contentHashCode() * 31 + targetPx
        if (cache.containsKey(key)) return cache[key]
        val img = runCatching { downsample(bytes, targetPx).toComposeImageBitmap() }
            .onFailure { Diag.log("cover decode failed (${bytes.size} B)", it) }
            .getOrNull()
        cache[key] = img
        return img
    }

    /** 按「最长边 ≤ targetPx」缩放；原图本来就更小时原样返回。 */
    internal fun downsample(bytes: ByteArray, targetPx: Int = TARGET_PX): BufferedImage {
        val src = ImageIO.read(ByteArrayInputStream(bytes))
            ?: throw IllegalArgumentException("不认识的图片格式（${bytes.size} 字节）")
        val longest = max(src.width, src.height)
        if (longest <= targetPx) return src
        val scale = longest.toFloat() / targetPx
        val w = max(1, (src.width / scale).roundToInt())
        val h = max(1, (src.height / scale).roundToInt())
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.drawImage(src, 0, 0, w, h, null)
        } finally {
            g.dispose()
        }
        return out
    }

    @Synchronized
    fun clear() = cache.clear()
}
