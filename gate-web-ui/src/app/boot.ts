/**
 * 应用启动编排（app boot）：demo 种子、桌面壳令牌、后端探测与轮询启停。
 */
import { appStore } from "@/store";
import { seedDemo } from "@/demo/seed";
import { detectBackend } from "@/net";
import { loadEngineConfig } from "@/features/gate";
import { startAgentBusyPolling, stopAgentBusyPolling } from "@/features/agent";
import { actions } from "./actions";

export async function boot() {
  seedDemo();
  // 桌面壳免登录：壳内 iframe 以 ?ow-token= 跳入。仅在 iframe 环境（window.parent !== window）
  // 消费 URL 令牌——浏览器直开的部署（含公网 Docker）永远忽略该参数，不接受 URL 凭据。
  // 令牌存 sessionStorage 供壳内 F5 静默重连：标签页级隔离、关闭即焚、不随请求传输，
  // 与既有快照机制（gate-ui-state-v3 同样含 token）同一作用域，访客侧始终需要输入令牌。
  const inShell = window.parent !== window;
  const shellToken = inShell
    ? new URLSearchParams(window.location.search).get("ow-token")
    : null;
  let savedToken: string | null = null;
  try {
    savedToken = shellToken ? null : sessionStorage.getItem("ow-desktop-token");
  } catch {
    /* 隐私模式等存储不可用场景：静默降级为手动登录 */
  }
  if (shellToken || savedToken) {
    if (shellToken) {
      window.history.replaceState(null, "", window.location.pathname);
      try {
        sessionStorage.setItem("ow-desktop-token", shellToken);
      } catch {
        /* 配额满等场景忽略——本会话退化为 F5 需重连，可接受 */
      }
    }
    const token = shellToken ?? savedToken;
    if (token && (await actions.connectLive(token))) {
      return;
    }
    try {
      sessionStorage.removeItem("ow-desktop-token");
    } catch {
      /* ignore */
    }
  }
  const conn = await detectBackend();
  appStore.setState({ conn });
  // 已在 live 模式且后端连通时启动运行中智能体轮询
  const st = appStore.getState();
  if (st.mode === "live" && conn === "ok") {
    startAgentBusyPolling();
    // 刷新后走 sessionStorage 恢复，不会经过 connectLive：引擎配置（AI 审查入口的
    // 可用性判断）必须在这里补拉，否则 engine 恒为 null，按钮永远停在"配置读取中"。
    void loadEngineConfig();
  } else if (conn !== "ok") {
    appStore.setState({ runningAgents: { count: 0, sessions: [] } });
    stopAgentBusyPolling();
  }
  // 监听后续模式/连接状态变化，自动启停轮询
  let prevMode = st.mode;
  let prevConn: string = conn;
  appStore.subscribe((cur) => {
    if (cur.mode !== prevMode || cur.conn !== prevConn) {
      prevMode = cur.mode;
      prevConn = cur.conn;
      if (cur.mode === "live" && cur.conn === "ok") startAgentBusyPolling();
      else {
        appStore.setState({ runningAgents: { count: 0, sessions: [] } });
        stopAgentBusyPolling();
      }
    }
  });
}
