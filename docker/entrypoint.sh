#!/usr/bin/env bash
# OpenWorktree 容器入口：
#   1) 首启生成 /data/gate.toml（bind 固定容器内回环 127.0.0.1:18080 —— 后端
#      刻意拒绝 0.0.0.0，见 GateConfig.WebConfig；对外的 8080 由 nginx 监听）
#   2) 拉起 Java 后端（$OW_WEB_PORT，容器内）
#   3) 后端就绪后拉起 nginx（8080），任一进程退出则整个容器退出
set -euo pipefail

DATA_DIR=/data
CONFIG="$DATA_DIR/gate.toml"
GATE_HOME="${OW_GATE_HOME:-$DATA_DIR/gate-home}"
WEB_PORT="${OW_WEB_PORT:-18080}"

mkdir -p "$GATE_HOME" "$DATA_DIR/logs" "$DATA_DIR/nginx-tmp"

# —— OpenCode 全局配置目录软链到 /data 卷 ——
# 智能体页的供应商 CRUD 直接写 $HOME/.config/opencode/opencode.json（与桌面版
# opencode 同一份全局配置，会话子进程会把它与每会话 MCP 配置合并读取）；
# HOME 在镜像层不持久，软链进卷后重建容器不丢已配置的供应商。
OC_PARENT="${HOME:-/home/openworktree}/.config"
mkdir -p "$OC_PARENT" "$DATA_DIR/opencode"
if [ ! -L "$OC_PARENT/opencode" ]; then
    if [ -d "$OC_PARENT/opencode" ]; then
        # 镜像里若已预置内容，迁移进卷（卷里同名文件优先，不覆盖）
        cp -rn "$OC_PARENT/opencode/." "$DATA_DIR/opencode/" 2>/dev/null || true
        rm -rf "$OC_PARENT/opencode"
    fi
    ln -s "$DATA_DIR/opencode" "$OC_PARENT/opencode"
fi

# —— 首次启动生成配置 ——
if [ ! -f "$CONFIG" ]; then
    origins='"127.0.0.1", "localhost"'
    if [ -n "${OW_ALLOWED_ORIGINS:-}" ]; then
        IFS=',' read -ra extra_hosts <<< "$OW_ALLOWED_ORIGINS"
        for host in "${extra_hosts[@]}"; do
            host="$(printf '%s' "$host" | tr -d '[:space:]')"
            [ -n "$host" ] && origins="$origins, \"$host\""
        done
    fi
    cat > "$CONFIG" <<TOML
# OpenWorktree 容器内生成的默认配置；之后直接编辑本文件并重启容器即可。
schema_version = 2
project = "openworktree"
gate_home = "$GATE_HOME"

[web]
bind = "127.0.0.1"
port = $WEB_PORT
allowed_origins = [$origins]
TOML
    echo "[openworktree] generated default config: $CONFIG"
fi

# —— 类路径：gate-* jar 与第三方依赖都在 /app/lib，SPA 构建产物在 /app/ui/static ——
CP="/app/lib/*:/app/ui"

JVM_ARGS=(-Dfile.encoding=UTF-8)
if [ -n "${JAVA_OPTS:-}" ]; then
    # shellcheck disable=SC2206
    EXTRA=($JAVA_OPTS)
    JVM_ARGS+=("${EXTRA[@]}")
fi

java "${JVM_ARGS[@]}" -cp "$CP" gate.web.GateWebApp --config "$CONFIG" &
JAVA_PID=$!

# —— 等后端就绪（最多 90s），后端若直接退出则容器退出并带上它的退出码 ——
ready=0
for _ in $(seq 1 90); do
    if curl -fsS "http://127.0.0.1:$WEB_PORT/api/health" >/dev/null 2>&1; then
        ready=1
        break
    fi
    if ! kill -0 "$JAVA_PID" 2>/dev/null; then
        echo "[openworktree] backend exited during startup"
        wait "$JAVA_PID" || exit $?
        exit 1
    fi
    sleep 1
done
if [ "$ready" != "1" ]; then
    echo "[openworktree] backend did not become healthy within 90s"
    exit 1
fi
echo "[openworktree] backend healthy, starting nginx on :8080"

nginx -c /app/nginx/nginx.conf &
NGINX_PID=$!

shutdown() {
    kill -TERM "$JAVA_PID" 2>/dev/null || true
    nginx -c /app/nginx/nginx.conf -s quit 2>/dev/null || true
}
trap shutdown SIGTERM SIGINT

# 任一进程先退出 → 回收另一个，以其退出码结束容器
set +e
wait -n "$JAVA_PID" "$NGINX_PID"
STATUS=$?
set -e
shutdown || true
wait || true
exit "${STATUS:-1}"
