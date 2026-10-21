# Salt Player 抓轨 Mod 可行性评估

> 目标：把 EAC 的抓轨能力做成 Salt Player（Windows / Steam）的创意工坊 Mod，发布到 GitHub + Steam Workshop。
> 依据：saltplayer.com 官方文档（2026-10-16 版）、`spw-workshop-api` 源码、现有第三方插件实现、以及本仓库对 EAC 1.8 的逆向结论（`../eac-re/architecture.md`）。

---

## 0. 结论先行

| 维度 | 判断 | 说明 |
|---|---|---|
| **技术可行性** | **高（~85%）** | 核心抓轨链路（SCSI → secure read → gap → CUE → 编码）全部可复刻，算法已逆向清楚 |
| **精度对标 EAC** | **可以做到等价** | secure mode 是「算法 + 重试策略」，不是黑魔法；CDB/流程已逐字节还原 |
| **性能对标 EAC** | **可以** | 瓶颈在光驱与缓存击破，不在 CPU；Java 侧开销 <5% |
| **「完美复刻」全部功能** | **不现实** | AccurateRip 私有协议、驱动器特性库、刻录/写盘三块不建议照搬 |
| **产品完成度** | **中** | 卡点是「抓完不能自动进曲库」——宿主没有 library 写 API |
| **总体建议** | **做，但重新定义范围** | V1 = 抓轨 + 校验(CTDB 为主) + EAC 兼容 CUE/log + 编码；不做 AR 网络与刻录 |

**一句话**：技术上完全能做，且能做到 bit-perfect 判定与 EAC 同级；但「完美复刻 EAC」这个目标里有 30% 是 EAC 二十年积累的**数据资产**（驱动器库、AR 数据库、私有协议），不是代码，抄不来也不该抄。

---

## 1. Salt Player Mod 平台事实（决定了架构边界）

### 1.1 运行时模型

- 插件是 **JVM 插件**（Kotlin/Java），基于 **PF4J**，宿主是 **Compose Multiplatform Desktop（JDK 25 / Kotlin 2.4.20）**
- 打包产物是 `.spmod`（本质是 zip/jar），通过 Gradle 插件 `com.xuncorp.spw.workshop` 的 `plugin` 任务生成
- 入口：`class X(ctx: PluginContext) : SpwPlugin(ctx)`，重写 `start()/stop()/delete()/update()`
- API 坐标：`com.github.Moriafly:spw-workshop-api:0.1.0-dev22`（JitPack），`compileOnly`
- 扩展点用 PF4J `@Extension` + kapt 生成索引

### 1.2 权限模型（关键）

| 权限 | 能力 | 对本项目 |
|---|---|---|
| `key-bindings` | 注册应用内/全局快捷键 | 可选（开始抓轨快捷键） |
| `library-read` | 读曲库元数据/路径/封面 | 需要（判断是否已存在、封面复用） |
| `library-write` | **预留，当前 API 无写入入口** | ❌ 无法把抓轨结果写进曲库 |
| `class-transform` | 方法 Hook / UI Hook（最高危） | 可选，但会显著增加脆弱性 |

> 官方原话：「当前插件仍在宿主 JVM 内运行，权限声明和检查**不构成操作系统沙箱**」。
> 这既是机会也是责任：**JNA/JNI、ProcessBuilder、任意文件读写、起本地 HTTP 服务全部可用**（现有 `SaltLyricPlugin` 已经这么干了：JNA + Jetty + 自建窗口）。

### 1.3 API 表面（完整清单，来自源码）

```
WorkshopApi
├── ui            : toast(text, Success|Warning|Error)
├── playback      : play/pause/next/previous/seekTo/changeExclusive
│                   getCurrentMediaItem() / getLyricsLines()
├── library       : getTrackById / getCoverById / getAllTracks / getTracks(afterId, limit)
├── manager       : createConfigManager() / isPermissionGranted() / keyBindingManager
└── hookRegistrar : hookMethod(before/replace/after) / replaceComposable / afterComposable / appendContent
```

配置界面走 `preference_config.json`（switch/list/button/seekbar/edittext + `on_click` 静态方法），
用户数据目录 `workshop/data/<pluginId>/`，用 `ConfigManager`/`ConfigHelper` 读写。

**没有**：文件写入、曲库写入、媒体扫描触发、任意文件播放、HTTP 客户端封装（自己带）、进程管理（自己带）。

### 1.4 宿主实测（Salt Player for Windows 1.19.0，Steam 版）

直接解剖了本机安装 `D:\Steam\steamapps\common\Salt Player for Windows`（jpackage app-image）：

| 事实 | 值 | 影响 |
|---|---|---|
| 运行时 | **Java 25.0.4.1**（jpackage 内置 JRE，无 javac） | 插件必须按 JDK 25 编译；本机只装了 JDK 8，需要装 JDK 25 |
| JVM 模块集 | 只有 base/compiler/datatransfer/xml/prefs/desktop/instrument/logging/management/sasl/naming/jgss/transaction.xa/sql/accessibility/attach/crypto.ec/security.auth/unsupported | ⚠️ **没有 `java.net.http`**（`HttpClient` 会 NoClassDefFoundError），也没有 `java.scripting`、`jdk.zipfs`。HTTP 只能走 `HttpURLConnection`（java.base）或自带客户端 |
| 关键 JVM 参数 | `--enable-native-access=ALL-UNNAMED`、`--add-opens java.desktop/sun.awt`、`-Xmx512M`、`-XX:AOTCache=voxzen.aot` | FFM（`java.lang.foreign`，JDK 22+ 正式版）**可直接调用 `DeviceIoControl`，零依赖**；堆上限 512M，抓轨必须流式写盘 |
| 程序类位置 | 不在 jar 里，而是 **zip 追加在 `app\ffmpeg-x64.dll` 末尾**（42,402 个 entry），`app.classpath` 直接指向它 | 说明宿主对打包格式很随意，也方便我们做静态侦察 |
| 已含依赖 | `org/pf4j`（插件框架）、`com/sun/jna` + `jna-platform`（**JNA 已随宿主分发**）、`io/ktor`（HTTP）、`org/jaudiotagger`（标签）、`net/bytebuddy`（Hook 引擎）、`androidx/room`（DB） | JNA/ktor/jaudiotagger 可以 `compileOnly`，不用打进包里；但更稳的是用 FFM + `HttpURLConnection` |
| 宿主类名 | `com/xuncorp/voxzen/**`，数据模型 **被混淆**（`Ϳ`、`Ԩ`、`ԩ`） | ⚠️ UI Hook 要面对混淆类名 → 强烈支持「自建窗口」方案 |
| 曲库重建入口 | `RepoService.rebuildMusicLibrary(Folder, boolean, boolean, Continuation)`（还有 `addOrUpdateTracks` / `deleteCustomFolder` / `rereadTracks`） | 存在「加文件夹 + 重建曲库」的逃生通道，但需要反射/`class-transform`，逐版本脆弱 |
| CD 支持 | `app\resources\basscd.dll`（BASS CD 插件）已随宿主分发 | 宿主本身能读 CDDA（BASS 层），但**无 C2、无子通道、无 secure mode**，不能用于抓轨质量目标；可用于「预览/试听」 |
| 音频引擎 | `bass.dll` + bassflac/bassape/bassdsd/basswasapi/bassasio 等 | 与抓轨无关，仅确认播放栈 |

### 1.5 发布

- **GitHub**：Release 上传 `.spmod` + 仓库加 `salt-player-plugins` topic
- **Steam**：启动项加 `--workshop-publisher` → 创意工坊创作中心 → 上传 `.spmod` + <1MB 封面（PNG/JPG/GIF）→ 新建条目默认私有 → 手动公开
- AppID `3009140`（Salt Player for Windows）

---

## 2. 逐模块可行性

### 2.1 SCSI / MMC 层 —— ✅ 高（这是整个项目的地基，且地基已经画好）

**首选方案：JDK 22+ 的 FFM（`java.lang.foreign`）**。宿主已经开了 `--enable-native-access=ALL-UNNAMED`，所以 `Linker.nativeLinker().downcallHandle(...)` 可以直接绑定 `kernel32` 的 `CreateFileW` / `DeviceIoControl` / `ReadFile`，**零第三方依赖、无类加载冲突**：

```java
// JDK 25 FFM 骨架（不依赖 JNA）
var linker = Linker.nativeLinker();
var kernel = linker.defaultLookup();
MethodHandle createFile = linker.downcallHandle(
    kernel.find("CreateFileW").orElseThrow(),
    FunctionDescriptor.of(VALUE_LAYOUT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS,
                          JAVA_INT, JAVA_INT, ADDRESS));

// SCSI_PASS_THROUGH_DIRECT（x64 布局，0x30 字节；注意 4 字节对齐空洞）
MemoryLayout SPTD = MemoryLayout.structLayout(
    JAVA_SHORT.withName("Length"),      // 0x00
    JAVA_BYTE.withName("ScsiStatus"),   // 0x02
    JAVA_BYTE.withName("PathId"),       // 0x03
    JAVA_BYTE.withName("TargetId"),     // 0x04
    JAVA_BYTE.withName("Lun"),          // 0x05
    JAVA_BYTE.withName("CdbLength"),    // 0x06
    JAVA_BYTE.withName("SenseInfoLength"), // 0x07
    JAVA_BYTE.withName("DataIn"),       // 0x08
    MemoryLayout.paddingLayout(3),      // 0x09..0x0B
    JAVA_INT.withName("DataTransferLength"), // 0x0C
    JAVA_INT.withName("TimeOutValue"),  // 0x10
    MemoryLayout.paddingLayout(4),      // 0x14..0x17
    ADDRESS.withName("DataBuffer"),     // 0x18
    JAVA_INT.withName("SenseInfoOffset"), // 0x20
    MemoryLayout.sequenceLayout(16, JAVA_BYTE).withName("Cdb") // 0x24
);
```

备选：JNA（宿主已内置 `com.sun.jna`，`compileOnly` 即可，现有 `SaltLyricPlugin` 就是这么用的）。两者都能跑，FFM 更干净。

- `CreateFileW("\\\\.\\D:", GENERIC_READ|GENERIC_WRITE, FILE_SHARE_READ|WRITE, ...)`
- `DeviceIoControl(h, IOCTL_SCSI_PASS_THROUGH_DIRECT=0x4D014, in, size, out, size, &ret, null)`
- 不需要管理员权限；不需要驱动；不需要 WNASPI32

**已具备的 EAC 知识**（直接可编码）：

| 项 | 值 |
|---|---|
| READ CD (0xBE) CDB | `BE 04 [LBA 大端24位] 00 00 [扇区数≤255] 14 00 00`（`CDB[1]=0x04`=CD-DA，`CDB[9]=0x14`=C2+子通道） |
| READ TOC | `43 00 [format] 00 00 00 00 [lenHi] [lenLo] 00 00`，format 0=TOC / 1=会话 / 2=全TOC(MSF) / 5=CD-TEXT |
| READ SUB-CHANNEL | `42 02 40 01 00 00 00 00 [len] 00 00`（Q 子通道，用于 index/pregap） |
| MODE SENSE(10) | 读 CD 缓存页(0x08)/C2 页，判断驱动器是否缓存、是否支持 C2 |
| SYNCHRONIZE CACHE | `35 00 ...` 缓存击破 |
| SET CD SPEED | `BB ...` 读速控制 |
| 数据长度 | 必须偶数（ASPI 遗留约束，EAC 提交前 `and 0xFFFFFFFE`） |
| 单次传输 | ≤255 扇区，EAC 用 27 扇区/包 |

**风险**：无。这是最确定的一块。

### 2.2 抓轨引擎（Secure Mode）—— ✅ 高

EAC 的 secure mode 本质是状态机，我们已还原：

```
for each chunk:
  A = read(chunk)                       // 0xBE + C2
  if C2 bitmap clean:
      B = read(chunk) after cache-defeat // SYNC CACHE + 读远端脏块
      if A == B: accept
      else: 缩小窗口重试 / 换命令变体 / 标记 suspicious
  else:
      只重读 C2 报错的扇区
      重试 N 次仍失败 → suspicious position（写日志）
```

- **缓存击破**：`SYNCHRONIZE CACHE` + 读一段远端数据污染缓存 + 改变读取起点。三种手段组合，缺一不可
- **offset**：按 `SampleOffset`（采样，1 sample=2B）读 N+2 扇区后裁切
- **overread lead-out**：最后一轨需要，失败要降级
- **性能**：全部可在 4–8 个固定 `DirectByteBuffer`（每块 27×2352≈64KB）上循环，零分配；CRC 增量计算
- **对标**：精度可以做到与 EAC 逐字节一致（判定逻辑相同）；速度取决于光驱与 cache-defeat 策略，预计 ±5%

### 2.3 Gap 检测 / CUE / Log —— ✅ 高

- 子通道法（Q 的 index 0/1）+ 静音法双路
- 三种 gaps 模式（独立文件含 gap / 丢弃 / 修正到 2s / 追加到前曲）
- **建议输出 EAC 兼容的 CUE + log 格式**——这是社区事实标准（`CheckLog.exe`、CTDB/AR 提交、log 校验文化都依赖它），能直接白嫖生态信任

### 2.4 校验：AccurateRip vs CTDB —— ⚠️ 中（需换路线）

| 方案 | 可行性 | 说明 |
|---|---|---|
| **AccurateRip 查询/提交** | ⚠️ 灰区 | AR 协议未公开，EAC 用闭源 `AccurateRip.dll`。逆向实现技术上可行（CRC 算法我们已从 CUETools 拿到），但**服务端非官方客户端可能被封**；且 AR 数据库只认精确 offset 组合 |
| **CTDB（CUETools DB）** | ✅ 高 | 协议公开（HTTP + JSON/XML），开源实现可参考；**校验能力比 AR 更强**（能验未收录盘、可定位错误样本） |
| **本地自校验** | ✅ 高 | 双遍比对 + C2 + 逐扇区 CRC，即使不联网也能给「bit-perfect 自证」 |

**建议**：V1 做 **CTDB + 本地自证**；AR 作为可选实验特性（允许用户填 AR 服务器地址/自建代理），默认关闭，避免封号风险与合规问题。

### 2.5 编码管线 —— ✅ 高

- `ProcessBuilder` 起 `flac.exe`，stdin 喂 WAV 流，stderr 读进度（EAC 同款做法）
- 或纯 Java：`jFLAC` 能编码但慢 3–5×；**不建议**
- 标签模板沿用 EAC 变量集（`%tracknr2% %artist% - %title%`），用户迁移成本低
- 打包 flac.exe 注意 GPL：附 LICENSE + 源码链接（EAC 自己也这么分发）

### 2.6 UI —— ⚠️ 中（有两个方案，建议选 B）

| 方案 | 做法 | 评价 |
|---|---|---|
| A. Hook 宿主 UI | `replaceComposable`/`appendContent` + `CLASS_TRANSFORM` | 原生融合最好，但依赖宿主**内部类名**，随版本漂移；最高危权限，审核/用户信任成本高 |
| B. **插件自建窗口** | 插件里开 Swing/AWT `JFrame`（Compose Desktop 本身就是 AWT，可共存）+ 配置页走 `preference_config.json` | 零 Hook、零高危权限、版本稳定；已有插件（桌面歌词）验证可行 |

> 建议 B。代价是「不像原生界面」，但抓轨是**模态、长时间、独占光驱**的任务，独立窗口反而更合理。

### 2.7 曲库集成 —— ⚠️ 有逃生通道，但脆弱

- 宿主无 library 写 API（`library-write` 是空权限）
- 后果：抓完的文件**不能自动出现在 Salt Player 曲库里**，用户必须手动「添加文件夹」
- 实测发现宿主内部有 `RepoService.rebuildMusicLibrary(Folder, bool, bool, Continuation)`、`addOrUpdateTracks`、`deleteCustomFolder`、`rereadTracks` —— 即「加文件夹 + 重建曲库」的能力**存在**，只是没暴露成 API
- 兜底方案（按风险排序）：
  1. 抓完提示「已输出到 X，请在设置里添加该文件夹」+ 一键复制/打开目录（**V1 推荐**）
  2. 用反射/`hookMethod` 调 `RepoService.rebuildMusicLibrary`（需 `CLASS_TRANSFORM` + 构造 Room `Folder` 实体；宿主类名已混淆，逐版本适配，脆弱）→ 作为**实验性开关**
  3. 给官方提 feature request / PR，申请 `library-write` 或「扫描目录」API（**长期正解**）

### 2.8 平台 —— Windows only（V1）

- SPTI 仅 Windows；Steam 版是 `Salt Player for Windows`，正好匹配
- macOS 需 `IOKit/SCSITaskDeviceInterface`，Linux 需 `SG_IO`——都是另一套后端（内部 SRB 抽象可复用），V2 再说

### 2.9 本地验证 —— ⚠️ 当前机器无光驱

- 短期：Windows 内置「装载 ISO」会创建虚拟 DVD 盘符，可跑 `INQUIRY`/`READ TOC`/`READ CD`（部分虚拟驱动不支持音频读，需要试）
- 建议：**买一个 USB 外置光驱（¥80–150）**，这是这个项目唯一的硬性硬件投入
- 之后用同一张盘跑 EAC 与自研，做逐字节对比 + CUE/log diff

---

## 3. 「完美复刻」做不到的部分（要提前跟用户说清）

| EAC 能力 | 能否复刻 | 原因 |
|---|---|---|
| 精确抓轨（secure mode） | ✅ 能 | 算法已还原 |
| C2 / 缓存击破 / offset | ✅ 能 | 通用实现 |
| gap 检测 / CUE / log | ✅ 能 | 格式已知 |
| 驱动器特性库（数百型号） | ⚠️ 部分 | 数据资产，需自建 + 社区贡献 + 自动探测 |
| AccurateRip 查询/提交 | ⚠️ 灰区 | 私有协议，可能被封 |
| CTDB 校验 | ✅ 能 | 协议公开 |
| 刻录 / cdrdao 写盘 | ❌ 不做 | 与本项目目标无关，且工程量巨大 |
| CD-Text 编辑 / freedb / GD3 元数据 | ✅ 能 | HTTP + 解析，工作量小 |
| 封面/标签/命名模板 | ✅ 能 | 常规 |

---

## 4. 工作量与风险

### 4.1 里程碑

| 阶段 | 内容 | 预估（有 AI 辅助） | 验收 |
|---|---|---|---|
| **M0** | Gradle 工程 + 空插件跑通 + JNA SPTI：INQUIRY / READ TOC，打印盘片信息 | 1–2 天 | 虚拟光驱或真光驱能列出轨道 |
| **M1** | READ CD 抓单轨 → WAV；双遍比对；不一致定位 | 3–5 天 | 与 EAC 输出 `md5` 一致 |
| **M2** | 缓存击破 + C2 + 重试 + offset + 全盘抓轨 + gap 检测 | 4–6 天 | 脏盘场景下有 suspicious 报告 |
| **M3** | CUE/log 生成（EAC 兼容）+ 编码管线（flac）+ 命名模板 | 3–4 天 | `CheckLog` 通过、FLAC tag 正确 |
| **M4** | CTDB 校验 + 配置页 + 自建 UI 窗口 + Steam 打包发布 | 4–6 天 | 创意工坊可安装、可启用 |
| 合计 | | **约 3 周（兼职）/ 1.5–2 周（全职）** | |

### 4.2 风险清单（按严重度）

1. **曲库写入缺失**（产品体验）→ 用「输出目录 + 提示添加」过渡，长期提 API 需求
2. **AR 协议灰区**（合规/封禁）→ 默认走 CTDB，AR 可选
3. **UI 集成脆弱**（若选 Hook 方案）→ 选自建窗口
4. **无光驱无法自测**（阻塞开发）→ 买 USB 光驱 / 先用虚拟光驱
5. **宿主 API 处于 dev 阶段**（`UnstableSpwWorkshopApi`）→ 锁定版本、做好降级、关注 changelog
6. **打包体积**（JNA + flac.exe + 资源）→ 预计 5–8 MB，可接受；避免引入 Jetty 这类重依赖
7. **法律**：GPL 二进制分发（flac）、CD 抓轨的地区合规（多数地区个人备份合法，但不要内置「绕过版权保护」功能；音频 CD 无 DRM，风险低）

---

## 5. 建议的 V1 范围（MVP）

**做**：
- Windows / SPTI
- secure mode（双遍 + C2 + 缓存击破 + 重试 + offset）
- gap 检测（子通道 + 静音兜底）
- 输出：整轨 WAV/FLAC + 分轨 + EAC 兼容 CUE/log
- CTDB 校验（可选在线）+ 本地 bit-perfect 自证
- 元数据：CD-Text / 手动编辑 / freedb
- 自建窗口 UI + `preference_config.json` 配置页
- 打包 flac.exe

**不做（V1）**：
- AccurateRip 网络（留开关，默认关）
- 刻录 / cdrdao
- macOS / Linux 后端
- 曲库写入（等官方 API）
- 宿主 UI Hook

---

## 6. 待确认（需要你拍板）

1. **精度优先还是体验优先**？精度优先 = 先做引擎（M0–M2），UI 最后；体验优先 = 先搭壳子做 demo 给人看
2. **Windows-only 可接受吗**？还是必须 Win+mac（mac 需要另一套 SCSI 后端，工期 +1 周）
3. **AR 要不要碰**？我的建议是 V1 不碰，只做 CTDB + 本地自证
4. **有没有光驱**？没有的话建议先买一个 USB 外置光驱，否则整条链路只能靠虚拟光驱验证，风险很高
5. **是否接受「抓完手动添加文件夹」**？这是当前 API 下的唯一解；如果不接受，需要先向 Salt Player 官方提 API 需求

---

## 附录：现有生态参考

| 项目 | 价值 |
|---|---|
| [spw-workshop-api](https://github.com/Moriafly/spw-workshop-api) | 官方 API + Gradle 插件 + example |
| [SaltLyricPlugin](https://github.com/zmxlsss666/SaltLyricPlugin) | 真实案例：JNA 原生调用 + 自建窗口 + Jetty 内嵌服务，证明「插件里什么都能干」 |
| [CUETools](https://github.com/gchudov/cuetools.net) | CTDB 协议、CDImage、Parity 的参考实现（GPL，注意许可） |
| `../eac-re/architecture.md` | EAC 的 CDB 表、secure mode 状态机、AR CRC 常量、编码器模板 |
