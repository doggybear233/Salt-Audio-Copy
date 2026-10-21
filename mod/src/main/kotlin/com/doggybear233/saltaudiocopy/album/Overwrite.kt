package com.doggybear233.saltaudiocopy.album

import java.nio.file.Files
import java.nio.file.Path

/**
 * 输出文件已存在时的处置策略。
 *
 * 默认 `suffix`（永不覆盖）：抓轨是长时间操作，覆盖掉上一次的结果是最难挽回的事故。
 * EAC 的做法是弹窗询问，但插件在配置页按钮回调里没有可靠的模态询问通道，
 * 所以把选择权前移到设置里，并默认选最安全的一档。
 */
object Overwrite {

    const val SUFFIX = "suffix"
    const val OVERWRITE = "overwrite"
    const val SKIP = "skip"

    /**
     * @param dir 目标目录
     * @param base 不含扩展名的文件名
     * @param ext 扩展名（含点，如 `.wav`）
     * @param resumeExpectedBytes 若给出（且 >0），表示「这是本轨应有的数据字节数」：
     *   已存在文件的数据段比它短，就判定为**上次中断留下的半截文件**，直接续写同名文件，
     *   而不是按覆盖策略另存为 `(2)`——否则断点续抓永远续不上，只会堆一堆残file。
     * @return 实际可写的路径；策略为 skip 且已存在时返回 null
     */
    fun resolve(
        dir: Path,
        base: String,
        ext: String,
        policy: String,
        resumeExpectedBytes: Long? = null
    ): Path? {
        val exact = dir.resolve(base + ext)
        if (!Files.exists(exact)) return exact
        if (resumeExpectedBytes != null && resumeExpectedBytes > 0) {
            val facts = runCatching {
                com.doggybear233.saltaudiocopy.rip.WavInfo.read(exact)
            }.getOrNull()
            if (facts != null && facts.headerOk && facts.dataBytes in 1 until resumeExpectedBytes) {
                return exact // 半截文件：续写
            }
        }
        return when (policy.lowercase()) {
            OVERWRITE -> exact
            SKIP -> null
            else -> {
                var n = 2
                while (n < 1000) {
                    val p = dir.resolve("$base ($n)$ext")
                    if (!Files.exists(p)) return p
                    n++
                }
                dir.resolve("$base ($n)$ext")
            }
        }
    }

    /**
     * 音频轨的目标路径：**同时**看 `.wav` 与 `.flac`。
     *
     * 只按 `.wav` 判定是不够的：FLAC 模式的实际产物是 `.flac`，上一次抓完留下的
     * `01 x.flac` 不会让 `01 x.wav` 存在，于是这一轨会被判成「没有同名文件」，
     * 然后编码时把上一版 FLAC 原地覆盖掉——用户选的是「加 (2) 另存」。
     *
     * @return 应写入的 WAV 路径（FLAC 模式最终的 `.flac` 由它改名得到）；skip 且已存在时 null
     */
    fun resolveAudio(
        dir: Path,
        base: String,
        policy: String,
        resumeExpectedBytes: Long? = null
    ): Path? {
        val wav = dir.resolve("$base.wav")
        val flac = dir.resolve("$base.flac")
        val wavThere = Files.exists(wav)
        val flacThere = Files.exists(flac)
        if (!wavThere && !flacThere) return wav
        if (resumeExpectedBytes != null && resumeExpectedBytes > 0 && wavThere) {
            val facts = runCatching {
                com.doggybear233.saltaudiocopy.rip.WavInfo.read(wav)
            }.getOrNull()
            if (facts != null && facts.headerOk && facts.dataBytes in 1 until resumeExpectedBytes) {
                // 半截 WAV：续写；但如果同名 flac 已经存在，续写出来的还是会被覆盖
                if (!flacThere) return wav
            }
        }
        return when (policy.lowercase()) {
            OVERWRITE -> wav
            SKIP -> null
            else -> {
                var n = 2
                while (n < 1000) {
                    val w = dir.resolve("$base ($n).wav")
                    val f = dir.resolve("$base ($n).flac")
                    if (!Files.exists(w) && !Files.exists(f)) return w
                    n++
                }
                null
            }
        }
    }

    /**
     * 给「一组必须同名的附属文件」选一个统一后缀。
     *
     * 以前只有音频走 [resolve]，CUE/日志/封面/album.json 全是原地覆盖：
     * 用户选了「加 (2) 另存」却拿到 `01 x (2).wav` + 被覆盖的 `Album.cue`，两组文件对不上。
     *
     * @return `""`（原名可用）或 `" (2)"` 这类后缀；`null` = 按 skip 策略一个都不该写。
     */
    fun pickSuffix(dir: Path, base: String, exts: List<String>, policy: String): String? {
        when (policy.lowercase()) {
            OVERWRITE -> return ""
            SKIP -> return if (exts.any { Files.exists(dir.resolve(base + it)) }) null else ""
        }
        var n = 1
        while (n < 1000) {
            val suffix = if (n == 1) "" else " ($n)"
            if (exts.none { Files.exists(dir.resolve(base + suffix + it)) }) return suffix
            n++
        }
        return null
    }

    /**
     * 一组同名文件（WAV/FLAC + CUE + 日志 + 封面）一起找位置：
     * 任何一个已存在就整组顺延，避免 CUE 里 FILE 指到的文件其实是上一张盘的残留。
     */
    fun resolveGroup(dir: Path, base: String, exts: List<String>, policy: String): List<Path?> {
        val first = dir.resolve("$base${exts.first()}")
        when (policy.lowercase()) {
            OVERWRITE -> return exts.map { dir.resolve("$base$it") }
            SKIP -> return if (Files.exists(first)) exts.map { null } else exts.map { dir.resolve("$base$it") }
        }
        var n = 1
        while (n < 1000) {
            val suffix = if (n == 1) "" else " ($n)"
            val cand = exts.map { dir.resolve("$base$suffix$it") }
            if (cand.none { Files.exists(it) }) return cand
            n++
        }
        return exts.map { null }
    }
}
