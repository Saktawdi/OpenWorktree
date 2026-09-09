/**
 * claude 任务清单域（claudeTasks）：会话任务 journal 的唯一属主模块（V24 平行链）。
 *
 * store 里的 `claudeTasks` 是「按会话 id 键控的内存投影」，事实源是后端 session_task
 * journal 表。与 todos（todowrite 全量快照）的关键差异：TaskCreate/TaskUpdate 是增量
 * 事件、id 由 claude 服务端分配——前端不本地推演，统一拉取服务端 journal（live 时
 * 适配器已按 max+1 乐观入行，终态重放后为权威 id），杜绝两端各自乐观导致的 id 漂移。
 * 对齐路径：
 *   1. SSE 工具事件触发（stream.ts）——TaskCreate/TaskUpdate 终态帧到达即拉一次；
 *   2. GET /messages 附带查询（api.ts loadSessionMessages）——切会话/刷新重建；
 *   3. GET /tasks 轻端点（busy.ts 回合结束兜底）——流式事件丢失后的最终一致。
 * 会话消亡经 dropSessionTasks 抹除投影，防止同 id 重建的会话静默继承残留。
 */
import { appStore } from "@/store";
import type { ClaudeTaskItem } from "@/shared/types";

/** 覆写某会话的任务清单投影（服务端 journal 是唯一事实源，全量替换）。 */
export function applyTasksSnapshot(sessionId: string, tasks: ClaudeTaskItem[]) {
  appStore.setState((st) => ({ claudeTasks: { ...st.claudeTasks, [sessionId]: tasks } }));
}

/** 会话消亡后的端侧抹除（删除/列表收敛路径共用）；空入参是 no-op。 */
export function dropSessionTasks(sessionIds: string[]) {
  if (sessionIds.length === 0) return;
  const kill = new Set(sessionIds);
  appStore.setState((st) => {
    const claudeTasks = { ...st.claudeTasks };
    let touched = false;
    for (const sid of kill) {
      if (sid in claudeTasks) {
        delete claudeTasks[sid];
        touched = true;
      }
    }
    return touched ? { claudeTasks } : st;
  });
}
