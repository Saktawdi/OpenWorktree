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

未声明的能力对应 ctx 成员为 `null`（或调用抛错）；manifest 声明列表形如
`["kv", "net"]`。

| 能力 | ctx 注入 | 说明 |
|---|---|---|
| `kv` | `ctx.kv.get/set/del` | 插件命名空间 KV，数据落 `<gateHome>/plugins-data/<id>/`，后端按插件隔离 |
| `net` | `ctx.hostFetch(path, init)` | 注入 Web Token 的同源 `/api/` 相对路径请求（method 限 GET/POST/PUT/DELETE）；未声明即抛错，非 `/api/` 路径也抛错 |
| `storage` | `ctx.storage.getItem/setItem/removeItem/clear` | 浏览器 localStorage，键自动加 `gate_plugin_<id>:` 前缀按插件隔离（不跨插件、不随工单） |
| `llm` | `ctx.llm.chat / ctx.llm.chatStream` | 走宿主已配置的 LLM Provider 的单次/流式对话（`messages` 必填；`providerId / model / temperature / maxTokens` 可选——缺省取第一个已配置的 Provider 及其首个模型） |

`ctx.log(...)` 恒可用（控制台输出带 `[plugin:<id>]` 前缀），无需权限。

### 贡献点（宿主区域插槽）

| API | 区域 | 形态 | 说明 |
|---|---|---|---|
| `ctx.registerChatInputAction(c)` | `composer.chips` | 动作型 | 输入区上方快捷 chip；`when(state)` 显隐 + `run(api, state)`。**原生 chip 不在注册表**，插件贡献永远追加在原生段之后（不可遮蔽） |
| `ctx.registerSelectionAction(c)` | `selection.menu` | 动作型 | 划选页面文字弹出菜单的动作（如「添加到 xxx」）。**内置「添加到对话框」不在注册表**，插件动作追加在其后；`when({ text })` 按划选文本显隐，`run(api, text)` 里可 `api.addToComposer(text) / insertText(text) / toast(text)` |
| `ctx.registerPanelWidget(w)` | `settings.plugins` | 渲染型 | 设置中心「插件」分区的面板挂件 |
| `ctx.registerPage(p)` | `nav.pages` | 渲染型 | 顶栏导航整页（`order` 升序、缺省 100）；插件禁用/重载时宿主自动关闭打开中的页面 |
| `ctx.registerHeaderAction(a)` | `header.actions` | 渲染型 | 顶栏右上角胶囊动作区（`order` 升序、缺省 100）；LLM 小助手已原生化进宿主，此槽开放给第三方胶囊动作 |
| `ctx.registerFloatingWidget(w)` | `floating.widgets` | 渲染型 | 全局悬浮挂件（全视口自由浮动挂载，如可拖拽的 mini 对话面板） |

渲染型区域的 `render()` 返回 React 节点，经共享 shim 用**宿主同一个 React 实例**，
可用 hooks、可直接用宿主全局样式类。

动作型区域的 `ChatInputState` 快照：`ticketNo / mode / busy / terminal / stage / diffs / findingsCount / restartCount`。
`ChatActionApi`：`insertText / sendPrompt / presubmit / returnWithFindings / toast`。
划选菜单 `run(api, text)` 的 `SelectionActionApi`：`addToComposer / insertText / toast`。

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
- 同代次内允许的增量（`apiVersion="1"` 下已追加过）：`selection.menu` / `nav.pages` /
  `header.actions` / `floating.widgets` 插槽与 `storage` / `llm` 权限、`ctx.log`——插件按需探测
  ctx 成员是否为 `null` 即可向下兼容，无需升代次。

## 红线

- 插件层（宿主 `app/plugins/**`）不得 import 任何 `@/features/*`——插件只拿到快照与能力 api。
- Level 1 本地可信模型：插件与页面脚本同级权限，只装可信来源（详见 plugin-template README「信任模型」）。
