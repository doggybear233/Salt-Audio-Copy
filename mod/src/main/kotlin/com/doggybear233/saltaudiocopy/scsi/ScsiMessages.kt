package com.doggybear233.saltaudiocopy.scsi

import com.doggybear233.saltaudiocopy.ui.Strings

/**
 * 把 SCSI 的 sense key / ASC / ASCQ 和 Win32 错误码翻译成一句人话。
 *
 * 用户看到的应该是「光驱里没有光盘」「这是数据盘」这种结论；`0x70/5/0x30/0x02`
 * 这类原始码只写进日志，供出问题时对账。
 */
object ScsiMessages {

    private fun keyMap() = mapOf(
        0x00 to Strings.message("没有错误"),
        0x01 to Strings.message("已恢复的错误"),
        0x02 to Strings.message("驱动器未就绪"),
        0x03 to Strings.message("介质读取错误"),
        0x04 to Strings.message("驱动器硬件错误"),
        0x05 to Strings.message("请求不被接受"),
        0x06 to Strings.message("驱动器状态已变化"),
        0x07 to Strings.message("介质写保护"),
        0x08 to Strings.message("空白检查失败"),
        0x0B to Strings.message("命令被中止")
    )

    private fun win32Map() = mapOf(
        2 to Strings.message("系统找不到这个驱动器（盘符可能已经变了）"),
        5 to Strings.message("Windows 拒绝了访问：驱动器可能正被别的程序占用"),
        15 to Strings.message("设备不存在"),
        21 to Strings.message("设备还没准备好（驱动器里可能没有光盘）"),
        32 to Strings.message("设备被其它程序占用"),
        87 to Strings.message("传给驱动器的参数不正确（这是插件的 bug，请把日志发给我们）"),
        1117 to Strings.message("驱动器 I/O 失败（硬件或线缆问题）")
    )

    fun human(t: Throwable): String = when (t) {
        is ScsiException -> human(t)
        else -> t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
    }

    fun human(e: ScsiException): String {
        specific(e.senseKey, e.asc, e.ascq)?.let { return it }
        e.win32Error.takeIf { it != 0 && e.scsiStatus == 0 }?.let { win ->
            win32Map()[win]?.let { return it }
        }
        // 结构化字段全是 0 时说明这条异常不是 sense 带回来的（插件自己抛的校验错误、
        // 或读取失败但没 sense 数据）。此时 [keyMap] 会命中 0x00 -> 「没有错误」，
        // 把「短读」「lead-out 缺失」解释成「一切正常」——必须改用异常自己的文案。
        if (e.senseKey == 0 && e.asc == 0 && e.ascq == 0 && e.win32Error == 0) {
            e.message?.takeIf { it.isNotBlank() }?.let { return it }
        }
        val key = keyMap()[e.senseKey] ?: Strings.fmt("未知状态 key=0x%02X", e.senseKey)
        return Strings.fmt("%s（ASC 0x%02X / ASCQ 0x%02X）", key, e.asc, e.ascq)
    }

    /**
     * 这条异常是否只是「驱动器里没有光盘」，而不是设备故障。
     *
     * 只看结构化的 sense / Win32 码，**不看文案**：文案会随界面语言变化，
     * 早先用「没有光盘」这种中文子串判定，英文/日文界面会把没盘当成设备故障并
     * 指数退避到 30 秒，插盘后半天不识别。
     */
    fun isNoDisc(t: Throwable): Boolean {
        var cur: Throwable? = t
        var depth = 0
        while (cur != null && depth < 8) {
            if (cur is ScsiException) {
                if (cur.win32Error == 21) return true
                if (cur.senseKey == 0x02 && (cur.asc == 0x3A || cur.asc == 0x04 || cur.asc == 0x3C)) return true
            }
            cur = cur.cause
            depth++
        }
        return false
    }

    /** 已知组合的具体说明；没命中就返回 null，交给 [human] 兜底。 */
    fun specific(key: Int, asc: Int, ascq: Int): String? = when {
        key == 0x02 && asc == 0x3A -> Strings.message("光驱里没有光盘（或托盘没关上）")
        key == 0x02 && asc == 0x04 -> Strings.message("光驱还没准备好：光盘可能刚放进去，等一两秒再试")
        key == 0x02 && asc == 0x3C -> Strings.message("光盘不在正确的位置上")
        key == 0x06 && asc == 0x28 -> Strings.message("光盘已经更换：请点「扫描」重新识别这张盘")
        key == 0x06 && asc == 0x29 -> Strings.message("驱动器被复位过：请点「扫描」重新识别")
        key == 0x05 && asc == 0x30 && ascq == 0x02 -> Strings.message("这不是音频 CD（数据盘/视频盘）：抓轨只支持音乐 CD")
        key == 0x05 && asc == 0x30 -> Strings.message("驱动器不认这张盘的格式")
        key == 0x05 && asc == 0x64 -> Strings.message("这张盘不支持这种读取方式（音轨模式不符）")
        key == 0x05 && asc == 0x20 -> Strings.message("驱动器不支持这条命令（读命令可换成 READ(10) 或 0xD8）")
        key == 0x05 && asc == 0x21 -> Strings.message("命令里的地址越界：这张盘可能已经换掉了")
        key == 0x05 && asc == 0x24 -> Strings.message("命令参数无效（CDB 字段不对）")
        key == 0x03 && asc == 0x11 -> Strings.message("音轨有无法纠正的读取错误（L-EC/CIRC）：盘面脏或划伤")
        key == 0x03 && asc == 0x02 -> Strings.message("找不到要读的扇区")
        key == 0x04 && asc == 0x08 -> Strings.message("驱动器报告通讯故障")
        else -> null
    }
}
