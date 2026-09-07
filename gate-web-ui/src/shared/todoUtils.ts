import type { TodoItem } from "@/shared/types";

const TODO_STATUSES: TodoItem["status"][] = ["pending", "in_progress", "completed", "cancelled"];

/**
 * 解析 todowrite / todoread 工具参数或结果里的 todos 数组。
 * 兼容三种载体：直接数组、{todos: [...]}、结果 JSON 里嵌套数组。
 * 非法输入返回 null（调用方保持现有清单不变）；空数组是合法输入（= 显式清空），
 * 返回 [] 而非 null——「清空」与「解析失败」语义在此分野（V21 任务清单重构）。
 */
export function parseTodos(raw: string | undefined | null): TodoItem[] | null {
  if (!raw) return null;
  try {
    let parsed: unknown = JSON.parse(raw);
    if (parsed && typeof parsed === "object" && !Array.isArray(parsed)) {
      const obj = parsed as Record<string, unknown>;
      parsed = obj.todos ?? obj.items ?? obj.result ?? obj.data;
    }
    if (typeof parsed === "string") {
      try {
        parsed = JSON.parse(parsed);
      } catch {
        return null;
      }
    }
    if (!Array.isArray(parsed)) return null;
    const todos: TodoItem[] = [];
    for (const entry of parsed) {
      if (!entry || typeof entry !== "object") continue;
      const e = entry as Record<string, unknown>;
      const content = typeof e.content === "string" ? e.content : typeof e.text === "string" ? e.text : null;
      if (!content) continue;
      const status = TODO_STATUSES.includes(e.status as TodoItem["status"])
        ? (e.status as TodoItem["status"])
        : "pending";
      const priority =
        e.priority === "high" || e.priority === "medium" || e.priority === "low"
          ? (e.priority as TodoItem["priority"])
          : undefined;
      const id = typeof e.id === "string" ? e.id : undefined;
      todos.push({ id, content, status, priority });
    }
    // 条目全非法的非空数组按残缺输入处理（不覆盖现有清单）；空数组本身是显式清空。
    if (parsed.length > 0 && todos.length === 0) return null;
    return todos;
  } catch {
    return null;
  }
}

export interface TodoProgress {
  total: number;
  completed: number;
  inProgress: number;
  pending: number;
  cancelled: number;
  percent: number;
}

export function todoProgress(todos: TodoItem[]): TodoProgress {
  const total = todos.length;
  const completed = todos.filter((t) => t.status === "completed").length;
  const inProgress = todos.filter((t) => t.status === "in_progress").length;
  const pending = todos.filter((t) => t.status === "pending").length;
  const cancelled = todos.filter((t) => t.status === "cancelled").length;
  const denominator = total - cancelled;
  return {
    total,
    completed,
    inProgress,
    pending,
    cancelled,
    percent: denominator > 0 ? (completed / denominator) * 100 : total > 0 ? 100 : 0,
  };
}

/** todowrite / todoread 工具名识别（不同 CLI 命名略有差异）。 */
export function isTodoTool(name: string | undefined | null): boolean {
  if (!name) return false;
  const n = name.toLowerCase();
  return n.includes("todowrite") || n.includes("todoread") || n === "todo_write" || n === "todo_read";
}

/** 常见工具的中文友好名（仅用于展示，未匹配时返回原名）。 */
export function friendlyToolName(name: string | undefined): string {
  if (!name) return "工具调用";
  const n = name.toLowerCase();
  if (n.includes("todowrite") || n === "todo_write") return "任务清单";
  if (n.includes("todoread") || n === "todo_read") return "读取清单";
  if (n === "bash" || n === "shell") return "运行命令";
  if (n === "read" || n === "view") return "读取文件";
  if (n === "edit" || n === "multiedit" || n === "apply_patch") return "编辑文件";
  if (n === "write" || n === "create") return "写入文件";
  if (n === "glob" || n === "ls" || n === "list") return "列出文件";
  if (n === "grep" || n === "search") return "全局搜索";
  if (n === "webfetch" || n === "fetch") return "抓取网页";
  if (n === "websearch" || n === "search_web") return "网络搜索";
  if (n === "task") return "子任务";
  return name;
}

/** todo 类工具行参数列的简短摘要（避免整段 JSON 刷屏）。 */
export function todoArgsSummary(argsJson?: string): string {
  const todos = parseTodos(argsJson);
  if (!todos) return "任务清单";
  const done = todos.filter((t) => t.status === "completed").length;
  return `${todos.length} 项任务 · 已完成 ${done}`;
}

const COMPACT_MAX = 96;

function truncate(text: string, max = COMPACT_MAX): string {
  const oneLine = text.replace(/\s+/g, " ").trim();
  return oneLine.length > max ? oneLine.slice(0, max - 1) + "…" : oneLine;
}

/**
 * 工具行参数列的紧凑摘要（ZCode 式单行）：bash 只留命令原文，read/edit 留路径，
 * grep/glob 留 pattern——完整 JSON 仍保留在展开的 IN 区。解析失败回退单行截断。
 */
export function compactToolArgs(toolName: string | undefined, argsJson: string | undefined): string {
  const args = (argsJson ?? "").trim();
  if (!args) return "";
  const n = (toolName ?? "").toLowerCase();
  let o: Record<string, unknown> | null = null;
  try {
    const parsed = JSON.parse(args);
    if (parsed && typeof parsed === "object" && !Array.isArray(parsed)) {
      o = parsed as Record<string, unknown>;
    }
  } catch {
    o = null;
  }
  const str = (k: string): string => {
    const v = o?.[k];
    return typeof v === "string" ? v : "";
  };
  if (o) {
    if (n === "bash" || n === "shell" || n.includes("exec") || n.includes("command")) {
      const cmd = str("command") || str("cmd");
      if (cmd) return truncate(cmd);
    }
    if (n.includes("grep") || n.includes("search")) {
      const pattern = str("pattern") || str("query") || str("regex");
      if (pattern) return truncate(pattern);
    }
    if (n.includes("webfetch") || n.includes("fetch") || n.includes("url")) {
      const url = str("url");
      if (url) return truncate(url);
    }
    if (n.includes("task")) {
      const desc = str("description") || str("prompt");
      if (desc) return truncate(desc);
    }
    // 路径族：read/edit/write/glob/ls 等以文件为主体的工具
    const path = str("file_path") || str("path") || str("notebook_path") || str("dir");
    if (path) {
      const pattern = str("pattern");
      const include = str("include");
      const extra = pattern || include;
      return truncate(extra ? `${path} · ${extra}` : path);
    }
    // 无匹配字段：取第一个字符串值兜底，仍比整段 JSON 可读
    for (const v of Object.values(o)) {
      if (typeof v === "string" && v.trim()) return truncate(v);
    }
  }
  return truncate(args);
}

/**
 * 工具行右侧结果提示：仅当输出首行足够短（ZCode 式 "No files found"/"Found 27 matches"），
 * 多行长输出不预告（展开看 OUT）。
 */
export function compactToolResult(resultJson: string | undefined | null): string | undefined {
  const raw = (resultJson ?? "").trim();
  if (!raw) return undefined;
  const firstLine = raw.split("\n", 1)[0] ?? raw;
  const oneLine = firstLine.replace(/\s+/g, " ").trim();
  if (!oneLine || oneLine.length > 72) return undefined;
  return oneLine.length < raw.length ? oneLine + "…" : oneLine;
}
