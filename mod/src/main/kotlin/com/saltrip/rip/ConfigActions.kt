package com.saltrip.rip

import com.saltrip.rip.album.AlbumRipper
import com.saltrip.rip.album.DiscTest
import com.saltrip.rip.album.AlbumJsonWriter
import com.saltrip.rip.meta.DiscId
import com.saltrip.rip.meta.MetadataService
import com.saltrip.rip.meta.NetCheck
import com.saltrip.rip.rip.RipEngine
import com.saltrip.rip.rip.RipProgress
import com.saltrip.rip.scsi.CdDevice
import com.saltrip.rip.scsi.Win32
import com.saltrip.rip.settings.RipSettings
import com.saltrip.rip.settings.SettingsStore
import com.saltrip.rip.ui.ManualDraft
import com.saltrip.rip.ui.Notify
import com.saltrip.rip.ui.PanelFormat
import com.saltrip.rip.ui.PanelState
import com.saltrip.rip.ui.SettingsSpec
import com.saltrip.rip.ui.Strings
import com.xuncorp.spw.workshop.api.WorkshopApi
import com.xuncorp.spw.workshop.api.WorkshopApi.Ui.ToastType
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 配置页 button 的入口（`preference_config.json` 里 on_click 指向这里的静态无参方法）。
 * 全部方法必须 public static 无参。
 */
object ConfigActions {

    private val exec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "saltrip-worker").apply { isDaemon = true }
    }
    private val cancelFlag = AtomicBoolean(false)

    @Volatile
    private var busy = false

    /** 暂停标志：抓轨主循环每块结束调用 [gate]，暂停时在此阻塞。 */
    @Volatile
    private var paused = false

    /** 用户确认取消（区别于「暂停」）：门返回 true 让烧录循环收尾。 */
    @Volatile
    private var userCancel = false

    /**
     * 抓轨门：每块结束被调用一次。
     *
     * 暂停用阻塞实现（不中断读取，只是不再往下走），取消返回 true 让 RipEngine
     * 在当前块结束后收尾。之所以不用中断线程：SPTI 调用中途被打断会留下
     * 半读的缓冲区，且台架上的驱动对未完成请求很敏感。
     */
    private fun gate(): Boolean {
        while (paused && !userCancel) {
            try {
                Thread.sleep(120)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return true
            }
        }
        return userCancel
    }

    /**
     * 提示分流：例行成功消息走坞上沿的状态条（[com.saltrip.rip.ui.Notify]），
     * 警告/失败仍用宿主 toast——宿主 toast 整宽贴窗口底，坞也在底部，
     * 用它说「扫描完成」这类话必然挡住进度条；但坞被折叠时它是唯一可见的通道。
     */
    private fun toast(msg: String, type: ToastType = ToastType.Success) {
        when (type) {
            ToastType.Success -> com.saltrip.rip.ui.Notify.info(msg)
            ToastType.Warning -> com.saltrip.rip.ui.Notify.warn(msg)
            else -> com.saltrip.rip.ui.Notify.fail(msg)
        }
    }

    /** 直接弹宿主 toast（状态条不可用时的兜底，以及失败消息）。 */
    internal fun toastHost(msg: String, hostSuccess: Boolean) {
        // 宿主 API 不在单测 classpath 上，连 ToastType 的解析都可能抛 NoClassDefFoundError，
        // 所以整段（包括取枚举值）都包在 runCatching 里。
        runCatching {
            val type = if (hostSuccess) ToastType.Success else ToastType.Error
            WorkshopApi.ui.toast(msg, type)
        }.onFailure { Diag.log("toast failed", it) }
    }

    /**
     * 每次操作前重读一次盘。
     *
     * 缓存里的 [com.saltrip.rip.rip.ScanResult] 可能对应上一张盘（用户换盘后不点扫描），
     * 用它查元数据/命名就会把新盘的音轨写成旧盘的曲名和路径。这里统一走 [com.saltrip.rip.Discs.sync]：
     * 指纹变了就替换缓存并清掉面板旧信息；没有任何盘就返回 null 并给出人话提示。
     */
    private fun freshScan(): com.saltrip.rip.rip.ScanResult? {
        val settings = runCatching { SettingsStore.load(force = true) }.getOrNull()
        return runCatching { com.saltrip.rip.Discs.sync(settings?.driveLetter.orEmpty()) }
            .onFailure {
                Diag.log("freshScan failed", it)
                // 盘读不到就把面板上的盘摘要清掉：「抓整盘 / 查元数据」会随即变灰，
                // 用户不会再点到一个注定失败的按钮（反馈 4）。
                runCatching { PanelState.showDisc(null) }
                toast(com.saltrip.rip.scsi.ScsiMessages.human(it), ToastType.Warning)
            }
            .getOrNull()
    }

    /** 扫描所有可访问光驱，报告型号、轨数与 DiscID。 */
    @JvmStatic
    fun scanDrives() {
        // 配置页按钮在宿主 UI 线程上被反射调用，任何 SCSI I/O 都必须挪到工作线程，
        // 否则 INQUIRY / READ TOC 期间界面会整段卡死（用户看到的就是"死机"）。
        toast("正在扫描光驱，请稍候…", ToastType.Success)
        exec.execute {
            try {
                val drives = CdDevice.scanDrives()
                if (drives.isEmpty()) {
                    toast("未发现可用光驱（没有光驱或驱动器内无盘）", ToastType.Warning)
                    Diag.log("scanDrives: none")
                    return@execute
                }
                val sb = StringBuilder()
                val chips = ArrayList<PanelState.DriveChip>()
                for ((path, inq) in drives) {
                    Diag.log("drive $path -> $inq")
                    sb.append("$path $inq")
                    val scan = runCatching { RipEngine.scan(path) }
                    scan.onSuccess { r ->
                        val kind = if (r.toc.isAudioCd) "音频 CD" else "非音频（含数据轨）"
                        val ids = if (r.toc.isAudioCd) {
                            runCatching { "，" + DiscId.describe(r.toc) }.getOrDefault("")
                        } else ""
                        Diag.log("toc $path: ${r.toc.tracks.size} 轨, leadOut=${r.toc.leadOutLba}, audio=${r.toc.isAudioCd}$ids")
                        // 让轮询以这张盘为基准，避免它立刻再判一次「换盘」。
                        runCatching { DiscWatcher.seed(r.toc) }
                        sb.append("，${r.toc.tracks.size} 轨 / $kind$ids")
                        val letter = path.trimEnd('\\').lastOrNull()?.uppercase() ?: ""
                        chips += PanelState.DriveChip(
                            letter = letter,
                            label = "$letter: ${r.toc.tracks.size} 轨",
                            audio = r.toc.isAudioCd
                        )
                        ScanResultHolder.put(r)
                        val playable = (r.toc.leadOutLba - (r.toc.tracks.firstOrNull()?.lba ?: 0))
                            .coerceAtLeast(0)
                        val discId = if (r.toc.isAudioCd) {
                            runCatching { DiscId.musicBrainz(r.toc) }.getOrDefault("")
                        } else ""
                        // 之前抓过这张盘就直接显示专辑信息，不必等联网查询。
                        val cached = if (discId.isNotBlank()) {
                            runCatching {
                                com.saltrip.rip.meta.MetaOverride.load(
                                    com.saltrip.rip.album.DiscCache.path(
                                        SettingsStore.load().effectiveRoot, discId
                                    )
                                )
                            }.getOrNull()
                        } else null
                        PanelState.clearRipped()
                        PanelState.showDisc(
                            PanelState.DiscInfo(
                                tracks = r.toc.tracks.size,
                                artist = cached?.albumArtist.orEmpty(),
                                album = cached?.albumTitle.orEmpty(),
                                lengthText = PanelFormat.duration(playable * 1000L / 75L),
                                discId = discId,
                                audio = r.toc.isAudioCd
                            )
                        )
                    }.onFailure { sb.append("，读取失败: ${it.message}") }
                    sb.append("\n")
                }
                PanelState.drives = chips
                if (chips.size > 1) {
                    // 多光驱：默认选设置里指定的那台，没指定就用第一台。
                    val want = SettingsStore.load(true).driveLetter.uppercase()
                    PanelState.selectedDrive =
                        chips.firstOrNull { it.letter == want }?.letter ?: chips.first().letter
                } else {
                    PanelState.selectedDrive = chips.firstOrNull()?.letter ?: ""
                }
                val text = sb.toString().trim()
                PanelState.set(
                    statusText = text.lineSequence().firstOrNull() ?: "已扫描",
                    detailText = "点「抓整盘」按当前设置输出；「查元数据」只联网不写文件"
                )
                toast(text, ToastType.Success)
                // 扫描到音频 CD 就顺手查元数据：用户不必再点一次（反馈 3）。
                val disc = ScanResultHolder.get()
                if (disc != null && disc.toc.isAudioCd) {
                    runCatching {
                        refreshMetadataAuto(
                            com.saltrip.rip.scsi.DiscFingerprint.of(disc.toc),
                            manual = true
                        )
                    }
                }
            } catch (e: Throwable) {
                Diag.log("scanDrives failed", e)
                PanelState.set("扫描失败：${e.message}")
                toast("扫描失败: ${e.message}", ToastType.Error)
            }
        }
    }

    /**
     * 导出当前光盘的可编辑元数据模板：`<输出根>/<DiscID>.album.json`。
     *
     * 流程：扫描 → 点这个 → 用记事本改 → 再抓整盘（抓轨时会自动加载并覆盖网络元数据）。
     */
    @JvmStatic
    fun exportAlbumJson() {
        val scan = freshScan() ?: return
        if (!scan.toc.isAudioCd) {
            toast("当前盘不是音频 CD", ToastType.Warning)
            return
        }
        exec.execute {
            try {
                val settings = SettingsStore.load(force = true)
                val toc = scan.toc
                val notes = ArrayList<String>()
                val album = runCatching { MetadataService.lookup(toc, settings, notes) }
                    .onFailure { Diag.log("exportAlbumJson lookup failed", it) }
                    .getOrNull()
                val discId = album?.discId?.takeIf { it.isNotBlank() }
                    ?: runCatching { DiscId.musicBrainz(toc) }.getOrDefault("")
                val root = settings.effectiveRoot
                java.nio.file.Files.createDirectories(root)
                val name = (if (discId.isNotBlank()) discId else "disc-" + toc.leadOutLba) + ".album.json"
                val target = root.resolve(name)
                java.nio.file.Files.writeString(target, AlbumJsonWriter.build(album, toc), Charsets.UTF_8)
                Diag.log("album.json exported: $target")
                PanelState.set("已导出元数据模板", target.toString())
                toast("已导出：$target\n改完保存，再抓整盘即生效", ToastType.Success)
            } catch (e: Throwable) {
                Diag.log("exportAlbumJson failed", e)
                toast("导出失败: ${e.message}", ToastType.Error)
            }
        }
    }

    /** 按已读扇区数反推当前轨号（用于面板「第 N/M 轨」）。 */
    private fun trackNoFrom(sectorsDone: Int, toc: com.saltrip.rip.scsi.DiscToc): Int {
        if (toc.tracks.isEmpty()) return 1
        var acc = 0
        for ((i, tr) in toc.tracks.withIndex()) {
            val next = if (i + 1 < toc.tracks.size) toc.tracks[i + 1].lba else toc.leadOutLba
            acc += (next - tr.lba)
            if (sectorsDone < acc) return i + 1
        }
        return toc.tracks.size
    }

    /** 只读检测当前盘有没有 CD-TEXT（结果进日志 + toast），用于判定是「盘没有」还是「读错了」。 */
    /** 「检查面板挂载」：告诉用户面板到底挂上没挂上、没挂上的原因与替代路径。 */
    @JvmStatic
    fun checkPanelMount() {
        val text = com.saltrip.rip.ui.PanelMount.status()
        Diag.log("panel mount status: $text")
        PanelState.set(if (com.saltrip.rip.ui.PanelMount.mounted) "面板已挂载" else "面板未挂载", text)
        toast(text.lineSequence().first(), ToastType.Success)
    }

    @JvmStatic
    fun showCdText() {
        val scan = ScanResultHolder.get() ?: run {
            toast("请先点「扫描驱动器」", ToastType.Warning)
            return
        }
        exec.execute {
            try {
                val info = CdDevice(scan.path).use { it.readCdText() }
                if (info == null || !info.hasText) {
                    Diag.log("cd-text: none")
                    PanelState.set("CD-TEXT：无", "这张盘没有 CD-TEXT")
                    toast("这张盘没有 CD-TEXT", ToastType.Warning)
                    return@execute
                }
                val sb = StringBuilder()
                sb.append("专辑: ").append(info.albumTitle ?: "-").append('\n')
                sb.append("艺术家: ").append(info.albumPerformer ?: "-").append('\n')
                sb.append("流派: ").append(info.genre ?: "-").append("  字符集: ").append(info.charset).append('\n')
                info.titles.toSortedMap().forEach { (n, v) -> sb.append("%02d. %s\n".format(n, v)) }
                Diag.log("cd-text: " + sb.toString().replace("\n", " | "))
                PanelState.set("CD-TEXT：${info.titles.size} 轨", sb.toString())
                toast("CD-TEXT 读取成功：${info.titles.size} 轨", ToastType.Success)
            } catch (e: Throwable) {
                Diag.log("cd-text probe failed", e)
                toast("CD-TEXT 读取失败: ${e.message}", ToastType.Error)
            }
        }
    }

    /** 整盘间隙预扫（只读）：TOC + 每轨 INDEX 00，结果进面板与日志。 */
    @JvmStatic
    fun analyzeDisc() {
        val scan = ScanResultHolder.get() ?: run {
            toast("请先点「扫描驱动器」", ToastType.Warning)
            return
        }
        exec.execute {
            try {
                val table = CdDevice(scan.path).use { dev ->
                    val gaps = com.saltrip.rip.album.DiscAnalysis.gapsFor(scan.toc, dev) { Diag.log(it) }
                    com.saltrip.rip.album.DiscAnalysis.describe(gaps)
                }
                Diag.log("analysis: " + table.replace("\n", " | "))
                PanelState.set("间隙预扫完成", table)
                toast("间隙预扫完成", ToastType.Success)
            } catch (e: Throwable) {
                Diag.log("analysis failed", e)
                toast("间隙预扫失败: ${e.message}", ToastType.Error)
            }
        }
    }

    /** 导出诊断包（纯文本）：版本、系统、设置、日志尾部，便于用户直接回传。不触碰光驱。 */
    @JvmStatic
    fun exportDiagnostics() {
        exec.execute {
            try {
                val st = SettingsStore.load(force = true)
                val root = st.effectiveRoot.resolve("Salt Audio Copy")
                java.nio.file.Files.createDirectories(root)
                val ts = java.time.LocalDateTime.now().toString().replace(':', '-').take(19)
                val out = root.resolve("diagnostics-$ts.txt")
                val sb = StringBuilder()
                sb.append("Salt Audio Copy 诊断信息\r\n")
                sb.append("时间: ").append(java.time.LocalDateTime.now()).append("\r\n")
                sb.append("插件: ").append(SaltRipPlugin::class.java.`package`?.implementationVersion ?: "-")
                    .append("  (id=com.saltrip.rip)\r\n")
                sb.append("系统: ").append(System.getProperty("os.name")).append(' ')
                    .append(System.getProperty("os.version")).append(" / ")
                    .append(System.getProperty("os.arch")).append("\r\n")
                sb.append("JVM: ").append(System.getProperty("java.version")).append(" (")
                    .append(System.getProperty("java.vendor")).append(")\r\n")
                sb.append("\r\n[设置]\r\n").append(describeSettings()).append("\r\n")
                sb.append("\r\n[光驱盘符]\r\n")
                for (c in 'D'..'Z') {
                    val t = runCatching { Win32.driveType(c) }.getOrDefault(-1)
                    if (t == 5) sb.append("  $c: 光驱\r\n")
                }
                sb.append("\r\n[日志尾部]\r\n")
                runCatching {
                    val log = java.io.File(Diag.logPath())
                    if (log.exists()) {
                        val lines = log.readLines(Charsets.UTF_8)
                        lines.takeLast(200).forEach { sb.append(it).append("\r\n") }
                    } else sb.append("  （暂无日志文件）\r\n")
                }.onFailure { sb.append("  读取日志失败: ").append(it.message).append("\r\n") }
                java.nio.file.Files.write(out, sb.toString().toByteArray(Charsets.UTF_8))
                Diag.log("diagnostics exported: $out")
                toast("诊断已导出：\n$out", ToastType.Success)
            } catch (e: Throwable) {
                Diag.log("exportDiagnostics failed", e)
                toast("导出诊断失败: ${e.message}", ToastType.Error)
            }
        }
    }

    /** 只读驱动器健康检查；带看门狗，避免设备在驱动层挂起时用户以为界面卡死。 */
    @JvmStatic
    /**
     * 「①.3 探测可用读命令（只读）」：逐条试读 1 个扇区，报告哪些命令这台驱动器真的认。
     *
     * 全程只发读命令、不写盘、不改设置；探测完把偏好复位成 auto，避免把探针的结果带进正式抓轨。
     */
    fun probeReadCommands() {
        toast("正在探测读命令（只读，1 扇区）…", ToastType.Success)
        exec.execute {
            try {
                val scan = ScanResultHolder.get() ?: CdDevice.scanDrives().firstOrNull()?.let {
                    com.saltrip.rip.rip.ScanResult(it.first, it.second, CdDevice(it.first).use { d -> d.readToc() })
                }
                if (scan == null) {
                    toast("没有找到光驱", ToastType.Warning)
                    return@execute
                }
                val sb = StringBuilder()
                var available = 0
                for (cmd in com.saltrip.rip.scsi.ReadCommand.entries) {
                    val r = runCatching {
                        CdDevice(scan.path, preferred = cmd, allowCommandFallback = false).use { dev ->
                            dev.readAudioSectors(0, 1).size
                        }
                    }
                    if (r.isSuccess) available++
                    sb.append(cmd.id).append("  ")
                        .append(if (r.isSuccess) "可用（${r.getOrNull()} 字节）" else "失败：${r.exceptionOrNull()?.message}")
                        .append('\n')
                }
                com.saltrip.rip.scsi.ReadCommandPreference.apply("auto")
                val table = sb.toString().trimEnd()
                Diag.log("read-command probe: " + table.replace("\n", " | "))
                PanelState.set("读命令探测完成（$available/${com.saltrip.rip.scsi.ReadCommand.entries.size} 可用）", table)
                toast(
                    if (available > 0) "读命令探测完成：$available 条可用" else "所有读命令都失败，驱动器可能仍挂起",
                    if (available > 0) ToastType.Success else ToastType.Error
                )
            } catch (e: Throwable) {
                Diag.log("read-command probe failed", e)
                toast("探测失败：${e.message}", ToastType.Error)
            }
        }
    }

    @JvmStatic
    fun healthCheck() {
        toast("正在检查驱动器（只读）…", ToastType.Success)
        val finished = AtomicBoolean(false)
        val startedAt = System.nanoTime()
        val worker = Thread {
            val sb = StringBuilder()
            runCatching {
                val drives = CdDevice.scanDrives()
                if (drives.isEmpty()) {
                    sb.append("未检测到可用光驱（无设备或无介质）")
                } else {
                    for ((path, inq) in drives) {
                        sb.append(path).append(' ').append(inq).append('\n')
                        runCatching {
                            CdDevice(path).use { dev ->
                                val ready = runCatching { dev.testUnitReady() }.getOrDefault(false)
                                sb.append("  TEST UNIT READY: ").append(if (ready) "就绪" else "未就绪")
                                runCatching { dev.readToc() }
                                    .onSuccess { t ->
                                        sb.append("，").append(t.tracks.size).append(" 轨 / ")
                                            .append(if (t.isAudioCd) "音频 CD" else "含数据轨")
                                    }
                                    .onFailure { e -> sb.append("，读 TOC 失败: ").append(e.message) }
                                sb.append('\n')
                            }
                        }.onFailure { e -> sb.append("  打开失败: ").append(e.message).append('\n') }
                    }
                }
            }.onFailure { e ->
                sb.append("检查失败: ").append(e.message)
                Diag.log("healthCheck failed", e)
            }
            val ms = (System.nanoTime() - startedAt) / 1_000_000
            sb.append("用时 ").append(ms).append(" ms")
            if (!finished.compareAndSet(false, true)) return@Thread
            Diag.log("healthCheck: " + sb.toString().replace('\n', '|'))
            toast(sb.toString().trim().take(900), ToastType.Success)
        }
        worker.isDaemon = true
        worker.name = "saltrip-health"
        worker.start()

        val watchdog = Thread {
            runCatching { Thread.sleep(12_000) }
            if (finished.compareAndSet(false, true)) {
                Diag.log("healthCheck watchdog fired: device likely wedged")
                toast(
                    "驱动器 12 秒无响应：设备很可能已在驱动层挂起。\n" +
                        "请到「设备管理器 → DVD/CD-ROM 驱动器 → 禁用再启用」，或重启后再试。",
                    ToastType.Error
                )
            }
        }
        watchdog.isDaemon = true
        watchdog.name = "saltrip-health-watchdog"
        watchdog.start()
    }

    /** 只查询元数据，落地目录与文件不动；用于确认 DiscID 是否命中。 */
    @JvmStatic
    fun previewPlan() {
        val scan = freshScan() ?: return
        toast("正在计算抓轨计划（不写盘）…", ToastType.Success)
        exec.execute {
            try {
                val settings = SettingsStore.load()
                val notes = ArrayList<String>()
                val album = if (settings.fetchMetadata) {
                    runCatching { MetadataService.lookup(scan.toc, settings, notes) }.getOrNull()
                } else null
                val plan = com.saltrip.rip.album.RipPlan.build(scan.toc, settings, album)
                val text = plan.describe()
                Diag.log("rip plan:\n$text")
                PanelState.set("抓轨计划已生成", "共 ${plan.tracks.size} 轨，约 ${plan.totalBytes / 1024 / 1024} MB")
                toast(text.take(900), ToastType.Success)
            } catch (e: Throwable) {
                Diag.log("previewPlan failed", e)
                toast("计划生成失败: ${e.message}", ToastType.Error)
            }
        }
    }

    /**
     * 查元数据并给出**候选列表**（用户反馈：查到的是错版本时无从下手）。
     *
     * 面板上会显示每个候选的缩略图与小标题，用户点一下即可换；选中的候选会在抓轨前
     * 通过 [selectCandidate] 落进 album.json 缓存，再次抓同一张盘直接沿用。
     */
    @JvmStatic
    fun lookupMetadata() {
        lookupCandidates(interactive = true)
    }

    /**
     * 换盘后自动查询：候选列表照常更新，但不再弹大段 toast（否则每次换盘都刷屏）。
     *
     * [manual] = true 表示这是用户主动点「扫描」触发的，不算「自动」，
     * 所以不受「插入或换盘时自动查元数据」开关限制。
     */
    fun refreshMetadataAuto(expectedFingerprint: String? = null, manual: Boolean = false) =
        lookupCandidates(interactive = false, expectedFingerprint = expectedFingerprint, manual = manual)

    /**
     * 自动查询拿到的结果是否还配得上当前这张盘。
     *
     * 查询要走网络，慢的时候用户已经换盘了；这时候把 A 盘的专辑名写到 B 盘的面板上，
     * 比不显示更糟。`requested == null` 表示调用方没给基准（手动点按钮），一律接受。
     */
    internal fun autoResultIsFresh(requested: String?, actual: String?): Boolean =
        requested == null || requested == actual

    /** 同一时刻只允许一个查询在跑，避免连换两张盘时两次结果互相覆盖。 */
    private val lookupBusy = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun lookupCandidates(
        interactive: Boolean,
        expectedFingerprint: String? = null,
        manual: Boolean = false
    ) {
        val settings = SettingsStore.load()
        if (!settings.fetchMetadata) {
            if (interactive) toast("「查询元数据」已在设置里关闭", ToastType.Warning)
            return
        }
        if (!interactive && !manual && !settings.autoLookupOnInsert) {
            Diag.log("auto lookup skipped: 设置里关掉了「插入或换盘时自动查元数据」")
            return
        }
        if (!lookupBusy.compareAndSet(false, true)) {
            Diag.log("lookup skipped: 上一次查询还没结束")
            return
        }
        if (!interactive) {
            PanelState.set(
                "正在查询元数据…",
                "读 CD-TEXT，再连 MusicBrainz / gnudb；换盘会自动重来"
            )
        }
        exec.execute {
            try {
                val scan = freshScan() ?: return@execute
                val fp = com.saltrip.rip.scsi.DiscFingerprint.of(scan.toc)
                if (!autoResultIsFresh(expectedFingerprint, fp)) {
                    Diag.log("stale lookup dropped: 想要 $expectedFingerprint，现在是 $fp")
                    return@execute
                }
                val cdtext = runCatching {
                    com.saltrip.rip.scsi.CdDevice(scan.path).use { it.readCdText() }
                }.getOrNull()
                val result = com.saltrip.rip.meta.MetadataCandidates.load(scan.toc, settings, cdtext)
                Diag.log("candidates:\n" + result.notes.joinToString("\n"))

                val playable = (scan.toc.leadOutLba - (scan.toc.tracks.firstOrNull()?.lba ?: 0)).coerceAtLeast(0)
                PanelState.showDisc(
                    PanelState.DiscInfo(
                        tracks = scan.toc.tracks.size,
                        artist = result.best?.albumArtist.orEmpty(),
                        album = result.best?.albumTitle.orEmpty(),
                        lengthText = PanelFormat.duration(playable * 1000L / 75L),
                        discId = result.discId,
                        audio = scan.toc.isAudioCd
                    )
                )
                PanelState.showCandidates(
                    result.candidates.map {
                        PanelState.Candidate(it.index, it.label(), it.source, it.cover, it.releaseId)
                    }
                )

                // 查过一轮了：没有候选时面板按钮改成「手动填写元数据…」。
                PanelState.lookupTried = true
                if (result.candidates.isEmpty()) {
                    // 分清「这张盘没记录」和「网络根本不通」：后者让用户去填代理，否则会白试。
                    val netNote = result.notes.firstOrNull {
                        it.contains("连不上") || it.contains("超时") || it.contains("失败")
                    }
                    PanelState.set(
                        "没查到专辑信息",
                        if (netNote != null) {
                            "网络不通：$netNote —— 可在设置里填 HTTP 代理，或直接手动填写"
                        } else {
                            "点「手动填写元数据…」自己写歌手/专辑名/歌名，或换一张盘再查"
                        }
                    )
                    if (interactive) toast("未找到元数据\n" + result.notes.takeLast(2).joinToString("\n"), ToastType.Warning)
                } else {
                    val best = result.best!!
                    PanelState.set(
                        "已找到 ${result.candidates.size} 个候选",
                        "点选封面可换版本；抓轨会用选中版本的信息命名"
                    )
                    if (interactive) {
                        toast(
                            "找到 ${result.candidates.size} 个候选，已在面板列出\n" +
                                "当前：${best.label()}",
                            ToastType.Success
                        )
                    }
                }
            } catch (e: Throwable) {
                Diag.log("lookupMetadata failed", e)
                if (interactive) toast("查询失败：" + com.saltrip.rip.scsi.ScsiMessages.human(e), ToastType.Error)
            } finally {
                lookupBusy.set(false)
            }
        }
    }

    /**
     * 用户在面板上点选候选（[index] 为面板顺序）。下载完整元数据 + 原图封面，
     * 并写成 album.json 缓存，后续抓轨、以及再次插入同一张盘都用它。
     */
    @JvmStatic
    fun selectCandidate(index: Int) {
        PanelState.selectCandidate(index)
        val picked = PanelState.candidates.getOrNull(index) ?: return
        toast("正在获取「${picked.label}」…", ToastType.Success)
        exec.execute {
            try {
                val scan = freshScan() ?: return@execute
                val settings = SettingsStore.load()
                val cdtext = runCatching {
                    com.saltrip.rip.scsi.CdDevice(scan.path).use { it.readCdText() }
                }.getOrNull()
                val cand = com.saltrip.rip.meta.MetadataCandidates.Candidate(
                    index = picked.index,
                    albumArtist = picked.label.substringBefore(" · "),
                    albumTitle = picked.label.substringAfter(" · ").substringBefore("（"),
                    year = null,
                    trackCount = 0,
                    releaseId = picked.releaseId,
                    source = picked.source
                )
                val notes = ArrayList<String>()
                val album = com.saltrip.rip.meta.MetadataCandidates.materialize(scan.toc, cand, settings, cdtext, notes)
                if (album == null) {
                    toast("没能取到该版本的完整信息，请换一个候选", ToastType.Warning)
                    return@execute
                }
                // 选中即落盘成 album.json：下次抓同一张盘直接沿用，不必再选一次。
                val json = com.saltrip.rip.album.AlbumJsonWriter.build(album, scan.toc)
                val id = com.saltrip.rip.meta.DiscId.musicBrainz(scan.toc)
                val target = com.saltrip.rip.album.DiscCache.path(settings.effectiveRoot, id)
                java.nio.file.Files.createDirectories(target.parent)
                java.nio.file.Files.writeString(target, json, Charsets.UTF_8)
                PanelState.showDisc(
                    PanelState.DiscInfo(
                        tracks = album.tracks.size,
                        artist = album.albumArtist.orEmpty(),
                        album = album.albumTitle.orEmpty(),
                        lengthText = PanelFormat.duration(
                            ((scan.toc.leadOutLba - (scan.toc.tracks.firstOrNull()?.lba ?: 0)).coerceAtLeast(0)) * 1000L / 75L
                        ),
                        discId = album.discId,
                        audio = scan.toc.isAudioCd
                    )
                )
                PanelState.set("已选用：${album.albumArtist} - ${album.albumTitle}", notes.takeLast(1).joinToString())
                toast("已选用「${album.albumArtist} - ${album.albumTitle}」", ToastType.Success)
            } catch (e: Throwable) {
                Diag.log("selectCandidate failed", e)
                toast("获取失败：" + com.saltrip.rip.scsi.ScsiMessages.human(e), ToastType.Error)
            }
        }
    }

    /* ------------------------------------------------------------------ 手动元数据 */

    /**
     * 打开「手动填写元数据」：网络查不到时的兜底。
     *
     * 预填顺序：已缓存的 `album.json` → 面板上已知的艺术家/专辑 → `Track NN` 占位。
     * 读盘在后台线程做（`CreateFileW` 可能阻塞十几秒）。
     */
    @JvmStatic
    fun openManualMeta() {
        PanelState.manualOpen = true
        PanelState.settingsOpen = false
        PanelState.set("正在准备手动填写…", "读取曲目数与本机缓存")
        exec.execute {
            try {
                val scan = freshScan() ?: return@execute
                if (!scan.toc.isAudioCd) {
                    PanelState.manualOpen = false
                    toast("当前盘不是音频 CD（数据盘不能抓轨）", ToastType.Warning)
                    return@execute
                }
                val settings = SettingsStore.load()
                val discId = runCatching { DiscId.musicBrainz(scan.toc) }.getOrDefault("")
                val cached = readCachedAlbum(settings.effectiveRoot, discId)
                var draft = ManualDraft.forDisc(
                    trackCount = scan.toc.tracks.size,
                    artist = PanelState.disc?.artist,
                    album = PanelState.disc?.album,
                    cached = cached
                )
                // 盘上有 CD-TEXT 时先塞进去：用户经常只需要补一两个字段。
                var prefilled = false
                if (draft.tracks.all { it.title.isBlank() }) {
                    val cdtext = runCatching {
                        com.saltrip.rip.scsi.CdDevice(scan.path).use { it.readCdText() }
                    }.getOrNull()
                    if (cdtext != null && cdtext.hasText) {
                        val ctTitle = cdtext.albumTitle.orEmpty()
                        val ctArtist = cdtext.albumPerformer.orEmpty()
                        if (draft.albumTitle.isBlank() && ctTitle.isNotBlank()) {
                            draft = draft.copy(albumTitle = ctTitle); prefilled = true
                        }
                        if (ManualDraft.displayable(draft.albumArtist) == null && ctArtist.isNotBlank()) {
                            draft = draft.copy(albumArtist = ctArtist); prefilled = true
                        }
                        for (i in draft.tracks.indices) {
                            val n = draft.tracks[i].number
                            val title = cdtext.titles[n]
                            val performer = cdtext.performers[n]
                            if (!title.isNullOrBlank() || !performer.isNullOrBlank()) {
                                draft = draft.withTrack(n, title, performer)
                                prefilled = true
                            }
                        }
                    }
                }
                PanelState.openManual(draft)
                PanelState.set(
                    "手动填写元数据",
                    if (prefilled) "已用盘上的 CD-TEXT 预填，改完保存即可"
                    else "写歌手/专辑名/歌名，保存后抓轨按这些名字命名并写标签"
                )
            } catch (e: Throwable) {
                Diag.log("openManualMeta failed", e)
                PanelState.manualOpen = false
                toast("打开手动填写失败：" + com.saltrip.rip.scsi.ScsiMessages.human(e), ToastType.Error)
            }
        }
    }

    /**
     * 保存手填的元数据。
     *
     * 写两份同样的 JSON：
     * - `<输出根>/.saltaudiocopy/album-<DiscID>.json`：抓轨时 `AlbumRipper` 直接沿用；
     * - `<输出根>/<DiscID>.album.json`：`MetaOverride` 认的位置，「刷新元数据」/离线查询也能读到。
     */
    @JvmStatic
    fun saveManualMeta() {
        val draft = PanelState.manual
        if (!draft.hasAnything) {
            Notify.warn("至少写一个专辑名或歌名再保存")
            return
        }
        exec.execute {
            try {
                val scan = freshScan() ?: return@execute
                val settings = SettingsStore.load()
                val toc = scan.toc
                val discId = runCatching { DiscId.musicBrainz(toc) }.getOrDefault("")
                val album = draft.toAlbumMeta(discId)
                val json = AlbumJsonWriter.build(album, toc)
                val root = settings.effectiveRoot
                val cachePath = manualCachePath(root, discId, toc.leadOutLba)
                java.nio.file.Files.createDirectories(cachePath.parent)
                java.nio.file.Files.writeString(cachePath, json, Charsets.UTF_8)
                if (discId.isNotBlank()) {
                    java.nio.file.Files.writeString(root.resolve("$discId.album.json"), json, Charsets.UTF_8)
                }
                Diag.log("manual album.json saved: $cachePath")
                saveManualCover(cachePath, root, discId)

                PanelState.showDisc(
                    PanelState.DiscInfo(
                        tracks = album.tracks.size,
                        artist = album.albumArtist,
                        album = album.albumTitle,
                        lengthText = PanelFormat.duration(
                            (toc.leadOutLba - (toc.tracks.firstOrNull()?.lba ?: 0)).coerceAtLeast(0) * 1000L / 75L
                        ),
                        discId = discId,
                        audio = toc.isAudioCd
                    )
                )
                PanelState.showCandidates(emptyList())
                PanelState.lookupTried = true
                PanelState.closeManual()
                PanelState.set("已保存手动元数据", "${album.albumArtist} — ${album.albumTitle}（${album.tracks.size} 轨）")
                Notify.info("已保存手动元数据，抓轨会用这些名字")
            } catch (e: Throwable) {
                Diag.log("saveManualMeta failed", e)
                toast("保存失败：" + com.saltrip.rip.scsi.ScsiMessages.human(e), ToastType.Error)
            }
        }
    }

    /** 手填元数据时导入本地封面（没有网络元数据的盘常常也没有封面）。 */
    @JvmStatic
    fun chooseManualCover() {
        exec.execute {
            val current = PanelState.manualCover.orEmpty()
            val picked = pickFile(current, "选择专辑封面", "图片 (jpg / png / webp)", "jpg;jpeg;png;webp")
            if (picked == null) {
                toast("已取消选择", ToastType.Warning)
                return@execute
            }
            PanelState.manualCover = picked
            Notify.info("封面已选：" + picked.substringAfterLast('\\').substringAfterLast('/'))
        }
    }

    /** 把盘上的 CD-TEXT 抄进手填草稿（有的盘只有 CD-TEXT，网络没收录）。 */
    @JvmStatic
    fun fillManualFromCdText() {
        exec.execute {
            try {
                val scan = freshScan() ?: return@execute
                val info = runCatching {
                    com.saltrip.rip.scsi.CdDevice(scan.path).use { it.readCdText() }
                }.getOrNull()
                if (info == null || !info.hasText) {
                    Notify.warn(Strings.key("manual_no_cdtext"))
                    return@execute
                }
                var d = PanelState.manual
                val albumArtist = ManualDraft.displayable(info.albumPerformer)
                val albumTitle = ManualDraft.displayable(info.albumTitle)
                d = d.copy(
                    albumArtist = albumArtist.ifBlank { d.albumArtist },
                    albumTitle = albumTitle.ifBlank { d.albumTitle }
                )
                for (t in d.tracks) {
                    d = d.withTrack(
                        t.number,
                        title = ManualDraft.displayable(info.titles[t.number]).ifBlank { null },
                        artist = ManualDraft.displayable(info.performers[t.number]).ifBlank { null }
                    )
                }
                PanelState.manual = d
                Notify.info("已用 CD-TEXT 填入可用的字段")
            } catch (e: Throwable) {
                Diag.log("fillManualFromCdText failed", e)
                toast("读 CD-TEXT 失败：" + com.saltrip.rip.scsi.ScsiMessages.human(e), ToastType.Error)
            }
        }
    }

    /** 抓轨实际读取的缓存路径；DiscID 缺失时按 lead-out 兜底，避免各写各的。 */
    /**
     * 把导入的封面复制到 album.json 旁边（`<discId>.album.cover.jpg`），
     * 下次读缓存时 [com.saltrip.rip.meta.MetaOverride.load] 会自动带上。
     */
    private fun saveManualCover(cachePath: java.nio.file.Path, root: java.nio.file.Path, discId: String) {
        val picked = PanelState.manualCover ?: return
        runCatching {
            val src = java.nio.file.Path.of(picked)
            if (!java.nio.file.Files.isRegularFile(src)) return@runCatching
            val ext = picked.substringAfterLast('.', "jpg").lowercase()
                .let { if (it == "jpeg") "jpg" else it }
            val bytes = java.nio.file.Files.readAllBytes(src)
            val stem = cachePath.fileName.toString().removeSuffix(".json")
            val targets = buildList {
                add(cachePath.parent.resolve("$stem.cover.$ext"))
                if (discId.isNotBlank()) add(root.resolve("$discId.album.cover.$ext"))
            }
            for (dst in targets) {
                runCatching {
                    java.nio.file.Files.createDirectories(dst.parent)
                    java.nio.file.Files.write(dst, bytes)
                }.onFailure { Diag.log("cover copy failed: $dst", it) }
            }
            Diag.log("manual cover saved: ${bytes.size} B -> $stem.cover.$ext")
        }.onFailure { Diag.log("saveManualCover failed", it) }
    }

    internal fun manualCachePath(root: java.nio.file.Path, discId: String, leadOutLba: Int): java.nio.file.Path =
        com.saltrip.rip.album.DiscCache.path(root, discId.ifBlank { "disc-$leadOutLba" })

    /** 读已有的 album.json 缓存（读不到就当没有，不报错）。 */
    internal fun readCachedAlbum(root: java.nio.file.Path, discId: String): com.saltrip.rip.meta.AlbumMeta? {
        if (discId.isBlank()) return null
        val p = com.saltrip.rip.album.DiscCache.path(root, discId)
        if (!java.nio.file.Files.isRegularFile(p)) return null
        return com.saltrip.rip.meta.MetaOverride.load(p)
            .also { if (it == null) Diag.log("readCachedAlbum: 解析失败或为空 $p") }
    }

    /** 抓取整张专辑：元数据 → 模板命名 → 逐轨双读校验 → 封面/CUE/日志。 */
    @JvmStatic
    fun ripAlbum() {
        if (busy) {
            toast("已有抓轨任务在进行", ToastType.Warning)
            return
        }
        // 这张盘已经抓完一遍：第一次点击只确认，第二次才真的覆盖
        if (PanelState.ripDone && !PanelState.reRipArmed) {
            PanelState.reRipArmed = true
            PanelState.set(
                "已抓完 · 再点一次「重新抓取」确认",
                "重新抓取会按当前设置覆盖同名文件（覆盖策略见设置 → 输出位置）"
            )
            toast("再点一次「重新抓取」才会开始；「取消」或换盘会解除", ToastType.Warning)
            return
        }
        val scan = freshScan() ?: return
        if (!scan.toc.isAudioCd) {
            toast("这张盘不是音频 CD（数据盘不能抓轨）：请换一张音乐 CD", ToastType.Warning)
            return
        }
        val settings = SettingsStore.load(force = true)
        val count = scan.toc.tracks.size
        busy = true
        cancelFlag.set(false)
        paused = false
        userCancel = false
        PanelState.reRipArmed = false
        PanelState.begin("准备中…")
        PanelState.set("正在抓取 $count 轨…", "可用「取消」在当前块结束后停止，已抓部分保留")
        toast("开始抓取整张专辑（$count 轨，${if (settings.secureMode) "双读校验" else "快速模式"}）…", ToastType.Success)
        exec.execute {
            try {
                var lastPct = -10
                var removedFiles = 0
                val result = AlbumRipper.rip(
                    scan, settings, { gate() },
                    onProgress = { p: RipProgress ->
                        val pct = (p.fraction * 100).toInt()
                        val secs = (p.elapsedMs / 1000).toInt()
                        PanelState.onProgress(
                            PanelState.Progress(
                                fraction = p.fraction,
                                speedX = p.speedX,
                                etaSec = if (p.etaMs < 0) -1 else (p.etaMs / 1000).toInt(),
                                trackNo = trackNoFrom(p.sectorsDone, scan.toc),
                                trackTotal = count
                            )
                        )
                        if (pct >= lastPct + 5) {
                            lastPct = pct / 5 * 5
                            Diag.log(
                                "progress $pct% ${String.format("%.2f", p.speedX)}x " +
                                    "已用 ${p.elapsedMs / 1000}s 剩余约 ${p.etaMs / 1000}s 重试 ${p.errors}"
                            )
                        }
                    },
                    onStage = {
                        PanelState.stage = it
                        Diag.log("stage: $it")
                    }
                )
                if (result.cancelled) {
                    removedFiles = deleteCreated(result.createdFiles)
                    if (removedFiles > 0) {
                        Diag.log("cancelled: removed $removedFiles files from ${result.outDir}")
                    }
                }
                PanelState.lastOutDir = result.outDir
                val detail = result.tracks.joinToString("\n") { r ->
                    "  %02d  %6d 扇区  CRC32 %08X%s".format(
                        r.number, r.sectors, r.crc32,
                        if (r.errors > 0) "  (重试 ${r.errors})" else ""
                    )
                }
                Diag.log("album result:\n$detail\nnotes:\n" + result.notes.joinToString("\n"))
                if (result.cancelled) {
                    val tail = if (removedFiles > 0) {
                        "已删除本次生成的 $removedFiles 个文件"
                    } else {
                        "文件保留在 ${result.outDir}"
                    }
                    PanelState.set("已取消", tail)
                    toast("已取消：完成 ${result.tracks.size}/$count 轨，$tail", ToastType.Warning)
                } else {
                    val bits = buildList {
                        add("抓轨完成：${result.tracks.size}/$count 轨，用时 ${result.elapsedMs / 1000} 秒")
                        result.album?.let { add("${it.albumArtist} - ${it.albumTitle}") }
                        if (result.coverFile != null) add("封面已保存")
                        result.cueFile?.let { add("CUE：${it.fileName}") }
                        result.logFile?.let { add("日志：${it.fileName}") }
                        val retries = result.tracks.sumOf { it.errors }
                        if (retries > 0) add("重试 $retries 次")
                    }
                    PanelState.markRipped(result.tracks.size)
                    PanelState.status = "已抓完 · ${result.tracks.size} 轨 · 用时 ${result.elapsedMs / 1000} 秒"
                    PanelState.lastOutDir = result.outDir
                    PanelState.detail = buildString {
                        append(result.outDir)
                        result.cueFile?.let { append("\nCUE：").append(it) }
                        append("\n再抓一遍请点「重新抓取」（会先确认）")
                    }
                    toast(bits.joinToString("\n") + "\n${result.outDir}", ToastType.Success)
                }
            } catch (e: com.saltrip.rip.scsi.ScsiException) {
                Diag.log("ripAlbum scsi failure", e)
                PanelState.set("抓轨失败", com.saltrip.rip.scsi.ScsiMessages.human(e))
                toast("抓轨失败：" + com.saltrip.rip.scsi.ScsiMessages.human(e), ToastType.Error)
            } catch (e: Throwable) {
                Diag.log("ripAlbum failed", e)
                toast("抓轨失败: ${e.message}", ToastType.Error)
            } finally {
                busy = false
                PanelState.clearProgress()
            }
        }
    }

    /** 只抓第 1 轨到临时目录，用于快速验证驱动器状态。 */
    @JvmStatic
    fun testDisc() {
        val scan = freshScan() ?: return
        if (busy) {
            toast("已有任务在进行", ToastType.Warning)
            return
        }
        if (!scan.toc.isAudioCd) {
            toast("这不是音频 CD", ToastType.Warning)
            return
        }
        busy = true
        cancelFlag.set(false)
        PanelState.begin("只测不写（Test only）")
        toast("开始测试读取，不会写任何文件…", ToastType.Success)
        exec.execute {
            try {
                val settings = SettingsStore.load(true)
                val rows = DiscTest.run(
                    scan, scan.toc, { gate() },
                    { stage -> PanelState.stage = stage; Diag.log("test-only: $stage") },
                    verify = settings.secureMode,
                    maxRetries = settings.maxRetries,
                    retryDelayMs = settings.retryDelayMs,
                    useC2 = settings.useC2,
                    defeatCache = settings.defeatCache,
                    gapMethod = settings.gapMethod
                )
                val text = DiscTest.summary(rows)
                Diag.log("test-only done: ${rows.size} tracks")
                rows.forEach { Diag.log("test-only track=${it.track} sectors=${it.sectors} crc32=%08X errors=${it.errors}".format(it.crc32)) }
                PanelState.set("测试完成（未写盘）", text)
                toast("测试完成：${rows.size} 轨，未写任何文件", ToastType.Success)
            } catch (e: Throwable) {
                Diag.log("test-only failed", e)
                val why = com.saltrip.rip.scsi.ScsiMessages.human(e)
                PanelState.set("测试失败", why)
                toast("测试失败：$why", ToastType.Error)
            } finally {
                busy = false
            }
        }
    }

    @JvmStatic
    fun ripFirstTrack() {
        val scan = freshScan() ?: return
        if (busy) {
            toast("已有抓轨任务在进行", ToastType.Warning)
            return
        }
        busy = true
        cancelFlag.set(false)
        toast("开始抓取第 1 轨…", ToastType.Success)
        exec.execute {
            try {
                val settings = SettingsStore.load(true)
                val dir = settings.effectiveRoot.resolve("disc-${scan.toc.leadOutLba}")
                val out = dir.resolve("01.wav")
                var lastReport = 0
                val result = RipEngine.ripTrack(
                    scan, 0, out, { gate() },
                    { p: RipProgress ->
                        val pct = (p.fraction * 100).toInt()
                        if (pct >= lastReport + 10) {
                            lastReport = pct / 10 * 10
                            Diag.log("progress $pct% ${String.format("%.2f", p.speedX)}x")
                        }
                    },
                    verify = settings.secureMode,
                    useC2 = settings.useC2,
                    maxRetries = settings.maxRetries,
                    retryDelayMs = settings.retryDelayMs
                )
                val hex = "%08X".format(result.crc32)
                if (result.cancelled) {
                    toast("已取消（${result.sectors}/${scan.toc.sectorsOf(0).second} 扇区）", ToastType.Warning)
                } else {
                    toast("完成: ${result.sectors} 扇区, CRC32 $hex\n${result.outFile}", ToastType.Success)
                }
            } catch (e: com.saltrip.rip.scsi.ScsiException) {
                Diag.log("ripFirstTrack scsi failure", e)
                toast("读取失败：" + com.saltrip.rip.scsi.ScsiMessages.human(e), ToastType.Error)
            } catch (e: Throwable) {
                Diag.log("ripFirstTrack failed", e)
                toast("抓轨失败: ${e.message}", ToastType.Error)
            } finally {
                busy = false
                PanelState.clearProgress()
            }
        }
    }

    /** 暂停 / 继续。暂停只在当前块结束后生效，进度条会隐藏。 */
    @JvmStatic
    fun pauseRip() {
        if (!busy) {
            toast("当前没有抓轨任务", ToastType.Warning)
            return
        }
        paused = !paused
        PanelState.paused = paused
        if (paused) {
            PanelState.progress = null
            toast("已暂停（当前块结束后停住），再点一次继续", ToastType.Warning)
        } else {
            toast("继续抓轨", ToastType.Success)
        }
    }

    /**
     * 取消（两段确认）。
     *
     * 抓轨中途取消会丢弃已抓的数据，所以第一次点只武装确认、第二次点才真取消，
     * 并且会删除**本次运行新建**的文件（[com.saltrip.rip.album.AlbumRipResult.createdFiles]），
     * 用户之前抓好的文件不受影响。
     */
    @JvmStatic
    fun cancelRip() {
        // 「取消」也解除「重新抓取」的待确认态
        PanelState.reRipArmed = false
        if (!busy) {
            toast("当前没有抓轨任务", ToastType.Warning)
            return
        }
        if (!PanelState.cancelArmed) {
            PanelState.cancelArmed = true
            PanelState.paused = false
            paused = false
            toast("再点一次「取消」将中止并删除本次生成的文件", ToastType.Warning)
            return
        }
        PanelState.cancelArmed = false
        paused = false
        PanelState.paused = false
        userCancel = true
        cancelFlag.set(true)
        toast("正在取消…", ToastType.Warning)
    }

    /** 删除本次新建的文件，返回删掉的个数。失败只记日志。 */
    private fun deleteCreated(files: List<java.nio.file.Path>): Int {
        var n = 0
        for (f in files) {
            val ok = runCatching { java.nio.file.Files.deleteIfExists(f) }
                .onFailure { Diag.log("delete failed: $f", it) }
                .getOrDefault(false)
            if (ok) n++
        }
        return n
    }

    /** 切换测试模式（设置页与面板测试动作的开关）。 */
    @JvmStatic
    fun toggleTestMode() {
        val s = SettingsStore.load(true)
        val next = s.copy(testMode = !s.testMode)
        SettingsStore.save(next)
        PanelState.testMode = next.testMode
        toast("测试模式：${if (next.testMode) "开" else "关"}", ToastType.Success)
    }

    /** 打开设置页：整份设置读进草稿，固定停在「输出位置」。 */
    @JvmStatic
    fun openSettings() {
        val s = SettingsStore.load(true)
        PanelState.loadDraft(s.copy(outputRoot = runCatching { s.effectiveRoot.toString() }.getOrDefault("")))
        PanelState.settingsPage = SettingsSpec.DEFAULT_PAGE
        PanelState.settingsOpen = true
    }

    /** 关闭设置区（不保存草稿）。 */
    @JvmStatic
    fun closeSettings() {
        PanelState.settingsOpen = false
    }

    /** 把设置草稿整份写回磁盘。 */
    @JvmStatic
    fun savePanelSettings() {
        val d = PanelState.draft.copy(
            folderTemplate = PanelState.draft.folderTemplate.ifBlank { "%albumartist%/%albumtitle%" },
            fileTemplate = PanelState.draft.fileTemplate.ifBlank { "%tracknr2% %title%" },
            coverFileName = PanelState.draft.coverFileName.ifBlank { "cover" },
            multiDiscFolder = PanelState.draft.multiDiscFolder.ifBlank { "CD%discnumber%" }
        )
        SettingsStore.save(d)
        PanelState.testMode = d.testMode
        PanelState.settingsOpen = false
        toast("设置已保存", ToastType.Success)
    }

    /** 保存面板里编辑的三个模板（目录 / 文件名 / 封面名）。 */
    @JvmStatic
    fun saveTemplates(folder: String, file: String, cover: String) {
        val s = SettingsStore.load(true)
        val next = s.copy(
            folderTemplate = folder.ifBlank { "%albumartist%/%albumtitle%" },
            fileTemplate = file.ifBlank { "%tracknr2% %title%" },
            coverFileName = cover.ifBlank { "cover" }
        )
        SettingsStore.save(next)
        toast("模板已保存", ToastType.Success)
    }

    /** 保存单个布尔设置（面板开关用）。 */
    @JvmStatic
    fun saveBool(key: String, value: Boolean) {
        val s = SettingsStore.load(true)
        val next = when (key) {
            "secureMode" -> s.copy(secureMode = value)
            "testMode" -> s.copy(testMode = value)
            "writeCue" -> s.copy(writeCue = value)
            "writeLog" -> s.copy(writeLog = value)
            "fetchMetadata" -> s.copy(fetchMetadata = value)
            "detectGaps" -> s.copy(detectGaps = value)
            "embedCover" -> s.copy(embedCover = value)
            "writeTags" -> s.copy(writeTags = value)
            else -> {
                toast("未知设置项: $key", ToastType.Error)
                return
            }
        }
        SettingsStore.save(next)
        if (key == "testMode") PanelState.testMode = value
    }

    /** 切换双读校验（写回宿主配置）。 */
    @JvmStatic
    fun toggleSecureMode() {
        val s = SettingsStore.load(true)
        val next = s.copy(secureMode = !s.secureMode)
        SettingsStore.save(next)
        toast("双读校验：${if (next.secureMode) "开" else "关"}", ToastType.Success)
    }

    /** 切换元数据联网查询。 */
    @JvmStatic
    fun toggleMetadata() {
        val s = SettingsStore.load(true)
        val next = s.copy(fetchMetadata = !s.fetchMetadata)
        SettingsStore.save(next)
        toast("联网元数据：${if (next.fetchMetadata) "开" else "关"}", ToastType.Success)
    }

    /**
     * 多光驱时切换目标光驱：写进设置（下次抓轨也用这台），然后重扫。
     * 只切「默认目标」，不隐式开始抓轨——用户可能只是想看看另一台里是什么盘。
     */
    @JvmStatic
    fun selectDrive(letter: String) {
        val s = SettingsStore.load(true)
        SettingsStore.save(s.copy(driveLetter = letter.uppercase()))
        PanelState.selectedDrive = letter.uppercase()
        toast("已切换到光驱 ${letter.uppercase()}:", ToastType.Success)
        scanDrives()
    }

    /** 打开最近一次抓轨的专辑目录；没有就打开输出根目录。 */
    @JvmStatic
    fun openOutputFolder() {
        val last = PanelState.lastOutDir
        if (last != null && java.nio.file.Files.isDirectory(last)) {
            runCatching { java.awt.Desktop.getDesktop().open(last.toFile()) }
                .onFailure {
                    Diag.log("open album folder failed", it)
                    toast("无法打开目录: $last", ToastType.Warning)
                }
            return
        }
        val root = SettingsStore.load(true).effectiveRoot
        runCatching {
            java.nio.file.Files.createDirectories(root)
            java.awt.Desktop.getDesktop().open(root.toFile())
        }.onFailure {
            Diag.log("open folder failed", it)
            toast("无法打开目录: $root", ToastType.Warning)
        }
    }

    @JvmStatic
    fun showLogPath() {
        toast("日志: ${Diag.logPath()}", ToastType.Success)
    }

    /**
     * 网络自检：并发探测 MusicBrainz / Cover Art Archive / 三个 CDDB 镜像。
     *
     * 用户分不清「盘没记录」和「网不通」时，点一下就知道；填了代理后也能立刻验证。
     */
    @JvmStatic
    fun testNetwork() {
        PanelState.set("正在自检网络…", "并发探测 MusicBrainz / Cover Art Archive / 三个 CDDB 镜像")
        exec.execute {
            val settings = runCatching { SettingsStore.load() }.getOrDefault(RipSettings())
            val items = runCatching { NetCheck.run(settings.proxyUrl) }.getOrElse { e ->
                Diag.log("netcheck failed", e)
                Notify.fail("网络自检失败：${e.message}")
                return@execute
            }
            val summary = NetCheck.summary(items)
            val detail = items.joinToString("；") {
                "${it.name} ${if (it.ok) "${it.ms}ms" else "不通（${it.note}）"}"
            }
            PanelState.set("网络自检完成", detail)
            Diag.log("netcheck: $summary")
            Notify.info(if (settings.proxyUrl.isBlank()) summary else "$summary（代理 ${settings.proxyUrl}）")
        }
    }

    /** 打开日志所在的文件夹（诊断页用；找不到就退回输出根目录）。 */
    @JvmStatic
    fun openLogFolder() {
        exec.execute {
            val dir = runCatching { java.nio.file.Path.of(Diag.logPath()).parent }.getOrNull()
            val target = dir ?: runCatching { SettingsStore.load(true).effectiveRoot }.getOrNull()
            if (target == null) {
                toast("还没有日志文件", ToastType.Warning)
                return@execute
            }
            runCatching {
                java.nio.file.Files.createDirectories(target)
                java.awt.Desktop.getDesktop().open(target.toFile())
            }.onFailure {
                Diag.log("open log folder failed", it)
                toast("无法打开目录: $target", ToastType.Warning)
            }
        }
    }

    /** 选手工元数据覆盖文件（album.json / 自定义 JSON）。 */
    @JvmStatic
    fun chooseOverrideFile() {
        exec.execute {
            val current = runCatching { SettingsStore.load(true).overrideFile }.getOrDefault("")
            val picked = pickFile(current, "选择元数据覆盖文件", "JSON 元数据 (json)", "json")
            if (picked == null) {
                toast("已取消选择", ToastType.Warning)
                return@execute
            }
            if (PanelState.settingsOpen) {
                PanelState.updateDraft { it.copy(overrideFile = picked) }
            } else {
                val s = SettingsStore.load(true)
                SettingsStore.save(s.copy(overrideFile = picked))
            }
            toast("覆盖文件：$picked", ToastType.Success)
        }
    }

    /**
     * 选输出目录。
     *
     * edittext 里手打路径对普通用户是不可接受的门槛（还容易漏正斜杠），
     * 所以开系统文件夹选择框。宿主没有提供选择器 API，这里直接用 Swing（JRE 自带 java.desktop），
     * 在 EDT 上弹出、工作线程等结果，父窗口尽量挂到宿主可见窗口上，避免对话框跑到应用背后。
     */
    @JvmStatic
    fun chooseOutputRoot() {
        exec.execute {
            val current = runCatching { SettingsStore.load(true).effectiveRoot.toString() }.getOrDefault("")
            val picked = pickFolder(current)
            if (picked == null) {
                toast("已取消选择", ToastType.Warning)
                return@execute
            }
            if (PanelState.settingsOpen) {
                PanelState.updateDraft { it.copy(outputRoot = picked) }
            } else {
                val s = SettingsStore.load(true)
                SettingsStore.save(s.copy(outputRoot = picked))
            }
            toast("输出目录：$picked", ToastType.Success)
            Diag.log("outputRoot set to $picked")
        }
    }

    private fun pickFolder(initial: String): String? {
        var result: String? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        runCatching {
            javax.swing.SwingUtilities.invokeLater {
                try {
                    val start = runCatching { if (java.nio.file.Files.isDirectory(Path.of(initial))) java.io.File(initial) else null }
                        .getOrNull()
                    val chooser = javax.swing.JFileChooser(start)
                    chooser.dialogTitle = "选择输出目录"
                    chooser.fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
                    chooser.isMultiSelectionEnabled = false
                    val parent = javax.swing.JFrame.getFrames()
                        .firstOrNull { it.isVisible && it.isShowing }
                    if (chooser.showOpenDialog(parent) == javax.swing.JFileChooser.APPROVE_OPTION) {
                        result = chooser.selectedFile?.absolutePath
                    }
                } catch (t: Throwable) {
                    Diag.log("folder picker failed", t)
                } finally {
                    latch.countDown()
                }
            }
        }.onFailure { Diag.log("folder picker dispatch failed", it); latch.countDown() }
        // 5 分钟足够人慢慢找目录；超时按取消处理，绝不让工作线程永久挂住
        val done = runCatching { latch.await(300, java.util.concurrent.TimeUnit.SECONDS) }.getOrDefault(false)
        if (!done) Diag.log("folder picker timed out")
        return result
    }

    /** 选 flac.exe 路径（用户不想填 PATH 时用）。 */
    @JvmStatic
    fun chooseFlacExe() {
        exec.execute {
            val current = runCatching { SettingsStore.load(true).flacExe }.getOrDefault("flac")
            val picked = pickFile(current, "选择 flac.exe", "FLAC 编码器 (flac.exe)", "exe")
            if (picked == null) {
                toast("已取消选择", ToastType.Warning)
                return@execute
            }
            if (PanelState.settingsOpen) {
                PanelState.updateDraft { it.copy(flacExe = picked) }
            } else {
                val s = SettingsStore.load(true)
                SettingsStore.save(s.copy(flacExe = picked))
            }
            toast("FLAC 编码器：$picked", ToastType.Success)
        }
    }

    private fun pickFile(initial: String, title: String, desc: String, ext: String): String? {
        var result: String? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        runCatching {
            javax.swing.SwingUtilities.invokeLater {
                try {
                    val start = runCatching {
                        val f = java.io.File(initial)
                        if (f.isFile) f.parentFile else if (f.isDirectory) f else null
                    }.getOrNull()
                    val chooser = javax.swing.JFileChooser(start)
                    chooser.dialogTitle = title
                    chooser.fileSelectionMode = javax.swing.JFileChooser.FILES_ONLY
                    chooser.fileFilter = javax.swing.filechooser.FileNameExtensionFilter(desc, ext)
                    val parent = javax.swing.JFrame.getFrames().firstOrNull { it.isVisible && it.isShowing }
                    if (chooser.showOpenDialog(parent) == javax.swing.JFileChooser.APPROVE_OPTION) {
                        result = chooser.selectedFile?.absolutePath
                    }
                } catch (t: Throwable) {
                    Diag.log("file picker failed", t)
                } finally {
                    latch.countDown()
                }
            }
        }.onFailure { Diag.log("file picker dispatch failed", it); latch.countDown() }
        runCatching { latch.await(300, java.util.concurrent.TimeUnit.SECONDS) }
        return result
    }

    /**
     * 校验输出目录（只读、不碰光驱）：WAV 头/扇区对齐/CUE 引用是否都能落地。
     * 抓轨中断、手动改名、磁盘写满之后，先跑这个比重新抓一遍省事得多。
     */
    @JvmStatic
    fun checkOutput() {
        exec.execute {
            try {
                val settings = SettingsStore.load(true)
                val dir = settings.effectiveRoot
                val report = com.saltrip.rip.album.OutputCheck.check(dir)
                val lines = ArrayList<String>()
                lines += report.summary()
                report.problems.take(20).forEach { lines += "· ${it.file}: ${it.detail}" }
                if (report.problems.size > 20) lines += "· …还有 ${report.problems.size - 20} 个问题，见日志"
                PanelState.set(lines.first(), lines.drop(1).joinToString("\n").ifBlank { "文件自洽，可以直接入库" })
                lines.forEach { Diag.log("checkOutput: $it") }
                toast(report.summary(), if (report.ok) ToastType.Success else ToastType.Warning)
            } catch (e: Throwable) {
                Diag.log("checkOutput failed", e)
                toast("校验失败: ${e.message}", ToastType.Error)
            }
        }
    }

    /** 清空 MusicBrainz 磁盘缓存（改了 DiscID 相关设置、或想强制刷新元数据时用）。 */
    @JvmStatic
    fun clearMetadataCache() {
        exec.execute {
            val n = com.saltrip.rip.meta.MbCache.clear()
            toast("已清除 $n 个元数据缓存文件", ToastType.Success)
            Diag.log("mb cache cleared: $n")
        }
    }

    /**
     * 试写测试：用当前模板 + 假元数据（宇多田ヒカル / Fantôme / 道）走一遍
     * 目录创建 → 中文/日文文件名写入 → 读回校验 → 清理，确认没有乱码或权限问题。
     */
    @JvmStatic
    fun probePath() {
        exec.execute {
            val settings = SettingsStore.load(true)
            val vars = mapOf(
                "albumartist" to "宇多田ヒカル",
                "albuminterpret" to "宇多田ヒカル",
                "albumtitle" to "Fantôme",
                "album" to "Fantôme",
                "year" to "2016",
                "genre" to "J-Pop",
                "discid" to "PROBE",
                "cddbid" to "PROBE",
                "discnumber" to "1",
                "totaldiscs" to "1",
                "tracknr" to "3",
                "tracknr1" to "3",
                "tracknr2" to "03",
                "tracknr3" to "003",
                "title" to "道 - テスト",
                "artist" to "宇多田ヒカル",
                "tracklen" to "3:30"
            )
            var dir = settings.effectiveRoot
            val segments = com.saltrip.rip.naming.TemplateEngine
                .expandToSegments(settings.folderTemplate, vars)
            segments.forEach { dir = dir.resolve(it) }
            val base = com.saltrip.rip.naming.TemplateEngine
                .expand(settings.fileTemplate, vars)
                .ifBlank { "probe" }
            val name = com.saltrip.rip.naming.TemplateEngine.sanitizeSegment(base, 150)
            try {
                java.nio.file.Files.createDirectories(dir)
                val file = dir.resolve("$name.probe.txt")
                java.nio.file.Files.writeString(file, "Salt Audio Copy probe 抓轨探针\n${settings.effectiveRoot}")
                val back = java.nio.file.Files.readString(file)
                val relative = settings.effectiveRoot.relativize(dir).toString().replace('\\', '/')
                Diag.log("probePath ok: $file (readback=${back.length} chars)")
                java.nio.file.Files.deleteIfExists(file)
                toast("试写成功\n$relative/$name\n路径与中文名均正常", ToastType.Success)
            } catch (e: Throwable) {
                Diag.log("probePath failed", e)
                toast("试写失败: ${e.message}\n目标: $dir", ToastType.Error)
            }
        }
    }

    /** 恢复内置默认设置（不碰已存在的文件）。 */
    @JvmStatic
    fun resetDefaults() {
        SettingsStore.save(RipSettings())
        toast("已恢复默认设置", ToastType.Success)
    }

    /** 供测试/诊断：当前设置的一行摘要。 */
    fun describeSettings(s: RipSettings = SettingsStore.load()): String =
        "root=${s.effectiveRoot} folder=${s.folderTemplate} file=${s.fileTemplate} " +
            "secure=${s.secureMode} offset=${s.offsetSamples} meta=${s.fetchMetadata}"
}
