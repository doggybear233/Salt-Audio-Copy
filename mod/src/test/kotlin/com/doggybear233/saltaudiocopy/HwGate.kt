package com.doggybear233.saltaudiocopy

import org.junit.jupiter.api.Assumptions

/**
 * 真机测试的「媒体闸门」。
 *
 * 这台 HL-DT-ST BD-RE BP71N 在**无介质**时，任何触达设备的调用（连 `File("F:\\").exists()`
 * 的 `GetFileAttributes`）都会长时间不返回，直接把整个 test 任务卡死。
 * 所以真机测试默认全部 skip，只有显式设置环境变量 `SALTAC_HW=1` 才跑：
 *
 * ```
 * $env:SALTAC_HW='1'; .\gradlew.bat test --no-daemon
 * ```
 */
object HwGate {

    /** 有介质时用的盘符。 */
    const val DRIVE = "F:"

    /** 是否允许跑真机测试。 */
    fun enabled(): Boolean = System.getenv("SALTAC_HW") == "1"

    /** 未显式开启则整条测试 skip。 */
    fun assumeMedia() {
        Assumptions.assumeTrue(enabled(), "未设置 SALTAC_HW=1，跳过真机测试")
    }
}
