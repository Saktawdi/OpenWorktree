<p align="center">
  <img src="gate-web-ui/public/brand/ow-light-badge-64.png" width="88" alt="OpenWorktree">
</p>

<h1 align="center">OpenWorktree</h1>

<p align="center">
  工单驱动开发工作台：把需求交给 Agent，在隔离工作区完成开发，经过可追溯审查后安全发布。
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-GPL--3.0-2ea44f?style=flat-square" alt="License GPL-3.0"></a>
  <img src="https://img.shields.io/badge/Java-17-ED8B00?style=flat-square&logo=openjdk&logoColor=white" alt="Java 17">
  <img src="https://img.shields.io/badge/React-19-149eca?style=flat-square&logo=react&logoColor=white" alt="React 19">
  <img src="https://img.shields.io/badge/opencode-已适配-7c5cff?style=flat-square" alt="opencode 已适配">
  <img src="https://img.shields.io/badge/Claude_Code-已适配-D97757?style=flat-square" alt="Claude Code 已适配">
  <img src="https://img.shields.io/badge/运行方式-local--first-35d99e?style=flat-square" alt="Local first">
</p>
<p align="center">
  <a href="#界面展示">界面展示</a> ·
  <a href="#核心流程">核心流程</a> ·
  <a href="#mcp-工具">MCP 工具</a> ·
  <a href="#插件生态">插件生态</a> ·
  <a href="#快速开始">快速开始</a>
</p>
<p align="center">
  <sub>产品名 <b>OpenWorktree</b>（缩写 <b>OW</b>）· 内部审查与发布核心统称 <b>Gate</b>（门禁引擎）——产品与引擎的分层，不是命名混乱。</sub>
</p>


OpenWorktree 是一个本地优先的工单驱动开发工作台。它把「一张工单 = 一次受控的开发交付」作为最小闭环：工单派给编码智能体，在专属的隔离克隆里写代码；提交前锁定快照并执行门禁审查，发现与判决全程留痕；审查通过后，以可追溯的身份安全发布回目标分支。

## 为什么是 OpenWorktree

AI 编码不应该只留下一个「看起来改好了」的结果。OpenWorktree 把 Agent 的执行范围、代码变更、审查证据和最终发布串在同一张工单里，让开发者可以在不污染主仓库的前提下快速试错，并在发布前回答三个问题：改了什么、为什么能过、谁批准了发布。

## 界面展示

以下截图来自实际工作台。

<table>
  <tr>
    <td align="center" style="border:1px solid #30363d; border-radius:10px; padding:8px;">
      <img src="doc/9f8ea95c-2bb6-4284-a2d2-3b2c234dd883.png" alt="浅色主题工单看板" width="100%">
      <br><sub><b>工单看板</b> · 六泳道拖拽流转，状态与优先级一眼可见</sub>
    </td>
    <td align="center" style="border:1px solid #30363d; border-radius:10px; padding:8px;">
      <img src="doc/34f5a311-9591-4cfc-a804-7c2dd9a134a3.png" alt="暗色主题运行监控与会话工作台" width="100%">
      <br><sub><b>会话工作台</b> · Agent 流式执行、运行监控与隔离终端</sub>
    </td>
  </tr>
  <tr>
    <td align="center" style="border:1px solid #30363d; border-radius:10px; padding:8px;">
      <img src="doc/2c88c107-257b-460a-bebc-1256e8df64eb.png" alt="会话消息与任务清单" width="100%">
      <br><sub><b>任务清单</b> · 从 Agent 上下文中提取进度，执行状态随会话更新</sub>
    </td>
    <td align="center" style="border:1px solid #30363d; border-radius:10px; padding:8px;">
      <img src="doc/92353065-66f7-41f2-8ed0-852ea7833285.png" alt="浅色主题变更对比与发布门禁" width="100%">
      <br><sub><b>变更对比</b> · 按文件查看差异，发布前确认快照内容</sub>
    </td>
  </tr>
  <tr>
    <td align="center" style="border:1px solid #30363d; border-radius:10px; padding:8px;">
      <img src="doc/4fa658b6-3279-4437-9c6e-2d95dd6b8942.png" alt="暗色主题审查发现与门禁结果" width="100%">
      <br><sub><b>审查发现</b> · 展示阻断、警告与建议，并保留修复依据</sub>
    </td>
    <td align="center" style="border:1px solid #30363d; border-radius:10px; padding:8px;">
      <img src="doc/7ad5fa54-880e-43cf-b0fe-d68186736e2d.png" alt="暗色主题隔离终端选择器" width="100%">
      <br><sub><b>隔离终端</b> · 直接进入工单克隆目录，终端进程可保活</sub>
    </td>
  </tr>
  <tr>
    <td align="center" style="border:1px solid #30363d; border-radius:10px; padding:8px;">
      <img src="doc/e5a2f6a5-22a6-468a-9dc8-a27e5c4142d7.png" alt="浅色主题新增智能体配置窗口" width="100%">
      <br><sub><b>智能体配置</b> · 接入 OpenCode、Claude Code 与自定义 Provider</sub>
    </td>
    <td align="center" style="border:1px solid #30363d; border-radius:10px; padding:8px;">
      <img src="doc/f30c6f39-7884-44f0-9d19-2004e5aee33f.png" alt="浅色主题项目分支与提交历史" width="100%">
      <br><sub><b>项目历史</b> · 分支、提交与工单发布关系集中查看</sub>
    </td>
  </tr>
</table>

## 核心流程

```text
创建工单 → Agent 在隔离 worktree 编码 → 预提审锁定快照
        → 门禁审查产出发现 → 修复并再次审查 → 授权发布到目标分支
```

### 1. 建立上下文

在看板创建工单，填写需求、优先级、标签和目标分支；为工单选择 CLI 智能体、模型、推理强度以及可选的系统提示词。工单上下文会随会话注入，减少 Agent 在不同工具之间来回拼接信息的成本。

### 2. 隔离执行

每张工单使用独立的 Git worktree / 克隆目录。Agent 可以流式回显文本、编辑文件、运行命令和持续更新任务清单；需要人工介入时，会话会明确提示结束状态或待决询问。主仓库不直接承受 Agent 的中间改动。Agent 还能通过内置 MCP 工具建票、送审与读取审查反馈（见 [MCP 工具](#mcp-工具)）。

### 3. 快照与审查

预提审会锁定当前变更快照，门禁审查基于这份不可变输入生成发现与判决：`BLOCKER` 用于阻断，`WARNING` 用于提醒风险，`SUGGESTION` 用于改进建议。审查、修复、二轮结果和操作人都写入审计记录（`audit.jsonl`）。

### 4. 授权发布

只有通过门禁并获得授权的工单才能发布。发布提交可以固定为系统身份，也可以使用本机 Git 作者信息；提交时间使用真实时间，便于在项目历史中追溯工单与代码的对应关系。

## MCP 工具

OpenWorktree 内置一套 MCP（Model Context Protocol）stdio 服务器，是 Agent 与门禁交互的唯一通道。接入是全自动的：启动会话时，后端会为 Claude Code 写入 `--mcp-config`、为 OpenCode 写入 `OPENCODE_CONFIG`，MCP 子进程以工单克隆为工作目录就地拉起，无需任何手工配置。

工具按权限分为两个域，凭据随会话签发并绑定单张工单：

| 工具 | 权限域 | 功能 |
| --- | --- | --- |
| `ticket_create` | Agent | 创建新工单：校验请求、从基线切出工单分支并生成隔离克隆，返回工单号与克隆路径。适合 Agent 在开发中途发现后续工作时随手建票 |
| `presubmit_create` | Agent | 预提审：把当前工作区冻结为不可变快照并开启一轮审查。这是 Agent 唯一能触发的状态迁移 |
| `presubmit_get_diff` | Agent | 读取某轮预提审锁定的差异文本，确认送审内容 |
| `review_result_get` | Agent | 读取审查结果（判决 + 结构化发现），据此修复后再次送审 |
| `review_run` | Human | 执行一轮审查（内置引擎或人工判决），产出发现与判决 |
| `commit_and_publish` | Human | 把已审查的快照提交并经门禁发布到目标分支 |
| `config_show` | Human | 查看门禁生效配置（路径、目标引用、引擎状态） |
| `provider_list` | Human | 列出已配置的 LLM Provider 及其缓存模型 |

安全设计：

- **判决权不在 Agent 手里**：`review_run` 与 `commit_and_publish` 永不进入 Agent 域——否则 Agent 可以反复跑审查直到碰运气通过。判决始终由门禁策略铸造。
- **凭据最小化**：会话令牌按会话签发、绑定单张工单，通过 `GATE_DOMAIN_TOKEN` 环境变量传递（不经 argv），明文只存在于子进程与克隆的 `.git/gate-context/` 内，不会出现在工单 diff 中。
- **可独立编排**：也可以脱离工作台手工使用——`gate mcp serve` 启动 stdio 服务器，`gate mcp issue-token --ticket T-101`（或 `--human`）签发对应权限域的令牌；明文只打印一次，数据库只存 SHA-256 哈希。

## 核心特性

- **工单看板**：六泳道看板，拖拽即流转；门禁泳道会执行对应的预提审、审查和发布操作。
- **隔离工作区**：每张工单独立克隆，主仓库保持干净，多个任务可以并行推进。
- **会话工作台**：接入 Claude headless、OpenCode serve 等 CLI 智能体，支持流式回显、模型与推理强度切换。
- **MCP 工具链**：Agent 经由低权限 MCP 工具建票、送审、读取审查反馈；审查执行与发布授权保留在人类域。
- **运行监控**：集中查看正在运行的 Agent、会话结束状态和待决询问，避免后台任务无声退出。
- **可追溯审查**：快照、差异、发现、判决、修复和审计日志形成完整证据链。
- **安全发布**：发布需要授权，提交身份可控，目标分支与发布结果明确可见。
- **终端工作台**：多标签终端可直连工单克隆目录，支持最小化后台与进程保活。
- **插件系统**：对话快捷动作、划选菜单、设置挂件与整页导航四类贡献点，契约唯一来源仓库内 SDK（见 [插件生态](#插件生态)）。
- **本地优先**：服务绑定回环地址，SQLite、本地 blob 和令牌都存放在运行目录内，拷贝即可迁移。
- **双主题**：暗 / 亮主题随切，OW 徽章配套昼夜过渡动画。

## 插件生态

宿主对插件开放六个贡献点——对话区快捷动作（`composer.chips`）、划选文字菜单（`selection.menu`）、
设置中心管理挂件（`settings.plugins`）、顶栏整页（`nav.pages`）、顶栏右上角胶囊动作
（`header.actions`）与全局悬浮挂件（`floating.widgets`）；插件按 manifest 声明能力：
`kv`（插件命名空间 KV 持久化，落 `<gateHome>/plugins-data/<id>/`）、`net`（注入 Web Token 的
同源 `/api/` 请求）、`storage`（浏览器 localStorage，键前缀按插件隔离）、`llm`（走宿主已配置
Provider 的对话），未声明的能力调用直接抛错。插件**不打包自己的 React**——经共享 shim 用宿主
同一个 React 实例，样式可沿用宿主全局工具类。安全边界为 Level 1 本地可信：插件与页面脚本同级权限，
只安装可信来源（详见 plugin-template 的「信任模型」）。

- **契约唯一来源**：[packages/plugin-sdk](packages/plugin-sdk/README.md)（README 含完整能力表、
  插槽清单、事件总线与冻结语义）——宿主 re-export 同一份类型，**不允许任何工程再维护
  host-types 镜像**
- **开发起点**：[plugin-template/](plugin-template/README.md)（README 含生命周期、共享 React
  原理与常见问题），复制即开工
- **内置插件**：[plugins/quick-quotes](plugins/quick-quotes/README.md) 快捷语录（增删改查、
  拖拽排序、按工单状态显隐；发送消息 / 触发宿主预提审与按意见修复 / 指示 Agent 调 MCP 工具三类动作）
- **安装**：插件工程内 `npm run deploy` 一键装进运行中的实例——自动探测在线后端的
  `gate_home`（逐个候选 `gate.toml` 做 `/api/health` 探活），全离线才回退仓库 `local-run/gate-home`；
  改代码后 `npm run build` → 设置 → 插件 →「重载」

## 架构

Java 17 多模块，依赖单向倒置；Web 层刻意不使用 Spring Boot，仅用 Javalin（Jetty）保持轻量。前端是独立的 React 19 + Vite 单页应用。

```text
gate-domain        领域模型与规则（纯 Java，无框架）
gate-ports         端口接口定义
gate-application   用例编排
gate-adapters      外部适配（git CLI、引擎、MCP、凭据等）
gate-web           HTTP/SSE 服务（Javalin；API + SPA 静态伺服 + SSE）
gate-bootstrap     装配启动
gate-cli           命令行入口（picocli）
gate-web-ui        前端（React 19 + Vite + Tailwind v4 + zustand + motion + xterm）
```

- **Git 证据**：一律通过真实 `git` 二进制执行，不引入 JGit；审核与发布的证据就是仓库里的真实对象。
- **持久化**：SQLite 由 Flyway 管理迁移，本地 blob 保存运行数据，数据集中在运行目录。
- **安全边界**：仅允许本地回环绑定；Web 请求令牌与 SSE 分别校验；LLM API Key 加密落库。

<p align="center">
  <img src="doc/openworktree.png" alt="OpenWorktree 架构图" width="100%">
  <br><sub><b>系统架构</b> · 从浏览器到隔离工作区的数据流与安全边界</sub>
</p>

## 快速开始

### 方式一：Docker（推荐，无需本地环境）

镜像以多架构（amd64/arm64）发布在 Docker Hub：[`saktawdi/openworktree`](https://hub.docker.com/r/saktawdi/openworktree)。不必克隆仓库，把下面这份存成 `compose.yaml`（放任意目录）：

```yaml
services:
  openworktree:
    image: saktawdi/openworktree:latest
    ports:
      - "8080:8080"                      # 宿主机上改左边这个数，例如 9000:8080
    environment:
      # 局域网/反向代理访问时，把入口 Host 加进白名单（逗号分隔）：
      # OW_ALLOWED_ORIGINS: 192.168.1.10,worktree.internal
      OW_ALLOWED_ORIGINS: ""
    volumes:
      - openworktree-data:/data         # 配置、SQLite、令牌、工单克隆全在这
    restart: unless-stopped
volumes:
  openworktree-data:
```

```bash
docker compose up -d                                     # 拉取并启动
docker compose logs openworktree | grep GATE_WEB_TOKEN   # 复制登录令牌 → 页面登录
```

打开 <http://localhost:8080>。

注意：

- **镜像内容**：JDK 17 + git + nginx + 构建好的前端 + **opencode（项目默认 Agent CLI，内置，Agent 会话开箱即用）**。工单、审查、发布、隔离终端不依赖外部环境；Claude Code 按下面方式扩装。
- **安全边界不变**：后端只能绑容器内回环（`GateConfig.WebConfig` fail-closed），对外界只有一个 nginx；`allowed_origins` Host 白名单照旧生效——局域网访问记得填 `OW_ALLOWED_ORIGINS`。
- **数据**：`/data/gate.toml` 首启自动生成；改配置后 `docker compose restart`。
- **门禁初始化（可选，不挂项目的工单用）**：`docker compose exec openworktree java -cp "/app/lib/*" gate.cli.GateApp init -c /data/gate.toml`。

要加 Claude Code（或其他 Agent CLI），写个一行继承镜像再上自己的 compose：

```dockerfile
FROM saktawdi/openworktree:latest
RUN npm install -g @anthropic-ai/claude-code && npm cache clean --force
```

从源码自构建（开发时）：仓库根目录 `docker compose up -d --build`，build-arg `INSTALL_CLAUDE=true` 让 Claude Code 随镜像出来。

### 方式二：Windows 桌面版（推荐，需CLI环境）

双击即用——启动自动登录。
前置只需本机 `git`（跑 Agent 会话再按需装对应 CLI，如 opencode / Claude Code）。

- 安装包随 GitHub Releases 发布（另可按照`desktop/README.md` 自行构建）；
- 数据默认落安装目录 `data\`（工单克隆随安装盘走），卸载可保留（移至 `%APPDATA%\OpenWorktree`）或删除；后端契约与 Docker / 源码方式完全一致。

### 方式三：本地开发（源码）

#### 环境要求

| 工具 | 版本 | 检查命令 |
| --- | --- | --- |
| JDK | 17+ | `java -version` |
| Maven | 3.9+ | `mvn -v` |
| Node.js + npm | 18+ | `node -v` |
| Git | 任意近期版本 | `git --version` |

<details>
<summary><b>还没有环境？各系统一键安装</b></summary>

**Windows**（winget）：

```powershell
winget install Microsoft.OpenJDK.17 Apache.Maven OpenJS.NodeJS.LTS Git.Git
```

没有 winget 时，到 Adoptium（JDK 17）、Maven、nodejs.org 与 Git 官网手动下载安装即可。

**Ubuntu / Debian**：

```bash
sudo apt install openjdk-17-jdk maven git
# 旧版源里的 Node 太老，建议用 nvm 装 Node 20：
curl -o- https://raw.githubusercontent.com/nvm-sh/nvm/v0.40.3/install.sh | bash
nvm install 20
```

**CentOS / Fedora / RHEL**：

```bash
sudo dnf install java-17-openjdk-devel maven git   # Node 18+ 同样建议用 nvm 安装
```

**macOS**（Homebrew）：

```bash
brew install openjdk@17 maven node git
```

</details>

#### 第 0 步：克隆并构建

```bash
git clone https://github.com/Saktawdi/OpenWorktree.git
cd OpenWorktree

# 后端构建（用 IDEA 直接跑 main 的话，第二条可跳过）
mvn -DskipTests install
mvn -pl gate-web dependency:copy-dependencies

# 前端依赖
cd gate-web-ui && npm install && cd ..
```

#### 第 1 步：启动后端（两种方式任选）

**方式 A：终端命令行**（保持终端开启，报错也在这里看）

```powershell
# Windows
java -Dfile.encoding=UTF-8 -cp "gate-web/target/classes;gate-web/target/dependency/*" gate.web.GateWebApp
```

```bash
# Linux / macOS
java -Dfile.encoding=UTF-8 -cp "gate-web/target/classes:gate-web/target/dependency/*" gate.web.GateWebApp
```

**方式 B：IDEA 等 IDE**

直接运行 `gate.web.GateWebApp` 的 main，无需 `dependency:copy-dependencies`。Run/Debug 配置：

| 配置项 | 值 |
| --- | --- |
| Main class | `gate.web.GateWebApp` |
| Working directory | 仓库根目录 |
| Program arguments | 留空 |
| VM options | `-Dfile.encoding=UTF-8`（Windows 建议，避免中文日志乱码） |
| JRE | 17+ |

VS Code / Cursor 用 launch.json 等价配置（`mainClass: gate.web.GateWebApp`，`cwd` 指向仓库根）。

首次启动会自动生成 `local-run/gate.toml`（loopback 127.0.0.1:18080），数据库、令牌等运行数据也一并自动创建；之后端口、审查引擎等都在「设置中心」改（写回该文件），无需手编。看到 `GATE_WEB_TOKEN=...` 与 `listening on http://127.0.0.1:18080/` 即成功。

#### 第 2 步：启动前端

另开一个终端：

```bash
cd gate-web-ui
npm run dev
```

浏览器打开 <http://127.0.0.1:5173>（Vite 已把 API 代理到 18080 的后端）。

#### 第 3 步：登录并开工

- **登录令牌**：后端日志的 `GATE_WEB_TOKEN=` 行，或 `local-run/gate-home/web-token` 文件。
- **配置 Provider**：「设置中心 → LLM Providers」填 API Key（加密落库），再到「审查引擎」选 Provider 与模型。
- **注册项目**：「项目」页注册你的代码目录——注册时自动初始化该项目专属的门禁镜像仓，随后建工单即可开工。
- **Agent 侧零配置**：会话启动时 MCP 工具（建票、送审、读审查结果）自动注入 Agent，见 [MCP 工具](#mcp-工具)。

如果还想让**不挂项目**的工单也可用，执行一次 `gate init` 初始化门禁级默认镜像仓（幂等，可重复执行）：

```bash
mvn -pl gate-cli dependency:copy-dependencies
# Windows 把 classpath 分隔符换成 ; ，Linux/macOS 用 :
java -cp "gate-cli/target/classes:gate-cli/target/dependency/*" gate.cli.GateApp init -c local-run/gate.toml
```

### 常见问题

- **端口 18080 被占用**：修改 `local-run/gate.toml` 中 `[web].port`，并让前端代理指向新端口：`VITE_BACKEND_URL=http://127.0.0.1:<新端口> npm run dev`。
- **后端起不来**：多数是漏了第 1 步的两条构建命令；看启动终端报错的第一行即可定位。
- **令牌文件、数据库等都落在 `local-run/`**：整目录不入库，整目录拷走即可迁移。

### 测试分层

全量套件约 17 分钟（真实 Git 操作、Javalin 端到端、故障注入），日常迭代用分层 profile 控制反馈速度。分层靠 JUnit `@Tag("slow")`：打标的是**起真实服务器/Git 仓库的端到端、故障注入/崩溃恢复、真实 CLI smoke**——即崩溃、并发、重放、错权、SSE 断线、任务恢复这类慢而关键的路径；不打标的是纯单元、HTTP 契约、内存协议测试。

| 命令 | 跑什么 | 耗时 |
|------|--------|------|
| `mvn test`（默认，等同 `-Pfast`） | 不打 slow 标签的全部测试 | 约 1 分钟 |
| `mvn test -Pintegration` | **只跑** slow 层（端到端/故障注入/smoke） | 约 15 分钟 |
| `mvn test -Pfull` | 全量（提交前 / 发版前跑一次） | 约 17 分钟 |

约定：日常开发 `mvn test`；推前置验证 `mvn test -Pintegration`；提交前的最终确认与发版用 `-Pfull`。CI 流水线（native-build / docker-publish）目前 `-DskipTests` 不受影响。

## 路线图

近期规划方向：

- 持续打磨使用体验，修复缺陷
- 审计记录查阅页、工单 Token 用量与成本统计页
- 通用 LLM 助手接入
- 接入更多 CLI 智能体
- 云端团队版（多租户；单机 Docker 镜像已发布至 Docker Hub，见「快速开始」）

## 许可证

本项目以 [GPL-3.0](LICENSE) 协议开源。
