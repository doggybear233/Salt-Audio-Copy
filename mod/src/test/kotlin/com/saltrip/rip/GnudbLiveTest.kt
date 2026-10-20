package com.saltrip.rip

import com.saltrip.rip.meta.GnudbClient
import com.saltrip.rip.scsi.CdDevice
import org.junit.jupiter.api.Assumptions
import kotlin.test.Test

/**
 * gnudb 真机连通性测试：需要 F: 上有音频 CD 且能访问网络，否则 skip。
 * 打印 status / 候选条数 / 第一条的专辑名，便于判断 `hello=` 是否被接受。
 */
class GnudbLiveTest {

    @Test
    fun queriesRealDisc() {
        HwGate.assumeMedia()
        val dev = runCatching { CdDevice("\\\\.\\F:") }.getOrNull()
        Assumptions.assumeTrue(dev != null, "F: 不可用")
        val toc = runCatching { dev!!.readToc() }.getOrNull()
        dev!!.close()
        Assumptions.assumeTrue(toc != null && toc.isAudioCd, "介质不是音频 CD，跳过")

        val entries = runCatching { GnudbClient.query(toc!!) }.getOrNull()
        println("gnudb entries=${entries?.size} -> ${entries?.take(3)}")
        Assumptions.assumeTrue(entries != null, "gnudb 不可达，跳过")
        if (entries!!.isEmpty()) {
            println("gnudb 无候选（该盘未收录，或 status 非 200）")
            return
        }
        val album = runCatching { GnudbClient.read(entries[0]) }.getOrNull()
        println("gnudb album=${album?.albumArtist} / ${album?.albumTitle} tracks=${album?.tracks?.size}")
    }
}
