import { client } from './client';

export interface WorkspaceEntry {
  name: string;
  path: string;
  is_git_repo: boolean;
  is_registered_project: boolean;
}

/** 文件系统根（Windows 盘符），用于跨盘跳转。 */
export interface WorkspaceRoot {
  name: string;
  path: string;
}

export interface WorkspaceView {
  path: string;
  parent: string | null;
  exists: boolean;
  directories: WorkspaceEntry[];
  roots: WorkspaceRoot[];
}

export type ProjectPriority = 'P0' | 'P1' | 'P2' | 'P3';
export type ProjectSize = 'small' | 'medium' | 'large';

export interface ProjectView {
  id: string;
  name: string;
  workspace_path: string;
  target_ref: string | null;
  auth_repo: string | null;
  priority: ProjectPriority | null;
  size: ProjectSize | null;
  tags: string[];
  ticket_count: number;
  active_ticket_count: number;
  created_at: string;
  updated_at: string;
}

function numberOf(value: unknown): number {
  const number = typeof value === 'number' ? value : Number(value);
  return Number.isFinite(number) ? number : 0;
}

function projectOf(value: unknown): ProjectView {
  const raw = value && typeof value === 'object' ? value as Record<string, unknown> : {};
  const priority = raw.priority === 'P0' || raw.priority === 'P1' || raw.priority === 'P2' || raw.priority === 'P3'
    ? raw.priority
    : null;
  const size = raw.size === 'small' || raw.size === 'medium' || raw.size === 'large' ? raw.size : null;
  return {
    id: String(raw.id ?? ''),
    name: String(raw.name ?? ''),
    workspace_path: String(raw.workspace_path ?? ''),
    target_ref: raw.target_ref == null ? null : String(raw.target_ref),
    auth_repo: raw.auth_repo == null ? null : String(raw.auth_repo),
    priority,
    size,
    tags: Array.isArray(raw.tags) ? raw.tags.filter((tag): tag is string => typeof tag === 'string') : [],
    ticket_count: numberOf(raw.ticket_count),
    active_ticket_count: numberOf(raw.active_ticket_count),
    created_at: String(raw.created_at ?? ''),
    updated_at: String(raw.updated_at ?? ''),
  };
}

export async function browseWorkspace(path?: string): Promise<WorkspaceView> {
  const response = path
    ? await client.post<WorkspaceView>('/workspaces', { path })
    : await client.get<WorkspaceView>('/workspaces');
  const raw = response.data;
  return {
    path: raw.path,
    parent: raw.parent ?? null,
    exists: Boolean(raw.exists),
    directories: Array.isArray(raw.directories) ? raw.directories : [],
    roots: Array.isArray(raw.roots)
      ? raw.roots
          .filter((item): item is { name: string; path: string } =>
            Boolean(item) && typeof (item as { path?: unknown }).path === 'string')
          .map((item) => ({ name: String(item.name ?? item.path), path: item.path }))
      : [],
  };
}

export async function listProjects(): Promise<ProjectView[]> {
  const response = await client.get<{ projects?: unknown[] }>('/projects');
  return (response.data.projects ?? []).map(projectOf).filter((project) => project.id && project.workspace_path);
}

export interface CreateProjectRequest {
  name: string;
  workspacePath: string;
  initGit?: boolean;
  targetRef?: string;
}

export async function createProject(request: CreateProjectRequest): Promise<ProjectView> {
  const response = await client.post<unknown>('/projects', {
    name: request.name,
    workspace_path: request.workspacePath,
    ...(request.initGit != null ? { init_git: request.initGit } : {}),
    ...(request.targetRef ? { target_ref: request.targetRef } : {}),
  });
  return projectOf(response.data);
}

export interface UpdateProjectPayload {
  name?: string;
  workspacePath?: string;
  /** 显式 null 表示清除；undefined 表示不修改。 */
  priority?: ProjectPriority | null;
  size?: ProjectSize | null;
  tags?: string[];
}

export async function updateProject(id: string, payload: UpdateProjectPayload): Promise<ProjectView> {
  const body: Record<string, unknown> = {};
  if (payload.name != null) body.name = payload.name;
  if (payload.workspacePath != null) body.workspace_path = payload.workspacePath;
  if (payload.priority !== undefined) body.priority = payload.priority;
  if (payload.size !== undefined) body.size = payload.size;
  if (payload.tags !== undefined) body.tags = payload.tags;
  const response = await client.put<unknown>(`/projects/${encodeURIComponent(id)}`, body);
  return projectOf(response.data);
}

export async function deleteProject(id: string): Promise<void> {
  await client.delete(`/projects/${encodeURIComponent(id)}`);
}
