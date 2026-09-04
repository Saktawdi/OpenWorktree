# sidecar-jvm-shim —— 开发用 sidecar 垫片（替代 native 单文件）

桌面壳（src-tauri）以 sidecar 方式拉起 `binaries/ow-{triple}.exe`，并从它的 stdout 抓
`GATE_WEB_TOKEN=` 与 `listening on http://127.0.0.1:<port>/` 完成自动登录。正式发布链路里
这个 exe 是 GraalVM native-image 单文件（CI 产出）；本地没有 GraalVM 工具链时，用本垫片
以 JVM 方式拉起同一后端，让 `npm run dev`（tauri dev）直接跑最新代码。

## 工作方式

- 按 exe 相对位置定位仓库根（`desktop/src-tauri/binaries → 仓库根`）；找不到时可用环境变量
  `OW_SIDECAR_REPO_ROOT` 指定。
- classpath：`gate-web-ui/.sidecar-classpath`（垫在最前，`static/` 服务 SPA）+ 各模块
  `target/classes` + `gate-web/target/dependency/*`。**先跑 `mvn -DskipTests install`
  和 `gate-web-ui` 的构建**，否则后端/界面是旧的或缺类。
- stdout/stderr 原样透传（壳两侧都读）；退出码透传。
- 作业对象 KILL_ON_JOB_CLOSE：壳退出收割垫片时，java 子进程随作业一起退出，不留占端口的孤儿。
- 仅适用于 dev：打包（tauri build）装进安装包的必须还是 native 单文件，垫片离开仓库根即失效。

## 使用

```bash
# 前置：构建后端与前端
mvn -DskipTests install
(cd gate-web-ui && npm run build)
# 静态目录 junction（一次性；dist 重建后仍有效）
cd gate-web-ui && cmd //c "mklink //J .sidecar-classpath\\static dist" && cd ../..
# 编译并替换 sidecar（先备份原 exe）
cargo build --release --manifest-path desktop/sidecar-jvm-shim/Cargo.toml
cp desktop/src-tauri/binaries/ow-x86_64-pc-windows-msvc.exe{,.shim-backup}
cp desktop/sidecar-jvm-shim/target/release/sidecar-jvm-shim.exe \
   desktop/src-tauri/binaries/ow-x86_64-pc-windows-msvc.exe
# 跑起来
(cd desktop && npm run dev)
```
