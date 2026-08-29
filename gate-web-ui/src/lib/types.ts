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
  /** 重启次数（T-117）——demo 工单无此字段 */
  restartCount?: number;
  createdAt: string;
  updatedAt: string;
}

/** 一次重启记录（T-117）：终态工单带回 IN_PROGRESS 的历史 */
export interface RestartRecord {
  round: number;
  fromStage: Stage;
  reason: string;
  createdAt: string | null;
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
  auth?: { repo: string | null; target_ref: string | null; tip: string | null };
}

export interface GitTreeEntry {
  path: string;
  type: "dir" | "file";
  size?: number;
  lastCommitShort: string;
  lastMessage: string;
}

export type ToolStatus = "running" | "ok" | "error";

export type ToolIconKind = "file" | "search" | "edit" | "terminal" | "test" | "code" | "question" | "web" | "custom" | "todo";

export interface ToolCallView {
  id: string;
  name: string;
  toolName?: string;
  args?: string;
  icon: ToolIconKind;
  argsSummary: string;
  resultSummary?: string;
  resultDetail?: string;
  status: ToolStatus;
}

/** One entry of the agent's task list (todowrite 工具的 todos 数组元素). */
export interface TodoItem {
  id?: string;
  content: string;
  status: "pending" | "in_progress" | "completed" | "cancelled";
  priority?: "high" | "medium" | "low";
}

/** 会话上下文占用（最新一轮的窗口占用，而非逐轮累加）。 */
export interface ContextUsageState {
  /** 最新已知的上下文 token 占用；0 表示尚无数据。 */
  tokens: number;
  /** 模型上下文窗口上限；null 表示模型未暴露（前端回退默认值）。 */
  limit: number | null;
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

/** One selectable choice of an agent question (opencode question 工具). */
export interface QuestionOptionView {
  label: string;
  description?: string;
}

/** One question shown to the user. */
export interface QuestionPromptView {
  question: string;
  header: string;
  options: QuestionOptionView[];
  /** 允许多选。 */
  multiple: boolean;
  /** 允许自定义输入（opencode 缺省为 true）。 */
  custom: boolean;
}

/** One pending question request from the agent (question 工具等待用户作答). */
export interface QuestionRequestView {
  requestId: string;
  questions: QuestionPromptView[];
  messageId?: string;
  callId?: string;
}

export type QuestionStatus = "pending" | "answered" | "rejected";

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
    }
  | {
      kind: "question";
      id: string;
      request: QuestionRequestView;
      status: QuestionStatus;
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
  /** 该模型是否支持图片输入（serve 归一化 capabilities.input.image，attachment 兜底）。 */
  imageInput: boolean;
  /** 模型上下文窗口上限（tokens）；null 表示模型未暴露。 */
  contextLimit: number | null;
  /** 输出上限（tokens）；null 表示模型未暴露。 */
  outputLimit: number | null;
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

/** Composer 里待发送的图片附件（data URL 仅用于本地预览）。 */
export interface PendingAttachment {
  id: string;
  filename: string;
  mime: string;
  dataBase64: string;
  dataUrl: string;
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
  /** true = 引擎未产出有效审查（超时/崩溃等基础设施故障）：可原地重试，不消耗轮次。 */
  degraded?: boolean;
}

/** 审查引擎配置（GET /api/config 的 engine 节）；null = 未配置，AI 审查不可用。 */
export interface EngineInfo {
  configured: boolean;
  providerId?: string | null;
  model?: string | null;
  /** 引擎单次审查的超时上限（秒）：进度页用于告知最长等待。 */
  timeoutSeconds?: number | null;
}

export interface TaskProgress {
  kind: "presubmit" | "review" | "publish";
  percent: number;
  label: string;
  done: boolean;
  failed?: boolean;
}

export interface WorkspaceSyncResult {
  project_id: string;
  status: "SYNCED" | "ALREADY" | "DEFERRED";
  note?: string | null;
  target_ref?: string | null;
  auth_tip?: string | null;
  workspace_tip_before?: string | null;
  workspace_tip_after?: string | null;
}

export interface PublishOutcome {
  commitSha: string;
  refBefore: string;
  refAfter: string;
  targetRef: string;
  publishedAt: number;
  workspaceSyncStatus?: string | null;
  workspaceSyncNote?: string | null;
}

export interface AgentConfigOption {
  id: string;
  name: string;
  model: string;
  cli: "claude" | "opencode";
}

/* ── 设置中心 ── */

export type GateTomlType = "int" | "bool" | "string" | "string_list";

export interface GateTomlOption {
  value: string;
  label: string;
}

export interface GateTomlKey {
  key: string;
  value: unknown;
  type: GateTomlType;
  editable: boolean;
  default: unknown;
  /** 有枚举取值的键：渲染为下拉选择而非自由输入 */
  options?: GateTomlOption[];
  /** 数值范围约束（含端点）；只有 min/max 单边约束时另一侧缺省 */
  min?: number;
  max?: number;
  /** 人性化说明文字（约束、默认行为） */
  hint?: string;
}

export interface GateTomlSection {
  section: string;
  title: string;
  keys: GateTomlKey[];
}

export interface GateTomlResponse {
  toml_path: string;
  restart_required: boolean;
  sections: GateTomlSection[];
}

export interface McpStatus {
  provisioning: string;
  transport: string;
  serve_command: string;
  cli_integration: Array<{ cli: string; mechanism: string }>;
  tools: Array<{ name: string; domain: string; description: string }>;
  agent_tool_count: number;
  human_tool_count: number;
}

export interface LlmProvider {
  id: string;
  name: string;
  base_url: string;
  type: string;
  credential_configured: boolean;
  model_count: number;
  models: string[];
}
