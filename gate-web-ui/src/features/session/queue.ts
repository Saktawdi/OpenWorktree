/**
 * 会话排队消息管理（session message queue · T-107）：
 * - 按会话（sessionId）严格隔离，消息绑定 ticketNo + sessionId；
 * - 本地持久化（localStorage，prefs.ts 的键集中读写）；
 * - 支持入队 / 移除 / 编辑回填（popToInput）/ 排序 / 清空；
 * - 自动出队泵：监听 store 的 busy→idle 转换，会话一空闲就按 FIFO 逐条自动发送，
 *   覆盖所有回合结束路径（本地 SSE、demo 引擎、他端/断流回合经 busy 轮询），
 *   幂等（会话 busy 或有泵在跑则跳过），不会重复投递。
 */
import { appStore } from "@/store";
import { saveFollowUpBehavior, saveQueuedMessages } from "@/store/prefs";
import type { FollowUpBehavior, PendingAttachment, QueuedMessage } from "@/shared/types";
import { uid } from "@/shared/format";

const set = appStore.setState;
const s = () => appStore.getState();

export const EMPTY_QUEUE: QueuedMessage[] = [];

/** 设置 Agent 输出时的跟随行为：queue（排队）| steer（插队）。 */
export function setFollowUpBehavior(behavior: FollowUpBehavior) {
  set({ followUpBehavior: behavior });
  saveFollowUpBehavior(behavior);
}

/** 读取某会话的排队列表（只读）。 */
export function getQueuedMessages(sessionId: string): QueuedMessage[] {
  if (!sessionId) return EMPTY_QUEUE;
  return s().queuedMessages[sessionId] ?? EMPTY_QUEUE;
}

/** 队列入队：消息追加到会话队列末尾并落盘；返回生成的条目。 */
export function addMessageToQueue(
  ticketNo: string,
  sessionId: string,
  content: string,
  attachments: PendingAttachment[] = [],
): QueuedMessage {
  const item: QueuedMessage = {
    id: uid("qm"),
    ticketNo,
    sessionId,
    content,
    attachments: attachments.length > 0 ? [...attachments] : undefined,
    createdAt: Date.now(),
  };

  set((st) => {
    const list = st.queuedMessages[sessionId] ?? [];
    const nextList = [...list, item];
    saveQueuedMessages({ ...st.queuedMessages, [sessionId]: nextList });
    return { queuedMessages: { ...st.queuedMessages, [sessionId]: nextList } };
  });
  return item;
}

function writeQueue(sessionId: string, nextList: QueuedMessage[]) {
  set((st) => {
    const nextQueuedMessages = { ...st.queuedMessages, [sessionId]: nextList };
    saveQueuedMessages(nextQueuedMessages);
    return { queuedMessages: nextQueuedMessages };
  });
}

/** 从队列移除一条消息。 */
export function removeQueuedMessage(sessionId: string, messageId: string) {
  const list = s().queuedMessages[sessionId] ?? [];
  if (!list.some((m) => m.id === messageId)) return;
  writeQueue(sessionId, list.filter((m) => m.id !== messageId));
}

/** 弹出一条消息回输入框（编辑用）：从队列移除并返回原文。 */
export function popQueuedMessageToInput(sessionId: string, messageId: string): QueuedMessage | null {
  const list = s().queuedMessages[sessionId] ?? [];
  const found = list.find((m) => m.id === messageId);
  if (!found) return null;
  writeQueue(sessionId, list.filter((m) => m.id !== messageId));
  return found;
}

/** 队列内排序：把 activeId 挪到 overId 的位置（拖拽/上移/下移统一入口）。 */
export function reorderQueuedMessages(sessionId: string, activeId: string, overId: string) {
  const list = s().queuedMessages[sessionId] ?? [];
  const from = list.findIndex((m) => m.id === activeId);
  const to = list.findIndex((m) => m.id === overId);
  if (from < 0 || to < 0 || from === to) return;
  const nextList = [...list];
  const [moved] = nextList.splice(from, 1);
  nextList.splice(to, 0, moved);
  writeQueue(sessionId, nextList);
}

/** 清空某会话的排队队列（会话删除/归档时兜底清理）。 */
export function clearSessionQueue(sessionId: string) {
  if (!s().queuedMessages[sessionId]) return;
  set((st) => {
    const nextQueuedMessages = { ...st.queuedMessages };
    delete nextQueuedMessages[sessionId];
    saveQueuedMessages(nextQueuedMessages);
    return { queuedMessages: nextQueuedMessages };
  });
}

/* ─── 自动出队泵 ─── */

/** 正在泵某个会话队列（并发守卫：每会话同时至多一条在途发送）。 */
const pumping = new Set<string>();

/** 发送失败后的冷却（按会话）：避免持续重试打后端，5 秒后随下一次触发自动重试。 */
const failCooldown = new Map<string, number>();
const FAIL_RETRY_MS = 5000;

function ticketNoOfSession(sessionId: string): string | null {
  const st = s();
  for (const [no, list] of Object.entries(st.sessions ?? {})) {
    if ((list ?? []).some((sess) => sess.id === sessionId)) return no;
  }
  return null;
}

async function sendQueuedItem(item: QueuedMessage): Promise<boolean> {
  const st = s();
  const no = item.ticketNo || ticketNoOfSession(item.sessionId);
  if (!no) return false;
  if (st.mode === "live") {
    const { liveSendToSession } = await import("./stream");
    return liveSendToSession(no, item.sessionId, item.content, item.attachments ?? []);
  }
  const demo = await import("@/demo");
  await demo.demoSendPrompt(
    no,
    item.content,
    (item.attachments ?? [])
      .filter((a) => a.mime.startsWith("image/"))
      .map((a) => a.dataUrl),
  );
  return true;
}

/**
 * 泵一个会话的队列：会话空闲时逐条 FIFO 发送，直到队列清空或再次忙碌/失败。
 * 发送中的条目先出队（避免重复），失败则原样放回队首并停止（等冷却后自动重试）。
 */
async function pumpQueue(sessionId: string) {
  if (!sessionId || pumping.has(sessionId)) return;
  const lastFail = failCooldown.get(sessionId) ?? 0;
  if (Date.now() - lastFail < FAIL_RETRY_MS) return;
  pumping.add(sessionId);
  try {
    for (;;) {
      const st = s();
      if (st.sessionBusy[sessionId]) return;
      const queue = st.queuedMessages[sessionId];
      if (!queue || queue.length === 0) return;
      const next = queue[0];
      // 先出队再发送：消息进入在途，本泵自不会再选中它；其它入口（手动触发）
      // 被 pumping 守卫挡住，杜绝双发。
      writeQueue(sessionId, queue.slice(1));
      let ok = false;
      try {
        ok = await sendQueuedItem(next);
      } catch {
        ok = false;
      }
      if (!ok) {
        // 发送失败：放回队首（保留原排序）、记冷却并停泵。
        const current = s().queuedMessages[sessionId] ?? [];
        writeQueue(sessionId, [next, ...current]);
        failCooldown.set(sessionId, Date.now());
        return;
      }
    }
  } finally {
    pumping.delete(sessionId);
  }
}

/**
 * 手动触发一次队列泵（导入 queue 的入口都能安全调用，幂等）：
 * 会话空闲且有排队消息即开始自动发送。
 */
export function kickQueuePump(sessionId: string) {
  if (!sessionId) return;
  const st = s();
  const queue = st.queuedMessages[sessionId];
  if (!queue || queue.length === 0) return;
  if (st.sessionBusy[sessionId]) return;
  void pumpQueue(sessionId);
}

/** busy→idle / 队列非空触发（每会话幂等）。 */
let watcherAttached = false;
let prevSessionBusy = new Map<string, boolean>();
export function attachQueueWatcher() {
  if (watcherAttached) return;
  watcherAttached = true;
  for (const [sid, busy] of Object.entries(appStore.getState().sessionBusy)) {
    prevSessionBusy.set(sid, busy);
  }
  appStore.subscribe((st) => {
    // 1) 会话从运行→空闲：泵该会话（覆盖所有回合结束路径）。
    for (const [sid, busy] of Object.entries(st.sessionBusy)) {
      const wasBusy = prevSessionBusy.get(sid) ?? false;
      if (wasBusy && !busy && (st.queuedMessages[sid] ?? []).length > 0) {
        void pumpQueue(sid);
      }
    }
    // 2) 会话已从 sessionBusy 表消失（他端/收敛）且此前在跑 → 视为空闲转换。
    for (const sid of prevSessionBusy.keys()) {
      if (prevSessionBusy.get(sid) && st.sessionBusy[sid] === undefined) {
        if ((st.queuedMessages[sid] ?? []).length > 0) void pumpQueue(sid);
      }
    }
    // 3) 队列出现新消息且会话已空闲（刷新恢复/入队瞬间空闲）→ 泵。
    for (const [sid, queue] of Object.entries(st.queuedMessages)) {
      if (queue.length > 0 && !st.sessionBusy[sid]) void pumpQueue(sid);
    }
    // 快照当前 busy 表供下一次转换判定。
    const nextBusy = new Map<string, boolean>();
    for (const [sid, busy] of Object.entries(st.sessionBusy)) {
      nextBusy.set(sid, busy);
    }
    prevSessionBusy = nextBusy;
  });
}

// 模块加载即挂上订阅：任何 busy→idle 都会触发自动发送（本文件被 Composer/state 链最早加载）。
attachQueueWatcher();
