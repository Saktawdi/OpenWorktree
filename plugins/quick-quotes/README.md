# 快捷语录

把对话输入区上方的快捷语录 chips 插件化：语录可增删改查、拖拽排序、按工单状态显隐，
支持三类动作——发送消息、触发宿主功能（预提审 / 按审查意见修复）、指示 Agent 调用 MCP 工具。
基于 `plugin-template/` 开发（结构与契约说明见模板 README）。

## 功能

- **composer 快捷 chips**：每条语录一枚 chip，显示顺序 = 管理页排序；
  条目过多时宿主自动折叠（前 4 条 + `+N` 展开按钮）
- **三类动作**：
  - `消息`：点击直接把文案发给当前工单会话
  - `功能 · 预提审`：触发宿主 actions.presubmit（冻结工作区 + 启动审查轮）
  - `功能 · 按意见修复`：触发宿主 actions.returnWithFindings（审查意见拼装成消息）
  - `MCP 工具`：把「请调用 MCP 工具 <tool> …」发给 Agent（工具清单从 `/api/mcp/status` 实时拉取）
- **显隐条件**：始终 / 有工作区变更 / 有变更且进行中 / 审查驳回且有意见 / 工单重启过
- **持久化**：`ctx.kv`（`<gateHome>/plugins-data/quick-quotes/quotes.json`），编辑防抖 500ms 落盘，后端重启不丢
- **内置语录**：首次安装自动种子（MCP 预提审示例）。原五条（预提审/解释当前变更/运行本地单测/
  完成此工单/按审查意见修复）已回迁为宿主原生 chip，不再由本插件提供；存量 KV 中的对应
  条目会在激活时自动迁移清除。可删可改，删了可用「恢复内置」找回
- **JSON 导入导出**：导出到剪贴板；导入覆盖当前列表

## 开发与安装

```bash
cd plugins/quick-quotes
npm install
npm run deploy     # 构建并装进 <仓库根>/local-run/gate-home/plugins/quick-quotes/
```

前端 设置 → 插件 → 刷新；改代码后 `npm run build` → 设置页点「重载」。

## manifest

```json
{ "id": "quick-quotes", "permissions": ["kv", "net"] }
```

- `kv`：语录持久化
- `net`：读取 MCP 工具清单（hostFetch /api/mcp/status）
