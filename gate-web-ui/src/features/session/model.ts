/**
 * 会话域（session）— 后端 wire 格式 ↔ 前端视图模型的映射器。
 * 全部为纯函数，不含状态副作用；供 api/catalog/permissions/stream 复用。
 */
import { getLocale, t as i18nT } from "@/i18n";
import type {
  CatalogProvider,
  ChatItem,
  ChatSession,
  OpenCodeProvider,
  PermissionRequestView,
  QuestionRequestView,
} from "@/shared/types";
import { friendlyToolName, isTodoTool, isClaudeTaskTool, parseTodos, todoArgsSummary as todoArgsSummaryImpl, compactToolArgs, compactToolResult } from "@/shared/todoUtils";
import { stripImageCitations } from "@/shared/attachments";

function sessionTimeLabel(at?: number): string {
  const tt = at == null ? new Date() : new Date(at);
  return i18nT("sess.defaultTitle", { time: tt.toLocaleTimeString(getLocale(), { hour: "2-digit", minute: "2-digit" }) });
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
  permission_mode?: string | null;
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
    permissionMode: s.permission_mode ?? null,
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
  /** V22 逐消息模型标注（上游实际值优先、请求值兜底）；存量行为 null。 */
  model_provider?: string | null;
  model_id?: string | null;
  /** V23 逐消息推理强度（发送端钉住的请求档位）；存量行/未选档位为 null。 */
  reasoning_variant?: string | null;
  timestamp: string;
}

export function resolveToolIcon(name: string): import("@/shared/types").ToolIconKind {
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

/** 后端 V20 时间线分段（GET /messages 的 parts 数组行格式）。 */
export interface RawTurnPart {
  type?: string;
  text?: string;
  name?: string;
  arguments_json?: string;
  result_json?: string | null;
}

function timelinePartIcon(toolName: string, todo: boolean): import("@/shared/types").ToolIconKind {
  return todo || isClaudeTaskTool(toolName) ? ("todo" as const) : resolveToolIcon(toolName);
}

/**
 * V20 时间线 → ChatItem.parts：按到达序重建思考/文本/工具分段。
 * 工具行同时并入 tools（兼容视图），时间线引用同一视图对象（共享渲染缓存）。
 */
function partsFromRaw(rawParts: RawTurnPart[] | undefined | null, tools: import("@/shared/types").ToolCallView[], msgId: string): import("@/shared/types").TimelinePart[] | undefined {
  if (!rawParts || rawParts.length === 0) return undefined;
  const out: import("@/shared/types").TimelinePart[] = [];
  let toolIdx = 0;
  for (const p of rawParts) {
    if (p.type === "tool") {
      const toolName = p.name || "unknown";
      const todo = isTodoTool(toolName);
      const argsJson = p.arguments_json ?? "";
      const tool: import("@/shared/types").ToolCallView = {
        id: `${msgId}-p${toolIdx++}`,
        name: friendlyToolName(toolName),
        toolName,
        args: argsJson,
        icon: timelinePartIcon(toolName, todo),
        argsSummary: todo ? todoArgsSummary(argsJson) : compactToolArgs(toolName, argsJson),
        resultSummary: compactToolResult(p.result_json),
        resultDetail: p.result_json ?? undefined,
        status: "ok",
      };
      tools.push(tool);
      out.push({ type: "tool", id: tool.id, name: toolName, arguments_json: argsJson, result_json: p.result_json ?? null, status: "ok", view: tool });
    } else if (p.type === "thinking") {
      if ((p.text ?? "").trim()) out.push({ type: "thinking", text: p.text ?? "" });
    } else if (p.type === "steer") {
      // T-107 渲染修复：插队段按原位渲染（name = 被吞并 USER 行 id）。
      out.push({ type: "steer", id: p.name || undefined, text: p.text ?? "" });
    } else {
      if ((p.text ?? "").trim()) out.push({ type: "text", text: p.text ?? "" });
    }
  }
  return out.length > 0 ? out : undefined;
}

/**
 * 历史用户消息 → 气泡数据：图片引用痕迹（后端 [图片引用 #n] <路径> 引用行、
 * [图片 #n] <文件名> 标记）按 token 剥离出 images 与干净正文。token 级剥离保证
 * 引用与正文同行（输入未换行的历史消息）时正文不被整行吞掉，且与实时气泡
 * （UserMessageBody 同一 stripImageCitations）渲染口径一致——重切入会话不再
 * 出现实时/历史两种显示。
 */
export function mapHistoryMessage(m: RawMessage): ChatItem | null {
  if (m.role === "USER") {
    const { body, images } = stripImageCitations(m.content);
    const item: ChatItem = { kind: "user", id: m.id, text: body, ts: Date.parse(m.timestamp) };
    if (images.length > 0) item.images = images;
    return item;
  }
  if (m.role === "ASSISTANT") {
    const tools: import("@/shared/types").ToolCallView[] = [];
    // V20：优先时间线（内部并入 tools）；旧行（无 parts）回退平铺映射。
    const parts = partsFromRaw((m as RawMessage & { parts?: RawTurnPart[] }).parts, tools, m.id);
    if (!parts) {
      (m.tool_calls ?? []).forEach((tc, i) => {
        const toolName = tc.name || "";
        const args = tc.arguments_json ?? "";
        const todo = isTodoTool(toolName);
        tools.push({
          id: `${m.id}-${i}`,
          name: friendlyToolName(toolName),
          toolName,
          args,
          icon: todo || isClaudeTaskTool(toolName) ? ("todo" as const) : resolveToolIcon(toolName),
          argsSummary: todo ? todoArgsSummary(args) : compactToolArgs(toolName, args),
          resultSummary: compactToolResult(tc.result_json),
          resultDetail: tc.result_json,
          status: "ok" as const,
        });
      });
    }
    return {
      kind: "assistant",
      id: m.id,
      text: m.content,
      streaming: false,
      tools,
      parts,
      endedAt: Date.parse(m.timestamp),
      ts: Date.parse(m.timestamp),
      // V22/V23 逐消息精确标注：行上有值就用它（模型=上游实际/请求兜底，档位=请求值），
      // 没有（旧行）保持 undefined，由 applyReplyMetaDefaults 用会话当前值近似兜底。
      model: m.role === "ASSISTANT" && m.model_id ? m.model_id : undefined,
      variant: m.role === "ASSISTANT" && m.reasoning_variant ? m.reasoning_variant : undefined,
    };
  }
  return null;
}

/** todo 类工具行参数列的简短摘要（避免整段 JSON 刷屏）——实现见 todoUtils。 */
export function todoArgsSummary(argsJson?: string): string {
  return todoArgsSummaryImpl(argsJson);
}

/** Splits an AgentConfig default model ref ("provider/model") into its halves. */
export function splitModelRef(model?: string | null): { provider: string | null; model: string | null } {
  if (!model || !model.trim()) return { provider: null, model: null };
  const slash = model.indexOf("/");
  if (slash <= 0 || slash >= model.length - 1) return { provider: null, model };
  return { provider: model.slice(0, slash), model: model.slice(slash + 1) };
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
