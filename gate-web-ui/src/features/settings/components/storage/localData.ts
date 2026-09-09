/**
 * 本地数据管理（存储设置·T-116）：排队消息 / 输入框草稿 / 引用片段 / 小助手历史等
 * 端侧软数据（localStorage）的占用估算与一键清空。
 *
 * 数据不落库后端，全部经 store/prefs.ts 的键集中读写；这里只读原始键做统计，
 * 清空动作直接调用各域已有的 setter（保持 store 状态与落盘同步），绝不绕过
 * store 直接 removeItem。
 */
import { appStore } from "@/store";
import { saveAssistantHistory, saveComposerDrafts, savePendingQuotes, saveQueuedMessages } from "@/store/prefs";
import { clearAllQueuedMessages } from "@/features/session";

export interface LocalDataEntry {
  id: "queued_messages" | "composer_drafts" | "pending_quotes" | "assistant_history";
  label: string;
  hint: string;
  /** 占用字节数（JSON 序列化估算）。 */
  bytes: number;
  /** 条目数（排队条数 / 草稿份数 / 胶囊数 / 气泡数），无计数语义的为 null。 */
  count: number | null;
}

function blobSize(value: unknown): number {
  try {
    return new Blob([JSON.stringify(value)]).size;
  } catch {
    return 0;
  }
}

/** 占用估算：空容器（{} / []）返回 0 —— JSON 序列化底噪（"{}"/"[]" 各 2 字节）不得算作占用。 */
function byteLengthOf(value: unknown): number {
  if (value == null) return 0;
  if (Array.isArray(value)) return value.length === 0 ? 0 : blobSize(value);
  if (typeof value === "object" && Object.keys(value).length === 0) return 0;
  return blobSize(value);
}

/** 四类端侧软数据的实时占用快照（读 store 状态而非重读 localStorage，口径一致）。 */
export function collectLocalData(): LocalDataEntry[] {
  const st = appStore.getState();
  const queuedLists = Object.values(st.queuedMessages ?? {});
  const quoteLists = Object.values(st.pendingQuotes ?? {});
  return [
    {
      id: "queued_messages",
      label: "排队消息",
      hint: "按会话隔离的待发送消息（含图片附件），Agent 空闲后自动发出",
      bytes: byteLengthOf(st.queuedMessages ?? {}),
      count: queuedLists.reduce((n, list) => n + list.length, 0),
    },
    {
      id: "composer_drafts",
      label: "输入框草稿",
      hint: "各工单输入框的自动保存草稿，切换页面后还原",
      bytes: byteLengthOf(st.composerDrafts ?? {}),
      count: Object.keys(st.composerDrafts ?? {}).length,
    },
    {
      id: "pending_quotes",
      label: "引用片段",
      hint: "划选页面文字「添加到对话框」暂存的胶囊，随下一条消息发送",
      bytes: byteLengthOf(st.pendingQuotes ?? {}),
      count: quoteLists.reduce((n, list) => n + list.length, 0),
    },
    {
      id: "assistant_history",
      label: "小助手对话历史",
      hint: "LLM 小助手面板的本地会话气泡（不上传后端）",
      bytes: byteLengthOf(st.assistantMessages ?? []),
      count: (st.assistantMessages ?? []).length,
    },
  ];
}

/**
 * 单类清空：仅清指定类别，走各域既有 setter（store 状态 + localStorage 同步清）。
 * 返回清除的条目数（0 = 该类当前为空；调用方负责确认与 toast）。
 */
export function clearLocalDataCategory(id: LocalDataEntry["id"]): number {
  const st = appStore.getState();
  switch (id) {
    case "queued_messages": {
      const count = Object.values(st.queuedMessages ?? {}).reduce((n, list) => n + list.length, 0);
      if (count === 0) return 0;
      return clearAllQueuedMessages();
    }
    case "composer_drafts": {
      const count = Object.keys(st.composerDrafts ?? {}).length;
      if (count === 0) return 0;
      saveComposerDrafts({});
      appStore.setState({ composerDrafts: {} });
      return count;
    }
    case "pending_quotes": {
      const count = Object.values(st.pendingQuotes ?? {}).reduce((n, list) => n + list.length, 0);
      if (count === 0) return 0;
      savePendingQuotes({});
      appStore.setState({ pendingQuotes: {} });
      return count;
    }
    case "assistant_history": {
      const count = (st.assistantMessages ?? []).length;
      if (count === 0) return 0;
      saveAssistantHistory([]);
      appStore.setState({ assistantMessages: [] });
      return count;
    }
  }
}

/**
 * 一键清空全部端侧软数据：逐类走单类清空（store 状态 + localStorage 同步清）。
 * 返回清空的类目数（≥0；调用方负责确认与 toast）。
 */
export function clearAllLocalData(): number {
  const ids: LocalDataEntry["id"][] = [
    "queued_messages",
    "composer_drafts",
    "pending_quotes",
    "assistant_history",
  ];
  let touched = 0;
  for (const id of ids) {
    if (clearLocalDataCategory(id) > 0) touched++;
  }
  return touched;
}
