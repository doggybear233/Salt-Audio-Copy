package com.doggybear233.saltaudiocopy.scsi

import java.lang.foreign.MemorySegment

/**
 * SCSI 命令的窄传输接口。
 *
 * 存在的唯一理由：让 [CdDevice] 的命令级逻辑（读命令回退、短读判定、C2 剥离）
 * 能在 CI 里用内存假实现验证，不需要真光驱。以前的 11 个 `SALTAC_HW` 用例
 * 在这台机器之外全是 skipped，等于这些分支从未被任何一次构建执行过。
 *
 * 真实实现是 [SptiTransport]（SPTI + `DeviceIoControl`）。
 */
internal interface ScsiTransport : AutoCloseable {

    /** 下发 CDB 并读回 [size] 字节（`dataIn = true`）。 */
    fun execute(cdb: ByteArray, size: Int): ByteArray

    /** 下发只写的 CDB（MODE SELECT 等），[payload] 放在数据输出方向。 */
    /** 返回驱动实际写入的字节数（MODE SELECT 部分写入要求 == payload.size）。 */
    fun executeOut(cdb: ByteArray, payload: ByteArray): Int

    /** 读进调用方缓冲，返回实际传输字节数（可能小于 [size]，由调用方判短读）。 */
    fun executeInto(cdb: ByteArray, buf: MemorySegment, size: Int, timeoutSec: Int = 10): Int

    override fun close()
}

/** SPTI 实现：把现有 [Sptd] 调用原样转发。 */
internal class SptiTransport(private val handle: MemorySegment) : ScsiTransport {
    override fun execute(cdb: ByteArray, size: Int): ByteArray = Sptd.execute(handle, cdb, size)

    override fun executeOut(cdb: ByteArray, payload: ByteArray): Int =
        Sptd.executeOut(handle, cdb, payload)

    override fun executeInto(cdb: ByteArray, buf: MemorySegment, size: Int, timeoutSec: Int): Int =
        Sptd.executeInto(handle, cdb, buf, size, timeoutSec)

    override fun close() = Win32.close(handle)
}
