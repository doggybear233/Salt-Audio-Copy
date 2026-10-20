package com.doggybear233.saltaudiocopy

import com.doggybear233.saltaudiocopy.rip.ScanResult

/** 最近一次扫描结果的进程内缓存（M1 简化；M5 起改为持久的盘标识）。 */
object ScanResultHolder {
    @Volatile
    private var current: ScanResult? = null

    fun put(r: ScanResult) { current = r }
    fun get(): ScanResult? = current
    fun clear() { current = null }
}
