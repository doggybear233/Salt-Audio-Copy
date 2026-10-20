package com.doggybear233.saltaudiocopy.scsi

/**
 * 音频读取命令的几种形态（对应 EAC 的 "Extraction command set"）。
 *
 * 老式/特殊驱动器对 MMC 的标准 READ CD(0xBE) 支持不好时会只认厂家命令，
 * 所以 EAC 会列出七八种读法并允许手工切换。这里保留可解释的三种加一个厂家变体：
 *
 * - [MMC]：READ CD(0xBE)，CDB[1]=0x04（Expected Sector Type = CD-DA），CDB[9]=0xF8
 *   （用户数据 2352 + 每扇区 96 字节子通道）。
 *   这是 MMC 标准写法，绝大多数驱动器可用。
 * - [MMC_C2]：同上，CDB[9]=0xFA = 用户数据 + 每扇区 294 字节 C2 信息，**不含子通道**。
 *   两者是互斥的读法：要 C2 就没有子通道，要子通道就没有 C2（MMC 规定）。
 * - [READ10]：老式 SCSI-2 READ(10) 的音频读法（0x28，不带 sub-channel/sector-type 概念，
 *   块长由驱动器当前模式决定）。少数早期 SCSI 驱动器只认它。
 * - [D8]：厂家私有命令（多见于旧 CD-ROM），LBA 与长度都按 3 字节大端排布，**实验性**。
 *
 * CDB 一律 12 字节（D8 也按 12 下发，多余字节为 0），长度字段用大端。
 */
enum class ReadCommand(val id: String, val description: String) {
    MMC("mmc", "READ CD(0xBE) 标准 MMC"),
    MMC_C2("mmc-c2", "READ CD(0xBE) + C2 指针"),
    READ10("read10", "READ(10) 音频读法"),
    D8("d8", "厂家命令 0xD8（实验性）");

    /** 构造 12 字节 CDB。`count` 由调用方保证落在驱动允许的单次范围内。 */
    fun cdb(lba: Int, count: Int): ByteArray {
        val cdb = ByteArray(12)
        when (this) {
            MMC, MMC_C2 -> {
                cdb[0] = 0xBE.toByte()
                cdb[1] = 0x04.toByte()
                // READ CD 的 LBA 是 CDB[2..5] 四字节大端；写成 3 字节会让 LBA 左移 8 位
                be32(cdb, 2, lba)
                be24(cdb, 6, count)
                cdb[9] = (if (this == MMC_C2) 0xFA else 0xF8).toByte()
            }
            READ10 -> {
                cdb[0] = 0x28.toByte()
                be32(cdb, 2, lba)
                cdb[7] = ((count ushr 8) and 0xFF).toByte()
                cdb[8] = (count and 0xFF).toByte()
            }
            D8 -> {
                cdb[0] = 0xD8.toByte()
                be24(cdb, 2, lba)
                be24(cdb, 6, count)
            }
        }
        return cdb
    }

    companion object {
        /** 试错顺序：标准写法优先，越往后越「偏门」。 */
        val FALLBACK: List<ReadCommand> = listOf(MMC, READ10, D8)

        fun byId(id: String?): ReadCommand? =
            entries.firstOrNull { it.id.equals(id?.trim(), ignoreCase = true) }

        /** `auto`/空 → null（表示按 [FALLBACK] 试）；非法值也返回 null。 */
        fun parsePreferred(spec: String?): ReadCommand? =
            when (val s = spec?.trim()?.lowercase()) {
                null, "", "auto" -> null
                else -> byId(s)
            }

        /** 从首选命令开始的候选序列（首选项同时把 C2 变体排在最前）。 */
        fun order(preferred: ReadCommand?): List<ReadCommand> {
            val base = when (preferred) {
                null -> FALLBACK
                MMC_C2 -> listOf(MMC_C2) + FALLBACK
                else -> listOf(preferred) + FALLBACK.filter { it != preferred }
            }
            return base.distinct()
        }

        private fun be24(b: ByteArray, off: Int, v: Int) {
            b[off] = ((v ushr 16) and 0xFF).toByte()
            b[off + 1] = ((v ushr 8) and 0xFF).toByte()
            b[off + 2] = (v and 0xFF).toByte()
        }

        private fun be32(b: ByteArray, off: Int, v: Int) {
            b[off] = ((v ushr 24) and 0xFF).toByte()
            b[off + 1] = ((v ushr 16) and 0xFF).toByte()
            b[off + 2] = ((v ushr 8) and 0xFF).toByte()
            b[off + 3] = (v and 0xFF).toByte()
        }
    }
}

/**
 * 「命令不被接受就换下一种」的状态机，纯逻辑、可脱机测试。
 *
 * 只在**命令级错误**（ILLEGAL REQUEST / 无效字段 / 介质不兼容）时切换；
 * 真正的读错误（L-EC、CIRC、超时）不切换——那说明命令是通的，换命令只会掩盖问题。
 */
class ReadCommandPlan(
    preferred: ReadCommand?,
    private val allowFallback: Boolean = true,
    private val log: (String) -> Unit = {}
) {

    private val order: List<ReadCommand> = ReadCommand.order(preferred)
    private var index = 0

    val current: ReadCommand get() = order[index]
    val exhausted: Boolean get() = index >= order.size - 1
    val tried: List<ReadCommand> get() = order.take(index + 1)

    /**
     * 失败后决定下一个命令。返回 true 表示「已切换，可以用 [current] 重试」。
     * 已是最后一个候选则返回 false（调用方应把原始异常抛出去）。
     */
    fun advanceAfter(e: Throwable): Boolean {
        if (!allowFallback) return false
        if (!isCommandLevelFailure(e)) return false
        if (exhausted) return false
        val from = current
        index++
        val detail = (e as? ScsiException)?.let { "key=0x%02X ASC=0x%02X".format(it.senseKey, it.asc) } ?: e.message
        log("read command ${from.id} 不被接受（$detail），改用 ${current.id}")
        return true
    }

    companion object {
        /** 命令层面「不认识这个 CDB」的错误；真正的读写错误不算。 */
        fun isCommandLevelFailure(e: Throwable): Boolean {
            val s = e as? ScsiException ?: return false
            return when {
                s.senseKey == 0x05 -> true                    // ILLEGAL REQUEST
                s.senseKey == 0x03 && s.asc == 0x30 -> false  // 介质不兼容是盘的问题，不是命令
                s.asc in 0x20..0x26 -> true                   // INVALID COMMAND / FIELD / PARAMETER
                s.asc == 0x24 || s.asc == 0x26 -> true
                else -> false
            }
        }
    }
}

/**
 * 全局读命令偏好：设置页改一次，之后新建的 [CdDevice] 都按它起步。
 *
 * 之所以用一个小单例而不是层层传参：需要它的地方（抓轨、预扫、CD-TEXT、探针）
 * 都在不同包、且都要「当前设置」这一份同样的值。
 */
object ReadCommandPreference {
    @Volatile
    var preferred: ReadCommand? = null

    fun apply(spec: String?) {
        preferred = ReadCommand.parsePreferred(spec)
    }
}
