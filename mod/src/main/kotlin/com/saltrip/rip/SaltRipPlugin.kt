package com.saltrip.rip

import com.xuncorp.spw.workshop.api.PluginContext
import com.xuncorp.spw.workshop.api.SpwPlugin
import com.xuncorp.spw.workshop.api.WorkshopApi
import com.saltrip.rip.ui.PanelMount
import com.xuncorp.spw.workshop.api.WorkshopApi.Ui.ToastType

/**
 * M1-0：只验证打包契约与生命周期。抓轨能力在 M1-2 起逐步接入。
 */
class SaltRipPlugin(context: PluginContext) : SpwPlugin(context) {

    override fun start() {
        val ctx = pluginContext
        Diag.init(ctx.pluginPath)
        Diag.log(
            "start: id=${ctx.pluginId} v=${ctx.pluginVersion} spw=${ctx.spwVersion} " +
                "channel=${ctx.spwChannel} path=${ctx.pluginPath}"
        )
        // 重置面板：上一次会话遗留的失败文案、旧盘摘要、残留 busy 都不能带进新一轮。
        runCatching {
            com.saltrip.rip.ui.PanelState.showDisc(null)
            com.saltrip.rip.ui.PanelState.clearProgress()
            com.saltrip.rip.ui.PanelState.set("尚未扫描光驱", "点「扫描」识别光盘；识别后可直接抓整盘")
        }.onFailure { Diag.log("PanelState reset failed", it) }
        // 把抓轨面板挂到宿主「CD 工具箱」页；失败只记日志，不影响插件可用性。
        runCatching { PanelMount.install(WorkshopApi.hookRegistrar) }
            .onFailure { Diag.log("PanelMount.install failed", it) }
        // 换盘自动发现：不点「扫描」也能识别新盘（用户反馈 3/7）。
        runCatching { com.saltrip.rip.DiscWatcher.start() }
            .onFailure { Diag.log("DiscWatcher.start failed", it) }
        runCatching {
            WorkshopApi.ui.toast("盐专辑 Salt Audio Copy ${ctx.pluginVersion} 已加载", ToastType.Success)
        }.onFailure { Diag.log("toast failed", it) }
    }

    override fun stop() {
        runCatching { com.saltrip.rip.DiscWatcher.stop() }
        runCatching { PanelMount.uninstall() }
        Diag.log("stop")
    }

    override fun delete() {
        Diag.log("delete")
    }
}
