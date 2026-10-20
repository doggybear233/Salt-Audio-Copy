package com.doggybear233.saltaudiocopy.meta

import com.doggybear233.saltaudiocopy.Diag
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * 原子写文本：先写同目录的 `.tmp`，再 move 到目标。
 *
 * 直接 `Files.writeString` 到目标文件的问题是：写到一半进程退出 / 磁盘满，
 * 留下来的就是**半截 JSON**。缓存读取侧会把它当成「有缓存但解析失败」，
 * 于是每张盘都要重新联网，而用户看不出原因。
 */
internal fun writeTextAtomically(target: Path, text: String) {
    val dir = target.parent ?: throw IllegalArgumentException("没有父目录: $target")
    Files.createDirectories(dir)
    val tmp = dir.resolve(target.fileName.toString() + ".tmp")
    Files.writeString(tmp, text, Charsets.UTF_8)
    try {
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
    } catch (e: Throwable) {
        runCatching { Files.deleteIfExists(tmp) }
        Diag.log("atomic write failed: $target", e)
        throw e
    }
}
