# GitHub 发布工作流 · 新会话 Prompt（草稿）

> 用法：把「正文」一段复制到新会话（工作区选 `D:\deepseek\plugin`，preset `standard`），
> 方括号 `【】` 里是需要你确认或修改的占位；不改也能跑，agent 会在门禁处问你。

---

## 正文（从这里开始复制）

你现在负责把这个项目发布成一个完整的 GitHub 仓库与 Release。**先只做到 Draft，等我说"批准"再动手写远端。**

### 0. 开工前必做

1. 加载 skill：`github-release-pipeline`（流程与 gh 命令模板）、`dsh-session-forensics`（读历史对话）。
2. 用 `dsh-session-forensics` 的 `session-reader.mjs` 做会话考古（先 `list` 再 `search`，不要整份 dump）：
   - `node D:\deepseek\dsh\skills\dsh-session-forensics\scripts\session-reader.mjs list`
   - `... search "Salt Audio" --users-only`
   - 目标：确认要发布的文件在哪、之前定过哪些约束（版本号规则、许可、发布渠道、我明确说过"不要"的事）。
3. 打印一段「事实基线」给我：项目根路径、git 分支/最近提交、有无 remote、构建产物、测试数、缺哪些对外文件。**这一段必须全部有出处，不许猜。**

### 1. 项目事实

- 项目根：`D:\deepseek\plugin\Salt Audio Copy`
- 产品：盐专辑（Salt Audio Copy），Salt Player for Windows 的 CD 抓轨 mod（`.spmod`，本质是 zip）
- 插件 id：`com.saltrip.saltaudiocopy`；provider `saltrip`；当前版本 `0.43.0`（`mod\build.gradle.kts` 的 `version`，与 `Version.VALUE` 必须一致；上一版发布是 `0.42.0`）
- 构建：`mod\` 下 Gradle（`gradlew.bat`），产物 `mod\build\libs\*.spmod`
- 发布渠道：GitHub + Steam 创意工坊（两者产物要分开）
- 母项目：Salt Player（上游 `https://github.com/Moriafly/SaltPlayerSource`）。本 mod 的术语、版本号与 tag 风格、Release notes 结构都以母项目与工坊生态的既有页面为准——先研究、再设计，见阶段 B
- 已知缺口：无根 `README.md`、无 `LICENSE`、无 `CHANGELOG.md`、无 git remote；
  `mod\README.md` 引用了不存在的 `../architecture.md`

【版本号策略：沿用 0.x 继续，还是这次直接 1.0.0？默认按我上次说的"测试全过才发 1.0.0"处理】
【许可证：MIT / Apache-2.0 / 其他 / 暂不添加，默认给出选项让我选】
【仓库名：默认 `salt-audio-copy`，可见性 public】

### 2. 五个阶段（严格顺序，门禁不过不许跳）

**阶段 A · 深度审查（只读，逐函数级，可循环）**

审查粒度要求：**每一个函数、每一个变量/字段/常量、每一段文本（标识符、文案、注释、KDoc）都要过一遍，不许抽样。** 先跑 `Get-ChildItem mod\src -Recurse -Filter *.kt` 得到全量文件与行数，在报告开头给一张「文件 → 审查状态」覆盖表；表里没有出现的文件视为未审查，不许进入阶段 B。

- **A1 逐函数**：对每个函数写清 职责 / 入参与出参 / 边界与空值 / 异常路径 / 资源释放 / 线程与取消 / 复杂度。重点盯：半截数据、量纲与单位（扇区↔字节↔采样↔毫秒）、整数溢出与符号、编码、Windows 非 ASCII 路径、`runCatching` 与空 catch 吞错、单例里的可变状态、`require`/`error` 是否会穿透到 UI。
- **A2 逻辑与数据流**：同一概念在多处重复实现时是否一致。至少核对：预览路径（计划）vs 真实落盘、设置读取 vs 写回、CUE/日志/`album.json` 三处元数据口径、首次抓轨 vs 校验失败复抓的参数、跳过轨与总进度。
- **A3 命名与文本**：标识符是否名实相符、注释/KDoc 是否与行为一致（含已过期的注释）、魔法值与散落字面量、死代码与无调用者的函数、未使用参数、复制粘贴残留、是否该走 `Strings` 的硬编码文案。
- **A4 设计**：职责边界与分层、默认值是否安全（会不会静默覆盖用户数据）、可测试性、公开 API 是否过宽、配置非法值有没有校验、错误是否对用户可见。
- **A5 UI/交互**：文案是否误导、破坏性操作有无确认、进度/失败/降级是否可见、中英双语文案是否漏译、界面与 `design\mockups` 是否一致。
- **A6 构建与测试**：跑 `.\gradlew.bat test` 与 `.\gradlew.bat build`，记录「N tests / M failed」、哪些 live test 被跳过、产物 sha256；并判断现有测试是否真的覆盖 A1–A5 指出的风险（只测默认设置的对照测试不算覆盖）。文档一致性：版本号三处一致、README 引用路径真实存在、截图与 UI 一致。

产出两份文件：

1. `release/review-report.md` —— findings 表，编号 `F-001` 起，字段固定：`严重度(BLOCKER/MAJOR/MINOR/NIT)`、`位置(文件#L行)`、`现状(原文摘录一行)`、`问题`、`为什么错(触发条件+后果)`、`修复要求`、`验证方式`。每条必须有 `文件#L行` 证据；没有证据的怀疑单独标 `待确认`，不许混进结论。
2. `release/fix-prompt.md` —— **只含需要改的项**，写成可直接丢给原项目对话执行的指令：每条给 `文件#L行`、要改成什么（描述语义与约束，不写死实现）、**不许动的东西**（避免顺手重构扩大爆炸半径）、验收标准（测试名/断言/手工步骤）。按严重度排序，BLOCKER 在最前；按「一批能一起改完并单独验证」分批，每批给出改完后的自检命令。

**A7 硬停与循环**：审查完只报给我 `fix-prompt.md` 路径 + BLOCKER/MAJOR 计数 + 一句话结论，**停在这里**，不许进入阶段 B，也不许自己改源码（只允许写 `release/` 下的报告）。我把 `fix-prompt.md` 丢给原项目对话改完后会说「重新审查」，此时从 A1 重跑，并在 `release/review-report.md` 追加 `## 第 2 轮` 小节：逐条核对上一轮 findings 是 `closed / still-open / regressed`，新增项续编号。如此循环，直到 **BLOCKER=0 且 MAJOR 全部关闭，或我明确豁免某几条**，才允许进入阶段 B。

**阶段 B · 发布标准研究（只读；允许联网；先有标准再谈设计）**

目的：先搞清楚「这个生态里，合格的发布页长什么样」，再设计我们自己的页面。**禁止凭空设想页面结构**；所有结构决策都要能指回一个真实可打开的参考页。

- **B1 找参考**（至少三类，每类 1–2 个真实页面，记录访问日期）：
  1. **母项目本体**：Salt Player（`https://github.com/Moriafly/SaltPlayerSource`，看 README 与 Releases）。学习：术语（盐 / Salt Player / mod / 插件怎么写）、版本号与 tag 风格、Release notes 的章节顺序、附件命名。
  2. **同产品的分发页**：Steam 商店页 `https://store.steampowered.com/app/3009140/Salt_Player/`。学习：一句话定位、功能点写法、截图数量与尺寸、系统要求表述。
  3. **同生态的工坊插件仓库/页面 2–3 个**（自己搜 `spw workshop`、`Salt Player` 插件、`spmod`，优先同时有 Release 和 README 的）。对比：README 骨架、截图放法、安装步骤、FAQ、许可与免责声明。
  4. 可选：同类 CD 抓轨工具（EAC / XLD / CUETools）的对外页面，用来对齐「抓轨」领域的术语与用户预期。
- **B2 把标准写成清单**：逐条摘录「参考页做了什么 → 我们要不要照做 → 理由」。至少覆盖：首屏（一句话定位 + hero 图）、截图区、功能列表粒度、安装/卸载步骤、选项说明、兼容性与系统要求、已知限制、更新日志、许可、致谢、**免责声明**（本 mod 涉及第三方算法与逆向，必须有）、仓库 description 与 topics 写法、tag 与 Release 标题格式、附件命名与校验文件。
- **B3 差异裁决**：参考页互相冲突时（例如母项目用英文 tag、工坊生态用中文标题），列出候选 + 推荐 + 理由，标「待我确认」，不要自己拍板。
- **B4 产出 `release/reference-study.md`**：每条结论后跟 `来源 URL + 章节/位置 + 摘录`；打不开或需登录的页面明确写「无法核实，未采用」，不许用记忆代替。
- **B5 门禁**：这份文档是阶段 C 的唯一设计依据。阶段 C 里每个 README 章节、每张截图、每条 Release notes 都要能指回本文档的某条结论；指不回去的，要么补研究，要么删掉。

**阶段 C · 物料（阶段 A 通过、阶段 B 出文档后才开始；只写工作区，不联网）**
- `README.md`（对外版：定位/截图/安装/功能/选项/构建/限制/许可/致谢）、`LICENSE`、`CHANGELOG.md`（从 git log 生成，保留中文 commit 主题）、`docs/screenshots/*.png`（从 `design\mockups` 挑 3–5 张，压到 ≤1600px、总计 ≤3MB）、`release/RELEASE_NOTES.md`、`release/repo-meta.md`（描述 ≤120 字 + topics）。
- 产物整理成 `dist/github/` 与 `dist/steam/` 两个文件夹，各带 `SHA256SUMS.txt`。
- 硬规则：每个功能/数字都要能追到仓库里的文件（引用写成 `文件#L行号`），**不许编造**性能、兼容性、测试数。

**阶段 D · Draft（硬停）**
- 产出 `release/DRAFT.md`：拟新增/修改的文件清单（含 diff 摘要）、拟执行的每条命令、不可逆操作、Release notes 全文、仓库信息。
- 逐节标注设计依据：README/Release 页的每一节都写清「来自 `release/reference-study.md` 的哪条结论（§B-xx）」；指不回去的章节直接删掉，不要保留。
- 明确回答：仓库名与可见性、版本与 tag、LICENSE、哪些文件只进 Steam 不进 GitHub、是否先发 draft release、页面结构与参考页的对应关系。
- **停下来等我批准，批准前禁止任何 `git push` / `gh repo create` / `gh release create`。**

**阶段 E · 发布（我说"批准"之后）**
- 提交物料 → `gh repo create <owner>/<repo> --public --source . --remote origin --push` → 打 tag → `gh release create ... --draft` → 校验（`gh release view --json assets`、下载回验 sha256、看 README 网页渲染）→ 转正式（如我同意）。
- 发布后给我：仓库 URL、Release URL、附件清单与 sha256、剩余人工步骤。

### 3. 不会做的事

- 不 `git push --force`、不改历史、不动已有 tag。
- 不擅自选许可证、不擅自把私有仓库转公开。
- 不把 token/key 写进任何文件或回复。
- 不用插件工具和 `gh` 命令行重复发同一个 Release（二选一）。
- 不在我没批准时创建仓库。

### 4. 汇报格式

每阶段结束用一张小表：`阶段 | 结论(PASS/BLOCK) | 关键证据(路径/命令输出摘要) | 需要我决定的事`。
审查阶段额外给一行：`轮次 | BLOCKER | MAJOR | 上轮关闭数 | 本轮新增数`。全程中文，术语保留英文；命令与路径给绝对路径。

---

## 备注（不用复制）

- 已装工具：`gh` 2.102.0（`D:\github\gh\bin`，已登录 `doggybear233`，scopes `repo,workflow`）、插件 `dsh-github-flow`（`github_release` 等工具）、插件 `@michengai/dsh-code-review`（`/review`）。
- 新增 skill：`github-release-pipeline`、`dsh-session-forensics`（含 `session-reader.mjs`）。
- 若新会话里 `gh` 报找不到，用绝对路径 `D:\github\gh\bin\gh.exe`——是进程 PATH 快照问题，不是没装。
- 阶段 B 的参考入口已核实可打开（2026-10-21）：`https://github.com/Moriafly/SaltPlayerSource`、`https://store.steampowered.com/app/3009140/Salt_Player/`。同生态工坊插件仓库没有可信清单，必须由 agent 现场搜索并把 URL 写进 `release/reference-study.md`，不许凭印象点名。
- 阶段 A 是一个循环，不是一次性动作：agent 只出 `release/fix-prompt.md`（不改代码）→ 你把该文件丢给原项目对话去改 → 回来对 agent 说「重新审查」→ 复审并追加轮次小节 → 直到 BLOCKER=0 且 MAJOR 清零。想跳过某条就用「豁免 F-00x」这种明确说法。
