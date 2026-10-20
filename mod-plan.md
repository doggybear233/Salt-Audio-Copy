# M1 实施计划：抓轨 Mod 骨架 + 宿主内嵌入口 + 单轨抓取

> 状态：**已获用户批准**（2026-10-19）。本文件是执行基准，偏离需记录原因。

## 0. 目标与验收

**目标**：产出能装进 Salt Player 1.19.0、在宿主窗口内出现抓轨入口、并能把 CD 第 1 轨读成 WAV 的 Mod（`.spmod`）。

**验收（全部通过才算 M1 完成）**

1. `.\gradlew.bat plugin` 产出 `.spmod`；宿主「Mod 管理」可导入并显示名称/版本/作者。
2. 启用后弹出 `class-transform` 权限请求；授权后插件状态变为已启用（无红字报错）。
3. 宿主「CD 工具」页内出现抓轨面板（不新开窗口）。
4. 面板显示驱动器型号 + TOC（轨数/时长/lead-out），与 Python 探针一致。
5. 点「抓第 1 轨」→ 输出 WAV，2352 字节/扇区、44.1k/16bit/立体声，时长与 TOC 一致。
6. **CRC32 对账**：同一轨 PCM CRC32 与 `rip_probe3.py` 对同一盘的输出一致。
7. 停止播放/关闭宿主后无残留句柄、无崩溃；禁用插件后入口消失。

**范围外**：secure 重读、C2 纠错、gap 检测、CUE/日志、元数据、编码器、曲库入库（M2–M5）。

## 1. 已确认硬事实（设计基座）

| 事实 | 依据 |
|---|---|
| `SpwPlugin extends org.pf4j.Plugin`，构造入参 `PluginContext`，可覆写 `start/stop/delete/update` | javap |
| `WorkshopPluginManager extends org.pf4j.DefaultPluginManager`，构造 `Path...` | javap |
| `.spmod` = zip，内部要求 **`classes/META-INF/MANIFEST.MF`**；`Plugin-Id` / `Plugin-Version` / `Plugin-Provider` 缺任一抛 `PluginRuntimeException` | `PluginManifestReader` 反编译 |
| API 依赖钉死：`pf4j 3.12.0`、`compose.ui:ui-desktop 1.12.0`、`foundation-desktop 1.12.0`、`io.github.moriafly:salt-ui-desktop 2.9.0-alpha02`、`kotlin-stdlib 2.4.20` | JitPack pom |
| Hook 签名：`hookMethod(String,String,List<String>,MethodHook)`、`afterComposable(String,String,String,List<String>,int,ComposableHook)`、`appendContent(String,String,String,String,Class<S>,List<String>,int,ContentHook<S>)`、`replaceComposable(...)` | javap `HookRegistrar` |
| 权限枚举：`KEY_BINDINGS / LIBRARY_READ / LIBRARY_WRITE / CLASS_TRANSFORM` | javap |
| CD 页可 hook：`androidx/compose/ui/ݩ.Ϳ(androidx.compose.ui.?, Composer, int, int)` | javap + 导航 lambda 常量池 |
| 宿主 JVM：JRE 25.0.4.1，`--enable-native-access=ALL-UNNAMED`，`-Xmx512M`；无 `java.net.http`、无 `jdk.zipfs` | `app\Salt Player for Windows.cfg` + `runtime\release` |
| SPTI：`\\.\F:`，需 `GENERIC_READ\|GENERIC_WRITE`，无需管理员；`IOCTL_SCSI_PASS_THROUGH_DIRECT=0x4D014`；自然对齐；**单次 ≤16 扇区** | Python 实测 |
| spmod Gradle 插件：扩展名 `spmod`，`SpmodExtension` 是 public 字段（`PluginClass/PluginId/PluginVersion/PluginProvider/PluginName/PluginDescription/PluginHasConfig/PluginOpenSourceUrl/PluginPermissions`），权限为 `List<com.xuncorp.spw.workshop.gradle.PluginPermission>` 枚举 | javap + CFR |

## 2. 工程骨架

位置 `projects/Salt-Audio-Copy/mod/`：

```
mod/
├─ settings.gradle.kts      # jitpack + resolutionStrategy.useModule 修正 groupId
├─ build.gradle.kts         # kotlin 2.4.20 + compose 插件 + spmod{}
├─ gradle.properties
├─ gradlew(.bat) + gradle/wrapper/
└─ src/main/kotlin/com/saltaudiocopy/rip/
   ├─ SaltAudioCopyPlugin.kt  Diag.kt
   ├─ ui/RipPanel.kt  ui/HookTargets.kt
   ├─ scsi/Native.kt  scsi/Sptd.kt  scsi/Cdrom.kt
   ├─ rip/Toc.kt  rip/RipEngine.kt
   └─ cfg/RipConfig.kt
   + src/main/resources/preference_config.json
   + src/test/kotlin/...（纯 JVM 单测）
```

版本矩阵：JDK 25 / Kotlin 2.4.20 / Compose 1.12.0 / Gradle 9.8.1 / JVM target 25。
依赖仅 `compileOnly(api dev22)` + `compileOnly(kotlin("stdlib"))`，不打包第三方库（M1 只用 JDK 自带 FFM/zip/io），目标 `.spmod` < 200 KB。

## 3. 打包契约

`build.gradle.kts` 用官方插件 `com.xuncorp.spw.workshop` version `0.1.0-dev22`，并在 `settings.gradle.kts` 的 `pluginManagement.resolutionStrategy.eachPlugin` 中：

```kotlin
if (requested.id.id == "com.xuncorp.spw.workshop")
    useModule("com.github.Moriafly.spw-workshop-api:spw-workshop-gradle-plugin:${requested.version}")
```

`spmod { PluginId="com.doggybear233.saltaudiocopy"; PluginClass="com.doggybear233.saltaudiocopy.SaltAudioCopyPlugin"; PluginVersion="0.1.0"; PluginProvider="saltaudiocopy"; PluginName="盐音抓轨 (Salt Audio Copy)"; PluginDescription="CD 抓轨（EAC 风格）"; PluginOpenSourceUrl="..."; PluginHasConfig=true; PluginPermissions=listOf(PluginPermission.CLASS_TRANSFORM) }`

构建后校验 zip：①`classes/META-INF/MANIFEST.MF` 存在 ②含 4 个关键属性 ③未打入宿主已有类。失败即构建失败。
回退：官方插件不可用时手写 Zip 任务拼 `classes/` + `lib/`。

## 4. 生命周期与诊断

- `start()` 注册 hook、打日志、toast 一次；`stop()` 注销 `HookHandle`、释放设备句柄；`delete()` 清理。注册动作幂等。
- 诊断三通道：`Ui.toast`、`Diag` 环形缓冲（面板可看）、`pluginPath/logs/saltaudiocopy.log`。

## 5. 宿主内嵌入口（挂载点与降级链）

- **P0**：`afterComposable("androidx/compose/ui/ݩ", "Ϳ", "androidx/compose/ui/ݩ", listOf(), 50)`，在 CD 页追加面板。
- **P1**：同目标改 `appendContent`。
- **P2**：`hookMethod` 挂未混淆的 `com.xuncorp.voxzen.ui.navigation.ScreenRouteKt.getTopLevelScreenRoutes()` 定位 CD 路由后再 hook content。
- **P3**：配置页 `button` → 宿主窗口内 Compose `Dialog`。
- **跨版本**：启动时读 `ComposableSingletons$AppNavigation_desktopKt` 字节，常量池解析出 CDToolkit 的 invokestatic 目标，覆盖硬编码名；失败回退硬编码。

面板内容：驱动器型号/固件、TOC 表、输出目录、`[扫描][抓第 1 轨][停止]`、进度条（扇区/速度）、日志折叠区。

## 6. SPTI 传输层（FFM，不用 JNA）

`java.lang.foreign` + kernel32 downcall。

| 步骤 | 实现 |
|---|---|
| 打开 | `CreateFileW("\\.\F:", 0xC0000000, 3, NULL, 3, 0, NULL)`，返回 -1 失败 |
| 请求 | `DeviceIoControl(h, 0x4D014, buf, 56, buf, 56, &ret, NULL)` |
| 布局 | `Length:u16@0, ScsiStatus:u8@2, PathId@3, TargetId@4, Lun@5, CdbLength@6, SenseInfoLength@7, DataIn@8, pad@9, DataTransferLength:u32@12, TimeOutValue:u32@16, pad, DataBuffer:ptr@24, SenseInfoOffset:u32@32, pad, Cdb:u8[16]@36` → sizeof 56，**禁止 pack** |
| INQUIRY | CDB `12 00 00 00 24 00`，36 B |
| READ TOC | CDB `43 02 00 00 00 00 00 03 24 00`（format0+MSF），804 B；条目 8 字节，ADR 高半字节/CTRL 低半字节，CTRL=0 为 audio |
| READ CD | `BE 04 [LBA 24bit BE @2..5] 00 00 [n @8] F8`，n≤16，2352 B/扇区；**不用 0x14（本驱动 err=121）** |
| 错误 | 5=权限/独占、87=参数（多为 >16 扇区）、121=超时；sense 本轮只记录 |

并发：单工作线程 + `AtomicBoolean` 取消，SPTI 调用全串行（模拟 EAC 单请求），UI 经 `StateFlow` 读进度。

## 7. 抓轨闭环

1. 扫描：INQUIRY → READ TOC → `DiscToc`；`LBA = (m*60+s)*75+f - 150`。
2. 抓取：按 16 扇区步进读，追加 PCM，更新 CRC32 与进度。
3. 输出：44.1k/16bit/LE/立体声 WAV（44 字节头，data 长度回填）。
4. 命名（M1 临时）：`<outDir>/<albumArtist|Unknown>/<albumTitle|discid>/01.wav`。
5. 校验：面板显示 `read=N, errors=0, crc32=0x...`。
6. 边界：跨 lead-out 截断；data 轨/0 轨拒绝并提示。

## 8. 配置与数据

`preference_config.json` 三项：`autoScan`(switch)、`outputRoot`(edittext，默认 `%USERPROFILE%\Music\Salt Audio Copy`)、`openPanelOnCdPage`(switch)。运行时用宿主 `ConfigManager`；数据在 `pluginPath`，日志 `pluginPath/logs/`。

## 9. 测试

纯 JVM 单测：`TocTest`（真实 TOC 字节→轨表/lead-out）、`CdbTest`（CDB 黄金字节 + LBA 大端）、`WavTest`（头与长度回填）、`SanitizeTest`（非法字符清洗）。
宿主内手工验收按 §0 逐条；对账脚本 `projects/Salt-Audio-Copy/rip_probe3.py F <track>`。

## 10. 风险与回退

| 风险 | 处置 |
|---|---|
| `afterComposable` 参数语义与签名不完全对应 | M1-A spike 先只 toast 验证；失败走 P1/P2/P3 |
| 混淆名升级即碎 | 常量池动态解析 + 面板失败不影响抓轨核心 |
| FFM 布局写错 → err=87 | 单测断言 sizeof=56 与偏移 |
| 与宿主 BASS CD 播放抢设备 | `FILE_SHARE_READ\|WRITE`；失败提示先停止播放 |
| 宿主缺 `java.net.http`/`jdk.zipfs` | M1 不依赖（HttpURLConnection 留给 M4） |

## 11. 实施顺序

1. **M1-0** wrapper + 骨架 + `spmod{}` + 空插件（只 toast）→ 导入启用看到 toast（证明打包契约）。
2. **M1-1** Diag + 面板骨架（P0，先显示 hook ok）。
3. **M1-2** FFM + INQUIRY + READ TOC → 真实驱动器与 11 轨。
4. **M1-3** READ CD 分块 + CRC32 + 取消 → 进度条。
5. **M1-4** WAV 落盘 + 对账 + 单测 → 勾完验收。

## 12. 假设

- 宿主固定 1.19.0；光驱 `HL-DT-ST BD-RE BP71N @ F:`；盘 Fantôme（11 轨 / lead-out 224166）。
- 音频 CD 无 CD-TEXT；M1 不读。
- 用户接受 M1 先出未压缩 WAV。
- 不修改宿主安装目录、不写注册表；卸载 = 禁用 + 删除。

## 13. 实施进展

**M1（已完成，真机验证）**：Gradle/Kotlin 工程、spmod 打包、FFM+SPTI（INQUIRY / READ TOC / READ CD 16 扇区分块）、
WAV 落盘、配置页按钮入口、日志、扫描线程化、非音频 CD 拒绝、Sense 解码、整盘/单轨两个入口。
真机基线：21 轨盘第 1 轨 `crc32=0xFD497FAA`，WAV 16,581,644 B = 7050×2352+44，与独立重算一致；平均 5.1x。

**M2（功能层已完成）**：元数据（MusicBrainz discid + Cover Art Archive `front-<size>` + gnudb 回退）、
DiscID/CDDB 算法（单测期望值由 Python 独立实现生成）、命名模板引擎（NFC/清洗/保留名/码点截断）、
CUE（UTF-8 BOM + CRLF，含 REM DISCID/CDDBID）、日志（每轨 CRC/重试/用时 + 合并指纹）、
双读校验（secureMode，重试计数）、采样级 offset、设置持久化（ConfigManager）、部分轨抓取（onlyTracks）。
端到端实测：Sonny Boy OST 目录 `Various Artists/TV ANIMATION Sonny Boy soundtrack/`，
文件 `01 少年少女 TV ver.wav`，封面 80,320 B JPEG，CUE/日志样例见 `samples/`。

**自测**：`gradlew test` 41 用例全绿（含真机用例：TOC、单扇区、16 扇区、双读一致；联网用例 2 个）。

**未做**：Gap（INDEX 00）检测、C2 纠错重读、AccurateRip 校验、FLAC 编码、面板 UI（Compose Hook）。
