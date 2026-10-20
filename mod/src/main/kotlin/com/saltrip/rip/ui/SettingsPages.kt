package com.saltrip.rip.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.saltrip.rip.ConfigActions
import com.saltrip.rip.settings.RipSettings

/**
 * 设置面板：左列 9 个二级页，右侧当前页的条目。
 *
 * 只有这一处定义「界面长什么样」，条目内容/顺序/显隐都来自 [SettingsSpec]——
 * 宿主配置页（`preference_config.json`）是同源的扁平镜像，两边由
 * [SettingsSpecTest] 对账，避免改了一边忘了另一边。
 */
@Composable
internal fun SettingsPanel() {
    val cs = MaterialTheme.colorScheme
    val s = PanelState.draft
    val page = SettingsSpec.page(PanelState.settingsPage)

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = Strings.key("settings"),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = { ConfigActions.closeSettings() }) {
            Text(Strings.key("settings_close"), style = MaterialTheme.typography.labelMedium)
        }
    }
    HorizontalDivider(color = cs.outlineVariant)

    Row(modifier = Modifier.fillMaxWidth().height(320.dp)) {
        // 左列：分类
        Column(
            modifier = Modifier
                .width(132.dp)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            for (p in SettingsSpec.pages) {
                val selected = p.id == page.id
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (selected) cs.onSurface.copy(alpha = 0.10f) else androidx.compose.ui.graphics.Color.Transparent,
                    modifier = Modifier.fillMaxWidth()
                        .clickable { PanelState.settingsPage = p.id }
                ) {
                    Text(
                        text = "${SettingsSpec.pages.indexOf(p) + 1}. ${p.title}",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (selected) cs.onSurface else cs.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        // 右列：当前页条目
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = page.title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = cs.onSurface
            )
            for (item in page.items) {
                if (!item.visibleWhen(s)) continue
                SettingRow(item, s)
            }
        }
    }

    HorizontalDivider(color = cs.outlineVariant)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = { ConfigActions.savePanelSettings() },
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                containerColor = cs.onSurface,
                contentColor = cs.surface
            )
        ) {
            Text(Strings.key("save"), style = MaterialTheme.typography.labelLarge)
        }
        TextButton(onClick = { ConfigActions.closeSettings() }) {
            Text(Strings.key("cancel"), style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
        }
    }
}

/** 一条设置：按 [SettingKind] 分派到具体控件。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingRow(item: SettingItem, s: RipSettings) {
    val cs = MaterialTheme.colorScheme
    val enabled = item.enabledWhen(s)
    when (item.kind) {
        SettingKind.SWITCH -> Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(labelOf(item), style = MaterialTheme.typography.bodySmall, color = cs.onSurface)
                if (item.hint.isNotBlank()) {
                    Text(
                        item.hint,
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Switch(
                checked = SettingsAccess.bool(s, item.key),
                onCheckedChange = { v -> PanelState.updateDraft { SettingsAccess.withBool(it, item.key, v) } },
                enabled = enabled
            )
        }

        SettingKind.LIST -> Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                labelOf(item),
                style = MaterialTheme.typography.bodySmall,
                color = if (enabled) cs.onSurface else cs.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.weight(1f)
            )
            ListDropdown(item, SettingsAccess.text(s, item.key), enabled) { v ->
                PanelState.updateDraft { SettingsAccess.withText(it, item.key, v) }
            }
        }

        SettingKind.TEXT -> TextRow(item, SettingsAccess.text(s, item.key), enabled)

        SettingKind.PATH -> Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                labelOf(item),
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurface,
                modifier = Modifier.width(96.dp)
            )
            Text(
                text = SettingsAccess.text(s, item.key).ifBlank { Strings.key("default_root") },
                style = MaterialTheme.typography.bodySmall,
                color = if (SettingsAccess.text(s, item.key).isBlank()) cs.onSurfaceVariant else cs.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { choosePath(item.key) }, enabled = enabled) {
                Text(Strings.key("choose"), style = MaterialTheme.typography.labelMedium)
            }
        }

        SettingKind.TEMPLATE -> TemplateRow(item, SettingsAccess.text(s, item.key))

        SettingKind.ACTION -> TextButton(onClick = { runAction(item.key) }) {
            Text(labelOf(item), style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** PATH 类项的「选择…」：目录还是文件。 */
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
        "chooseOutputRoot" -> ConfigActions.chooseOutputRoot()
        "chooseFlacExe" -> ConfigActions.chooseFlacExe()
        else -> Unit
    }
}

private fun labelOf(item: SettingItem): String =
    Strings.keyOr("opt_${item.key}", item.label.ifBlank { Strings.key("opt_${item.key}") })

@Composable
private fun ListDropdown(
    item: SettingItem,
    value: String,
    enabled: Boolean,
    onPick: (String) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    val current = item.options.firstOrNull { it.value == value }?.label
        ?: item.options.firstOrNull()?.label
        ?: value
    Box {
        TextButton(onClick = { open = true }, enabled = enabled) {
            Text(current, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (o in item.options) {
                DropdownMenuItem(
                    text = {
                        Text(
                            o.label,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = if (o.value == value) FontWeight.SemiBold else FontWeight.Normal
                        )
                    },
                    onClick = { open = false; onPick(o.value) }
                )
            }
        }
    }
}

@Composable
private fun TextRow(item: SettingItem, value: String, enabled: Boolean) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> PanelState.updateDraft { SettingsAccess.withText(it, item.key, v) } },
        singleLine = true,
        enabled = enabled,
        label = { Text(labelOf(item), style = MaterialTheme.typography.labelSmall) },
        placeholder = {
            if (item.placeholder.isNotBlank()) {
                Text(item.placeholder, style = MaterialTheme.typography.labelSmall)
            }
        },
        textStyle = MaterialTheme.typography.bodySmall,
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
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
private fun TemplateRow(item: SettingItem, value: String) {
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
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = { v ->
                PanelState.updateDraft { SettingsAccess.withText(it, item.key, v) }
                PanelState.editTarget = target
            },
            singleLine = true,
            label = { Text(labelOf(item), style = MaterialTheme.typography.labelSmall) },
            textStyle = MaterialTheme.typography.bodySmall,
            interactionSource = interaction,
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedBorderColor = cs.onSurface,
                unfocusedBorderColor = if (active) cs.onSurface else cs.outlineVariant,
                focusedLabelColor = cs.onSurface,
                unfocusedLabelColor = if (active) cs.onSurface else cs.onSurfaceVariant,
                cursorColor = cs.onSurface
            ),
            modifier = Modifier.fillMaxWidth().onFocusChanged {
                if (it.isFocused) PanelState.editTarget = target
            }
        )
        if (active) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                for ((token, label) in TemplateChips) {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = cs.surfaceVariant.copy(alpha = 0.9f),
                        modifier = Modifier.clickable { PanelState.appendToken(token) }
                    ) {
                        Text(
                            text = label,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = cs.onSurfaceVariant
                        )
                    }
                }
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = cs.surfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.clickable { PanelState.clearTarget() }
                ) {
                    Text(
                        text = Strings.key("clear"),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant
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
