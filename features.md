# Salt Player 抓轨 Mod — 需求定义 v2（范围已定：M 档 + 元数据子系统）

调查日期：2026-10-19　对象：Exact Audio Copy 1.8（2024-07-15 build）
测试硬件：`F: HL-DT-ST BD-RE BP71N rev 1.02`（USB 外置蓝光多驱）
测试介质：**音频 CD，10 轨，lead-out LBA 194092（43:07:67）**

> v2 变更：① 用户已拍板范围（M 档 + 「所有获取专辑信息相关的功能」）；② 元数据子系统从"半本体"升级为独立子系统并给出可实现的协议设计（§2.6）；③ 真机实测替换了 v1 的推测（§3.5）；④ 刻录/音频编辑器/驱动器特性数据库**永久排除**；⑤ 元数据源定为 MusicBrainz，AR 只查不提交。

---

## 0. 结论先行

EAC 的 10 大功能群中只有 4 群是抓轨软件本体：**读盘校验、gap/index、输出/CUE/日志、校验元数据**。
刻录、音频编辑器、驱动器特性数据库属于 2000 年代的软件形态，不复刻。

**本项目范围 = M 档（对标 EAC 核心，输出可与 EAC 逐字节等价）+ 元数据子系统（超出 EAC 的能力）**。

一条贯穿全篇的取舍原则：**宿主已有的能力不复刻，只桥接** —— 播放/试听、标签写入、封面展示、歌词、
曲库管理、格式解码全部复用 Salt Player，mod 只负责"把盘变成文件、把文件变成库条目"。

**元数据是本项目的差异化重点**：EAC 只有 freedb（已停服）+ CD-TEXT + 商业 GD3；
我们要做到"放盘 → 自动认出专辑 → 曲名/艺人/年份/厂牌/封面全部就位 → 一键入库"。

---

## 1. 调查证据（三份独立清单，互相校验）

| 证据 | 内容 | 提取方式 |
|---|---|---|
| 官方文档 `Documentation\EAC.txt` | 官网 Features **34 条** + 技术说明（secure 算法、AR、offset、gap） | 转码 `out/docs/EAC.utf8.txt` |
| **语言文件 `Languages\English.txt`** | **135 个界面分区、2,375 条 UI 文案**（每个对话框的每个复选框都在内） | `langdump.py` → `out/captions.txt` |
| PE 菜单资源（11 个 RT_MENU） | 主菜单/右键菜单/CD Layout 菜单全部命令 | `menus2.py` |
| SCSI 层逆向 | 7 种读命令、CDB 逐字节、双后端、缓存击破 | `architecture.md` §3–§5 |
| 设置键扫描 | 约 60 个 `EAC.ini` 键 | `allstrings.py` |
| 本机真机实测 | SPTI 全链路 + 整轨读取 CRC | `spti_probe.py` / `rip_probe3.py`（§3.5） |

135 个分区的职能分布：基础选项与资源 ~25、**抓轨主流程/分析/gap/日志/元数据 ~30**、
音频编辑器 ~25、刻录 ~12、安装向导 14、状态与错误消息表 1（123 条）。

---

## 2. 功能全清单（逐项判定）

判定：**★ 必做** ｜ **○ 可选/降级** ｜ **✗ 不做**

### 2.1 读盘与错误处理（本体）

| 功能 | EAC 实现要点 | 判定 |
|---|---|---|
| TOC 读取（MSF/扩展 MSF/会话/多区段） | READ TOC 0x43 format 0/1/2 | ★ |
| CD-TEXT 读取 | READ TOC format 5；EAC 先用 `CDB[0]=0x43, CDB[2]=5` 探测 | ★（元数据第一优先来源） |
| UPC/EAN + ISRC | READ TOC format 2 + 子通道 Q | ★（CUE 与 MB 匹配都要） |
| 盘片识别 | INQUIRY + GET CONFIGURATION + TOC 判"是不是音乐 CD" | ★ |
| 7 种读命令 + 自动探测 | MMC(0xBE)/MMC+C2/D8/D4-1/D4-2/D5/READ10 | ★ MMC + 自动探测 |
| Secure 模式（多读多数表决） | 16 次重读中 8 次一致才算通过，最多 1/3/5 轮 → **最坏约 82 次读** | ★ 核心 |
| C2 错误纠错 | 0xBE 带 C2 位（CDB[1] bit4:3、CDB[9] bit1） | ★ 有则用 |
| 缓存击破 | 越界多读再丢弃 | ★ 核心 |
| Fast 模式 | 23 块取 2 个同步块校验 | ○ 降级 |
| Burst 模式 | 无校验，只报可疑位置 | ○ 预览用 |
| 自动降速/回速 | SET CD SPEED 0xBB | ★ |
| 错误位置报告 + 试听 | 记录可疑采样位置 | ★（复用宿主播放器） |
| Overread | 读入 Lead-In/Out | ○ V2 |
| 采样 offset 校正 | ±采样级；`Fill up missing offset samples with silence` | ★ 核心 |
| 轨间同步（非精确流驱动器） | 轨交界重同步 | ○ 自动判定 |
| 静音裁剪 | 去首尾数字静音 | ○ 可选（影响 AR 长度） |
| 跳过策略 | 出错跳过/超时跳过 | ★ |
| 驱动器锁盘 | PREVENT/ALLOW MEDIUM REMOVAL | ○ |
| 冷却 | 每 N 分钟停 M 分钟 | ○ |

### 2.2 Gap / Index 分析（本体）

| 功能 | EAC 要点 | 判定 |
|---|---|---|
| 三种 gap 检测法 | A（子通道）/ B（TOC+启发）/ C（静音扫描） | ★ A+B |
| 三档精度 | Inaccurate / Accurate / Secure（交叉复核） | ★ Accurate 默认 |
| 索引 >1 检测 | 子通道 Q 的 index 字段 | ★ |
| 静音检测 | 扫描 gap 是否全静音 + peak level | ○ |
| 三种 gap 归属 | 并入前轨 / 归入正确轨 / 丢弃 | ★ |
| 性能 | EAC 约 1 秒/轨，误差 <2–3 扇区 | ★ 目标 ≤1 s/轨 |

### 2.3 输出与命名（本体）

| 功能 | 判定 |
|---|---|
| 单文件整盘 WAV + CUE（Copy Image） | ★ |
| 每轨一文件 | ★ |
| 每轨一文件但 gap 三态（With / Corrected / Leave Out） | ★ 前两种 |
| 按索引切分 / 任意区间抓取 | ○ V1.2 |
| 命名模板（30+ 变量）+ 非法字符替换 + VA 方案 | ★ |
| `.m3u` 播放列表 | ★ 便宜 |
| headerless WAV（供特殊编码器） | ○ |
| 覆盖确认 / 空间预检 | ★ |

### 2.4 CUE 与日志（本体，护城河）

| 功能 | 判定 |
|---|---|
| CUE 风格 EAC / 标准（+ Burnnn/Feurio/Nero 可选） | ★ |
| CUE 完整性：INDEX 00/01、PREGAP、FLAGS（pre-emphasis/copy）、CATALOG、ISRC、CD-TEXT 块 | ★ |
| CUE 校验（38 条行号级规则） | ○ 只读 + 基本编辑 |
| 提取日志：逐轨 CRC、Track quality %、peak level、速度、读模式、offset、AR 结果、**日志校验和** | ★ 核心 |
| CheckLog.exe 验签 | ✗（但我们日志带校验和） |

### 2.5 编码（桥接宿主）

| 功能 | 判定 |
|---|---|
| 外部命令行编码器 + `%变量%` 替换 + 返回码检查 | ★（FLAC/MP3/Opus 任意） |
| 并行编码队列（挂起/删除） | ★ |
| 第二编码器（同时出两种格式） | ○ |
| 内置 LAME DLL / Gogo / ACM | ✗ |
| 压缩 offset 校正（有损往返） | ○ |
| ID3v1/v2 写入细节 | ✗ 交给宿主标签系统 |
| 解码压缩→WAV | ✗ 宿主 FFmpeg 已有 |

> 宿主自带 `ffmpeg-x64.dll`(105 MB) + `spwdecode.dll`，编码直接桥接，不必自己调外部 exe。

### 2.6 元数据子系统（用户重点，超出 EAC）

EAC 的元数据只有三渠道：CD-TEXT、freedb/CDDB（**已停服**）、商业 GD3，外加手填。
本项目的目标是"**放盘即得完整专辑信息**"，渠道多、可交叉验证、失败可手改。

#### 2.6.1 数据来源（按优先级链式尝试）

| 优先级 | 来源 | 提供什么 | 协议/接口 | 状态 |
|---|---|---|---|---|
| 1 | **盘内 CD-TEXT** | 专辑名、艺人、曲名、作曲、UPC | `READ TOC format 5`（0x43） | 待实测（本机 CD 无 CD-TEXT） |
| 2 | **UPC/EAN（MCN）** | 条码 → 精确发行版 | `READ TOC format 2` | 待实测 |
| 3 | **MusicBrainz DiscID** | 发行版候选 + 完整曲目 + 艺人 + 日期 + 厂牌 + 格式 | `GET /ws/2/discid/{id}?fmt=json&inc=artists+recordings+release-groups` | ✅ 已打通 |
| 4 | **Cover Art Archive** | 封面 front/back/disc/booklet + 缩略图 | `/release/{mbid}/front-500`、`/release/{mbid}`（JSON 列全部图） | ✅ 已打通 |
| 5 | **gnudb（freedb 继任者，仍在线）** | CDDB 记录：专辑/艺人/年份/流派/曲名 | `http://gnudb.gnudb.org/~cddb/cddb.cgi?cmd=cddb+query/read` | ✅ 已打通 |
| 6 | **AcoustID 指纹**（可选） | 无 ID 命中时按音频指纹反查 | Chromaprint + `api.acoustid.org/v2/lookup` | V2 |
| 7 | **本地缓存/历史** | 同一 ID 上次结果 | 本地 SQLite/JSON | ★ |
| 8 | **手动编辑** | 全部字段 | 自建表单 | ★ |

> **CDDB 为什么必须保留**：MB 的 exact-discid 命中率对日版/亚洲版/老盘偏低。
> 实测这张盘（松田聖子 / Strawberry Time，1987）在 MB 里能命中（靠 CAA 关联），
> 但很多同年代的日版盘只在 gnudb 有记录。两条链都要有，先本地 CD-TEXT → 并行查 MB + gnudb → 合并候选。

#### 2.6.2 关键算法（**已用真实数据校验**）

**MusicBrainz DiscID**（已与 MB 官方记录逐字符比对通过）：

```
输入串 = %02X(首轨号) + %02X(末轨号) + %08X(lead-out + 150)
        + 99 × %08X(各轨起始 (LBA + 150)，不足补 0)
discid = base64(sha1(输入串))  然后  '+'→'.'  '/'→'_'  '='→'-'
```

> **最容易踩的坑：所有偏移都要 +150（2 秒 lead-in）**。用裸 LBA 算出来的 ID 是错的（MB 直接 400）。
> 实测：本机 TOC → `mHEn5XPh1chsnQA5EbR0_npgXSs-`，与 MB 为 release
> `d3d40b24-50ef-40cb-933c-9d8048e12919` 记录的 discid **完全一致**（该记录 sectors=194242=194092+150）。

**CDDB / freedb ID**（已用 gnudb 反查校验通过）：

```
total = Σ floor(((next_track_lba+150) - (track_lba+150)) / 75)   # 逐轨向下取整后求和
n     = (total - floor((track1_lba+150)/75)) mod 0xFF
crc   = total 的迭代数字和（反复各位相加直到一位）
id    = %02x%02x%04x  (n, crc, floor((leadout+150)/75))
```

> 实测：`1f090a1d` → gnudb 精确命中「松田聖子 / Strawberry Time」(1987, J-Pop, 10 轨曲名齐全)。
> 注意 **n 的取模是 0xFF（255）而不是 256**，且 total 要用"逐轨取整后相加"，不能用"总时长取整"。

#### 2.6.3 字段清单

| 层 | 字段 |
|---|---|
| 专辑 | 标题、专辑艺人、艺人排序名、发行日期、原始发行日期、厂牌、目录号、条码、国家、介质格式、碟号/总碟数、release-group 类型、MB release id、MB release-group id、封面路径 |
| 曲目 | 标题、艺人、作曲、作词、编曲、ISRC、音轨号/总数、时长、MB recording id、MB track id、流派 |
| 兼容 | CUE 的 `TITLE/PERFORMER/CATALOG/ISRC/REM DISCID/REM MBID/REM COMMENT`、日志头部 |

#### 2.6.4 封面策略

- 尺寸：`front-500`（实测 97,698 B JPEG，够用）；需要时再取 original。
- 落盘：`cover.jpg` + 可选 `back.jpg`；嵌入标签交给宿主标签系统。
- 缓存：`%LOCALAPPDATA%/SaltRip/covers/{mbid}-{type}-{size}.jpg`。
- 无封面：宿主占位图 + "在 CAA 中搜索"链接。

#### 2.6.5 网络与合规（实测约束）

- **MusicBrainz 限速 1 req/s，超了直接 503**（本次实测遇到两次）→ 必须串行化 + 指数退避重试 + 结果缓存。
- 必须带可识别 `User-Agent`：`SaltRip/0.1 ( https://github.com/<user>/salt-rip )`。
- **gnudb 的 `hello=` 里的客户端名必须是已注册的**，否则 `500 Unknown application`；
  实测 `cddb` / `eac` / `ripper` 可用，`SaltRip` 未注册被拒 → 上线前在 gnudb 注册客户端名（免费），
  或先用已注册名。
- 运行时**没有 `java.net.http`**（jlink 未打包）→ 用 `HttpURLConnection`，或复用宿主 classpath 的 ktor client。
- 请求走后台线程，5 s 超时、1 次退避重试；失败静默降级到下一来源，绝不阻塞抓轨。
- 隐私：AR 只查询不提交；不上传指纹；AcoustID 默认关闭。

#### 2.6.6 UI 流程

```
放盘 → TOC/CD-TEXT 就绪 → 并行：DiscID + CDDB ID 计算 + CD-TEXT 解析 + MB 查询 + gnudb 查询
     → 有 CD-TEXT：先填，再与网络结果交叉校验（不一致处标黄）
     → 无 CD-TEXT：显示候选列表（封面缩略图 + 年份 + 厂牌 + 轨数 + 来源标记）
     → 用户点选（唯一高置信候选可自动选）
     → 曲目表逐行可改（曲名/艺人/作曲）+ 整盘字段编辑
     → 勾选"写标签 / 生成 CUE / 下载封面"
     → 开始抓轨
```

#### 2.6.7 实测记录（2026-10-19，松田聖子 / Strawberry Time）

| 步骤 | 结果 |
|---|---|
| TOC | 10 轨，lead-out LBA 194092 |
| MB DiscID | `mHEn5XPh1chsnQA5EbR0_npgXSs-`（与 MB 记录一致） |
| MB 查询 | 200，1 个候选：Strawberry Time / 松田聖子 / 1987-05-16 / JP |
| 封面 | `front-500` 200，97,698 B image/jpeg；CAA 1 张（Front） |
| CDDB ID | `1f090a1d` |
| gnudb | 210 精确命中，曲名 10 条（日文）齐全，DYEAR=1987 DGENRE=J-Pop |


### 2.7 AccurateRip（本体，质量证明）

| 功能 | 判定 |
|---|---|
| 按轨校验（整轨 CRC + AR 库比对 + confidence） | ★ 核心 |
| AR 双 CRC（v1 每 450 扇区窗口 / v2 全轨） | ★（算法已从 CUETools 逆向，`architecture.md` §7） |
| offset 校准（多张 CD 反推） | ○ V2 |
| 结果提交 | ✗ 只查 |
| 离线库 | ○ |

### 2.8 音频编辑器 → **✗ 永久排除**

Wave 编辑器（`Sound Processing` 92 条文案）：剪切/粘贴/混音粘贴、淡入淡出、回声/相位/镶边/混响、EQ、
DC offset、反转、静音插入、标准化、插值、glitch 移除、pop 检测、降噪（需 profile）、FFT 频谱、
波形比较、撤销栈、循环录音、按 CUE 分割。**只保留降级功能：可疑位置定位 + 调宿主播放。**

### 2.9 刻录 → **✗ 永久排除**

CD Layout Editor、写 CD-R/RW、TAO/DAO/SAO、BurnProof、擦除、写特性探测、offset 测试碟、CDRDAO。

### 2.10 平台/体验/自动化

| 功能 | 判定 |
|---|---|
| 配置向导（12 步）→ 简化为 1 屏 | ○ |
| 驱动器特性探测（accurate stream / 缓存 / C2，1–4 分钟） | ★ 探测做，**内置型号数据库 ✗** |
| 语言文件 / Profile 导入导出 | ✗ 宿主 i18n；○ V2 |
| 命令行开关（nocdtext/notestunit/nospeedsel/noreadsub） | ○ 内部用 |
| 提取后动作（关机/休眠/弹盘/通知） | ○ 只做"完成后通知 + 可选弹盘" |
| 优先级 / 冷却 / 锁盘 | ○ |
| 多驱动器并行 | ○ V2 |
| **断点续抓（EAC 没有）** | ★ 我们的改进点 |

### 2.11 插件体系 → ✗（mod 本身就是插件）

---

## 3. 与宿主的边界

| 关注点 | 宿主能力 | Mod 策略 |
|---|---|---|
| 播放/试听 | `WorkshopApi.playback` | 直接用 |
| 标签读写 | 宿主标签系统（classpath 有 jaudiotagger） | 桥接，不自建 |
| 曲库入库 | `RepoService.rebuildMusicLibrary(...)` 等（混淆类，需反射） | 写文件后触发重扫 |
| 封面/歌词展示 | 宿主已有 | 复用；封面由我们下载 |
| 编码 | 宿主 FFmpeg + spwdecode | 桥接 |
| 网络 | **无 `java.net.http`** | `HttpURLConnection` |
| UI | **必须嵌进宿主界面**（用户硬要求） | 待定，见 §3.6 |

### 3.5 真机实测（2026-10-19，音频 CD）

| 项 | 实测结论 |
|---|---|
| 权限 | **不需要管理员**；但 `\\.\F:` 必须 `GENERIC_READ\|GENERIC_WRITE` 打开（只读 → `err=5`） |
| 结构体 | `SCSI_PASS_THROUGH_DIRECT` 必须自然对齐（x64 sizeof=56）；`pack(1)` 直接失败 |
| **单次传输上限** | **16 扇区 = 37,632 B**；17+ 扇区 → `DeviceIoControl err=87`（参数错误）。这条是 v1 未知的坑，已实测确定 |
| **每扇区成本** | 1 扇区/次 = 15 扇/s（约 68 ms/次 IPC 开销）；4/8/16 扇区/次 ≈ **500 扇/s ≈ 3.3x** |
| 整轨实测 | 第 1 轨 20,115 扇区（268.2 s）：26.63 s 读完 = **5.0x**，0 错误，`crc32=0xF3B439F2`，`md5=47433b606ce422746e2855fb5fd23564` |
| TOC | 10 轨全 audio（ctl=0），lead-out LBA 194092；条目 8 字节，**ADR 高半字节 / CTRL 低半字节**（v1 曾误读） |
| CDB 标志 | `CDB[9]=0xF8` 正常；`0x10`、`0x08` 同效；**`0x14`/`0xFC` → `err=121`（信号量超时）**，不要照抄 EAC 的 0x14 |
| CDB[1] | `0x04`（Expected Sector Type = CD-DA）必需 |
| SET CD SPEED | `0xBB` 被接受（scsi status 0），但短读吞吐仍受限于启动开销 |
| 缓存 | 同一区域二次读 203 ms vs 292 ms → **有缓存但不极端**；仍需击破逻辑 |
| 盘片判定 | GET CONFIG 当前 profile：音频 CD=`0x0008`，BD-R 数据盘=`0x0040` |

→ **M0/M1 传输链路已打通**，且拿到了第一组可比对的 CRC（可用于后续"与 EAC 逐字节等价"验收）。

元数据链路也已端到端打通（详见 §2.6.7）：TOC → MB DiscID（与官方记录逐字符一致）→ release + 封面 97 KB；
CDDB ID → gnudb 命中 10 条日文曲名。**两条来源都可作为 M4 的实现基础。**

### 3.6 待解决的技术前提（影响架构，不影响功能清单）

1. **宿主 UI 插入点**：宿主是 Compose Desktop（`androidx.compose.*` 在 classpath），但宿主类名被混淆
   （`com/xuncorp/voxzen/data/model/Ϳ` 之类）。可选路线：
   - (a) `appendContent` / `replaceComposable` 挂到"设置 → Mod 管理"类页面（`ScreenRoute$ModConfigContent` 存在）；
   - (b) 寻找宿主是否暴露"新增页面/侧栏项"的 route 注册 API（更干净）；
   - (c) 最坏情况：Hook 主窗口组合函数，在侧栏注入入口。
   → 需要一次专门的宿主侦察（反编译 app classpath / 运行时 attach）来定案。
2. **混淆的 Room 实体可否反射构造**（决定"直接入库"还是"触发重扫"）。
3. 宿主 `ModConfigContent` 的调用约定（决定配置页能否复用）。

---

## 4. 范围（已定）

| 项 | 决定 |
|---|---|
| 档位 | **M 档全部** |
| 追加 | **元数据子系统**（§2.6，网络获取封面/专辑名/歌名/艺人/年份等） |
| 排除 | 刻录、音频编辑器、驱动器特性数据库（永久） |
| 平台 | Windows only（V1） |
| 校验 | AccurateRip 只查不提交 |
| 元数据源 | MusicBrainz + Cover Art Archive（+ CD-TEXT 优先） |

工期估算：抓轨核心 M1–M3 约 4 周；元数据子系统 1.5 周；校验/编码/入库 1.5 周；总计 **6–8 周**（单人）。

---

## 5. 性能：EAC 的瓶颈与我们能超越的地方

1. 限速项是**物理读 + 每 IOCTL 的往返开销**（实测 1 扇区/次只有 15 扇/s → 必须 ≥8 扇区/次，上限 16）。
2. EAC 串行单轨、抓轨与编码重叠；我们用同样的流水但共享宿主线程池。
3. 可超越点：
   - **断点续抓**（EAC 无，中断即重来）；
   - **智能重试预算**（按错误密度决定轮数，不固定 16 次）；
   - **缓存击破自适应**（先测缓存容量再定越界 stride）；
   - 抓轨与入库/编码并行；
   - 元数据在放盘瞬间就开始预取（EAC 是抓完才查）。

---

## 6. 里程碑（修订）

| 阶段 | 交付 | 验收标准 |
|---|---|---|
| **M0** ✅ | 光驱枚举 + INQUIRY/TOC + 单轨读取 | 已达成：10 轨 TOC + 整轨 CRC（§3.5） |
| **M0.5** | 宿主 UI 插入点定案 + 插件脚手架能跑起来 | 宿主里出现一个我们自己的入口 |
| **M1** | 轨道模型 + 单轨 secure 抓取（0xBE + 多读表决 + 缓存击破 + C2） | 与 EAC 同盘同轨 **CRC 一致** |
| **M2** | 整盘 + offset + 重试策略 + 速度控制 | 与 EAC 日志全轨 CRC 一致 |
| **M3** | gap/index + CUE + 命名模板 + 日志（含校验和） | CUE/日志与 EAC 逐字段对齐 |
| **M4** | **元数据子系统**：DiscID + MB + CAA + CD-TEXT + 候选选择 UI + 封面 | 放盘 3 秒内出候选；任选一张流行 CD 能正确命中并下到封面 |
| **M5** | AR 校验 + 编码队列 + 入库 + 断点续抓 + 批量 | 一键：放盘 → 出文件 → 入库 → 可播；AR confidence 与 EAC 一致 |

---

## 7. 已确认 / 待确认

已确认（用户）：
1. 范围 **M 档全部**，并追加"所有获取专辑信息相关的功能"。
2. 刻录、音频编辑器、驱动器特性数据库 **永久排除**。
3. 元数据 = MusicBrainz；AR **只查不提交**。
4. 平台 Windows only；**UI 必须嵌进宿主**。

待确认：
1. 封面默认尺寸（500px 够吗？要不要同时存 original）。
2. 抓轨默认输出：**WAV 还是直接 FLAC**（宿主 FFmpeg 可直出 FLAC）。
3. 多候选 release 时，是否要"自动选最高分"还是"必须人工确认"。
4. 是否需要**批量模式**（一次放多张盘排队抓，V1 或 V2）。
