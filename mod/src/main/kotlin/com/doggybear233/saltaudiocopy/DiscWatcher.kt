package com.doggybear233.saltaudiocopy

import com.doggybear233.saltaudiocopy.ui.Strings
import com.doggybear233.saltaudiocopy.scsi.CdDevice
import com.doggybear233.saltaudiocopy.scsi.DiscFingerprint
import com.doggybear233.saltaudiocopy.scsi.ScsiException
import com.doggybear233.saltaudiocopy.settings.SettingsStore
import com.doggybear233.saltaudiocopy.ui.PanelState
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 盘检测：面板开着的时候，用户换盘 / 抽出盘 / 放错数据盘，都要**自己发现**，
 * 不能等用户点「扫描」——用户实测反馈（第 3、7 条）就是不点扫描直接抓整盘，
 * 结果按上一张盘的元数据命名了新盘。
 *
 * 设计取舍：
 * - 2 秒一次 `READ TOC` 很轻（真正贵的 READ CD 不在轮询里）。
 * - 「没有盘」不是错误：立即清面板并把间隔保持 2 秒，用户放盘后马上能识别。
 * - 只有真正的设备错误（驱动挂起、权限、I/O 失败）才指数退避到 30 秒，
 *   避免驱动卡死时每 2 秒制造一次新的挂起。
 * - [PanelState.busy] 且未暂停时完全不轮询，不与抓轨抢设备；暂停期间仍轮询以便发现抽盘。
 */
object DiscWatcher {

    private const val MIN_INTERVAL_MS = 2_000L
    private const val MAX_INTERVAL_MS = 30_000L

    private var thread: Thread? = null
    private val running = AtomicBoolean(false)

    @Volatile
    private var lastFingerprint: String? = null

    @Volatile
    private var lastError: String? = null

    @Volatile
    private var lastHadDisc = false

    /** 测试缝：替换一轮「检查光盘」的实际动作，避免单测真的去读 \.\X:。 */
    internal var tickOverride: (() -> Boolean)? = null

    /** 线程是否还活着（测试用：验证 stop() 真的 join 了）。 */
    internal fun workerAliveForTest(): Boolean = thread?.isAlive == true

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val t = Thread({
            var interval = MIN_INTERVAL_MS
            while (running.get()) {
                try {
                    Thread.sleep(interval)
                } catch (_: InterruptedException) {
                    break
                }
                if (!running.get()) break
                // 暂停时也要继续轮询：否则暂停中抽出光盘，面板还停在旧状态（真机反馈 B7）
                if (PanelState.busy && !PanelState.paused) {
                    interval = MIN_INTERVAL_MS
                    continue
                }
                val ok = tickOverride?.invoke() ?: tick()
                interval = if (ok) MIN_INTERVAL_MS
                else (interval * 2).coerceAtMost(MAX_INTERVAL_MS)
            }
        }, "saltaudiocopy-watch")
        t.isDaemon = true
        thread = t
        t.start()
        Diag.log("DiscWatcher started")
    }

    fun stop() {
        running.set(false)
        val t = thread
        thread = null
        t?.interrupt()
        // 等它真的退出：不 join 的话旧线程可能还卡在一次 SCSI 轮询里，
        // 随后插件被重新加载、start() 又起一条，两条线程同时读同一台光驱。
        if (t != null && t.isAlive) {
            runCatching { t.join(2_000) }
            if (t.isAlive) Diag.log("DiscWatcher stop: 旧线程 2 秒内没退出（仍在一次读盘里）")
        }
        Diag.log("DiscWatcher stopped")
    }

    /** 用户点扫描/抓轨后同步一次基准，避免轮询立刻再判一次「换盘」。 */
    fun seed(toc: com.doggybear233.saltaudiocopy.scsi.DiscToc?) {
        lastFingerprint = toc?.let { DiscFingerprint.of(it) }
        lastHadDisc = toc != null
    }

    /** 返回 true 表示设备正常（含「只是没盘」）；false 才退避。 */
    private fun tick(): Boolean {
        val preferred = runCatching { SettingsStore.load() }.getOrNull()?.driveLetter.orEmpty()
        val drives = runCatching { CdDevice.scanDrives() }.getOrElse { e ->
            reportFailure(e)
            return false
        }
        if (drives.isEmpty()) {
            showEmpty("没有检测到可用的光驱")
            return true
        }

        return runCatching { Discs.sync(preferred, quiet = true) }.fold(
            onSuccess = { scan ->
                lastError = null
                val fp = DiscFingerprint.of(scan.toc)
                // 上一轮读失败时面板已被清空（指纹也清成 null），这里的指纹比对一定不相等；
                // 但若因为别的路径把指纹留了下来，面板空着也要重建一次，否则永久「读不到光盘」。
                if (fp == lastFingerprint && PanelState.disc == null) {
                    runCatching { com.doggybear233.saltaudiocopy.Discs.publish(scan) }
                }
                if (fp != lastFingerprint) {
                    lastFingerprint = fp
                    lastHadDisc = true
                    Diag.log("DiscWatcher: disc $fp")
                    val back = runCatching { DiscMemory.find(fp) }.getOrNull()
                    if (back != null) {
                        // 同一张盘放回来了：恢复曲名/候选/封面/「已抓完」，用户可以直接继续
                        runCatching { PanelState.restoreSnapshot(back, back.ripDone) }
                        PanelState.set(
                            Strings.message("同一张盘放回来了"),
                            Strings.message("已恢复上次的状态；未抓完的可以直接再点抓取（设置里开着断点续抓）")
                        )
                        Diag.log("DiscWatcher: same disc restored, ripDone=${back.ripDone}")
                        // 已经有候选就不用再联网；没有才补查一次
                        if (back.candidates.isEmpty()) runCatching { ConfigActions.refreshMetadataAuto(fp) }
                    } else {
                        // 换了盘：清掉旧记忆与旧状态，避免 A 盘的曲名贴到 B 盘
                        runCatching { DiscMemory.forget() }
                        PanelState.lookupTried = false
                        PanelState.closeManual()
                        PanelState.clearRipped()
                        // 换盘后自动查元数据：用户不该再点一次按钮。
                        // 传入这张盘的指纹，查询回来时若已换盘就丢弃（见 autoResultIsFresh）。
                        runCatching { ConfigActions.refreshMetadataAuto(fp) }
                    }
                }
                true
            },
            onFailure = { e ->
                if (isEmptyDisc(e)) {
                    lastError = null
                    showEmpty(com.doggybear233.saltaudiocopy.scsi.ScsiMessages.human(e))
                    true
                } else {
                    reportFailure(e)
                    false
                }
            }
        )
    }

    private fun showEmpty(reason: String) {
        if (!lastHadDisc && lastFingerprint == null) return
        // 抽盘前把「这张盘现在是什么状态」记下来：同一张盘放回来要能接着抓（用户反馈 B6）
        runCatching { DiscMemory.remember(lastFingerprint) }
        lastHadDisc = false
        lastFingerprint = null
        runCatching { PanelState.markEjected(reason) }
        Diag.log("DiscWatcher: no disc ($reason)")
    }

    /** 抽盘后再插回同一张盘：把记忆取回；面板此刻是空的，所以状态行由这里重建。 */
    private fun reportFailure(e: Throwable) {
        val msg = com.doggybear233.saltaudiocopy.scsi.ScsiMessages.human(e)
        // 读盘失败后必须让下一轮能重新「发现」这张盘：如果继续留着旧指纹，
        // 恢复后 fp == lastFingerprint，换盘分支永远不触发，面板就永久停在「读不到光盘」。
        lastFingerprint = null
        lastHadDisc = false
        if (msg == lastError) return
        lastError = msg
        Diag.log("DiscWatcher failed: $msg")
        runCatching {
            PanelState.showDisc(null)
            PanelState.set(Strings.message("读不到光盘"), msg)
        }
    }

    /**
     * sense key 2（NOT READY）/ ASC 0x3A（medium not present）算「没盘」，不算故障。
     * 判定逻辑在 [com.doggybear233.saltaudiocopy.scsi.ScsiMessages.isNoDisc]：只看结构化错误码，
     * 不看界面文案（否则英文/日文界面会把「没盘」当设备故障）。
     */
    internal fun isEmptyDisc(e: Throwable): Boolean =
        com.doggybear233.saltaudiocopy.scsi.ScsiMessages.isNoDisc(e)
}
