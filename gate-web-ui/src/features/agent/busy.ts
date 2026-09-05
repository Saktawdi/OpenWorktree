/**
 * 智能体域运行轮询（agent busy）：GET /api/agents/busy 的定时拉取，
 * 含可见性联动与工单列表慢节拍补拉。
 */
import { api } from "@/net";
import { appStore } from "@/store";
import { loadTickets } from "@/features/ticket/api";
import { syncSessionTodos } from "@/features/session/api";

interface RawBusyAgent {
  session_id: string;
  title: string | null;
  ticket_no: string | null;
  cli: string | null;
}

/**
 * 后台运行会话的任务清单收敛（无 SSE 直连时的兜底）：
 * 正在查看的会话在跑、但本页没有它的 EventSource（刷新后/外部发起/事件断流）时，
 * 按轮询节拍用服务端历史回算任务清单——「等 agent 回复完才出现 / 只手动触发才刷新」
 * 的问题在回合落库后最多延迟一拍自动对齐。本地直连的会话由 SSE 实时回写，历史滞后
 * （整回合 idle 才落库），这里必须跳过，否则会把流式清单误清成旧数据。
 */
function syncViewedRunningSessionTodos() {
  const st = appStore.getState();
  if (st.mode !== "live" || st.conn !== "ok") return;
  const no = st.selectedNo;
  if (!no) return;
  const sid = st.activeSessionId[no];
  if (!sid || st.liveTurns[sid]) return;
  const running =
    st.runningAgents.count > 0 &&
    st.runningAgents.sessions.some((r) => r.ticket_no === no && r.session_id === sid);
  if (!running) return;
  void syncSessionTodos(no, sid);
}

export async function fetchBusyAgents(): Promise<void> {
  const st = appStore.getState();
  // 非 live 或未连接时清空并停止轮询（按契约失败时静默）
  if (st.mode !== "live" || st.conn !== "ok") {
    appStore.setState({ runningAgents: { count: 0, sessions: [] } });
    stopAgentBusyPolling();
    return;
  }
  try {
    const data = await api<{ count: number; running: RawBusyAgent[] }>("/api/agents/busy");
    appStore.setState({
      runningAgents: {
        count: typeof data.count === "number" ? data.count : 0,
        sessions: Array.isArray(data.running)
          ? data.running.map((r) => ({
              session_id: r.session_id,
              title: r.title ?? null,
              ticket_no: r.ticket_no ?? null,
              cli: r.cli ?? null,
            }))
          : [],
      },
    });
    // 当前查看会话若由后台运行（本页无 SSE），按拍收敛其任务清单
    syncViewedRunningSessionTodos();
  } catch {
    // 请求失败按现有 fetch 封装行为处理：静默，不改状态
  }
}

let busyPollTimer: ReturnType<typeof setInterval> | null = null;
let busyPollVisibilityAttached = false;
// 工单列表慢节拍补拉的节流窗与上次时间戳（15s：agent 建票不必实时推送，最终一致即可）
const TICKETS_REFETCH_MS = 15000;
let lastTicketsFetch = 0;

function handleBusyVisibility() {
  // 切回可见时立即拉一次（含工单列表：后台 agent 可能在不可见期间建了票）
  if (document.hidden) return;
  const st = appStore.getState();
  if (st.mode !== "live" || st.conn !== "ok") {
    appStore.setState({ runningAgents: { count: 0, sessions: [] } });
    stopAgentBusyPolling();
    return;
  }
  void fetchBusyAgents();
  lastTicketsFetch = Date.now();
  void loadTickets().catch(() => {});
}

export function startAgentBusyPolling() {
  // 重复调用不得叠加定时器
  if (busyPollTimer !== null) return;
  const st = appStore.getState();
  if (st.mode !== "live" || st.conn !== "ok") {
    appStore.setState({ runningAgents: { count: 0, sessions: [] } });
    return;
  }
  // 绑定 visibilitychange（仅一次）
  if (!busyPollVisibilityAttached) {
    document.addEventListener("visibilitychange", handleBusyVisibility);
    busyPollVisibilityAttached = true;
  }
  // 立即拉一次，再按 3 秒轮询
  void fetchBusyAgents();
  busyPollTimer = setInterval(() => {
    // document.hidden 时暂停
    if (document.hidden) return;
    const cur = appStore.getState();
    if (cur.mode !== "live" || cur.conn !== "ok") {
      appStore.setState({ runningAgents: { count: 0, sessions: [] } });
      stopAgentBusyPolling();
      return;
    }
    void fetchBusyAgents();
    // 工单可由 agent 经 MCP ticket_create 带外创建；列表只在连接建立时加载过，
    // 这里按慢节拍补拉，让侧栏/看板最终一致（失败静默，下一拍重试）。
    if (Date.now() - lastTicketsFetch >= TICKETS_REFETCH_MS) {
      lastTicketsFetch = Date.now();
      void loadTickets().catch(() => {});
    }
  }, 3000);
}

export function stopAgentBusyPolling() {
  if (busyPollTimer !== null) {
    clearInterval(busyPollTimer);
    busyPollTimer = null;
  }
  if (busyPollVisibilityAttached) {
    document.removeEventListener("visibilitychange", handleBusyVisibility);
    busyPollVisibilityAttached = false;
  }
  // 停轮询时若已不在 live/ok 也清零（调用方可能已清，这里兜底）
  const st = appStore.getState();
  if (st.mode !== "live" || st.conn !== "ok") {
    // 避免无谓 setState 触发订阅：仅在非空时清
    if (st.runningAgents.count !== 0 || st.runningAgents.sessions.length !== 0) {
      appStore.setState({ runningAgents: { count: 0, sessions: [] } });
    }
  }
}
