package com.doggybear233.saltaudiocopy

import com.doggybear233.saltaudiocopy.album.AlbumRipper
import com.doggybear233.saltaudiocopy.album.DiscTest
import com.doggybear233.saltaudiocopy.album.DriveSelect
import com.doggybear233.saltaudiocopy.album.AlbumJsonWriter
import com.doggybear233.saltaudiocopy.meta.DiscId
import com.doggybear233.saltaudiocopy.meta.MetadataService
import com.doggybear233.saltaudiocopy.meta.NetCheck
import com.doggybear233.saltaudiocopy.rip.RipEngine
import com.doggybear233.saltaudiocopy.rip.RipProgress
import com.doggybear233.saltaudiocopy.scsi.CdDevice
import com.doggybear233.saltaudiocopy.scsi.Win32
import com.doggybear233.saltaudiocopy.settings.RipSettings
import com.doggybear233.saltaudiocopy.settings.SettingsStore
import com.doggybear233.saltaudiocopy.ui.ManualDraft
import com.doggybear233.saltaudiocopy.ui.Notify
import com.doggybear233.saltaudiocopy.ui.NumericDraft
import com.doggybear233.saltaudiocopy.ui.OptionText
import com.doggybear233.saltaudiocopy.ui.PanelFormat
import com.doggybear233.saltaudiocopy.ui.PanelState
import com.doggybear233.saltaudiocopy.ui.SettingsSpec
import com.doggybear233.saltaudiocopy.ui.Strings
import com.xuncorp.spw.workshop.api.WorkshopApi
import com.xuncorp.spw.workshop.api.WorkshopApi.Ui.ToastType
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 配置页 button 的入口（`preference_config.json` 里 on_click 指向这里的静态无参方法）。
 * 全部方法必须 public static 无参。
 */
object ConfigActions {

    /** 「恢复默认设置」的确认窗口：超过这段时间再点，视为重新开始确认。 */
    private const val RESET_CONFIRM_WINDOW_MS = 10_000L

    private fun newWorker(): ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "saltac-worker").apply { isDaemon = true }
    }

    /**
     * 执行器是可替换的：卸载插件时 [shutdown] 会关掉它，而宿主可能在同一进程里再次启用
     * （改完设置重启插件、或切回页面重新装载）。原先它是 `val`，关掉后就永久是 shutdown 状态，
     * 之后每个按钮都抛 `RejectedExecutionException`。
     */
    @Volatile
    private var exec = newWorker()

    @Volatile
    private var closed = false

    /** 取当前可用的执行器；[closed] 为真时重建一条。 */
    private fun worker(): ExecutorService {
        if (!closed) return exec
        synchronized(this) {
            if (closed) {
                exec = newWorker()
                closed = false
                Diag.log("worker recreated after shutdown")
            }
        }
        return exec
    }

    /** 每次用户动作开始前复位「取消 / 暂停」，避免上一次取消把这一次立刻打断。 */
    /** 仅供测试：验证「新一次操作会换一批网络 scope」。 */
    internal fun beginRunForTest() = beginRun()

    private fun beginRun() {
        // 抓轨中再点别的入口时，先到的 beginRun 不能把用户刚点的取消/暂停清掉：
        // 入口自己的 busy 检查会挡住重复启动，这里只需保持现有状态（R4-04）。
        if (busy) return
        userCancel = false
        PanelState.paused = false
        PanelState.cancelArmed = false
        // 上一次抓轨点过取消会 cancel 掉那一批的网络 scope；新一次抓轨要能用网络，
        // 必须在这里换一批。否则「取消过一次之后，封面/元数据永远查不回来」。
        runCatching { com.doggybear233.saltaudiocopy.meta.Http.beginScope() }
    }

    /** 光驱里的盘被弹出/拔线：让正在跑的抓轨尽快收尾；不走「二次确认取消」那套。 */
    @JvmStatic
    internal fun abortForEject() {
        userCancel = true
        PanelState.paused = false
        Diag.log("disc ejected during run: abort requested")
    }

    private val cancelFlag = AtomicBoolean(false)

    /**
     * 抓轨是否在进行。唯一存储是 [PanelState.busy]。
     *
     * 早先这里另有一个 `@Volatile var busy`，与面板各记一份：UI 用面板那份显隐按钮，
     * 入口用这份判重入。两边一旦不同步，就会同时出现「抓轨中还能再点开始」和
     * 「结束后按钮不回来」两种相反的现象——因为修一边的漏写就落到另一边的症状上。
     */
    private var busy: Boolean
        get() = PanelState.busy
        set(value) {
            PanelState.busy = value
        }

    /** 暂停标志：同样只留 [PanelState.paused] 一份，[gate] 与 UI 读同一处。 */
    private var paused: Boolean
        get() = PanelState.paused
        set(value) {
            PanelState.paused = value
        }

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
     * 提示分流：例行成功消息走坞上沿的状态条（[com.doggybear233.saltaudiocopy.ui.Notify]），
     * 警告/失败仍用宿主 toast——宿主 toast 整宽贴窗口底，坞也在底部，
     * 用它说「扫描完成」这类话必然挡住进度条；但坞被折叠时它是唯一可见的通道。
     */
    private fun toast(msg: String, type: ToastType = ToastType.Success) {
        when (type) {
            ToastType.Success -> com.doggybear233.saltaudiocopy.ui.Notify.info(msg)
            ToastType.Warning -> com.doggybear233.saltaudiocopy.ui.Notify.warn(msg)
            else -> com.doggybear233.saltaudiocopy.ui.Notify.fail(msg)
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
     * 从设备路径取盘符：`\\.\F:` → `F`。
     *
     * 早先写的是 `path.trimEnd('\\').lastOrNull()`：对 `\\.\F:` 取到的是 `':'`，
     * 于是所有光驱 chip 都显示 `:`、点第二台会写进 `driveLetter=":"`，
     * 之后 normalize 又把它当空串回退到第一台——显示、设置、实际抓轨三处不一致。
     */
    /**
     * 保存手填元数据前判断「盘是不是换过了」。
     *
     * 任一为空就放行（旧草稿/测试没有指纹）；都有且不等才拦——那时写下去就是错盘的歌名。
     */
    internal fun discChanged(draftFingerprint: String, nowFingerprint: String): Boolean =
        draftFingerprint.isNotBlank() && nowFingerprint.isNotBlank() && draftFingerprint != nowFingerprint

    /**
     * 可播放扇区数：只把**音轨**算进去。
     *
     * 旧代码用「lead-out − 首轨起点」，混合模式盘（第 1 轨是数据轨）会把数据区也算成音频，
     * 时长虚高；对纯音频盘两者一致。
     */
    internal fun playableSectors(toc: com.doggybear233.saltaudiocopy.scsi.DiscToc): Int =
        toc.tracks.indices.sumOf { i -> if (toc.tracks[i].isAudio) toc.sectorsOf(i).second else 0 }

    internal fun driveLetterOf(path: String): String {
        val tail = path.trim().removePrefix("\\\\.\\").trimEnd('\\')
        val head = tail.substringBefore(':')
        return if (head.length == 1 && head[0].isLetter()) head.uppercase() else ""
    }

    /**
     * 在工作线程里先重读一次盘，再执行 [block]。
     *
     * 配置页按钮是在宿主 UI 线程被反射调用的；`freshScan()` 会打开设备并读 TOC，
     * 空盘/驱动挂起时能阻塞十几秒，放在入口里直接调用就是「点一下界面冻住」。
     */
    private fun onWorkerWithScan(block: (com.doggybear233.saltaudiocopy.rip.ScanResult) -> Unit) {
        worker().execute {
            val scan = freshScan() ?: return@execute
            block(scan)
        }
    }

    /** 插件卸载时调用：让抓轨停下来，并把两条工作线程收掉，避免卸载后还在写盘。 */
    internal fun shutdown() {
        userCancel = true
        cancelFlag.set(true)
        runCatching { exec.shutdownNow() }
        closed = true
        Diag.log("ConfigActions shutdown")
    }

    /** 测试缝：确认 [beginRun] 会把上一次的取消/暂停状态清掉。 */
    internal fun resetStateForTest(): Boolean {
        userCancel = true
        PanelState.paused = true
        beginRun()
        return !userCancel && !PanelState.paused
    }

    /** 测试缝：卸载后再取执行器必须能拿到可用的新队列（不能是 shutdown 过的那个）。 */
    internal fun workerAfterShutdownForTest(): ExecutorService {
        shutdown()
        return worker()
    }

    /**
     * 每次操作前重读一次盘。
     *
     * 缓存里的 [com.doggybear233.saltaudiocopy.rip.ScanResult] 可能对应上一张盘（用户换盘后不点扫描），
     * 用它查元数据/命名就会把新盘的音轨写成旧盘的曲名和路径。这里统一走 [com.doggybear233.saltaudiocopy.Discs.sync]：
     * 指纹变了就替换缓存并清掉面板旧信息；没有任何盘就返回 null 并给出人话提示。
     */
    private fun freshScan(): com.doggybear233.saltaudiocopy.rip.ScanResult? {
        val settings = runCatching { SettingsStore.load(force = true) }.getOrNull()
        return runCatching { com.doggybear233.saltaudiocopy.Discs.sync(settings?.driveLetter.orEmpty()) }
            .onFailure {
                Diag.log("freshScan failed", it)
                // 盘读不到就把面板上的盘摘要清掉：「抓整盘 / 查元数据」会随即变灰，
                // 用户不会再点到一个注定失败的按钮（反馈 4）。
                runCatching { PanelState.showDisc(null) }
                toast(com.doggybear233.saltaudiocopy.scsi.ScsiMessages.human(it), ToastType.Warning)
            }
            .getOrNull()
    }

    /** 扫描所有可访问光驱，报告型号、轨数与 DiscID。 */
    @JvmStatic
    fun scanDrives() {
        // 配置页按钮在宿主 UI 线程上被反射调用，任何 SCSI I/O 都必须挪到工作线程，
        // 否则 INQUIRY / READ TOC 期间界面会整段卡死（用户看到的就是"死机"）。
        toast(Strings.message("正在扫描光驱，请稍候…"), ToastType.Success)
        worker().execute {
            try {
                val drives = CdDevice.scanDrives()
                if (drives.isEmpty()) {
                    toast(Strings.message("未发现可用光驱（没有光驱或驱动器内无盘）"), ToastType.Warning)
                    Diag.log("scanDrives: none")
                    return@execute
                }
                val sb = StringBuilder()
                val chips = ArrayList<PanelState.DriveChip>()
                val found = ArrayList<Pair<String, com.doggybear233.saltaudiocopy.rip.ScanResult>>()
                var failed = 0
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
                        sb.append("，${r.toc.tracks.size} 轨 / $kind$ids")
                        val letter = driveLetterOf(path)
                        chips += PanelState.DriveChip(
                            letter = letter,
                            label = "$letter: ${r.toc.tracks.size} 轨",
                            audio = r.toc.isAudioCd
                        )
                        found += letter to r
                    }.onFailure {
                        failed++
                        sb.append("，读取失败: ${it.message}")
                    }
                    sb.append("\n")
                }
                PanelState.drives = chips
                // 多光驱：先选「要显示的那一台」，最后只发布一次。
                // 原先每读到一张盘就 publish 一次，于是最后一台（常常是空盘或数据盘）
                // 会把前面那台音乐 CD 的信息从面板上覆盖掉，用户以为扫错了盘。
                // 盘符语义统一走 DriveSelect：设置里可能存 `F`、`f:`、`\\.\F:`，
                // 以前这里直接 uppercase 后与 chip 的裸字母比较，配成 `F:` 就永远选不中。
                val scans = found.map { it.second }
                // 兜底顺序与 DiscWatcher 必须完全一致，否则「扫描」选中的音乐 CD
                // 会在两秒后被轮询换成第一台数据盘（同一台机器两种结论）。
                val chosenScan = DriveSelect.pick(scans, SettingsStore.load(true).driveLetter)
                val chosen = chosenScan?.let { s -> found.firstOrNull { it.second === s } }
                PanelState.selectedDrive = chosen?.first ?: chips.firstOrNull()?.letter ?: ""
                chosen?.second?.let { publishScanned(it) }
                val text = sb.toString().trim()
                val nothingUsable = found.isEmpty() && failed > 0
                PanelState.set(
                    statusText = if (nothingUsable) {
                        Strings.message("扫描完成，但没有一台光驱能读出光盘信息")
                    } else {
                        text.lineSequence().firstOrNull() ?: Strings.message("已扫描")
                    },
                    detailText = if (nothingUsable) text else Strings.message("点「抓整盘」按当前设置输出；「查元数据」只联网不写文件")
                )
                // 全部失败还报 Success/「已扫描」会让人以为只是没放盘，实际是盘符或驱动出错（R4-08）
                toast(text, if (nothingUsable) ToastType.Warning else ToastType.Success)
                // 扫描到音频 CD 就顺手查元数据：用户不必再点一次（反馈 3）。
                val disc = ScanResultHolder.get()
                if (disc != null && disc.toc.isAudioCd) {
                    runCatching {
                        refreshMetadataAuto(
                            com.doggybear233.saltaudiocopy.scsi.DiscFingerprint.of(disc.toc),
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
     * 把「扫描到的那张盘」发布到面板。
     *
     * 单独提出来是因为多光驱扫描必须**只发布一台**：原先在循环里每读到一张就发布，
     * 最后一台的盘信息会覆盖前面那台音乐 CD。
     */
    private fun publishScanned(r: com.doggybear233.saltaudiocopy.rip.ScanResult) {
        // 面板预览要用设置算路径：这里存一份内存快照，组合期就不用再读宿主配置
        runCatching { SettingsStore.load() }.getOrNull()?.let { PanelState.settingsSnapshot = it }
        // 让轮询以这张盘为基准，避免它立刻再判一次「换盘」。
        runCatching { DiscWatcher.seed(r.toc) }
        ScanResultHolder.put(r)
        val playable = playableSectors(r.toc)
        val discId = if (r.toc.isAudioCd) {
            runCatching { DiscId.musicBrainz(r.toc) }.getOrDefault("")
        } else ""
        // 之前抓过这张盘就直接显示专辑信息，不必等联网查询。
        val cached = if (discId.isNotBlank()) {
            runCatching {
                com.doggybear233.saltaudiocopy.album.DiscCache.load(
                    SettingsStore.load().effectiveRoot, discId
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
        if (discId.isNotBlank()) cached?.let { PanelState.rememberMeta(discId, it) }
        PanelState.discCover = cached?.cover
    }

    /**
     * 导出当前光盘的可编辑元数据模板：`<输出根>/<DiscID>.album.json`。
     *
     * 流程：扫描 → 点这个 → 用记事本改 → 再抓整盘（抓轨时会自动加载并覆盖网络元数据）。
     */
    @JvmStatic
    fun exportAlbumJson() {
        runCatching { com.doggybear233.saltaudiocopy.meta.Http.beginScope() }
        onWorkerWithScan { scan ->
            if (!scan.toc.isAudioCd) {
                toast(Strings.message("当前盘不是音频 CD"), ToastType.Warning)
                return@onWorkerWithScan
            }
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
    private fun trackNoFrom(sectorsDone: Int, toc: com.doggybear233.saltaudiocopy.scsi.DiscToc): Int {
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
        val text = com.doggybear233.saltaudiocopy.ui.PanelMount.status()
        Diag.log("panel mount status: $text")
        PanelState.set(if (com.doggybear233.saltaudiocopy.ui.PanelMount.mounted) Strings.message("面板已挂载") else "面板未挂载", text)
        toast(text.lineSequence().first(), ToastType.Success)
    }

    @JvmStatic
    fun showCdText() {
        val scan = ScanResultHolder.get() ?: run {
            toast(Strings.message("请先点「扫描驱动器」"), ToastType.Warning)
            return
        }
        worker().execute {
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
        worker().execute {
            try {
                val table = CdDevice(scan.path).use { dev ->
                    val gaps = com.doggybear233.saltaudiocopy.album.DiscAnalysis.gapsFor(scan.toc, dev) { Diag.log(it) }
                    com.doggybear233.saltaudiocopy.album.DiscAnalysis.describe(gaps)
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
        if (busy) {
            toast(Strings.message("抓轨进行中，等这次抓轨结束再导出诊断"), ToastType.Warning)
            return
        }
        worker().execute {
            try {
                val st = SettingsStore.load(force = true)
                val root = st.effectiveRoot.resolve("Salt Audio Copy")
                java.nio.file.Files.createDirectories(root)
                val ts = java.time.LocalDateTime.now().toString().replace(':', '-').take(19)
                val out = root.resolve("diagnostics-$ts.txt")
                val sb = StringBuilder()
                sb.append("Salt Audio Copy 诊断信息\r\n")
                sb.append("时间: ").append(java.time.LocalDateTime.now()).append("\r\n")
                sb.append("插件: ").append(SaltAudioCopyPlugin::class.java.`package`?.implementationVersion ?: "-")
                    .append("  (id=com.doggybear233.saltaudiocopy)\r\n")
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
        worker().execute {
            try {
                val scan = ScanResultHolder.get() ?: CdDevice.scanDrives().firstOrNull()?.let {
                    com.doggybear233.saltaudiocopy.rip.ScanResult(it.first, it.second, CdDevice(it.first).use { d -> d.readToc() })
                }
                if (scan == null) {
                    toast(Strings.message("没有找到光驱"), ToastType.Warning)
                    return@execute
                }
                val sb = StringBuilder()
                var available = 0
                for (cmd in com.doggybear233.saltaudiocopy.scsi.ReadCommand.entries) {
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
                com.doggybear233.saltaudiocopy.scsi.ReadCommandPreference.apply("auto")
                val table = sb.toString().trimEnd()
                Diag.log("read-command probe: " + table.replace("\n", " | "))
                PanelState.set(Strings.fmt("读命令探测完成（%s/%s 可用）", available, com.doggybear233.saltaudiocopy.scsi.ReadCommand.entries.size), table)
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
        worker.name = "saltac-health"
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
        watchdog.name = "saltac-health-watchdog"
        watchdog.start()
    }

    /** 只查询元数据，落地目录与文件不动；用于确认 DiscID 是否命中。 */
    @JvmStatic
    fun previewPlan() {
        toast(Strings.message("正在计算抓轨计划（不写盘）…"), ToastType.Success)
        onWorkerWithScan { scan ->
            try {
                val settings = SettingsStore.load()
                val notes = ArrayList<String>()
                val album = if (settings.fetchMetadata) {
                    runCatching { MetadataService.lookup(scan.toc, settings, notes) }.getOrNull()
                } else null
                val plan = com.doggybear233.saltaudiocopy.album.RipPlan.build(scan.toc, settings, album)
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
                    Strings.message("这张盘有你填过或选过的信息；想重新联网查，点面板上的元数据按钮")
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
        worker().execute {
            try {
                val scan = freshScan() ?: return@execute
                val fp = com.doggybear233.saltaudiocopy.scsi.DiscFingerprint.of(scan.toc)
                if (!autoResultIsFresh(expectedFingerprint, fp)) {
                    Diag.log("stale lookup dropped: 想要 $expectedFingerprint，现在是 $fp")
                    return@execute
                }
                val cdtext = runCatching {
                    com.doggybear233.saltaudiocopy.scsi.CdDevice(scan.path).use { it.readCdText() }
                }.getOrNull()
                val result = com.doggybear233.saltaudiocopy.meta.MetadataCandidates.load(scan.toc, settings, cdtext)
                Diag.log("candidates:\n" + result.notes.joinToString("\n"))

                val playable = playableSectors(scan.toc)
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
                        PanelState.Candidate(
                            index = it.index,
                            label = it.label(),
                            source = it.source,
                            cover = it.cover,
                            releaseId = it.releaseId,
                            albumArtist = it.albumArtist,
                            albumTitle = it.albumTitle
                        )
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
            } catch (e: com.doggybear233.saltaudiocopy.meta.RequestCancelled) {
                // 用户取消：不是失败，不要弹「查询失败」，也不要把镜像记成挂掉。
                Diag.log("lookup cancelled by user")
                PanelState.set(
                    Strings.message("已取消查询"),
                    Strings.message("需要时可以再点一次刷新元数据")
                )
            } catch (e: Throwable) {
                Diag.log("lookupMetadata failed", e)
                if (interactive) toast(Strings.message("查询失败：") + com.doggybear233.saltaudiocopy.scsi.ScsiMessages.human(e), ToastType.Error)
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
    /**
     * 把面板候选转成查询用的候选。
     *
     * 只搬结构化字段，**不解析 [PanelState.Candidate.label]**：名字里带「 · 」或全角「（」
     * 时切分会截错，而错的名字会被写进 album.json 长期复用。
     */
    internal fun candidateFor(picked: PanelState.Candidate) =
        com.doggybear233.saltaudiocopy.meta.MetadataCandidates.Candidate(
            index = picked.index,
            albumArtist = picked.albumArtist,
            albumTitle = picked.albumTitle,
            year = null,
            trackCount = 0,
            releaseId = picked.releaseId,
            source = picked.source
        )

    fun selectCandidate(index: Int) {
        // 先不动高亮：取数据失败时不能让人以为已经换版本了
        val picked = PanelState.candidates.getOrNull(index) ?: return
        val previous = PanelState.selectedCandidate
        PanelState.set(Strings.fmt("正在获取「%s」…", picked.label), Strings.message("取到后才会切换到这个版本"))
        toast(Strings.fmt("正在获取「%s」…", picked.label), ToastType.Success)
        worker().execute {
            try {
                // 选候选会去 Cover Art Archive / MusicBrainz 取图与详情：
                // 上次取消留下的 scope 已经作废，这里换一批新的。
                runCatching { com.doggybear233.saltaudiocopy.meta.Http.beginScope() }
                val scan = freshScan() ?: return@execute
                val settings = SettingsStore.load()
                val cdtext = runCatching {
                    com.doggybear233.saltaudiocopy.scsi.CdDevice(scan.path).use { it.readCdText() }
                }.getOrNull()
                val cand = candidateFor(picked)
                val notes = ArrayList<String>()
                val album = com.doggybear233.saltaudiocopy.meta.MetadataCandidates.materialize(scan.toc, cand, settings, cdtext, notes)
                if (album == null) {
                    // 失败：高亮保持原样，并在坞内说明（toast 4 秒就没了）
                    PanelState.commitSelection(previous)
                    PanelState.set(
                        Strings.message("没能取到该版本的完整信息，仍用原来选中的版本"),
                        Strings.message("换一个候选，或直接手动填写元数据")
                    )
                    toast(Strings.message("没能取到该版本的完整信息，请换一个候选"), ToastType.Warning)
                    return@execute
                }
                // 选中即落盘成 album.json：下次抓同一张盘直接沿用，不必再选一次。
                val json = com.doggybear233.saltaudiocopy.album.AlbumJsonWriter.build(album, scan.toc)
                val id = com.doggybear233.saltaudiocopy.meta.DiscId.musicBrainz(scan.toc)
                val target = com.doggybear233.saltaudiocopy.album.DiscCache.path(settings.effectiveRoot, id)
                java.nio.file.Files.createDirectories(target.parent)
                java.nio.file.Files.writeString(target, json, Charsets.UTF_8)
                // 封面也落盘：album.json 里不存图，下次从缓存读到时靠的是 JSON 旁边的 sidecar。
                // 只写 JSON 会让「选了个带封面的版本」在下次抓轨时变回无封面（F-481）。
                runCatching {
                    com.doggybear233.saltaudiocopy.meta.MetaOverride
                        .writeCoverSibling(target, album.cover, album.coverMime)
                }.onFailure { Diag.log("selectCandidate: 写封面失败", it) }
                PanelState.discCover = album.cover
                PanelState.showDisc(
                    PanelState.DiscInfo(
                        tracks = album.tracks.size,
                        artist = album.albumArtist.orEmpty(),
                        album = album.albumTitle.orEmpty(),
                        lengthText = PanelFormat.duration(playableSectors(scan.toc) * 1000L / 75L),
                        discId = album.discId,
                        audio = scan.toc.isAudioCd
                    )
                )
                PanelState.commitSelection(index)
                PanelState.set(Strings.fmt("已选用：%s - %s", album.albumArtist, album.albumTitle), notes.takeLast(1).joinToString())
                toast(Strings.fmt("已选用「%s - %s」", album.albumArtist, album.albumTitle), ToastType.Success)
            } catch (e: Throwable) {
                Diag.log("selectCandidate failed", e)
                PanelState.commitSelection(previous)
                PanelState.set(
                    Strings.message("没能取到该版本的完整信息，仍用原来选中的版本"),
                    com.doggybear233.saltaudiocopy.scsi.ScsiMessages.human(e)
                )
                toast(Strings.message("获取失败：") + com.doggybear233.saltaudiocopy.scsi.ScsiMessages.human(e), ToastType.Error)
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
        // 抓轨中打开手填面板会把进度条与状态行整块顶掉（面板同一时刻只显示一个区块），
        // 用户看不到进度，容易以为卡死。这里先挡住，等这次抓轨结束再进来。
        if (busy) {
            toast(Strings.message("抓轨进行中，等这次抓轨结束再改元数据"), ToastType.Warning)
            return
        }
        PanelState.manualOpen = true
        PanelState.settingsOpen = false
        PanelState.set(Strings.message("正在准备手动填写…"), "读取曲目数与本机缓存")
        worker().execute {
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
                ).copy(
                    fingerprint = runCatching {
                        com.doggybear233.saltaudiocopy.scsi.DiscFingerprint.of(scan.toc)
                    }.getOrDefault("")
                )
                // 盘上有 CD-TEXT 时先塞进去：用户经常只需要补一两个字段。
                var prefilled = false
                if (draft.tracks.all { it.title.isBlank() }) {
                    val cdtext = runCatching {
                        com.doggybear233.saltaudiocopy.scsi.CdDevice(scan.path).use { it.readCdText() }
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
                toast(Strings.message("打开手动填写失败：") + com.doggybear233.saltaudiocopy.scsi.ScsiMessages.human(e), ToastType.Error)
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
        draft: com.doggybear233.saltaudiocopy.ui.ManualDraft,
        before: com.doggybear233.saltaudiocopy.ui.PanelState.DiscInfo?
    ): com.doggybear233.saltaudiocopy.ui.PanelState.DiscInfo {
        val album = draft.toAlbumMeta(before?.discId.orEmpty())
        return com.doggybear233.saltaudiocopy.ui.PanelState.DiscInfo(
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
     * 再把写文件丢到 [exec]（与抓轨同一条队列，天然不会两边同时读光驱）。旧实现在工作线程里先 `freshScan()`（读 TOC，
     * 可能等上十几秒）且排在查询后面，于是"点保存没反应"。
     */
    @JvmStatic
    fun saveManualMeta() {
        // 抓轨正在读同一台光驱：这时候再插一次 TOC/读盘会跟抓轨抢设备，
        // 真机上表现为「抓轨莫名慢一截、偶尔读失败」。
        if (PanelState.busy) {
            PanelState.set(
                Strings.message("抓轨进行中，暂时不能保存元数据"),
                Strings.message("等这次抓轨结束再保存；草稿还在，不会丢")
            )
            Notify.warn(Strings.message("抓轨进行中：等抓轨结束再保存元数据"))
            return
        }
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
        // 「已保存」必须等文件真写进去再说：读不到盘时这里会失败，先说成功等于骗用户。
        PanelState.set(
            Strings.message("正在保存元数据…"),
            Strings.fmt("%s — %s（%s 轨）", album.albumArtist, album.albumTitle, album.tracks.size)
        )

        // 2) 落盘（独立线程，不跟自动查询抢队）
        worker().execute {
            try {
                // freshScan() 内部会 Discs.publish()：此刻 JSON 还没写，缓存是空的，
                // 面板会被重新刷成「未知艺术家」。所以扫描后立刻把乐观值再盖回去，
                // 等写完文件再用 publish 从缓存重建（那一次才是准的）。
                val scan = freshScan() ?: run {
                    val why = Strings.message("读不到光盘，元数据没有写入")
                    PanelState.set(Strings.message("保存失败"), why)
                    Notify.fail(Strings.message("保存失败：") + why)
                    // 把草稿放回面板，用户换盘/放回盘后可以直接再点保存，不用重填
                    PanelState.openManual(draft)
                    return@execute
                }
                // 盘换过了：A 盘的歌名不能写进 B 盘的缓存。
                val nowFp = runCatching {
                    com.doggybear233.saltaudiocopy.scsi.DiscFingerprint.of(scan.toc)
                }.getOrDefault("")
                if (discChanged(draft.fingerprint, nowFp)) {
                    Diag.log("manual save aborted: disc changed (${draft.fingerprint} -> $nowFp)")
                    PanelState.set(
                        Strings.message("光盘已经换过了，这次没有保存"),
                        Strings.message("草稿还在：把原来那张盘放回来，再点一次保存")
                    )
                    Notify.warn(Strings.message("光盘换过了，元数据没有写入"))
                    PanelState.openManual(draft)
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
                // 只写 .saltaudiocopy 与保险副本，不再往音乐库根目录散落 <DiscID>.album.json：
                // 那个位置会把用户的音乐目录搞乱，而且删掉输出根之后就没人记得清。
                Diag.log("manual album.json saved: $cachePath")
                saveManualCover(cachePath, root, discId)
                PanelState.rememberMeta(discId, finalAlbum)
                // 保险副本：用户之后换输出根、甚至删掉输出目录，这张盘的信息都还在
                if (discId.isNotBlank()) com.doggybear233.saltaudiocopy.album.DiscCache.mirrorToVault(root, discId)

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
                PanelState.set(
                    Strings.message("已保存手动元数据"),
                    Strings.fmt("%s — %s（%s 轨）", finalAlbum.albumArtist, finalAlbum.albumTitle, finalAlbum.tracks.size)
                )
                Notify.info(Strings.message("已保存手动元数据，抓轨会用这些名字"))
                Diag.log("manual meta persisted: ${finalAlbum.tracks.size} tracks")
            } catch (e: Throwable) {
                Diag.log("saveManualMeta failed", e)
                val why = com.doggybear233.saltaudiocopy.scsi.ScsiMessages.human(e)
                PanelState.set(Strings.message("保存失败"), why)
                Notify.fail(Strings.message("保存失败：") + why)
                PanelState.openManual(draft)
            }
        }
    }

    /**
     * 弹文件/目录选择框的线程。
     *
     * 选择框是模态的，会一直占着线程直到用户点确定或取消。放进 [exec] 的话，
     * 一旦用户开着框发呆，后面所有任务（抓轨、查元数据）都被卡住；
     * 放进 [exec] 又会挡住保存。所以每次单开一条一次性线程。
     */
    private fun pickerThread(name: String, block: () -> Unit) {
        Thread(block, name).apply { isDaemon = true }.start()
    }

    /** 手填元数据时导入本地封面（没有网络元数据的盘常常也没有封面）。 */
    @JvmStatic
    fun chooseManualCover() {
        pickerThread("saltac-picker-cover") {
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
        worker().execute {
            try {
                val scan = freshScan() ?: return@execute
                val info = runCatching {
                    com.doggybear233.saltaudiocopy.scsi.CdDevice(scan.path).use { it.readCdText() }
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
                toast(Strings.message("读 CD-TEXT 失败：") + com.doggybear233.saltaudiocopy.scsi.ScsiMessages.human(e), ToastType.Error)
            }
        }
    }

    /**
     * 把导入的封面复制到 album.json 旁边（`<discId>.album.cover.jpg`），
     * 下次读缓存时 [com.doggybear233.saltaudiocopy.meta.MetaOverride.load] 会自动带上。
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
        com.doggybear233.saltaudiocopy.album.DiscCache.path(root, discId.ifBlank { "disc-$leadOutLba" })

    /**
     * 读已有的 album.json 缓存（读不到就当没有，不报错）。
     *
     * 先在当前输出根里找；找不到再看系统「音乐」文件夹——用户改过输出根之后，
     * 之前手填的元数据与封面还留在旧根下，不回落就会显示成「未知艺术家 / 未知专辑」。
     */
    internal fun readCachedAlbum(root: java.nio.file.Path, discId: String): com.doggybear233.saltaudiocopy.meta.AlbumMeta? {
        if (discId.isBlank()) return null
        // 输出根 → 保险副本 → 系统音乐文件夹（换过输出根也不丢手填信息）
        return com.doggybear233.saltaudiocopy.album.DiscCache.load(root, discId)
    }

    /** 抓取整张专辑：元数据 → 模板命名 → 逐轨双读校验 → 封面/CUE/日志。 */
    @JvmStatic
    fun ripAlbum() {
        beginRun()
        if (busy) {
            toast(Strings.message("已有抓轨任务在进行"), ToastType.Warning)
            return
        }
        PanelState.settingsOpen = false
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
        busy = true
        cancelFlag.set(false)
        paused = false
        userCancel = false
        PanelState.reRipArmed = false
        PanelState.begin("准备中…")
        toast(Strings.message("正在读取光盘信息…"), ToastType.Success)
        worker().execute {
            try {
                // 读盘放到工作线程：入口是宿主 UI 线程调用的，空盘/挂起时 freshScan 能阻塞十几秒。
                val scan = freshScan() ?: return@execute
                if (!scan.toc.isAudioCd) {
                    toast(Strings.message("这张盘不是音频 CD（数据盘不能抓轨）：请换一张音乐 CD"), ToastType.Warning)
                    return@execute
                }
                val settings = SettingsStore.load(force = true)
                val count = scan.toc.tracks.size
                PanelState.set(Strings.fmt("正在抓取 %s 轨…", count), "可用「取消」在当前块结束后停止，已抓部分保留")
                PanelState.verifyTimes =
                    if (settings.secureMode) com.doggybear233.saltaudiocopy.rip.SecureRip.policyFor(settings.errorRecovery, settings.maxRetries).identicalNeeded
                    else 1
                val modeTalk = if (settings.secureMode) Strings.message("双读校验") else Strings.message("快速模式")
                toast(Strings.fmt("开始抓取整张专辑（%s 轨，%s）…", count, modeTalk), ToastType.Success)
                var lastPct = -10
                var removedFiles = 0
                var keptPartials = 0
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
                    // 取消时清理半成品，但已经完整抓好的音轨是有效成果，不能连坐删掉。
                    // 另外：开着续抓时，当前这轨「抓到一半」的 WAV 恰恰是下次要接着写的那份，
                    // 删掉就等于把「断点续抓」的承诺作废（用户以为能接上，结果从头再来）。
                    val finished = result.tracks.filter { !it.skipped }
                        .map { it.file.toAbsolutePath().normalize() }.toSet()
                    val resumable = resumableOnCancel(result.createdFiles, settings.resume)
                    val removable = removableOnCancel(result.createdFiles, finished, resumable)
                    keptPartials = resumable.count { java.nio.file.Files.exists(it) }
                    removedFiles = deleteCreated(removable)
                    if (removedFiles > 0 || keptPartials > 0) {
                        Diag.log(
                            "cancelled: removed $removedFiles files from ${result.outDir} " +
                                "(kept ${finished.size} finished tracks, $keptPartials resumable partials)"
                        )
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
                    val doneCount = result.tracks.count { !it.skipped }
                    val tail = buildString {
                        append(
                            when {
                                doneCount > 0 && removedFiles > 0 ->
                                    Strings.fmt("已保留抓完的 %s 轨，清理了 %s 个半成品", doneCount, removedFiles)
                                doneCount > 0 -> Strings.fmt("已保留抓完的 %s 轨（文件在 %s）", doneCount, result.outDir)
                                removedFiles > 0 -> Strings.fmt("已删除本次生成的 %s 个文件", removedFiles)
                                else -> Strings.fmt("文件保留在 %s", result.outDir)
                            }
                        )
                        if (keptPartials > 0) {
                            append("；")
                            append(Strings.fmt("已保留 %s 轨可续抓的半截文件（再抓一次会接着写）", keptPartials))
                        }
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
            } catch (e: com.doggybear233.saltaudiocopy.scsi.ScsiException) {
                Diag.log("ripAlbum scsi failure", e)
                PanelState.set(Strings.message("抓轨失败"), com.doggybear233.saltaudiocopy.scsi.ScsiMessages.human(e))
                toast(Strings.message("抓轨失败：") + com.doggybear233.saltaudiocopy.scsi.ScsiMessages.human(e), ToastType.Error)
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
        beginRun()
        if (busy) {
            toast(Strings.message("已有任务在进行"), ToastType.Warning)
            return
        }
        // 从设置页发起：立刻回到坞视图，否则进度/取消都被设置页盖住
        PanelState.settingsOpen = false
        busy = true
        cancelFlag.set(false)
        PanelState.begin("只测不写（Test only）")
        toast(Strings.message("开始测试读取，不会写任何文件…"), ToastType.Success)
        worker().execute {
            try {
                val scan = freshScan() ?: return@execute
                if (!scan.toc.isAudioCd) {
                    toast(Strings.message("这不是音频 CD"), ToastType.Warning)
                    return@execute
                }
                val settings = SettingsStore.load(true)
                val report = DiscTest.run(
                    scan, scan.toc, { gate() },
                    { stage -> PanelState.stage = stage; Diag.log("test-only: $stage") },
                    verify = settings.secureMode,
                    maxRetries = settings.maxRetries,
                    retryDelayMs = settings.retryDelayMs,
                    useC2 = settings.useC2,
                    defeatCache = settings.defeatCache,
                    gapMethod = settings.gapMethod,
                    errorRecovery = settings.errorRecovery,
                    gapMode = settings.gapMode,
                    speedKbps = settings.driveSpeed
                )
                val rows = report.rows
                val text = DiscTest.summary(rows)
                Diag.log("test-only done: ${rows.size} tracks cancelled=${report.cancelled}")
                rows.forEach { Diag.log("test-only track=${it.track} sectors=${it.sectors} crc32=%08X errors=${it.errors}".format(it.crc32)) }
                if (report.cancelled) {
                    PanelState.set(Strings.message("测试已取消"), text)
                    toast(Strings.fmt("测试已取消：只测了 %s 轨，未写任何文件", rows.size), ToastType.Warning)
                } else {
                    PanelState.set(Strings.message("测试完成（未写盘）"), text)
                    toast(Strings.fmt("测试完成：%s 轨，未写任何文件", rows.size), ToastType.Success)
                }
            } catch (e: Throwable) {
                Diag.log("test-only failed", e)
                val why = com.doggybear233.saltaudiocopy.scsi.ScsiMessages.human(e)
                PanelState.set(Strings.message("测试失败"), why)
                toast(Strings.fmt("测试失败：%s", why), ToastType.Error)
            } finally {
                busy = false
            }
        }
    }

    @JvmStatic
    fun ripFirstTrack() {
        beginRun()
        if (busy) {
            toast(Strings.message("已有抓轨任务在进行"), ToastType.Warning)
            return
        }
        PanelState.settingsOpen = false
        busy = true
        cancelFlag.set(false)
        toast(Strings.message("开始抓取第 1 轨…"), ToastType.Success)
        worker().execute {
            try {
                val scan = freshScan() ?: return@execute
                if (!scan.toc.isAudioCd) {
                    PanelState.set(Strings.message("当前盘不是音频 CD"), Strings.message("数据盘没有音轨可抓"))
                    toast(Strings.message("当前盘不是音频 CD（数据盘不能抓轨）"), ToastType.Warning)
                    return@execute
                }
                val settings = SettingsStore.load(true)
                PanelState.begin(Strings.message("正在抓取第 1 轨…"))
                val dir = settings.effectiveRoot.resolve("disc-${scan.toc.leadOutLba}")
                val out = dir.resolve("01.wav")
                var lastReport = 0
                val result = RipEngine.ripTrack(
                    scan, 0, out, { gate() },
                    { p: RipProgress ->
                        // RipProgress → 面板用的 Progress（面板不依赖 rip 包）
                        PanelState.onProgress(
                            PanelState.Progress(
                                fraction = p.fraction,
                                speedX = p.speedX,
                                etaSec = if (p.etaMs < 0) -1 else (p.etaMs / 1000).toInt(),
                                trackNo = 1,
                                trackTotal = 1
                            )
                        )
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
            } catch (e: com.doggybear233.saltaudiocopy.scsi.ScsiException) {
                Diag.log("ripFirstTrack scsi failure", e)
                toast(Strings.message("读取失败：") + com.doggybear233.saltaudiocopy.scsi.ScsiMessages.human(e), ToastType.Error)
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
     * 并且会删除**本次运行新建**的文件（[com.doggybear233.saltaudiocopy.album.AlbumRipResult.createdFiles]），
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
        // 元数据查询可能正卡在一次重试退避或 Retry-After 里：不打断它，抓轨取消要等它睡完
        runCatching { com.doggybear233.saltaudiocopy.meta.Http.abort() }
        toast(Strings.message("正在取消…"), ToastType.Warning)
    }

    /**
     * 删除本次新建的文件，返回删掉的个数。失败只记日志。
     *
     * internal 是为了能单测：这是「取消后删文件」这类破坏性行为的唯一入口，
     * 以前没有测试，漏记或多记 createdFiles 都会删错东西或留垃圾。
     */
    /**
     * 取消时哪些文件算「可续抓的半截文件」。
     *
     * 判据刻意用「文件里已经有 PCM 数据」而不是「那一轨报了 cancelled」：
     * 抓轨被取消时 AlbumRipper 不会为中断的那轨产出 AlbumTrackResult，
     * 唯一能看出「这里有一份能接着写的 WAV」的就是文件本身。
     */
    internal fun resumableOnCancel(
        createdFiles: List<java.nio.file.Path>,
        resume: Boolean
    ): Set<java.nio.file.Path> {
        if (!resume) return emptySet()
        return createdFiles.filter { p ->
            p.fileName.toString().endsWith(".wav", ignoreCase = true) &&
                (com.doggybear233.saltaudiocopy.rip.WavInfo.read(p)?.dataBytes ?: 0L) > 0L
        }.map { it.toAbsolutePath().normalize() }.toSet()
    }

    /** 取消时要删的文件：既不是已抓完的轨，也不是可续抓的半截文件。 */
    internal fun removableOnCancel(
        createdFiles: List<java.nio.file.Path>,
        finished: Set<java.nio.file.Path>,
        resumable: Set<java.nio.file.Path>
    ): List<java.nio.file.Path> = createdFiles.filter { p ->
        val n = p.toAbsolutePath().normalize()
        n !in finished && n !in resumable
    }

    internal fun deleteCreated(files: List<java.nio.file.Path>): Int {
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
    internal fun persist(next: com.doggybear233.saltaudiocopy.settings.RipSettings, okMessage: String): Boolean {
        val ok = SettingsStore.save(next)
        // 宿主 API 在单测 classpath 上不存在：取枚举本身也要包在 runCatching 里，
        // 否则失败路径会抛 NoClassDefFoundError（成功的路径反而没事，很难发现）
        if (ok) {
            // 磁盘已经是 next：UI 的唯一真源（草稿/快照/测试模式）必须跟着走。
            // 否则恢复默认/恢复备份之后点「保存」会把旧草稿原样写回去（R4-02）。
            PanelState.settingsSnapshot = next
            PanelState.loadDraft(next)
            PanelState.testMode = next.testMode
            PanelState.draftDirty = false
            runCatching { toast(okMessage, ToastType.Success) }
        } else {
            PanelState.set(Strings.message("设置没能写回磁盘"), Strings.message("宿主配置不可用，改动只在本次运行有效；详见日志"))
            runCatching { toast(Strings.message("设置没能写回磁盘（详见日志）"), ToastType.Error) }
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
        PanelState.settingsSnapshot = s
        // 不要把「默认（留空）」固化成绝对路径：草稿要保持用户写的原样，
        // 面板上方那行「当前：…」负责显示真正生效的目录。
        // 有未保存改动时保留草稿：收起设置只是让开画面，不是放弃修改。
        if (!PanelState.draftDirty) PanelState.loadDraft(s)
        PanelState.settingsPage = SettingsSpec.DEFAULT_PAGE
        // 确认态绑定「一次设置会话」：重开设置必须是未武装状态（R4-05）
        PanelState.resetArmed = false
        PanelState.resetArmedAt = 0L
        PanelState.restoreArmed = false
        PanelState.restoreArmedAt = 0L
        PanelState.settingsOpen = true
    }

    /** 关闭设置区（不保存草稿）。 */
    /**
     * 只是把设置页收起来：草稿留着，下次打开还能接着改。
     *
     * 以前标题栏的「收起设置」和底部的「取消」都调 closeSettings()，
     * 用户点「收起」以为只是隐藏，结果改了一半的模板全没了。
     */
    @JvmStatic
    fun hideSettings() {
        PanelState.settingsOpen = false
        // 收起也算一次会话结束；否则二次确认会跨收起存活（R4-05）
        PanelState.resetArmed = false
        PanelState.resetArmedAt = 0L
        PanelState.restoreArmed = false
        PanelState.restoreArmedAt = 0L
    }

    @JvmStatic
    fun closeSettings() {
        PanelState.settingsOpen = false
        // 「取消」= 放弃草稿；下次打开重新从磁盘读
        PanelState.draftDirty = false
        // 确认态不能跟着用户跑：关掉设置页后重新进，应该是「第一次点」而不是直接执行
        PanelState.resetArmed = false
        PanelState.resetArmedAt = 0L
        PanelState.restoreArmed = false
        PanelState.restoreArmedAt = 0L
    }

    /** 把设置草稿整份写回磁盘。 */
    @JvmStatic
    fun savePanelSettings() {
        if (busy) {
            // 抓轨用的是启动那一刻的设置快照，改了也不会影响本次，说清楚比让它「看起来生效」好
            PanelState.set(
                Strings.message("抓轨进行中，暂时不能改设置"),
                Strings.message("等这次抓轨结束再保存；本次仍用开始时的设置")
            )
            toast(Strings.message("抓轨进行中，暂时不能改设置"), ToastType.Warning)
            return
        }
        val d = PanelState.draft.copy(
            folderTemplate = PanelState.draft.folderTemplate.ifBlank { "%albumartist%/%albumtitle%" },
            fileTemplate = PanelState.draft.fileTemplate.ifBlank { "%tracknr2% %title%" },
            coverFileName = PanelState.draft.coverFileName.ifBlank { "cover" },
            multiDiscFolder = PanelState.draft.multiDiscFolder.ifBlank { "CD%discnumber%" }
        )
        // 数字框里留着解析不了的原文（"-"、空串）时不要静默当默认值：说清哪项没生效
        val badNumeric = SettingsSpec.numericKeys.filter { k ->
            val raw = NumericDraft.get(k)
            raw != null && raw.trim().toIntOrNull() == null
        }
        if (badNumeric.isNotEmpty()) {
            val names = badNumeric.joinToString("、") { k -> OptionText.label(k, k) }
            Notify.warn(Strings.fmt("这些项不是整数，已保留原值：%s", names))
        }
        NumericDraft.clearAll()
        PanelState.draftDirty = false
        val saved = SettingsStore.save(d)
        PanelState.testMode = d.testMode
        PanelState.settingsSnapshot = d
        PanelState.settingsOpen = false
        PanelState.refreshPreview()
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
    internal fun hasLocalNames(meta: com.doggybear233.saltaudiocopy.meta.AlbumMeta?): Boolean =
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
        // 传进来的是光驱 chip 的 letter；也接受 `F:` / `\\.\F:` 这种写法，
        // 统一用 DriveSelect 归一化后再校验，避免两套盘符语义各判一套。
        val upper = DriveSelect.normalize(letter).uppercase()
        if (upper.length != 1 || upper[0] !in 'A'..'Z') {
            toast(Strings.fmt("光驱标识无效：%s", letter), ToastType.Warning)
            return
        }
        val s = SettingsStore.load(true)
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
        worker().execute {
            try {
                val report = EnvProbe.collect()
                Diag.log("env:\n" + EnvProbe.details(report))
                val line = EnvProbe.summary(report)
                PanelState.set(
                    Strings.message("环境自检完成"),
                    EnvProbe.details(report).trim()
                        .replace("\r\n", "；").replace("\n", "；").replace("\r", "；")
                )
                // 完整正文留在面板上（提示条只活 4 秒），用户要能照着抄进 issue
                PanelState.diagnosticReport = EnvProbe.details(report).trim()
                if (report.cjkPathRoundTrip) Notify.info(line) else Notify.warn(line)
            } catch (e: Throwable) {
                Diag.log("env probe failed", e)
                Notify.fail(Strings.message("环境自检失败：") + com.doggybear233.saltaudiocopy.scsi.ScsiMessages.human(e))
            }
        }
    }

    @JvmStatic
    fun testNetwork() {
        PanelState.set(Strings.message("正在自检网络…"), "并发探测 MusicBrainz / Cover Art Archive / 三个 CDDB 镜像")
        worker().execute {
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
            PanelState.diagnosticReport = items.joinToString("\n") {
                "${it.name}: " + if (it.ok) "${it.ms}ms" else "不通（${it.note}）"
            }
            Diag.log("netcheck: $summary")
            Notify.info(if (settings.proxyUrl.isBlank()) summary else Strings.fmt("%s（代理 %s）", summary, settings.proxyUrl))
        }
    }

    /** 打开日志所在的文件夹（诊断页用；找不到就退回输出根目录）。 */
    @JvmStatic
    fun openLogFolder() {
        worker().execute {
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
        pickerThread("saltac-picker-override") {
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
        pickerThread("saltac-picker-root") {
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
                        com.doggybear233.saltaudiocopy.album.DiscCache.mirrorToVault(oldRoot, id)
                    }
                }.onFailure { Diag.log("root change: 迁移元数据缓存失败", it) }
            }
            // 名字立刻按新根重算（Discs 会带上内存/保险副本里的歌手与专辑）。
            // 这一步会读盘：放到工作线程，且抓轨时不抢光驱（否则与抓轨线程并发 SCSI）。
            runCatching {
                if (!busy) worker().execute {
                    runCatching {
                        com.doggybear233.saltaudiocopy.Discs.sync(SettingsStore.load().driveLetter, quiet = false)
                    }.onFailure { Diag.log("root change: 刷新光驱信息失败", it) }
                }
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
        worker().execute {
            try {
                val settings = SettingsStore.load(true)
                val dir = settings.effectiveRoot
                val report = com.doggybear233.saltaudiocopy.album.OutputCheck.check(dir)
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
        worker().execute {
            val n = com.doggybear233.saltaudiocopy.meta.MbCache.clear()
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
        worker().execute {
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
            val segments = com.doggybear233.saltaudiocopy.naming.TemplateEngine
                .expandToSegments(settings.folderTemplate, vars)
            segments.forEach { dir = dir.resolve(it) }
            val base = com.doggybear233.saltaudiocopy.naming.TemplateEngine
                .expand(settings.fileTemplate, vars)
                .ifBlank { "probe" }
            val name = com.doggybear233.saltaudiocopy.naming.TemplateEngine.sanitizeSegment(base, 150)
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

    /**
     * 恢复内置默认设置（不碰已存在的文件）。
     *
     * 破坏性操作：输出根、模板、读偏移、光驱/读命令全被换掉，而「试写测试」就在旁边。
     * 因此要二次确认，并先把旧设置另存一份——偏移被清零后抓轨会整体错位，且用户无从察觉。
     */
    @JvmStatic
    fun resetDefaults() {
        val now = System.currentTimeMillis()
        val armedFresh = PanelState.resetArmed && now - PanelState.resetArmedAt <= RESET_CONFIRM_WINDOW_MS
        if (!armedFresh) {
            PanelState.resetArmed = true
            PanelState.resetArmedAt = now
            PanelState.set(
                Strings.message("再点一次「恢复默认设置」确认"),
                Strings.message("会先把当前设置备份到插件数据目录，再换成内置默认值")
            )
            Notify.warn(Strings.message("再点一次「恢复默认设置」确认：会先备份当前设置"))
            return
        }
        PanelState.resetArmed = false
        PanelState.resetArmedAt = 0L
        val backup = writeSettingsBackup(SettingsStore.load())
        val ok = persist(
            RipSettings(),
            if (backup != null) Strings.fmt("已恢复默认设置（备份：%s）", backup.toString())
            else Strings.message("已恢复默认设置（备份失败，旧设置未另存）")
        )
        if (!ok) PanelState.resetArmed = true
    }

    /**
     * 把当前设置按 key=value 存一份到插件数据目录，返回路径；失败返回 null。
     *
     * 只用固定文件名 `settings.backup.txt`：以前按时间戳生成，恢复默认点几次就攒几份，
     * 用户也不知道该还原哪一份。固定名 + 每次覆盖，配合「恢复上次备份」动作才有意义。
     */
    internal fun writeSettingsBackup(s: RipSettings): java.nio.file.Path? = runCatching {
        val dir = Diag.dataDir() ?: error("没有数据目录")
        java.nio.file.Files.createDirectories(dir)
        val target = settingsBackupPath()
        // 整份导出（全部键），别再只写十几项——提示语承诺的是完整备份
        val body = s.dumpKeys().entries.joinToString(System.lineSeparator()) { (k, v) -> "$k=$v" }
        java.nio.file.Files.writeString(target, body + System.lineSeparator())
        target
    }.onFailure { Diag.log("settings backup failed", it) }.getOrNull()

    /** 固定备份路径（插件数据目录下）。 */
    internal fun settingsBackupPath(): java.nio.file.Path =
        (Diag.dataDir() ?: java.nio.file.Path.of(System.getProperty("java.io.tmpdir")))
            .resolve("settings.backup.txt")

    /**
     * 卸载时清理插件自己产生的文件：日志与设置备份。
     *
     * 刻意不碰 `.saltaudiocopy` 下的专辑缓存（用户手填的元数据/封面）与输出目录里的音频。
     */
    internal fun cleanupOnDelete(): Int {
        var removed = 0
        fun deleteOne(p: java.nio.file.Path) {
            val ok = runCatching {
                if (java.nio.file.Files.isRegularFile(p)) {
                    java.nio.file.Files.delete(p)
                    true
                } else false
            }.getOrDefault(false)
            if (ok) removed++
        }
        deleteOne(settingsBackupPath())
        // 日志就在插件数据目录的 logs/ 下；用 dataDir 推导，不依赖 Diag 是否已初始化
        runCatching {
            val dir = (Diag.dataDir() ?: return@runCatching).resolve("logs")
            if (!java.nio.file.Files.isDirectory(dir)) return@runCatching
            java.nio.file.Files.list(dir).use { s ->
                s.filter { it.fileName.toString().startsWith(LOG_NAME) }
                    .forEach { deleteOne(it) }
            }
        }
        Diag.log("cleanup on delete: removed $removed file(s)")
        return removed
    }

    /**
     * 从 [settingsBackupPath] 恢复设置。
     *
     * 只认 `key=value` 行，未知键忽略、坏行跳过；返回成功写入的键数，0 表示没有可用备份。
     */
    @JvmStatic
    fun restoreSettingsBackup() {
        val file = settingsBackupPath()
        if (!java.nio.file.Files.isRegularFile(file)) {
            toast(Strings.fmt("没有找到设置备份：%s", file.toString()), ToastType.Warning)
            return
        }
        // 恢复会整份覆盖：先要一次确认，并把「当前值」另存一份，才谈得上可逆（R4-03）。
        val now = System.currentTimeMillis()
        val armedFresh = PanelState.restoreArmed && now - PanelState.restoreArmedAt <= RESET_CONFIRM_WINDOW_MS
        if (!armedFresh) {
            PanelState.restoreArmed = true
            PanelState.restoreArmedAt = now
            PanelState.set(
                Strings.message("再点一次「恢复上次备份」确认"),
                Strings.message("会用备份覆盖当前全部设置；当前值会先另存到插件数据目录")
            )
            Notify.warn(Strings.message("再点一次「恢复上次备份」确认：会覆盖当前设置"))
            return
        }
        PanelState.restoreArmed = false
        PanelState.restoreArmedAt = 0L
        writeSettingsBackup(SettingsStore.load())
        val values = runCatching {
            java.nio.file.Files.readAllLines(file, Charsets.UTF_8)
                .mapNotNull { line ->
                    val i = line.indexOf('=')
                    if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1)
                }
                .toMap()
        }.getOrElse {
            toast(Strings.message("设置备份读不出来，文件可能损坏"), ToastType.Error)
            return
        }
        val applied = applySettingValues(SettingsStore.load(), values)
        val appliedCount = values.keys.count { isKnownSettingKey(it) }
        val ignored = values.keys.filterNot { isKnownSettingKey(it) }
        val msg = if (ignored.isEmpty()) {
            Strings.fmt("已从备份恢复 %s 项设置", appliedCount.toString())
        } else {
            Strings.fmt("已从备份恢复 %s 项设置，忽略 %s 个未知键：%s", appliedCount.toString(), ignored.size.toString(), ignored.joinToString(", "))
        }
        val ok = persist(applied, msg)
        if (!ok) PanelState.set(Strings.message("恢复备份失败"), Strings.message("宿主配置不可用，改动只在本次运行有效"))
        // 草稿跟着换：否则设置页还显示恢复前的旧值，用户以为没恢复
        PanelState.loadDraft(applied)
        PanelState.settingsSnapshot = applied
        PanelState.resetArmed = false
    }

    /** 这个键是否真的会被 [applySettingValues] 套用（供测试与「恢复了几项」计数）。 */
    internal fun isKnownSettingKey(key: String): Boolean =
        key in SETTING_STRING_KEYS || key in SETTING_BOOL_KEYS || key in SETTING_INT_KEYS

    /** 把 `key=value` 映射套到设置上；未知键忽略。抽成纯函数便于测试。 */
    internal fun applySettingValues(base: RipSettings, values: Map<String, String>): RipSettings {
        var s = base
        for ((k, v) in values) {
            s = when (k) {
                "outputRoot" -> s.copy(outputRoot = v)
                "folderTemplate" -> s.copy(folderTemplate = v)
                "fileTemplate" -> s.copy(fileTemplate = v)
                "coverFileName" -> s.copy(coverFileName = v)
                "multiDiscFolder" -> s.copy(multiDiscFolder = v)
                "driveLetter" -> s.copy(driveLetter = v)
                "overwritePolicy" -> s.copy(overwritePolicy = v)
                "format" -> s.copy(format = v)
                "readCommand" -> s.copy(readCommand = v)
                "errorRecovery" -> s.copy(errorRecovery = v)
                "gapMode" -> s.copy(gapMode = v)
                "gapMethod" -> s.copy(gapMethod = v)
                "proxyUrl" -> s.copy(proxyUrl = v)
                "trackSelection" -> s.copy(trackSelection = v)
                "overrideFile" -> s.copy(overrideFile = v)
                "appearance" -> s.copy(appearance = v)
                else -> {
                    val b = v.toBooleanStrictOrNull()
                    val n = v.toIntOrNull()
                    when {
                        k in SETTING_BOOL_KEYS && b != null -> setBool(s, k, b)
                        k in SETTING_INT_KEYS && n != null -> setInt(s, k, n)
                        else -> s
                    }
                }
            }
        }
        return s
    }

    private fun setBool(s: RipSettings, key: String, v: Boolean): RipSettings = when (key) {
        "secureMode" -> s.copy(secureMode = v)
        "fetchMetadata" -> s.copy(fetchMetadata = v)
        "autoLookupOnInsert" -> s.copy(autoLookupOnInsert = v)
        "writeCue" -> s.copy(writeCue = v)
        "writeLog" -> s.copy(writeLog = v)
        "annotateCueCrc" -> s.copy(annotateCueCrc = v)
        "imageMode" -> s.copy(imageMode = v)
        "analyzeBeforeRip" -> s.copy(analyzeBeforeRip = v)
        "embedCover" -> s.copy(embedCover = v)
        "defeatCache" -> s.copy(defeatCache = v)
        "testMode" -> s.copy(testMode = v)
        "writeAlbumJson" -> s.copy(writeAlbumJson = v)
        "reuseAlbumJson" -> s.copy(reuseAlbumJson = v)
        "detectGaps" -> s.copy(detectGaps = v)
        "resume" -> s.copy(resume = v)
        "keepWav" -> s.copy(keepWav = v)
        "preferTrackCount" -> s.copy(preferTrackCount = v)
        "useCdText" -> s.copy(useCdText = v)
        "guardDisc" -> s.copy(guardDisc = v)
        "ejectAfterRip" -> s.copy(ejectAfterRip = v)
        "useC2" -> s.copy(useC2 = v)
        "testAndCopy" -> s.copy(testAndCopy = v)
        else -> s
    }

    private fun setInt(s: RipSettings, key: String, v: Int): RipSettings = when (key) {
        "maxRetries" -> s.copy(maxRetries = v)
        "retryDelayMs" -> s.copy(retryDelayMs = v)
        "coverSize" -> s.copy(coverSize = v)
        "offsetSamples" -> s.copy(offsetSamples = v)
        "flacLevel" -> s.copy(flacLevel = v)
        "releaseIndex" -> s.copy(releaseIndex = v)
        "discNumberOverride" -> s.copy(discNumberOverride = v)
        "totalDiscsOverride" -> s.copy(totalDiscsOverride = v)
        "driveSpeed" -> s.copy(driveSpeed = v)
        else -> s
    }

    private val SETTING_BOOL_KEYS = setOf(
        "secureMode", "fetchMetadata", "autoLookupOnInsert", "writeCue", "writeLog", "annotateCueCrc",
        "imageMode", "analyzeBeforeRip", "embedCover", "defeatCache", "testMode", "writeAlbumJson",
        "reuseAlbumJson", "detectGaps", "resume", "keepWav", "preferTrackCount", "useCdText",
        "guardDisc", "ejectAfterRip", "useC2", "testAndCopy"
    )

    private val SETTING_STRING_KEYS = setOf(
        "outputRoot", "folderTemplate", "fileTemplate", "coverFileName", "multiDiscFolder",
        "driveLetter", "overwritePolicy", "format", "readCommand", "errorRecovery", "gapMode",
        "gapMethod", "proxyUrl", "trackSelection", "overrideFile", "appearance"
    )

    private val SETTING_INT_KEYS = setOf(
        "maxRetries", "retryDelayMs", "coverSize", "offsetSamples", "flacLevel", "releaseIndex",
        "discNumberOverride", "totalDiscsOverride", "driveSpeed"
    )

    /** 供测试/诊断：当前设置的一行摘要。 */
    fun describeSettings(s: RipSettings = SettingsStore.load()): String =
        "root=${s.effectiveRoot} folder=${s.folderTemplate} file=${s.fileTemplate} " +
            "secure=${s.secureMode} offset=${s.offsetSamples} meta=${s.fetchMetadata}"
}
