package com.saltrip.rip.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saltrip.rip.ConfigActions

/**
 * 手动填写元数据（坞内联面板，不弹独立窗口）。
 *
 * 为什么是内联而不是 `Dialog`：宿主是自绘 Compose 窗口，插件拿不到可靠的 overlay 层，
 * 独立窗口也有过「点不到宿主、焦点跑掉」的问题；这块跟着坞一起画，主题、字体、点击
 * 都跟其他面板一致（见 `design/proposals-notify.md` 的同类结论）。
 */
@Composable
internal fun ManualMetaPanel() {
    val cs = MaterialTheme.colorScheme
    val d = PanelState.manual
    val shape = RoundedCornerShape(12.dp)

    Surface(
        color = cs.surface,
        contentColor = cs.onSurface,
        shape = shape,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, cs.outlineVariant, shape)
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Header()
            HorizontalDivider(
                color = cs.outlineVariant.copy(alpha = 0.5f),
                modifier = Modifier.padding(vertical = 10.dp)
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 296.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Field(
                        label = Strings.key("manual_artist"),
                        value = d.albumArtist,
                        modifier = Modifier.weight(1f)
                    ) { v -> PanelState.manual = PanelState.manual.copy(albumArtist = v) }
                    Field(
                        label = Strings.key("manual_album"),
                        value = d.albumTitle,
                        modifier = Modifier.weight(1f)
                    ) { v -> PanelState.manual = PanelState.manual.copy(albumTitle = v) }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Field(
                        label = Strings.key("manual_year"),
                        value = d.year,
                        modifier = Modifier.width(120.dp)
                    ) { v -> PanelState.manual = PanelState.manual.copy(year = v.filter { it.isDigit() }.take(4)) }
                    Field(
                        label = Strings.key("manual_disc"),
                        value = d.discNumber.toString(),
                        modifier = Modifier.width(96.dp)
                    ) { v ->
                        v.filter { it.isDigit() }.take(2).toIntOrNull()?.let { n ->
                            PanelState.manual = PanelState.manual.copy(discNumber = n.coerceAtLeast(1))
                        }
                    }
                    Field(
                        label = Strings.key("manual_discs"),
                        value = d.totalDiscs.toString(),
                        modifier = Modifier.width(96.dp)
                    ) { v ->
                        v.filter { it.isDigit() }.take(2).toIntOrNull()?.let { n ->
                            PanelState.manual = PanelState.manual.copy(totalDiscs = n.coerceAtLeast(1))
                        }
                    }
                }

                Text(
                    text = Strings.key("manual_tracks") + " · ${d.tracks.size}",
                    fontSize = 11.sp,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp, bottom = 2.dp)
                )
                for (t in d.tracks) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    ) {
                        Text(
                            text = "%02d".format(t.number),
                            fontSize = 11.sp,
                            color = cs.onSurfaceVariant.copy(alpha = 0.8f),
                            modifier = Modifier.width(22.dp)
                        )
                        Field(
                            label = "",
                            value = t.title,
                            compact = true,
                            modifier = Modifier.weight(1.25f)
                        ) { v -> PanelState.manual = PanelState.manual.withTrack(t.number, title = v) }
                        Field(
                            label = "",
                            value = t.artist,
                            placeholder = Strings.key("manual_track_artist"),
                            compact = true,
                            modifier = Modifier.weight(1f)
                        ) { v -> PanelState.manual = PanelState.manual.withTrack(t.number, artist = v) }
                    }
                }
                Spacer(Modifier.height(6.dp))
            }

            HorizontalDivider(
                color = cs.outlineVariant.copy(alpha = 0.5f),
                modifier = Modifier.padding(top = 10.dp, bottom = 8.dp)
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { ConfigActions.fillManualFromCdText() }) {
                    Text(Strings.key("manual_fill_cdtext"), fontSize = 12.sp)
                }
                TextButton(onClick = { ConfigActions.chooseManualCover() }) {
                    Text(
                        if (PanelState.manualCover == null) Strings.key("manual_cover")
                        else Strings.key("manual_cover_chosen"),
                        fontSize = 12.sp
                    )
                }
                PanelState.manualCover?.let { p ->
                    Text(
                        text = p.substringAfterLast('\\').substringAfterLast('/'),
                        fontSize = 11.sp,
                        color = cs.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 180.dp)
                    )
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { PanelState.closeManual() }) {
                    Text(Strings.key("cancel"), fontSize = 12.sp)
                }
                Spacer(Modifier.width(6.dp))
                Button(
                    onClick = { ConfigActions.saveManualMeta() },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 18.dp,
                        vertical = 8.dp
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.height(34.dp)
                ) {
                    Text(Strings.key("manual_save"), fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun Header() {
    val cs = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = Strings.key("manual_title"),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = cs.onSurface
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = Strings.key("settings_close"),
                fontSize = 12.sp,
                color = cs.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { PanelState.closeManual() }
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
        Text(
            text = Strings.key("manual_hint"),
            fontSize = 11.sp,
            lineHeight = 15.sp,
            color = cs.onSurfaceVariant.copy(alpha = 0.85f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

/** 单行输入；[label] 为空时只画框（曲目行靠左侧轨号对齐）。 */
@Composable
private fun Field(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    compact: Boolean = false,
    onChange: (String) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Column(modifier = modifier) {
        if (label.isNotBlank()) {
            Text(label, fontSize = 11.sp, color = cs.onSurfaceVariant)
            Spacer(Modifier.height(2.dp))
        }
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            placeholder = {
                if (placeholder.isNotBlank()) {
                    Text(placeholder, fontSize = 12.sp, color = cs.onSurfaceVariant.copy(alpha = 0.6f))
                }
            },
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                fontSize = if (compact) 12.sp else 13.sp,
                lineHeight = if (compact) 16.sp else 18.sp
            ),
            shape = RoundedCornerShape(8.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = cs.onSurface,
                unfocusedBorderColor = cs.outlineVariant,
                cursorColor = cs.onSurface
            ),
            modifier = Modifier.fillMaxWidth().widthIn(min = 0.dp).heightIn(min = if (compact) 38.dp else 44.dp)
        )
    }
}

/** 顶部按钮用：没有候选且查过一轮时显示「手动填写元数据…」。 */
@Composable
internal fun manualButtonLabel(): String = Strings.key("manual_meta")

/** 供面板判断按钮用途（避免 UI 里重复布尔逻辑）。 */
internal fun showManualButton(): Boolean =
    PanelState.manualOpen || ManualEntry.buttonIsManual(
        candidatesEmpty = PanelState.candidates.isEmpty(),
        lookupTried = PanelState.lookupTried
    )
