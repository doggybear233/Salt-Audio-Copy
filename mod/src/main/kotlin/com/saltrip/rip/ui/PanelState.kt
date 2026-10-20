package com.saltrip.rip.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 面板可见状态。全部走 Compose 快照状态，抓轨线程可直接写（Compose 会在下一帧取到新值）。
 * 只保存短字符串/数字，避免把 SCSI 对象或大缓冲带进 UI 线程。
 */
object PanelState {

    /**
     * 页内区块上一次真正被组合（绘制）的时间戳。
     *
     * 页内挂载「hook 成功」不等于「真的会渲染」：宿主的子组件可能只在某个状态下才被调用。
     * 底部坞据此决定要不要出来兜底（见 [PanelMount]）——页内区块 1.5s 内没动静就由坞接管，
     * 避免「挂上了但看不见」这种最差结果。
     */
    @Volatile
    var inlineBeatAt: Long = 0L


    /** 一行状态文案，例如「F: HL-DT-ST BD-RE BP71N · 10 轨 · 音频 CD」。 */
    var status: String by mutableStateOf("尚未扫描光驱")
    /** 第二行补充说明。 */
    var detail: String by mutableStateOf("点「扫描」识别光盘；识别后可直接抓整盘")
    /** 是否正在执行耗时操作（按钮置灰用）。 */
    var busy: Boolean by mutableStateOf(false)
    /** 当前阶段名，例如「抓取第 3 轨：道」。 */
    var stage: String by mutableStateOf("")
    /** 进度；null 表示不定进度（不画进度条）。 */
    var progress: Progress? by mutableStateOf(null)

    /** 当前盘的摘要；null 表示还没识别出盘。 */
    var disc: DiscInfo? by mutableStateOf(null)

    /** 抓轨是否已暂停（只在当前块结束后真正停住）。 */
    var paused: Boolean by mutableStateOf(false)

    /**
     * 「取消」的二次确认是否已武装。抓轨中途取消会丢弃已抓的数据，
     * 所以第一次点只提示，第二次点才执行并删除本次产生的文件。
     */
    var cancelArmed: Boolean by mutableStateOf(false)

    /** 面板内嵌设置区是否展开。 */
    var settingsOpen: Boolean by mutableStateOf(false)

    /** 测试模式：只有开启时面板才显示「只测不写」等真机测试动作。 */
    var testMode: Boolean by mutableStateOf(false)

    /** 多光驱时面板上可以点选的光驱（字母 + 摘要）。 */
    data class DriveChip(val letter: String, val label: String, val audio: Boolean)

    var drives: List<DriveChip> by mutableStateOf(emptyList())

    /** 当前选中的光驱字母（大写，无冒号）；空串表示还没选。 */
    var selectedDrive: String by mutableStateOf("")

    /** 最近一次抓轨的输出目录；「打开目录」优先打开它。 */
    var lastOutDir: java.nio.file.Path? by mutableStateOf(null)

    /** 设置页草稿：点「设置」时从磁盘读一份，改完点「保存」才落盘。 */
    var draft: com.saltrip.rip.settings.RipSettings by mutableStateOf(com.saltrip.rip.settings.RipSettings())

    /** 设置页当前停在哪个二级页（见 [SettingsSpec.pages] 的 id）。 */
    var settingsPage: String by mutableStateOf(SettingsSpec.DEFAULT_PAGE)

    fun loadDraft(s: com.saltrip.rip.settings.RipSettings) {
        draft = s
        testMode = s.testMode
        editTarget = -1
    }

    fun updateDraft(f: (com.saltrip.rip.settings.RipSettings) -> com.saltrip.rip.settings.RipSettings) {
        draft = f(draft)
        // testMode 在草稿里改也要立刻反映到面板（诊断页显隐、动作闸门都读它）
        testMode = draft.testMode
    }

    /** 三个模板的只读视图（预览与变量块用）。 */
    val editFolder: String get() = draft.folderTemplate
    val editFile: String get() = draft.fileTemplate
    val editCover: String get() = draft.coverFileName

    /**
     * 模板块编辑器当前写入的目标：0=专辑目录模板，1=文件名模板，2=封面名，-1=未选中。
     *
     * 默认必须是 -1：以前默认 1，用户没点任何输入框就点变量块，结果静默写进了文件名模板。
     */
    var editTarget: Int by mutableStateOf(-1)

    /** 把一段标签追加到当前目标字段末尾（块编辑器的「点一下加进去」）。 */
    fun appendToken(token: String) {
        when (editTarget) {
            0 -> updateDraft { it.copy(folderTemplate = it.folderTemplate + token) }
            1 -> updateDraft { it.copy(fileTemplate = it.fileTemplate + token) }
            2 -> updateDraft { it.copy(coverFileName = it.coverFileName + token) }
            else -> Unit // 未选中目标：什么都不写，由 UI 给出提示
        }
    }

    /** 清空当前目标字段。 */
    fun clearTarget() {
        when (editTarget) {
            0 -> updateDraft { it.copy(folderTemplate = "") }
            1 -> updateDraft { it.copy(fileTemplate = "") }
            2 -> updateDraft { it.copy(coverFileName = "") }
            else -> Unit
        }
    }

    /** 元数据候选列表（查到多个发行版时让用户自选）；空表示没有候选。 */
    var candidates: List<Candidate> by mutableStateOf(emptyList())
    /** 当前选中的候选序号（-1 表示没选）。 */
    var selectedCandidate: Int by mutableStateOf(-1)

    /**
     * 面板上的一个候选发行版。[cover] 是缩略图字节（250px），只在面板里画 48dp 小图。
     * 大图在用户点选后才下载（默认原图），避免查元数据时一次性拉十几个原图。
     */
    data class Candidate(
        val index: Int,
        val label: String,
        val source: String,
        val cover: ByteArray? = null,
        /** MusicBrainz release MBID；CD-TEXT / gnudb 候选为 null。 */
        val releaseId: String? = null
    )

    fun showCandidates(list: List<Candidate>, selected: Int = 0) {
        candidates = list
        selectedCandidate = if (list.isEmpty()) -1 else selected.coerceIn(0, list.size - 1)
    }

    fun selectCandidate(index: Int) {
        if (index in candidates.indices) selectedCandidate = index
    }

    /** 面板上「这张盘是什么」的一行摘要。 */
    data class DiscInfo(
        val tracks: Int,
        val artist: String,
        val album: String,
        val lengthText: String,
        val discId: String,
        val audio: Boolean
    )

    fun showDisc(info: DiscInfo?) {
        disc = info
    }

    /** 当前盘是否可以抓（必须是音频 CD）。 */
    val hasAudioDisc: Boolean get() = disc?.audio == true

    /**
     * 会改盘/写盘的动作能不能点；返回 null 表示可以，否则返回一句「为什么不能」。
     *
     * 放在状态层而不是 UI 层：面板与设置页按钮共用同一套判定，避免两处漂移；
     * 也让「点了才报错」变成「点不了且说明原因」。
     */
    fun actionGate(): String? = when {
        busy -> "正在执行，等当前操作结束"
        disc == null -> "先点「扫描」识别光盘"
        disc?.audio != true -> "当前盘不是音频 CD（数据盘不可抓轨）"
        else -> null
    }

    /** 抓轨进度快照。fraction 已按整盘归一化。 */
    data class Progress(
        val fraction: Float,
        val speedX: Double,
        val etaSec: Int,
        val trackNo: Int,
        val trackTotal: Int
    )

    fun set(statusText: String, detailText: String = detail) {
        status = statusText
        detail = detailText
    }

    fun begin(stageText: String) {
        busy = true
        stage = stageText
        progress = null
        paused = false
        cancelArmed = false
    }

    fun onProgress(p: Progress) {
        progress = p
    }

    /** 只清进度，保留状态行（用于 finally 收尾）。 */
    fun clearProgress() {
        busy = false
        stage = ""
        progress = null
        paused = false
        cancelArmed = false
    }

    fun finish(statusText: String, detailText: String) {
        busy = false
        stage = ""
        progress = null
        status = statusText
        detail = detailText
    }
}

/** 面板上的数字格式化（纯函数，便于单测）。 */
object PanelFormat {

    /** `4.8x`；非法值给 `-`。 */
    fun speed(x: Double): String =
        if (x <= 0.0 || x.isNaN() || x.isInfinite()) "-" else String.format(java.util.Locale.ROOT, "%.1fx", x)

    /** `剩余约 1 分 20 秒` / `剩余约 45 秒`；未知给 `剩余时间未知`。 */
    fun eta(sec: Int): String = when {
        sec <= 0 -> "剩余时间未知"
        sec < 60 -> "剩余约 $sec 秒"
        sec < 3600 -> {
            val m = sec / 60
            val s = sec % 60
            if (s == 0) "剩余约 $m 分" else "剩余约 $m 分 $s 秒"
        }
        else -> {
            val h = sec / 3600
            val m = (sec % 3600) / 60
            "剩余约 $h 小时 $m 分"
        }
    }

    /**
     * 盘摘要行，例如 `宇多田ヒカル · Fantôme · 11 轨 · 61:23`。
     *
     * 元数据还没查到时只写轨数与时长，不写「未知专辑」占位——半截信息比假信息好看，
     * 也不会让人以为查询已经失败。
     */
    fun discLine(info: PanelState.DiscInfo?): String? {
        if (info == null) return null
        val parts = ArrayList<String>(4)
        if (info.artist.isNotBlank() && info.artist != "未知艺术家") parts += info.artist
        if (info.album.isNotBlank() && info.album != "未知专辑") parts += info.album
        parts += "${info.tracks} 轨"
        if (info.lengthText.isNotBlank()) parts += info.lengthText
        if (!info.audio) parts += "非音频盘"
        return parts.joinToString(" · ")
    }

    /** 毫秒 → `61:23` 或 `1:02:03`；未知给 `--:--`。 */
    fun duration(ms: Long): String {
        if (ms <= 0) return "--:--"
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    /** `第 3/11 轨 · 62%`。 */
    fun trackLine(trackNo: Int, trackTotal: Int, fraction: Float): String {
        val pct = (fraction.coerceIn(0f, 1f) * 100).toInt()
        return if (trackTotal > 0) "第 $trackNo/$trackTotal 轨 · $pct%" else "$pct%"
    }
}
