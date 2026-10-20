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
- 插件 id：`com.saltrip.saltaudiocopy`；provider `saltrip`；当前版本 `0.42.0`
- 构建：`mod\` 下 Gradle（`gradlew.bat`），产物 `mod\build\libs\*.spmod`
- 发布渠道：GitHub + Steam 创意工坊（两者产物要分开）
- 已知缺口：无根 `README.md`、无 `LICENSE`、无 `CHANGELOG.md`、无 git remote；
  `mod\README.md` 引用了不存在的 `../architecture.md`

【版本号策略：沿用 0.x 继续，还是这次直接 1.0.0？默认按我上次说的"测试全过才发 1.0.0"处理】
【许可证：MIT / Apache-2.0 / 其他 / 暂不添加，默认给出选项让我选】
【仓库名：默认 `salt-audio-copy`，可见性 public】

### 2. 四个阶段（严格顺序，门禁不过不许跳）

**阶段 A · 审查（只读）**
- 用 `/review`（已装 `@michengai/dsh-code-review`）审待发布范围；自己再补一轮：错误处理、用户可见反馈、IO/线程、资源释放、Windows 非 ASCII 路径与编码。
- 跑 `.\gradlew.bat test` 与 `.\gradlew.bat build`，记录「N tests / M failed」和产物 sha256。
- 文档一致性检查：版本号三处一致、README 引用路径真实存在、截图与 UI 一致。
- 产出 `release/review-report.md`。**有 BLOCKER 就停下来报给我，不要顺手改代码。**

**阶段 B · 物料（只写工作区，不联网）**
- `README.md`（对外版：定位/截图/安装/功能/选项/构建/限制/许可/致谢）、`LICENSE`、`CHANGELOG.md`（从 git log 生成，保留中文 commit 主题）、`docs/screenshots/*.png`（从 `design\mockups` 挑 3–5 张，压到 ≤1600px、总计 ≤3MB）、`release/RELEASE_NOTES.md`、`release/repo-meta.md`（描述 ≤120 字 + topics）。
- 产物整理成 `dist/github/` 与 `dist/steam/` 两个文件夹，各带 `SHA256SUMS.txt`。
- 硬规则：每个功能/数字都要能追到仓库里的文件（引用写成 `文件#L行号`），**不许编造**性能、兼容性、测试数。

**阶段 C · Draft（硬停）**
- 产出 `release/DRAFT.md`：拟新增/修改的文件清单（含 diff 摘要）、拟执行的每条命令、不可逆操作、Release notes 全文、仓库信息。
- 明确回答：仓库名与可见性、版本与 tag、LICENSE、哪些文件只进 Steam 不进 GitHub、是否先发 draft release。
- **停下来等我批准，批准前禁止任何 `git push` / `gh repo create` / `gh release create`。**

**阶段 D · 发布（我说"批准"之后）**
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
全程中文，术语保留英文；命令与路径给绝对路径。

---

## 备注（不用复制）

- 已装工具：`gh` 2.102.0（`D:\github\gh\bin`，已登录 `doggybear233`，scopes `repo,workflow`）、插件 `dsh-github-flow`（`github_release` 等工具）、插件 `@michengai/dsh-code-review`（`/review`）。
- 新增 skill：`github-release-pipeline`、`dsh-session-forensics`（含 `session-reader.mjs`）。
- 若新会话里 `gh` 报找不到，用绝对路径 `D:\github\gh\bin\gh.exe`——是进程 PATH 快照问题，不是没装。
