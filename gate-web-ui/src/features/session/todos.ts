/**
 * 任务清单域（todos）：会话任务清单的唯一属主模块（V21 重构）。
 *
 * store 里的 `todos` 是「按会话 id 键控的内存投影」，事实源是后端 session_todo
 * 快照表。三条对齐路径全部收敛到 applyTodosSnapshot：
 *   1. SSE todowrite write-through（stream.ts）——实时性，不等落库；
 *   2. GET /messages 附带查询（api.ts loadSessionMessages）——切会话/刷新重建；
 *   3. GET /todos 轻端点（busy.ts 回合结束兜底）——流式事件丢失后的最终一致。
 * 会话消亡（live/demo 删除、列表收敛发现 gone 会话）经 dropSessionTodos 抹除投影，
 * 防止同 id 重建的会话静默继承残留。键位是 sessionId：串会话在数据结构上不可能发生。
 */
import { appStore } from "@/store";
import type { TodoItem } from "@/shared/types";

/** 覆写某会话的任务清单投影（todowrite 每次调用都是全量数组；空数组 = 显式清空）。 */
export function applyTodosSnapshot(sessionId: string, todos: TodoItem[]) {
  appStore.setState((st) => ({ todos: { ...st.todos, [sessionId]: todos } }));
}

/** 会话消亡后的端侧抹除（删除/列表收敛路径共用）；空入参是 no-op。 */
export function dropSessionTodos(sessionIds: string[]) {
  if (sessionIds.length === 0) return;
  const kill = new Set(sessionIds);
  appStore.setState((st) => {
    const todos = { ...st.todos };
    let touched = false;
    for (const sid of kill) {
      if (sid in todos) {
        delete todos[sid];
        touched = true;
      }
    }
    return touched ? { todos } : st;
  });
}
