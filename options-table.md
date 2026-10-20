# 盐专辑（Salt Audio Copy）配置界面选项统计表 v2（0.39.4）

> 0.39.4 起配置页为 **8 组 / 47 个设置键 / 13 个按钮**（已移除「导入编码器」相关选项）；面板内是同一份数据的 8 页二级导航。
> 本表由 `design/options_table.py` 从 `mod/src/main/resources/preference_config.json` 生成，与代码同步。
> 末列「改法（你填）」留空；要改就在那格写（行首加 `#` 表示删掉这一项）。

## 1 · 输出位置（7 设置 + 0 按钮）

| 序号 | 选项名（界面原文） | key | 控件 | 取值 / 格式 | 默认 | 作用 | 改法（你填） |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 输出根目录 | `outputRoot` | 输入 | 任意路径；空 = 系统「音乐」 | （空） | 抓轨产物根目录；面板里点这一行也能改 |  |
| 2 | 同名文件已存在时 | `overwritePolicy` | 单选 | 加 (2) 另存（suffix） / 覆盖（overwrite） / 跳过（skip） | suffix | 撞名策略 |  |
| 3 | 断点续抓 | `resume` | 开关 | 开 / 关 | 开 | 目标 WAV 已有则从断点继续 |  |
| 4 | 碟号（0=自动） | `discNumberOverride` | 输入 | 整数，0 = 用元数据 | `0` | 多碟套装的当前碟号，配 `%discnumber%` |  |
| 5 | 总碟数（0=自动） | `totalDiscsOverride` | 输入 | 整数，0 = 用元数据 | `0` | >1 且模板未写 `%discnumber%` 时自动加 CD1/CD2 子目录 |  |
| 6 | 多碟子目录模板 | `multiDiscFolder` | 输入 | 如 `CD%discnumber%` | `CD%discnumber%` | 总碟数 >1 时在专辑目录下再套一层 |  |
| 7 | 抓完弹出托盘 | `ejectAfterRip` | 开关 | 开 / 关 | 关 | 整盘抓完弹托盘，方便换盘 |  |

## 2 · 命名模板（3 设置 + 0 按钮）

| 序号 | 选项名（界面原文） | key | 控件 | 取值 / 格式 | 默认 | 作用 | 改法（你填） |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 8 | 专辑目录模板 | `folderTemplate` | 输入 | 如 `%albumartist%/%albumtitle%` | `%albumartist%/%albumtitle%` | 专辑目录结构；点框再点变量插入光标处 |  |
| 9 | 音轨文件名模板 | `fileTemplate` | 输入 | 如 `%tracknr2% %title%` | `%tracknr2% %title%` | 音轨文件名；点框再点变量插入光标处 |  |
| 10 | 封面文件名 | `coverFileName` | 输入 | 不含扩展名 | `cover` | 封面文件名；点框再点变量插入光标处 |  |

## 3 · 音频格式（4 设置 + 0 按钮）

| 序号 | 选项名（界面原文） | key | 控件 | 取值 / 格式 | 默认 | 作用 | 改法（你填） |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 11 | 输出格式 | `format` | 单选 | WAV（推荐）（wav） / FLAC（内置编码器）（flac） | wav | 决定走哪条写出链路 |  |
| 12 | 整盘镜像（单文件 + CUE） | `imageMode` | 开关 | 开 / 关 | 关 | 每轨抓好后拼成单 WAV + 绝对时间 CUE（占两倍临时空间） |  |
| 13 | FLAC 压缩等级 | `flacLevel` | 单选 | 0 最快（0） / 3（3） / 5 默认（5） / 8 最小（8） | 5 | 越高越慢、文件越小 |  |
| 14 | FLAC 编码后保留 WAV | `keepWav` | 开关 | 开 / 关 | 开 | 关掉则编码成功后删中间 WAV |  |

## 4 · 标签与附属文件（7 设置 + 0 按钮）

| 序号 | 选项名（界面原文） | key | 控件 | 取值 / 格式 | 默认 | 作用 | 改法（你填） |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 15 | 写入 FLAC 标签 | `writeTags` | 开关 | 开 / 关 | 开 | 标题/艺术家/专辑/轨号/年份/流派/ISRC |  |
| 16 | 把封面内嵌进 FLAC | `embedCover` | 开关 | 开 / 关 | 开 | 每个 FLAC 内嵌一张 front cover |  |
| 17 | 封面尺寸 | `coverSize` | 单选 | 原图（推荐）（0） / 500 px（500） / 1200 px（1200） | 0 | Cover Art Archive 的 front-<size> |  |
| 18 | 给 WAV 写标签 | `writeWavTags` | 开关 | 开 / 关 | 开 | 写 RIFF LIST/INFO |  |
| 19 | 生成 CUE | `writeCue` | 开关 | 开 / 关 | 开 | UTF-8 BOM + CRLF，含 REM DISCID/CDDBID |  |
| 20 | 在 CUE 里附上每轨 CRC | `annotateCueCrc` | 开关 | 开 / 关 | 开 | 把每轨 CRC 写进 CUE 的 REM，方便对账 |  |
| 21 | 生成抓轨日志 | `writeLog` | 开关 | 开 / 关 | 开 | 每轨 CRC32 / 重试 / 用时 + 设置摘要 |  |

## 5 · 元数据（9 设置 + 0 按钮）

| 序号 | 选项名（界面原文） | key | 控件 | 取值 / 格式 | 默认 | 作用 | 改法（你填） |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 22 | 联网获取元数据 | `fetchMetadata` | 开关 | 开 / 关 | 开 | 关闭则离线，用占位名 |  |
| 23 | 插入或换盘时自动查元数据 | `autoLookupOnInsert` | 开关 | 开 / 关 | 开 |  |  |
| 24 | HTTP 代理（可选） | `proxyUrl` | 输入 | 任意文本 | （空） |  |  |
| 25 | 读取 CD-TEXT | `useCdText` | 开关 | 开 / 关 | 开 | 盘上曲名压过网络数据，读不到静默跳过 |  |
| 26 | 优先选轨数一致的发行版 | `preferTrackCount` | 开关 | 开 / 关 | 开 | 同 DiscID 多发行版时先按轨数筛 |  |
| 27 | 候选序号 | `releaseIndex` | 输入 | 0 起整数 | `0` | 手选第几个候选；日志会列全部 |  |
| 28 | 手工元数据文件（可选） | `overrideFile` | 输入 | 路径；空 = 自动找 | （空） | 强制用指定 album.json |  |
| 29 | 抓过的盘优先用上次的元数据 | `reuseAlbumJson` | 开关 | 开 / 关 | 开 | 同盘再抓跳过联网 |  |
| 30 | 在专辑目录写入 album.json | `writeAlbumJson` | 开关 | 开 / 关 | 关 | 存元数据，供离线复用/手改 |  |

## 6 · 光驱与校验（12 设置 + 0 按钮）

| 序号 | 选项名（界面原文） | key | 控件 | 取值 / 格式 | 默认 | 作用 | 改法（你填） |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 31 | 指定光驱 | `driveLetter` | 单选 | 自动（第一台）（） / D:（D） / E:（E） / F:（F） / G:（G） / H:（H） / I:（I） / J:（J） / K:（K） / L:（L） / M:（M） / N:（N） / O:（O） / P:（P） / Q:（Q） / R:（R） / S:（S） / T:（T） / U:（U） / V:（V） / W:（W） / X:（X） / Y:（Y） / Z:（Z） |  | 多光驱时指定用哪台，避免抓错盘 |  |
| 32 | 读速上限 | `driveSpeed` | 单选 | 8x（约 1200 KB/s，推荐）（1200） / 16x（约 2400 KB/s）（2400） / 24x（约 3600 KB/s）（3600） / 4x（约 600 KB/s）（600） / 2x（约 300 KB/s）（300） / 不干预（全速，最快但抖）（0） | 1200 | 限速换稳定性；0 = 驱动器最高速 |  |
| 33 | 音频读命令 | `readCommand` | 单选 | 自动（推荐）（auto） / 标准 MMC（0xBE）（mmc） / MMC + C2（mmc-c2） / READ(10)（read10） / 厂家 0xD8（实验性）（d8） | auto | 驱动不认标准 READ CD 时的退路，auto 自动降级 |  |
| 34 | 双读校验（secure） | `secureMode` | 开关 | 开 / 关 | 开 | 面板内快速开关 |  |
| 35 | Test & Copy | `testAndCopy` | 开关 | 开 / 关 | 关 | 先只读一遍拿 CRC，再正式抓并逐轨比对，耗时约翻倍 |  |
| 36 | 出错恢复质量 | `errorRecovery` | 单选 | 低（快速）（low） / 中（推荐）（medium） / 高（苛刻）（high） | medium | 决定要求几次一致 |  |
| 37 | 单块最大重读次数 | `maxRetries` | 输入 | 整数 | `8` | 单块重读上限 |  |
| 38 | 重读间隔（毫秒） | `retryDelayMs` | 输入 | 整数 | `120` | 重读前等待，老驱动可加 |  |
| 39 | 采样级 offset | `offsetSamples` | 输入 | 整数，正数=丢开头若干采样 | `0` | 与 EAC read offset 对应 |  |
| 40 | C2 错误检查 | `useC2` | 开关 | 开 / 关 | 关 | 用 CDB 的 C2 位校验；不支持自动降级 |  |
| 41 | 击破驱动器缓存 | `defeatCache` | 开关 | 开 / 关 | 关 | 重读前读远端，让双读真的在验盘 |  |
| 42 | 防止中途换碟 | `guardDisc` | 开关 | 开 / 关 | 开 | 每轨前重读 TOC，盘变立即中止 |  |

## 7 · 间隙（4 设置 + 0 按钮）

| 序号 | 选项名（界面原文） | key | 控件 | 取值 / 格式 | 默认 | 作用 | 改法（你填） |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 43 | 探测 INDEX 00 间隙 | `detectGaps` | 开关 | 开 / 关 | 开 | 子通道 Q 往前扫最多 1 秒，只报告 |  |
| 44 | 间隙探测方法 | `gapMethod` | 单选 | 子通道 Q（EAC 默认）（q） / 波形静音（Test Gaps On Silence）（silence） | q | Q 法与波形静音法二选一（EAC 的 Test Gaps On Silence） |  |
| 45 | 间隙处理方式 | `gapMode` | 单选 | 附加到上一轨（EAC 默认）（append） / 归入下一轨（非标准）（prepend） / 丢弃间隙（leaveout） | append | 决定 INDEX 00 归属哪个文件 |  |
| 46 | 抓轨前预扫间隙 | `analyzeBeforeRip` | 开关 | 开 / 关 | 关 | 整盘先扫 INDEX 00，省逐轨重复读 Q |  |

## 8 · 诊断（1 设置 + 13 按钮）

| 序号 | 选项名（界面原文） | key | 控件 | 取值 / 格式 | 默认 | 作用 | 改法（你填） |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 47 | 只抓这些音轨 | `trackSelection` | 输入 | 如 `1-5,8`；空 = 整盘 | （空） | 只抓部分轨 |  |
| B1 | ① 扫描驱动器 | — | 按钮 | `ConfigActions.scanDrives` | — | 重新枚举光驱 |  |
| B2 | ①.2 抓轨计划预览（不写盘） | — | 按钮 | `ConfigActions.previewPlan` | — | 算出目录/文件名/预计耗时，不写盘 |  |
| B3 | ①.5 间隙预扫（只读） | — | 按钮 | `ConfigActions.analyzeDisc` | — | 整盘扫 INDEX 00（只读） |  |
| B4 | ①.3 探测可用读命令（只读） | — | 按钮 | `ConfigActions.probeReadCommands` | — | 逐条试读命令，报告哪些可用 |  |
| B5 | 只测不写（Test only） | — | 按钮 | `ConfigActions.testDisc` | — | 读完整张盘但不写文件 |  |
| B6 | ③ 仅查询元数据 | — | 按钮 | `ConfigActions.lookupMetadata` | — | 只联网查元数据并列出候选 |  |
| B7 | ③.5 导出元数据模板 | — | 按钮 | `ConfigActions.exportAlbumJson` | — | 导出可手改的元数据模板 |  |
| B8 | ④ 试抓第 1 轨 | — | 按钮 | `ConfigActions.ripFirstTrack` | — | 只抓第 1 轨验证链路 |  |
| B9 | 取消当前抓轨 | — | 按钮 | `ConfigActions.cancelRip` | — | 中止当前抓轨（已写文件保留） |  |
| B10 | 试写测试（中文路径） | — | 按钮 | `ConfigActions.probePath` | — | 在含中文的路径下试写一小段 |  |
| B11 | 恢复默认设置 | — | 按钮 | `ConfigActions.resetDefaults` | — | 把全部设置恢复默认 |  |
| B12 | 打开日志文件夹 | — | 按钮 | `ConfigActions.openLogFolder` | — | 打开日志所在文件夹 |  |
| B13 | 网络自检（音乐库 / CDDB） | — | 按钮 | `ConfigActions.testNetwork` | — | 并发探测 MusicBrainz / Cover Art / CDDB，判断是没记录还是网不通 |  |

## B 面板设置（8 页二级导航）

| 页 | 标题 | 项数 | 默认可见 |
| --- | --- | --- | --- |
| P1 | 输出位置 | 8 | 是（默认停这页） |
| P2 | 命名模板 | 3 | 是 |
| P3 | 音频格式 | 10 | 条件：FLAC / 外部编码器相关项按格式显隐 |
| P4 | 标签与附属文件 | 7 | 条件：按格式显隐 |
| P5 | 元数据 | 7 | 条件：关掉联网取元数据后隐藏 3 项 |
| P6 | 光驱与校验 | 12 | 条件：关掉安全双读后隐藏重试三项 |
| P7 | 间隙 | 4 | 条件：关掉间隙检测后隐藏三项 |
| P8 | 诊断 | 13 | 是 |

面板不再重复实现一套控件：点「设置」直接进这 8 页，改的是同一份 `RipSettings`。

## C 已移除 / 仍隐藏

| key | 状态 | 说明 |
| --- | --- | --- |
| `testMode` | 已移除 | 0.30.1 删掉「显示诊断工具」：诊断页动作常驻 |
| `appearance` | 已移除 | 0.29.7 删掉：面板跟随宿主主题，不给用户选颜色 |
| `includeGaps` | 已移除 | 被 `gapMode`（append/prepend/leaveout）取代 |

## D 与 v1 的差异（本版重排结果）

- 4 组 → 8 组；`ripAlbum`/`pauseRip` 从配置页移到面板（只在面板出现）。
- 新增两个原本隐藏的键：`multiDiscFolder`（多碟子目录模板）、`annotateCueCrc`（CUE 写 CRC）。
- 新增按钮「打开日志文件夹」；`chooseOutputRoot`/`chooseFlacExe` 并入各自的行。
- 51 键 = 原 48 + 新增 3（multiDiscFolder / annotateCueCrc / proxyUrl）；15 按钮 = 原 15 - 移到面板的 2（ripAlbum/pauseRip）+ 新增 2（openLogFolder / testNetwork）。

