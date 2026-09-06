/**
 * 智能体域运行轮询（agent busy）：GET /api/agents/busy 的定时拉取，
 * 含可见性联动与工单列表慢节拍补拉。
 */
import { api } from "@/net";
import { appStore } from "@/store";
import { loadTickets } from "@/features/ticket/api";
import { loadSessionPermissions, loadSessionQuestions } from "@/features/session/permissions";
import { syncSessionTodos } from "@/features/session/api";

interface RawBusyAgent {
  session_id: string;
  title: string | null;
  ticket_no: string | null;
  cli: string | null;
}

/**
 * 后台运行会话的任务清单收敛（无 SSE 直连时的兜底）：
 * 只在「当前查看的会话从运行中转为空闲」的那一刻用服务端已落库历史回算一次任务
 * 清单——回合落库后最多延迟一拍自动对齐，兑现"不手动刷新也能出现/更新"。
 * 运行中绝不清零：本页没有 EventSource 时（刷新后/外部发起/事件断流），服务端
 * 历史要到整回合 idle 才落库，运行期间按历史重建只会把已显示的清单误清空
 * （agent 仍在输出中图标消失）；本地直连的会话由 SSE 实时回写，同样跳过。
 */
let prevRunningSessionIds = new Set<string>();

function syncSessionTodosOnRunEnd() {
  const st = appStore.getState();
  if (st.mode !== "live" || st.conn !== "ok") return;
  const no = st.selectedNo;
  if (!no) return;
  const sid = st.activeSessionId[no];
  if (!sid || st.liveTurns[sid]) return;
  const runningNow = new Set(st.runningAgents.sessions.map((r) => r.session_id));
  if (!prevRunningSessionIds.has(sid) || runningNow.has(sid)) return;
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
    // 当前查看会话刚结束一次后台运行（上一拍还在跑、这一拍已空闲）→ 收敛任务清单
    syncSessionTodosOnRunEnd();
    prevRunningSessionIds = new Set(appStore.getState().runningAgents.sessions.map((r) => r.session_id));
  } catch {
    // 请求失败按现有 fetch 封装行为处理：静默，不改状态
  }
}

let busyPollTimer: ReturnType<typeof setInterval> | null = null;
let busyPollVisibilityAttached = false;
// 工单列表慢节拍补拉的节流窗与上次时间戳（15s：agent 建票不必实时推送，最终一致即可）
const TICKETS_REFETCH_MS = 15000;
let lastTicketsFetch = 0;

/**
 * 运行中会话的待决权限/提问恢复拉取（15s 慢节拍，与工单补拉同一拍）：
 * permission_asked 只经 SSE 实时推送——SSE 断流/页面刷新期间到达的询问，此前
 * 只有点击工单 item 才会经 REST 恢复成卡片（用户看着"待授权"徽标却没有可点的
 * 卡片）。运行集合拍时对所有 busy 会话补拉一次；pushPermissionRequest 内部按
 * id 去重，重复拉取无副作用。
 */
function hydratePendingAsks(sessions: Array<{ session_id: string; ticket_no: string | null }>) {
  for (const r of sessions) {
    const no = r.ticket_no;
    if (!no) continue;
    void loadSessionPermissions(no, r.session_id).catch(() => {});
    void loadSessionQuestions(no, r.session_id).catch(() => {});
  }
}

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
      // 同一慢节拍：补拉运行中会话的待决权限/提问卡片（不依赖用户点工单 item）
      const st = appStore.getState();
      const busy = st.runningAgents.sessions;
      if (busy.length > 0) hydratePendingAsks(busy);
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
  // 运行集随轮询停止作废：重启轮询时以新一轮快照为准，不触发陈旧 transition。
  prevRunningSessionIds = new Set<string>();
  // 停轮询时若已不在 live/ok 也清零（调用方可能已清，这里兜底）
  const st = appStore.getState();
  if (st.mode !== "live" || st.conn !== "ok") {
    // 避免无谓 setState 触发订阅：仅在非空时清
    if (st.runningAgents.count !== 0 || st.runningAgents.sessions.length !== 0) {
      appStore.setState({ runningAgents: { count: 0, sessions: [] } });
    }
  }
}
