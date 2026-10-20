# Salt Audio Copy 需求定义 · 选项单（v3，2026-10-19）

> 目标：在 Salt Player（Windows，Steam AppID 3009140）里做一个**嵌进宿主界面**的 CD 抓轨 mod。
> 本文只列「已经验证过的事实」和「需要你拍板的选项」，每项给出推荐值。
> 前置约束（不可改）：Windows only、必须嵌进宿主 UI、不复刻刻录/音频编辑器/驱动器特性数据库。

---

## 0. 已确认的硬事实（全部实测，非推测）

| 项目 | 结论 | 影响 |
|---|---|---|
| 驱动器 | `F:` **HL-DT-ST BD-RE BP71N** rev 1.02 | 蓝光刻录机，CD 读速上限 ~4.6x |
| 权限 | SPTI 需 `GENERIC_READ\|GENERIC_WRITE` 打开 `\\.\F:`；**不需要管理员** | 普通用户即可 |
| 传输 | `IOCTL_SCSI_PASS_THROUGH_DIRECT` 单次 **≤16 扇区**，17 扇区起 `err=87` | 必须分块循环 |
| READ CD | CDB `BE 04 [LBA 24bit BE] 00 00 [n] [flags]`；`flags` 用 `0xF8`（不用 EAC 的 `0x14`，此机超时） | 逐字节等价 EAC |
| 吞吐 | 实测 **4.2–4.6x**（≈630–694 扇区/s）；整轨 20,115 扇区 26.6 s | 一张 74 分钟盘约 16–18 分钟 |
| Accurate Stream | **False**（MODE SENSE page 0x2A byte2 bit1=0） | 此机不能靠"流准确"免二次校验 |
| C2 | 读 C2（294 B/扇区）**成功返回**，本盘全 0 | 可用，但"能报错"未在坏盘上验证 |
| 缓存 | A-B-A 读数不一致（49/1154/1580 ms）→ **判定方法不可靠，需重做** | Secure 模式必须自带"双读比对" |
| CD-TEXT | 本盘 `data_len=2` → **无 CD-TEXT**（格式支持，盘没有） | CD-TEXT 通道未验证 |
| MCN / ISRC | 全 TOC（format 2）无 point 0x00(adr=2) / adr=3 → **本盘无** | 条码/ISRC 只能靠网络 |
| TOC | 10 轨，lead-out LBA **194092**；TOC 条目 8 字节，ADR 高半字节 / CTRL 低半字节 | 已能正确解析 |
| 元数据 | **MusicBrainz DiscID 已验证**：`mHEn5XPh1chsnQA5EbR0_npgXSs-` 与 MB 官方记录逐字符一致 | 可直接查 |
| | **CDDB/gnudb ID 已验证**：`1f090a1d` | gnudb 仍在线可用 |
| 封面 | Cover Art Archive `front-500` → 97,698 B JPEG | 无需第三方 key |
| 宿主运行时 | JRE **25.0.4.1**，**无 javac**，模块里**没有 `java.net.http`**、无 `java.scripting` | 用 `HttpURLConnection`；开发需另装 JDK 25 |
| 宿主 mod API | `spw-workshop-api 0.1.0-dev22`；**没有"注册新页面"的 API** | 只能 Hook 现有 Compose 页面 |
| 宿主 CD 页 | 存在路由 `ScreenRoute$CDToolkit`，渲染类 **`androidx/compose/ui/ݩ`**（方法 `Ϳ`），页内含 `%d KB/s` 等读盘速显示 | 天然挂载点 |
| 宿主曲库写入 | 无 API；`RepoService.rebuildMusicLibrary(Folder,…)` / `addOrUpdateTracks` 存在但需反射 + 混淆模型 | 入库有两条路 |

---

## 1. 选项 A：界面挂在哪里（必选 1 项）

宿主没有"加一个页面"的 API，只有 Compose Hook。三种现实做法：

| 选项 | 做法 | 优点 | 缺点 |
|---|---|---|---|
| **A1 ★推荐** | `appendContent` 到宿主 **CD 工具页**（`androidx/compose/ui/ݩ.Ϳ`），在页面底部追加「抓轨」区块（选轨、目标格式、进度、开始按钮） | 语义对、用户路径最短（设置→CD 工具→抓轨）；宿主原页面功能保留 | 目标类名是混淆名，**宿主更新后可能变**；需维护版本映射表 + 找不到就降级 |
| A2 | `replaceComposable` 整个替换 CD 工具页，显示我们自己的完整抓轨 UI | UI 完全自主，体验最好 | 会**干掉宿主原有 CD 页功能**；同样依赖混淆名 |
| A3 | `appendContent` 到**设置页**或 **Mod 管理页**，在里面放一个「CD 抓轨」入口 | 挂载点更稳定（设置页结构变化小） | 路径长、语义不直观 |
| A4 | 自建独立窗口 | 最稳、完全自主 | **你已否决** |

> 所有 Hook 方案都要处理同一件事：宿主类名混淆且随版本变化。工程对策：
> ① 建立 `host-map.json`（宿主版本 → 目标类/方法）；② 启动时探测，探测失败则 toast 提示"宿主版本不受支持"，不崩溃；
> ③ 提供 fallback 目标（A3 的设置页）保证功能可用。

**需要你选：A1 / A2 / A3**（推荐 A1）。

---

## 2. 选项 B：抓轨强度（默认档 + 可选档）

EAC 的 secure 本质是「读两次 → 比对 → 不一致就重读到收敛」，不是某条特殊命令。

| 档位 | 算法 | 单盘耗时（74min） | 适用 |
|---|---|---|---|
| **Secure 标准 ★推荐** | 每块读 2 次比对；不一致 → 重读最多 16 次，取多数一致（≥8 次相同即收敛）；失败块标记 | ~2× 直读 ≈ 30–35 min | 日常抓轨，等价 EAC 默认 |
| Secure + C2 | 同上 + 读 C2 指针，C2 报错的块优先重读 | 略快于标准（坏块命中率高时） | 划伤盘 |
| Fast | 每块读 1 次 + 全盘二次校验（只报错不重读） | ~1× ≈ 16–18 min | 好盘、赶时间 |
| Burst | 直读一次，不校验 | ~1× | 仅试听/预览 |

**需要你选默认档**（推荐 Secure 标准）；其余档作为设置项。

另外两个默认值建议（可否决）：
- **缓存击破**：每块前后发一次 `SET CD SPEED`/越界读？—— 实测本机缓存行为不明，建议 **Secure 模式天然用双读比对，不依赖缓存判定**；不做 EAC 那套 "Clear Cache Read10"。
- **重试预算**：单块最多 16 次、单轨最多 5 分钟，超时即标记为「有错」继续下一轨（不中断整盘）。

---

## 3. 选项 C：输出格式与编码器（必选 1 项）

宿主**没有编码 API**，`ffmpeg-x64.dll` 其实是宿主自己的 classpath 包（不是可调用的 ffmpeg），`spwdecode.dll`/BASS 只负责解码播放。所以编码要自带：

| 选项 | 方案 | 体积 | 风险 |
|---|---|---|---|
| **C1 ★推荐** | 自带 **纯 Java FLAC 编码器**（如 `jFLAC`/`javaFlacEncoder`，LGPL/BSD），进程内编码 | +200 KB | 编码速度较慢（约 0.5–1x 实时），需验证 |
| C2 | 打包 **`flac.exe`**（Xiph，BSD），进程调用 `flac -8 --verify` | +1.5 MB | 需分发二进制；杀软误报；跨进程错误处理 |
| C3 | 只输出 **WAV + CUE**，让宿主/用户自行转码 | 0 | 一张盘 700 MB；宿主不会自动转 |
| C4 | C1 + C3 组合：默认 FLAC，可选"保留 WAV" | +200 KB | 推荐给发烧用户 |

**需要你选**（推荐 C4：默认 FLAC，可勾选保留 WAV）。

---

## 4. 选项 D：元数据来源与交互

已验证可用：MusicBrainz（exact DiscID 命中）、gnudb（CDDB ID 命中，日文曲名更全）、Cover Art Archive（封面）。
教训：MB 的 exact-discid 对日版/老盘命中率低 → **必须多源 + 人工确认**。

| 选项 | 流程 |
|---|---|
| **D1 ★推荐** | 读盘 → 并行查 MB + gnudb → 弹「选择发行版」列表（显示来源/年份/曲目数/封面缩略图）→ 用户点选 → 可逐字段手改 → 确认后开始抓轨 |
| D2 | 自动选第一个高置信候选（曲目数+时长完全匹配才算），失败才弹窗 | 
| D3 | 只查 MB，不做 gnudb |

附带默认值（可否决）：
- 封面：`front-500` 自动下载嵌入 + 存 `cover.jpg`；可选 1200。
- 字段优先级：**用户手改 > CD-TEXT > MB > gnudb**。
- 曲目命名模板：默认 `%tracknr2%. %artist% - %title%`（保留 EAC 的模板变量体系，但只实现常用 12 个）。
- 网络合规：MB 限速 1 req/s + 自定义 User-Agent（`Salt Audio Copy/0.1 ( <contact> )`）；gnudb 的 `hello=` 客户端名需先注册（`cddb`/`eac` 可用，自建名要申请）。

**需要你选：D1 / D2 / D3**（推荐 D1）。

---

## 5. 选项 E：抓完之后的入库（必选 1 项）

| 选项 | 做法 | 体验 |
|---|---|---|
| **E1 ★推荐** | 写文件 + **反射调 `RepoService.rebuildMusicLibrary`**（或用宿主已有的"添加文件夹→扫描"）自动入库 | 抓完即出现在曲库 |
| E2 | 只写文件 + toast 提示"请到 设置→音乐库 手动添加文件夹" | 零风险，但多一步 |
| E3 | E1 失败自动降级到 E2 | 推荐：E1 为主，失败静默降级 |

**需要你选**（推荐 E3）。

---

## 6. 选项 F：日志与 CUE（可多选）

| 项 | 说明 | 建议 |
|---|---|---|
| CUE 单文件 | `FILE "album.flac" WAVE` + 每轨 `INDEX 01` | ★默认 |
| CUE 分轨 | 每轨一个文件 | 可选 |
| EAC 风格日志 | 含驱动器/offset/读模式/**每轨 CRC32+MD5**/错误统计/AR 结果 | ★默认（这是"可验证抓轨"的核心） |
| AccurateRip 结果段 | 只查不提交，写入日志「AccurateRip: 已确认(confidence N) / 未能确认」 | ★默认 |
| gap 处理 | 不做"逐样本 gap 探测"（EAC 那套要位反交织子通道），改为**信任 CUE 的 INDEX 00/01**；no-gap 盘按 EAC "Leave Out Gaps" 语义 | 简化点，需你确认 |

> 关于 gap：EAC 的 gap 检测依赖 READ CD 带子通道 + **位反交织**还原 Q。我实测拿到了 96 B/扇区子通道，但字节直读是无效的，需实现反交织。**建议 V1 不做**，V2 再加（保留 `GapDetectionMode` 设置项）。

---

## 7. 选项 G：read offset（采样级偏移）

EAC 的 offset 校正需要「已知 offset 的参考盘 + AR 数据库匹配」。当前**无法自动测定**（无参考盘）。

| 选项 | 说明 |
|---|---|
| **G1 ★推荐** | V1 默认 offset=0，设置里允许**手动填采样数**（高级用户），日志记录所用值 | 
| G2 | 做自动探测：用 AR 数据库穷举 ±2939 采样找匹配（EAC 的 `FindDriveReadOffset` 思路） | 工作量大，需 AR 私有协议 |
| G3 | 直接查询公开的驱动器 offset 数据库（如 AccurateRip drive offset list 的镜像，手工内置几十个常见型号） | 折中，可后续加 |

---

## 8. 工具链前置（需你确认）

| 项 | 现状 | 需要 |
|---|---|---|
| JDK | 本机只有 Java 8，宿主 JRE 25 无 javac | **装 JDK 25**（Temurin/MS OpenJDK，~200 MB） |
| 构建 | 无 Gradle | 用官方 Gradle Wrapper（随项目分发，不污染系统） |
| IDE | 无 | 可选 IntelliJ IDEA CE |

---

## 9. 里程碑（按上面选项排）

| 阶段 | 内容 | 验收 |
|---|---|---|
| M0 ✅ | SPTI 打通、TOC、整轨 CRC、MB/gnudb/CAA | 已达成（本文 §0） |
| M1 | Mod 骨架 + A1 挂载 + 单轨抓 WAV + 进度 | 宿主 CD 页出现「抓轨」区块，能抓第 1 轨 |
| M2 | Secure 双读比对 + 错误统计 + 全盘 10 轨 | 同盘与 EAC 抓出的 WAV **逐字节一致**（CRC32/MD5 相同） |
| M3 | 元数据（D1）+ 封面 + CUE + 日志 + 命名模板 | 抓完得到完整专辑目录 |
| M4 | FLAC 编码 + 入库（E3）+ 设置页 | 抓完自动入库、可在宿主播放 |
| M5 | 打包 `.spmod` + GitHub Release + Steam 创意工坊发布 | 用户在 Steam 订阅即用 |

---

## 10. 需要你回复的 5 个决策

1. **界面挂载**：A1 追加到 CD 工具页 / A2 替换 CD 工具页 / A3 挂设置页 → 推荐 A1
2. **默认抓轨档**：Secure 标准 / Secure+C2 / Fast → 推荐 Secure 标准
3. **输出格式**：C4 FLAC+可选WAV / C1 仅 FLAC / C3 仅 WAV → 推荐 C4
4. **元数据流程**：D1 人工确认候选 / D2 自动选 → 推荐 D1
5. **入库方式**：E3 反射自动入库+降级 / E2 只写文件 → 推荐 E3

另需确认（可否决，我按推荐值继续）：gap 检测 V1 不做、offset V1 为 0 可手填、进程内 Java FLAC 编码器。
