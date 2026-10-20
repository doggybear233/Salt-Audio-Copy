package com.doggybear233.saltaudiocopy.scsi

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_SHORT
import java.lang.invoke.MethodHandle

/**
 * kernel32 的最小 FFM 绑定。宿主以 `--enable-native-access=ALL-UNNAMED` 启动，
 * 因此这里不需要额外 JVM 参数。
 */
internal object Win32 {

    private val linker: Linker = Linker.nativeLinker()
    private val lookup: SymbolLookup = SymbolLookup.libraryLookup("kernel32", Arena.global())

    private val CAPTURE_LAST_ERROR: Linker.Option = Linker.Option.captureCallState("GetLastError")

    private fun handle(name: String, desc: FunctionDescriptor, capture: Boolean = false): MethodHandle {
        val sym = lookup.find(name).orElseThrow { UnsatisfiedLinkError("kernel32!$name") }
        // captureCallState 让 FFM 在调用返回的瞬间把 GetLastError 写进我们给的段。
        // 以前是「先调 CreateFileW，再单独 downcall GetLastError」——中间任何一次
        // downcall（含 FFM 内部的分配/查表）都可能覆盖线程 last-error，
        // 于是错误码时常变成 0，日志里的 GetLastError 就成了假信息。
        return if (capture) linker.downcallHandle(sym, desc, CAPTURE_LAST_ERROR) else linker.downcallHandle(sym, desc)
    }

    val createFileW: MethodHandle = handle(
        "CreateFileW",
        FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS),
        capture = true
    )

    val deviceIoControl: MethodHandle = handle(
        "DeviceIoControl",
        FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS),
        capture = true
    )

    val closeHandle: MethodHandle = handle("CloseHandle", FunctionDescriptor.of(JAVA_INT, ADDRESS))

    /** 返回 DRIVE_* 常量：2=可移动 3=固定 4=网络 5=光驱 6=RAM 盘。用于快速过滤盘符。 */
    val getDriveTypeW: MethodHandle = handle("GetDriveTypeW", FunctionDescriptor.of(JAVA_INT, ADDRESS))

    val getLastError: MethodHandle = handle("GetLastError", FunctionDescriptor.of(JAVA_INT))

    const val GENERIC_READ = 0x80000000.toInt()
    const val GENERIC_WRITE = 0x40000000
    const val FILE_SHARE_READ = 1
    const val FILE_SHARE_WRITE = 2
    const val OPEN_EXISTING = 3
    const val INVALID_HANDLE_VALUE = -1L

    /** UTF-16LE 以 NUL 结尾，写入 arena。 */
    fun wideString(arena: Arena, s: String): MemorySegment {
        val seg = arena.allocate((s.length + 1).toLong() * 2, 2)
        for (i in s.indices) seg.set(JAVA_SHORT, i.toLong() * 2, s[i].code.toShort())
        seg.set(JAVA_SHORT, s.length.toLong() * 2, 0)
        return seg
    }

    /** 仅用于诊断打印；错误路径请用 capture 段里的值（见 [createFileW]/[deviceIoControl]）。 */
    fun lastError(): Int = getLastError.invokeWithArguments() as Int

    fun openDevice(path: String, writable: Boolean): MemorySegment {
        // 名字缓冲用全局 arena：句柄需要在多次 IOCTL 之间长期存活。
        val name = wideString(Arena.global(), path)
        val access = if (writable) GENERIC_READ or GENERIC_WRITE else GENERIC_READ
        Arena.ofConfined().use { state ->
            val captured = state.allocate(JAVA_INT, 4)
            val h = createFileW.invokeWithArguments(
                captured, name, access, FILE_SHARE_READ or FILE_SHARE_WRITE,
                MemorySegment.NULL, OPEN_EXISTING, 0, MemorySegment.NULL
            ) as MemorySegment
            if (h.address() == INVALID_HANDLE_VALUE) {
                val err = captured.get(JAVA_INT, 0)
                throw ScsiException("CreateFileW($path) 失败, GetLastError=$err", err)
            }
            return h
        }
    }

    fun close(handle: MemorySegment) {
        runCatching { closeHandle.invokeWithArguments(handle) }
    }

    /**
     * 执行一次 DeviceIoControl；in/out 用同一块缓冲（SCSI_PASS_THROUGH_DIRECT 是 in-out 结构）。
     * @return lpBytesReturned
     */
    fun deviceIoControl(
        handle: MemorySegment, code: Int, buf: MemorySegment, size: Int, arena: Arena
    ): Int {
        val returned = arena.allocate(JAVA_INT, 4)
        val captured = arena.allocate(JAVA_INT, 4)
        val ok = deviceIoControl.invokeWithArguments(
            captured, handle, code, buf, size, buf, size, returned, MemorySegment.NULL
        ) as Int
        if (ok == 0) {
            val err = captured.get(JAVA_INT, 0)
            throw ScsiException("DeviceIoControl(0x${code.toString(16)}) 失败, GetLastError=$err", err)
        }
        return returned.get(JAVA_INT, 0)
    }

    fun readByte(seg: MemorySegment, off: Long): Int = seg.get(JAVA_BYTE, off).toInt() and 0xFF

    /**
     * 用户「音乐」文件夹。优先 `%USERPROFILE%\Music`，否则退回 Documents\Music、最后用家目录。
     * 刻意不调 SHGetKnownFolderPath：重定向场景少见，省掉一个 shell32 绑定。
     */
    fun musicFolder(): java.nio.file.Path {
        val home = java.nio.file.Path.of(System.getProperty("user.home") ?: ".")
        for (name in listOf("Music", "音乐")) {
            val p = home.resolve(name)
            if (java.nio.file.Files.isDirectory(p)) return p
        }
        val docs = home.resolve("Documents").resolve("Music")
        if (java.nio.file.Files.isDirectory(docs)) return docs
        return home.resolve("Music")
    }

    /** "F:" 这类盘符对应的 Windows 驱动器类型；光驱为 5。 */
    fun driveType(letter: Char): Int {
        val root = wideString(Arena.global(), "$letter:\\")
        return getDriveTypeW.invokeWithArguments(root) as Int
    }
}

class ScsiException(
    message: String,
    val win32Error: Int = 0,
    val scsiStatus: Int = 0,
    val senseKey: Int = 0,
    val asc: Int = 0,
    val ascq: Int = 0
) : Exception(message) {
    /** 介质不可用（非法请求/不可读）——这种错重试无意义。 */
    val isHardFailure: Boolean
        get() = scsiStatus == 0x02 && (senseKey == 0x05 || senseKey == 0x03 || (asc == 0x30 || asc == 0x64))
}
