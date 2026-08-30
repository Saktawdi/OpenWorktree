# 发行打包（内测 zip）

本目录只保留**模板**；`OpenWorktree/` 组装目录与 `*.zip` 产物不入库（见 .gitignore）。

## 打包步骤

1. 前端静态资源：

   ```bash
   cd gate-web-ui && npm run build
   ```

2. 后端 JAR 与依赖收集：

   ```bash
   mvn -DskipTests install
   mvn -pl gate-web dependency:copy-dependencies
   ```

3. 组装 `release/OpenWorktree/`：

   ```text
   OpenWorktree/
   ├── start_web_windows.bat    # 本目录模板，直接复制
   ├── 使用说明.txt              # 本目录模板，直接复制
   ├── lib/                     # gate-web/target/gate-web-*.jar + gate-web/target/dependency/*.jar
   └── ui/static/               # gate-web-ui/dist/* 全部内容（前端走 classpath /static 伺服）
   ```

   注意：`config/`、`data/` 由启动脚本首次运行自动生成，**不要**打进 zip。

4. 压缩：

   ```bash
   powershell -NoProfile -Command "Compress-Archive -Path OpenWorktree -DestinationPath OpenWorktree-0.1.0-win64.zip -Force"
   ```

## 冒烟验证清单

启动后逐项核对（GET 请求，不要用 `curl -I`，Javalin 对 HEAD 会返回默认 Content-Type）：

- `GET /api/health` → 200
- `GET /` → 200，index.html 内含 OpenWorktree favicon 引用
- `GET /workbench` → 200 text/html（SPA history fallback）
- `GET /brand/ow-theme-switch.webp` → 200 image/webp
- `GET /assets/index-*.js|css` → 200 text/javascript | text/css
- `data/gate-home/web-token` 已生成

## 已知坑（踩过的）

- bat 必须是 **CRLF + 纯 ASCII**（控制台文案用英文）；cmd 用 GBK 解析 UTF-8 中文行会吞行尾 `\r`，
  `chcp 65001` 也救不了解析期。中文指引放《使用说明.txt》。
- echo 文案里不能出现未转义的 `)`——在 if 代码块内会提前闭合块。
- 本机 ffmpeg/libvpx 不可用不影响打包，但后端静态伺服的动画必须用 WebP。
- 测试时若 18080 被旧开发后端占用，把测试副本的 `PORT=18080` 临时改成其他端口再跑。
