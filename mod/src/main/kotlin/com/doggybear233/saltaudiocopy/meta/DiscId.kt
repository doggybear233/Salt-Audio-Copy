package com.doggybear233.saltaudiocopy.meta

import com.doggybear233.saltaudiocopy.scsi.DiscToc
import java.security.MessageDigest
import java.util.Base64

/**
 * 光盘标识算法。两条通道都用「轨道起始 LBA + 150」的规矩（2 秒 lead-in），
 * 用裸 LBA 会被 MusicBrainz 判为 invalid discid。
 */
object DiscId {

    /** MusicBrainz DiscID：SHA1(首轨/末轨/lead-out/99 个轨道偏移) → 定制 Base64。 */
    fun musicBrainz(toc: DiscToc): String {
        val sb = StringBuilder(4 + 99 * 8)
        sb.append("%02X".format(toc.firstTrack))
        sb.append("%02X".format(toc.lastTrack))
        sb.append("%08X".format(toc.leadOutLba + 150))
        for (i in 1..99) {
            val e = toc.tracks.firstOrNull { it.track == i }
            sb.append(if (e == null) "00000000" else "%08X".format(e.lba + 150))
        }
        val sha1 = MessageDigest.getInstance("SHA-1").digest(sb.toString().toByteArray(Charsets.US_ASCII))
        return Base64.getEncoder().encodeToString(sha1)
            .replace('+', '.')
            .replace('/', '_')
            .replace('=', '-')
    }

    /** CDDB/freedb ID：8 位十六进制（disc id、曲目数、总秒数、crc）。 */
    fun cddb(toc: DiscToc): String {
        val ob = toc.tracks.map { it.lba + 150 }
        val leadOut = toc.leadOutLba + 150
        // 逐轨取整求和（(下一轨 - 本轨)/75），不能先用总时长取整
        var total = 0
        for (i in ob.indices) {
            val next = if (i + 1 < ob.size) ob[i + 1] else leadOut
            total += (next - ob[i]) / 75
        }
        val first = ob.first()
        val n = ((total - first / 75) % 255 + 255) % 255
        val crc = digitSum(total)
        return "%02x%02x%04x".format(n, ob.size, crc)
    }

    private fun digitSum(v0: Int): Int {
        var v = v0
        var s = 0
        while (v > 0) {
            s += v % 10
            v /= 10
        }
        return s
    }

    /** 便于日志/界面：`discid (cddb)` 组合。 */
    fun describe(toc: DiscToc): String = "${musicBrainz(toc)} (${cddb(toc)})"
}
