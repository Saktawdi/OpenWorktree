/**
 * 项目域 API（project）：项目 CRUD、仓库视图（分支图/文件树）、
 * 工作区目录浏览与终端目录列表。终端 WebSocket 见 terminal.ts。
 */
import { api } from "@/net";
import { appStore, showToast } from "@/store";
import type { GitRepoView, GitTreeEntry, Project, TerminalEntry, WorkspaceListing, WorkspaceSyncResult, Priority } from "@/shared/types";

interface RawProject {
  id: string;
  name: string;
  workspace_path: string;
  target_ref?: string | null;
  auth_repo?: string | null;
  priority?: string | null;
  size?: string | null;
  tags?: string[] | null;
  starred?: boolean | null;
  sort_order?: number | null;
  ticket_count?: number;
  active_ticket_count?: number;
  super_ticket_no?: string | null;
  created_at: string;
  updated_at: string;
}

function mapProject(p: RawProject): Project {
  return {
    id: p.id,
    name: p.name,
    workspacePath: p.workspace_path,
    targetRef: p.target_ref ?? "refs/heads/main",
    authRepo: p.auth_repo ?? "",
    priority: (p.priority as Priority | null) ?? null,
    size: (p.size as "small" | "medium" | "large" | null) ?? null,
    tags: p.tags ?? [],
    starred: p.starred ?? false,
    sortOrder: p.sort_order ?? 0,
    ticketCount: p.ticket_count ?? 0,
    activeTicketCount: p.active_ticket_count ?? 0,
    superTicketNo: p.super_ticket_no ?? null,
    createdAt: p.created_at,
    updatedAt: p.updated_at,
  };
}

export async function loadProjects() {
  const data = await api<{ projects: RawProject[] }>("/api/projects");
  appStore.setState({ projects: data.projects.map(mapProject) });
}

export async function createProjectLive(body: {
  name: string;
  workspace_path: string;
  target_branch?: string;
  init_git?: boolean;
  priority?: string | null;
  size?: string | null;
  tags?: string[];
}): Promise<boolean> {
  try {
    // The response is the created project row; switch the workspace context to it so
    // the freshly onboarded (usually empty) project is immediately usable.
    const created = await api<{ id: string }>("/api/projects", {
      method: "POST",
      body: JSON.stringify(body),
    });
    await loadProjects();
    if (created?.id) {
      appStore.setState({ activeProjectId: created.id });
    }
    return true;
  } catch (e) {
    showToast(`创建项目失败：${(e as Error).message}`);
    return false;
  }
}

export async function updateProjectLive(id: string, body: Record<string, unknown>): Promise<boolean> {
  try {
    await api(`/api/projects/${id}`, { method: "PUT", body: JSON.stringify(body) });
    await loadProjects();
    return true;
  } catch (e) {
    showToast(`更新项目失败：${(e as Error).message}`);
    return false;
  }
}

export async function deleteProjectLive(id: string): Promise<boolean> {
  try {
    await api(`/api/projects/${id}`, { method: "DELETE" });
    await loadProjects();
    return true;
  } catch (e) {
    showToast(`删除项目失败：${(e as Error).message}`);
    return false;
  }
}

/** 星标/取消星标（控制台整理动作，走 updateProject 但只带 starred 键）。 */
export async function setProjectStarredLive(id: string, starred: boolean): Promise<boolean> {
  try {
    await api(`/api/projects/${id}`, {
      method: "PUT",
      body: JSON.stringify({ starred }),
    });
    await loadProjects();
    return true;
  } catch (e) {
    showToast(`星标更新失败：${(e as Error).message}`);
    return false;
  }
}

/** 拖拽排序持久化：console 给出全量展示顺序，后端写 1..N 的 sort_order。 */
export async function reorderProjectsLive(orderedIds: string[]): Promise<boolean> {
  try {
    await api("/api/projects/reorder", {
      method: "POST",
      body: JSON.stringify({ order: orderedIds }),
    });
    await loadProjects();
    return true;
  } catch (e) {
    showToast(`排序保存失败：${(e as Error).message}`);
    return false;
  }
}

/* ─── 项目 → 仓库视图（分支图 + 文件树，读工作区仓库） ─── */

interface RawGitBranch {
  name: string;
  tip: string;
  lane: number;
}

interface RawGitCommit {
  sha: string;
  parents?: string[] | null;
  message: string;
  author: string;
  time: string;
  refs?: string[] | null;
  lane: number;
}

interface RawGitRepoView {
  repo_path?: string | null;
  head?: string | null;
  branches?: RawGitBranch[] | null;
  commits?: RawGitCommit[] | null;
  truncated?: boolean | null;
  auth?: { repo: string | null; target_ref: string | null; tip: string | null } | null;
}

/** GET /api/projects/{id}/repo — branches + topo commits with lane numbers. */
export async function loadProjectRepoView(projectId: string): Promise<void> {
  const data = await api<RawGitRepoView>(`/api/projects/${projectId}/repo`);
  const view: GitRepoView = {
    branches: (data.branches ?? []).map((b) => ({ name: b.name, tip: b.tip, lane: b.lane })),
    commits: (data.commits ?? []).map((c) => ({
      sha: c.sha,
      parents: c.parents ?? [],
      message: c.message,
      author: c.author,
      time: c.time,
      refs: c.refs ?? [],
      lane: c.lane,
    })),
    truncated: data.truncated ?? false,
    auth: data.auth ?? undefined,
  };
  appStore.setState((st) => ({ gitViews: { ...st.gitViews, [projectId]: view } }));
}

interface RawTreeEntry {
  path: string;
  type: string;
  size?: number | null;
  last_commit_short?: string | null;
  last_message?: string | null;
}

/**
 * GET /api/projects/{id}/tree[/{dir…}] — direct children of one directory with last-commit
 * attribution. The root listing lands in treeViews; callers expanding deeper keep the
 * children themselves (they refetch on reopen anyway).
 */
export async function loadProjectTree(projectId: string, dir = ""): Promise<GitTreeEntry[]> {
  const suffix = dir
    ? "/" + dir.split("/").map(encodeURIComponent).join("/")
    : "";
  const data = await api<{ entries?: RawTreeEntry[] }>(`/api/projects/${projectId}/tree${suffix}`);
  const entries: GitTreeEntry[] = (data.entries ?? []).map((e) => ({
    path: e.path,
    type: e.type === "dir" ? "dir" : "file",
    size: e.size ?? undefined,
    lastCommitShort: e.last_commit_short ?? "",
    lastMessage: e.last_message ?? "",
  }));
  if (!dir) {
    appStore.setState((st) => ({ treeViews: { ...st.treeViews, [projectId]: entries } }));
  }
  return entries;
}

/* ─── 项目 → 终端目录（克隆目录列表，供终端选择 base 目录） ─── */

interface RawTerminalEntry {
  path: string;
  label: string;
  type: string;
  ticket_no?: string | null;
  ticket_title?: string | null;
  exists?: boolean | null;
}

/** GET /api/projects/{id}/terminals — 工作区 + 各工单克隆目录（供终端选择 base 目录）。 */
export async function loadProjectTerminals(projectId: string): Promise<TerminalEntry[]> {
  const data = await api<{ entries?: RawTerminalEntry[] }>(
    `/api/projects/${projectId}/terminals`,
  );
  return (data.entries ?? []).map((e) => ({
    path: e.path,
    label: e.label,
    type: e.type === "clone" ? "clone" : "workspace",
    ticketNo: e.ticket_no ?? null,
    ticketTitle: e.ticket_title ?? null,
    exists: e.exists ?? true,
  }));
}

/* ─── 工作区目录浏览（路径选择器） ─── */

interface RawWorkspaceListing {
  path: string;
  parent?: string | null;
  exists: boolean;
  platform?: string | null;
  user_home?: string | null;
  roots?: { name: string; path: string }[] | null;
  directories?: {
    name: string;
    path: string;
    is_git_repo?: boolean | null;
    is_registered_project?: boolean | null;
  }[] | null;
}

function toWorkspaceListing(data: RawWorkspaceListing): WorkspaceListing {
  return {
    path: data.path,
    parent: data.parent ?? null,
    exists: data.exists,
    platform: data.platform ?? "",
    userHome: data.user_home ?? "",
    roots: data.roots ?? [],
    directories: (data.directories ?? []).map((d) => ({
      name: d.name,
      path: d.path,
      isGitRepo: d.is_git_repo ?? false,
      isRegisteredProject: d.is_registered_project ?? false,
    })),
  };
}

/** 目录浏览（工作区路径选择器）：lists child directories of `path`，空串/缺省 = 用户家目录。 */
export async function browseWorkspace(path: string): Promise<WorkspaceListing | null> {
  try {
    const trimmed = path.trim();
    const data = await api<RawWorkspaceListing>("/api/workspaces", {
      method: "POST",
      // 空 path 不带字段：后端回退到用户家目录（传空串会被解析成进程工作目录）
      body: JSON.stringify(trimmed ? { path: trimmed } : {}),
    });
    return toWorkspaceListing(data);
  } catch (e) {
    showToast(`目录浏览失败：${(e as Error).message}`);
    return null;
  }
}

/** 目录选择器里的「新建文件夹」：在 parent 下创建一层子目录，返回新目录信息。 */
export async function createWorkspaceDir(parent: string, name: string): Promise<{ path: string } | null> {
  try {
    return await api<{ path: string }>("/api/workspaces/mkdir", {
      method: "POST",
      body: JSON.stringify({ parent, name }),
    });
  } catch (e) {
    showToast(`新建文件夹失败：${(e as Error).message}`);
    return null;
  }
}

export async function syncProjectWorkspace(projectId: string): Promise<WorkspaceSyncResult | null> {
  try {
    const res = await api<WorkspaceSyncResult>(`/api/projects/${projectId}/workspace-sync`, {
      method: "POST",
      body: "{}",
    });
    if (res.status === "SYNCED") {
      showToast("工作区已同步");
    } else if (res.status === "ALREADY") {
      showToast("工作区已是最新");
    } else if (res.status === "DEFERRED") {
      showToast(res.note ? `工作区待同步：${res.note}` : "工作区待同步");
    }
    return res;
  } catch (e) {
    showToast(`工作区同步失败：${(e as Error).message}`);
    return null;
  }
}
