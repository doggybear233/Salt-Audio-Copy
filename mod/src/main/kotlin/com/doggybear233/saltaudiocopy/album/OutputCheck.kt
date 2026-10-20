package com.doggybear233.saltaudiocopy.album

import com.doggybear233.saltaudiocopy.ui.Strings
import com.doggybear233.saltaudiocopy.rip.WavInfo
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * 输出目录自检：不碰光驱，只验证「上次抓出来的东西是不是自洽的」。
 *
 * 动机：抓轨最怕的不是失败，而是**看起来成功了**——上次崩在 60% 留下半截 WAV，
 * 或者手动改名之后 CUE 指向了不存在的文件。这些事后都能一键查出来。
 */
object OutputCheck {

    data class Problem(val file: String, val detail: String)

    data class Report(
        val dir: Path,
        val wavCount: Int,
        val cueCount: Int,
        val problems: List<Problem>
    ) {
        val ok: Boolean get() = problems.isEmpty()
        fun summary(): String = when {
            wavCount == 0 && cueCount == 0 -> Strings.fmt("目录里没有 WAV/CUE：%s", dir)
            ok -> Strings.fmt("检查通过：%s 个 WAV，%s 个 CUE", wavCount, cueCount)
            else -> Strings.fmt(
                "发现 %s 个问题（共 %s 个 WAV，%s 个 CUE）",
                problems.size, wavCount, cueCount
            )
        }
    }

    fun check(dir: Path): Report {
        if (!Files.isDirectory(dir)) return Report(dir, 0, 0, listOf(Problem("", Strings.fmt("目录不存在：%s", dir))))
        val files = runCatching {
            Files.list(dir).use { s -> s.filter { Files.isRegularFile(it) }.toList() }
        }.getOrDefault(emptyList())

        val problems = ArrayList<Problem>()
        var wavCount = 0
        var cueCount = 0

        for (f in files) {
            val name = f.fileName.toString()
            when {
                name.endsWith(".wav", true) -> {
                    wavCount++
                    val facts = WavInfo.read(f)
                    when {
                        facts == null -> problems += Problem(
                            name,
                            Strings.message("文件太短，连 44 字节的 WAV 头都不完整")
                        )
                        !facts.headerOk -> problems += Problem(
                            name, Strings.message("不是合法的 RIFF/WAVE 头")
                        )
                        facts.dataBytes == 0L -> problems += Problem(
                            name, Strings.message("只有文件头，没有音频数据")
                        )
                        !WavInfo.wholeSectors(facts) ->
                            problems += Problem(name, "数据长度 ${facts.dataBytes} 不是 2352 的整数倍（抓轨被中断）")
                        facts.sampleRate != 44100 || facts.channels != 2 || facts.bitsPerSample != 16 ->
                            problems += Problem(
                                name,
                                "格式异常：${facts.sampleRate}Hz/${facts.channels}ch/${facts.bitsPerSample}bit"
                            )
                    }
                }

                name.endsWith(".cue", true) -> {
                    cueCount++
                    problems += checkCue(f, dir)
                }
            }
        }
        return Report(dir, wavCount, cueCount, problems)
    }

    /** CUE 里每个 `FILE "x.wav" WAVE` 都要能落到磁盘上。 */
    private fun checkCue(cue: Path, dir: Path): List<Problem> {
        val text = runCatching { Files.readString(cue, StandardCharsets.UTF_8) }.getOrNull()
            ?: return listOf(Problem(cue.fileName.toString(), "无法读取（不是 UTF-8？）"))
        val out = ArrayList<Problem>()
        val re = Regex("""^\s*FILE\s+"(.+?)"\s+WAVE""", RegexOption.IGNORE_CASE)
        var fileLines = 0
        for (line in text.lineSequence()) {
            val m = re.find(line) ?: continue
            fileLines++
            val ref = m.groupValues[1]
            val p = if (Path.of(ref).isAbsolute) Path.of(ref) else dir.resolve(ref)
            if (!Files.isRegularFile(p)) {
                out += Problem(cue.fileName.toString(), "引用的文件不存在：$ref")
            }
        }
        if (fileLines == 0) out += Problem(cue.fileName.toString(), "没有任何 FILE 行")
        return out
    }
}
