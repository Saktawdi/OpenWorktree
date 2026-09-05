# OpenWorktree 插件模板（空白起点）

复制本目录为 `plugins/<你的插件名>/`（或任意位置），改掉 manifest 的 `id` / `name`，就能开始开发。
本模板自带一个演示：composer 上方一枚「插入问候」chip + 设置中心「插件」分区的示例面板。

## 快速开始

```bash
cd plugin-template
npm install
npm run deploy          # 构建 + 安装进 <仓库根>/local-run/gate-home/plugins/
```

启动后端与前端后：浏览器 → 设置 → 插件 → 「刷新」，模板插件出现在列表里；
composer 上方出现「插入问候」chip；插件分区下方出现「模板示例面板」。

- `npm run build`：只构建（产物 `dist/index.js` + `dist/style.css`）
- `npm run watch`：增量构建（配合设置页「重载」热更新）
- `npm run deploy`：构建 + 安装（目标默认 `<仓库根>/local-run/gate-home`，可用参数或 `GATE_HOME` 覆盖）

## 目录结构

```
plugin-template/
├─ manifest.json        # 插件声明：id/name/version/apiVersion/entry/css/permissions
├─ package.json         # 独立 npm 工程（构建期依赖；运行时产物自包含）
├─ tsconfig.json
├─ vite.config.ts       # lib 构建 + react 桥接 alias
├─ scripts/install.mjs  # 构建产物 → gate-home/plugins/<id>/
└─ src/
   ├─ index.ts          # 入口：export function activate(ctx)
   ├─ panel.tsx         # 示例面板组件
   ├─ styles.css        # 插件私有样式（类名带前缀）
   ├─ host-types.ts     # 宿主契约类型镜像（与宿主 types.ts 手工同步）
   └─ host/             # SDK 桥接（勿改）：react / jsx-runtime shim
```

## 插件生命周期

```ts
export function activate(ctx) {
  const d1 = ctx.registerChatInputAction({ ... });
  const d2 = ctx.registerPanelWidget({ ... });
  return () => { d1(); d2(); };   // 清理函数（disable/reload 时被调用）
}
```

- **激活**：宿主 `import(dist/index.js)` → 调 `activate(ctx)` → 贡献点注册进宿主 UI
- **禁用**：清理链逆序执行（register 返回值、onDeactivate、activate 返回值），样式 `<link>` 移除
- **重载**：禁用 → 以新指纹重新 import（改了 dist 之后在设置页点「重载」）

红线：**必须返回清理函数或逐个调用 register 的 Disposable**；不得把定时器/监听器挂在全局而不清理。

## 共享 React（重要）

插件**不打包自己的 React**。`vite.config.ts` 把 `react` / `react/jsx-runtime` alias 到
`src/host/` 的 shim，运行时从宿主注入的全局 `__GATE_PLUGIN_SHARED__` 取**同一个 React 实例**——
所以插件组件可以直接返回 JSX、用 hooks、挂进宿主树。

推论：

- 插件里的 React 相关依赖一律放 `devDependencies`（避免 vite lib 模式把 `dependencies` 当外部依赖）
- 不要 `import react-dom`（渲染由宿主完成）；其它库（dnd-kit / phosphor-icons 等）随便用，会被完整打包
- 宿主样式类（`card` / `btn` / `chip` / `text-input` / `field-label` / `text-dim` …）全局生效，直接用即可保持视觉一致

## 能力与权限（manifest.permissions）

| 权限 | 能力 | 说明 |
|---|---|---|
| `kv` | `ctx.kv.get/set/del(key, value)` | 数据落 `<gateHome>/plugins-data/<id>/`，后端按插件命名空间隔离，重启仍在 |
| `net` | `ctx.hostFetch(path, init)` | 注入 Web Token 的同源 `/api/` 请求（如读取 `/api/mcp/status` 工具清单） |

未声明的能力调用会直接抛错。**不得绕过 ctx 直接 fetch**——鉴权与审计都在 ctx 的通道里。

## 贡献点

| API | 落点 |
|---|---|
| `ctx.registerChatInputAction({ id, label, icon?, when?, run })` | 对话输入区上方快捷 chip；`when(state)` 按工单上下文（diffs/findings/restartCount/stage/busy…）决定显隐；`run(api, state)` 里可 `api.insertText / sendPrompt / presubmit / returnWithFindings / toast` |
| `ctx.registerPanelWidget({ id, title?, render })` | 设置中心「插件」分区的管理面板（`render()` 返回 React 节点） |

icon 只能引用宿主白名单（见 `src/host-types.ts` 注释），未知名回落对话图标。

## 安装形态

```
<gateHome>/plugins/<id>/     ← npm run deploy 的目标
  manifest.json
  dist/index.js
  dist/style.css
```

## 信任模型（务必阅读）

本插件体系是 **Level 1 本地可信插件**：插件代码与页面脚本同级权限（同源、可访问 DOM 与全局）。
只安装你自己或信任来源的插件；将来面向第三方分发时需要引入沙箱层（iframe/Worker），不在本模板范围。

## 常见问题

- **改了代码不生效**：`npm run build` → 设置页「重载」（指纹变化才会重新 import）
- **激活失败/面板渲染崩溃**：设置页会展示具体错误；渲染崩溃卡片上有「重试渲染」
- **demo 模式看不到插件**：插件设施依赖后端目录与资产，连接后端（live）才有
- **宿主契约升级**：改了 `gate-web-ui/src/app/plugins/types.ts` 后需同步 `src/host-types.ts`，
  并把 manifest 的 `apiVersion` 与宿主 `SUPPORTED_API_VERSION` 对齐
