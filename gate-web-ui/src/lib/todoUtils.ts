import type { TodoItem } from "./types";

const TODO_STATUSES: TodoItem["status"][] = ["pending", "in_progress", "completed", "cancelled"];

/**
 * 解析 todowrite / todoread 工具参数或结果里的 todos 数组。
 * 兼容三种载体：直接数组、{todos: [...]}、结果 JSON 里嵌套数组。
 * 非法输入返回 null（调用方保持现有清单不变）。
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
    return todos.length > 0 ? todos : null;
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
