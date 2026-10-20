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
    var status: String by mutableStateOf(Strings.message("尚未扫描光驱"))
    /** 第二行补充说明。 */
    var detail: String by mutableStateOf(Strings.message("点「扫描」识别光盘；识别后可直接抓整盘"))
    /** 是否正在执行耗时操作（按钮置灰用）。 */
    var busy: Boolean by mutableStateOf(false)
    /** 当前阶段名，例如「抓取第 3 轨：道」。 */
    var stage: String by mutableStateOf("")
    /** 进度；null 表示不定进度（不画进度条）。 */
    var progress: Progress? by mutableStateOf(null)

    /** 当前盘的摘要；null 表示还没识别出盘。 */
    var disc: DiscInfo? by mutableStateOf(null)

    /**
     * 最近一次收到抓轨进度的心跳时刻（毫秒时间戳）。
     *
     * 用途：驱动器在某一块上死磕（重读坏道）时，进度条几乎不动，
     * 界面必须说「还在读，只是慢」，而不是让用户以为卡死。
     */
    @Volatile
    var lastBeatAt: Long = 0L

    /** 每块数据校验读的次数（2 = 读两次比对）；面板用它解释「为什么速度只有盘速的一半」。 */
    var verifyTimes: Int = 1

    /** 抓轨是否已暂停（只在当前块结束后真正停住）。 */
    var paused: Boolean by mutableStateOf(false)

    /**
     * 「取消」的二次确认是否已武装。抓轨中途取消会丢弃已抓的数据，
     * 所以第一次点只提示，第二次点才执行并删除本次产生的文件。
     */
    var cancelArmed: Boolean by mutableStateOf(false)

    /**
     * 当前盘是否已经整盘抓完（成功后为 true）。
     * 用途：主按钮文案从「抓整盘」变「重新抓取」，且第一次点击只做二次确认。
     */
    var ripDone: Boolean by mutableStateOf(false)

    /** 「重新抓取」的二次确认是否已武装。 */
    var reRipArmed: Boolean by mutableStateOf(false)

    /** 面板内嵌设置区是否展开。 */
    var settingsOpen: Boolean by mutableStateOf(false)

    /** 手动填写元数据的面板是否展开。 */
    var manualOpen: Boolean by mutableStateOf(false)

    /** 手动元数据草稿（点「手动填写」时建，保存前只活在这里）。 */
    var manual: ManualDraft by mutableStateOf(ManualDraft())

    /** 手动元数据里导入的封面（本地图片路径；null = 没导入）。 */
    var manualCover: String? by mutableStateOf(null)

    /**
     * 当前这张盘的封面字节（本地 album.json / 手导封面 / 缓存）。
     *
     * 坞上的缩略图优先用候选发行的封面，候选为空时退回这里——
     * 否则「手填了封面」在面板上看不到任何变化。
     */
    var discCover: ByteArray? by mutableStateOf(null)

    /**
     * 这一张盘是否已经查过一轮元数据。
     *
     * 「刷新元数据」按钮据此决定要不要变成「手动填写元数据…」：没查过就变，
     * 用户会以为网络已经失败（见 [ManualEntry.buttonIsManual]）。
     */
    var lookupTried: Boolean by mutableStateOf(false)

    fun openManual(d: ManualDraft) {
        manual = d
        manualCover = null
        settingsOpen = false
        manualOpen = true
    }

    fun closeManual() {
        manualOpen = false
    }

    /** 坞当前是否在屏幕上（由 [DockPanelBody] 用 DisposableEffect 维护）。 */
    var visible: Boolean by mutableStateOf(false)

    /** 坞上沿那条临时提示；null 表示没有。 */
    internal var notice: Notice? by mutableStateOf(null)

    /** 提示的递增序号，用来让相同文案也能重新计时。 */
    var noticeSeq: Int = 0

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
        if (info == null) discCover = null
    }

    /**
     * 光盘被抽出：立刻清掉盘面信息，但**保留** [lastOutDir] 之类「已经做过什么」的记忆。
     *
     * 与 [showDisc] 的区别是它会给一句人话，并明确告诉用户「放回同一张盘可以接着抓」，
     * 而不是丢一个「先点扫描识别光盘」的空状态。
     */
    fun markEjected(reason: String) {
        showDisc(null)
        candidates = emptyList()
        selectedCandidate = -1
        progress = null
        paused = false
        busy = false
        cancelArmed = false
        stage = ""
        status = Strings.message("光驱里没有光盘")
        detail = "$reason\n放入光盘会自动识别；同一张盘放回来可以接着抓"
    }

    /** 把 [com.saltrip.rip.DiscMemory.Snapshot] 恢复回面板（同一张盘放回来时用）。 */
    internal fun restoreSnapshot(s: com.saltrip.rip.DiscMemory.Snapshot, ripDoneKept: Boolean) {
        disc = s.info
        discCover = s.cover
        candidates = s.candidates
        selectedCandidate = s.selected
        lookupTried = s.lookupTried
        ripDone = ripDoneKept
        lastOutDir = s.outDir ?: lastOutDir
    }

    /**
     * 正在查询/读取元数据。
     *
     * 联网查元数据可能卡十几秒（镜像连不上），这段时间里如果没有可见状态，用户会以为点没反应；
     * 所以面板要显示「元数据正在加载」并禁用会改盘/写盘的按钮，避免一边查一边抓。
     */
    var metaBusy: Boolean by mutableStateOf(false)

    /**
     * 当前盘最近一次解析出的元数据（内存里留一份）。
     *
     * 换输出根、缓存文件读不到时，面板标题与输出预览仍然要有歌手/专辑名；
     * 否则用户会看到刚填好的信息突然变「未知」。
     */
    internal var discMeta: com.saltrip.rip.meta.AlbumMeta? by mutableStateOf(null)

    /** [discMeta] 属于哪张盘；与当前 disc 不一致时不能拿来用。 */
    internal var discMetaId: String by mutableStateOf("")

    internal fun rememberMeta(discId: String, meta: com.saltrip.rip.meta.AlbumMeta?) {
        if (discId.isBlank()) return
        discMetaId = discId
        discMeta = meta
    }

    internal fun knownMeta(discId: String): com.saltrip.rip.meta.AlbumMeta? =
        discMeta?.takeIf { discMetaId == discId }

    /** 清掉内存里的元数据（换盘、测试隔离用）。 */
    internal fun forgetMeta() {
        discMetaId = ""
        discMeta = null
    }

    /** 当前盘是否可以抓（必须是音频 CD）。 */
    val hasAudioDisc: Boolean get() = disc?.audio == true

    /**
     * 已解析的专辑艺术家 / 专辑名。
     *
     * 顺序与坞上标题一致：盘面信息（含刚保存的手填值）→ 本地 album.json 缓存 → 空。
     */
    internal fun albumNames(): Pair<String, String> {
        val d = disc
        if (d != null) {
            val a = d.artist.takeIf { it.isNotBlank() && it != "未知艺术家" }
            val b = d.album.takeIf { it.isNotBlank() && it != "未知专辑" }
            if (a != null && b != null) return a to b
            if (a != null || b != null) return (a ?: "") to (b ?: "")
        }
        val meta = resolvedAlbumMeta(com.saltrip.rip.settings.SettingsStore.load())
        return (meta?.albumArtist ?: "") to (meta?.albumTitle ?: "")
    }

    /** 本地缓存里的完整元数据（决定 %year%/%genre% 这类变量），没有就 null。 */
    internal fun resolvedAlbumMeta(settings: com.saltrip.rip.settings.RipSettings): com.saltrip.rip.meta.AlbumMeta? {
        val id = disc?.discId ?: return null
        knownMeta(id)?.let { return it }
        val fromDisk = runCatching {
            com.saltrip.rip.ConfigActions.readCachedAlbum(settings.effectiveRoot, id)
        }.getOrNull()
        if (fromDisk != null) rememberMeta(id, fromDisk)
        return fromDisk
    }

    /**
     * 会改盘/写盘的动作能不能点；返回 null 表示可以，否则返回一句「为什么不能」。
     *
     * 放在状态层而不是 UI 层：面板与设置页按钮共用同一套判定，避免两处漂移；
     * 也让「点了才报错」变成「点不了且说明原因」。
     */
    fun actionGate(): String? = when {
        metaBusy -> Strings.message("元数据正在加载，等它读完（或换一张盘）")
        busy -> Strings.message("正在执行，等当前操作结束")
        disc == null -> Strings.message("先点「扫描」识别光盘")
        disc?.audio != true -> Strings.message("当前盘不是音频 CD（数据盘不可抓轨）")
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
        lastBeatAt = System.currentTimeMillis()
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

    /** 整盘抓完：主按钮变成「重新抓取」，并且第一次点击只做确认。 */
    fun markRipped(trackCount: Int) {
        ripDone = true
        reRipArmed = false
        finish(
            statusText = Strings.fmt("已抓完 · %s 轨", trackCount),
            detailText = Strings.message("记录、CUE、日志都已写好；再抓一遍请点「重新抓取」（会先确认）")
        )
    }

    /** 换盘 / 重新扫描时清掉「已抓完」，避免上一个盘的状态骗人。 */
    fun clearRipped() {
        ripDone = false
        reRipArmed = false
    }
}

/** 面板上的数字格式化（纯函数，便于单测）。 */
object PanelFormat {

    /** `4.8x`；非法值给 `-`。 */
    fun speed(x: Double): String =
        if (x <= 0.0 || x.isNaN() || x.isInfinite()) "-" else String.format(java.util.Locale.ROOT, "%.1fx", x)

    /**
     * 剩余时间文案。
     *
     * 为什么约整：抓轨速度本身会抖（缓存、重读、轨道长度差异），
     * 把 1:12 显示成 1:07→1:09→1:05 只会让人觉得「数字在乱跳」。
     * 粗档位（<1 分按 5 秒、<10 分按 15 秒、更久按 1 分）配合
     * [com.saltrip.rip.rip.EtaEstimator] 的窗口估算，数字基本只会稳步下降。
     *
     * @param sec `-1` = 还在估算（样本不足）；`0` = 收尾阶段
     */
    fun eta(sec: Int): String {
        if (sec < 0) return Strings.message("正在估算…")
        if (sec == 0) return Strings.message("即将完成")
        val step = when {
            sec < 60 -> 5
            sec < 600 -> 15
            sec < 3600 -> 60
            else -> 300
        }
        val rounded = ((sec + step - 1) / step) * step
        return when {
            rounded < 60 -> Strings.fmt("剩余约 %s 秒", rounded)
            rounded < 3600 -> {
                val m = rounded / 60
                val s = rounded % 60
                if (s == 0) Strings.fmt("剩余约 %s 分", m) else Strings.fmt("剩余约 %s 分 %s 秒", m, s)
            }
            else -> {
                val h = rounded / 3600
                val m = (rounded % 3600) / 60
                if (m == 0) Strings.fmt("剩余约 %s 小时", h) else Strings.fmt("剩余约 %s 小时 %s 分", h, m)
            }
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
        parts += Strings.fmt("%s 轨", info.tracks)
        if (info.lengthText.isNotBlank()) parts += info.lengthText
        if (!info.audio) parts += Strings.message("非音频盘")
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
        return if (trackTotal > 0) Strings.fmt("第 %s/%s 轨 · %s%%", trackNo, trackTotal, pct) else "$pct%"
    }
}
