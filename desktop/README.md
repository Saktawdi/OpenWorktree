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

## 本地 dev：无 GraalVM 时的 JVM 垫片侧车（sidecar-jvm-shim）

native 单文件只能由 CI 的 GraalVM 产出；本机只有普通 JDK 时，用
`sidecar-jvm-shim/` 的 Rust 垫片顶替 sidecar 跑 `tauri dev`（用法详见
`sidecar-jvm-shim/README.md`）：

```bash
cd desktop/sidecar-jvm-shim && cargo build --release   # MSVC link 环境有问题时跑 build-shim.bat
cp target/release/sidecar-jvm-shim.exe ../src-tauri/binaries/ow-x86_64-pc-windows-msvc.exe
cd .. && npm run tauri dev
```

垫片以 JVM 拉起 `gate.web.GateWebApp`（`JAVA_HOME\bin\java.exe`，无则取 PATH 上的 java），
stdout/stderr/退出码原样透传，壳的自动登录流程不变。要点：

- **仓库根探测**：按 exe 位置逐级向上找含 `gate-web/target/classes` 的目录——tauri dev
  直跑 `binaries/`（仓库根下 3 层）与复制进 `target/debug/`（5 层）通吃，也可用
  `OW_SIDECAR_REPO_ROOT` 显式覆盖；
- **classpath**：`gate-web-ui/.sidecar-classpath`（junction → `dist`，伺服 SPA）垫最前 +
  各模块 `target/classes` + `gate-web/target/dependency/*`；需先 `mvn -DskipTests install`
  并构建前端 + 建 junction（缺失时后端照常起，但桌面壳 404 白屏，垫片会打警告）；
- **孤儿保护**：java 挂进 KILL_ON_JOB_CLOSE 作业对象——壳退出收割垫片（含
  TerminateProcess）时内核连带结束 java，不留占端口的孤儿后端；
- 首次替换先把原 exe 备份为 `ow-x86_64-pc-windows-msvc.exe.shim-backup`，测完还原同名
  文件即可换回 native 侧车；
- **仅 dev 可用**：垫片离开仓库根（如被打进安装包）即报错退出，打包必须换回 native 单文件。

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

`tauri dev`（debug 构建）不做迁移；垫片（sidecar-jvm-shim）拉起的 JVM 后端沿用
`GateWebApp` 默认配置仓库内 `local-run/gate.toml`，与上述目录无关。`backend-boot.log`
落在所选数据目录根。

