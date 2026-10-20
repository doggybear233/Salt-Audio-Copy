package com.saltrip.rip.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saltrip.rip.ConfigActions
import com.saltrip.rip.settings.RipSettings

/**
 * 设置面板：左列分类，右侧当前页的条目。
 *
 * 只有这一处定义「界面长什么样」，条目内容/顺序/显隐来自 [SettingsSpec]，
 * 文案（title/summary）来自 [OptionText]（即宿主那份 `preference_config.json`）——
 * 两边同源，[SettingsSpecTest] 负责对账，避免改了一边忘了另一边。
 *
 * 排版刻意全部用显式 `sp` + 固定行距，不跟随宿主 typography：
 * 宿主的文字样式随主题变化，中文在 `bodySmall`/`labelSmall` 之间跳会让同一列
 * 看起来「又粗又细」。
 */
@Composable
internal fun SettingsPanel() {
    val cs = MaterialTheme.colorScheme
    val s = PanelState.draft
    val page = SettingsSpec.page(PanelState.settingsPage)
    val visible = page.items.filter { it.visibleWhen(s) }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = cs.surface,
        border = BorderStroke(1.dp, cs.outlineVariant.copy(alpha = 0.75f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // ── 标题栏 ────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = Strings.key("settings"),
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.Medium,
                    color = cs.onSurface
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = Strings.key("settings_close"),
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { ConfigActions.closeSettings() }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
            HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.6f))

            // ── 主体：左导航 + 右条目 ─────────────────────────────────
            Row(modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp, max = 292.dp)) {
                Column(
                    modifier = Modifier
                        .width(146.dp)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 6.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    SettingsSpec.pages.forEachIndexed { index, p ->
                        val selected = p.id == page.id
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(7.dp))
                                .background(if (selected) cs.onSurface.copy(alpha = 0.07f) else Color.Transparent)
                                .clickable { PanelState.settingsPage = p.id }
                                .padding(end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                Modifier
                                    .padding(vertical = 9.dp)
                                    .width(2.dp)
                                    .height(14.dp)
                                    .clip(RoundedCornerShape(1.dp))
                                    .background(if (selected) cs.onSurface else Color.Transparent)
                            )
                            Text(
                                text = "${index + 1}. ${p.title}",
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                                color = if (selected) cs.onSurface else cs.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }

                VerticalDivider(color = cs.outlineVariant.copy(alpha = 0.6f))

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(start = 14.dp, end = 16.dp, top = 10.dp, bottom = 10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = page.title,
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            fontWeight = FontWeight.Medium,
                            color = cs.onSurface
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "${visible.size} 项",
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                            color = cs.onSurfaceVariant.copy(alpha = 0.8f)
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    visible.forEachIndexed { index, item ->
                        if (index > 0) {
                            HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.4f))
                        }
                        SettingRow(item, s)
                    }
                }
            }

            HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.6f))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Button(
                    onClick = { ConfigActions.savePanelSettings() },
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = cs.onSurface, contentColor = cs.surface)
                ) {
                    Text(Strings.key("save"), fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
                }
                TextButton(onClick = { ConfigActions.closeSettings() }) {
                    Text(
                        Strings.key("cancel"),
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        color = cs.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** 一条设置：按 [SettingKind] 分派到具体控件。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingRow(item: SettingItem, s: RipSettings) {
    val cs = MaterialTheme.colorScheme
    val enabled = item.enabledWhen(s)
    val label = OptionText.label(item.key, item.label)
    val hint = OptionText.hint(item.key, item.hint)

    when (item.kind) {
        SettingKind.SWITCH -> Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LabelColumn(label, hint, enabled, Modifier.weight(1f))
            Switch(
                checked = SettingsAccess.bool(s, item.key),
                onCheckedChange = { v -> PanelState.updateDraft { SettingsAccess.withBool(it, item.key, v) } },
                enabled = enabled,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = cs.onSurface,
                    checkedThumbColor = cs.surface,
                    uncheckedTrackColor = cs.outlineVariant,
                    uncheckedThumbColor = cs.surface
                ),
                modifier = Modifier.padding(start = 12.dp)
            )
        }

        SettingKind.LIST -> Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LabelColumn(label, hint, enabled, Modifier.weight(1f))
            ListDropdown(item, SettingsAccess.text(s, item.key), enabled) { v ->
                PanelState.updateDraft { SettingsAccess.withText(it, item.key, v) }
            }
        }

        SettingKind.TEXT -> Column(modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
            Text(
                text = label,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = cs.onSurface.copy(alpha = if (enabled) 1f else 0.45f)
            )
            if (hint.isNotBlank()) {
                Text(
                    text = hint,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = cs.onSurfaceVariant.copy(alpha = 0.85f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 1.dp)
                )
            }
            Spacer(Modifier.height(4.dp))
            PlainField(
                value = SettingsAccess.text(s, item.key),
                placeholder = item.placeholder,
                enabled = enabled,
                onChange = { v -> PanelState.updateDraft { SettingsAccess.withText(it, item.key, v) } }
            )
        }

        SettingKind.PATH -> Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LabelColumn(label, hint, enabled, Modifier.weight(1f))
            TextButton(onClick = { choosePath(item.key) }, enabled = enabled) {
                Text(Strings.key("choose"), fontSize = 12.sp, lineHeight = 16.sp, color = cs.onSurfaceVariant)
            }
        }

        SettingKind.TEMPLATE -> TemplateRow(item, label, hint, SettingsAccess.text(s, item.key))

        SettingKind.ACTION -> Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { runAction(item.key) }, contentPadding = PaddingValues(horizontal = 2.dp)) {
                Text(label, fontSize = 13.sp, lineHeight = 18.sp, color = cs.onSurface)
            }
            if (hint.isNotBlank()) {
                Text(
                    text = hint,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    color = cs.onSurfaceVariant.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun LabelColumn(label: String, hint: String, enabled: Boolean, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Column(modifier) {
        Text(
            text = label,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            color = cs.onSurface.copy(alpha = if (enabled) 1f else 0.45f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (hint.isNotBlank()) {
            Text(
                text = hint,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                color = cs.onSurfaceVariant.copy(alpha = if (enabled) 0.85f else 0.4f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 1.dp)
            )
        }
    }
}

/** 路径项的「选择…」：目录还是文件。 */
private fun choosePath(key: String) {
    when (key) {
        "outputRoot" -> ConfigActions.chooseOutputRoot()
        "flacExe" -> ConfigActions.chooseFlacExe()
        "overrideFile" -> ConfigActions.chooseOverrideFile()
        else -> Unit
    }
}

private fun runAction(name: String) {
    when (name) {
        "scanDrives" -> ConfigActions.scanDrives()
        "previewPlan" -> ConfigActions.previewPlan()
        "analyzeDisc" -> ConfigActions.analyzeDisc()
        "probeReadCommands" -> ConfigActions.probeReadCommands()
        "testDisc" -> ConfigActions.testDisc()
        "lookupMetadata" -> ConfigActions.lookupMetadata()
        "exportAlbumJson" -> ConfigActions.exportAlbumJson()
        "ripFirstTrack" -> ConfigActions.ripFirstTrack()
        "cancelRip" -> ConfigActions.cancelRip()
        "probePath" -> ConfigActions.probePath()
        "resetDefaults" -> ConfigActions.resetDefaults()
        "openLogFolder" -> ConfigActions.openLogFolder()
        "testNetwork" -> ConfigActions.testNetwork()
        "chooseOutputRoot" -> ConfigActions.chooseOutputRoot()
        "chooseFlacExe" -> ConfigActions.chooseFlacExe()
        else -> Unit
    }
}

@Composable
private fun ListDropdown(
    item: SettingItem,
    value: String,
    enabled: Boolean,
    onPick: (String) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    val current = item.options.firstOrNull { it.value == value }?.label
        ?: item.options.firstOrNull()?.label
        ?: value
    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(7.dp))
                .clickable(enabled = enabled) { open = true }
                .padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = current,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = if (enabled) cs.onSurface else cs.onSurfaceVariant.copy(alpha = 0.5f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 150.dp)
            )
            Text(
                text = "▾",
                fontSize = 10.sp,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp)
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (o in item.options) {
                DropdownMenuItem(
                    text = {
                        Text(
                            text = o.label,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            fontWeight = if (o.value == value) FontWeight.Medium else FontWeight.Normal
                        )
                    },
                    onClick = { open = false; onPick(o.value) }
                )
            }
        }
    }
}

/** 文本输入：不用 OutlinedTextField 的浮动 label——那种写法把 label 挤成 11sp 飘在边框上。 */
@Composable
private fun PlainField(
    value: String,
    placeholder: String,
    enabled: Boolean,
    onChange: (String) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        enabled = enabled,
        placeholder = {
            if (placeholder.isNotBlank()) {
                Text(placeholder, fontSize = 12.sp, color = cs.onSurfaceVariant.copy(alpha = 0.6f))
            }
        },
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 18.sp),
        shape = RoundedCornerShape(8.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = cs.onSurface,
            unfocusedBorderColor = cs.outlineVariant,
            cursorColor = cs.onSurface
        ),
        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)
    )
}

/**
 * 模板块：点进输入框后，下面的变量块才会写入**这个**框。
 *
 * [PanelState.editTarget] 是全局单值，所以同一屏只能有一个「正在编辑」的框——
 * 这也是为什么未选中时变量块是灰的，而不是默认猜一个目标。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TemplateRow(item: SettingItem, label: String, hint: String, value: String) {
    val cs = MaterialTheme.colorScheme
    val target = when (item.key) {
        "folderTemplate" -> 0
        "fileTemplate" -> 1
        else -> 2
    }
    val active = PanelState.editTarget == target
    val interaction = remember { MutableInteractionSource() }
    LaunchedEffect(interaction) {
        interaction.interactions.collect {
            if (it is PressInteraction.Release) PanelState.editTarget = target
        }
    }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        Text(
            text = label,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            color = cs.onSurface
        )
        if (hint.isNotBlank()) {
            Text(
                text = hint,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                color = cs.onSurfaceVariant.copy(alpha = 0.85f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 1.dp)
            )
        }
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = value,
            onValueChange = { v ->
                PanelState.updateDraft { SettingsAccess.withText(it, item.key, v) }
                PanelState.editTarget = target
            },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 18.sp),
            shape = RoundedCornerShape(8.dp),
            interactionSource = interaction,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = cs.onSurface,
                unfocusedBorderColor = if (active) cs.onSurface.copy(alpha = 0.6f) else cs.outlineVariant,
                cursorColor = cs.onSurface
            ),
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).onFocusChanged {
                if (it.isFocused) PanelState.editTarget = target
            }
        )
        if (active) {
            Spacer(Modifier.height(6.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                for ((token, chipLabel) in TemplateChips) {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = cs.surfaceVariant,
                        modifier = Modifier.clickable { PanelState.appendToken(token) }
                    ) {
                        Text(
                            text = chipLabel,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                            color = cs.onSurfaceVariant
                        )
                    }
                }
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = Color.Transparent,
                    modifier = Modifier.clickable { PanelState.clearTarget() }
                ) {
                    Text(
                        text = Strings.key("clear"),
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        color = cs.onSurfaceVariant.copy(alpha = 0.8f)
                    )
                }
            }
        }
    }
}

/**
 * 模板变量块：只放用户真的会用的。
 *
 * 刻意**没有** `%tracknr1%`：`01` 与 `1` 的区别只在「要不要补零」，
 * 两个块摆在一起只会让人来问「这俩差在哪」——补零请用 `%tracknr2%`。
 */
private val TemplateChips = listOf(
    "%albumartist%" to "专辑艺术家",
    "%albumtitle%" to "专辑名",
    "%year%" to "年份",
    "%genre%" to "流派",
    "%tracknr2%" to "轨号 01",
    "%title%" to "曲名",
    "%artist%" to "曲目艺术家",
    "%discnumber%" to "碟号",
    "%totalcds%" to "总碟数",
    "%cddbid%" to "CDDB",
    "%discid%" to "DiscID"
)
