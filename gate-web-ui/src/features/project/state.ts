/**
 * 项目域状态（project）：demo 本地项目 CRUD 与终端工作台（多标签 + 最小化后台）。
 */
import { appStore } from "@/store";
import type { PendingProject, Project, TerminalSessionMeta } from "@/shared/types";

const set = appStore.setState;

/* ─── 接入中的项目占位（乐观 UI） ───
   接入请求在飞时先落一张骨架卡（列表网格里可见、带已等待秒数），
   完成后由真实列表顶替、失败移除；占位不参与排序/拖拽。 */

let pendingProjectSeq = 0;

/** 登记一张接入占位卡，返回端侧临时 id（收尾时用它移除）。 */
export function addPendingProject(p: Omit<PendingProject, "tempId">): string {
  const tempId = `pp-${++pendingProjectSeq}-${Date.now().toString(36)}`;
  set((st) => ({ pendingProjects: [...st.pendingProjects, { ...p, tempId }] }));
  return tempId;
}

export function removePendingProject(tempId: string) {
  set((st) => ({ pendingProjects: st.pendingProjects.filter((x) => x.tempId !== tempId) }));
}

/** 项目列表加载中标记：仅空列表时呈现骨架屏（已有内容不因刷新闪烁）。 */
export function setProjectsLoading(loading: boolean) {
  set({ projectsLoading: loading });
}

export function upsertProject(p: Project) {
  set((st) => ({
    projects: st.projects.some((x) => x.id === p.id)
      ? st.projects.map((x) => (x.id === p.id ? p : x))
      : [...st.projects, p],
    activeProjectId: st.projects.some((x) => x.id === p.id) ? st.activeProjectId : p.id,
  }));
}

export function removeProject(id: string) {
  set((st) => ({
    projects: st.projects.filter((p) => p.id !== id),
    tickets: st.tickets.map((t) =>
      t.projectId === id ? { ...t, projectId: "" } : t,
    ),
    activeProjectId: st.activeProjectId === id ? (st.projects.find((p) => p.id !== id)?.id ?? "") : st.activeProjectId,
  }));
}

/* ─── 终端工作台：多标签 + 最小化后台（进程随会话存活） ─── */

let terminalSeq = 0;

/**
 * 新开一个终端标签：同目录允许多开（例如同一工作区一个跑前端、一个跑后端），
 * 同名标签自动追加序号（工作区、工作区 2…）以便区分。
 */
export function openTerminalSession(meta: Omit<TerminalSessionMeta, "id">) {
  set((st) => {
    const sameLabel = st.terminalSessions.filter((t) => t.label === meta.label).length;
    const label = sameLabel === 0 ? meta.label : `${meta.label} ${sameLabel + 1}`;
    const id = `term-${++terminalSeq}-${Date.now().toString(36)}`;
    return {
      terminalSessions: [...st.terminalSessions, { ...meta, label, id }],
      activeTerminalId: id,
      terminalView: "open" as const,
    };
  });
}

export function activateTerminalSession(id: string) {
  appStore.setState({ activeTerminalId: id, terminalView: "open" });
}

/** 关闭一个终端会话（组件卸载即断开 WebSocket、结束 shell 进程）。 */
export function closeTerminalSession(id: string) {
  set((st) => {
    const terminalSessions = st.terminalSessions.filter((t) => t.id !== id);
    const activeTerminalId =
      st.activeTerminalId === id
        ? (terminalSessions[terminalSessions.length - 1]?.id ?? null)
        : st.activeTerminalId;
    return {
      terminalSessions,
      activeTerminalId,
      terminalView:
        terminalSessions.length === 0
          ? ("closed" as const)
          : st.terminalView === "open"
            ? ("open" as const)
            : st.terminalView,
    };
  });
}

export function closeAllTerminalSessions() {
  appStore.setState({ terminalSessions: [], activeTerminalId: null, terminalView: "closed" });
}

/** 最小化终端工作台：会话与进程保持运行，顶栏圆钮可恢复。 */
export function minimizeTerminal() {
  appStore.setState({ terminalView: "minimized" });
}

export function restoreTerminal() {
  appStore.setState({ terminalView: "open" });
}
