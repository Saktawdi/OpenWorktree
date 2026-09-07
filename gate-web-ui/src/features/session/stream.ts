/**
 * 会话域流管道（session）：发送消息（含草稿建会话三步）、SSE 事件流消费与中止。
 * 这是唯一持有 EventSource 的模块；工具行累积、todo/上下文回写、
 * 权限/提问卡片挂载都在这里驱动。
 */
import { api } from "@/net";
import { appStore, showToast } from "@/store";
import type { PendingAttachment, ToolIconKind } from "@/shared/types";
import { friendlyToolName, isTodoTool, parseTodos, todoArgsSummary, compactToolArgs, compactToolResult } from "@/shared/todoUtils";
import type { TimelinePart, ToolCallView } from "@/shared/types";
import { emitPluginEvent } from "@/app/plugins/events";
import { loadSessionMessages, loadTicketSessions, refreshTicketSessionsMeta, syncSessionTodos } from "./api";
import { loadSessionPermissions, loadSessionQuestions } from "./permissions";
import { loadSessionCatalog, switchSessionModelLive } from "./catalog";
import { mapPermissionAsk, mapQuestionAsk } from "./model";
import {
  addUsage,
  clearDraftModelSel,
  clearSessionEnded,
  markSessionEnded,
  notePendingPermission,
  notePendingQuestion,
  refreshTicketBusy,
  setBusy,
  setContextTokens,
  setCreatingSession,
  setSessionBusy,
  setTodos,
} from "./state";
import {
  attachUserImages,
  finishLiveTurn,
  pushPermissionRequest,
  pushQuestionRequest,
  pushSystemMessage,
  pushUserMessage,
  removeChatItem,
  resolvePermission,
  resolveQuestion,
  startLiveTurn,
  updateLiveTurn,
} from "./chat";
import { loadTicketDiff, refreshTicket } from "@/features/ticket/api";
import { loadPresubmits } from "@/features/gate/api";

/* ─── 中止 ─── */

/** 正在执行中止的会话：sse 的 done 到达时按"已中止"而非"已完成"提醒。 */
const abortingSessions = new Set<string>();

export async function abortLive(no: string) {
  const sid = appStore.getState().activeSessionId[no];
  if (!sid) return;
  // 乐观翻转按钮：后端的 done 事件可能迟到，用户的点击必须立刻可见。
  setSessionBusy(sid, false);
  refreshTicketBusy(no);
  abortingSessions.add(sid);
  try {
    await api(`/api/sessions/${sid}/abort`, { method: "POST" });
  } catch (e) {
    abortingSessions.delete(sid);
    showToast(`中断失败：${(e as Error).message}`);
  }
  await loadTicketSessions(no).catch(() => {});
}

/* ─── 发送（含草稿建会话三步） ─── */

export async function liveSendPrompt(
  no: string,
  userText: string,
  attachments: PendingAttachment[] = [],
): Promise<boolean> {
  const st = appStore.getState();
  // 目标永远是「当前查看的会话」（activeSessionId），不再有跨工单/跨会话的全局游标；
  // 同一会话生成中不允许并发追加，其他会话不受影响。
  // userText 已含 [图片 #n] 引用（Composer 粘贴时插入），原样推送与发送。
  const sessionId = st.activeSessionId[no];
  if (sessionId && st.sessionBusy[sessionId]) return true;
  // 工单重新进入运行状态：上一次的"会话已结束"提醒随之失效
  clearSessionEnded(no);
  const userItem = pushUserMessage(
    no,
    userText,
    attachments.filter((a) => a.mime.startsWith("image/")).map((a) => a.dataUrl),
  );
  setBusy(no, true);
  let sid: string | null = sessionId || null;
  // 草稿首条消息：建会话（空首句，仅启动 serve）→ 写入草稿的模型/推理覆盖 →
  // 再发消息（首回合即用所选模型）。期间右侧面板显示「正在创建会话…」遮罩。
  setCreatingSession(no, !sid);
  let draftFailed = false;
  try {
    if (!sid) {
      const agentId = st.agents.some((a) => a.id === st.agentId) ? st.agentId : (st.agents[0]?.id ?? "");
      const created = await api<{ id: string }>(`/api/tickets/${no}/sessions`, {
        method: "POST",
        body: JSON.stringify({ agent_config_id: agentId, initial_prompt: "" }),
      });
      sid = created.id;
      // 会话创建语义写入点（live 路径）：后端建会话成功即 emit。
      emitPluginEvent("session.created", { ticketNo: no, sessionId: sid, agentConfigId: agentId || null });
      await loadTicketSessions(no).catch(() => {});
      appStore.setState((s2) => ({ activeSessionId: { ...s2.activeSessionId, [no]: sid! } }));
      // 草稿里选过的模型/推理强度：建会话后立即持久化为覆盖（首回合即生效）。
      const draftSel = appStore.getState().draftModelSel[no];
      if (draftSel?.providerId && draftSel.modelId) {
        await switchSessionModelLive(sid, draftSel);
      }
      clearDraftModelSel(no);
      // 真目录按会话加载，草稿目录（opencode 配置）完成使命。
      void loadSessionCatalog(no, sid);
    }
    const sel = appStore.getState().sessionModelSel[sid];
    const sent = await api<{ task_id: string; images?: string[] }>(`/api/sessions/${sid}/messages`, {
      method: "POST",
      body: JSON.stringify({
        message: userText,
        attachments: attachments.map((a) => ({
          filename: a.filename,
          mime: a.mime,
          data_base64: a.dataBase64,
        })),
        provider_id: sel?.providerId ?? undefined,
        model_id: sel?.modelId ?? undefined,
        variant: sel?.variant ?? undefined,
      }),
    });
    // 后端已把缩略图落盘到克隆 .gate/chat-images/：用可持久化的工作区路径
    // 替换乐观 data URL，此后历史重载按同一引用行解析出一致的数据源。
    if (sent.images?.length) attachUserImages(no, userItem.id, sent.images);
    // 遮罩只覆盖「建会话→写覆盖→发消息」三步：后端受理消息即返回，回合从此开始
    // 流式输出，必须现在就撤；finally 要等整个回合结束才执行，只留给异常路径兜底。
    setCreatingSession(no, false);
    setSessionBusy(sid, true);
    await consumeSessionStream(no, sid);
  } catch (e) {
    pushSystemMessage(no, `会话失败：${(e as Error).message}`, "warn");
    if (!sid) {
      // 连会话都没建成（如端口占用）：回滚用户消息恢复草稿——选择全保留，
      // 输入框原文由 Composer 还原，用户直接重发即可，不打「已中断」标记。
      removeChatItem(no, userItem.id);
      draftFailed = true;
    } else {
      markSessionEnded(no, "failed", sid);
    }
  } finally {
    setCreatingSession(no, false);
    if (sid) setSessionBusy(sid, false);
    refreshTicketBusy(no);
  }
  return !draftFailed;
}

/* ─── SSE 流消费 ─── */

// 编辑类工具在一个回合里往往连续完成多次；逐次全量拉 diff 既慢也毫无增益，合并成一次。
const diffRefreshTimers: Record<string, ReturnType<typeof setTimeout>> = {};
function scheduleDiffRefresh(no: string) {
  if (diffRefreshTimers[no]) clearTimeout(diffRefreshTimers[no]);
  diffRefreshTimers[no] = setTimeout(() => {
    delete diffRefreshTimers[no];
    void loadTicketDiff(no);
  }, 800);
}

const FILE_EDIT_TOOLS = ["edit", "write", "patch", "multiedit"];

/* ─── live 回合时间线（parts）维护 ───
 * parts 是 chronology 事实来源：thinking/text/tool 按到达序交错；
 * tools 平铺数组保留为兼容视图（旧渲染路径与 todo 侧栏依赖它）。
 */

function lastThinkingIndex(parts: TimelinePart[] | undefined): number {
  if (!parts || parts.length === 0) return -1;
  const last = parts[parts.length - 1];
  return last.type === "thinking" ? parts.length - 1 : -1;
}

/** thinking_delta 到达：延续最后一段未封口思考（末位恰为 thinking），否则开新段。 */
function upsertThinkingPart(
  parts: TimelinePart[] | undefined,
  prevThinkingDone: boolean,
  delta: string,
  now: number,
): TimelinePart[] {
  const list = parts ?? [];
  const idx = prevThinkingDone ? -1 : lastThinkingIndex(list);
  if (idx >= 0) {
    const seg = list[idx] as Extract<TimelinePart, { type: "thinking" }>;
    const next = list.slice();
    next[idx] = {
      type: "thinking",
      text: seg.text + delta,
      startedAt: seg.startedAt ?? now,
      endedAt: undefined,
    };
    return next;
  }
  return [...list, { type: "thinking", text: delta, startedAt: now, endedAt: undefined }];
}

/** 正文 token 到来：封口末段未封口思考（记录 endedAt）；无未封口段时原样返回。 */
function sealThinkingPart(parts: TimelinePart[] | undefined, now: number): TimelinePart[] | undefined {
  if (!parts || parts.length === 0) return parts;
  const idx = lastThinkingIndex(parts);
  if (idx < 0) return parts;
  const seg = parts[idx] as Extract<TimelinePart, { type: "thinking" }>;
  if (seg.endedAt) return parts;
  const next = parts.slice();
  next[idx] = { ...seg, endedAt: now };
  return next;
}

/** 工具 upsert：按 callID 原位更新（参数快照/终态输出），不存在则按到达序追加。 */
function upsertToolPart(
  parts: TimelinePart[] | undefined,
  callId: string,
  toolName: string,
  args: string,
  resultText: string,
  status: ToolCallView["status"],
  view: ToolCallView,
): TimelinePart[] {
  const list = parts ?? [];
  const idx = list.findIndex((p) => p.type === "tool" && p.id === callId);
  const part: Extract<TimelinePart, { type: "tool" }> = {
    type: "tool",
    id: callId,
    name: toolName,
    arguments_json: args,
    result_json: resultText || null,
    status,
    view,
  };
  if (idx >= 0) {
    const next = list.slice();
    next[idx] = part;
    return next;
  }
  return [...list, part];
}

/** 回合收尾：全部工具段定格 ok（终态 FAILED 保留 error），thinking 已在 token/done 封口。 */
function finalizeParts(parts: TimelinePart[] | undefined): TimelinePart[] | undefined {
  if (!parts || parts.length === 0) return parts;
  return parts.map((p) =>
    p.type === "tool"
      ? { ...p, status: p.status === "error" ? ("error" as const) : ("ok" as const) }
      : p,
  );
}

function resolveToolIcon(name: string): ToolIconKind {
  const n = name.toLowerCase().trim();
  if (n.includes("bash") || n.includes("exec") || n.includes("shell") || n.includes("terminal") || n.includes("cmd")) {
    return "terminal";
  }
  if (n.includes("read") || n.includes("view") || n.includes("cat") || n.includes("get_file") || n.includes("load")) {
    return "file";
  }
  if (n.includes("edit") || n.includes("write") || n.includes("patch") || n.includes("multiedit") || n.includes("create") || n.includes("modify")) {
    return "edit";
  }
  if (n.includes("grep") || n.includes("glob") || n.includes("search") || n.includes("find") || n.includes("locate")) {
    return "search";
  }
  if (n.includes("test")) {
    return "test";
  }
  if (n.includes("web") || n.includes("fetch") || n.includes("http") || n.includes("browser")) {
    return "web";
  }
  if (n.includes("ask") || n.includes("question") || n.includes("prompt")) {
    return "question";
  }
  return "terminal";
}

async function consumeSessionStream(no: string, sessionId: string) {
  startLiveTurn(no, sessionId);
  // Per-call argument accumulation: argument_delta fragments concatenate into the
  // full arguments JSON, which todo tools parse into the sidebar task list.
  const argsBuf = new Map<string, { name: string; args: string }>();

  // 断流重连循环（此前一次 error 即关流弃疗，agent 回合一跑数十分钟，网络一抖
  // 实时视图与授权卡片全丢）。网络级断流先查运行集：会话仍在跑 → 补拉 pending
  // （断流窗口内到达的授权/提问不丢）后重开事件流；已空闲 → 按正常完成收场。
  // 连续 5 次重连收不到任何事件才放弃（后端进程不可达的兜底，防无限循环）。
  let networkFailures = 0;
  // 本回合事件流是否异常断过（含最终走重连/收场的所有 network 出口）：断过的回合
  // 浏览器侧必然缺事件——上游 reader 与浏览器 SSE 是两条独立链路，后端多半已收到
  // 完整回合并落库，收场后必须用落库历史重建视图，否则残缺的流式占位被定格
  // （用户实测：回复中途突然中断、中止按钮变发送按钮，opencode 侧实际有完整回复）。
  let streamInterrupted = false;
  try {
    for (;;) {
      const r = await consumeSessionEvents(no, sessionId, argsBuf);
      if (r.outcome !== "network") break;
      streamInterrupted = true;
      if (r.sawEvents) networkFailures = 0;
      networkFailures++;
      const stillRunning = await isSessionBusy(sessionId);
      if (!stillRunning) {
        // 回合已在服务端收尾并落库：不打"已中断"标记。先终结占位条目再用落库历史
        // 重建视图——断流窗口内的正文/工具事件浏览器永远收不到，重建是唯一补全路径
        // （finishLiveTurn 移出 liveTurns 后，loadSessionMessages 才能按纯历史回放）。
        markSessionEnded(no, abortingSessions.has(sessionId) ? "failed" : "done", sessionId);
        finishLiveTurn(sessionId);
        await loadSessionMessages(no, sessionId);
        void syncSessionTodos(no, sessionId);
        void loadTicketDiff(no);
        void refreshTicket(no);
        break;
      }
      if (networkFailures >= 5) {
        markSessionEnded(no, "failed", sessionId);
        updateLiveTurn(sessionId, (a) => ({ ...a, streaming: false }));
        pushSystemMessage(
          no,
          "会话连接中断（多次重连失败）。回合仍在服务端运行，重新打开工单可恢复视图与交互。",
          "warn",
        );
        break;
      }
      await sleep(2000);
      void loadSessionPermissions(no, sessionId);
      void loadSessionQuestions(no, sessionId);
    }
    // 正常 done/terminal 之外，凡断流过的回合（哪怕重连后接完）都不 trusts 占位条目：
    // 断流窗口内丢失的正文/工具段必须以落库历史为准重建，占位补不出没收到的事件。
    // 正常未断流的回合不动——finishLiveTurn 已把流式条目定格进 chats，重建反而抖动。
    if (streamInterrupted) {
      finishLiveTurn(sessionId);
      await loadSessionMessages(no, sessionId).catch(() => {});
    }
  } finally {
    finishLiveTurn(sessionId);
  }
}

function sleep(ms: number): Promise<void> {
  return new Promise((r) => setTimeout(r, ms));
}

/** 查运行集：会话是否仍在后端执行（断流重连的决策依据；查询失败按仍在跑处理）。 */
async function isSessionBusy(sessionId: string): Promise<boolean> {
  try {
    const d = await api<{ running: Array<{ session_id: string }> }>("/api/agents/busy");
    return (d.running ?? []).some((x) => x.session_id === sessionId);
  } catch {
    return true;
  }
}

/** 单次事件流连接：结局为 done（回合完成帧）/ terminal（后端错误帧）/ network（连接级断流）。 */
async function consumeSessionEvents(
  no: string,
  sessionId: string,
  argsBuf: Map<string, { name: string; args: string }>,
): Promise<{ outcome: "done" | "terminal" | "network"; sawEvents: boolean }> {
  const token = appStore.getState().token;
  const url = `/api/sessions/${sessionId}/events${token ? `?token=${encodeURIComponent(token)}` : ""}`;
  const es = new EventSource(url);
  let sawEvents = false;

  const outcome = await new Promise<"done" | "terminal" | "network">((resolve) => {
    // 看门狗只在 30 分钟无任何事件时判流悬挂（原固定 5 分钟截断会误杀长工具回合）。
    let watchdog: ReturnType<typeof setTimeout> | null = null;
    const settle = (reason: "done" | "terminal" | "network") => {
      if (watchdog) clearTimeout(watchdog);
      if (reason !== "network") abortingSessions.delete(sessionId);
      es.close();
      resolve(reason);
    };
    const arm = () => {
      sawEvents = true;
      if (watchdog) clearTimeout(watchdog);
      watchdog = setTimeout(() => settle("network"), 1_800_000);
    };
    arm();
    es.addEventListener("message", (ev) => {
      // History replay (event: message) must not touch the live placeholder: the chat is
      // already rendered from GET /messages when the session opens, and replaying past
      // assistant messages here would overwrite the streaming reply with the previous one.
      void ev;
    });
    es.addEventListener("token", (ev) => {
      arm();
      const d = JSON.parse((ev as MessageEvent).data);
      const now = Date.now();
      updateLiveTurn(sessionId, (a) => ({
        ...a,
        text: a.text + (d.text_delta ?? ""),
        thinking: a.thinking && !a.thinking.done ? { ...a.thinking, done: true } : a.thinking,
        // 思考段封口：正文 token 到来即该段思考结束（历史回放据此显示"持续 N 秒"）
        parts: sealThinkingPart(a.parts, now),
      }));
    });
    es.addEventListener("thinking", (ev) => {
      arm();
      const d = JSON.parse((ev as MessageEvent).data);
      const now = Date.now();
      updateLiveTurn(sessionId, (a) => ({
        ...a,
        thinking: {
          text: (a.thinking?.text ?? "") + (d.thinking_delta ?? ""),
          startedAt: a.thinking?.startedAt ?? now,
          done: false,
        },
        // 时间线：当前思考段（可能已是本回合第 N 段）原位累积；段结束（token 到来）时
        // 由 token 处理器封口 endedAt。
        parts: upsertThinkingPart(a.parts, a.thinking?.done === true, d.thinking_delta ?? "", now),
      }));
    });
    es.addEventListener("tool_call", (ev) => {
      arm();
      const d = JSON.parse((ev as MessageEvent).data);
      // 编辑类工具落盘成功 → 防抖刷新该工单的变更对比，兑现"每次编辑实时反映"的文案；
      // bash 等其它工具可能改文件但太噪，回合结束的 done 刷新兜底。
      if (d.status === "SUCCESS" && FILE_EDIT_TOOLS.includes(String(d.tool_name ?? "").toLowerCase())) {
        scheduleDiffRefresh(no);
      }
      // agent 经 MCP 预提审成功 → 工单阶段已在服务端推进到 PRESUBMITTED，立即刷新工单与快照。
      // 不刷新的话面板仍按旧阶段渲染"同步基座"按钮，点击才撞上阶段守卫报 400（T-109 实测）。
      // opencode 给 MCP 工具加服务器前缀（gate_presubmit_create），按惯例用 includes 匹配。
      if (d.status === "SUCCESS" && String(d.tool_name ?? "").toLowerCase().includes("presubmit_create")) {
        void refreshTicket(no);
        void loadPresubmits(no);
      }
      const callId: string = d.call_id ?? "";
      const toolName: string = d.tool_name ?? "";
      const todo = isTodoTool(toolName);
      const entry = argsBuf.get(callId) ?? { name: toolName, args: "" };
      entry.name = toolName || entry.name;
      if (d.argument_delta) {
        // opencode 的 part 更新每次都重发完整 input JSON（字段名叫 delta，实际是快照），
        // Claude headless 才是真分片——能解析成完整 JSON 的按快照替换，残片才累积拼接。
        let snapshot = false;
        try {
          const parsed = JSON.parse(d.argument_delta);
          snapshot = parsed !== null && typeof parsed === "object";
        } catch {
          /* 解析失败即残片，走累积 */
        }
        entry.args = snapshot ? d.argument_delta : entry.args + d.argument_delta;
      }
      argsBuf.set(callId, entry);
      // 工具输出随终态事件携带（opencode 侧 RUNNING 时 result 为 null）。此前被丢弃，
      // 流式回合的工具行没有 resultDetail，最新一轮无法展开查看，只能等重拉历史。
      const resultText = typeof d.result === "string" && d.result ? d.result : "";
      // todo 类工具在参数可解析为完整清单时即时回写侧栏任务清单，不再等终态——
      // opencode 每次 part 更新都重发完整 input 快照，首次广播即可让任务环出现；
      // Claude 残片累积未成形时解析失败自然跳过，终态快照到齐后同样即时生效。
      // （历史遗留的终态门控会让任务清单在事件缺失/断流时拖到手动刷新才更新。）
      if (todo) {
        const todos = parseTodos(entry.args);
        if (todos) setTodos(no, todos);
      }
      const argsSummary = todo
        ? todoArgsSummary(entry.args)
        : compactToolArgs(entry.name, entry.args);
      const liveStatus: ToolCallView["status"] =
        d.status === "SUCCESS" ? "ok" : d.status === "FAILED" ? "error" : "running";
      updateLiveTurn(sessionId, (a) => {
        const existing = a.tools.find((t) => t.id === d.call_id);
        let nextTools;
        let view: ToolCallView;
        if (existing) {
          view = {
            ...existing,
            name: friendlyToolName(entry.name),
            icon: todo ? ("todo" as const) : existing.icon,
            args: entry.args,
            argsSummary,
            resultSummary: compactToolResult(resultText) ?? existing.resultSummary,
            resultDetail: resultText || existing.resultDetail,
            status: liveStatus,
          };
          nextTools = a.tools.map((t) => (t.id === d.call_id ? view : t));
        } else {
          view = {
            id: d.call_id,
            name: friendlyToolName(toolName),
            toolName,
            args: entry.args,
            icon: todo ? ("todo" as const) : resolveToolIcon(toolName),
            argsSummary,
            resultSummary: compactToolResult(resultText),
            resultDetail: resultText || undefined,
            status: liveStatus,
          };
          nextTools = [...a.tools, view];
        }
        return {
          ...a,
          tools: nextTools,
          // 时间线与兼容视图共享同一 view 对象（渲染缓存 + 原位状态更新）
          parts: upsertToolPart(a.parts, d.call_id, toolName, entry.args, resultText, liveStatus, view),
        };
      });
    });
    es.addEventListener("usage", (ev) => {
      arm();
      const d = JSON.parse((ev as MessageEvent).data);
      if (d.usage) {
        addUsage(no, d.usage.prompt_tokens ?? 0, d.usage.completion_tokens ?? 0);
        // 最新一轮的窗口占用（prompt+completion），非逐轮累加 —— 上下文环数据源。
        const total =
          typeof d.usage.total_tokens === "number" && d.usage.total_tokens > 0
            ? d.usage.total_tokens
            : (d.usage.prompt_tokens ?? 0) + (d.usage.completion_tokens ?? 0);
        if (total > 0) setContextTokens(no, total);
      }
    });
    es.addEventListener("permission_asked", (ev) => {
      arm();
      const d = JSON.parse((ev as MessageEvent).data);
      // 待决登记与视图无关：无论当前查看哪个会话，工单列表的"待授权"徽标都要亮起；
      // 卡片仍只挂当前查看的会话视图（避免误挂 + 应答发错 session），切回时
      // loadSessionPermissions 会重新拉取 pending 卡片。
      if (d.permission_id) notePendingPermission(d.permission_id, no, sessionId);
      if (appStore.getState().activeSessionId[no] === sessionId) {
        pushPermissionRequest(no, mapPermissionAsk(d));
      }
    });
    es.addEventListener("permission_replied", (ev) => {
      arm();
      const d = JSON.parse((ev as MessageEvent).data);
      resolvePermission(no, d.permission_id, d.response ?? "once", !!d.auto);
    });
    es.addEventListener("question_asked", (ev) => {
      arm();
      const d = JSON.parse((ev as MessageEvent).data);
      // 待决登记与视图无关（工单列表"待回答"徽标的数据源）；卡片挂载策略与权限相同。
      if (d.request_id) notePendingQuestion(d.request_id, no, sessionId);
      if (d.request_id && appStore.getState().activeSessionId[no] === sessionId) {
        pushQuestionRequest(no, mapQuestionAsk(d));
      }
    });
    es.addEventListener("question_replied", (ev) => {
      arm();
      const d = JSON.parse((ev as MessageEvent).data);
      if (d.request_id) resolveQuestion(no, d.request_id, !!d.rejected);
    });
    es.addEventListener("session_title", (ev) => {
      // 后端把 opencode 自动生成的标题上抛（HTTP Server: SessionSseHandler）。
      // 顺手同步当前 store 里的会话条目；回合结束再拉一次可确保一致。
      const d = JSON.parse((ev as MessageEvent).data);
      const sid = String(d.session_id ?? "");
      const title = String(d.title ?? "");
      if (!sid || !title) return;
      appStore.setState((st) => ({
        sessions: {
          ...st.sessions,
          [no]: (st.sessions[no] ?? []).map((x) => (x.id === sid ? { ...x, title } : x)),
        },
      }));
    });
    es.addEventListener("done", () => {
      // T-120 增强：回合结束提醒（用户中止的会话按"已中断"呈现）。
      markSessionEnded(no, abortingSessions.has(sessionId) ? "failed" : "done", sessionId);
      // 时间线定格：工具段终态化（最后一拍 RUNNING→ok），思考段封口。
      updateLiveTurn(sessionId, (a) => ({
        ...a,
        parts: finalizeParts(sealThinkingPart(a.parts, Date.now())),
      }));
      // 后端此刻已把 opencode 的自动生成标题写库（session.updated → sessions.update）。
      // 只刷新列表数据，不动 activeSessionId，避免把用户在查看的会话顶走。
      void refreshTicketSessionsMeta(no);
      // 回合结束：刷新变更对比的最终状态（本回合内 bash 等未跟踪的文件改动也一并覆盖）。
      void loadTicketDiff(no);
      // 兜底同步工单阶段与快照：回合内服务端可能已推进阶段（agent 的 presubmit_create），
      // tool_call 监听万一漏掉，这里保证面板按钮与真实阶段一致，而不是点了才报 400。
      void refreshTicket(no);
      void loadPresubmits(no);
      // 回合结束任务清单收敛：后端此刻已整回合落库（flushTurn → done），以历史回算
      // 一次——流式事件若有遗漏/断流，侧栏任务环仍与持久化数据最终一致。
      void syncSessionTodos(no, sessionId);
      settle("done");
    });
    es.addEventListener("error", (ev) => {
      // 事件级 error（后端显式错误帧，带 JSON body）= 回合失败，终局；
      // 连接级断流（EventSource 自动重连失败也走 error，无 data）= network，交由
      // 外层循环查运行集后重连——不再一次断流就弃疗。
      const data = (ev as MessageEvent).data;
      if (!data) {
        settle("network");
        return;
      }
      let msg = "会话连接中断";
      try {
        const d = JSON.parse(data);
        if (d.error_message) {
          let detail = String(d.error_message);
          try {
            const inner = JSON.parse(detail);
            if (inner?.data?.message) detail = inner.data.message;
            else if (inner?.message) detail = inner.message;
          } catch {
            /* 非结构化错误体，原样展示 */
          }
          msg = `Agent 出错：${detail}`;
        } else if (d.error_code) {
          msg = `Agent 出错：${d.error_code}`;
        }
      } catch {
        /* ignore */
      }
      // T-120 增强：意外失败中止也属于"会话结束"，工单列表按"已中断"提醒。
      markSessionEnded(no, "failed", sessionId);
      updateLiveTurn(sessionId, (a) => ({ ...a, streaming: false }));
      pushSystemMessage(no, msg, "warn");
      // 断流≠回合结束：agent 可能仍在服务端运行，立即按（尚未落库的）空历史重建
      // 只会清掉已显示的清单，故不即时收敛。延迟一拍主动核对一次运行集：
      // - 会话已空闲 → 回合确已终结且已落库，此时收敛一次（覆盖极短回合从未进入
      //   轮询快照、以及轮询随页面隐藏暂停的窗口）；
      // - 仍在运行 → 不碰，等 busy 轮询的「运行→空闲」transition 兜底收敛。
      setTimeout(() => {
        void isSessionBusy(sessionId).then((stillRunning) => {
          if (!stillRunning) void syncSessionTodos(no, sessionId);
        });
      }, 2000);
      settle("terminal");
    });
  });
  return { outcome, sawEvents };
}
