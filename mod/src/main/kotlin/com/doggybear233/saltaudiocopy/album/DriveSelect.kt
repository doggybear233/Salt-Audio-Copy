package com.doggybear233.saltaudiocopy.album

import com.doggybear233.saltaudiocopy.rip.ScanResult
import com.doggybear233.saltaudiocopy.ui.Strings

/**
 * 多光驱时的选择。
 *
 * 用户可能同时接着内置 BD 刻录机和外置 DVD；之前的实现总是取「第一个能打开」的，
 * 在有盘的那台不是第一台时会表现为「明明插了盘却说没盘」。这里把选择逻辑抽成纯函数便于单测。
 */
object DriveSelect {

    /**
     * @param scans 已成功扫描的驱动器（顺序＝枚举顺序）
     * @param preferred 用户指定：`F`、`f:`、`\\.\F:` 都接受；空＝自动
     * @return 选中的扫描结果；指定了但没找到时返回 null（调用方据此报错而不是悄悄换一台）
     */
    fun pick(scans: List<ScanResult>, preferred: String): ScanResult? {
        if (scans.isEmpty()) return null
        val want = normalize(preferred)
        if (want.isNotEmpty()) return scans.firstOrNull { normalize(it.path) == want }
        // 自动模式优先「有音频 CD 的那台」：多光驱机器上按枚举顺序取第一台，
        // 常见结果是内置数据用 BD/DVD 顶掉外置音频盘，插了盘却显示「没有光盘」。
        return scans.firstOrNull { it.toc.isAudioCd } ?: scans.first()
    }

    /** `\\.\F:` / `F:` / `f` 统一成 `f`。 */
    fun normalize(path: String): String {
        var s = path.trim().uppercase()
        s = s.removePrefix("\\\\.\\")
        s = s.trimEnd(':', '\\')
        return s.lowercase()
    }

    /** 给设置里写的值一个稳定回显。 */
    fun describe(preferred: String): String =
        normalize(preferred).ifEmpty { Strings.message("自动") }.uppercase()
}
