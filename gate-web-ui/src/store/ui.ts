/**
 * 界面导航与全局 UI 状态（store）：视图切换、连接弹窗、toast、主题、项目切换。
 * 只做纯导航/呈现态变更，不含任何后端调用。
 */
import type { Stage, Ticket } from "@/shared/types";
import { appStore, useApp, type AppState, type CenterTab, type Theme } from "./state";
import { loadTheme, saveTheme } from "./prefs";

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

export function switchProject(id: string) {
  patch({ activeProjectId: id });
  const first = s().tickets.find((t) => t.projectId === id && !isTerminalStage(t.stage));
  patch({ selectedNo: first?.ticketNo ?? null });
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
