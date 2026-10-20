package com.saltrip.rip.ui

import com.saltrip.rip.album.RipPlan
import com.saltrip.rip.meta.AlbumMeta
import com.saltrip.rip.naming.TemplateEngine
import com.saltrip.rip.settings.RipSettings
import java.nio.file.Path

/**
 * 坞上那行「输出预览」的真实计算。
 *
 * 以前这里直接用 `PanelState.lastOutDir`（上一次抓轨的目录），改过输出根之后仍显示旧路径，
 * 看起来像「设置没生效」。现在按当前设置 + 已知元数据把目录算出来，跟真正抓轨时 [RipPlan.build]
 * 用的是同一套模板与变量。
 */
internal object PathPreview {

    /**
     * @param artist 已解析的专辑艺术家（空串表示还不知道）
     * @param album 已解析的专辑名（空串表示还不知道）
     * @param meta 元数据（决定 %year%/%genre%/%discid% 等），没有就传 null
     * @param root 输出根（测试可注入；生产传 null 用 [RipSettings.effectiveRoot]）
     */
    fun folder(
        settings: RipSettings,
        artist: String,
        album: String,
        meta: AlbumMeta? = null,
        root: Path? = null
    ): String {
        val base = (root ?: settings.effectiveRoot).toString()
        if (album.isBlank() && artist.isBlank()) return "$base\\…"
        val vars = RipPlan.albumVars(meta, settings, artist, album)
        var dir = Path.of(base)
        TemplateEngine.expandToSegments(settings.folderTemplate, vars).forEach { dir = dir.resolve(it) }
        val totalDiscs = settings.totalDiscsOverride.takeIf { it > 0 } ?: meta?.totalDiscs ?: 1
        if (totalDiscs > 1 && !settings.folderTemplate.contains("%discnumber%")) {
            TemplateEngine.expandToSegments(settings.multiDiscFolder, vars).forEach { dir = dir.resolve(it) }
        }
        return dir.toString()
    }
}
