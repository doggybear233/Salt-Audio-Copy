# 1.0.0 名称统一与三语文案复查

日期：2026-10-21

## 1. 名称

| 位置 | 之前 | 现在 |
|---|---|---|
| 插件清单 `PluginName`（宿主「模组管理」显示） | `盐专辑 (Salt Audio Copy)` | `Salt Audio Copy` |
| `PluginDescription` | 中文长句 | `Professional CD ripping: SPTI, secure re-reads, CUE and log, metadata and cover art.` |
| 面板标题 · 中文 | 盐专辑 | 盐专辑（不变） |
| 面板标题 · 英文 | Salt Audio Copy | Salt Audio Copy（不变） |
| 面板标题 · 日文 | ソルト・オーディオコピー | **Salt Audio Copy** |

**为什么清单名不能用中文**：`spmod` 的 `SpmodExtension` 只有单个 `PluginName` 字段（无本地化入口）；打包时 `java.util.jar.Manifest` 按**字节**每 72 字节折行，中文 UTF-8 会被从字符中间切断（实测 `…\xe5\x8f\x8c\xe8\r\n \xaf\xbb…`），宿主按行解码必然乱码。因此清单与描述固定 ASCII，界面内的名字按语言走 `Strings`。

## 2. 复查范围与方法

- 范围：`messages-i18n.json`（运行期提示）、`options-i18n.json`（设置页 45 键 + 14 动作）、`Strings.kt`（面板按钮/标题）、`preference_config.json`（宿主页文案与选项值）、`SettingsSpec.kt` 的标签与 hint。
- 自动检查：
  1. 占位符一致性——每条 `%s` 在 zh / en / ja 中数量相等；
  2. 语种污染——en 中不得出现 CJK；ja 与 zh 不得同源；ja 中不宜出现简体字；
  3. 覆盖——`MessageCoverageTest` 扫 `Strings.message|fmt("…")` 字面量，逐条断言有 en/ja；`I18nTest` 断言每个设置键都有 en/ja；
  4. 死键——反向扫 `messages-i18n.json` 的键是否仍被代码引用。
- 结论：占位符 0 处不一致；en 无 CJK 残留；日文侧命中 4 处「技术串共用」（`曲名`、`CUE：%s`、`← 不足`、`%s（ASC …）`），判定可接受（术语原样保留）。

## 3. 修掉的问题

| # | 问题 | 处理 |
|---|---|---|
| 1 | 设置页按钮名与提示里提到的按钮名不一致（`Rip album` vs 实际 `Rip disc`；`Fill in metadata…` vs `Enter metadata manually…`） | 提示统一改用实际标签 |
| 2 | `errorRecovery` 说明仍写「3 次一致」，而 0.41.1 起 medium 是「读两次逐字节比对」 | 三处文案重写 |
| 3 | 限速 `0` 档写「不干预（全速）」，但 0.41.0 起 0 会下发 7056 KB/s | 改「不限速（请求全速，最快但抖）」+ hint 说明 |
| 4 | `markRipped` 的「记录、CUE、日志都已写好」用词含糊 | 改「音轨、CUE、日志都已写好…」 |
| 5 | 英文覆盖提示语法错误（`(To replace them, set Settings → … to Overwrite)`） | 重写 |
| 6 | 英文 `settings_close` 只写 `Hide` | 改 `Hide settings` |
| 7 | 17 条死键（已删功能：外部编码器、flac.exe 选择器、输出目录选择器、「默认音乐库」、旧指标行等） | 删除，entries 330 → 314 |

## 4. 有意保留中文的地方

- **EAC 日志正文**（`Writers.kt`）：贴到论坛/issue 的原始记录，保持 EAC 惯例英文标签 + 中文备注。
- **等宽表格**（`RipPlan.describe` 音轨表、`DiscAnalysis` 间隙表、`DiscTest` 结果表）：翻译会破坏列对齐。
- **宿主「模组管理」页**：宿主渲染 `preference_config.json`，一份文件一种语言，插件无法按语言切换；面板内的设置页是本地化的完整版本。

## 5. 验证

- `gradle clean test plugin --rerun-tasks` → 438 tests / 0 failed / 11 skipped。
- 包 `plugin-com.saltrip.saltaudiocopy-1.0.0.spmod`，1,219,838 B；`classes/META-INF/MANIFEST.MF` 全 ASCII；`preference_config.json` 8 组 / 45 键 / 14 按钮；`messages-i18n.json` 314 条；`options-i18n.json` en/ja 各 59 条。
