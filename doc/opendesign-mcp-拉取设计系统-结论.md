# OpenDesign MCP 能否拉取设计系统包？—— 最终结论报告

> 综合 t1（本地 daemon/CLI 拉取能力核查）、t2（open-design-mcp 包工具面核查）、t3（实机端到端验证）三个子任务的核查结论。
> 日期：2026-08-14 ｜ 项目：gate ｜ 目的门：gate-web-ui 对齐 OpenDesign dashboard 设计系统

---

## 〇、先说结论的一句话

**OpenDesign「MCP 工具层」本身不能直接拉取 dashboard 的预装设计系统包**；但「daemon/CLI 层」**能**下载「用户自建/已导入的用户态」设计系统为标准 .zip 品牌包，而「预装 preset（dashboard 自带设计系统）」**不能**被下载（daemon 对 preset 的下载接口固定返回 404）。

所以答案是 **「部分能」**，且必须在三个层面严格区分。

---

## 一、结论（分层）

| 层面 | 能否拉取设计系统包 | 判据 |
|---|---|---|
| **① MCP 工具层**（`open-design-mcp` MCP server） | **不能**（作为「拉包」能力而言） | 13 个 MCP 工具里**没有** design-systems 的 list / show / download / archive 工具；只有针对单个 `design-system.html` 文档的提取/生成/更新三个工具 |
| **② daemon / CLI 层**（`nexu-io/open-design` daemon + `od` CLI） | **部分能** | CLI `od design-systems download <id> --out` 与 HTTP `GET /api/design-systems/:id/archive` 能下载**用户态**设计系统为 .zip；但对**内置 preset** 固定返回 404 |
| **③ 本机当前运行态**（这台 Windows 机） | **当前不可行** | 3600 秒探测端口 7456（daemon 文档默认端口）**关闭**、无 OpenDesign daemon 进程、`open-design-mcp` 未安装，且三条子任务编排全部 `[error]`，无端到端实证据 |

> 判级：**部分能**。能否拉取，取决于「你说的是哪一类设计系统 + 你是从哪一层去拉」。

---

## 二、证据链摘要（每个结论对应哪个子任务的什么证据）

> 客观性说明：本次 t1 / t2 / t3 三个编排子任务**全部以 `[error]` 状态结束**（错误状态本身即一条事实），因此**下面分层证据中，最能落地的是由我（主 agent）对上游源码与 npm 元数据的实抓核实**，我在每条上标注是「已验证事实」还是「推断」。

### ① MCP 工具层 ——「不能」作为拉包能力
- **已验证事实（t2 方向，由主 agent 抓 npm 实测）**：npm 包 `open-design-mcp@0.16.x`，描述为 *「MCP stdio server bridging coding agents to Open Design daemon」*，即它只是「编码 agent ↔ daemon」之间的 stdio 桥，**本身不落盘、不下载设计系统**。
- **已验证事实**：其 README 列出的 13 个工具为 `od_list_projects / od_get_project / od_create_project / od_update_project / od_delete_project / od_save_artifact / od_save_project_file / od_extract_design_system / od_generate_design_system / od_update_design_system / od_lint_artifact / od_compose_brief / od_generate_design`。设计系统相关的三个工具只操作**项目内一个 `design-system.html` 文档**（纯函数提取 / BYOK 生成 / 更新），**没有任何一个能列出或下载 daemon 的 design-systems 目录**。
- **已验证事实**：README v0.17.0 「已知局限」明载：`od_generate_design` 通过 `designSystemId` 链接后**自动注入设计系统内容当前是运行时 no-op**（daemon 的 project files-list 接口只回 metadata 不回内容），需待 v0.18 内容端点。

### ② daemon / CLI 层 ——「部分能」（可下载用户态设计系统）
- **已验证事实（t1 方向，抓上游 `nexu-io/open-design` 源码）**：CLI 帮助文本 `DESIGN_SYSTEMS_USAGE`：
  ```
  od design-systems list / show <id> / rename / download <id> [--out <p>]
  od design-systems import-local / import-github / import-shadcn / rebuild-token-contract
  ```
  其中 **`download <id> [--out <p>]` = 下载品牌 .zip（files + SKILLS.md）** —— 这就是「拉取设计系统包」的直接通道。
- **已验证事实（同上）**：对应 HTTP 端点：
  - `GET /api/design-systems/:id` — 取详情
  - `GET /api/design-systems/:id/files` — 列文件
  - `GET /api/design-systems/:id/file` — **取单个文件内容**（content 端点，正是 MCP 目前缺的那个）
  - `GET /api/design-systems/:id/archive` — **流式下载 .zip 品牌包**
  - `GET /api/design-systems/:id/preview` / `showcase` / `static`
- **已验证事实（关键限制）**：archive 端点与源码注释明写：
  > *"Only user systems have an editable dir; presets resolve to null and surface as 404."*
  > *"for non-user ids (built-in presets live elsewhere and have no editable dir)."*
  即 **内置 preset（dashboard 预装设计系统）不能被下载**；只有**用户自建/导入的用户态设计系统**才有可下载目录。

### ③ 本机当前运行态 ——「当前不可行」+ 编排失败
- **已验证事实（t3 方向）**：Windows 主机 `Test-NetConnection` 探测 daemon 默认端口 **7456 关闭**；3000/4173/8787/8765/4000 均关闭；仅 5173 是本项目自己的 Gate Vite dev 服务（title「Gate Web 操作台」，node pid 11932）。
- **已验证事实**：`Get-Command opendesign / od_create_project` 为空（PATH 上无该 CLI）；npm 全局未装 open-design-mcp。
- **已验证事实**：本会话三条编排子任务最终状态全为 `[error]` → 无端到端实机证据。**推断**：本机当前没有可连接的 OpenDesign daemon，因此 t1/t3 所依赖的实机调用无法成立。

---

## 三、替代路径（若不能直接拉取预装设计系统）

按「最省事 → 最灵活」排序，均可落地：

1. **不要拉 dashboard 的预装设计系统；直接用自己的商标包。**
   既然 gate-web-ui 要「对齐 OpenDesign dashboard 的设计系统」指的是**在 OpenDesign 里编辑并产出的设计系统（品牌包）**，最干净的路径是：在 OpenDesign 里用 `x.md` 的「三、设计系统」一节建立/编辑一个**用户态**设计系统，然后
   - CLI：`od design-systems download <designSystemId> --out ./design-tokens/`
   - 或 HTTP：`GET /api/design-systems/<id>/archive → design-system.zip`
   下载得到 `design-system/`（含 tokens / components / layout 的 `design-system.html` + SKILLS.md），再把 tokens 落到 gate-web-ui 的 design-token 层（对齐 `x.md` §3.2 令牌表）。

2. **走项目级 `designSystemId` 引用（MCP 层）**——但注意当前断点。
   `od_create_project` / `od_update_project` 接收 `designSystemId`，`od_generate_design` 会读取它；但官方明确 v0.17.0 该**自动注入是 no-op**」。落地建议：把每次生成的成品**用 `od_save_project_file` 存进项目**，并在生成时显式传 `designSystemHtml` / `designSystemSummary` 给 `od_lint_artifact` / `od_compose_brief`，绕开「自动注入」缺口。等 v0.18 内容端点补齐后再简化。

3. **直接复用本机/仓库里已固化的设计系统包（拷贝/引用）。**
   最稳、零运行时依赖：把设计系统的最终产物（tokens JSON / `design-system.html` / CSS 分块）**直接拷入 gate-web-ui 源码**（如 `src/design-system/`），由 `x.md` §3.2 的令牌表驱动工作台的 CSS 变量与组件层。不依赖任何 daemon 在途状态。

> 官方在途计划（推断 / 官方 README 明载）：open-design-mcp 路线图含 **v0.18 增加文件内容端点**，届时 `designSystemId` 自动注入与「MCP 层拉取项目内设计系统内容」会打通；但那针对的是**项目内 `design-system.html` 文档**，仍不等价于「拉取 dashboard 预装 preset」。预装 preset 的可下载性目前没有官方承诺。

---

## 四、对本项目的落地建议（2–3 条）

1. **不要指望 MCP「拉包」**：gate-web-ui 的对齐动作以「**离线品牌包直接落库**」为准 —— 在 OpenDesign 里将用户态设计系统导成 .zip（`od design-systems download` 或 `/archive`），把 tokens/components/layout 一次性固化进 `gate-web-ui` 的 design-token 层，作为设计系统的 single source of truth。这绕开 MCP no-op 与 daemon 在途状态，最稳。

2. **若确实要用 MCP 生成型工作流**（`od_create_project` + `od_generate_design` + `od_generate_design_system`），就给 `od_generate_design` 显式传 `designSystemHtml`（strict 模式）并用 `od_lint_artifact`（DS001–DS005）做生成后一致性校验；把每次成品用 `od_save_project_file` 持久化进项目，避免依赖尚未实现的 `designSystemId` 自动注入。

3. **「对齐 OpenDesign dashboard 设计系统」要重新定义成「对齐设计令牌，而不是对齐预装包」**：本机无 daemon、preset 不可下载已是验证事实。建议在 `x.md` 的「三、设计系统」基础上，把 gate-web-ui 需要的令牌（`bg-*/text-*/accent/success/danger/warning`、圆角/字体/间距/投影规范）落地为独立 token 资产，与 OpenDesign 产出的品牌包建立**符号级**对齐（同名令牌映射），而不是像素级对齐某个 dashboard 预装设计系统。

---

## 附：客观性边界

- **已验证事实（可直接引用）**：上面每条带「已验证事实」的陈述，均出自对 `nexu-io/open-design` 源码（routes/design-systems.ts、design-systems/index.ts、cli-help/design-systems-cli-help.ts、README）与 npm `open-design-mcp` 元数据/README 的实抓。
- **推断 / 未证实**：① v0.18 具体发布时点与行为；② dashboard「预装 preset」的完整清单与命名；③ 本机 `gate` 本项目未来是否会在 lo 环境自建 OpenDesign daemon。这三条标注为推断。
- **证明缺陷**：t1/t2/t3 三个编排子任务最终均为 `[error]`，本报告的实机验证部分因此退化为「端口/进程/包在位探测」，而非完整端到端生成链路；如需更强的端到端证明，需先在本机启动一个 OpenDesign daemon（端口 7456）后再复核。
