package com.saltrip.rip.scsi

/**
 * 光盘指纹：判断「驱动器里现在这张盘，还是不是刚才那张」。
 *
 * 只取 TOC 的稳定字段（lead-out、轨数、每轨起始 LBA、是否音频轨）。
 * 故意不掺元数据/CD-TEXT：换盘后曲目布局完全相同的概率极低，
 * 而「误判成同一张」会把两张盘的音轨混写进同一个目录——代价远大于多问一次网络。
 */
object DiscFingerprint {

    fun of(toc: DiscToc): String = buildString(96) {
        append(toc.leadOutLba)
        append('|').append(toc.tracks.size)
        for (t in toc.tracks) {
            append('|').append(t.track).append(',').append(t.lba).append(',')
            append(if (t.isAudio) 'A' else 'D')
        }
    }

    fun same(a: DiscToc?, b: DiscToc?): Boolean {
        if (a == null || b == null) return a == null && b == null
        return of(a) == of(b)
    }
}
