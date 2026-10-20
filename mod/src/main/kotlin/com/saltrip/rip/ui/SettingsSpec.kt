package com.saltrip.rip.ui

import com.saltrip.rip.settings.RipSettings

/**
 * 设置页的结构表：9 个二级页、每页有哪些条目、每条是什么控件。
 *
 * 为什么单独放一份而不是直接写 Compose：
 * 1. 面板（左列导航）和宿主配置页（`preference_config.json`，扁平 9 组）必须同源，
 *    否则两边迟早漂移——[SettingsSpecTest] 会拿这张表和 JSON 对账。
 * 2. 控件类型、可选项、显隐条件都是可断言的纯数据，不需要起 Compose 才能测。
 *
 * 新增设置项的流程：`RipSettings` 加字段 → [SettingsAccess] 的 when 加一行 →
 * 这里加入对应页 → `preference_config.json` 加入对应组 → 跑测试。
 */
enum class SettingKind {
    /** 开关。 */
    SWITCH,

    /** 从固定选项里选一个（对应宿主的 `list`）。 */
    LIST,

    /** 自由文本（数字型也用它，保存时再解析）。 */
    TEXT,

    /** 需要「选择…」按钮的路径（面板里是输入框 + 按钮）。 */
    PATH,

    /** 模板（面板里额外挂变量块）。 */
    TEMPLATE,

    /** 立即执行的按钮（不保存任何值）。 */
    ACTION
}

/** 一个可选项：value 写进配置，label 显示给人看。 */
data class SettingOption(val value: String, val label: String)

/**
 * 一条设置。
 *
 * [visibleWhen] / [enabledWhen] 只影响**面板**：宿主配置页是平铺静态列表，没有条件显隐的能力。
 */
data class SettingItem(
    val kind: SettingKind,
    /** 配置键；[SettingKind.ACTION] 时是 [ConfigActions] 里的方法名。 */
    val key: String,
    val label: String,
    val hint: String = "",
    val options: List<SettingOption> = emptyList(),
    val visibleWhen: (RipSettings) -> Boolean = { true },
    val enabledWhen: (RipSettings) -> Boolean = { true },
    /** 数字输入框的提示（仅 UI）。 */
    val placeholder: String = "",
    /**
     * 路径项在当前设置下的实际值（仅 UI）。
     *
     * 「输出目录」这类项留空时有隐藏默认值（系统音乐文件夹），光看输入框是空字符串，
     * 用户根本不知道文件会落到哪；面板因此在键下面再画一行这个值。
     */
    val current: ((RipSettings) -> String)? = null
)

/** 一个二级页。 */
data class SettingPage(val id: String, val title: String, val items: List<SettingItem>)

object SettingsSpec {

    private val drives: List<SettingOption> =
        listOf(SettingOption("", "自动（第一台）")) +
            ('D'..'Z').map { SettingOption(it.toString(), "$it:") }

    private fun opt(key: String, vararg pairs: Pair<String, String>) =
        SettingItem(SettingKind.LIST, key, "", options = pairs.map { SettingOption(it.first, it.second) })

    private fun sw(key: String) = SettingItem(SettingKind.SWITCH, key, "")

    private fun txt(key: String, placeholder: String = "") =
        SettingItem(SettingKind.TEXT, key, "", placeholder = placeholder)

    private fun path(key: String) = SettingItem(SettingKind.PATH, key, "")
    private fun act(name: String, label: String, hint: String = "") =
        SettingItem(SettingKind.ACTION, name, label, hint)

    /** 8 个页；顺序即面板左列与宿主配置页的顺序。 */
    val pages: List<SettingPage> = listOf(
        SettingPage(
            "p2", "输出位置", listOf(
                path("outputRoot").copy(current = { s -> "当前：${s.effectiveRoot}" }),
                opt(
                    "overwritePolicy",
                    "suffix" to "加 (2) 另存", "overwrite" to "覆盖", "skip" to "跳过"
                ),
                sw("resume"),
                txt("discNumberOverride", "0＝自动"),
                txt("totalDiscsOverride", "0＝自动"),
                txt("multiDiscFolder"),
                sw("ejectAfterRip")
            )
        ),
        SettingPage(
            "p3", "命名模板", listOf(
                SettingItem(SettingKind.TEMPLATE, "folderTemplate", ""),
                SettingItem(SettingKind.TEMPLATE, "fileTemplate", ""),
                SettingItem(SettingKind.TEMPLATE, "coverFileName", "")
            )
        ),
        SettingPage(
            "p4", "音频格式", listOf(
                opt(
                    "format",
                    "wav" to "WAV（推荐）", "flac" to "FLAC（内置编码器）"
                ),
                sw("imageMode").copy(
                    hint = "整盘镜像时不能只抓部分音轨，也不能用 prepend/leaveout 间隙",
                    enabledWhen = { it.gapMode == "append" && it.trackSelection.isBlank() }
                ),
                opt(
                    "flacLevel",
                    "0" to "0 最快", "3" to "3", "5" to "5 默认", "8" to "8 最小"
                ).copy(visibleWhen = { it.format == "flac" }),
                sw("keepWav").copy(visibleWhen = { it.format == "flac" })
            )
        ),
        SettingPage(
            "p5", "标签与附属文件", listOf(
                sw("writeTags").copy(
                    visibleWhen = { it.format == "flac" },
                    hint = "FLAC 没有标签就只剩裸音频"
                ),
                sw("embedCover").copy(visibleWhen = { it.format == "flac" }),
                opt("coverSize", "0" to "原图（推荐）", "500" to "500 px", "1200" to "1200 px"),
                sw("writeWavTags").copy(visibleWhen = { it.format == "wav" }),
                sw("writeCue"),
                sw("annotateCueCrc").copy(visibleWhen = { it.writeCue }),
                sw("writeLog")
            )
        ),
        SettingPage(
            "p6", "元数据", listOf(
                sw("fetchMetadata"),
                sw("autoLookupOnInsert").copy(visibleWhen = { it.fetchMetadata }),
            txt("proxyUrl", "http://127.0.0.1:7890").copy(visibleWhen = { it.fetchMetadata }),
                sw("useCdText"),
                sw("preferTrackCount").copy(visibleWhen = { it.fetchMetadata }),
                txt("releaseIndex", "0＝第一个候选").copy(visibleWhen = { it.fetchMetadata }),
                path("overrideFile").copy(visibleWhen = { it.fetchMetadata }),
                sw("reuseAlbumJson"),
                sw("writeAlbumJson")
            )
        ),
        SettingPage(
            "p7", "光驱与校验", listOf(
                SettingItem(SettingKind.LIST, "driveLetter", "", options = drives),
                opt(
                    "driveSpeed",
                    "1200" to "8x（约 1200 KB/s，推荐）", "2400" to "16x（约 2400 KB/s）",
                    "3600" to "24x（约 3600 KB/s）", "600" to "4x（约 600 KB/s）",
                    "300" to "2x（约 300 KB/s）", "0" to "不干预（全速，最快但抖）"
                ).copy(hint = "默认 8x：全速时驱动器噪音大、重读更频繁，剩余时间也更抖"),
                opt(
                    "readCommand",
                    "auto" to "自动（推荐）", "mmc" to "标准 MMC（0xBE）",
                    "mmc-c2" to "MMC + C2", "read10" to "READ(10)",
                    "d8" to "厂家 0xD8（实验性）"
                ),
                sw("secureMode"),
                sw("testAndCopy"),
                opt("errorRecovery", "low" to "低（快速）", "medium" to "中（推荐）", "high" to "高（苛刻）")
                    .copy(visibleWhen = { it.secureMode }),
                txt("maxRetries").copy(visibleWhen = { it.secureMode }),
                txt("retryDelayMs").copy(visibleWhen = { it.secureMode }),
                txt("offsetSamples", "正数＝丢弃开头若干采样"),
                sw("useC2"),
                sw("defeatCache"),
                sw("guardDisc")
            )
        ),
        SettingPage(
            "p8", "间隙", listOf(
                sw("detectGaps"),
                opt(
                    "gapMethod",
                    "q" to "子通道 Q（EAC 默认）", "silence" to "波形静音（Test Gaps On Silence）"
                ).copy(visibleWhen = { it.detectGaps }),
                opt(
                    "gapMode",
                    "append" to "附加到上一轨（EAC 默认）", "prepend" to "归入下一轨（非标准）",
                    "leaveout" to "丢弃间隙"
                ).copy(visibleWhen = { it.detectGaps }),
                sw("analyzeBeforeRip").copy(visibleWhen = { it.detectGaps })
            )
        ),
        SettingPage(
            "p9", "诊断", listOf(
                txt("trackSelection", "1-5,8；留空＝全部"),
                act("scanDrives", "扫描驱动器"),
                act("previewPlan", "抓轨计划预览（不写盘）"),
                act("analyzeDisc", "间隙预扫（只读）"),
                act("probeReadCommands", "探测可用读命令（只读）"),
                act("testDisc", "只测不写"),
                act("lookupMetadata", "仅查询元数据"),
                act("exportAlbumJson", "导出元数据模板"),
                act("ripFirstTrack", "试抓第 1 轨"),
                act("cancelRip", "取消当前抓轨"),
                act("probePath", "试写测试（中文路径）"),
                act("resetDefaults", "恢复默认设置"),
                act("openLogFolder", "打开日志文件夹"),
            act("testNetwork", "网络自检（音乐库 / CDDB）")
            )
        )
    )

    /** 全部设置键（不含按钮），顺序与面板一致。 */
    val settingKeys: List<String> = pages.flatMap { p ->
        p.items.filter { it.kind != SettingKind.ACTION }.map { it.key }
    }

    /** 全部按钮（[ConfigActions] 方法名）。 */
    val actionKeys: List<String> = pages.flatMap { p ->
        p.items.filter { it.kind == SettingKind.ACTION }.map { it.key }
    }

    fun page(id: String): SettingPage = pages.firstOrNull { it.id == id } ?: pages.first()

    fun item(key: String): SettingItem? =
        pages.asSequence().flatMap { it.items.asSequence() }.firstOrNull { it.key == key }

    /** 面板打开设置时默认停在哪一页。 */
    const val DEFAULT_PAGE: String = "p2"
}

/**
 * 设置键 ↔ [RipSettings] 字段的读写。
 *
 * 用 when 手写而不是反射：字段少、类型明确，编译期就能发现漏项；
 * [SettingsAccessTest] 会遍历 [SettingsSpec.settingKeys]，保证每个键都能读能写不抛异常。
 */
object SettingsAccess {

    fun bool(s: RipSettings, key: String): Boolean = when (key) {
        "secureMode" -> s.secureMode
        "testAndCopy" -> s.testAndCopy
        "useC2" -> s.useC2
        "defeatCache" -> s.defeatCache
        "guardDisc" -> s.guardDisc
        "fetchMetadata" -> s.fetchMetadata
        "autoLookupOnInsert" -> s.autoLookupOnInsert
        "useCdText" -> s.useCdText
        "preferTrackCount" -> s.preferTrackCount
        "reuseAlbumJson" -> s.reuseAlbumJson
        "writeAlbumJson" -> s.writeAlbumJson
        "writeTags" -> s.writeTags
        "embedCover" -> s.embedCover
        "writeWavTags" -> s.writeWavTags
        "writeCue" -> s.writeCue
        "annotateCueCrc" -> s.annotateCueCrc
        "writeLog" -> s.writeLog
        "imageMode" -> s.imageMode
        "keepWav" -> s.keepWav
        "resume" -> s.resume
        "ejectAfterRip" -> s.ejectAfterRip
        "detectGaps" -> s.detectGaps
        "analyzeBeforeRip" -> s.analyzeBeforeRip
        "testMode" -> s.testMode
        else -> false
    }

    fun withBool(s: RipSettings, key: String, v: Boolean): RipSettings = when (key) {
        "secureMode" -> s.copy(secureMode = v)
        "testAndCopy" -> s.copy(testAndCopy = v)
        "useC2" -> s.copy(useC2 = v)
        "defeatCache" -> s.copy(defeatCache = v)
        "guardDisc" -> s.copy(guardDisc = v)
        "fetchMetadata" -> s.copy(fetchMetadata = v)
        "autoLookupOnInsert" -> s.copy(autoLookupOnInsert = v)
        "useCdText" -> s.copy(useCdText = v)
        "preferTrackCount" -> s.copy(preferTrackCount = v)
        "reuseAlbumJson" -> s.copy(reuseAlbumJson = v)
        "writeAlbumJson" -> s.copy(writeAlbumJson = v)
        "writeTags" -> s.copy(writeTags = v)
        "embedCover" -> s.copy(embedCover = v)
        "writeWavTags" -> s.copy(writeWavTags = v)
        "writeCue" -> s.copy(writeCue = v)
        "annotateCueCrc" -> s.copy(annotateCueCrc = v)
        "writeLog" -> s.copy(writeLog = v)
        "imageMode" -> s.copy(imageMode = v)
        "keepWav" -> s.copy(keepWav = v)
        "resume" -> s.copy(resume = v)
        "ejectAfterRip" -> s.copy(ejectAfterRip = v)
        "detectGaps" -> s.copy(detectGaps = v)
        "analyzeBeforeRip" -> s.copy(analyzeBeforeRip = v)
        "testMode" -> s.copy(testMode = v)
        else -> s
    }

    fun text(s: RipSettings, key: String): String = when (key) {
        "outputRoot" -> s.outputRoot
        "folderTemplate" -> s.folderTemplate
        "fileTemplate" -> s.fileTemplate
        "coverFileName" -> s.coverFileName
        "multiDiscFolder" -> s.multiDiscFolder
        "overwritePolicy" -> s.overwritePolicy
        "format" -> s.format
        "overrideFile" -> s.overrideFile
        "readCommand" -> s.readCommand
        "errorRecovery" -> s.errorRecovery
        "proxyUrl" -> s.proxyUrl
        "gapMode" -> s.gapMode
        "gapMethod" -> s.gapMethod
        "driveLetter" -> s.driveLetter
        "trackSelection" -> s.trackSelection
        "maxRetries" -> s.maxRetries.toString()
        "retryDelayMs" -> s.retryDelayMs.toString()
        "offsetSamples" -> s.offsetSamples.toString()
        "discNumberOverride" -> s.discNumberOverride.toString()
        "totalDiscsOverride" -> s.totalDiscsOverride.toString()
        "releaseIndex" -> s.releaseIndex.toString()
        "driveSpeed" -> s.driveSpeed.toString()
        "coverSize" -> s.coverSize.toString()
        "flacLevel" -> s.flacLevel.toString()
        else -> ""
    }

    fun withText(s: RipSettings, key: String, v: String): RipSettings = when (key) {
        "outputRoot" -> s.copy(outputRoot = v)
        "folderTemplate" -> s.copy(folderTemplate = v)
        "fileTemplate" -> s.copy(fileTemplate = v)
        "coverFileName" -> s.copy(coverFileName = v)
        "multiDiscFolder" -> s.copy(multiDiscFolder = v)
        "overwritePolicy" -> s.copy(overwritePolicy = v)
        "format" -> s.copy(format = v)
        "overrideFile" -> s.copy(overrideFile = v)
        "readCommand" -> s.copy(readCommand = v)
        "errorRecovery" -> s.copy(errorRecovery = v)
        "proxyUrl" -> s.copy(proxyUrl = v)
        "gapMode" -> s.copy(gapMode = v)
        "gapMethod" -> s.copy(gapMethod = v)
        "driveLetter" -> s.copy(driveLetter = v)
        "trackSelection" -> s.copy(trackSelection = v)
        "maxRetries" -> s.copy(maxRetries = num(v, s.maxRetries))
        "retryDelayMs" -> s.copy(retryDelayMs = num(v, s.retryDelayMs))
        "offsetSamples" -> s.copy(offsetSamples = num(v, s.offsetSamples))
        "discNumberOverride" -> s.copy(discNumberOverride = num(v, s.discNumberOverride))
        "totalDiscsOverride" -> s.copy(totalDiscsOverride = num(v, s.totalDiscsOverride))
        "releaseIndex" -> s.copy(releaseIndex = num(v, s.releaseIndex))
        "driveSpeed" -> s.copy(driveSpeed = num(v, s.driveSpeed))
        "coverSize" -> s.copy(coverSize = num(v, s.coverSize))
        "flacLevel" -> s.copy(flacLevel = num(v, s.flacLevel))
        else -> s
    }

    private fun num(v: String, def: Int): Int = v.trim().toIntOrNull() ?: def

    /** 按控件类型分派读写，界面只认 [SettingItem.kind]。 */
    fun isSwitch(item: SettingItem) = item.kind == SettingKind.SWITCH
}
