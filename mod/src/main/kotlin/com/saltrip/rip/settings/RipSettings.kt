package com.saltrip.rip.settings

import com.saltrip.rip.Diag
import com.saltrip.rip.scsi.Win32
import com.xuncorp.spw.workshop.api.WorkshopApi
import com.xuncorp.spw.workshop.api.config.ConfigHelper
import java.nio.file.Path

/**
 * 用户设置。值全部存进宿主配置页的 `config.json`（`ConfigHelper` 键值对），
 * 未声明 `preference_config.json` 条目时用这里的默认值，行为与 GUI 解耦。
 */
data class RipSettings(
    /** 输出根目录；空字符串表示「音乐」已知文件夹。 */
    val outputRoot: String = "",
    /** 专辑目录模板，支持 `%变量%`，`/` 表示层级。 */
    val folderTemplate: String = "%albumartist%/%albumtitle%",
    /** 音轨文件名模板（不含扩展名）。 */
    val fileTemplate: String = "%tracknr2% %title%",
    /** 封面文件名（不含扩展名，按图片类型补 .jpg/.png）。 */
    val coverFileName: String = "cover",
    /** 多碟时的子目录模板。 */
    val multiDiscFolder: String = "CD%discnumber%",
    /** 抓轨时每块读两遍比对（EAC 的 secure 基础）。 */
    val secureMode: Boolean = true,
    /** 外观：auto / light / dark。 */
    val appearance: String = "auto",
    /** 单块最大重读次数。 */
    val maxRetries: Int = 8,
    /** 读取失败重试之前的等待（毫秒）。 */
    val retryDelayMs: Int = 120,
    /** 是否抓轨后联网获取元数据。 */
    val fetchMetadata: Boolean = true,
    /**
     * 插入新盘 / 换盘时自动查元数据。
     *
     * 默认开：换盘后用户不该再点一次「查元数据」。关掉的话插入盘只读 TOC，
     * 面板上的专辑名要自己点按钮才出现。
     */
    val autoLookupOnInsert: Boolean = true,
    /** 封面长边像素（Cover Art Archive 的 front-<size>）。 */
    /** 0 = 原图（用户默认要求）；>0 = Cover Art Archive 的 front-<size> 缩略图。 */
    val coverSize: Int = 0,
    /** 是否写 CUE 与日志。 */
    val writeCue: Boolean = true,
    val writeLog: Boolean = true,
    /** 输出格式：目前仅 wav。 */
    val format: String = "wav",
    /** 采样级 offset 校正（正数=丢弃开头若干采样，模拟 EAC 的 read offset）。 */
    val offsetSamples: Int = 0,
    /** 是否把每轨 CRC 附在 CUE 的 REM 里（方便对账）。 */
    val annotateCueCrc: Boolean = true,
    /** 整盘镜像：所有音轨拼成单个 WAV + 单 FILE 的 CUE（EAC 的 Copy Image）。 */
    val imageMode: Boolean = false,
    /** 抓轨前先整盘预扫 INDEX 00，避免逐轨重复扫 Q 子通道。 */
    val analyzeBeforeRip: Boolean = false,
    /** FLAC 输出时写 Vorbis 标签（需要 metaflac.exe）。 */
    val writeTags: Boolean = true,
    /** FLAC 输出时把封面内嵌进每个文件。 */
    val embedCover: Boolean = true,
    /** 是否用 C2 位检查读取错误（驱动器不支持时自动降级）。 */
    val useC2: Boolean = false,
    /** 双读校验时插远端读，击破驱动器缓存（驱动器若缓存音频，双读可能比对的是缓存本身）。 */
    val defeatCache: Boolean = false,
    /** Test & Copy：先测试读取一遍拿 CRC，再正式写入并逐轨比对。 */
    val testAndCopy: Boolean = false,

    /** 测试模式：面板只显示常用动作，真机测试动作归到设置页「测试与诊断」。 */
    val testMode: Boolean = false,

    /** 给 WAV 写 RIFF LIST/INFO 标签（资源管理器可见的标题/艺术家/专辑）。 */
    val writeWavTags: Boolean = true,
    /** 抓完后把解析结果写成 `<专辑目录>/album.json`，下次同盘可离线复用/手工修正。 */
    val writeAlbumJson: Boolean = false,
    /** 抓过的盘再抓时，优先用上次存下的 album.json（离线、可手改）。 */
    val reuseAlbumJson: Boolean = true,
    /** 音频读命令：`auto` 或 mmc / mmc-c2 / read10 / d8（见 ReadCommand）。 */
    val readCommand: String = "auto",
    /** 是否用子通道 Q 探测音轨前的 INDEX 00 间隙。 */
    val detectGaps: Boolean = true,
    /** EAC 出错恢复质量：low / medium / high（决定要求几次一致读取）。 */
    val errorRecovery: String = "medium",
    /** 把 INDEX 00 前间隙计入音轨（EAC「含间隙」风格）。 */
    /** 间隙归属：append（附加到上一轨，EAC 默认）/ prepend（归入下一轨，非标准）/ leaveout（丢弃） */
    val gapMode: String = "append",
    /** 间隙探测方法：`q` = 子通道 Q（EAC 默认），`silence` = 波形静音（Test Gaps On Silence） */
    val gapMethod: String = "q",

    /** 断点续抓：目标 WAV 已存在且大小合法时，从已有扇区之后继续。 */
    val resume: Boolean = true,

    /** flac.exe 路径；"flac" 表示按 PATH 查找。 */
    val flacExe: String = "flac",
    /** 通用外部编码器（format = "external" 时用）。 */
    val encoderExe: String = "flac",
    /** 参数模板，支持 %in% %out% %tracknr2% %title% 等命名变量。 */
    val encoderArgs: String = com.saltrip.rip.encode.ExternalEncoder.DEFAULT_ARGS,
    /** 产物扩展名（不含点）。 */
    val encoderExt: String = "flac",
    /** FLAC 压缩等级 0–8（越大越慢、文件越小）。 */
    val flacLevel: Int = 5,
    /** 编码成功后是否保留 WAV。 */
    val keepWav: Boolean = true,

    /** MusicBrainz 候选序号（0 起）：同一 DiscID 常对应多个发行版（日版/港版/再版）。 */
    val releaseIndex: Int = 0,
    /** 优先挑轨数与光盘一致的发行版（多碟合集常见）。 */
    val preferTrackCount: Boolean = true,
    /** 手工元数据覆盖文件；空＝自动用 <专辑目录>/album.json。 */
    val overrideFile: String = "",

    /** 读 CD-TEXT（盘上自带的曲名/艺术家），没有就跳过。 */
    val useCdText: Boolean = true,
    /** 手工指定碟号（多碟套装）；0＝用元数据里的值。 */
    val discNumberOverride: Int = 0,
    /** 手工指定总碟数；0＝用元数据里的值。 */
    val totalDiscsOverride: Int = 0,

    /** 只抓哪些轨，如 `1-5,8`；空＝全部。 */
    val trackSelection: String = "",
    /** 目标文件已存在时：suffix（加 (2)，默认）/ overwrite / skip。 */
    val overwritePolicy: String = "suffix",
    /** 每轨前重读 TOC，防止中途换碟把两张盘的音轨混在一起。 */
    val guardDisc: Boolean = true,

    /** 指定光驱盘符（`F` / `F:` / 空＝自动取第一台）。 */
    val driveLetter: String = "",
    /** 读速上限（KB/s）；0＝不干预（驱动器最高速）。低速通常换来更稳的错误率。 */
    val driveSpeed: Int = 0,
    /** 抓完后弹出托盘。 */
    val ejectAfterRip: Boolean = false
) {
    val effectiveRoot: Path
        get() = if (outputRoot.isBlank()) Win32.musicFolder() else Path.of(outputRoot)
}

/** 设置的读写；宿主 API 不可用时退回默认值（便于单测）。 */
object SettingsStore {

    private const val KEY_OUTPUT_ROOT = "outputRoot"
    private const val KEY_FOLDER_TEMPLATE = "folderTemplate"
    private const val KEY_FILE_TEMPLATE = "fileTemplate"
    private const val KEY_COVER_NAME = "coverFileName"
    private const val KEY_MULTI_DISC = "multiDiscFolder"
    private const val KEY_SECURE = "secureMode"
    private const val KEY_APPEARANCE = "appearance"
    private const val KEY_MAX_RETRIES = "maxRetries"
    private const val KEY_RETRY_DELAY = "retryDelayMs"
    private const val KEY_FETCH_META = "fetchMetadata"
    private const val KEY_AUTO_LOOKUP = "autoLookupOnInsert"
    private const val KEY_COVER_SIZE = "coverSize"
    private const val KEY_WRITE_CUE = "writeCue"
    private const val KEY_WRITE_LOG = "writeLog"
    private const val KEY_FORMAT = "format"
    private const val KEY_OFFSET = "offsetSamples"
    private const val KEY_CUE_CRC = "annotateCueCrc"
    private const val KEY_USE_C2 = "useC2"
        const val KEY_DEFEAT_CACHE = "defeatCache"
    private const val KEY_TEST_COPY = "testAndCopy"
    private const val KEY_TEST_MODE = "testMode"
    private const val KEY_WAV_TAGS = "writeWavTags"
    private const val KEY_DETECT_GAPS = "detectGaps"
    private const val KEY_ERROR_RECOVERY = "errorRecovery"
    private const val KEY_GAP_MODE = "gapMode"
    private const val KEY_GAP_METHOD = "gapMethod"
    private const val KEY_INCLUDE_GAPS = "includeGaps"
    private const val KEY_RESUME = "resume"
    private const val KEY_FLAC_EXE = "flacExe"
    private const val KEY_ENCODER_EXE = "encoderExe"
    private const val KEY_ENCODER_ARGS = "encoderArgs"
    private const val KEY_ENCODER_EXT = "encoderExt"
    private const val KEY_FLAC_LEVEL = "flacLevel"
    private const val KEY_KEEP_WAV = "keepWav"
    private const val KEY_RELEASE_INDEX = "releaseIndex"
    private const val KEY_PREFER_TRACKS = "preferTrackCount"
    private const val KEY_OVERRIDE_FILE = "overrideFile"
    private const val KEY_USE_CDTEXT = "useCdText"
    private const val KEY_DISC_NUMBER = "discNumberOverride"
    private const val KEY_TOTAL_DISCS = "totalDiscsOverride"
    private const val KEY_TRACK_SELECTION = "trackSelection"
    private const val KEY_OVERWRITE = "overwritePolicy"
    private const val KEY_GUARD_DISC = "guardDisc"
    private const val KEY_DRIVE_LETTER = "driveLetter"
    private const val KEY_DRIVE_SPEED = "driveSpeed"
    private const val KEY_EJECT = "ejectAfterRip"

    @Volatile
    private var cached: RipSettings? = null

    @Suppress("UNCHECKED_CAST")
    private fun helper(): ConfigHelper? = runCatching {
        WorkshopApi.manager.createConfigManager().getConfig()
    }.onFailure { Diag.log("config helper unavailable", it) }.getOrNull()

    private fun <T> read(h: ConfigHelper, key: String, def: T): T =
        runCatching { h.get(key, def) }.getOrDefault(def)

    /** 数值键可能被配置页以字符串形式写回（edittext），两种形态都接受。 */
    private fun readInt(h: ConfigHelper, key: String, def: Int): Int {
        runCatching { h.get(key, def) as? Number }.getOrNull()?.let { return it.toInt() }
        runCatching { (h.get(key, def.toString()) as? String)?.trim()?.toIntOrNull() }
            .getOrNull()?.let { return it }
        return def
    }

    /** 布尔键可能是 switch（Boolean）也可能被写成 "true"/"false" 字符串。 */
    private fun readBool(h: ConfigHelper, key: String, def: Boolean): Boolean {
        runCatching { h.get(key, def) as? Boolean }.getOrNull()?.let { return it }
        runCatching { (h.get(key, def.toString()) as? String)?.trim()?.toBooleanStrictOrNull() }
            .getOrNull()?.let { return it }
        return def
    }

    fun load(force: Boolean = false): RipSettings {
        cached?.let { if (!force) return it }
        val h = helper()
        val d = RipSettings()
        val s = if (h == null) d else RipSettings(
            outputRoot = read(h, KEY_OUTPUT_ROOT, d.outputRoot).ifBlank { Win32.musicFolder().toString() },
            folderTemplate = read(h, KEY_FOLDER_TEMPLATE, d.folderTemplate),
            fileTemplate = read(h, KEY_FILE_TEMPLATE, d.fileTemplate),
            coverFileName = read(h, KEY_COVER_NAME, d.coverFileName),
            multiDiscFolder = read(h, KEY_MULTI_DISC, d.multiDiscFolder),
            secureMode = readBool(h, KEY_SECURE, d.secureMode),
            appearance = read(h, KEY_APPEARANCE, d.appearance),
            maxRetries = readInt(h, KEY_MAX_RETRIES, d.maxRetries),
            retryDelayMs = readInt(h, KEY_RETRY_DELAY, d.retryDelayMs),
            fetchMetadata = readBool(h, KEY_FETCH_META, d.fetchMetadata),
            autoLookupOnInsert = readBool(h, KEY_AUTO_LOOKUP, d.autoLookupOnInsert),
            coverSize = readInt(h, KEY_COVER_SIZE, d.coverSize),
            writeCue = readBool(h, KEY_WRITE_CUE, d.writeCue),
            writeLog = readBool(h, KEY_WRITE_LOG, d.writeLog),
            format = read(h, KEY_FORMAT, d.format),
            offsetSamples = readInt(h, KEY_OFFSET, d.offsetSamples),
            annotateCueCrc = readBool(h, KEY_CUE_CRC, d.annotateCueCrc),
            useC2 = readBool(h, KEY_USE_C2, d.useC2),
            defeatCache = readBool(h, KEY_DEFEAT_CACHE, d.defeatCache),
            testAndCopy = readBool(h, KEY_TEST_COPY, d.testAndCopy),
            testMode = readBool(h, KEY_TEST_MODE, d.testMode),
            writeWavTags = readBool(h, KEY_WAV_TAGS, d.writeWavTags),
            detectGaps = readBool(h, KEY_DETECT_GAPS, d.detectGaps),
            errorRecovery = read(h, KEY_ERROR_RECOVERY, d.errorRecovery),
            gapMethod = runCatching { h.get(KEY_GAP_METHOD, d.gapMethod) }.getOrDefault(d.gapMethod),
            gapMode = runCatching { h.get(KEY_GAP_MODE, d.gapMode) }.getOrElse {
                // 0.9 及更早只有 includeGaps 布尔值，true 等价于「归入下一轨」
                if (readBool(h, KEY_INCLUDE_GAPS, false)) "prepend" else d.gapMode
            },
            resume = readBool(h, KEY_RESUME, d.resume),
            flacExe = read(h, KEY_FLAC_EXE, d.flacExe),
            encoderExe = read(h, KEY_ENCODER_EXE, d.encoderExe),
            encoderArgs = read(h, KEY_ENCODER_ARGS, d.encoderArgs),
            encoderExt = read(h, KEY_ENCODER_EXT, d.encoderExt),
            flacLevel = readInt(h, KEY_FLAC_LEVEL, d.flacLevel),
            keepWav = readBool(h, KEY_KEEP_WAV, d.keepWav),
            releaseIndex = readInt(h, KEY_RELEASE_INDEX, d.releaseIndex),
            preferTrackCount = readBool(h, KEY_PREFER_TRACKS, d.preferTrackCount),
            overrideFile = read(h, KEY_OVERRIDE_FILE, d.overrideFile),
            useCdText = readBool(h, KEY_USE_CDTEXT, d.useCdText),
            discNumberOverride = readInt(h, KEY_DISC_NUMBER, d.discNumberOverride),
            totalDiscsOverride = readInt(h, KEY_TOTAL_DISCS, d.totalDiscsOverride),
            trackSelection = read(h, KEY_TRACK_SELECTION, d.trackSelection),
            overwritePolicy = read(h, KEY_OVERWRITE, d.overwritePolicy),
            guardDisc = readBool(h, KEY_GUARD_DISC, d.guardDisc),
            driveLetter = read(h, KEY_DRIVE_LETTER, d.driveLetter),
            driveSpeed = readInt(h, KEY_DRIVE_SPEED, d.driveSpeed),
            ejectAfterRip = readBool(h, KEY_EJECT, d.ejectAfterRip)
        )
        cached = s
        return s
    }

    fun save(s: RipSettings) {
        cached = s
        val h = helper() ?: return
        runCatching {
            h.set(KEY_OUTPUT_ROOT, s.outputRoot)
            h.set(KEY_FOLDER_TEMPLATE, s.folderTemplate)
            h.set(KEY_FILE_TEMPLATE, s.fileTemplate)
            h.set(KEY_COVER_NAME, s.coverFileName)
            h.set(KEY_MULTI_DISC, s.multiDiscFolder)
            h.set(KEY_SECURE, s.secureMode)
            h.set(KEY_APPEARANCE, s.appearance)
            h.set(KEY_MAX_RETRIES, s.maxRetries.toString())
            h.set(KEY_RETRY_DELAY, s.retryDelayMs.toString())
            h.set(KEY_FETCH_META, s.fetchMetadata)
            h.set(KEY_AUTO_LOOKUP, s.autoLookupOnInsert)
            h.set(KEY_COVER_SIZE, s.coverSize.toString())
            h.set(KEY_WRITE_CUE, s.writeCue)
            h.set(KEY_WRITE_LOG, s.writeLog)
            h.set(KEY_FORMAT, s.format)
            h.set(KEY_OFFSET, s.offsetSamples.toString())
            h.set(KEY_CUE_CRC, s.annotateCueCrc)
            h.set(KEY_USE_C2, s.useC2)
        h.set(KEY_DEFEAT_CACHE, s.defeatCache)
            h.set(KEY_TEST_COPY, s.testAndCopy)
            h.set(KEY_TEST_MODE, s.testMode)
            h.set(KEY_WAV_TAGS, s.writeWavTags)
            h.set(KEY_DETECT_GAPS, s.detectGaps)
            h.set(KEY_ERROR_RECOVERY, s.errorRecovery)
            h.set(KEY_GAP_MODE, s.gapMode)
        h.set(KEY_GAP_METHOD, s.gapMethod)
            h.set(KEY_RESUME, s.resume)
            h.set(KEY_FLAC_EXE, s.flacExe)
        h.set(KEY_ENCODER_EXE, s.encoderExe)
        h.set(KEY_ENCODER_ARGS, s.encoderArgs)
        h.set(KEY_ENCODER_EXT, s.encoderExt)
            h.set(KEY_FLAC_LEVEL, s.flacLevel.toString())
            h.set(KEY_KEEP_WAV, s.keepWav)
            h.set(KEY_RELEASE_INDEX, s.releaseIndex.toString())
            h.set(KEY_PREFER_TRACKS, s.preferTrackCount)
            h.set(KEY_OVERRIDE_FILE, s.overrideFile)
            h.set(KEY_USE_CDTEXT, s.useCdText)
            h.set(KEY_DISC_NUMBER, s.discNumberOverride.toString())
            h.set(KEY_TOTAL_DISCS, s.totalDiscsOverride.toString())
            h.set(KEY_TRACK_SELECTION, s.trackSelection)
            h.set(KEY_OVERWRITE, s.overwritePolicy)
            h.set(KEY_GUARD_DISC, s.guardDisc.toString())
            h.set(KEY_DRIVE_LETTER, s.driveLetter)
            h.set(KEY_DRIVE_SPEED, s.driveSpeed.toString())
            h.set(KEY_EJECT, s.ejectAfterRip.toString())
            h.save()
        }.onFailure { Diag.log("settings save failed", it) }
    }
}
