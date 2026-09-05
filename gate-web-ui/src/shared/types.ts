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
  /** 星标置顶：true 时排在项目列表最前 */
  starred: boolean;
  /** 手动拖拽顺序（1-based；0 = 未排，按名称兜底） */
  sortOrder: number;
  ticketCount: number;
  activeTicketCount: number;
  /** 该项目的快速模式超级工单号（V19；后端确保存在） */
  superTicketNo?: string | null;
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
  /** 已废弃（仅历史数据兼容）：协作 Agent 改为会话级 1:1，工单不再写入、界面不再展示。 */
  agentConfigId?: string | null;
  execTokenTotal: number;
  /** 重启次数（T-117）——demo 工单无此字段 */
  restartCount?: number;
  /** 状态变更记录总数（V19）：重启 + 强制已完成 + 取消 */
  stageChangeCount?: number;
  /** 快速模式超级工单（V19）：直连项目原工作区、永不关闭、不走门禁 */
  isSuper?: boolean;
  createdAt: string;
  updatedAt: string;
}

/** 一次工单状态变更记录（V19）：重启 / 强制已完成 / 取消，理由必填 */
export interface StageChangeRecord {
  round: number;
  fromStage: Stage;
  toStage: Stage;
  kind: "restart" | "force_complete" | "cancel";
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

/** opencode.json 里一个模型的完整配置（limit/modalities/variants/… 原样透传）。 */
export interface OpenCodeModelEntry {
  id: string;
  config: Record<string, unknown>;
}

/** OpenCode 配置文件（opencode.json(c)）provider 节点里的一条供应商（扁平视图）。 */
export interface OpenCodeProvider {
  /** provider 节点的键，同时也是 opencode 模型 id 的前缀（如 deepseek/deepseek-chat）。 */
  key: string;
  name: string;
  npm?: string | null;
  baseURL?: string | null;
  apiKey?: string | null;
  models: OpenCodeModelEntry[];
  modelCount: number;
}

/** GET /api/opencode/providers 的返回。 */
export interface OpenCodeProvidersView {
  configPath: string;
  configExists: boolean;
  providers: OpenCodeProvider[];
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

/** 项目所属的可用终端目录：工作区本体或某个工单的克隆目录。 */
export interface TerminalEntry {
  path: string;
  label: string;
  type: "workspace" | "clone";
  ticketNo: string | null;
  ticketTitle: string | null;
  exists: boolean;
}

/** 一个存活的终端会话（tab）：对应后端一个 shell 进程 + 一条 WebSocket。 */
export interface TerminalSessionMeta {
  id: string;
  projectId: string;
  projectName: string;
  dir: string;
  /** tab 标题：工作区 / 工单号 */
  label: string;
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
  /** 会话固化的协作 Agent（会话 1:1 agent，创建时选定；会话徽标/回复标注/展示位的数据源）。 */
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

/** POST /api/workspaces 的目录浏览结果（接入项目表单选目录用） */
export interface WorkspaceListing {
  path: string;
  parent: string | null;
  exists: boolean;
  /** 后端运行平台：linux / windows / mac，路径提示与默认值按它适配 */
  platform: string;
  /** 后端的用户家目录（linux 容器里即 /home/…） */
  userHome: string;
  roots: { name: string; path: string }[];
  directories: {
    name: string;
    path: string;
    isGitRepo: boolean;
    isRegisteredProject: boolean;
  }[];
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
  /** 未设置时输入框的 placeholder（如实描述运行期行为） */
  placeholder?: string;
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

export interface AppInfo {
  name: string;
  version: string;
  repo_owner: string;
  repo_name: string;
  repo_url: string;
  releases_url: string;
  download_url: string;
}

/**
 * up_to_date：远程 == 本地；update_available：远程 > 本地（跳下载页）；
 * ahead_beta：远程 < 本地（本地是先行 beta 构建，徽标展示）；
 * unpublished：远程仓库还没有任何已发行版本（未公开或零 Release/tag）——当前构建就是先行者；
 * unknown：任一侧无法比对或检查失败（网络不通等，error 带原因）。
 */
export type UpdateStatus = "up_to_date" | "update_available" | "ahead_beta" | "unpublished" | "unknown";

export interface UpdateCheck {
  ok: boolean;
  status: UpdateStatus;
  current_version: string;
  latest_version?: string | null;
  tag_name?: string | null;
  release_url?: string | null;
  published_at?: string | null;
  source?: "releases" | "tags" | null;
  error?: string | null;
}

/** 更新日志拉取应答：ok 时 content 为远程 CHANGELOG.md 中对应版本的小节（Markdown 原文）。 */
export interface UpdateNotes {
  ok: boolean;
  version: string;
  content?: string;
  error?: string;
}
