package com.doggybear233.saltaudiocopy.ui

import com.doggybear233.saltaudiocopy.ConfigActions

/**
 * 面板内的临时提示（抓轨中、导出成功这类例行消息）。
 *
 * 为什么不用宿主 toast：`WorkshopApi.ui.toast()` 是**整宽贴窗口底**的，
 * 而坞也在底部，两者必然叠在一起，插件改不了它的位置。
 * 所以例行消息走坞上沿这条状态条（[NoticeBar]），只有失败/警告才留给宿主 toast
 * （坞被折叠时也要能看见）。
 */
internal data class Notice(
    val text: String,
    val kind: NoticeKind,
    /** 递增序号：同样的文案再发一次也能重新计时。 */
    val id: Int
)

internal enum class NoticeKind { INFO, WARN }

internal object Notify {

    /** 状态条停留时长；手动点 × 可提前收起。 */
    const val LINGER_MS = 4000L

    /**
     * 例行消息。面板不在屏幕上时退回宿主 toast——
     * 从宿主「模组管理」页点扫描驱动器就会走到这条路径。
     */
    fun info(text: String) {
        if (PanelState.visible) {
            PanelState.notice = Notice(text, NoticeKind.INFO, nextId())
        } else {
            ConfigActions.toastHost(text, hostSuccess = true)
        }
    }

    fun warn(text: String) {
        if (PanelState.visible) {
            PanelState.notice = Notice(text, NoticeKind.WARN, nextId())
        } else {
            ConfigActions.toastHost(text, hostSuccess = false)
        }
    }

    /** 失败：始终走宿主 toast，避免坞被折叠时丢掉关键信息。 */
    fun fail(text: String) {
        ConfigActions.toastHost(text, hostSuccess = false)
    }

    private fun nextId(): Int {
        PanelState.noticeSeq += 1
        return PanelState.noticeSeq
    }
}
