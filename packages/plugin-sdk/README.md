# @gate/plugin-sdk

宿主与插件共用的唯一契约来源：类型契约 + 宿主共享 React shim + vite alias 助手。
宿主（`gate-web-ui/src/app/plugins/types.ts`）re-export 本包；插件工程直接
`import type { ... } from "@gate/plugin-sdk"`——**不允许任何工程再维护 host-types 镜像**。

## 接入

```jsonc
// 插件 package.json（devDependencies）
"@gate/plugin-sdk": "file:../../packages/plugin-sdk"   // 路径按插件在仓库内的深度调整
```

- `file:` 依赖由 npm ≥7 以 junction/symlink 落进 `node_modules`（Windows junction 不需要管理员权限）；
  换机克隆后重跑 `npm install` 即可。
- vite 配置用共享助手，**不要手写 shim 相对路径**：

```ts
import { pluginReactAliases } from "@gate/plugin-sdk/vite";
export default defineConfig({ resolve: { alias: pluginReactAliases() }, /* … */ });
```

- 本包**零运行时依赖**：`react` 仅作为类型来源放 devDependencies；shim 运行时从宿主注入的
  全局 `__GATE_PLUGIN_SHARED__` 取实例，宿主未注入即抛错（该错误信息是唯一调试线索）。

## 契约速查（唯一权威清单）

### 能力与权限（manifest.permissions 后端单一事实来源）

| 能力 | API | 说明 |
|---|---|---|
| `kv` | `ctx.kv.get/set/del` | 插件命名空间 KV，数据落 `<gateHome>/plugins-data/<id>/` |
| `net` | `ctx.hostFetch(path, init)` | 注入 Web Token 的同源 `/api/` 请求；未声明即抛错 |

### 贡献点（宿主区域插槽）

| API | 区域 | 形态 | 说明 |
|---|---|---|---|
| `ctx.registerChatInputAction(c)` | `composer.chips` | 动作型 | 输入区上方快捷 chip；`when(state)` 显隐 + `run(api, state)`。**原生 chip 不在注册表**，插件贡献永远追加在原生段之后（不可遮蔽） |
| `ctx.registerPanelWidget(w)` | `settings.plugins` | 渲染型 | 设置中心「插件」分区的面板挂件 |
| `ctx.registerPage(p)` | `nav.pages` | 渲染型 | 顶栏导航整页（`order` 升序、缺省 100）；插件禁用/重载时宿主自动关闭打开中的页面 |

动作型区域的 `ChatInputState` 快照：`ticketNo / mode / busy / terminal / stage / diffs / findingsCount / restartCount`。
`ChatActionApi`：`insertText / sendPrompt / presubmit / returnWithFindings / toast`。

### 事件总线（`ctx.on`）

| 事件 | 载荷 |
|---|---|
| `ticket.stage-changed` | `{ ticketNo, from, to }` |
| `session.created` | `{ ticketNo, sessionId, agentConfigId }` |
| `session.ended` | `{ ticketNo, sessionId, kind: "done" \| "failed" }` |
| `plugin.activated` / `plugin.deactivated` | `{ pluginId }` |
| `plugin.error` | `{ pluginId, error }` |

- **no replay**：事件在语义写入点显式 emit，订阅晚于 emit 即错过，不重放、重连不去重；
  需要"现状"请用 `ctx.hostFetch` 补拉。
- `"*"` 通配订阅收到 `{ type, payload }` 信封；单个 handler 抛异常只进日志。
- 忘记 dispose 返回的 `Disposable` 没关系——宿主停用插件时强制注销。

### 生命周期

```ts
export function activate(ctx: PluginContext): Disposable | void
```
`registerXxx` 返回值 / `ctx.onDeactivate(fn)` / `activate` 返回值三者等价，
宿主在 disable/reload 时**逆序**执行。定时器/监听器必须走其中之一清理。

## 版本与冻结语义

- `SUPPORTED_API_VERSION`（本包导出）与后端 `gate.web.plugin.PluginManifest#SUPPORTED_API_VERSION`
  **跨语言各持一份**，升级代次时两处 + CHANGELOG 必须同步；manifest 校验以后端为唯一闸门。
- 已发布字段/事件不可改名、删除或收窄；演进只做增量（新增可选字段、新增 ctx 方法、新增事件）。

## 红线

- 插件层（宿主 `app/plugins/**`）不得 import 任何 `@/features/*`——插件只拿到快照与能力 api。
- Level 1 本地可信模型：插件与页面脚本同级权限，只装可信来源（详见 plugin-template README「信任模型」）。
