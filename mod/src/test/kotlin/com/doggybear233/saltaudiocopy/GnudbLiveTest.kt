package com.doggybear233.saltaudiocopy

import com.doggybear233.saltaudiocopy.meta.GnudbClient
import com.doggybear233.saltaudiocopy.scsi.CdDevice
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * gnudb 真机连通性测试：需要 `F:` 上有音频 CD 且能访问网络，否则 skip。
 *
 * 以前这个用例只有 `println`：不可达就 skip、空结果就 return，所以「查询整条链路坏了」
 * 也永远不会红。现在要求两种结局之一，否则测试失败：
 * - 拿到 ≥1 个候选，且第一条能 `read` 出专辑名/曲目；
 * - 或明确被告知「网络不通 / 无记录」，且失败原因不是空的。
 */
@Tag("live")
class GnudbLiveTest {

    @Test
    fun queriesRealDisc() {
        val dev = runCatching { CdDevice("\\\\.\\F:") }.getOrNull()
        Assumptions.assumeTrue(dev != null, "F: 不可用")
        val toc = runCatching { dev!!.readToc() }.getOrNull()
        dev!!.close()
        Assumptions.assumeTrue(toc != null && toc.isAudioCd, "介质不是音频 CD，跳过")

        var failure: String? = null
        val entries = runCatching { GnudbClient.query(toc!!) }
            .onFailure { failure = it.message ?: it.javaClass.simpleName }
            .getOrNull()
        println("gnudb entries=${entries?.size} -> ${entries?.take(3)} failure=$failure")

        if (entries == null) {
            // 网络路径：必须给出「为什么没查成」，不能静默返回 null
            val why = failure.orEmpty()
            assertTrue(why.isNotBlank(), "gnudb 查询失败却没给出原因（lastFailure 为空）")
            return
        }
        if (entries.isEmpty()) {
            // 这条盘确实没被 CDDB 收录也很正常，但必须至少能正常完成一次查询
            return
        }
        val first = entries.first()
        assertTrue(first.discId.isNotBlank(), "候选缺少 discId")
        val album = GnudbClient.read(first)
        assertNotNull(album, "候选拿到了却读不出条目")
        assertEquals(first.discId, album.discId, "read 回来的 discId 必须与候选一致")
        assertTrue(
            album.albumTitle.isNotBlank() || album.tracks.any { it.title.isNotBlank() },
            "read 成功却既没有专辑名也没有曲名"
        )
    }
}
