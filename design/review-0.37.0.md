# 0.37.0 发布前自测与代码审查报告

范围：计划 A–F（基线、编码路径可测化、非默认选项矩阵、反馈审计、代码审查、手动清单）。
基线：0.36.5（399 tests）→ 本次 0.37.0（**403 tests / 0 failed / 12 skipped / 68 个测试类**）。

---

## 一、本次修掉的真 bug（都会影响用户）

| # | 位置 | 现象 | 修法 |
|---|---|---|---|
| 1 | `album/Writers.kt` `CueWriter.buildImage` | 整盘镜像的 CUE 里 `TITLE`/`PERFORMER` 值末尾多一个 `)`（`TITLE "x)"`），播放器显示脏名 | 三处 `\"")` 改回 `\"")` |
| 2 | `settings/RipSettings.kt` `annotateCueCrc` | 设置项存在、能存能读，但**代码里从没被引用**：CUE 永远没有 CRC 行 | `CueWriter.build/buildImage` 加 `annotateCrc` 参数，`AlbumRipper` 传 `settings.annotateCueCrc` |
| 3 | `album/AlbumRipper.kt` 镜像 + FLAC | 转成 flac 并删 WAV 后，`AlbumRipResult.tracks[].file` 仍指向**已删除的 WAV**，「打开目录/复制路径」指向空文件 | flac 成功后把 `results[k].file` 全部改指 `flacTarget` |
| 4 | `album/AlbumRipper.kt` FLAC 标签 | 无可联网元数据时，标签里没有 `ALBUM`/`ARTIST`（目录叫「未知专辑」但标签是空的），播放器把这些文件散成「未知专辑」 | 传已解析的 `albumTitle`/`albumArtist`/`trackArtist` |
| 5 | `album/AlbumRipper.kt` `notes` | FLAC 编码失败、CUE/日志/封面写不出来时只进 `notes`，UI 只显示绿色「抓轨完成」 | 新增 `warnings`（`AlbumRipResult.warnings`），UI 有 warnings 时改黄字、状态行写「已抓完（N 项需要留意）」、detail 列前 3 条 |
| 6 | `album/AlbumRipper.kt:491` | 拼接失败的 note 里写的是字面量 `\${it.message}`（转义写错），用户看到的是占位符 | 改成真正的 `${it.message}` |
| 7 | `album/AlbumRipper.kt` 跳过分支 | 「同名文件已存在→跳过」直接 `continue`，不计进度，进度条永远差一截 | 跳过时也累加 `doneBase` |
| 8 | `album/AlbumRipper.kt` 日志/元数据副本 | 写 `.log`、`album.json` 失败只写调试日志，用户完全不知道 EAC 日志没生成 | 一并进 `warnings` |
| 9 | `settings/RipSettings.kt` `save()` | 返回 `Unit`，宿主配置写失败时调用方照样弹「设置已保存」→ 重启后设置丢失 | 改为 `Boolean`；`ConfigActions.persist()` 统一处理，失败弹红字「设置没能写回磁盘」 |
| 10 | `settings/RipSettings.kt` `load(force=true)` | 宿主配置不可用时忽略内存缓存、直接返回默认值 → 保存后立刻 force 读像是「设置没生效」 | `h == null` 时沿用 `cached ?: defaults` |
| 11 | `ConfigActions.kt` `saveBool/saveTemplates/toggleSecureMode/toggleMetadata/selectDrive/resetDefaults/toggleTestMode` | 全部无视写回结果 | 统一走 `persist()` |
| 12 | 注释/缩进 | `RipSettings.kt` 有 3 处历史脚本留下的错缩进、`AlbumRipper.kt` 有重复三行注释、`ConfigActions.kt` 有一个孤儿 KDoc | 清理 |

## 二、审查后确认「本来就是对的」（不需要改）

- **安全抓轨**：`SecureRip.policyFor` 的 low/medium/high 映射（`Policy(1,1)` / `Policy(3,(maxRetries+1).coerceIn(2,82))` / `Policy(5,82)`）与 EAC 的「读不动就多读几遍 + 多数表决」一致；`MAX_TOTAL_READS=82` 有上界，不会死循环。
- **偏移校正**：`PcmOffset.apply` 在写盘后按样本数平移并补静音，`offsetSamples=0` 时完全跳过。
- **间隙**：`DetectGaps` + 三种 `gapMode`；`gapMode != append` 时自动跳过整盘镜像（边界不连续）。
- **续抓**：`resume` 用已有 WAV 的 `data` 长度推算扇区，大小不合法时重新抓整轨。
- **换盘保护**：`guardDisc` 在每轨前重读 TOC，变了就 `cancelled = true` 中止（写进 warnings）。
- **空间预检**：`FreeSpace.check` 在写第一个字节前算够不够（含 `keepWav` 的额外占用），不够直接抛错。
- **覆盖策略**：`Overwrite.resolve` 的 suffix/overwrite/skip 三态与文案一致。
- **模板**：`TemplateEngine.expandToSegments` 会清 Windows 非法字符、`%tracknr1/2/3%` 与 `%discnumber%`（多碟自动 `CD1/CD2`）都对。
- **元数据缓存**：`DiscCache.path(root, discId)` 命中即离线可用；`MetaOverride.coverSibling` 支持 jpg/jpeg/png/webp/bmp/gif。
- **多语言**：`StringsAndDrivesTest.threeLanguagesCoverSameKeys` 保证三张表键集合一致；`SettingsTextTest` 保证 51 个设置项与 15 个动作都有中文标签、不再出现 `opt_xxx`。

## 三、选项覆盖矩阵（51 键 / 15 按钮）

| 分类 | 键 | 自动化证据 |
|---|---|---|
| 输出与命名 | `outputRoot` `effectiveRoot` `folderTemplate` `fileTemplate` `coverFileName` `multiDiscFolder` `discNumberOverride` `totalDiscsOverride` | `RipPlanTest`、`TemplateEngineTest`、`OptionMatrixTest.*DiscSubfolder`、`coverFileNameSettingControlsWrittenCover` |
| 覆盖与续抓 | `overwritePolicy` `resume` | `OverwriteTest`、`OptionMatrixTest.overwritePolicy*`、`ResumeTest` |
| 抓轨核心 | `secureMode` `useC2` `defeatCache` `errorRecovery` `maxRetries` `retryDelayMs` `testAndCopy` `guardDisc` `offsetSamples` `readCommand` `driveLetter` `driveSpeed` `ejectAfterRip` | `SecureRipTest`、`C2Test`、`EtaTest`、`OptionMatrixTest.errorRecoveryLevelsMapToStablePolicies`、`SettingsSpecTest`（映射） |
| 格式与编码 | `format` `flacExe` `flacLevel` `keepWav` `encoderExe` `encoderArgs` `encoderExt` `imageMode` | `FlacFormatTest`（17 例）、`OptionMatrixTest.flacLevel*/customEncoderArgs*` |
| 附属文件 | `writeCue` `writeLog` `annotateCueCrc` `writeAlbumJson` `writeTags` `writeWavTags` `embedCover` `coverSize` | `CueWriterTest`、`WavTagsTest`、`FlacMetaTest`、`OptionMatrixTest.annotateCueCrc*/writeWavTags*` |
| 元数据 | `fetchMetadata` `autoLookupOnInsert` `proxyUrl` `useCdText` `overrideFile` `releaseIndex` `preferTrackCount` | `AutoLookupTest`、`HttpProxyTest`、`MetadataTest`、`NetAndCacheTest`、`OptionMatrixTest.overrideFile*` |
| 间隙 | `detectGaps` `gapMethod` `gapMode` `analyzeBeforeRip` `trackSelection` | `GapDetectorTest`、`SilenceGapDetectorTest`、`OptionMatrixTest.silenceGapMethod*`、`TrackSelectionTest` |
| 诊断 | `testMode` + 15 个按钮（扫描/预扫/计划/读命令/诊断包/album.json/网络自检/日志目录…） | `SettingsSpecTest.actionsExistOnConfigActions`（反射校验 15 个静态方法）+ 人工清单 G8 |

**仍只能人工验**（已在清单里）：真机限速与噪音、真 flac.exe、资源管理器标签显示、宿主主题与语言、真盘上 8x 的 ETA 抖动、清空光驱/拔盘/磁盘满。

## 四、剩余风险

1. **AccurateRip 未实现**（协议私有 + 需采样级 offset 标定），只做本地双读 / Test&Copy。
2. **WAV 的 `LIST/INFO` 只能存 ANSI**：中日韩之外的字符在资源管理器会变 `?`；用 ID3v2 通道的播放器正确。
3. **设置页只有中文**：宿主插件配置不支持按语言切 JSON。
4. **`readCommand` 探测后会把运行时偏好重置成 `auto`**（不写设置）——影响仅限本次会话，已知不改。
5. **`coverSize` 只影响 Cover Art Archive 的抓取尺寸**，不影响本地已导入的封面。

## 五、结论

- 自动化：403 tests 全绿，覆盖默认路径 + 非默认选项矩阵（含 FLAC/外部编码器/镜像/多碟/CUE CRC/覆盖策略/容错等级）。
- 代码审查：修掉 12 项真问题，其中 5 项会直接让用户看到错误结果（CUE 脏名、CRC 选项无效、指向已删文件、标签缺专辑、失败被绿字掩盖）。
- 待办：用户按 `manual-test-checklist.md` v2 跑真机项目 → 回报 → 修 → 再询问是否出 1.0.0。
