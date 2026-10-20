package com.saltrip.rip.scsi

/**
 * 把 SCSI 的 sense key / ASC / ASCQ 和 Win32 错误码翻译成一句人话。
 *
 * 用户看到的应该是「光驱里没有光盘」「这是数据盘」这种结论；`0x70/5/0x30/0x02`
 * 这类原始码只写进日志，供出问题时对账。
 */
object ScsiMessages {

    private val KEYS = mapOf(
        0x00 to "没有错误",
        0x01 to "已恢复的错误",
        0x02 to "驱动器未就绪",
        0x03 to "介质读取错误",
        0x04 to "驱动器硬件错误",
        0x05 to "请求不被接受",
        0x06 to "驱动器状态已变化",
        0x07 to "介质写保护",
        0x08 to "空白检查失败",
        0x0B to "命令被中止"
    )

    private val WIN32 = mapOf(
        2 to "系统找不到这个驱动器（盘符可能已经变了）",
        5 to "Windows 拒绝了访问：驱动器可能正被别的程序占用",
        15 to "设备不存在",
        21 to "设备还没准备好（驱动器里可能没有光盘）",
        32 to "设备被其它程序占用",
        87 to "传给驱动器的参数不正确（这是插件的 bug，请把日志发给我们）",
        1117 to "驱动器 I/O 失败（硬件或线缆问题）"
    )

    fun human(t: Throwable): String = when (t) {
        is ScsiException -> human(t)
        else -> t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
    }

    fun human(e: ScsiException): String {
        specific(e.senseKey, e.asc, e.ascq)?.let { return it }
        e.win32Error.takeIf { it != 0 && e.scsiStatus == 0 }?.let { win ->
            WIN32[win]?.let { return it }
        }
        val key = KEYS[e.senseKey] ?: String.format("未知状态 key=0x%02X", e.senseKey)
        return "$key（ASC 0x%02X / ASCQ 0x%02X）".format(e.asc, e.ascq)
    }

    /** 已知组合的具体说明；没命中就返回 null，交给 [human] 兜底。 */
    fun specific(key: Int, asc: Int, ascq: Int): String? = when {
        key == 0x02 && asc == 0x3A -> "光驱里没有光盘（或托盘没关上）"
        key == 0x02 && asc == 0x04 -> "光驱还没准备好：光盘可能刚放进去，等一两秒再试"
        key == 0x02 && asc == 0x3C -> "光盘不在正确的位置上"
        key == 0x06 && asc == 0x28 -> "光盘已经更换：请点「扫描」重新识别这张盘"
        key == 0x06 && asc == 0x29 -> "驱动器被复位过：请点「扫描」重新识别"
        key == 0x05 && asc == 0x30 && ascq == 0x02 -> "这不是音频 CD（数据盘/视频盘）：抓轨只支持音乐 CD"
        key == 0x05 && asc == 0x30 -> "驱动器不认这张盘的格式"
        key == 0x05 && asc == 0x64 -> "这张盘不支持这种读取方式（音轨模式不符）"
        key == 0x05 && asc == 0x20 -> "驱动器不支持这条命令（读命令可换成 READ(10) 或 0xD8）"
        key == 0x05 && asc == 0x21 -> "命令里的地址越界：这张盘可能已经换掉了"
        key == 0x05 && asc == 0x24 -> "命令参数无效（CDB 字段不对）"
        key == 0x03 && asc == 0x11 -> "音轨有无法纠正的读取错误（L-EC/CIRC）：盘面脏或划伤"
        key == 0x03 && asc == 0x02 -> "找不到要读的扇区"
        key == 0x04 && asc == 0x08 -> "驱动器报告通讯故障"
        else -> null
    }
}
