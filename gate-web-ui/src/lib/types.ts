export type Stage =
  | "PENDING"
  | "IN_PROGRESS"
  | "PRESUBMITTED"
  | "IN_REVIEW"
  | "REJECTED"
  | "READY_TO_PUBLISH"
  | "NEEDS_HUMAN"
  | "DONE"
  | "CANCELLED";

export type Priority = "P0" | "P1" | "P2" | "P3";

export type Severity = "BLOCKER" | "WARNING" | "NIT" | "INFO";

export type Verdict = "PASS" | "REJECT" | "REQUIRES_HUMAN";

export interface Project {
  id: string;
  name: string;
  workspacePath: string;
  targetRef: string;
  authRepo: string;
  priority: Priority | null;
  size: "small" | "medium" | "large" | null;
  tags: string[];
  ticketCount: number;
  activeTicketCount: number;
  createdAt: string;
  updatedAt: string;
}

export interface Ticket {
  ticketNo: string;
  title: string;
  stage: Stage;
  priority: Priority;
  projectId: string;
  labels: string[];
  description?: string;
  note?: string;
  targetRef: string;
  clonePath: string;
  agentConfigId?: string | null;
  execTokenTotal: number;
  createdAt: string;
  updatedAt: string;
}

export interface AgentConfig {
  id: string;
  name: string;
  cli: "claude" | "opencode";
  providerId: string | null;
  model: string;
  systemPrompt: string | null;
  extraFlags: string[];
  description: string | null;
  /** 会话启动时注入项目/工单上下文作为系统提示词；缺省开启 */
  injectContext: boolean;
}

export interface AgentRuntime {
  name: "claude" | "opencode";
  available: boolean;
  version?: string | null;
  note?: string | null;
  models: string[];
  modelSource: string;
}

export interface GitCommit {
  sha: string;
  parents: string[];
  message: string;
  author: string;
  time: string;
  refs: string[];
  lane: number;
}

export interface GitBranchInfo {
  name: string;
  tip: string;
  lane: number;
}

export interface GitRepoView {
  branches: GitBranchInfo[];
  commits: GitCommit[];
  /** 超过后端提交数上限（100）时为 true，用于“仅显示最近提交”提示。 */
  truncated?: boolean;
}

export interface GitTreeEntry {
  path: string;
  type: "dir" | "file";
  size?: number;
  lastCommitShort: string;
  lastMessage: string;
}

export type ToolStatus = "running" | "ok" | "error";

export interface ToolCallView {
  id: string;
  name: string;
  icon: "file" | "search" | "edit" | "terminal" | "test";
  argsSummary: string;
  resultSummary?: string;
  resultDetail?: string;
  status: ToolStatus;
}

export interface ThinkingView {
  text: string;
  startedAt: number;
  done: boolean;
}

export interface UsageView {
  promptTokens: number;
  completionTokens: number;
}

/** One pending permission request from the agent (opencode 权限询问). */
export interface PermissionRequestView {
  permissionId: string;
  permission: string;
  patterns: string[];
  always: string[];
  metadata: Record<string, unknown>;
  messageId?: string;
  callId?: string;
}

export type PermissionStatus = "pending" | "once" | "always" | "reject" | "auto";

export type ChatItem =
  | { kind: "user"; id: string; text: string; ts: number }
  | {
      kind: "assistant";
      id: string;
      text: string;
      streaming: boolean;
      thinking?: ThinkingView;
      tools: ToolCallView[];
      ts: number;
      /** 完成本回复的模型/智能体名称（openchamber 式底部标注）。 */
      agent?: string | null;
      /** 推理等级（reasoning-effort，如 high/medium/low）；可能为空。 */
      variant?: string | null;
    }
  | { kind: "system"; id: string; text: string; tone: "info" | "success" | "warn"; ts: number }
  | {
      kind: "permission";
      id: string;
      request: PermissionRequestView;
      status: PermissionStatus;
      ts: number;
    };

export interface ChatSession {
  id: string;
  ticketNo: string;
  title: string;
  status: "active" | "archived";
  createdAt: number;
  updatedAt: number;
  /** 会话是否自动允许权限请求（端侧开关，受控）。 */
  permissionAutoAccept: boolean;
  /** Backing agent-config id (raw API field, used to resolve picker defaults). */
  agentConfigId?: string | null;
  /** 会话内实时切换的模型覆盖（null = 用 AgentConfig 默认值） */
  overrideProvider?: string | null;
  overrideModel?: string | null;
  overrideVariant?: string | null;
}

/** One model entry of the live opencode catalog (GET /api/sessions/{id}/models). */
export interface CatalogModel {
  id: string;
  name: string;
  /** OpenCode reasoning-effort keys ("high"/"medium"/"low"/…); empty when the model has none. */
  variants: string[];
}

export interface CatalogProvider {
  id: string;
  name: string;
  models: CatalogModel[];
}

/** The effective per-session model selection (会话内实时切换). */
export interface SessionModelSel {
  providerId: string | null;
  modelId: string | null;
  variant: string | null;
}

export interface DiffLine {
  type: "add" | "del" | "ctx";
  oldNo?: number;
  newNo?: number;
  content: string;
}

export interface DiffHunk {
  header: string;
  lines: DiffLine[];
}

export interface DiffFile {
  path: string;
  status: "added" | "modified" | "deleted";
  additions: number;
  deletions: number;
  hunks: DiffHunk[];
}

export interface Finding {
  severity: Severity;
  path: string;
  lineStart?: number;
  lineEnd?: number;
  ruleId?: string;
  message: string;
  suggestion?: string;
}

export interface Snapshot {
  round: number;
  treeHash: string;
  baseCommit: string;
  targetRef: string;
  diffBytes: number;
  changedPaths: string[];
  changedCount?: number;
  capturedAt: number;
}

export interface VerdictInfo {
  verdict: Verdict;
  reason: string;
  engineId: string;
  round: number;
  authorizationId?: string;
}

export interface TaskProgress {
  kind: "presubmit" | "review" | "publish";
  percent: number;
  label: string;
  done: boolean;
  failed?: boolean;
}

export interface PublishOutcome {
  commitSha: string;
  refBefore: string;
  refAfter: string;
  targetRef: string;
  publishedAt: number;
}

export interface AgentConfigOption {
  id: string;
  name: string;
  model: string;
  cli: "claude" | "opencode";
}
