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
}

export interface Ticket {
  ticketNo: string;
  title: string;
  stage: Stage;
  priority: Priority;
  projectId: string;
  labels: string[];
  description?: string;
  agentName?: string;
  createdAt: string;
  updatedAt: string;
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
    }
  | { kind: "system"; id: string; text: string; tone: "info" | "success" | "warn"; ts: number };

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
