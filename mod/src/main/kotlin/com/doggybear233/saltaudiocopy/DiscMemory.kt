package com.doggybear233.saltaudiocopy

import com.doggybear233.saltaudiocopy.ui.PanelState

/**
 * 抽盘记忆：**同一张盘**再放回来时，把面板恢复到抽出前的样子。
 *
 * 用户反馈（B6）：弹出光盘后应当立刻显示「光驱里没有光盘」，同一张盘放回来要能接着抓；
 * 换了盘才当新盘。以前每次抽盘都会 `showDisc(null)` + 清候选 + 清「已抓完」，
 * 同一张盘放回来等于从零开始，已抓的部分还得靠用户自己认出「断点续抓」。
 *
 * 只记「最近一张」：用户换盘时旧记忆必须失效，否则会把 A 盘的曲名贴到 B 盘上。
 */
internal object DiscMemory {

    data class Snapshot(
        val fingerprint: String,
        val discId: String,
        val info: PanelState.DiscInfo,
        val cover: ByteArray?,
        val candidates: List<PanelState.Candidate>,
        val selected: Int,
        val lookupTried: Boolean,
        val ripDone: Boolean,
        val outDir: java.nio.file.Path?
    ) {
        /** 对比封面用：ByteArray 的 equals 是引用比较。 */
        fun sameBitmapsCover(other: ByteArray?): Boolean =
            cover === other || (cover != null && other != null && cover.contentEquals(other))
    }

    @Volatile
    private var last: Snapshot? = null

    /** 面板现在有盘就记一份；没有盘时不动（避免用空状态覆盖好记忆）。 */
    fun remember(fingerprint: String?) {
        val info = PanelState.disc ?: return
        val fp = fingerprint ?: info.discId
        if (fp.isBlank()) return
        last = Snapshot(
            fingerprint = fp,
            // 两个键都存：抽盘那一刻 TOC 指纹有时已经是 null（上一轮读失败刚清过），
            // 这时只能拿 DiscID 当键，而放回来时上游给的是 TOC 指纹。以前只存一个键，
            // 两条路径的键不同 → 记忆永远命中不了，B6「同一张盘放回来接着抓」形同虚设。
            discId = info.discId,
            info = info,
            cover = PanelState.discCover,
            candidates = PanelState.candidates,
            selected = PanelState.selectedCandidate,
            lookupTried = PanelState.lookupTried,
            ripDone = PanelState.ripDone,
            outDir = PanelState.lastOutDir
        )
    }

    /** 取出并**不**清除记忆：用户可能来回抽放同一张盘好几次。 */
    fun find(fingerprint: String): Snapshot? =
        last?.takeIf { it.fingerprint == fingerprint || (it.discId.isNotBlank() && it.discId == fingerprint) }

    /** 换盘时清掉，防止 A 盘的记忆被当成 B 盘。 */
    fun forget() {
        last = null
    }

    internal fun clearForTest() {
        last = null
    }
}
