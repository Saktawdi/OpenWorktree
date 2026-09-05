/**
 * 项目域（project）公共出口：项目/仓库视图/工作区/终端 API、状态与组件。
 */
export {
  loadProjects,
  createProjectLive,
  updateProjectLive,
  deleteProjectLive,
  setProjectStarredLive,
  reorderProjectsLive,
  loadProjectRepoView,
  loadProjectTree,
  loadProjectTerminals,
  syncProjectWorkspace,
  browseWorkspace,
  createWorkspaceDir,
} from "./api";
export { openTerminalSocket } from "./terminal";
export {
  upsertProject,
  removeProject,
  openTerminalSession,
  activateTerminalSession,
  closeTerminalSession,
  closeAllTerminalSessions,
  minimizeTerminal,
  restoreTerminal,
} from "./state";
export { ProjectsPage } from "./components/ProjectsPage";
export { RepoViewDialog, RepoViewPage } from "./components/RepoView";
export { TerminalWorkbench, TerminalPickerDialog, TerminalMinimizedChip } from "./components/ProjectTerminal";
export { WorkspaceBrowserDialog } from "./components/WorkspaceBrowserDialog";
