/**
 * 应用动作 facade（app actions）：UI 层统一入口，按当前模式（live/demo）
 * 分发到对应 feature 包或演示引擎。跨域编排（连接/启动）见 boot.ts。
 */
import * as demo from "@/demo/engine";
import { appStore, showToast, wipePersisted } from "@/store";
import { seedDemo } from "@/demo/seed";
import {
  createTicketLive,
  loadTickets,
  updateTicketLive,
  restartTicketLive,
  completeTicketLive,
  cancelTicketLive,
} from "@/features/ticket/api";
import { selectTicketLive } from "@/features/ticket/flows";
import {
  appendStageChange,
  createTicket,
  updateTicket,
  selectTicket,
  setStage,
} from "@/features/ticket/state";
import { loadEngineConfig, livePresubmit, livePublish, liveReview, liveSyncBase, markReviewEnded } from "@/features/gate";
import { setVerdict } from "@/features/gate/state";
import {
  loadSessionCatalog,
  loadSessionMessages,
  loadSessionPermissions,
  loadSessionQuestions,
  patchSessionLive,
  deleteSessionLive,
  switchSessionModelLive,
  abortLive,
  liveSendPrompt,
  clearSessionQueue,
} from "@/features/session";
import {
  archiveSession as archiveSessionLocal,
  clearDraftModelSel,
  clearSessionInterrupted,
  createSessionGroup as createSessionGroupLocal,
  deleteSession as deleteSessionLocal,
  deleteSessionGroup as deleteSessionGroupLocal,
  dismissSessionAsks,
  moveSessionToGroup as moveSessionToGroupLocal,
  renameSession as renameSessionLocal,
  restoreSession as restoreSessionLocal,
  setDraftModelSel,
  setSessionPinned as setSessionPinnedLocal,
  setSessionPinnedOrder as setSessionPinnedOrderLocal,
  startSessionDraft as startSessionDraftLocal,
  switchSession as switchSessionLocal,
  updateSessionGroup as updateSessionGroupLocal,
} from "@/features/session/state";
import { pushSystemMessage } from "@/features/session/chat";
import { demoAbort } from "@/demo/engine";
import {
  loadProjects,
  createProjectLive,
  updateProjectLive,
  deleteProjectLive,
  setProjectStarredLive,
  reorderProjectsLive,
  browseWorkspace,
  createWorkspaceDir,
} from "@/features/project";
import {
  removeProject,
  upsertProject,
} from "@/features/project/state";
import {
  loadAgentConfigs,
  loadRuntimes,
  upsertAgentConfigLive,
  deleteAgentConfigLive,
  loadOcProviders,
  upsertOcProviderLive,
  deleteOcProviderLive,
  startAgentBusyPolling,
  stopAgentBusyPolling,
} from "@/features/agent";
import {
  removeAgentConfig,
  removeOcProvider,
  upsertAgentConfig,
  upsertOcProvider,
} from "@/features/agent/state";
import { verifyToken } from "@/net";
import type { AgentConfig, OpenCodeProvider, PendingAttachment, Project, Ticket } from "@/shared/types";

export const actions = {
  sendPrompt(no: string, text: string, attachments: PendingAttachment[] = [], delivery?: "steer") {
    // text 已含发送时统一追加的 [图片 #n] 引用行（引用只占位 chip，不进输入框正文）；
    // live 走 attachments 数组，demo 无后端，图片以 data URL 直接进气泡（不入克隆工作区）。
    if (appStore.getState().mode === "live") return liveSendPrompt(no, text, attachments, delivery);
    return demo.demoSendPrompt(
      no,
      text,
      attachments.filter((a) => a.mime.startsWith("image/")).map((a) => a.dataUrl),
    );
  },
  returnWithFindings(no: string) {
    const st = appStore.getState();
    const findings = st.findings[no] ?? [];
    if (findings.length === 0) return;
    if (st.mode === "live") {
      const text = ["请按以下审查意见逐条修复：", demo.findingsToPromptText(findings)].join("\n");
      return liveSendPrompt(no, text);
    }
    return demo.demoReturnWithFindings(no);
  },
  presubmit(no: string) {
    return appStore.getState().mode === "live" ? livePresubmit(no) : demo.demoPresubmit(no);
  },
  /** AI 审查：live 模式要求已配置审查引擎（gate.toml [engine]）。 */
  reviewAi(no: string) {
    return appStore.getState().mode === "live" ? liveReview(no) : demo.demoReview(no);
  },
  /** 人工审查：弹窗确认"已审阅"并填写必填理由后，以人工判决落盘（human_pass=true）。 */
  reviewHuman(no: string, note?: string) {
    if (appStore.getState().mode === "live") {
      return liveReview(no, { humanPass: true, note: note?.trim() || "人工审查通过（未填写理由）" });
    }
    setVerdict(no, {
      verdict: "PASS",
      reason: note?.trim() || "人工审查通过（确认已审阅）",
      engineId: "human/override",
      round: appStore.getState().snapshots[no]?.length ?? 1,
      authorizationId: "manual-" + Date.now().toString(36),
    });
    setStage(no, "READY_TO_PUBLISH");
    pushSystemMessage(no, "人工审查通过 · 发布授权已签发", "success");
    markReviewEnded(no, "PASS");
    return Promise.resolve();
  },
  review(no: string) {
    return actions.reviewAi(no);
  },
  publish(no: string) {
    return appStore.getState().mode === "live" ? livePublish(no) : demo.demoPublish(no);
  },
  newTicket(
    title: string,
    priority: "P0" | "P1" | "P2" | "P3",
    extra?: {
      description?: string;
      labels?: string[];
      targetBranch?: string;
    },
  ): string | null {
    if (appStore.getState().mode === "live") {
      void (async () => {
        const st0 = appStore.getState();
        const projectId =
          st0.activeProjectId && st0.projects.some((p) => p.id === st0.activeProjectId)
            ? st0.activeProjectId
            : undefined;
        const no = await createTicketLive({
          title,
          priority,
          stage: "PENDING",
          project_id: projectId,
          description: extra?.description,
          labels: extra?.labels,
          target_branch: extra?.targetBranch,
        });
        if (!no) return;
        await loadTickets().catch(() => {});
        await selectTicketLive(no);
      })();
      return null;
    }
    const no = createTicket(title, priority);
    const p = extra ?? {};
    if (p.description || p.labels?.length) {
      updateTicket(no, {
        description: p.description,
        labels: p.labels,
      });
    }
    return no;
  },
  editTicket(
    no: string,
    patch: {
      title?: string;
      description?: string;
      note?: string;
      labels?: string[];
      priority?: Ticket["priority"];
    },
  ) {
    if (appStore.getState().mode === "live") {
      return updateTicketLive(no, { ...patch });
    }
    updateTicket(no, patch);
    return Promise.resolve(true);
  },
  /** 取消工单（V19）：任意非终态可取消，理由必填并记入状态变更历史。 */
  cancelTicket(no: string, reason: string, from?: Ticket["stage"]) {
    if (appStore.getState().mode === "live") {
      return cancelTicketLive(no, reason);
    }
    const t = appStore.getState().tickets.find((x) => x.ticketNo === no);
    setStage(no, "CANCELLED");
    appendStageChange(no, from ?? t?.stage ?? "PENDING", "CANCELLED", reason);
    pushSystemMessage(no, `工单已取消 · 理由：${reason}`, "warn");
    return Promise.resolve(true);
  },
  /** 强制已完成（V19）：任意非终态可强制收尾，理由必填并记入状态变更历史。 */
  completeTicket(no: string, reason: string, from?: Ticket["stage"]) {
    if (appStore.getState().mode === "live") {
      return completeTicketLive(no, reason);
    }
    const t = appStore.getState().tickets.find((x) => x.ticketNo === no);
    setStage(no, "DONE");
    appendStageChange(no, from ?? t?.stage ?? "PENDING", "DONE", reason);
    pushSystemMessage(no, `工单已强制完成 · 理由：${reason}`, "success");
    return Promise.resolve(true);
  },
  createProject(body: {
    name: string;
    workspacePath: string;
    targetBranch?: string;
    initGit: boolean;
    priority: string | null;
    size: string | null;
    tags: string[];
  }) {
    if (appStore.getState().mode === "live") {
      return createProjectLive({
        name: body.name,
        workspace_path: body.workspacePath,
        target_branch: body.targetBranch,
        init_git: body.initGit,
        priority: body.priority,
        size: body.size,
        tags: body.tags,
      });
    }
    const id = body.name
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, "-")
      .replace(/(^-+|-+$)/g, "") || "project";
    const st = appStore.getState();
    const finalId = st.projects.some((p) => p.id === id) ? `${id}-${st.projects.length + 1}` : id;
    upsertProject({
      id: finalId,
      name: body.name,
      workspacePath: body.workspacePath,
      targetRef: "refs/heads/main",
      authRepo: `D:/repo/auth/${finalId}.git`,
      priority: (body.priority as Project["priority"]) ?? null,
      size: body.size as Project["size"],
      tags: body.tags,
      starred: false,
      sortOrder: Math.max(0, ...st.projects.map((p) => p.sortOrder ?? 0)) + 1,
      ticketCount: 0,
      activeTicketCount: 0,
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    });
    showToast(`项目「${body.name}」已接入`);
    return Promise.resolve(true);
  },
  editProject(
    id: string,
    patch: {
      name?: string;
      targetBranch?: string;
      priority?: Project["priority"] | null;
      size?: Project["size"] | null;
      tags?: string[];
    },
  ) {
    if (appStore.getState().mode === "live") {
      return updateProjectLive(id, {
        name: patch.name,
        priority: patch.priority,
        size: patch.size,
        tags: patch.tags,
        target_branch: patch.targetBranch,
      });
    }
    const st = appStore.getState();
    const p = st.projects.find((x) => x.id === id);
    if (!p) return Promise.resolve(false);
    const { targetBranch: branch, ...rest } = patch;
    upsertProject({
      ...p,
      ...rest,
      ...(branch !== undefined && branch.trim()
        ? { targetRef: `refs/heads/${branch.trim()}` }
        : {}),
      updatedAt: new Date().toISOString(),
    });
    return Promise.resolve(true);
  },
  deleteProject(id: string) {
    if (appStore.getState().mode === "live") {
      return deleteProjectLive(id);
    }
    removeProject(id);
    showToast("项目已移除（工单保留为未分配）");
    return Promise.resolve(true);
  },
  /** 星标开关：置顶展示，不参与「更新于」时间刷新。 */
  setProjectStarred(id: string, starred: boolean) {
    if (appStore.getState().mode === "live") {
      return setProjectStarredLive(id, starred);
    }
    const st = appStore.getState();
    const p = st.projects.find((x) => x.id === id);
    if (!p) return Promise.resolve(false);
    upsertProject({ ...p, starred });
    showToast(starred ? `已置顶「${p.name}」` : `已取消「${p.name}」置顶`);
    return Promise.resolve(true);
  },
  /** 拖拽排序结果落库（live 后端 + demo 本地 store）。 */
  reorderProjects(orderedIds: string[]) {
    if (appStore.getState().mode === "live") {
      return reorderProjectsLive(orderedIds);
    }
    const st = appStore.getState();
    const order = new Map(orderedIds.map((id, i) => [id, i + 1]));
    for (const p of st.projects) {
      const next = order.get(p.id);
      if (next !== undefined) upsertProject({ ...p, sortOrder: next });
    }
    return Promise.resolve(true);
  },
  /** 目录浏览只走 live 后端；demo 模式下无本地文件系统可调。 */
  browseWorkspace(path: string) {
    if (appStore.getState().mode !== "live") {
      showToast("目录浏览需要连接本地后端（live 模式）");
      return Promise.resolve(null);
    }
    return browseWorkspace(path);
  },
  /** 目录选择器的「新建文件夹」，同样只走 live 后端。 */
  createWorkspaceDir(parent: string, name: string) {
    if (appStore.getState().mode !== "live") {
      showToast("新建文件夹需要连接本地后端（live 模式）");
      return Promise.resolve(null);
    }
    return createWorkspaceDir(parent, name);
  },
  saveAgentConfig(c: AgentConfig) {
    if (appStore.getState().mode === "live") {
      return upsertAgentConfigLive(c);
    }
    upsertAgentConfig(c);
    showToast("智能体配置已保存");
    return Promise.resolve(true);
  },
  deleteAgentConfig(id: string) {
    if (appStore.getState().mode === "live") {
      return deleteAgentConfigLive(id);
    }
    removeAgentConfig(id);
    showToast("智能体配置已删除");
    return Promise.resolve(true);
  },
  refreshRuntimes() {
    if (appStore.getState().mode === "live") {
      void loadRuntimes();
    }
  },
  loadOcProviders() {
    if (appStore.getState().mode === "live") {
      void loadOcProviders();
    }
  },
  saveOcProvider(p: OpenCodeProvider) {
    if (appStore.getState().mode === "live") {
      return upsertOcProviderLive(p);
    }
    upsertOcProvider(p);
    showToast("OpenCode 供应商已保存（demo）");
    return Promise.resolve(true);
  },
  deleteOcProvider(key: string) {
    if (appStore.getState().mode === "live") {
      return deleteOcProviderLive(key);
    }
    removeOcProvider(key);
    showToast("OpenCode 供应商已删除（demo）");
    return Promise.resolve(true);
  },
  openTicket(no: string) {
    appStore.setState({ view: "workbench" });
    if (appStore.getState().mode === "live") return selectTicketLive(no);
    selectTicket(no);
    // demo 模式也投影证据链（带水印标记），保证第四个 tab 在演示下可用
    void import("@/features/gate/api").then((m) => m.loadEvidence(no));
  },
  async connectLive(token: string) {
    const ok = await verifyToken(token);
    if (!ok) return false;
    wipePersisted();
    appStore.setState({ mode: "live", token, conn: "ok" });
    startAgentBusyPolling();
    try {
      await Promise.all([
        loadTickets(),
        loadProjects(),
        loadAgentConfigs(),
        loadRuntimes(),
        loadOcProviders(),
        loadEngineConfig(),
      ]);
      // Demo leaves a demo project/agent id behind; live data is filtered by the project
      // field and sessions need a real agent config, so reset both to backend values.
      const st = appStore.getState();
      appStore.setState({
        activeProjectId: st.projects[0]?.id ?? "",
        agentId: st.agents.some((a) => a.id === st.agentId) ? st.agentId : (st.agents[0]?.id ?? ""),
      });
      const first = appStore.getState().tickets[0];
      if (first) await selectTicketLive(first.ticketNo);
    } catch {
      /* 列表加载失败不阻断连接 */
    }
    return true;
  },
  useDemo() {
    stopAgentBusyPolling();
    appStore.setState({ runningAgents: { count: 0, sessions: [] } });
    wipePersisted();
    seedDemo(true);
  },
  overridePass(no: string) {
    if (appStore.getState().mode === "live") {
      return liveReview(no, { humanPass: true, note: "人工核准放行" });
    }
    setVerdict(no, {
      verdict: "PASS",
      reason: "人工核准放行",
      engineId: "human/override",
      round: appStore.getState().snapshots[no]?.length ?? 1,
      authorizationId: "manual-" + Date.now().toString(36),
    });
    setStage(no, "READY_TO_PUBLISH");
    pushSystemMessage(no, "人工核准通过 · 发布授权已签发", "success");
    markReviewEnded(no, "PASS");
    return Promise.resolve();
  },
  rejectTicket(no: string) {
    if (appStore.getState().mode === "live") {
      return liveReview(no, { humanPass: false, note: "人工驳回重修" });
    }
    setStage(no, "REJECTED");
    pushSystemMessage(no, "人工驳回 · 请根据审查意见修复后重新提审", "warn");
    markReviewEnded(no, "REJECT");
    return Promise.resolve();
  },
  startTicket(no: string) {
    if (appStore.getState().mode === "live") {
      return updateTicketLive(no, { stage: "IN_PROGRESS" });
    }
    setStage(no, "IN_PROGRESS");
    pushSystemMessage(no, "工单已开始 · Agent 可以在沙箱内编码", "info");
    return Promise.resolve(true);
  },
  /** 退回待处理：仅「进行中」可拖回待处理重新排队（队列内流转，无需理由，后端同口径放行）。 */
  backToPending(no: string) {
    if (appStore.getState().mode === "live") {
      return updateTicketLive(no, { stage: "PENDING" });
    }
    setStage(no, "PENDING");
    pushSystemMessage(no, "工单已退回待处理", "info");
    return Promise.resolve(true);
  },
  /** 重启终态工单（T-117）：理由必填，轮次自动加一，进入 IN_PROGRESS。 */
  restartTicket(no: string, reason: string) {
    if (appStore.getState().mode === "live") {
      return restartTicketLive(no, reason);
    }
    setStage(no, "IN_PROGRESS");
    pushSystemMessage(no, `工单已重启 · 本轮理由：${reason}`, "info");
    return Promise.resolve(true);
  },
  /** 基座同步（T-118）：工单分支快进到主分支最新 tip，未提交改动原样保留。仅 live 模式可用。 */
  syncBase(no: string) {
    if (appStore.getState().mode === "live") {
      return liveSyncBase(no);
    }
    pushSystemMessage(no, "演示模式无主分支可同步", "info");
    return Promise.resolve();
  },
  /**
   * 「新建会话」进入空白草稿态：清空聊天区、解锁 Composer 的 Agent 选择，
   * 发送首条消息时才真正创建会话并固化 agent（live/demo 同一管道）。
   * 不再立即建会话——此前按钮直接用全局默认 agent 建会话，用户没有选择机会。
   * 可选 groupId：分组头 + 的新建入口，首条消息建会话后自动归入该分组。
   */
  startSessionDraft(no: string, groupId?: string) {
    startSessionDraftLocal(no, groupId);
  },
  archiveSession(no: string, id: string) {
    if (appStore.getState().mode === "live") {
      void patchSessionLive(id, { archived: true });
      return;
    }
    archiveSessionLocal(no, id);
  },
  restoreSession(no: string, id: string) {
    if (appStore.getState().mode === "live") {
      void patchSessionLive(id, { archived: false });
      return;
    }
    restoreSessionLocal(no, id);
  },
  deleteSession(no: string, id: string) {
    if (appStore.getState().mode === "live") {
      void deleteSessionLive(id, no);
      return;
    }
    // 与 live 的 deleteSessionLive 同口径：demo 删除会话时先清队列，避免残留队列
    // 被泵自动投递到该工单的其它/新建会话。
    clearSessionQueue(id);
    deleteSessionLocal(no, id);
  },
  switchSession(no: string, id: string) {
    // 与运行监控「前往处理」同语义：点开待回答/中断的会话即视为已关注——
    // 该会话名下的待决登记与中断标记一并清除（顶栏 chip 黄组/工单徽标/会话小圆点
    // 随之消退），忽略表防止慢节拍重拉重新点亮；卡片由下方补拉恢复。
    // 只清当前点开的会话：其它会话的提醒保持原样。
    dismissSessionAsks(id);
    clearSessionInterrupted(id);
    if (appStore.getState().mode === "live") {
      switchSessionLocal(no, id);
      // 历史重建（整表替换聊天视图）后再补拉未决的权限/提问卡片——顺序与
      // selectTicketLive 一致，否则卡片先挂后视图被冲掉；被忽略的 id 不会重新点亮。
      void loadSessionMessages(no, id)
        .catch(() => {})
        .then(() => {
          // 与 selectTicketLive 同门槛：仅活跃会话恢复未决卡片（归档会话无可答项）
          const sess = (appStore.getState().sessions[no] ?? []).find((x) => x.id === id);
          if (sess?.status !== "active") return;
          void loadSessionPermissions(no, id);
          void loadSessionQuestions(no, id);
        });
      void loadSessionCatalog(no, id);
      return;
    }
    switchSessionLocal(no, id);
  },
  /** 会话内实时切换模型/推理强度：持久化覆盖，下一回合生效。 */
  switchSessionModel(
    no: string,
    sel: { providerId: string | null; modelId: string | null; variant: string | null },
  ) {
    const st = appStore.getState();
    const sid = st.activeSessionId[no];
    if (!sid) {
      // 草稿态：暂存为草稿选择，随首条消息创建会话时持久化为覆盖。
      // 清空覆盖（provider/model 皆空）等价于跟随新 Agent 的默认。
      if (!sel.providerId && !sel.modelId) clearDraftModelSel(no);
      else setDraftModelSel(no, sel);
      return Promise.resolve(true);
    }
    if (st.mode !== "live") return Promise.resolve(false);
    return switchSessionModelLive(sid, sel);
  },
  /** 开关「权限：自动允许」：持久化到会话并回写 store。 */
  setSessionAutoAccept(no: string, value: boolean) {
    const st = appStore.getState();
    if (st.mode !== "live") return Promise.resolve(false);
    const sid = st.activeSessionId[no];
    if (!sid) return Promise.resolve(false);
    return patchSessionLive(sid, { permission_auto_accept: value });
  },
  /** 切换 claude 权限模式档位（V24 轮询）：PATCH 持久化，下一次发送时生效。 */
  setSessionPermissionMode(no: string, mode: string) {
    const st = appStore.getState();
    if (st.mode !== "live") return Promise.resolve(false);
    const sid = st.activeSessionId[no];
    if (!sid) return Promise.resolve(false);
    return patchSessionLive(sid, { permission_mode: mode });
  },
  abort(no: string) {
    if (appStore.getState().mode === "live") {
      return abortLive(no);
    }
    demoAbort(no);
    return Promise.resolve();
  },
  /* ─── 会话分组与置顶（T-105）：端侧软数据（live 后端暂无分组 API），demo/live 同一实现 ─── */
  /** 创建分组（名称必填、颜色取调色板），返回新分组 id；供对话框在创建后直接移入会话。 */
  createSessionGroup(ticketNo: string, name: string, color: string): Promise<string | null> {
    if (!name.trim() || !color) return Promise.resolve(null);
    return Promise.resolve(createSessionGroupLocal(ticketNo, name.trim(), color));
  },
  /** 更新分组（改名/换色）。 */
  updateSessionGroup(ticketNo: string, groupId: string, patch: { name?: string; color?: string }) {
    updateSessionGroupLocal(ticketNo, groupId, patch);
    return Promise.resolve(true);
  },
  /** 删除分组：组内会话回到未分组。 */
  deleteSessionGroup(ticketNo: string, groupId: string) {
    deleteSessionGroupLocal(ticketNo, groupId);
    return Promise.resolve(true);
  },
  /** 会话移入分组（groupId 为 null/空串时移出分组）。 */
  moveSessionToGroup(ticketNo: string, sessionId: string, groupId: string | null) {
    moveSessionToGroupLocal(ticketNo, sessionId, groupId && groupId.trim() !== "" ? groupId : null);
    return Promise.resolve(true);
  },
  /** 置顶 / 取消置顶：置顶会话在其分段内优先展示。 */
  setSessionPinned(ticketNo: string, sessionId: string, pinned: boolean) {
    setSessionPinnedLocal(ticketNo, sessionId, pinned);
    return Promise.resolve(true);
  },
  /** 覆写某工单的置顶会话序列（渲染层分区编辑后的规范化写回）。 */
  setSessionPinnedOrder(ticketNo: string, orderedIds: string[]) {
    setSessionPinnedOrderLocal(ticketNo, orderedIds);
    return Promise.resolve(true);
  },
  /** 重命名会话：live 走 patchSessionLive(title)，demo 本地改 store */
  renameSession(ticketNo: string, sessionId: string, newTitle: string) {
    if (appStore.getState().mode === "live") {
      void patchSessionLive(sessionId, { title: newTitle });
    }
    renameSessionLocal(ticketNo, sessionId, newTitle);
    return Promise.resolve(true);
  },
};
