import * as demo from "./engine";
import * as live from "./api";
import {
  appStore,
  archiveSession as archiveSessionLocal,
  cancelTicket,
  createSession as createSessionLocal,
  createTicket,
  deleteSession as deleteSessionLocal,
  pushSystemMessage,
  removeAgentConfig,
  removeProject,
  requestCancel,
  restoreSession as restoreSessionLocal,
  seedDemo,
  selectTicket,
  setCreatingSession,
  setStage,
  setVerdict,
  showToast,
  switchSession as switchSessionLocal,
  updateTicket,
  upsertAgentConfig,
  upsertProject,
  wipePersisted,
} from "./store";
import type { AgentConfig, PendingAttachment, Project, Ticket } from "./types";

export const actions = {
  sendPrompt(no: string, text: string, attachments: PendingAttachment[] = []) {
    // text 已含 Composer 插入的 [图片 #n] 引用；live 走 attachments 数组，demo 仅展示引用。
    if (appStore.getState().mode === "live") return live.liveSendPrompt(no, text, attachments);
    return demo.demoSendPrompt(no, text);
  },
  returnWithFindings(no: string) {
    const st = appStore.getState();
    const findings = st.findings[no] ?? [];
    if (findings.length === 0) return;
    if (st.mode === "live") {
      const text = ["请按以下审查意见逐条修复：", demo.findingsToPromptText(findings)].join("\n");
      return live.liveSendPrompt(no, text);
    }
    return demo.demoReturnWithFindings(no);
  },
  presubmit(no: string) {
    return appStore.getState().mode === "live" ? live.livePresubmit(no) : demo.demoPresubmit(no);
  },
  review(no: string) {
    return appStore.getState().mode === "live" ? live.liveReview(no) : demo.demoReview(no);
  },
  publish(no: string) {
    return appStore.getState().mode === "live" ? live.livePublish(no) : demo.demoPublish(no);
  },
  newTicket(
    title: string,
    priority: "P0" | "P1" | "P2" | "P3",
    extra?: {
      description?: string;
      labels?: string[];
      agentConfigId?: string;
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
        const no = await live.createTicketLive({
          title,
          priority,
          stage: "PENDING",
          project_id: projectId,
          description: extra?.description,
          labels: extra?.labels,
          agent_config_id: extra?.agentConfigId,
          target_branch: extra?.targetBranch,
        });
        if (!no) return;
        await live.loadTickets().catch(() => {});
        await live.selectTicketLive(no);
      })();
      return null;
    }
    const no = createTicket(title, priority);
    const p = extra ?? {};
    if (p.description || p.labels?.length || p.agentConfigId) {
      updateTicket(no, {
        description: p.description,
        labels: p.labels,
        agentConfigId: p.agentConfigId,
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
      agentConfigId?: string | null;
    },
  ) {
    if (appStore.getState().mode === "live") {
      // 后端 PATCH /api/tickets/{no} 只认 snake_case 的 agent_config_id；
      // 直接透传 camelCase 会被静默忽略，表现为绑定智能体「只改样式不落库」。
      const { agentConfigId, ...rest } = patch;
      return live.updateTicketLive(no, {
        ...rest,
        ...(agentConfigId !== undefined ? { agent_config_id: agentConfigId } : {}),
      });
    }
    updateTicket(no, patch);
    return Promise.resolve(true);
  },
  cancelTicket(no: string) {
    if (appStore.getState().mode === "live") {
      return live.updateTicketLive(no, { stage: "CANCELLED" });
    }
    cancelTicket(no);
    pushSystemMessage(no, "工单已取消 · 沙箱克隆保留，可随时归档", "warn");
    return Promise.resolve(true);
  },
  createProject(body: {
    name: string;
    workspacePath: string;
    initGit: boolean;
    priority: string | null;
    size: string | null;
    tags: string[];
  }) {
    if (appStore.getState().mode === "live") {
      return live.createProjectLive({
        name: body.name,
        workspace_path: body.workspacePath,
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
    patch: { name?: string; priority?: Project["priority"] | null; size?: Project["size"] | null; tags?: string[] },
  ) {
    if (appStore.getState().mode === "live") {
      return live.updateProjectLive(id, patch);
    }
    const st = appStore.getState();
    const p = st.projects.find((x) => x.id === id);
    if (!p) return Promise.resolve(false);
    upsertProject({ ...p, ...patch, updatedAt: new Date().toISOString() });
    return Promise.resolve(true);
  },
  deleteProject(id: string) {
    if (appStore.getState().mode === "live") {
      return live.deleteProjectLive(id);
    }
    removeProject(id);
    showToast("项目已移除（工单保留为未分配）");
    return Promise.resolve(true);
  },
  saveAgentConfig(c: AgentConfig) {
    if (appStore.getState().mode === "live") {
      return live.upsertAgentConfigLive(c);
    }
    upsertAgentConfig(c);
    showToast("智能体配置已保存");
    return Promise.resolve(true);
  },
  deleteAgentConfig(id: string) {
    if (appStore.getState().mode === "live") {
      return live.deleteAgentConfigLive(id);
    }
    removeAgentConfig(id);
    showToast("智能体配置已删除");
    return Promise.resolve(true);
  },
  refreshRuntimes() {
    if (appStore.getState().mode === "live") {
      void live.loadRuntimes();
    }
  },
  openTicket(no: string) {
    appStore.setState({ view: "workbench" });
    if (appStore.getState().mode === "live") return live.selectTicketLive(no);
    selectTicket(no);
  },
  async connectLive(token: string) {
    const ok = await live.verifyToken(token);
    if (!ok) return false;
    wipePersisted();
    appStore.setState({ mode: "live", token, conn: "ok" });
    live.startAgentBusyPolling();
    try {
      await Promise.all([live.loadTickets(), live.loadProjects(), live.loadAgentConfigs(), live.loadRuntimes()]);
      // Demo leaves a demo project/agent id behind; live data is filtered by the project
      // field and sessions need a real agent config, so reset both to backend values.
      const st = appStore.getState();
      appStore.setState({
        activeProjectId: st.projects[0]?.id ?? "",
        agentId: st.agents.some((a) => a.id === st.agentId) ? st.agentId : (st.agents[0]?.id ?? ""),
      });
      const first = appStore.getState().tickets[0];
      if (first) await live.selectTicketLive(first.ticketNo);
    } catch {
      /* 列表加载失败不阻断连接 */
    }
    return true;
  },
  useDemo() {
    live.stopAgentBusyPolling();
    appStore.setState({ runningAgents: { count: 0, sessions: [] } });
    wipePersisted();
    seedDemo(true);
  },
  overridePass(no: string) {
    if (appStore.getState().mode === "live") {
      return live.liveReview(no, { humanPass: true, note: "人工核准放行" });
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
    return Promise.resolve();
  },
  rejectTicket(no: string) {
    if (appStore.getState().mode === "live") {
      return live.liveReview(no, { humanPass: false, note: "人工驳回重修" });
    }
    setStage(no, "REJECTED");
    pushSystemMessage(no, "人工驳回 · 请根据审查意见修复后重新提审", "warn");
    return Promise.resolve();
  },
  startTicket(no: string) {
    if (appStore.getState().mode === "live") {
      return live.updateTicketLive(no, { stage: "IN_PROGRESS" });
    }
    setStage(no, "IN_PROGRESS");
    pushSystemMessage(no, "工单已开始 · Agent 可以在沙箱内编码", "info");
    return Promise.resolve(true);
  },
  createSession(no: string) {
    if (appStore.getState().creatingSession[no]) return;
    setCreatingSession(no, true);
    if (appStore.getState().mode === "live") {
      void live
        .createSessionLive(no)
        .catch(() => {})
        .finally(() => setCreatingSession(no, false));
      return;
    }
    try {
      createSessionLocal(no);
    } finally {
      setCreatingSession(no, false);
    }
  },
  archiveSession(no: string, id: string) {
    if (appStore.getState().mode === "live") {
      void live.patchSessionLive(id, { archived: true });
      return;
    }
    archiveSessionLocal(no, id);
  },
  restoreSession(no: string, id: string) {
    if (appStore.getState().mode === "live") {
      void live.patchSessionLive(id, { archived: false });
      return;
    }
    restoreSessionLocal(no, id);
  },
  deleteSession(no: string, id: string) {
    if (appStore.getState().mode === "live") {
      void live.deleteSessionLive(id, no);
      return;
    }
    deleteSessionLocal(no, id);
  },
  switchSession(no: string, id: string) {
    if (appStore.getState().mode === "live") {
      switchSessionLocal(no, id);
      void live.loadSessionMessages(no, id).catch(() => {});
      void live.loadSessionCatalog(no, id);
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
    if (st.mode !== "live") return Promise.resolve(false);
    const sid = st.activeSessionId[no];
    if (!sid) return Promise.resolve(false);
    return live.switchSessionModelLive(sid, sel);
  },
  /** 开关「权限：自动允许」：持久化到会话并回写 store。 */
  setSessionAutoAccept(no: string, value: boolean) {
    const st = appStore.getState();
    if (st.mode !== "live") return Promise.resolve(false);
    const sid = st.activeSessionId[no];
    if (!sid) return Promise.resolve(false);
    return live.patchSessionLive(sid, { permission_auto_accept: value });
  },
  abort(no: string) {
    if (appStore.getState().mode === "live") {
      return live.abortLive(no);
    }
    requestCancel(no);
    return Promise.resolve();
  },
};
export async function boot() {
  seedDemo();
  const conn = await live.detectBackend();
  appStore.setState({ conn });
  // 已在 live 模式且后端连通时启动运行中智能体轮询
  const st = appStore.getState();
  if (st.mode === "live" && conn === "ok") live.startAgentBusyPolling();
  else if (conn !== "ok") {
    appStore.setState({ runningAgents: { count: 0, sessions: [] } });
    live.stopAgentBusyPolling();
  }
  // 监听后续模式/连接状态变化，自动启停轮询
  let prevMode = st.mode;
  let prevConn: string = conn;
  appStore.subscribe((cur) => {
    if (cur.mode !== prevMode || cur.conn !== prevConn) {
      prevMode = cur.mode;
      prevConn = cur.conn;
      if (cur.mode === "live" && cur.conn === "ok") live.startAgentBusyPolling();
      else {
        appStore.setState({ runningAgents: { count: 0, sessions: [] } });
        live.stopAgentBusyPolling();
      }
    }
  });
}
