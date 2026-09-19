/**
 * 界面导航与全局 UI 状态（store）：视图切换、连接弹窗、toast、主题、项目切换。
 * 只做纯导航/呈现态变更，不含任何后端调用。
 */
import type { Stage, Ticket } from "@/shared/types";
import { appStore, useApp, type AppState, type CenterTab, type Theme } from "./state";
import { loadLastTicketByProject, loadTheme, saveTheme } from "./prefs";

const s = () => appStore.getState();

function patch(partial: Partial<AppState>) {
  appStore.setState((st) => ({ ...st, ...partial }));
}

export { useApp };

export function ticketByNo(no: string | null): Ticket | undefined {
  if (!no) return undefined;
  return s().tickets.find((t) => t.ticketNo === no);
}

export function setView(view: AppState["view"]) {
  patch({ view });
}

/** 打开某项目的仓库视图整页（弹窗放大按钮跳转入口）。 */
export function openRepoView(projectId: string) {
  patch({ view: "repo", repoViewProjectId: projectId });
}

/** 关闭仓库视图整页，回到项目列表。 */
export function closeRepoView() {
  patch({ view: "projects", repoViewProjectId: null });
}

/** 打开插件贡献的整页（顶栏导航注册表里的 nav.pages 条目）。 */
export function openPluginPage(pageId: string) {
  patch({ view: "plugin-page", pluginPageId: pageId });
}

/** 关闭插件整页，回工作台（插件被禁用/重载时也走这里）。 */
export function closePluginPage() {
  patch({ view: "workbench", pluginPageId: null });
}

export function setCenterTab(tab: CenterTab) {
  patch({ centerTab: tab });
}

export function openConnect() {
  patch({ connectOpen: true });
}

export function closeConnect() {
  patch({ connectOpen: false });
}

let toastNonce = 0;
export function showToast(text: string) {
  toastNonce += 1;
  patch({ toast: { id: Date.now(), nonce: toastNonce, text } });
}

export function clearToast() {
  patch({ toast: null });
}

export function jumpToFinding(path: string, line: number) {
  patch({ centerTab: "diff", highlight: { path, line, nonce: Date.now() } });
}

/**
 * 切换当前项目：优先恢复该项目「最后打开的工单」（端侧缓存），
 * 缓存缺失/失效（工单不在列表或不属于该项目）时退回该项目的超级工单，
 * 再退回首个非终态工单。只改选中态；完整打开（拉会话/diff + 跳工作台）
 * 由 app 层 actions.openProject 编排。
 * @returns 选中的工单号；null = 该项目当前没有可打开的工单。
 */
export function switchProject(id: string): string | null {
  const st = s();
  const cached = loadLastTicketByProject()[id];
  const remembered = cached
    ? st.tickets.find((t) => t.ticketNo === cached && t.projectId === id)
    : undefined;
  const superNo = st.projects.find((p) => p.id === id)?.superTicketNo || null;
  const first = st.tickets.find((t) => t.projectId === id && !isTerminalStage(t.stage));
  const target = remembered?.ticketNo ?? superNo ?? first?.ticketNo ?? null;
  patch({ activeProjectId: id, selectedNo: target });
  return target;
}

function isTerminalStage(stage: Stage): boolean {
  return stage === "DONE" || stage === "CANCELLED";
}

export function toggleTheme() {
  const next: Theme = s().theme === "dark" ? "light" : "dark";
  document.documentElement.setAttribute("data-theme", next);
  saveTheme(next);
  patch({ theme: next });
}

export function applyThemeFromStorage() {
  const theme = loadTheme() as Theme;
  document.documentElement.setAttribute("data-theme", theme);
  patch({ theme });
}
