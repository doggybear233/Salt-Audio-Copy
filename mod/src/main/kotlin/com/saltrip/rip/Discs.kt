package com.saltrip.rip

import com.saltrip.rip.ui.Strings
import com.saltrip.rip.album.DriveSelect
import com.saltrip.rip.meta.DiscId
import com.saltrip.rip.rip.RipEngine
import com.saltrip.rip.rip.ScanResult
import com.saltrip.rip.scsi.CdDevice
import com.saltrip.rip.scsi.DiscFingerprint
import com.saltrip.rip.settings.SettingsStore
import com.saltrip.rip.ui.PanelFormat
import com.saltrip.rip.ui.PanelState

/**
 * 「现在驱动器里是哪张盘」的唯一真相来源。
 *
 * 之前的实现把扫描结果缓存进 [ScanResultHolder] 就一直用，换盘后仍按旧盘的
 * 元数据命名新盘（用户实测：新盘的音轨被写成了上一张盘的曲名和路径）。
 * 现在所有入口都先调 [sync] 重读 TOC；指纹变了就替换缓存并清掉面板上的旧信息。
 */
object Discs {

    /** 测试用：绕过宿主设置直接指定输出根（正常运行时为 null）。 */
    @Volatile
    internal var rootOverride: java.nio.file.Path? = null

    private fun root(): java.nio.file.Path? =
        rootOverride ?: runCatching { SettingsStore.load().effectiveRoot }.getOrNull()

    fun info(r: ScanResult): PanelState.DiscInfo {
        val first = r.toc.tracks.firstOrNull()?.lba ?: 0
        val playable = (r.toc.leadOutLba - first).coerceAtLeast(0)
        val discId = if (r.toc.isAudioCd) runCatching { DiscId.musicBrainz(r.toc) }.getOrDefault("") else ""
        // 本地缓存（含手填）里的歌手/专辑要带出来：换盘监视每 2 秒重建一次 DiscInfo，
        // 不能每次都把用户填的名字抹成空串（0.36.3 之后用户实测「保存了又变回去」）。
        val cached = cachedMeta(discId)
        return PanelState.DiscInfo(
            tracks = r.toc.tracks.size,
            artist = cached?.albumArtist.orEmpty(),
            album = cached?.albumTitle.orEmpty(),
            lengthText = PanelFormat.duration(playable * 1000L / 75L),
            discId = discId,
            audio = r.toc.isAudioCd
        )
    }

    /** 这张盘在本地的 album.json（手填或上次抓轨留下的），没有就返回 null。 */
    private fun cachedMeta(discId: String): com.saltrip.rip.meta.AlbumMeta? {
        if (discId.isBlank()) return null
        val base = root() ?: return null
        return runCatching { ConfigActions.readCachedAlbum(base, discId) }.getOrNull()
    }

    fun publish(r: ScanResult) {
        ScanResultHolder.put(r)
        val info = info(r)
        PanelState.showDisc(info)
        // 坞上的封面：候选里有就用候选的，没有就用本地缓存里的（手导的封面也在这）
        PanelState.discCover = cachedMeta(info.discId)?.cover
    }

    /**
     * 重新读一次盘并更新缓存。
     *
     * [quiet] 给换盘监视用：盘的指纹没变时只刷新缓存，**不重写面板状态**。
     * 监视线程每 2 秒扫一次，如果每次都 `publish`，用户刚填的歌手/专辑/封面
     * 会在两秒内被空值覆盖（0.36.4 修的就是这个）。
     *
     * @throws IllegalStateException 没有光驱 / 读不到任何一张盘时，带人话消息。
     */
    fun sync(preferred: String = "", quiet: Boolean = false): ScanResult {
        val drives = CdDevice.scanDrives()
        if (drives.isEmpty()) {
            throw IllegalStateException("没有检测到可用的光驱：光驱没连接，或驱动器里没有光盘。")
        }
        val candidates = drives.mapNotNull { (path, _) -> runCatching { RipEngine.scan(path) }.getOrNull() }
        if (candidates.isEmpty()) {
            val why = drives.firstOrNull()?.first?.let { p ->
                runCatching { RipEngine.scan(p) }.exceptionOrNull()
            }
            throw IllegalStateException(
                "找到了光驱，但读不到光盘信息" + (why?.let { "：" + com.saltrip.rip.scsi.ScsiMessages.human(it) } ?: "：可能是空盘或光盘还没转起来")
            )
        }
        val want = DriveSelect.normalize(preferred)
        val chosen = if (want.isBlank()) candidates.first()
        else candidates.firstOrNull { DriveSelect.normalize(it.path) == want }
            ?: throw IllegalStateException(
                "设置里指定了光驱 ${DriveSelect.describe(preferred)}，但没找到它（已发现: " +
                    candidates.joinToString { it.path } + "）"
            )

        val previous = ScanResultHolder.get()
        val changed = previous == null || !DiscFingerprint.same(previous.toc, chosen.toc)
        if (!changed) {
            // 同一张盘：只更新缓存对象，界面交给现有状态（保住手填的元数据与封面）
            ScanResultHolder.put(chosen)
            if (!quiet) publish(chosen)
            return chosen
        }
        Diag.log(
            "scan: ${chosen.path} -> ${chosen.toc.tracks.size} 轨 leadOut=${chosen.toc.leadOutLba} audioCd=${chosen.toc.isAudioCd}"
        )
        if (previous != null) {
            Diag.log(
                "disc changed: ${previous.path} [${previous.toc.tracks.size} 轨 leadOut=${previous.toc.leadOutLba}] -> " +
                    "${chosen.path} [${chosen.toc.tracks.size} 轨 leadOut=${chosen.toc.leadOutLba}]，已丢弃旧元数据"
            )
            PanelState.showDisc(null)
            PanelState.discCover = null
        }
        publish(chosen)
        return chosen
    }
}
