# GATE 操作台 · 高保真 HTML 原型

依据 `doc/x.md` 生成的可点击高保真原型，单文件实现，无外部依赖，直接用浏览器打开即可。

## 打开方式

直接双击打开：

```
doc/prototype/index.html
```

或本地起一个静态服务：

```bash
cd doc/prototype
python -m http.server 4173
# 浏览器访问 http://127.0.0.1:4173
```

## 已覆盖页面（对应 x.md §5）

| 路由 | 页面 | 闭环环节 |
|---|---|---|
| `#/login` | 登录页 | auth |
| `#/` | 项目看板列表 | status |
| `#/styleguide` | 组件 / 样式速览页 | — |
| `#/projects/gate/tickets` | 工单看板（8 列 Kanban / 列表视图） | status |
| `#/projects/gate/tickets/T-104` | 工单详情（概览 / Diff / 审核 / 会话 / 历史） | presubmit + review |
| `#/projects/gate/tickets/T-104/review` | 审核台（核心） | review |
| `#/projects/gate/tickets/T-104/session` | Agent 会话页 | session |
| `#/projects/gate/agents` | AgentConfig 管理 | session |
| `#/projects/gate/cost` | 成本面板 | metrics |

## 已实现交互

- 登录：输入任意 ≥6 位 token 进入；退出登录。
- 看板：8 列状态列，卡片可拖拽换列；支持看板/列表视图切换；`REJECTED` 并入“进行中”列并标红。
- 样式速览：`#/styleguide` 展示颜色令牌、字体、按钮、徽章、表单、指标卡、加载/空/错误态。
- 新建工单：顶部/项目页“新建工单”弹窗。
- 审核台：
  - 常驻 `tree_hash` / `base_commit` / `targetRef` 锚定条；
  - Findings 按 blocker / warning / nit 分组；
  - 一键 LLM 审核、人工通过、人工驳回、驳回回喂；
  - 审核/发布带模拟 SSE 进度条。
- 会话页：切换会话、发送消息、流式回复、usage 徽章、中止、新建会话。
- Agent 配置：列表 + 新建/编辑弹窗。
- 成本面板：H1 判定卡 + 工单 token 聚合表。
- 全局 `⌘K` / `Ctrl+K` 命令面板。

## 设计来源

- 页面结构、数据模型、状态机：`doc/x.md`
- 视觉基调：`doc/x.md` §3 设计系统（暗色、高信息密度、单一 accent）
- 组件参考：本机 Open Design 安装目录中的 `dashboard` 设计系统包
  （`D:\Program Files\Open Design\resources\open-design\design-systems\dashboard\`）
  已将其 tokens / components 作为上下文，再按 x.md 的令牌表收敛为原型 CSS 变量。

## 说明

这是**高保真可点击原型**，不是生产实现。后续实现仍以 `doc/执行文档-前端-web.md` 和后端契约为准；本原型只作为视觉、布局、交互的评审基准。
