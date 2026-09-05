/**
 * 会话域（session）— 后端 wire 格式 ↔ 前端视图模型的映射器。
 * 全部为纯函数，不含状态副作用；供 api/catalog/permissions/stream 复用。
 */
import type {
  CatalogProvider,
  ChatItem,
  ChatSession,
  OpenCodeProvider,
  PermissionRequestView,
  QuestionRequestView,
} from "@/shared/types";
import { friendlyToolName, isTodoTool, parseTodos } from "@/shared/todoUtils";

function sessionTimeLabel(at?: number): string {
  const t = at == null ? new Date() : new Date(at);
  return `会话 ${t.toLocaleTimeString("zh-CN", { hour: "2-digit", minute: "2-digit" })}`;
}

export interface RawSession {
  id: string;
  ticket_no?: string;
  agent_config_id?: string | null;
  cli?: string | null;
  status?: string | null;
  title?: string | null;
  archived?: boolean | null;
  started_at?: string | null;
  updated_at?: string | null;
  override_provider?: string | null;
  override_model?: string | null;
  override_variant?: string | null;
  permission_auto_accept?: boolean | null;
}

export function mapSession(no: string, s: RawSession): ChatSession {
  const createdAt = s.started_at ? Date.parse(s.started_at) : Date.now();
  return {
    id: s.id,
    ticketNo: s.ticket_no ?? no,
    title: s.title ?? sessionTimeLabel(createdAt),
    status: s.archived ? "archived" : "active",
    createdAt,
    updatedAt: s.updated_at ? Date.parse(s.updated_at) : createdAt,
    permissionAutoAccept: s.permission_auto_accept ?? false,
    agentConfigId: s.agent_config_id ?? null,
    overrideProvider: s.override_provider ?? null,
    overrideModel: s.override_model ?? null,
    overrideVariant: s.override_variant ?? null,
  };
}

/**
 * 草稿态的 opencode 模型目录：直接从 opencode 配置文件（GET /api/opencode/providers）
 * 构建，与 serve 目录同源 —— 无需先建会话即可浏览/选择模型与推理档位。
 * claude 会话不走这里（ModelPicker 用内置预设 + 自定义输入，本就不依赖目录）。
 */
export function draftCatalogFromOc(ocProviders: OpenCodeProvider[]): CatalogProvider[] {
  return ocProviders.map((p) => ({
    id: p.key,
    name: p.name || p.key,
    models: p.models.map((m) => {
      const raw = (m.config ?? {}).variants;
      const variants =
        raw && typeof raw === "object" && !Array.isArray(raw)
          ? Object.keys(raw).sort((a, b) => a.localeCompare(b))
          : Array.isArray(raw)
            ? raw.map(String)
            : [];
      return {
        id: m.id,
        name: m.id,
        variants,
        imageInput: false,
        contextLimit: null,
        outputLimit: null,
      };
    }),
  }));
}

/* ─── 权限询问（opencode） ─── */

export type PermissionResponse = "once" | "always" | "reject";

export interface RawPermissionAsk {
  session_id?: string;
  timestamp?: string;
  permission_id: string;
  permission?: string;
  patterns?: string[];
  always?: string[];
  metadata?: Record<string, unknown>;
  message_id?: string | null;
  call_id?: string | null;
}

export function mapPermissionAsk(p: RawPermissionAsk): PermissionRequestView {
  return {
    permissionId: p.permission_id,
    permission: p.permission ?? "",
    patterns: p.patterns ?? [],
    always: p.always ?? [],
    metadata: p.metadata ?? {},
    messageId: p.message_id ?? undefined,
    callId: p.call_id ?? undefined,
  };
}

/* ─── 智能体提问（opencode question 工具） ─── */

export interface RawQuestionAsk {
  request_id?: string;
  questions?: Array<{
    question?: string;
    header?: string;
    options?: Array<{ label?: string; description?: string }>;
    multiple?: boolean;
    custom?: boolean;
  }>;
  message_id?: string | null;
  call_id?: string | null;
}

export function mapQuestionAsk(q: RawQuestionAsk): QuestionRequestView {
  return {
    requestId: q.request_id ?? "",
    questions: (q.questions ?? []).map((x) => ({
      question: x.question ?? "",
      header: x.header ?? "",
      options: (x.options ?? []).map((o) => ({ label: o.label ?? "", description: o.description })),
      multiple: x.multiple === true,
      // opencode 缺省允许自定义输入；仅显式 false 时关闭
      custom: x.custom !== false,
    })),
    messageId: q.message_id ?? undefined,
    callId: q.call_id ?? undefined,
  };
}

/* ─── 历史消息（GET /messages 的行格式） ─── */

export interface RawMessage {
  id: string;
  role: string;
  content: string;
  tool_calls?: Array<{ name: string; arguments_json?: string; result_json?: string }>;
  usage?: { prompt_tokens: number; completion_tokens: number } | null;
  timestamp: string;
}

function resolveToolIcon(name: string): import("@/shared/types").ToolIconKind {
  const n = name.toLowerCase().trim();
  if (n.includes("bash") || n.includes("exec") || n.includes("shell") || n.includes("terminal") || n.includes("cmd")) {
    return "terminal";
  }
  if (n.includes("read") || n.includes("view") || n.includes("cat") || n.includes("get_file") || n.includes("load")) {
    return "file";
  }
  if (n.includes("edit") || n.includes("write") || n.includes("patch") || n.includes("multiedit") || n.includes("create") || n.includes("modify")) {
    return "edit";
  }
  if (n.includes("grep") || n.includes("glob") || n.includes("search") || n.includes("find") || n.includes("locate")) {
    return "search";
  }
  if (n.includes("test")) {
    return "test";
  }
  if (n.includes("web") || n.includes("fetch") || n.includes("http") || n.includes("browser")) {
    return "web";
  }
  if (n.includes("ask") || n.includes("question") || n.includes("prompt")) {
    return "question";
  }
  return "terminal";
}

export function mapHistoryMessage(m: RawMessage): ChatItem | null {
  if (m.role === "USER") {
    return { kind: "user", id: m.id, text: m.content, ts: Date.parse(m.timestamp) };
  }
  if (m.role === "ASSISTANT") {
    return {
      kind: "assistant",
      id: m.id,
      text: m.content,
      streaming: false,
      tools: (m.tool_calls ?? []).map((tc, i) => {
        const toolName = tc.name || "";
        const args = tc.arguments_json ?? "";
        const todo = isTodoTool(toolName);
        return {
          id: `${m.id}-${i}`,
          name: friendlyToolName(toolName),
          toolName,
          args,
          icon: todo ? ("todo" as const) : resolveToolIcon(toolName),
          // Match the live-streamed row: tool name followed by its full arguments JSON
          // (todo rows use the compact summary instead of the full todos JSON).
          argsSummary: todo ? todoArgsSummary(args) : `${toolName}${args}`,
          resultSummary: tc.result_json?.slice(0, 80),
          resultDetail: tc.result_json,
          status: "ok" as const,
        };
      }),
      ts: Date.parse(m.timestamp),
    };
  }
  return null;
}

/** todo 类工具行参数列的简短摘要（避免整段 JSON 刷屏）。 */
export function todoArgsSummary(argsJson?: string): string {
  const todos = parseTodos(argsJson);
  if (!todos) return "任务清单";
  const done = todos.filter((t) => t.status === "completed").length;
  return `${todos.length} 项任务 · 已完成 ${done}`;
}

/* ─── 模型目录（会话 serve 的 /models 行格式） ─── */

export interface RawCatalogModel {
  id: string;
  name?: string | null;
  variants?: string[] | null;
  image_input?: boolean | null;
  limit?: { context?: unknown; output?: unknown } | null;
}

export interface RawCatalogProvider {
  id: string;
  name?: string | null;
  models?: RawCatalogModel[] | null;
}

export function numericLimit(value: unknown): number | null {
  return typeof value === "number" && Number.isFinite(value) && value > 0 ? value : null;
}
