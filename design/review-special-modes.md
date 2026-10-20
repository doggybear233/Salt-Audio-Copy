# 特殊模式（清单 I 区）代码审查

审查对象：0.41.2 的整盘镜像、间隙模式联动、Test&Copy、多碟目录、只抓部分音轨、
同名文件策略、抓完弹托盘、限速、代理快速失败。结论按「代码能证明的」写，
真机才能确认的部分标为「仍需实测」。

## I1 整盘镜像（WAV/FLAC）

- 拼接用 `ImageSplicer.splice`：逐轨 WAV 已是 44.1k/2ch/16bit，头 44 字节，
  只搬运 data 段并重写头；任一段格式不符直接抛错，不会产生错位镜像。
- CUE 用 `CueWriter.buildImage`，单 `FILE`，INDEX 为相对镜像起点的时间。
- **修掉的 bug**：镜像起点原来固定取 `toc.tracks.first().lba`（整盘第 1 轨）。
  只抓部分音轨且第 1 轨不在选择里时，所有 INDEX 会整体偏移。改为
  `results.first().startLba`，并加 `SpecialModesTest.imageCueUsesFirstSelectedTrackAsOrigin`。
- FLAC：编码成功后把 `results[*].file` 指向 flac、`imageRel` 也换成 flac，
  未保留 WAV 时删 WAV；编码失败保留 WAV 并进 `warnings`（会出现在完成提示）。

## I2 间隙模式与镜像联动

- `imageAllowed = imageMode && gapMode == "append"`：其它两种模式下音轨边界不连续，
  跳过镜像、逐轨文件不动。文案已从「只进 notes」改为 `warnings`，
  完成提示会带出「已跳过整盘镜像」。

## I3 Test & Copy

- 测试通道先用 `writeFile = false` 跑一遍拿每轨 CRC，复制通道比对；
  不一致时 `warn` + 立即重抓一次，仍不一致再 `warn`（两种情况都会进完成提示）。
- **修掉的 bug**：测试通道读失败（坏道）会抛出并终止整次抓轨。
  改为 `runCatching`，失败只丢该轨的 CRC 参照并 `warn`，复制继续。

## I4 多碟目录

- `totalDiscs > 1 && folderTemplate 不含 %discnumber%` 时追加一层目录。
- **修掉的 bug**：`multiDiscFolder` 为空时 `expandToSegments("")` 什么都不追加，
  两张碟会混进同一目录。现在空值退回 `CD%discnumber%`，并有
  `OptionMatrixTest.blankMultiDiscFolderFallsBackToDefault` 守着。

## I5 只抓部分音轨

- `TrackSelection.parse` 支持 `1-3,5`、`1-`、多余逗号/空格容错；越界片段忽略。
- 一轨都没匹配上时 `require(selected.isNotEmpty())` 抛「没有选中任何音轨（规则…）」，不会静默成功。
- 与镜像组合的 INDEX 偏移问题见 I1。

## I6 同名文件策略

- `Overwrite.resolve` 三态：`suffix` 追加 ` (n)`、`overwrite` 直接写、`skip` 返回 null。
- 续抓（resume）时半截文件按预期字节数原位续写，不会被改成 `(2)`。
- **修掉的体验问题**：跳过某轨原来只写 `notes`，完成提示仍是「已抓完 N 轨」，
  看起来像丢了一轨。现在跳过会进 `warnings` 并明确写「该轨没有抓」。

## I7 抓完弹托盘

- 全部落盘后才 `eject()`；失败原来只进 `notes`，现在进 `warnings`；
  没有任何产出（取消/全跳过）时不弹。

## I8 限速

见 0.41.2：限速在每条读句柄上重新下发；面板显示「每块校验 N 次」，
有效速度 = 盘速 ÷ N。

## I9 代理快速失败

- `Http.configure` 每次查询前重设代理；连接超时 4 秒、读 20 秒；
  `UnknownHostException` / 连接超时**不重试**；CDDB 每个镜像连接超时 2.5 秒。
- 填了代理但解析不了（缺端口等）时记日志并走直连，不会静默卡住。

## 仍需真机确认（代码无法代替）

| 项 | 原因 |
|---|---|
| I1 | 镜像能否在播放器里正确分轨、CUE 的 INDEX 是否被识别 |
| I3 | 故意用划伤盘制造 CRC 不一致，看是否重抓并提示 |
| I7 | 该光驱是否支持 `START STOP UNIT` 弹出 |
| I9 | 具体网络环境下「几秒内失败」的实际耗时 |

## 残留风险

- Test&Copy 会让整盘读两遍，速度减半，这是模式本身的代价（EAC 也一样）。
- 镜像 + FLAC + 不保留 WAV：若编码中途失败，WAV 会留下，属于有意的兜底，不是泄漏。
- 多碟判定只看 `totalDiscs`，不校验碟号是否重复；同一 `%discnumber%` 抓两次会按覆盖策略合并。
