package com.saltrip.rip.album

import com.saltrip.rip.scsi.DiscToc

/**
 * 盘片身份守卫。
 *
 * 长盘抓取要几分钟到几十分钟，用户中途换碟（或者驱动自己掉线）并不罕见。
 * 如果没有这条检查，结果会是：前 5 轨来自 A 碟、后 5 轨来自 B 碟，CUE 与日志却是同一张专辑，
 * 这种污染比直接失败糟得多。所以每轨开始前重读一次 TOC，确认还是同一张盘。
 */
object DiscGuard {

    /** 判定两个 TOC 是否指向同一张盘：lead-out、轨数、每轨起始 LBA 全部一致。 */
    fun sameDisc(a: DiscToc, b: DiscToc): Boolean {
        if (a.leadOutLba != b.leadOutLba) return false
        if (a.tracks.size != b.tracks.size) return false
        return a.tracks.indices.all { a.tracks[it].lba == b.tracks[it].lba }
    }

    /** @return 不一致时返回给用户看的提示。 */
    fun mismatchMessage(expectedLeadOut: Int, actualLeadOut: Int, where: String): String =
        "$where 检测到光盘已变更（lead-out $expectedLeadOut → $actualLeadOut），" +
            "为避免把两张盘的音轨混在一起，已中止。已抓取的音轨保留在输出目录。"
}
