# OpenWorktree 桌面壳（Tauri v2）

托管后端单文件二进制（sidecar），提供原生窗口 + 免手动输入登录令牌。

## 工作方式

1. 启动时以 sidecar 拉起 `src-tauri/binaries/ow-<target-triple>.exe`（后端单文件，见仓库 CI 的 native-build 产物）；
2. 读取其 stdout 中的 `GATE_WEB_TOKEN` 与 `listening on http://...:port`；
3. WebView 跳转 `http://127.0.0.1:<port>/?ow-token=<token>`，SPA 的 `boot()` 完成自动登录并抹掉 URL 参数；
4. 窗口关闭时收割后端进程（`RunEvent::ExitRequested` 显式 kill），不留孤儿。

前置依赖不变：后端需要 `git`；跑 Agent 会话需要对应 CLI。

## 本地构建

```powershell
# 1. 放置 sidecar：下载 CI artifact ow-native-Windows，
#    复制为 src-tauri/binaries/ow-x86_64-pc-windows-msvc.exe
# 2. 构建
npm install
npx tauri build        # 安装包在 src-tauri/target/release/bundle/nsis/
```

注意（Git Bash 环境）：`/usr/bin/link` 会遮蔽 MSVC link.exe，需把
`VC/Tools/MSVC/<ver>/bin/Hostx64/x64` 前置到 PATH，并设置 `LIB`/`INCLUDE`
指向 MSVC 与 Windows SDK（CI 上无需处理）。

## 本地 dev：无 GraalVM 时的 JVM 垫片侧车（dev-shim-ow）

native 单文件只能由 CI 的 GraalVM 产出；本机只有普通 JDK 时，用 `dev-shim-ow/`
的 Rust 垫片顶替 sidecar 跑 `tauri dev`：

```bash
cd dev-shim-ow && cargo build --release        # 需 MSVC link 环境（同上）
cp target/release/ow-dev-shim.exe src-tauri/binaries/ow-x86_64-pc-windows-msvc.exe
cd .. && npm run tauri dev
```

垫片行为：按 `OW_DEV_REPO`（缺省向上找带 `gate-web/target/dependency` 的祖先）定位
仓库根，spawn `java -cp <模块 classes + 非 gate-* 依赖 jar + local-run/ui-dev-root>
gate.web.GateWebApp --config local-run/gate.toml`，并把 JVM 的 stdout/stderr 泵给壳
（壳按行解析令牌与端口）。要点：

- **显式管道转发，不做句柄继承**：tauri 以管道作垫片 stdio 时，继承路径下 JVM 的
  输出到不了壳（java 在写、壳收不到）；
- **Job Object（kill-on-close）**：垫片进程被壳收割（含 TerminateProcess）时，
  java 子进程随之被系统回收，不留孤儿；
- SPA 用 `local-run/ui-dev-root/static/`（`gate-web-ui/dist` 的拷贝）伺服，改前端后
  需重新 build + 拷贝；
- 测完还原：删掉垫片 exe，把 `ow-x86_64-pc-windows-msvc.exe.native-backup`（若留有）
  改回原名即可换回 native 侧车。

## 打包内容

安装包 = 壳（~8MB）+ 后端单文件（~60MB）。

## 数据目录

后端工作目录（`local-run\` 的 gate.toml、SQLite、令牌、工单克隆都落在其中）由壳的
`resolve_data_dir` 决定，优先级：

1. **安装目录 `data\`**（默认）——数据随安装盘走（克隆体积会持续增长，装在哪个盘由用户
   安装时决定）；卸载时 NSIS 钩子（`installer-hooks.nsh`）会询问保留或删除，选保留则
   数据移到 `%APPDATA%\OpenWorktree\data`，重装时自动搬回；
2. **`%APPDATA%\OpenWorktree\data`**——上次卸载「保留」的数据（重装搬回失败时兜底）；
3. **`%APPDATA%\com.openworktree.desktop`**——安装目录不可写（如 perMachine 装进
   Program Files）时的兜底，也是旧版数据的位置：首次运行会整体搬入安装目录 `data\`。

`tauri dev`（debug 构建）不做迁移；dev-shim 侧车使用仓库内 `local-run/gate.toml`，
与上述目录无关。`backend-boot.log` 落在所选数据目录根。

