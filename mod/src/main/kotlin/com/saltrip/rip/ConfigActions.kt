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

    /**
     * 「保存手填元数据」专用线程。
     *
     * [exec] 是单线程串行队列：自动查元数据（CDDB 镜像连不上时要等几十秒）占着它的时候，
     * 保存动作被排在后面，用户会以为「点保存没反应」。这条只写本地文件，
     * 单独一条线程既不会被查询堵住，也不会和查询互相抢。
     */
    private val saveExec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "saltrip-save").apply { isDaemon = true }
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
        toast(Strings.message("正在扫描光驱，请稍候…"), ToastType.Success)
        exec.execute {
            try {
                val drives = CdDevice.scanDrives()
                if (drives.isEmpty()) {
                    toast(Strings.message("未发现可用光驱（没有光驱或驱动器内无盘）"), ToastType.Warning)
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
                    statusText = text.lineSequence().firstOrNull() ?: Strings.message("已扫描"),
                    detailText = Strings.message("点「抓整盘」按当前设置输出；「查元数据」只联网不写文件")
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
                PanelState.set(Strings.fmt("扫描失败：%s", e.message))
                toast(Strings.fmt("扫描失败: %s", e.message), ToastType.Error)
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
            toast(Strings.message("当前盘不是音频 CD"), ToastType.Warning)
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
                PanelState.set(Strings.message("已导出元数据模板"), target.toString())
                toast("已导出：$target\n改完保存，再抓整盘即生效", ToastType.Success)
            } catch (e: Throwable) {
                Diag.log("exportAlbumJson failed", e)
                toast(Strings.fmt("导出失败: %s", e.message), ToastType.Error)
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
        PanelState.set(if (com.saltrip.rip.ui.PanelMount.mounted) Strings.message("面板已挂载") else "面板未挂载", text)
        toast(text.lineSequence().first(), ToastType.Success)
    }

    @JvmStatic
    fun showCdText() {
        val scan = ScanResultHolder.get() ?: run {
            toast(Strings.message("请先点「扫描驱动器」"), ToastType.Warning)
            return
        }
        exec.execute {
            try {
                val info = CdDevice(scan.path).use { it.readCdText() }
                if (info == null || !info.hasText) {
                    Diag.log("cd-text: none")
                    PanelState.set(Strings.message("CD-TEXT：无"), "这张盘没有 CD-TEXT")
                    toast(Strings.message("这张盘没有 CD-TEXT"), ToastType.Warning)
                    return@execute
                }
                val sb = StringBuilder()
                sb.append("专辑: ").append(info.albumTitle ?: "-").append('\n')
                sb.append("艺术家: ").append(info.albumPerformer ?: "-").append('\n')
                sb.append("流派: ").append(info.genre ?: "-").append("  字符集: ").append(info.charset).append('\n')
                info.titles.toSortedMap().forEach { (n, v) -> sb.append("%02d. %s\n".format(n, v)) }
                Diag.log("cd-text: " + sb.toString().replace("\n", " | "))
                PanelState.set(Strings.fmt("CD-TEXT：%s 轨", info.titles.size), sb.toString())
                toast(Strings.fmt("CD-TEXT 读取成功：%s 轨", info.titles.size), ToastType.Success)
            } catch (e: Throwable) {
                Diag.log("cd-text probe failed", e)
                toast(Strings.fmt("CD-TEXT 读取失败: %s", e.message), ToastType.Error)
            }
        }
    }

    /** 整盘间隙预扫（只读）：TOC + 每轨 INDEX 00，结果进面板与日志。 */
    @JvmStatic
    fun analyzeDisc() {
        val scan = ScanResultHolder.get() ?: run {
            toast(Strings.message("请先点「扫描驱动器」"), ToastType.Warning)
            return
        }
        exec.execute {
            try {
                val table = CdDevice(scan.path).use { dev ->
                    val gaps = com.saltrip.rip.album.DiscAnalysis.gapsFor(scan.toc, dev) { Diag.log(it) }
                    com.saltrip.rip.album.DiscAnalysis.describe(gaps)
                }
                Diag.log("analysis: " + table.replace("\n", " | "))
                PanelState.set(Strings.message("间隙预扫完成"), table)
                toast(Strings.message("间隙预扫完成"), ToastType.Success)
            } catch (e: Throwable) {
                Diag.log("analysis failed", e)
                toast(Strings.fmt("间隙预扫失败: %s", e.message), ToastType.Error)
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
                toast(Strings.fmt("导出诊断失败: %s", e.message), ToastType.Error)
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
        toast(Strings.message("正在探测读命令（只读，1 扇区）…"), ToastType.Success)
        exec.execute {
            try {
                val scan = ScanResultHolder.get() ?: CdDevice.scanDrives().firstOrNull()?.let {
                    com.saltrip.rip.rip.ScanResult(it.first, it.second, CdDevice(it.first).use { d -> d.readToc() })
                }
                if (scan == null) {
                    toast(Strings.message("没有找到光驱"), ToastType.Warning)
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
                PanelState.set(Strings.fmt("读命令探测完成（%s/%s 可用）", available, com.saltrip.rip.scsi.ReadCommand.entries.size), table)
                toast(
                    if (available > 0) "读命令探测完成：$available 条可用" else "所有读命令都失败，驱动器可能仍挂起",
                    if (available > 0) ToastType.Success else ToastType.Error
                )
            } catch (e: Throwable) {
                Diag.log("read-command probe failed", e)
                toast(Strings.fmt("探测失败：%s", e.message), ToastType.Error)
            }
        }
    }

    @JvmStatic
    fun healthCheck() {
        toast(Strings.message("正在检查驱动器（只读）…"), ToastType.Success)
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
                        Strings.message("请到「设备管理器 → DVD/CD-ROM 驱动器 → 禁用再启用」，或重启后再试。"),
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
        toast(Strings.message("正在计算抓轨计划（不写盘）…"), ToastType.Success)
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
                PanelState.set(Strings.message("抓轨计划已生成"), "共 ${plan.tracks.size} 轨，约 ${plan.totalBytes / 1024 / 1024} MB")
                toast(text.take(900), ToastType.Success)
            } catch (e: Throwable) {
                Diag.log("previewPlan failed", e)
                toast(Strings.fmt("计划生成失败: %s", e.message), ToastType.Error)
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
            if (interactive) toast(Strings.message("「查询元数据」已在设置里关闭"), ToastType.Warning)
            return
        }
        if (!interactive && !manual && !settings.autoLookupOnInsert) {
            Diag.log("auto lookup skipped: 设置里关掉了「插入或换盘时自动查元数据」")
            return
        }
        // 本地已经有这张盘的元数据（手填或上次选过的候选）时，自动查询不要插一脚：
        // 网络查不到会把面板上的名字顶成「未知」，用户还得再开一次手填面板保存才回来。
        if (!interactive && !manual) {
            val localId = PanelState.disc?.discId.orEmpty()
            val local = if (localId.isBlank()) null else runCatching {
                readCachedAlbum(settings.effectiveRoot, localId)
            }.getOrNull()
            if (hasLocalNames(local)) {
                Diag.log("auto lookup skipped: 本地已有元数据（source=${local?.source}）")
                PanelState.rememberMeta(localId, local)
                PanelState.set(
                    Strings.message("使用已保存的元数据"),
                    Strings.message("这张盘有你填过或选过的信息；要重新联网查可以点「刷新元数据」")
                )
                return
            }
        }
        if (!lookupBusy.compareAndSet(false, true)) {
            Diag.log("lookup skipped: 上一次查询还没结束")
            return
        }
        // 不管是自动还是用户点的「刷新」，都要有可见状态：慢的时候用户得知道在等什么。
        PanelState.metaBusy = true
        PanelState.set(
            Strings.message("元数据正在加载…"),
            Strings.message("读 CD-TEXT，再连 MusicBrainz / gnudb；这期间抓轨按钮会先锁住")
        )
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
                // 查不到时沿用面板上已有的歌手/专辑（本地缓存或刚手填的），
                // 否则「刷新一下」就把用户的信息清空了。
                val keepArtist = keepNames(result.best?.albumArtist, PanelState.disc?.artist)
                val keepAlbum = keepNames(result.best?.albumTitle, PanelState.disc?.album)
                PanelState.showDisc(
                    PanelState.DiscInfo(
                        tracks = scan.toc.tracks.size,
                        artist = keepArtist,
                        album = keepAlbum,
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
                        Strings.message("没查到专辑信息"),
                        if (netNote != null) {
                            Strings.fmt("网络不通：%s —— 可在设置里填 HTTP 代理，或直接手动填写", netNote)
                        } else {
                            Strings.message("点「手动填写元数据…」自己写歌手/专辑名/歌名，或换一张盘再查")
                        }
                    )
                    if (interactive) toast("未找到元数据\n" + result.notes.takeLast(2).joinToString("\n"), ToastType.Warning)
                } else {
                    val best = result.best!!
                    PanelState.set(
                        Strings.fmt("已找到 %s 个候选", result.candidates.size),
                        Strings.message("点选封面可换版本；抓轨会用选中版本的信息命名")
                    )
                    if (interactive) {
                        toast(
                            "找到 ${result.candidates.size} 个候选，已在面板列出\n" +
                                Strings.fmt("当前：%s", best.label()),
                            ToastType.Success
                        )
                    }
                }
            } catch (e: Throwable) {
                Diag.log("lookupMetadata failed", e)
                if (interactive) toast(Strings.message("查询失败：") + com.saltrip.rip.scsi.ScsiMessages.human(e), ToastType.Error)
            } finally {
                lookupBusy.set(false)
                PanelState.metaBusy = false
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
        toast(Strings.fmt("正在获取「%s」…", picked.label), ToastType.Success)
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
                    toast(Strings.message("没能取到该版本的完整信息，请换一个候选"), ToastType.Warning)
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
                PanelState.set(Strings.fmt("已选用：%s - %s", album.albumArtist, album.albumTitle), notes.takeLast(1).joinToString())
                toast(Strings.fmt("已选用「%s - %s」", album.albumArtist, album.albumTitle), ToastType.Success)
            } catch (e: Throwable) {
                Diag.log("selectCandidate failed", e)
                toast(Strings.message("获取失败：") + com.saltrip.rip.scsi.ScsiMessages.human(e), ToastType.Error)
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
        PanelState.set(Strings.message("正在准备手动填写…"), "读取曲目数与本机缓存")
        exec.execute {
            try {
                val scan = freshScan() ?: return@execute
                if (!scan.toc.isAudioCd) {
                    PanelState.manualOpen = false
                    toast(Strings.message("当前盘不是音频 CD（数据盘不能抓轨）"), ToastType.Warning)
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
                        if (ManualDraft.displayable(draft.albumArtist).isBlank() && ctArtist.isNotBlank()) {
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
                    Strings.message("手动填写元数据"),
                    if (prefilled) "已用盘上的 CD-TEXT 预填，改完保存即可"
                    else "写歌手/专辑名/歌名，保存后抓轨按这些名字命名并写标签"
                )
            } catch (e: Throwable) {
                Diag.log("openManualMeta failed", e)
                PanelState.manualOpen = false
                toast(Strings.message("打开手动填写失败：") + com.saltrip.rip.scsi.ScsiMessages.human(e), ToastType.Error)
            }
        }
    }

    /**
     * 保存手填元数据时「先让界面更新」的那一步要显示的摘要。
     *
     * 纯函数、不碰光驱：坞上立刻换成用户刚写的歌手/专辑/轨数，
     * 时长与 DiscID 沿用盘上已有的信息（未扫描过就用空串）。
     */
    internal fun optimisticDisc(
        draft: com.saltrip.rip.ui.ManualDraft,
        before: com.saltrip.rip.ui.PanelState.DiscInfo?
    ): com.saltrip.rip.ui.PanelState.DiscInfo {
        val album = draft.toAlbumMeta(before?.discId.orEmpty())
        return com.saltrip.rip.ui.PanelState.DiscInfo(
            tracks = album.tracks.size,
            artist = album.albumArtist,
            album = album.albumTitle,
            lengthText = before?.lengthText.orEmpty(),
            discId = before?.discId.orEmpty(),
            audio = before?.audio ?: true
        )
    }

    /**
     * 保存手填的元数据。
     *
     * 写两份同样的 JSON：
     * - `<输出根>/.saltaudiocopy/album-<DiscID>.json`：抓轨时 `AlbumRipper` 直接沿用；
     * - `<输出根>/<DiscID>.album.json`：`MetaOverride` 认的位置，「刷新元数据」/离线查询也能读到。
     *
     * 分两步：**先立刻改面板状态**（用户点了保存就该马上看到名字变了），
     * 再把写文件丢到 [saveExec]。旧实现在工作线程里先 `freshScan()`（读 TOC，
     * 可能等上十几秒）且排在查询后面，于是"点保存没反应"。
     */
    @JvmStatic
    fun saveManualMeta() {
        val draft = PanelState.manual
        if (!draft.hasAnything) {
            Notify.warn(Strings.message("至少写一个专辑名或歌名再保存"))
            return
        }

        val album = draft.toAlbumMeta(PanelState.disc?.discId.orEmpty())
        // 1) 立刻反映到坞上：轨数/歌手/专辑名都用刚填的，长度与 DiscID 沿用当前盘信息。
        val before = PanelState.disc
        PanelState.showDisc(optimisticDisc(draft, before))
        PanelState.showCandidates(emptyList())
        // 手导的封面也立刻显示（落盘要等后台线程，不能让用户以为没生效）
        PanelState.manualCover?.let { p ->
            runCatching { java.nio.file.Files.readAllBytes(java.nio.file.Path.of(p)) }
                .getOrNull()?.let { PanelState.discCover = it }
        }
        PanelState.lookupTried = true
        // 内存里留一份：换输出根 / 缓存读不到时标题不会变「未知」
        PanelState.rememberMeta(before?.discId.orEmpty(), album)
        PanelState.closeManual()
        PanelState.set(
            Strings.message("已保存手动元数据"),
            Strings.fmt("%s — %s（%s 轨）", album.albumArtist, album.albumTitle, album.tracks.size)
        )
        Notify.info(Strings.message("已保存手动元数据，抓轨会用这些名字"))

        // 2) 落盘（独立线程，不跟自动查询抢队）
        saveExec.execute {
            try {
                // freshScan() 内部会 Discs.publish()：此刻 JSON 还没写，缓存是空的，
                // 面板会被重新刷成「未知艺术家」。所以扫描后立刻把乐观值再盖回去，
                // 等写完文件再用 publish 从缓存重建（那一次才是准的）。
                val scan = freshScan() ?: run {
                    Notify.fail(Strings.message("保存失败：读不到光盘，文件没有写入"))
                    return@execute
                }
                val keptCover = PanelState.discCover
                PanelState.showDisc(optimisticDisc(draft, PanelState.disc))
                PanelState.discCover = keptCover
                val settings = SettingsStore.load()
                val toc = scan.toc
                val discId = runCatching { DiscId.musicBrainz(toc) }.getOrDefault("")
                val finalAlbum = draft.toAlbumMeta(discId)
                val json = AlbumJsonWriter.build(finalAlbum, toc)
                val root = settings.effectiveRoot
                val cachePath = manualCachePath(root, discId, toc.leadOutLba)
                java.nio.file.Files.createDirectories(cachePath.parent)
                java.nio.file.Files.writeString(cachePath, json, Charsets.UTF_8)
                if (discId.isNotBlank()) {
                    java.nio.file.Files.writeString(root.resolve("$discId.album.json"), json, Charsets.UTF_8)
                }
                Diag.log("manual album.json saved: $cachePath")
                saveManualCover(cachePath, root, discId)
                PanelState.rememberMeta(discId, finalAlbum)
                // 保险副本：用户之后换输出根、甚至删掉输出目录，这张盘的信息都还在
                if (discId.isNotBlank()) com.saltrip.rip.album.DiscCache.mirrorToVault(root, discId)

                // 文件已落盘：从缓存重建一次盘摘要（补上真实 DiscID 与封面）。
                // 这一步是幂等的，也能修掉 freshScan 期间被清空的名字。
                PanelState.showDisc(optimisticDisc(draft, PanelState.disc))
                if (discId.isNotBlank()) {
                    PanelState.showDisc(
                        PanelState.DiscInfo(
                            tracks = finalAlbum.tracks.size,
                            artist = finalAlbum.albumArtist,
                            album = finalAlbum.albumTitle,
                            lengthText = PanelState.disc?.lengthText.orEmpty(),
                            discId = discId,
                            audio = PanelState.disc?.audio ?: true
                        )
                    )
                }
                PanelState.discCover = readCachedAlbum(root, discId)?.cover ?: PanelState.discCover
                Diag.log("manual meta persisted: ${finalAlbum.tracks.size} tracks")
            } catch (e: Throwable) {
                Diag.log("saveManualMeta failed", e)
                toast(Strings.message("保存失败：") + com.saltrip.rip.scsi.ScsiMessages.human(e), ToastType.Error)
            }
        }
    }

    /**
     * 弹文件/目录选择框的线程。
     *
     * 选择框是模态的，会一直占着线程直到用户点确定或取消。放进 [exec] 的话，
     * 一旦用户开着框发呆，后面所有任务（抓轨、查元数据）都被卡住；
     * 放进 [saveExec] 又会挡住保存。所以每次单开一条一次性线程。
     */
    private fun pickerThread(name: String, block: () -> Unit) {
        Thread(block, name).apply { isDaemon = true }.start()
    }

    /** 手填元数据时导入本地封面（没有网络元数据的盘常常也没有封面）。 */
    @JvmStatic
    fun chooseManualCover() {
        pickerThread("saltrip-picker-cover") {
            val current = PanelState.manualCover.orEmpty()
            val picked = pickFile(current, "选择专辑封面", "图片 (jpg / jpeg / png / webp / bmp)", "jpg", "jpeg", "jpe", "jfif", "png", "webp", "bmp", "gif")
            if (picked == null) {
                toast(Strings.message("已取消选择"), ToastType.Warning)
                return@pickerThread
            }
            PanelState.manualCover = picked
            Notify.info(Strings.message("封面已选：") + picked.substringAfterLast('\\').substringAfterLast('/'))
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
                Notify.info(Strings.message("已用 CD-TEXT 填入可用的字段"))
            } catch (e: Throwable) {
                Diag.log("fillManualFromCdText failed", e)
                toast(Strings.message("读 CD-TEXT 失败：") + com.saltrip.rip.scsi.ScsiMessages.human(e), ToastType.Error)
            }
        }
    }

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

    /**
     * 读已有的 album.json 缓存（读不到就当没有，不报错）。
     *
     * 先在当前输出根里找；找不到再看系统「音乐」文件夹——用户改过输出根之后，
     * 之前手填的元数据与封面还留在旧根下，不回落就会显示成「未知艺术家 / 未知专辑」。
     */
    internal fun readCachedAlbum(root: java.nio.file.Path, discId: String): com.saltrip.rip.meta.AlbumMeta? {
        if (discId.isBlank()) return null
        // 输出根 → 保险副本 → 系统音乐文件夹（换过输出根也不丢手填信息）
        return com.saltrip.rip.album.DiscCache.load(root, discId)
    }

    /** 抓取整张专辑：元数据 → 模板命名 → 逐轨双读校验 → 封面/CUE/日志。 */
    @JvmStatic
    fun ripAlbum() {
        if (busy) {
            toast(Strings.message("已有抓轨任务在进行"), ToastType.Warning)
            return
        }
        // 这张盘已经抓完一遍：第一次点击只确认，第二次才真的覆盖
        if (PanelState.ripDone && !PanelState.reRipArmed) {
            PanelState.reRipArmed = true
            val policy = SettingsStore.load().overwritePolicy
            val policyTalk = when (policy) {
                "overwrite" -> "同名文件会被覆盖"
                "skip" -> "同名文件会跳过（不重抓）"
                else -> "同名文件会另存为「(2)、(3)…」副本，不会覆盖旧的"
            }
            PanelState.set(
                Strings.message("已抓完 · 要重新抓取吗？再点一次「重新抓取」确认"),
                Strings.fmt("第二次点击才会真的重抓；%s。点「取消」或换盘会解除确认。", policyTalk)
            )
            toast(
                Strings.fmt("确认中：再点一次「重新抓取」才开始。%s。点「取消」解除确认。", policyTalk),
                ToastType.Warning
            )
            return
        }
        val scan = freshScan() ?: return
        if (!scan.toc.isAudioCd) {
            toast(Strings.message("这张盘不是音频 CD（数据盘不能抓轨）：请换一张音乐 CD"), ToastType.Warning)
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
        PanelState.set(Strings.fmt("正在抓取 %s 轨…", count), "可用「取消」在当前块结束后停止，已抓部分保留")
        val modeTalk = if (settings.secureMode) Strings.message("双读校验") else Strings.message("快速模式")
        toast(Strings.fmt("开始抓取整张专辑（%s 轨，%s）…", count, modeTalk), ToastType.Success)
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
                                    Strings.fmt("已用 %ss 剩余约 %ss 重试 %s", p.elapsedMs / 1000, p.etaMs / 1000, p.errors)
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
                    Strings.message("  %02d  %6d 扇区  CRC32 %08X%s").format(
                        r.number, r.sectors, r.crc32,
                        if (r.errors > 0) "  (重试 ${r.errors})" else ""
                    )
                }
                Diag.log("album result:\n$detail\nnotes:\n" + result.notes.joinToString("\n"))
                if (result.cancelled) {
                    val tail = if (removedFiles > 0) {
                        Strings.fmt("已删除本次生成的 %s 个文件", removedFiles)
                    } else {
                        Strings.fmt("文件保留在 %s", result.outDir)
                    }
                    PanelState.set(Strings.message("已取消"), tail)
                    toast(Strings.fmt("已取消：完成 %s/%s 轨，%s", result.tracks.size, count, tail), ToastType.Warning)
                } else {
                    val bits = buildList {
                        add(Strings.fmt("抓轨完成：%s/%s 轨，用时 %s 秒", result.tracks.size, count, result.elapsedMs / 1000))
                        result.album?.let { add("${it.albumArtist} - ${it.albumTitle}") }
                        if (result.coverFile != null) add(Strings.message("封面已保存"))
                        result.cueFile?.let { add("CUE：${it.fileName}") }
                        result.logFile?.let { add(Strings.fmt("日志：%s", it.fileName)) }
                        val retries = result.tracks.sumOf { it.errors }
                        if (retries > 0) add(Strings.fmt("重试 %s 次", retries))
                    }
                    PanelState.markRipped(result.tracks.size)
                    // 覆盖策略为 suffix 时会静默生成「名字 (2).wav」：真机上用户会以为抓了两份，
                    // 必须写进「需要留意」，否则只看到目录里多出一套文件却不知道为什么。
                    val duplicates = result.createdFiles.filter {
                        Regex("""\(\d+\)\.[A-Za-z0-9]+$""").containsMatchIn(it.fileName.toString())
                    }
                    val warns = result.warnings + if (duplicates.isNotEmpty()) {
                        listOf(
                            Strings.fmt("同名文件已存在，本次写成「%s」这样的副本", duplicates.first().fileName) +
                                Strings.message("（要替换请在 设置 → 输出位置 → 同名文件已存在时 选「覆盖」）")
                        )
                    } else emptyList()
                    PanelState.status = if (warns.isEmpty()) {
                        Strings.fmt("已抓完 · %s 轨 · 用时 %s 秒", result.tracks.size, result.elapsedMs / 1000)
                    } else {
                        // 有失败项时别只给绿字：用户会以为 FLAC/CUE 都好好生成了
                        Strings.fmt("已抓完（%s 项需要留意）· %s 轨 · 用时 %s 秒", warns.size, result.tracks.size, result.elapsedMs / 1000)
                    }
                    PanelState.lastOutDir = result.outDir
                    PanelState.detail = buildString {
                        append(result.outDir)
                        result.cueFile?.let { append("\nCUE：").append(it) }
                        if (warns.isNotEmpty()) {
                            append("\n需要留意：")
                            warns.take(3).forEach { append("\n· ").append(it) }
                            if (warns.size > 3) append("\n· …共 ").append(warns.size).append(" 项，详见日志")
                        }
                        append("\n再抓一遍请点「重新抓取」（会先确认）")
                    }
                    if (warns.isEmpty()) {
                        toast(bits.joinToString("\n") + "\n${result.outDir}", ToastType.Success)
                    } else {
                        toast(
                            "抓轨结束，但有 ${warns.size} 项需要注意：\n" +
                                warns.take(2).joinToString("\n") +
                                "\n${result.outDir}",
                            ToastType.Warning
                        )
                    }
                }
            } catch (e: com.saltrip.rip.scsi.ScsiException) {
                Diag.log("ripAlbum scsi failure", e)
                PanelState.set(Strings.message("抓轨失败"), com.saltrip.rip.scsi.ScsiMessages.human(e))
                toast(Strings.message("抓轨失败：") + com.saltrip.rip.scsi.ScsiMessages.human(e), ToastType.Error)
            } catch (e: Throwable) {
                Diag.log("ripAlbum failed", e)
                toast(Strings.fmt("抓轨失败: %s", e.message), ToastType.Error)
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
            toast(Strings.message("已有任务在进行"), ToastType.Warning)
            return
        }
        if (!scan.toc.isAudioCd) {
            toast(Strings.message("这不是音频 CD"), ToastType.Warning)
            return
        }
        busy = true
        cancelFlag.set(false)
        PanelState.begin("只测不写（Test only）")
        toast(Strings.message("开始测试读取，不会写任何文件…"), ToastType.Success)
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
                PanelState.set(Strings.message("测试完成（未写盘）"), text)
                toast(Strings.fmt("测试完成：%s 轨，未写任何文件", rows.size), ToastType.Success)
            } catch (e: Throwable) {
                Diag.log("test-only failed", e)
                val why = com.saltrip.rip.scsi.ScsiMessages.human(e)
                PanelState.set(Strings.message("测试失败"), why)
                toast(Strings.fmt("测试失败：%s", why), ToastType.Error)
            } finally {
                busy = false
            }
        }
    }

    @JvmStatic
    fun ripFirstTrack() {
        val scan = freshScan() ?: return
        if (busy) {
            toast(Strings.message("已有抓轨任务在进行"), ToastType.Warning)
            return
        }
        busy = true
        cancelFlag.set(false)
        toast(Strings.message("开始抓取第 1 轨…"), ToastType.Success)
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
                    toast(Strings.fmt("已取消（%s/%s 扇区）", result.sectors, scan.toc.sectorsOf(0).second), ToastType.Warning)
                } else {
                    toast("完成: ${result.sectors} 扇区, CRC32 $hex\n${result.outFile}", ToastType.Success)
                }
            } catch (e: com.saltrip.rip.scsi.ScsiException) {
                Diag.log("ripFirstTrack scsi failure", e)
                toast(Strings.message("读取失败：") + com.saltrip.rip.scsi.ScsiMessages.human(e), ToastType.Error)
            } catch (e: Throwable) {
                Diag.log("ripFirstTrack failed", e)
                toast(Strings.fmt("抓轨失败: %s", e.message), ToastType.Error)
            } finally {
                busy = false
                PanelState.clearProgress()
            }
        }
    }

    /**
     * 暂停 / 继续。
     *
     * 暂停在**当前块**结束后生效（一块约 1/25 轨，慢盘上可能一两秒）。以前这里会把
     * `PanelState.progress` 清空，进度条整块消失、只剩一条会被坞挡住的宿主 toast，
     * 用户看到的现象就是「点了暂停没反应、再点也没反应」（C1 反馈）。
     * 现在进度条与百分比全程保留，暂停状态写在坞里，用户能一眼看出停在哪。
     */
    @JvmStatic
    fun pauseRip() {
        if (!busy) {
            toast(Strings.message("当前没有抓轨任务"), ToastType.Warning)
            return
        }
        paused = !paused
        PanelState.paused = paused
        if (paused) {
            PanelState.set(Strings.message("已暂停"), "当前块读完后停住；点「继续」接着从这一块往下抓")
            toast(Strings.message("已暂停（当前块结束后停住），再点一次继续"), ToastType.Warning)
        } else {
            PanelState.set(PanelState.stage.ifBlank { Strings.message("正在抓取") }, "已继续")
            toast(Strings.message("继续抓轨"), ToastType.Success)
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
            toast(Strings.message("当前没有抓轨任务"), ToastType.Warning)
            return
        }
        if (!PanelState.cancelArmed) {
            PanelState.cancelArmed = true
            PanelState.paused = false
            paused = false
            toast(Strings.message("再点一次「取消」将中止并删除本次生成的文件"), ToastType.Warning)
            return
        }
        PanelState.cancelArmed = false
        paused = false
        PanelState.paused = false
        userCancel = true
        cancelFlag.set(true)
        toast(Strings.message("正在取消…"), ToastType.Warning)
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

    /**
     * 写回设置并给出反馈。
     *
     * 宿主配置写不进去时**不能**继续报成功：用户会以为重启后还在，实际只在本次运行有效。
     * 顺带把「保存失败」这件事写进面板 detail，方便事后判断。
     */
    internal fun persist(next: com.saltrip.rip.settings.RipSettings, okMessage: String): Boolean {
        val ok = SettingsStore.save(next)
        if (ok) {
            toast(okMessage, ToastType.Success)
        } else {
            PanelState.set(Strings.message("设置没能写回磁盘"), "宿主配置不可用，改动只在本次运行有效；详见日志")
            toast(Strings.message("设置没能写回磁盘（详见日志）"), ToastType.Error)
        }
        return ok
    }

    /** 切换测试模式（设置页与面板测试动作的开关）。 */
    @JvmStatic
    fun toggleTestMode() {
        val s = SettingsStore.load(true)
        val next = s.copy(testMode = !s.testMode)
        persist(next, "测试模式：${if (next.testMode) "开" else "关"}")
        PanelState.testMode = next.testMode
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
        val saved = SettingsStore.save(d)
        PanelState.testMode = d.testMode
        PanelState.settingsOpen = false
        if (saved) {
            toast(Strings.message("设置已保存"), ToastType.Success)
        } else {
            // 宿主配置写不进去时不能只说「已保存」：用户会以为重启后还在
            PanelState.set(Strings.message("设置没能写回磁盘"), "宿主配置不可用，改动只在本次运行有效；详见日志")
            toast(Strings.message("设置没能写回磁盘（详见日志）"), ToastType.Error)
        }
    }


    /**
     * 本地缓存算不算「已经有名字」。
     *
     * 自动查询只在没有本地信息时才跑：否则网络查不到会把面板顶回「未知艺术家」。
     */
    internal fun hasLocalNames(meta: com.saltrip.rip.meta.AlbumMeta?): Boolean =
        meta != null && (meta.albumArtist.isNotBlank() || meta.albumTitle.isNotBlank())

    /** 查询结果为空时沿用面板上已有的名字，别把用户填的清空。 */
    internal fun keepNames(best: String?, current: String?): String =
        best?.takeIf { it.isNotBlank() } ?: current.orEmpty()

    /** 保存面板里编辑的三个模板（目录 / 文件名 / 封面名）。 */
    @JvmStatic
    fun saveTemplates(folder: String, file: String, cover: String) {
        val s = SettingsStore.load(true)
        val next = s.copy(
            folderTemplate = folder.ifBlank { "%albumartist%/%albumtitle%" },
            fileTemplate = file.ifBlank { "%tracknr2% %title%" },
            coverFileName = cover.ifBlank { "cover" }
        )
        persist(next, "模板已保存")
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
            else -> {
                toast(Strings.fmt("未知设置项: %s", key), ToastType.Error)
                return
            }
        }
        persist(next, "已保存：$key")
        if (key == "testMode") PanelState.testMode = value
    }

    /** 切换双读校验（写回宿主配置）。 */
    @JvmStatic
    fun toggleSecureMode() {
        val s = SettingsStore.load(true)
        val next = s.copy(secureMode = !s.secureMode)
        persist(next, "双读校验：${if (next.secureMode) "开" else "关"}")
    }

    /** 切换元数据联网查询。 */
    @JvmStatic
    fun toggleMetadata() {
        val s = SettingsStore.load(true)
        val next = s.copy(fetchMetadata = !s.fetchMetadata)
        persist(next, "联网元数据：${if (next.fetchMetadata) "开" else "关"}")
    }

    /**
     * 多光驱时切换目标光驱：写进设置（下次抓轨也用这台），然后重扫。
     * 只切「默认目标」，不隐式开始抓轨——用户可能只是想看看另一台里是什么盘。
     */
    @JvmStatic
    fun selectDrive(letter: String) {
        val s = SettingsStore.load(true)
        val upper = letter.uppercase()
        PanelState.selectedDrive = upper
        persist(s.copy(driveLetter = upper), "已切换到光驱 $upper:")
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
                    toast(Strings.fmt("无法打开目录: %s", last), ToastType.Warning)
                }
            return
        }
        val root = SettingsStore.load(true).effectiveRoot
        runCatching {
            java.nio.file.Files.createDirectories(root)
            java.awt.Desktop.getDesktop().open(root.toFile())
        }.onFailure {
            Diag.log("open folder failed", it)
            toast(Strings.fmt("无法打开目录: %s", root), ToastType.Warning)
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
    /**
     * 环境自检：系统语言、代码页、路径编码，以及中日韩路径是否真能写入。
     *
     * 用户报「乱码」时，先让他点这个：一眼能分出是系统代码页、JVM 路径编码，
     * 还是标签写入策略的问题。
     */
    @JvmStatic
    fun testEnvironment() {
        exec.execute {
            try {
                val report = EnvProbe.collect()
                Diag.log("env:\n" + EnvProbe.details(report))
                val line = EnvProbe.summary(report)
                PanelState.set(
                    Strings.message("环境自检完成"),
                    EnvProbe.details(report).trim().replace("\n", "；")
                )
                if (report.cjkPathRoundTrip) Notify.info(line) else Notify.warn(line)
            } catch (e: Throwable) {
                Diag.log("env probe failed", e)
                Notify.fail(Strings.message("环境自检失败：") + com.saltrip.rip.scsi.ScsiMessages.human(e))
            }
        }
    }

    @JvmStatic
    fun testNetwork() {
        PanelState.set(Strings.message("正在自检网络…"), "并发探测 MusicBrainz / Cover Art Archive / 三个 CDDB 镜像")
        exec.execute {
            val settings = runCatching { SettingsStore.load() }.getOrDefault(RipSettings())
            val items = runCatching { NetCheck.run(settings.proxyUrl) }.getOrElse { e ->
                Diag.log("netcheck failed", e)
                Notify.fail(Strings.fmt("网络自检失败：%s", e.message))
                return@execute
            }
            val summary = NetCheck.summary(items)
            val detail = items.joinToString("；") {
                "${it.name} ${if (it.ok) "${it.ms}ms" else "不通（${it.note}）"}"
            }
            PanelState.set(Strings.message("网络自检完成"), detail)
            Diag.log("netcheck: $summary")
            Notify.info(if (settings.proxyUrl.isBlank()) summary else Strings.fmt("%s（代理 %s）", summary, settings.proxyUrl))
        }
    }

    /** 打开日志所在的文件夹（诊断页用；找不到就退回输出根目录）。 */
    @JvmStatic
    fun openLogFolder() {
        exec.execute {
            val dir = runCatching { java.nio.file.Path.of(Diag.logPath()).parent }.getOrNull()
            val target = dir ?: runCatching { SettingsStore.load(true).effectiveRoot }.getOrNull()
            if (target == null) {
                toast(Strings.message("还没有日志文件"), ToastType.Warning)
                return@execute
            }
            runCatching {
                java.nio.file.Files.createDirectories(target)
                java.awt.Desktop.getDesktop().open(target.toFile())
            }.onFailure {
                Diag.log("open log folder failed", it)
                toast(Strings.fmt("无法打开目录: %s", target), ToastType.Warning)
            }
        }
    }

    /** 选手工元数据覆盖文件（album.json / 自定义 JSON）。 */
    @JvmStatic
    fun chooseOverrideFile() {
        pickerThread("saltrip-picker-override") {
            val current = runCatching { SettingsStore.load(true).overrideFile }.getOrDefault("")
            val picked = pickFile(current, "选择元数据覆盖文件", "JSON 元数据 (json)", "json")
            if (picked == null) {
                toast(Strings.message("已取消选择"), ToastType.Warning)
                return@pickerThread
            }
            if (PanelState.settingsOpen) {
                PanelState.updateDraft { it.copy(overrideFile = picked) }
            } else {
                val s = SettingsStore.load(true)
                SettingsStore.save(s.copy(overrideFile = picked))
            }
            toast(Strings.fmt("覆盖文件：%s", picked), ToastType.Success)
        }
    }

    /**
     * 选输出目录。
     *
     * edittext 里手打路径对普通用户是不可接受的门槛（还容易漏正斜杠），
     * 所以开系统文件夹选择框。宿主没有提供选择器 API，这里直接用 Swing（JRE 自带 java.desktop），
     * 在 EDT 上弹出、工作线程等结果，父窗口尽量挂到宿主可见窗口上，避免对话框跑到应用背后。
     *
     * 选完**立刻落盘**（设置面板开着时同时更新草稿），不再等用户按「保存」——
     * 以前只改草稿却照样提示「输出目录：…」，用户以为生效，结果下一次抓轨还落在旧目录。
     */
    @JvmStatic
    fun chooseOutputRoot() {
        pickerThread("saltrip-picker-root") {
            val current = runCatching { SettingsStore.load(true).effectiveRoot.toString() }.getOrDefault("")
            val picked = pickFolder(current)
            if (picked == null) {
                toast(Strings.message("已取消选择"), ToastType.Warning)
                return@pickerThread
            }
            val s = SettingsStore.load(true)
            val oldRoot = s.effectiveRoot
            if (!SettingsStore.save(s.copy(outputRoot = picked))) {
                toast(Strings.message("设置没能写回磁盘"), ToastType.Error)
                return@pickerThread
            }
            if (PanelState.settingsOpen) PanelState.updateDraft { it.copy(outputRoot = picked) }
            // 当前这张盘的元数据留在旧根里：先搬一份到保险副本，换根后照样认得出这是哪张专辑。
            // 不这么做的话，面板会立刻变成「未知艺术家 / 未知专辑」，抓轨也按未知命名。
            PanelState.disc?.discId?.takeIf { it.isNotBlank() }?.let { id ->
                runCatching {
                    if (oldRoot != null && oldRoot != java.nio.file.Path.of(picked)) {
                        com.saltrip.rip.album.DiscCache.mirrorToVault(oldRoot, id)
                    }
                }.onFailure { Diag.log("root change: 迁移元数据缓存失败", it) }
            }
            // 名字立刻按新根重算（Discs 会带上内存/保险副本里的歌手与专辑）
            runCatching {
                com.saltrip.rip.Discs.sync(SettingsStore.load().driveLetter, quiet = false)
            }.onFailure { Diag.log("root change: 面板刷新失败", it) }
            toast(Strings.fmt("输出目录已改为：%s", picked), ToastType.Success)
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

    /**
     * 打开系统文件选择框。
     *
     * [ext] 必须是**每个扩展名一个参数**（`"jpg", "jpeg", "png"`）：
     * `FileNameExtensionFilter` 收的是 `vararg`，早先误传一整串 `"jpg;jpeg;png;webp"`
     * 会被当成一个扩展名，于是列表里一个文件都不显示（只能切「所有文件」才看得见）。
     */
    private fun pickFile(initial: String, title: String, desc: String, vararg ext: String): String? {
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
                    // 主过滤器只放需要的类型；再补一个「所有文件」备选，
                    // 免得遇到改名奇怪的图片（.jfif / .avif）时又只能去翻下拉框。
                    chooser.fileFilter = javax.swing.filechooser.FileNameExtensionFilter(desc, *ext)
                    chooser.addChoosableFileFilter(
                        javax.swing.filechooser.FileNameExtensionFilter("所有文件", "*")
                    )
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
                PanelState.set(lines.first(), lines.drop(1).joinToString("\n").ifBlank { Strings.message("文件自洽，可以直接入库") })
                lines.forEach { Diag.log("checkOutput: $it") }
                toast(report.summary(), if (report.ok) ToastType.Success else ToastType.Warning)
            } catch (e: Throwable) {
                Diag.log("checkOutput failed", e)
                toast(Strings.fmt("校验失败: %s", e.message), ToastType.Error)
            }
        }
    }

    /** 清空 MusicBrainz 磁盘缓存（改了 DiscID 相关设置、或想强制刷新元数据时用）。 */
    @JvmStatic
    fun clearMetadataCache() {
        exec.execute {
            val n = com.saltrip.rip.meta.MbCache.clear()
            toast(Strings.fmt("已清除 %s 个元数据缓存文件", n), ToastType.Success)
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
        persist(RipSettings(), "已恢复默认设置")
    }

    /** 供测试/诊断：当前设置的一行摘要。 */
    fun describeSettings(s: RipSettings = SettingsStore.load()): String =
        "root=${s.effectiveRoot} folder=${s.folderTemplate} file=${s.fileTemplate} " +
            "secure=${s.secureMode} offset=${s.offsetSamples} meta=${s.fetchMetadata}"
}
