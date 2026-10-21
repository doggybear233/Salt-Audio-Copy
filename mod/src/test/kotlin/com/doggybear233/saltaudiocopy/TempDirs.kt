package com.doggybear233.saltaudiocopy

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 测试用临时目录。
 *
 * 以前各用例直接 `Files.createTempDirectory`，跑完留在 %TEMP% 里：
 * 一次全量测试能攒出上千个目录，而且 Windows 上句柄没释放还会拖慢后续构建。
 * 统一走这里登记，JVM 退出时删干净（删除失败只忽略——句柄可能仍在收尾）。
 */
internal object TempDirs {

    private val made = ConcurrentLinkedQueue<Path>()

    init {
        Runtime.getRuntime().addShutdownHook(Thread({
            for (p in made) runCatching { deleteTree(p) }
        }, "saltaudiocopy-temp-cleanup"))
    }

    fun create(prefix: String): Path {
        val dir = Files.createTempDirectory(prefix)
        made.add(dir)
        return dir
    }

    private fun deleteTree(p: Path) {
        if (!Files.exists(p)) return
        Files.walk(p).use { s ->
            s.sorted(Comparator.reverseOrder()).forEach { runCatching { Files.deleteIfExists(it) } }
        }
    }
}
