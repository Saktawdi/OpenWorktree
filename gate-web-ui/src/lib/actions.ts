import * as demo from "./engine";
import * as live from "./api";
import {
  appStore,
  appendStageChange,
  archiveSession as archiveSessionLocal,
  createSession as createSessionLocal,
  createTicket,
  deleteSession as deleteSessionLocal,
  pushSystemMessage,
  removeAgentConfig,
  removeOcProvider,
  removeProject,
  requestCancel,
  restoreSession as restoreSessionLocal,
  seedDemo,
  selectTicket,
  setCenterTab,
  setCreatingSession,
  setStage,
  setVerdict,
  showToast,
  switchSession as switchSessionLocal,
  updateTicket,
  upsertAgentConfig,
  upsertOcProvider,
  upsertProject,
  wipePersisted,
} from "./store";
import type { AgentConfig, OpenCodeProvider, PendingAttachment, Project, Ticket } from "./types";

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
  /** AI 审查：live 模式要求已配置审查引擎（gate.toml [engine]）。 */
  reviewAi(no: string) {
    return appStore.getState().mode === "live" ? live.liveReview(no) : demo.demoReview(no);
  },
  /** 人工审查：弹窗确认"已审阅"后以人工判决落盘（human_pass=true）。 */
  reviewHuman(no: string) {
    if (appStore.getState().mode === "live") {
      return live.liveReview(no, { humanPass: true, note: "人工审查通过（确认已审阅）" });
    }
    setVerdict(no, {
      verdict: "PASS",
      reason: "人工审查通过（确认已审阅）",
      engineId: "human/override",
      round: appStore.getState().snapshots[no]?.length ?? 1,
      authorizationId: "manual-" + Date.now().toString(36),
    });
    setStage(no, "READY_TO_PUBLISH");
    pushSystemMessage(no, "人工审查通过 · 发布授权已签发", "success");
    setCenterTab("findings");
    return Promise.resolve();
  },
  review(no: string) {
    return actions.reviewAi(no);
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
  /** 取消工单（V19）：任意非终态可取消，理由必填并记入状态变更历史。 */
  cancelTicket(no: string, reason: string, from?: Ticket["stage"]) {
    if (appStore.getState().mode === "live") {
      return live.cancelTicketLive(no, reason);
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
      return live.completeTicketLive(no, reason);
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
      return live.createProjectLive({
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
      return live.updateProjectLive(id, {
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
      return live.deleteProjectLive(id);
    }
    removeProject(id);
    showToast("项目已移除（工单保留为未分配）");
    return Promise.resolve(true);
  },
  /** 星标开关：置顶展示，不参与「更新于」时间刷新。 */
  setProjectStarred(id: string, starred: boolean) {
    if (appStore.getState().mode === "live") {
      return live.setProjectStarredLive(id, starred);
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
      return live.reorderProjectsLive(orderedIds);
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
    return live.browseWorkspace(path);
  },
  /** 目录选择器的「新建文件夹」，同样只走 live 后端。 */
  createWorkspaceDir(parent: string, name: string) {
    if (appStore.getState().mode !== "live") {
      showToast("新建文件夹需要连接本地后端（live 模式）");
      return Promise.resolve(null);
    }
    return live.createWorkspaceDir(parent, name);
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
  loadOcProviders() {
    if (appStore.getState().mode === "live") {
      void live.loadOcProviders();
    }
  },
  saveOcProvider(p: OpenCodeProvider) {
    if (appStore.getState().mode === "live") {
      return live.upsertOcProviderLive(p);
    }
    upsertOcProvider(p);
    showToast("OpenCode 供应商已保存（demo）");
    return Promise.resolve(true);
  },
  deleteOcProvider(key: string) {
    if (appStore.getState().mode === "live") {
      return live.deleteOcProviderLive(key);
    }
    removeOcProvider(key);
    showToast("OpenCode 供应商已删除（demo）");
    return Promise.resolve(true);
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
      await Promise.all([
        live.loadTickets(),
        live.loadProjects(),
        live.loadAgentConfigs(),
        live.loadRuntimes(),
        live.loadOcProviders(),
        live.loadEngineConfig(),
      ]);
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
  /** 重启终态工单（T-117）：理由必填，轮次自动加一，进入 IN_PROGRESS。 */
  restartTicket(no: string, reason: string) {
    if (appStore.getState().mode === "live") {
      return live.restartTicketLive(no, reason);
    }
    setStage(no, "IN_PROGRESS");
    pushSystemMessage(no, `工单已重启 · 本轮理由：${reason}`, "info");
    return Promise.resolve(true);
  },
  /** 基座同步（T-118）：工单分支快进到主分支最新 tip，未提交改动原样保留。仅 live 模式可用。 */
  syncBase(no: string) {
    if (appStore.getState().mode === "live") {
      return live.liveSyncBase(no);
    }
    pushSystemMessage(no, "演示模式无主分支可同步", "info");
    return Promise.resolve();
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
  const conn = await live.detectBackend();
  appStore.setState({ conn });
  // 已在 live 模式且后端连通时启动运行中智能体轮询
  const st = appStore.getState();
  if (st.mode === "live" && conn === "ok") {
    live.startAgentBusyPolling();
    // 刷新后走 sessionStorage 恢复，不会经过 connectLive：引擎配置（AI 审查入口的
    // 可用性判断）必须在这里补拉，否则 engine 恒为 null，按钮永远停在"配置读取中"。
    void live.loadEngineConfig();
  } else if (conn !== "ok") {
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
