# 路径与命名模板设计（Salt Audio Copy v1）

> 用户要求（m00769）：文件夹默认 `%albumartist%/%albumtitle%`，文件名默认 `%tracknr2% %title%`，封面存同目录；
> 模板要**可视化编辑**（变量做成可追加的按钮块）；**中文/日文/韩文等字符不得乱码**。

---

## 1. 默认值

| 用途 | 默认模板 | 示例 |
|---|---|---|
| 专辑文件夹 | `%albumartist%/%albumtitle%` | `松田聖子/Strawberry Time` |
| 文件名 | `%tracknr2% %title%` | `01 夏の日の恋` |
| 封面 | 同目录 `cover.jpg` | `松田聖子/Strawberry Time/cover.jpg` |
| 附加 | `log.txt`（EAC 风格日志）、`album.cue` | 同目录 |

多碟规则：当 `%totaldiscs% > 1` 且模板里**没有** `%discnumber%` 时，自动在专辑文件夹后追加一级 `CD%discnumber%`（避免两碟互相覆盖）；用户显式写了 `%discnumber%` 则完全按模板走。

---

## 2. 变量（块）清单

### 专辑级
| 块 | 含义 | 空值兜底 |
|---|---|---|
| `%albumartist%` | 专辑艺术家 | `Unknown Artist` |
| `%albumtitle%` | 专辑名 | `Unknown Album` |
| `%year%` | 年份（4 位） | 不生成 |
| `%date%` | 发行日期 `YYYY-MM-DD` | 不生成 |
| `%genre%` | 流派 | 不生成 |
| `%label%` | 唱片公司 | 不生成 |
| `%catalognumber%` | 目录号 | 不生成 |
| `%barcode%` | 条码（MCN） | 不生成 |
| `%discnumber%` / `%totaldiscs%` | 碟号 / 总碟数 | 1 / 1 |
| `%format%` | 来源格式（`CD`） | `CD` |

### 曲目级
| 块 | 含义 | 说明 |
|---|---|---|
| `%tracknr%` / `%tracknr2%` / `%tracknr3%` | 1 / 01 / 001 | 补零位数不同 |
| `%title%` | 曲名 | 兜底 `Track NN` |
| `%artist%` | 曲目艺术家（合辑时与专辑艺术家不同） | 兜底 = `%albumartist%` |
| `%length%` | 时长 `M:SS` | |
| `%isrc%` | ISRC | 本盘无则空 |
| `%composer%` | 作曲 | 可选 |

### 文本块
- **分隔符块**：`/`（目录分隔）、` - `、` `（空格）、`.`、`_`
- **自定义文本块**：用户输入的固定串（如 `[FLAC]`、`CD1`）

---

## 3. 可视化编辑器（Compose，在抓轨向导的「选项」步 + 设置页入口）

```
┌─ 输出模板 ─────────────────────────────────────────────┐
│ 保存根目录  [ D:\Music                        ] [浏览]  │
│                                                        │
│ 专辑文件夹                                             │
│ ┌───────────────┐ ┌───┐ ┌──────────────┐              │
│ │ %albumartist% │ │ / │ │ %albumtitle% │  ← 块可拖拽排序 │
│ └───────────────┘ └───┘ └──────────────┘   ✕ 删除       │
│ [+ 添加块 ▾]                                           │
│ 预览：D:\Music\松田聖子\Strawberry Time                 │
│                                                        │
│ 音乐文件名                                             │
│ ┌─────────────┐ ┌───┐ ┌─────────┐                      │
│ │ %tracknr2%  │ │   │ │ %title% │                      │
│ └─────────────┘ └───┘ └─────────┘                      │
│ [+ 添加块 ▾]                                           │
│ 预览：01 夏の日の恋.flac                                │
└────────────────────────────────────────────────────────┘
```

交互细节：
- **添加块**：下拉分组（专辑字段 / 曲目字段 / 分隔符 / 自定义文本），点选即在末尾追加。
- **删除**：块右上角 `✕`；**重排**：长按拖动。
- **预览**：用当前元数据实时渲染两行完整路径（含扩展名、非法字符替换后的效果），下面再显示一个「📁 试写测试」按钮 —— 在目标目录用**日文+中文+韩文**文件名创建再删除一个探针文件，把"能不能写、会不会乱码"变成可验证结果。
- **高级**：切换成纯文本编辑（单行文本框），保存的仍是同一个模板字符串，二者双向同步。
- 模板字符串存储：mod 配置里是普通字符串（`folderTemplate` / `fileTemplate`），因此**不依赖可视化 UI 也能工作**（宿主设置页的 edittext 就是兜底入口）。

默认块集合随语言无关，模板里只出现 ASCII 的 `%name%`，用户看到的是块的显示名（本地化的中文/日文名），不改变存储值。

---

## 4. 非法字符与长度（必须做，否则写盘失败）

对**每个变量的取值**做清洗（对模板的字面文本不清洗）：

| 规则 | 处理 |
|---|---|
| `\ / : * ? " < > |` 及控制字符 0x00–0x1F | 替换为 `_` |
| 结尾的 `.` 和空格 | 去掉（Windows 不允许） |
| 保留设备名 `CON PRN AUX NUL COM1..9 LPT1..9` | 后加 `_` |
| 单个路径段 > 120 字符 | 截断到 120（按 Unicode 码点，不切半个代理对） |
| 整条路径 > 255 字符（未启用长路径时） | 从**文件名**的 `%title%` 处截断，保留轨号 |
| 空结果 | 用兜底值（见 §2） |

---

## 5. 字符编码（不乱码的关键）

| 环节 | 做法 | 原因 |
|---|---|---|
| 文件名 / 目录名 | `java.nio.file.Path`（Windows 走宽字符 API） | 原生 Unicode，无代码页转换，自然不会乱码 |
| 进程 | 宿主已带 `-Dfile.encoding=UTF-8`；Mod 不依赖默认编码，显式传 `StandardCharsets.UTF_8` | 避免 cp932/GBK 默认值 |
| 文本归一化 | 写入前做 **NFC**（`java.text.Normalizer`） | 避免 macOS 来源的 NFD 造成"看起来一样却不同名"；CJK 无影响 |
| `.cue` | **UTF-8 with BOM**（默认），可切 ANSI | 老播放器对无 BOM 的 UTF-8 会按本地代码页误读，BOM 能强制识别；CJK 曲名必须 |
| `.log` | UTF-8（无 BOM） | 纯文本，编辑器/浏览器自动识别 |
| FLAC 标签 | Vorbis Comment，**原生 UTF-8** | 格式规定，不存在乱码问题 |
| 控制台输出 | 不在 UI 打印路径（避免宿主控制台代码页）；日志走文件 | 排查时用日志 |
| 路径比较 | `equalsIgnoreCase` + NFC 后再比 | Windows 大小写不敏感 |

> 结论：只要**不用 ANSI 字符串 API、不拼 `cmd` 命令行**，CJK/日文/韩文文件名在 Windows 上不会乱码。唯一要小心的是 `.cue` 的编码（见上）和任何 `ProcessBuilder` 传参（统一用 `UTF-8` 列表参数，不经 shell）。

---

## 6. 配置项（存 mod 的 config.json）

```json
{
  "outputRoot": "D:\\Music",
  "folderTemplate": "%albumartist%/%albumtitle%",
  "fileTemplate": "%tracknr2% %title%",
  "coverFileName": "cover.jpg",
  "multiDiscFolder": true,
  "cueEncoding": "utf8bom",
  "sanitizeMode": "replace"
}
```

`folderTemplate` / `fileTemplate` 就是编辑器产出的字符串；两者都允许为空（空 = 直接用根目录 / 用 `%tracknr2% %title%`）。

---

## 7. 验收（M3）

| 用例 | 期望 |
|---|---|
| 日文专辑 `松田聖子 / Strawberry Time` | 目录与文件名正确，资源管理器无乱码 |
| 中文专辑 `周杰伦 / 范特西` | 同上 |
| 韩文专辑 `아이유 / Palette` | 同上 |
| 曲名含 `:` `/` `?` | 替换为 `_`，写盘成功 |
| 曲名 200 字符 | 截断到 120，不破坏代理对 |
| 多碟（2CD） | 自动 `CD1`/`CD2` 子目录 |
| `.cue` 用 fb2k 打开 | 中文曲名正常显示 |

---

## 8. 示例（默认模板的三种语言输出）

```
D:\Music\松田聖子\Strawberry Time\01 夏の日の恋.flac
D:\Music\周杰伦\范特西\01 爱在西元前.flac
D:\Music\아이유\Palette\01 이 지금.flac
D:\Music\Various Artists\Now That's What I Call Music 100\CD1\01 …
```
