package com.doggybear233.saltaudiocopy.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.doggybear233.saltaudiocopy.ConfigActions

/**
 * 宿主「CD 工具箱」页底部的抓轨坞。
 *
 * 形态：**整宽横向条**，贴页面底部、上移 96dp 避开宿主播放条；
 * 玻璃面板（宿主 `surfaceContainerHigh` + 92% 不透明 + 1px 描边 + 20dp 圆角），
 * 深浅主题都直接取宿主 scheme，不自己调色。
 *
 * 单行四段：封面 / 碟与专辑 / 进度与速度 / 动作按钮；设置在原位向下展开。
 */
@Composable
fun RipPanel() = SaltAudioCopyTheme { DockPanelBody() }

/** 底部坞本体；[PanelMount] 的 hook 直接调用它。 */
@Composable
internal fun DockPanelBody() {
    // 坞是否真的在屏幕上：例行提示据此决定走状态条还是退回宿主 toast。
    DisposableEffect(Unit) {
        PanelState.visible = true
        onDispose { PanelState.visible = false }
    }
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        // 宿主给的高度可能无界（放进可滚动列时），fillMaxHeight 会抛异常。
        val bounded = constraints.hasBoundedHeight
        val boxModifier = if (bounded) {
            Modifier.fillMaxWidth()
                .height(maxHeight)
                .padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 96.dp)
        } else {
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp)
        }
        Box(modifier = boxModifier, contentAlignment = Alignment.BottomCenter) {
            PanelSurface()
        }
    }
}

@Composable
private fun PanelSurface() {
    val cs = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = cs.surface,
        contentColor = cs.onSurface,
        border = BorderStroke(1.dp, cs.outlineVariant),
        tonalElevation = 0.dp,
        shadowElevation = 4.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            NoticeBar()
            when {
                PanelState.manualOpen -> ManualMetaPanel()
                PanelState.settingsOpen -> SettingsPanel()
                PanelState.busy || PanelState.progress != null -> RunningBlock()
                PanelState.disc == null -> IdleBlock()
                else -> ReadyBlock()
            }
        }
    }
}

/* ---------------------------------------------------------------- 提示条 */

/**
 * 坞上沿的临时提示（方案 A）。
 *
 * 宿主的 toast 是整宽贴窗口底的，坞也在底部，必然互相遮挡；
 * 例行消息改画在这里：不压任何按钮，4 秒后自己收起，点 × 立刻关。
 * 失败消息仍走宿主 toast（见 [Notify.fail]），因为坞可能被折叠。
 */
@Composable
private fun NoticeBar() {
    val cs = MaterialTheme.colorScheme
    val notice = PanelState.notice
    if (notice == null) {
        return
    }
    // 同一段文案再次触发时 id 变了，计时从头开始。
    LaunchedEffect(notice.id) {
        delay(Notify.LINGER_MS)
        if (PanelState.notice?.id == notice.id) PanelState.notice = null
    }
    val warn = notice.kind == NoticeKind.WARN
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (warn) cs.errorContainer.copy(alpha = 0.55f) else cs.surfaceVariant.copy(alpha = 0.75f))
            .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = notice.text,
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, lineHeight = 16.sp),
            color = if (warn) cs.onErrorContainer else cs.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "×",
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp),
            color = if (warn) cs.onErrorContainer else cs.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable { PanelState.notice = null }
                .padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

/* ---------------------------------------------------------------- 空闲 */

@Composable
private fun IdleBlock() {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = Strings.key("title"),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = PanelState.detail,
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Button(
            onClick = { ConfigActions.scanDrives() },
            enabled = !PanelState.busy && !PanelState.metaBusy,
            colors = monoPrimary()
        ) {
            Text(Strings.key("scan"), style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = { ConfigActions.openSettings() }) {
            Text(Strings.key("settings"), style = MaterialTheme.typography.labelMedium)
        }
    }
}

/* ---------------------------------------------------------------- 就绪 */

@Composable
private fun ReadyBlock() {
    val cs = MaterialTheme.colorScheme
    val gate = PanelState.actionGate()

    Row(verticalAlignment = Alignment.CenterVertically) {
        CoverThumb(56.dp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = albumLine(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = PanelFormat.discLine(PanelState.disc) ?: Strings.key("subtitle"),
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // 查元数据可能卡十几秒（镜像连不上）：必须看得见在等什么，否则像点了没反应。
            if (PanelState.metaBusy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 1.5.dp,
                        color = cs.primary
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = Strings.message("元数据正在加载…"),
                        style = MaterialTheme.typography.labelMedium,
                        color = cs.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            // 抓完之后必须有一行明确的完成标记：以前这条状态只在 Running 区块里显示，
            // 一回到 Ready 状态就整行消失，用户看不到「已抓完」（真机反馈 C3）。
            if (PanelState.ripDone) {
                Text(
                    text = "\u2713 " + PanelState.status,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = cs.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = outputPreview(),
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(12.dp))
        Button(onClick = { ConfigActions.ripAlbum() }, enabled = gate == null, colors = monoPrimary()) {
            Text(
                Strings.key(if (PanelState.ripDone) "rip_again" else "rip_album"),
                style = MaterialTheme.typography.labelLarge
            )
        }
        Spacer(Modifier.width(6.dp))
        if (showManualButton()) {
            // 查过一轮但一个候选都没有：这时「刷新」再点也是空，改成让用户自己写。
            OutlinedButton(
                onClick = { ConfigActions.openManualMeta() },
                enabled = gate == null,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = cs.onSurface),
                border = BorderStroke(1.dp, cs.outlineVariant)
            ) {
                Text(manualButtonLabel(), style = MaterialTheme.typography.labelLarge)
            }
        } else {
            OutlinedButton(
                onClick = { ConfigActions.lookupMetadata() },
                enabled = gate == null,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = cs.onSurface),
                border = BorderStroke(1.dp, cs.outlineVariant)
            ) {
                Text(Strings.key("refresh_meta"), style = MaterialTheme.typography.labelLarge)
            }
            // 有候选时按钮仍是「刷新」，但手填入口不能消失：候选不全时用户还是想自己改。
            if (PanelState.candidates.isNotEmpty()) {
                TextButton(
                    onClick = { ConfigActions.openManualMeta() },
                    enabled = gate == null
                ) {
                    Text(
                        Strings.key("manual_meta"),
                        style = MaterialTheme.typography.labelMedium,
                        color = cs.onSurfaceVariant
                    )
                }
            }
        }
        Spacer(Modifier.width(2.dp))
        TextButton(onClick = { ConfigActions.openOutputFolder() }) {
            Text(
                Strings.key("open_folder"),
                style = MaterialTheme.typography.labelMedium,
                color = cs.onSurfaceVariant
            )
        }
        TextButton(onClick = { ConfigActions.openSettings() }) {
            Text(Strings.key("settings"), style = MaterialTheme.typography.labelMedium)
        }
    }

    if (PanelState.drives.size > 1) DriveRow()
    if (PanelState.candidates.size > 1) CandidateRow()

    gate?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.labelSmall,
            color = cs.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/* ---------------------------------------------------------------- 运行中 */

@Composable
private fun RunningBlock() {
    val cs = MaterialTheme.colorScheme
    val p = PanelState.progress
    val paused = PanelState.paused
    val armed = PanelState.cancelArmed
    var idleSec by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            // 没有进度时从「本轮开始」算起；用上一轮的心跳会算出几千秒
            val since = if (PanelState.lastBeatAt == 0L) PanelState.beginAt else PanelState.lastBeatAt
            idleSec = if (since == 0L) 0L else (System.currentTimeMillis() - since) / 1_000
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        CoverThumb(48.dp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = albumLine(),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // 进度条全程保留：暂停时变淡而不是消失（以前整块消失，看起来像卡死）
            LinearProgressIndicator(
                progress = { (p?.fraction ?: 0f).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                color = if (paused) cs.onSurface.copy(alpha = 0.35f) else cs.onSurface,
                trackColor = cs.onSurface.copy(alpha = 0.14f)
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = buildString {
                        append(PanelState.status)
                        if (p != null) {
                            append(" · ")
                            append(PanelFormat.trackLine(p.trackNo, p.trackTotal, p.fraction))
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = when {
                        paused -> Strings.message("已暂停 · 点「继续」接着抓")
                        // 有进度就先说进度；只有「一块都还没回来」才谈等待
                        p != null -> PanelFormat.speed(p.speedX) + " · " + PanelFormat.eta(p.etaSec) +
                            if (PanelState.verifyTimes > 1) " · " + Strings.fmt("每块校验 %s 次", PanelState.verifyTimes) else ""
                        idleSec >= 8 -> Strings.fmt("正在重读这一块 · 已等 %s 秒", idleSec)
                        else -> ""
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        OutlinedButton(
            onClick = { ConfigActions.pauseRip() },
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Text(
                text = Strings.key(if (paused) "resume" else "pause"),
                style = MaterialTheme.typography.labelLarge
            )
        }
        Spacer(Modifier.width(6.dp))
        TextButton(onClick = { ConfigActions.cancelRip() }) {
            Text(
                text = Strings.key(if (armed) "cancel_confirm" else "cancel"),
                style = MaterialTheme.typography.labelLarge,
                color = if (armed) cs.error else cs.onSurfaceVariant
            )
        }
    }
}

/* ---------------------------------------------------------------- 片段 */

@Composable
private fun CoverThumb(sizeDp: Dp) {
    val cs = MaterialTheme.colorScheme
    // 候选里的封面优先；没有候选（比如手填元数据、网络查不到）就用本地缓存的封面
    val img = PanelState.candidates.getOrNull(PanelState.selectedCandidate)?.cover
        ?: PanelState.discCover
    val bmp = CoverImages.decode(img)
    if (bmp != null) {
        Image(
            bitmap = bmp,
            contentDescription = null,
            modifier = Modifier.size(sizeDp).clip(RoundedCornerShape(10.dp))
        )
    } else {
        Surface(
            color = cs.surfaceVariant,
            modifier = Modifier.size(sizeDp).clip(RoundedCornerShape(10.dp))
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("CD", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            }
        }
    }
}

private fun albumLine(): String {
    val d = PanelState.disc
    val artist = d?.artist?.takeIf { it.isNotBlank() && it != "未知艺术家" }
    val album = d?.album?.takeIf { it.isNotBlank() && it != "未知专辑" }
    if (artist != null && album != null) return "$artist — $album"
    if (album != null) return album
    // 盘信息里还没有元数据时，退到当前选中的候选版本
    PanelState.candidates.getOrNull(PanelState.selectedCandidate)?.let { return it.label }
    return Strings.key("title")
}

/**
 * 「文件会落到哪」：按当前设置 + 已解析的元数据算，不再拿上一次抓轨的目录顶替。
 *
 * 改过输出根却仍显示旧目录，用户会以为设置没生效；这里的 artist/album 与坞上标题
 * 走同一套解析（盘信息 → 选中候选 → 手动保存的缓存）。
 */
private fun outputPreview(): String = PanelState.previewPath.ifBlank { "…" }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DriveRow() {
    val cs = MaterialTheme.colorScheme
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        for (d in PanelState.drives) {
            val selected = PanelState.selectedDrive == d.letter
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = if (selected) cs.onSurface.copy(alpha = 0.10f) else Color.Transparent,
                border = BorderStroke(1.dp, if (selected) cs.onSurface.copy(alpha = 0.45f) else cs.outlineVariant.copy(alpha = 0.6f)),
                modifier = Modifier.clickable { ConfigActions.selectDrive(d.letter) }
            ) {
                Text(
                    text = d.label,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) cs.onSurface else cs.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CandidateRow() {
    val cs = MaterialTheme.colorScheme
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = Strings.key("versions"),
            style = MaterialTheme.typography.labelSmall,
            color = cs.onSurfaceVariant
        )
        PanelState.candidates.take(6).forEachIndexed { i, c ->
            val selected = PanelState.selectedCandidate == i
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = if (selected) cs.onSurface.copy(alpha = 0.10f) else Color.Transparent,
                border = BorderStroke(1.dp, if (selected) cs.onSurface.copy(alpha = 0.45f) else cs.outlineVariant.copy(alpha = 0.5f)),
                modifier = Modifier.clickable { ConfigActions.selectCandidate(i) }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    val thumb = CoverImages.decode(c.cover)
                    if (thumb != null) {
                        Image(
                            bitmap = thumb,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp).clip(RoundedCornerShape(4.dp))
                        )
                    }
                    Text(
                        text = c.label.take(16),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) cs.onSurface else cs.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * 黑白按钮：浅色主题用黑底白字，深色主题用白底黑字——不引入宿主的紫色 primary。
 */
@Composable
private fun monoPrimary() = ButtonDefaults.buttonColors(
    containerColor = MaterialTheme.colorScheme.onSurface,
    contentColor = MaterialTheme.colorScheme.surface,
    disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
    disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
)
