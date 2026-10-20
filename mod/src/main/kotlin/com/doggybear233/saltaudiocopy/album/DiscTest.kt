package com.doggybear233.saltaudiocopy.album

import com.doggybear233.saltaudiocopy.ui.Strings
import com.doggybear233.saltaudiocopy.rip.RipEngine
import com.doggybear233.saltaudiocopy.rip.TrackSource
import com.doggybear233.saltaudiocopy.scsi.DiscToc
import com.doggybear233.saltaudiocopy.rip.ScanResult
import java.nio.file.Paths

/**
 * 「只测不写」（EAC 的 Test only）：把整盘按正式抓轨的参数读一遍，算出每轨 CRC，
 * **不产生任何文件**。用途是先确认这张盘能否顺利读出、坏轨在哪，再决定是否真正抓取。
 *
 * 与 Test & Copy 的区别：那个是「测 + 抓」两遍；这里只有测试遍，落盘为零。
 */
object DiscTest {

    data class Row(
        val track: Int,
        val sectors: Int,
        val crc32: Long,
        val errors: Int,
        val elapsedMs: Long
    )

    /** 仅供 RipEngine 使用；`writeFile = false` 时该路径不会被创建。 */
    private val SCRATCH = Paths.get("__test_only__.tmp")

    fun run(
        scan: ScanResult,
        toc: DiscToc,
        cancel: () -> Boolean,
        onStage: (String) -> Unit,
        verify: Boolean,
        maxRetries: Int,
        retryDelayMs: Int,
        useC2: Boolean,
        defeatCache: Boolean = false,
        gapMethod: String = "q",
        tracks: List<Int> = toc.tracks.indices.toList(),
        source: TrackSource? = null
    ): List<Row> {
        val rows = ArrayList<Row>()
        for (i in tracks) {
            if (cancel()) break
            val entry = toc.tracks[i]
            onStage(Strings.fmt("测试读取第 %s 轨…", entry.track))
            val r = RipEngine.ripTrack(
                scan, i, SCRATCH, cancel, {},
                verify = verify,
                maxRetries = maxRetries,
                retryDelayMs = retryDelayMs,
                useC2 = useC2,
                defeatCache = defeatCache,
                writeFile = false,
                gapMethod = gapMethod,
                source = source
            )
            rows += Row(entry.track, r.sectors, r.crc32, r.readErrors, r.elapsedMs)
        }
        return rows
    }

    fun summary(rows: List<Row>): String {
        if (rows.isEmpty()) return Strings.message("没有测试任何音轨")
        val sb = StringBuilder()
        sb.append("轨  扇区数    CRC32     重试  用时\r\n")
        var bad = 0
        var total = 0L
        for (r in rows) {
            sb.append("%02d  %6d     %08X  %4d  %.1fs".format(r.track, r.sectors, r.crc32, r.errors, r.elapsedMs / 1000.0))
            if (r.errors > 0) { sb.append("  ⚠ " + Strings.message("有无法读取的扇区")); bad++ }
            sb.append("\r\n")
            total += r.sectors
        }
        sb.append(
            Strings.fmt(
                "合计 %s 轨 / %s 扇区 / %s",
                rows.size, total,
                if (bad == 0) Strings.message("全部读通")
                else Strings.fmt("%s 轨有错误", bad)
            )
        )
        return sb.toString()
    }
}
