# 盐专辑（Salt Audio Copy）配置界面选项统计表

版本基准：0.29.9。共两处可配置界面 + 若干隐藏键。

- **A. 宿主配置页**：宿主「设置 → 插件 → 盐专辑」里渲染，来源 `mod/src/main/resources/preference_config.json`，4 个分组、**63 项**（48 个设置项 + 15 个按钮动作）。 A4分组默认只显示测试模式，如果测试模式为打开状态才显示其他选项
- **B. 面板设置块**：CD 工具箱面板里点「设置」展开，**5 个控件 + 4 个按钮**，其中 5 个控件与 A 重复（写同一批 key）。
- **C. 隐藏键**：代码在用、界面上没有入口，共 4 个。

控件类型说明：`开关` = switch；`单选` = list（宿主下拉/列表，只能选一个）；`输入` = edittext（**数字也是输入框**，宿主没有数字控件）；`动作` = button（点了就执行，不存值）。

---

## A1 分组「抓轨」（7 项）

| 序号 | 选项名（界面原文） | key | 控件 | 取值 / 格式 | 默认 | 作用 | 改法（你填） |
|---|---|---|---|---|---|---|---|
| 1 | 指定光驱 | driveLetter | 单选 | 自动（第一台）/ D: … Z: | 自动（空） | 多光驱时指定用哪台，避免抓错盘 |  |
| 2 | 读速上限 | driveSpeed | 单选 | 不干预(0) / 1200 / 600 / 300 KB/s | 0 | 限速换稳定性；0 = 驱动器最高速 |  |
| 3 | 音频读命令 | readCommand | 单选 | auto / mmc / mmc-c2 / read10 / d8 | auto | 驱动不认标准 READ CD 时的退路，auto 自动降级 |  |
#| 4 | 只抓这些音轨 | trackSelection | 输入 | 如 `1-5,8`；空 = 整盘 | （空） | 只抓部分轨 |  |
#| 5 | ② 抓取整张专辑 | — | 动作 | `ConfigActions.ripAlbum` | — | 取元数据 → 命名 → 逐轨抓 → 封面/CUE/日志 |  |
| 6 | 抓完弹出托盘 | ejectAfterRip | 开关 | 开 / 关 | 关 | 整盘抓完弹托盘，方便换盘 |  |
#| 7 | 暂停 / 继续 | — | 动作 | `ConfigActions.pauseRip` | — | 中途暂停/继续（只影响写盘节奏） |  |

| (迁移自A2，插入A1，位置由你建议) | 抓轨前预扫间隙 | analyzeBeforeRip | 开关 | 开 / 关 | 关 | 整盘先扫 INDEX 00，省逐轨重复读 Q |  |
| (迁移自A2，插入A1，位置由你建议) | 写入 FLAC 标签 | writeTags | 开关 | 开 / 关 | 开 | 标题/艺术家/专辑/轨号/年份/流派/ISRC |  |
| (迁移自A2，插入A1，位置由你建议) | 断点续抓 | resume | 开关 | 开 / 关 | 开 | 目标 WAV 已有则从断点继续 |  |
| (迁移自A2，插入A1，位置由你建议) | 碟号（0=自动） | discNumberOverride | 输入 | 整数，0 = 用元数据 | 0 | 多碟套装的当前碟号，配 `%discnumber%` |  |
| (迁移自A2，插入A1，位置由你建议) | 总碟数（0=自动） | totalDiscsOverride | 输入 | 整数，0 = 用元数据 | 0 | >1 且模板未写 `%discnumber%` 时自动加 CD1/CD2 子目录 |  |

## A2 分组「输出与命名」（26 项）

| 序号 | 选项名（界面原文） | key | 控件 | 取值 / 格式 | 默认 | 作用 | 改法（你填） |
|---|---|---|---|---|---|---|---|
| 8 | 同名文件已存在时 | overwritePolicy | 单选 | suffix 加(2) / overwrite 覆盖 / skip 跳过 | suffix | 撞名策略 |  |
#| 9 | 输出根目录 | outputRoot | 输入 | 任意路径；空 = 系统「音乐」 | （空 → 音乐） | 抓轨产物根目录 |  |
#| 10 | 选择输出目录… | — | 动作 | `ConfigActions.chooseOutputRoot` | — | 系统文件夹选择框，选完即存 |  |
| 9 | 输出根目录 | 点击可打开弹窗让用户选择根目录。结合了之前的输出根目录和选择输出目录…的两个功能。默认输出目录是音乐库路径
| 11 | 专辑目录模板 | folderTemplate | 输入 | `%albumartist%/%albumtitle%` 等变量，`/` 分层 | `%albumartist%/%albumtitle%` | 决定专辑文件夹层级与名字 |  |
| 12 | 音轨文件名模板 | fileTemplate | 输入 | `%tracknr2% %title%` 等变量 | `%tracknr2% %title%` | 决定单轨文件名 |  |
| 13 | 封面文件名 | coverFileName | 输入 | 不含扩展名 | `cover` | 封面文件基名（按图片类型补 .jpg/.png） |  |
| 14 | 封面尺寸 | coverSize | 单选 | 原图(0) / 500 / 1200 px | 0 | Cover Art Archive 的 front-<size> |  |  （默认是原图大小）
| 15 | 生成 CUE | writeCue | 开关 | 开 / 关 | 开 | UTF-8 BOM + CRLF，含 REM DISCID/CDDBID |  |
| 16 | 生成抓轨日志 | writeLog | 开关 | 开 / 关 | 开 | 每轨 CRC32 / 重试 / 用时 + 设置摘要 |  |
| 17 | 在专辑目录写入 album.json | writeAlbumJson | 开关 | 开 / 关 | 关 | 存元数据，供离线复用/手改 |  |
| 18 | 抓过的盘优先用上次的元数据 | reuseAlbumJson | 开关 | 开 / 关 | 开 | 同盘再抓跳过联网 |  |
#| 19 | 抓轨前预扫间隙 | analyzeBeforeRip | 开关 | 开 / 关 | 关 | 整盘先扫 INDEX 00，省逐轨重复读 Q |  |
#| 20 | 写入 FLAC 标签 | writeTags | 开关 | 开 / 关 | 开 | 标题/艺术家/专辑/轨号/年份/流派/ISRC |  |
| 21 | 把封面内嵌进 FLAC | embedCover | 开关 | 开 / 关 | 开 | 每个 FLAC 内嵌一张 front cover |  |
| 22 | 给 WAV 写标签 | writeWavTags | 开关 | 开 / 关 | 开 | 写 RIFF LIST/INFO |  |
| 23 | 整盘镜像（单文件 + CUE） | imageMode | 开关 | 开 / 关 | 关 | 每轨抓好后拼成单 WAV + 绝对时间 CUE（占两倍临时空间） |  |
| 24 | 输出格式 | format | 单选 | WAV / FLAC / 外部编码器 | wav | 决定走哪条写出链路 |  |
| 25 | 外部编码器路径 | encoderExe | 输入 | 可执行名或绝对路径 | `flac` | format=external 时用 |  |
| 26 | 编码器参数模板 | encoderArgs | 输入 | `%in% %out% %tracknr2% %title%` 等 | `-f -s -5 -o "%out%" "%in%"` | 外部编码器命令行 |  |
| 27 | 产物扩展名 | encoderExt | 输入 | 不含点 | `flac` | 外部编码器产物后缀 |  |
| 28 | flac.exe 路径 | flacExe | 输入 | 名字走 PATH 或绝对路径 | `flac` | FLAC 编码器位置 |  |
| 29 | FLAC 压缩等级 | flacLevel | 单选 | 0 最快 / 3 / 5 / 8 最小 | 5 | 越高越慢、文件越小 |  |
| 30 | FLAC 编码后保留 WAV | keepWav | 开关 | 开 / 关 | 开 | 关掉则编码成功后删中间 WAV |  |
#| 31 | 断点续抓 | resume | 开关 | 开 / 关 | 开 | 目标 WAV 已有则从断点继续 |  |
#| 32 | 碟号（0=自动） | discNumberOverride | 输入 | 整数，0 = 用元数据 | 0 | 多碟套装的当前碟号，配 `%discnumber%` |  |
#| 33 | 总碟数（0=自动） | totalDiscsOverride | 输入 | 整数，0 = 用元数据 | 0 | >1 且模板未写 `%discnumber%` 时自动加 CD1/CD2 子目录 |  |

## A3 分组「校验与元数据」（17 项）

| 序号 | 选项名（界面原文） | key | 控件 | 取值 / 格式 | 默认 | 作用 | 改法（你填） |
|---|---|---|---|---|---|---|---|
| 34 | 防止中途换碟 | guardDisc | 开关 | 开 / 关 | 开 | 每轨前重读 TOC，盘变立即中止 |  |
| 35 | 双读校验（secure） | secureMode | 开关 | 开 / 关 | 开 | 每块读两遍逐字节比对，不一致重读 |  |
| 36 | 单块最大重读次数 | maxRetries | 输入 | 整数 | 8 | 单块重读上限 |  |
| 37 | 重读间隔（毫秒） | retryDelayMs | 输入 | 整数 | 120 | 重读前等待，老驱动可加 |  |
| 38 | 采样级 offset | offsetSamples | 输入 | 整数，正数=丢开头若干采样 | 0 | 与 EAC read offset 对应 |  |
| 39 | C2 错误检查 | useC2 | 开关 | 开 / 关 | 关 | 用 CDB 的 C2 位校验；不支持自动降级 |  |
| 40 | 击破驱动器缓存 | defeatCache | 开关 | 开 / 关 | 关 | 重读前读远端，让双读真的在验盘 |  |
| 41 | 探测 INDEX 00 间隙 | detectGaps | 开关 | 开 / 关 | 开 | 子通道 Q 往前扫最多 1 秒，只报告 |  |
| 42 | 出错恢复质量 | errorRecovery | 单选 | low 一次 / medium 连续 3 次 / high 连续 5 次 | medium | 决定要求几次一致 |  |
| 43 | 间隙处理方式 | gapMode | 单选 | append 附加上一轨 / prepend 归入下一轨 / leaveout 丢弃 | append | 决定 INDEX 00 归属哪个文件 |  |
| 44 | 间隙探测方法 | gapMethod | 单选 | q 子通道 / silence 波形静音 | q | Q 法与波形静音法二选一（EAC 的 Test Gaps On Silence） |  |
| 45 | Test & Copy | testAndCopy | 开关 | 开 / 关 | 关 | 先只读一遍拿 CRC，再正式抓并逐轨比对，耗时约翻倍 |  |
| 46 | 优先选轨数一致的发行版 | preferTrackCount | 开关 | 开 / 关 | 开 | 同 DiscID 多发行版时先按轨数筛 |  |
| 47 | 候选序号 | releaseIndex | 输入 | 0 起整数 | 0 | 手选第几个候选；日志会列全部 |  |
| 48 | 手工元数据文件（可选） | overrideFile | 输入 | 路径；空 = 自动找 | （空） | 强制用指定 album.json |  |
| 49 | 联网获取元数据 | fetchMetadata | 开关 | 开 / 关 | 开 | 关闭则离线，用占位名 |  |
| 50 | 读取 CD-TEXT | useCdText | 开关 | 开 / 关 | 开 | 盘上曲名压过网络数据，读不到静默跳过 |  |

| (迁移自A1，插入A4，位置由你建议) | 只抓这些音轨 | trackSelection | 输入 | 如 `1-5,8`；空 = 整盘 | （空） | 只抓部分轨 |  |
| (迁移自A1，插入A4，位置由你建议) | ② 抓取整张专辑 | — | 动作 | `ConfigActions.ripAlbum` | — | 取元数据 → 命名 → 逐轨抓 → 封面/CUE/日志 |  |
| (迁移自A1，插入A4，位置由你建议) | 暂停 / 继续 | — | 动作 | `ConfigActions.pauseRip` | — | 中途暂停/继续（只影响写盘节奏） |  |

## A4 分组「测试与诊断」（13 项）

这个分组里 12 项都是按钮，只有 1 个真设置。**A4分组默认只显示测试模式，如果测试模式为打开状态才显示其他选项**

| 序号 | 选项名（界面原文） | key | 控件 | 取值 / 格式 | 默认 | 作用 | 改法（你填） |
|---|---|---|---|---|---|---|---|
| 51 | 测试模式 | testMode | 开关 | 开 / 关 | 关 | A4分组默认只显示测试模式，如果测试模式为打开状态才显示其他选项 |  |
| 52 | ① 扫描驱动器 | — | 动作 | `ConfigActions.scanDrives` | — | 读光驱型号/轨数/DiscID，换盘后要重扫 |  |
| 53 | ①.2 抓轨计划预览（不写盘） | — | 动作 | `ConfigActions.previewPlan` | — | 算会生成哪些文件、撞名、空间 |  |
| 54 | ①.5 间隙预扫（只读） | — | 动作 | `ConfigActions.analyzeDisc` | — | 读每轨 INDEX 00，结果显示在面板 |  |
| 55 | ①.3 探测可用读命令（只读） | — | 动作 | `ConfigActions.probeReadCommands` | — | 逐条读 1 个扇区，看驱动认哪几种 |  |
| 56 | 只测不写（Test only） | — | 动作 | `ConfigActions.testDisc` | — | 按正式参数整盘读一遍，只报 CRC |  |
| 57 | ③ 仅查询元数据 | — | 动作 | `ConfigActions.lookupMetadata` | — | 只查 MB / gnudb，不写文件 |  |
| 58 | ③.5 导出元数据模板 | — | 动作 | `ConfigActions.exportAlbumJson` | — | 导出 `<根>/<DiscID>.album.json` 供手改 |  |
| 59 | ④ 试抓第 1 轨 | — | 动作 | `ConfigActions.ripFirstTrack` | — | 最小链路验证，输出到 `disc-<leadOut>/01.wav` |  |
| 60 | 取消当前抓轨 | — | 动作 | `ConfigActions.cancelRip` | — | 块边界处停，已写文件保留 |  |
| 61 | 选择 flac.exe… | — | 动作 | `ConfigActions.chooseFlacExe` | — | 从磁盘挑编码器 |  |
| 62 | 试写测试（中文路径） | — | 动作 | `ConfigActions.probePath` | — | 建含中日文名的目录/文件再删，验乱码与权限 |  |
| 63 | 恢复默认设置 | — | 动作 | `ConfigActions.resetDefaults` | — | 恢复内置默认（不动已抓文件） |  |

## B 面板「设置」块（与 A 重复，写同一批 key）

面板里点「设置」后展开；改动按「保存」才落盘。

| 编号 | 选项名 | key | 控件 | 取值 / 格式 | 默认 | 作用 | 改法（你填） |
|---|---|---|---|---|---|---|---|
| B1 | 输出目录 | outputRoot | 输入 + 动作（选择） | 同 A9 | 同 A9 | 面板内直接改根目录 |  |
| B2 | 专辑目录模板 | folderTemplate | 输入（模板框 + 变量按钮） | 同 A11 | 同 A11 | 点框→点变量插入光标处 |  |
| B3 | 文件名模板 | fileTemplate | 输入（模板框 + 变量按钮） | 同 A12 | 同 A12 | 同上 |  |
| B4 | 封面文件名 | coverFileName | 输入（模板框 + 变量按钮） | 同 A13 | 同 A13 | 同上 |  |
| B5 | 安全双读校验 | secureMode | 开关 | 同 A35 | 同 A35 | 面板内快速开关 |  |
| B6 | 保存 / 取消 / 清空 / 选择目录 | — | 动作 | `savePanelSettings` / `closeSettings` / `clearTarget` / `chooseOutputRoot` | — | 提交或放弃面板内改动 |  |

## C 隐藏键（代码在用、界面无入口）

| 编号 | 选项名 | key | 控件 | 取值 / 格式 | 默认 | 作用 | 改法（你填） |
|---|---|---|---|---|---|---|---|
| C1 | 多碟子目录模板 | multiDiscFolder | 无（写死默认） | `CD%discnumber%` | `CD%discnumber%` | `AlbumRipper.kt:123`、`RipPlan.kt:99` 使用；A33 的总碟数会触发它 |  |
| C2 | CUE 里附 CRC | annotateCueCrc | 无 | true / false | true | 把每轨 CRC 写进 CUE 的 REM，方便对账 |  |
| C3 | 面板外观 | appearance | 无（0.29.7 起删除入口） | auto / light / dark | auto | 只影响面板深浅；现在每次合成读宿主外观 |  |
| C4 | 旧键 includeGaps | includeGaps | 无（0.9 遗留） | true / false | — | 仅兼容迁移：true 视作 `gapMode=prepend` |  |

## 需要你决定的现存问题

1. **14 个按钮混在设置里**：A1 的「抓取整张专辑 / 暂停继续」、A4 的 12 个诊断按钮，都是动作而不是设置项；A4 的编号 `① / ①.2 / ①.3 / ①.5 / ② / ③ / ③.5 / ④` 是开发过程留下的，顺序也乱。
2. **数字只能用输入框**：`maxRetries` / `retryDelayMs` / `offsetSamples` / `coverSize` 后的 `releaseIndex` / `discNumberOverride` / `totalDiscsOverride` 都是 edittext，宿主没有数值控件；输错只能靠代码兜底。
3. **A 与 B 有 5 项重复**（输出根、三个模板、secure），两处都能改，容易互相覆盖。
4. **`testMode` 名不副实**：注释说 gate 真机测试，实际没有任何代码读它做判断。
5. **`imageMode` 与 `gapMode` 隐式互斥**：镜像只在 `append` 下可用，但 UI 没有任何提示。
6. **`format` 决定 4 个字段是否生效**（`encoderExe/encoderArgs/encoderExt` 仅 external；`flacExe/flacLevel/keepWav` 仅 flac），界面上全都一直显示。
7. **隐藏键 `multiDiscFolder` 会静默改变输出路径**（总碟数 >1 时自动加 `CD1/CD2`），界面上看不到。

## 你可以怎么编辑这张表

直接在任意一行后面加一列「改法」，写清下面任一种即可：

- `保留`（默认不改）/ `删除` / `改名为 xxx` / `移到某分组`
- `改为开关` / `改为单选` / `改为输入` / `改为按钮`
- `默认改为 xxx` / `取值只留 a,b,c`
- `新增：<名字> | <控件> | <取值> | <默认> | <作用>`

我拿到改完的表后按它重排 `preference_config.json`、面板设置块与 README 说明，并保证旧 key 的迁移不丢用户已存的值。
