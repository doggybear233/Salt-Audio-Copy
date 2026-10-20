# Salt Audio Copy 需求定义 v1.0（冻结稿）

> 项目：Salt Player（Windows / Steam AppID 3009140）的 CD 抓轨 mod，嵌入宿主界面。
> 依据：`options.md`（选项单）+ 用户 m00740 的决策。所有"已确认"项都来自真机实测。
> 平台约束：Windows only；不复刻刻录 / 音频编辑器 / 驱动器特性数据库；AccurateRip 与 MusicBrainz **只查不提交**。

---

## 1. 已确认的技术事实（实测，不可再讨论）

| 项 | 结论 |
|---|---|
| 驱动器 | `F:` HL-DT-ST BD-RE BP71N rev 1.02，CD 读速上限 ≈4.6x（实测 630–694 扇区/s） |
| 打开设备 | `CreateFileW("\\\\.\\F:", GENERIC_READ\|GENERIC_WRITE, FILE_SHARE_READ\|WRITE)`，**无需管理员** |
| 唯一通道 | `IOCTL_SCSI_PASS_THROUGH_DIRECT` (0x4D014)；结构体**自然对齐**（x64 sizeof=56），`_pack_=1` 必失败 |
| 单次上限 | **≤16 扇区**（37,632 B）；17 扇区起 `err=87` |
| READ CD | CDB = `BE 04 [LBA 24bit BE @2..5] 00 00 [n @8] [flags @9]`；本机 `flags=0xF8` 可用，EAC 的 `0x14` 会 `err=121` |
| TOC | READ TOC format 0（MSF 位）与 format 2 均可；条目 8/11 字节，**ADR 高半字节、CTRL 低半字节**，CTRL=0 = audio |
| Accurate Stream | MODE SENSE page 0x2A → **False**（不能免二次校验） |
| C2 | 读 C2 成功（294 B/扇区），本盘全 0（坏盘未验证） |
| CD-TEXT / MCN / ISRC | **两盘实测均无**：松田聖子 1987（10 轨）、宇多田ヒカル Fantôme 2016（11 轨）format 5 都只返回 `00 02`+全 0（start=0/1 × alloc=0x400/0x1000 四种组合一致，同句柄 format 0 正常）；format 2 无 point 0x00/ISRC → **通道实现但行为未验证** |
| 元数据 | MusicBrainz DiscID 实测与官方记录一致；gnudb CDDB ID 一致；CAA `front-500` 可取（97,698 B JPEG） |
| 宿主运行时 | JRE 25.0.4.1（无 javac）；模块无 `java.net.http` → 用 `HttpURLConnection` |
| 宿主 mod API | `spw-workshop-api 0.1.0-dev22`；**无新增页面 API**，只有 Compose Hook（`appendContent` / `afterComposable` / `replaceComposable`） |
| 宿主侧边栏 | 顶层路由由 `com.xuncorp.voxzen.ui.navigation.ScreenRouteKt.getTopLevelScreenRoutes()` 提供（**未混淆**）；列表项由 `androidx/compose/ui/ξ`（LazyListScope 构建）渲染 |
| 宿主 CD 页 | 路由 `ScreenRoute$CDToolkit` → 渲染类 `androidx/compose/ui/ݩ`（方法 `Ϳ`） |

---

## 2. 用户已定的决策

| # | 决策 | 用户原话要点 |
|---|---|---|
| 1 | **界面默认挂在侧边栏，位置在「音乐库」与「设置」之间**；可在 mod 设置里改成隐藏，隐藏后只在 **CD 工具页**出现 | m00740 mount |
| 2 | 默认抓轨档 = **Secure 标准**，但**一切强度参数都可在设置里改** | m00740 strength |
| 3 | 输出 = **FLAC 默认 + 可选保留 WAV** | m00740 output |
| 4 | 元数据 = **多源查询 + 人工确认候选** | m00740 metadata |
| 5 | 抓完 **自动入库**；保存路径等**所有选项都可在设置里自定义** | m00740 library |
| 6 | 派生决定（我按推荐值定，可推翻）：gap 探测 V1 不做（信任 CUE INDEX 00/01）；offset 默认 0、可手填；FLAC 用进程内纯 Java 编码器 | options.md §6/§7 |

---

## 3. 界面规格

### 3.1 侧边栏入口（默认）
- 位置：**「音乐库」与「设置」之间**（顺序：… 音乐库 → **CD 抓轨** → 设置）。
- 形态：与宿主其它项同款的图标 + 文案（默认文案「CD 抓轨」，图标用内置 Material CD/Disc 图标）。
- 交互：点击 → 在宿主窗口内打开**抓轨视图**（Compose 全窗口 overlay / Dialog，不新开 OS 窗口）。
- 设置项 `sidebarEntry`：`显示（默认）` / `隐藏`。隐藏时侧边栏无此项，抓轨 UI 改为追加到宿主 CD 工具页。

> 实现路径（M1 验证）：候选① 在宿主侧边栏的 LazyListScope 构建函数里追加 `item{}`（可精确控制插入点需实验）；
> 候选② 在「音乐库」项的 composable 之后注入；候选③ 降级为追加到列表末尾。
> 三者都不需要新增宿主路由 —— 抓轨界面是 Compose overlay，不是导航目的地。

### 3.2 抓轨主界面（四步向导）
1. **读盘**：自动检测驱动器（可下拉选择）、读 TOC、显示碟片信息（轨数/总时长/lead-out）。
2. **元数据**：并行查 MB + gnudb（+ CAA 封面）→ 候选列表（来源 / 年份 / 曲目数 / 封面缩略图 / 置信度）→ 选中后字段可逐项编辑（专辑名、艺术家、年份、流派、每轨标题/艺术家、碟号/总碟数、条码）。
3. **选项**：输出目录、文件命名模板、格式（FLAC / FLAC+WAV）、压缩级、抓轨强度、重试预算、offset、是否写 CUE/日志、封面尺寸、抓完是否入库。—— 全部有默认值，均来自设置页。
4. **执行**：逐轨进度（轨号、百分比、当前速度 KB/s、重读次数、错误数）、可暂停/取消、实时日志窗格、完成页（耗时、平均校验结果、AR 结果、打开目录按钮）。

### 3.3 设置页（`preference_config.json`，宿主原生渲染）
宿主支持 `switch` / `list` / `button` / `seekbar` / `edittext`，足够覆盖。分组：

| 分组 | 项 |
|---|---|
| 常规 | 侧边栏入口显示/隐藏；语言跟随宿主 |
| 输出 | 默认保存路径（edittext + 浏览）；**文件夹模板**默认 `%albumartist%/%albumtitle%`；**文件名模板**默认 `%tracknr2% %title%`；封面 `cover.jpg` 存同目录。模板在**可视化块编辑器**里编辑（变量=可拖拽/可追加/可删除的块 + 实时预览 + CJK 试写探针），详见 `path-template.md` |
| 格式 | 默认格式（FLAC / FLAC+WAV）；FLAC 压缩级 0–8（seekbar，默认 5）；是否嵌入封面；是否写 `cover.jpg` |
| 抓轨 | 强度（Secure 标准 / Secure+C2 / Fast / Burst）；单块重试上限（默认 16）；单轨超时（默认 300 s）；缓存策略（自动/强制双读） |
| 校正 | read offset（采样数，默认 0）；写入 CUE 时是否补偿 |
| 元数据 | 来源开关（MB / gnudb / CD-TEXT(未验证)）；候选选择方式（人工确认 / 自动高置信）；封面尺寸（500 / 1200）；MB User-Agent 联系邮箱 |
| 日志 | 写 EAC 风格日志（默认开）；日志语言；是否含 AR 段落 |
| 入库 | 抓完自动入库（默认开）；入库失败降级为提示 |

### 3.4 隐藏模式（设置里选「隐藏」）
抓轨 UI 用 `appendContent` 追加到宿主 CD 工具页底部（`androidx/compose/ui/ݩ.Ϳ`），保留宿主原页面内容。

---

## 4. 架构与模块

```
[UI 层] SidebarHook / RipWizard / SettingsPage        (Compose, 运行在宿主 JVM)
[应用层] RipSession(状态机) → TrackJob(逐轨) → 进度事件流
[校验层] SecureVerifier(双读比对/重读收敛) / Crc32+Mdx / ArQuery(只查)
[元数据] MetadataResolver(MB→gnudb→手改) / CoverFetcher / DiscIdCalculator
[编码层] FlacEncoder(纯 Java) / WavWriter / TagWriter(FLAC VorbisComment + ID3?)
[设备层] SptiDevice(FFM 调 kernel32) → ScsiPassThrough → ReadCdCommand / ReadToc / ModeSense
[输出层] PathBuilder(模板) / CueWriter / LogWriter / LibraryIngest(反射)
```

要点：
- **设备层用 `java.lang.foreign`（FFM）**，不引 JNA（宿主自带 JNA 但版本受控，FFM 更干净）。
- 全部 IO（读盘/编码/网络）在后台线程，UI 只订阅状态流。
- 每轨独立可重试；单轨失败不中断整盘（标记后继续）。
- 断点续抓：已完成轨的 WAV 分片落盘 + 清单 JSON，重启可续（EAC 没有，作为差异化）。

---

## 5. 验收标准

| 阶段 | 验收 |
|---|---|
| M1 | 侧边栏出现「CD 抓轨」并在正确位置；打开向导、读到 TOC、抓第 1 轨 WAV 成功 |
| M2 | 同一张盘抓出的 10 轨 WAV 与 **EAC 抓出的逐字节一致**（CRC32 + MD5 相同，含 lead-in/lead-out 处理一致） |
| M3 | 元数据向导可选到正确发行版；输出 CUE/日志字段完整；日志含每轨 CRC32/MD5 |
| M4 | FLAC 可被宿主播放；抓完自动进入曲库（失败降级提示） |
| M5 | `.spmod` 打包、GitHub Release、Steam 创意工坊可订阅安装 |

---

## 6. 里程碑

| # | 内容 | 依赖 |
|---|---|---|
| M0 ✅ | SPTI/TOC/整轨 CRC/MB/gnudb/CAA 验证 | 已完成 |
| M1 | Mod 骨架 + 侧边栏挂载 + 向导 + 单轨 WAV + 进度 | 需 JDK 25 + Gradle |
| M2 | Secure 双读 + 全盘 + 与 EAC 逐字节对齐 + offset 手填 | M1 |
| M3 | 元数据向导 + 封面 + CUE + 日志 + 模板 | M1 |
| M4 | FLAC 编码 + 入库 + 设置页 | M2/M3 |
| M5 | 打包发布（GitHub + Steam） | M4 |

---

## 7. 风险与对策

| 风险 | 影响 | 对策 |
|---|---|---|
| 宿主类名混淆、随版本变化 | 侧边栏/CD 页挂载点失效 | `host-map.json` 版本映射 + 运行时探测 + 三级降级（列表末尾 → CD 工具页 → 仅 toast 提示手动入口） |
| 宿主更新破坏 Compose 版本兼容 | 组合期崩溃 | 锁定宿主 Compose 版本；Mod 内只依赖 `compileOnly` 的 API，不自带 Compose |
| 本机无 javac | 无法开始 M1 | **已解决**：JDK 25.0.4.1 装到 `D:\dev\jdk-25`，Gradle 9.8.1 装到 `D:\dev\gradle`（见 §8） |
| FLAC 编码器许可/性能 | 分发合规 / 速度 | 选 BSD/LGPL 的纯 Java 实现；先测编码吞吐，不足则改调 `flac.exe` |
| gnudb `hello=` 客户端名 | 查询被拒 | 先内置已注册名 `cddb`/`eac`；长期向 gnudb 申请自有名 |
| MB 限速 503 | 查询失败 | 1 req/s 节流 + 指数退避 + 缓存 discid→release 结果 |
| 缓存/读取速度判定不稳 | Secure 效果打折 | 不做缓存判定，Secure 一律双读比对；重读次数可配 |
| 驱动器 offset 未知 | 与 AR 不完全对齐 | 默认 0 可手填；后续内置常见型号 offset 表 |

---

## 8. 工具链（已就位）与待办

### 8.1 已安装（2026-10-19）

| 组件 | 路径 | 版本 | 说明 |
|---|---|---|---|
| Temurin JDK | `D:\dev\jdk-25` | **25.0.4.1+1-LTS** | 与宿主 JRE 25.0.4.1 **完全同版本**，避免 Kotlin/Compose 目标不一致 |
| Gradle | `D:\dev\gradle` | 9.8.1 | Launcher/Daemon JVM 均为 `D:\dev\jdk-25` |

推荐路径的理由：全 ASCII、无空格（`D:\dev\jdk-25`）、不在 `C:\Program Files`（免 UAC）、可整目录删除回滚；不写系统 `JAVA_HOME`/`PATH`，用项目内 `dev-env.ps1` 按需注入，避免影响其它 JDK 使用者。

### 8.2 待办

1. 目标项目将使用 **Gradle Wrapper**（`gradlew`）锁定构建，不依赖上面这份 Gradle 安装。
2. 提供一张**带 CD-TEXT 的现代 CD**（可选）—— 用于验证 CD-TEXT 通道；没有就按"未验证、默认关闭"处理。已试两盘均无：松田聖子 Strawberry Time（10 轨 / lead-out 194092）与宇多田ヒカル Fantôme（11 轨 / lead-out 224166），`format 5` 都返回 `data_len=2`。判别方法：用 EAC 打开同一张盘，若 EAC 的 CD-Text 对话框有内容，则说明是本机驱动（HL-DT-ST BD-RE BP71N）不导出 CD-TEXT，而非盘上无数据。
