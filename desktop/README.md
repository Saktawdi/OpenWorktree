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

## 打包内容

安装包 = 壳（~8MB）+ 后端单文件（~60MB）。数据仍在运行目录的 `local-run\`。
