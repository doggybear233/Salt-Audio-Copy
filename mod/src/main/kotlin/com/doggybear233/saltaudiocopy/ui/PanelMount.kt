package com.doggybear233.saltaudiocopy.ui

import androidx.compose.runtime.Composable
import com.doggybear233.saltaudiocopy.Diag
import com.xuncorp.spw.workshop.api.hook.ComposableHook
import com.xuncorp.spw.workshop.api.hook.HookHandle
import com.xuncorp.spw.workshop.api.hook.HookRegistrar
import com.xuncorp.spw.workshop.api.hook.UiHookCall

/**
 * 把面板挂到宿主「CD 工具箱」页。
 *
 * 只走一条路径：**hook 整页 composable**，面板自己贴底排成横向坞（避让播放条）。
 *
 * 为什么不挂页面内部：0.29.0–0.29.3 试过 hook 页面 `Column` 里的子组件以获得「真正内嵌」，
 * 两种失败都出现了——子组件在某些状态下不组合（面板整个消失），
 * 或者被宿主放进没有剩余空间的布局里（内容尺寸为 0，同样看不见）。
 * 宿主的页面结构不是给插件留的插槽，猜内部布局的风险远高于收益。
 *
 * 目标类名是宿主混淆名，宿主升级可能变：先看 [HostTargets] 的解析结果，
 * 再退回写死常量；全部失败只写日志 + 提示一次，绝不影响宿主。
 */
object PanelMount {

    private var handle: HookHandle? = null
    private var lastError: String? = null

    /** 恒为 false；保留字段是为了兼容旧的诊断文案与测试。 */
    @Volatile
    var inline: Boolean = false
        private set

    fun install(registrar: HookRegistrar) {
        if (handle != null) return

        val hook = object : ComposableHook() {
            // Kotlin 侧签名是 `Content(call: UiHookCall)`；Composer/$changed 由
            // Compose 编译器为 @Composable 函数自动追加，不要手写。
            @Composable
            override fun Content(call: UiHookCall) {
                // 必须在这里套主题：宿主没给插件组合提供 MaterialTheme，
                // 不套就会落到 M3 缺省配色（那层淡紫）。
                SaltAudioCopyTheme { DockPanelBody() }
            }
        }

        // 候选来源：先宿主页面类常量池里现算的（宿主升级换了混淆名也能跟上），
        // 再退回写死的常量（校验过的实测值）。以前只试常量，解析器写好了却从未被调用。
        val pageTarget = HostTargets.Target(HostTargets.PAGE_CLASS, HostTargets.PAGE_METHOD)
        val candidateList = mountTargets()
        Diag.log("panel mount candidates=${candidateList.joinToString()}")

        // parameterTypes：null = 不限（官方语义）；另外两种是宿主可能比较的写法。
        for (cand in candidateList) {
            for (types in listOf<List<String>?>(null, emptyList(), listOf(HostTargets.PAGE_PARAM))) {
                val source = if (cand == pageTarget) "const" else "pool"
                if (tryMount(registrar, hook, cand.owner, cand.method, HostTargets.PAGE_CALLER, types)) {
                    Diag.log("panel mounted on CD page (target=$cand source=$source types=$types)")
                    PanelState.detail = Strings.message("面板已挂载；点「扫描」读取当前光盘")
                    return
                }
            }
        }

        lastError = Strings.message("宿主 CD 页类名未解析到（宿主版本可能已变化）")
        Diag.log("panel mount gave up：宿主类名可能已变化，功能仍可通过插件设置页使用")
        warnUserOnce()
    }

    /**
     * 挂载候选顺序：常量池里现算的（宿主升级换了混淆名也能跟上）→ 写死常量。
     *
     * 常量放最后而不是最前：解析器能读到真实类名时它更可信；解析失败时它自然只剩常量。
     */
    internal fun mountTargets(): List<HostTargets.Target> {
        val pageTarget = HostTargets.Target(HostTargets.PAGE_CLASS, HostTargets.PAGE_METHOD)
        val found = runCatching { HostTargets.candidates() }.getOrElse { emptyList() }
        return found + if (pageTarget in found) emptyList() else listOf(pageTarget)
    }

    private fun tryMount(
        registrar: HookRegistrar,
        hook: ComposableHook,
        owner: String,
        method: String,
        inClass: String,
        types: List<String>?
    ): Boolean {
        val result = runCatching {
            registrar.afterComposable(owner, method, inClass, types, 50, hook)
        }
        result.onSuccess { handle = it; return true }
        result.onFailure { e ->
            Diag.log("mount attempt failed: $owner.$method in $inClass types=$types " +
                "-> ${e::class.java.simpleName}: ${e.message}")
        }
        return false
    }

    private var warned = false

    /**
     * 面板挂不上时明确告知用户，而不是静默只写日志。
     * 宿主的 CD 页类名是混淆的，宿主升级后可能变化——这是可预期风险，用户有权知道原因。
     */
    private fun warnUserOnce() {
        if (warned) return
        warned = true
        runCatching {
            com.xuncorp.spw.workshop.api.WorkshopApi.ui.toast(
                Strings.message("抓轨面板未能挂载（宿主版本可能已更新）。功能仍可在插件设置页使用。"),
                com.xuncorp.spw.workshop.api.WorkshopApi.Ui.ToastType.Warning
            )
        }.onFailure { Diag.log("panel mount warn toast failed", it) }
    }

    fun uninstall() {
        runCatching { handle?.unhook() }.onFailure { Diag.log("panel unhook failed", it) }
        handle = null
    }

    /** 供设置页按钮/诊断使用。 */
    val mounted: Boolean get() = handle != null

    /**
     * 一行人能读懂的状态，给「检查面板挂载」按钮用。
     *
     * 宿主类名是混淆名（`androidx.compose.ui.…`），写进用户可见文案只会让人以为是崩溃；
     * 详细目标只进日志，界面只说「挂上了没有、为什么没挂上」。
     */
    fun status(): String {
        Diag.log("panel status: mounted=$mounted target=${HostTargets.PAGE_CLASS}.${HostTargets.PAGE_METHOD} caller=${HostTargets.PAGE_CALLER}")
        return if (mounted) {
            Strings.message("面板已挂载为底部坞（贴底显示，并避让播放条）。")
        } else {
            Strings.fmt("面板未挂载：%s", lastError ?: Strings.message("尚未尝试"))
        }
    }
}
