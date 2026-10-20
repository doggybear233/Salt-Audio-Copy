package com.doggybear233.saltaudiocopy

import com.doggybear233.saltaudiocopy.ui.CoverImages
import com.doggybear233.saltaudiocopy.ui.PanelState
import com.doggybear233.saltaudiocopy.ui.SettingsSpec
import com.doggybear233.saltaudiocopy.ui.optionLabel
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.math.max
import kotlin.test.assertTrue

/**
 * 批次 15：显示值与实际值必须一致。
 *
 * 这一批的共同点是「界面说的和引擎做的不一样」：候选高亮不代表已生效、
 * 下拉框拿第一项冒充未知值、封面缓存塞原图、诊断文案硬编码中文。
 */
class Batch15Test {

    @Test
    fun unknownOptionValueIsShownVerbatim() {
        val item = assertNotNull(SettingsSpec.item("driveSpeed"))
        // 7056 是自动探测/手改配置可能写进来的值，不在选项表里
        val text = optionLabel(item, "7056")
        assertTrue(text.contains("7056"), "实际值必须原样显示，不能拿第一项冒充：$text")
        assertTrue(text.contains("非预设"), text)
        // 已知值仍走翻译
        val known = optionLabel(item, "1200")
        assertTrue(!known.contains("1200（非预设）"), known)
    }

    @Test
    fun coverThumbnailIsDownsampledBeforeCaching() {
        // 只测解码/缩放这一段：toComposeImageBitmap 要 skiko 原生库，单测环境里不可用
        val small = CoverImages.downsample(pngOf(1200, 1200))
        assertTrue(small.width <= CoverImages.TARGET_PX && small.height <= CoverImages.TARGET_PX,
            "缩略图最长边应 ≤ ${CoverImages.TARGET_PX}，实际 ${small.width}x${small.height}")
        assertEquals(CoverImages.TARGET_PX, maxOf(small.width, small.height))
    }

    @Test
    fun smallCoverIsNotUpscaled() {
        val small = CoverImages.downsample(pngOf(64, 32))
        assertEquals(64, small.width)
        assertEquals(32, small.height)
    }

    @Test
    fun candidateNamesComeFromStructuredFieldsNotTheLabel() {
        val tricky = PanelState.Candidate(
            index = 0,
            label = "A · B · X（Deluxe）Y · 12 轨",
            source = "musicbrainz",
            albumArtist = "A · B",
            albumTitle = "X（Deluxe）Y"
        )
        val cand = ConfigActions.candidateFor(tricky)
        assertEquals("A · B", cand.albumArtist)
        assertEquals("X（Deluxe）Y", cand.albumTitle)
    }

    private fun pngOf(w: Int, h: Int): ByteArray {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = Color(200, 160, 40)
        g.fillRect(0, 0, w, h)
        g.dispose()
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "png", out)
        return out.toByteArray()
    }
}
