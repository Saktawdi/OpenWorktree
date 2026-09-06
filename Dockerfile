# syntax=docker/dockerfile:1
#
# OpenWorktree — 单容器一体化镜像。
#
# 设计约束：后端 GateWebApp 刻意拒绝绑定 0.0.0.0（fail-closed loopback 安全边界，
# 见 GateConfig.WebConfig）。因此容器内采用「nginx 监听 8080 → 反代到容器内回环
# 127.0.0.1:18080 的 Java 进程」的结构，对外的唯一入站口是 nginx，后端仍留在
# 容器内 loopback 上——不改代码、不削弱原有安全设计。
#
#   docker build -t openworktree .
#   docker build -t openworktree --build-arg INSTALL_CLAUDE=true .   # 附装 Claude Code
#
# ─── 阶段 1：前端构建 ────────────────────────────────────────────────
FROM node:20-bookworm-slim AS ui-build
WORKDIR /ui
# package-lock 在开发机平台生成，rollup/esbuild 的平台原生二进制记在
# optionalDependencies 里，跨平台 npm ci 会缺包（npm/cli#4828）。与
# start-local.bat 的 `npm install --no-package-lock` 同一策略：按当前
# 平台重新解析，amd64/arm64 构建均可用。
COPY gate-web-ui/package.json ./
RUN npm install --no-audit --no-fund
COPY gate-web-ui/ ./
RUN npm run build

# ─── 阶段 2：后端构建 ────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-17 AS backend-build
WORKDIR /src

# 先只拷贝 pom，利用层缓存预热依赖（源码变动不至于整条依赖重拉）
COPY pom.xml ./
COPY gate-domain/pom.xml gate-domain/pom.xml
COPY gate-ports/pom.xml gate-ports/pom.xml
COPY gate-application/pom.xml gate-application/pom.xml
COPY gate-adapters/pom.xml gate-adapters/pom.xml
COPY gate-bootstrap/pom.xml gate-bootstrap/pom.xml
COPY gate-cli/pom.xml gate-cli/pom.xml
COPY gate-web/pom.xml gate-web/pom.xml
COPY gate-ws-patch/pom.xml gate-ws-patch/pom.xml
RUN mvn -B -q dependency:resolve || true

COPY gate-domain gate-domain
COPY gate-ports gate-ports
COPY gate-application gate-application
COPY gate-adapters gate-adapters
COPY gate-bootstrap gate-bootstrap
COPY gate-cli gate-cli
COPY gate-web gate-web
COPY gate-ws-patch gate-ws-patch
RUN mvn -B -DskipTests install

# 运行时类路径 = gate-web 的全部传递依赖 jar（含各 gate-* 模块 jar：
# 其中已打包好类与 Flyway 迁移等资源，见 start-local.bat 的说明）；
# copy-dependencies 不含 gate-web 自身构件，补拷进去（本地流程用
# target/classes 顶这个位，容器内统一走 jar）。
RUN mvn -B -q -DskipTests -pl gate-web dependency:copy-dependencies \
 && cp /src/gate-web/target/gate-web-*.jar /src/gate-web/target/dependency/

# ─── 阶段 3：运行时 ─────────────────────────────────────────────────
FROM eclipse-temurin:17-jre

RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      git curl bash nginx ca-certificates \
 && rm -rf /var/lib/apt/lists/*

# Agent CLI：opencode 是项目的默认会话 CLI（GateConfig 的 session.default_cli 默认值），
# 镜像直接内置；Claude Code 等其余 CLI 选装：
#   docker build --build-arg INSTALL_CLAUDE=true .
ARG INSTALL_CLAUDE=false
RUN apt-get update \
 && apt-get install -y --no-install-recommends nodejs npm \
 && npm install -g opencode-ai \
 && if [ "$INSTALL_CLAUDE" = "true" ]; then \
      npm install -g @anthropic-ai/claude-code ; \
    fi \
 && npm cache clean --force \
 && rm -rf /var/lib/apt/lists/*

RUN useradd --system --uid 10002 --user-group --create-home --shell /bin/bash openworktree

WORKDIR /app
COPY --from=backend-build /src/gate-web/target/dependency /app/lib
# SPA 伺服点：GateWebApp 从 classpath 的 /static 读取前端资源（带 history fallback）
COPY --from=ui-build /ui/dist /app/ui/static
COPY docker/nginx.conf /app/nginx/nginx.conf
COPY docker/entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh \
 && sed -i 's/\r$//' /app/entrypoint.sh /app/nginx/nginx.conf \
 && mkdir -p /data \
 && chown -R openworktree:openworktree /data /app/ui /app/nginx

USER openworktree
WORKDIR /data

# OW_ALLOWED_ORIGINS: 逗号分隔的额外可用 Host（默认仅 127.0.0.1/localhost 可达 API）
# JAVA_OPTS:          追加 JVM 参数（如 -Xmx1g）
ENV OW_WEB_PORT=18080 \
    OW_GATE_HOME=/data/gate-home

EXPOSE 8080
VOLUME ["/data"]

ENTRYPOINT ["/app/entrypoint.sh"]
