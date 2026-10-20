package com.saltrip.rip

import com.saltrip.rip.album.DriveSelect
import com.saltrip.rip.meta.DiscId
import com.saltrip.rip.rip.RipEngine
import com.saltrip.rip.rip.ScanResult
import com.saltrip.rip.scsi.CdDevice
import com.saltrip.rip.scsi.DiscFingerprint
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

    fun info(r: ScanResult): PanelState.DiscInfo {
        val first = r.toc.tracks.firstOrNull()?.lba ?: 0
        val playable = (r.toc.leadOutLba - first).coerceAtLeast(0)
        return PanelState.DiscInfo(
            tracks = r.toc.tracks.size,
            artist = "",
            album = "",
            lengthText = PanelFormat.duration(playable * 1000L / 75L),
            discId = if (r.toc.isAudioCd) runCatching { DiscId.musicBrainz(r.toc) }.getOrDefault("") else "",
            audio = r.toc.isAudioCd
        )
    }

    fun publish(r: ScanResult) {
        ScanResultHolder.put(r)
        PanelState.showDisc(info(r))
    }

    /**
     * 重新读一次盘并更新缓存。
     *
     * @throws IllegalStateException 没有光驱 / 读不到任何一张盘时，带人话消息。
     */
    fun sync(preferred: String = ""): ScanResult {
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
        if (previous != null && !DiscFingerprint.same(previous.toc, chosen.toc)) {
            Diag.log(
                "disc changed: ${previous.path} [${previous.toc.tracks.size} 轨 leadOut=${previous.toc.leadOutLba}] -> " +
                    "${chosen.path} [${chosen.toc.tracks.size} 轨 leadOut=${chosen.toc.leadOutLba}]，已丢弃旧元数据"
            )
            PanelState.showDisc(null)
        }
        publish(chosen)
        return chosen
    }
}
