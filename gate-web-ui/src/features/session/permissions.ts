/**
 * 会话域权限与提问（session）：opencode 的 permission_asked / question_asked
 * 的恢复拉取与应答提交。实时事件由 stream.ts 消费，这里负责 REST 侧。
 */
import { api } from "@/net";
import { showToast } from "@/store";
import { mapPermissionAsk, mapQuestionAsk, type PermissionResponse, type RawPermissionAsk, type RawQuestionAsk } from "./model";
import { notePendingPermission, notePendingQuestion } from "./state";
import { pushPermissionRequest, pushQuestionRequest } from "./chat";

/** 应答一次权限请求：POST /api/sessions/{sid}/permissions/{pid}，body {response}。 */
export async function answerSessionPermission(
  sessionId: string,
  permissionId: string,
  response: PermissionResponse,
): Promise<boolean> {
  try {
    await api(`/api/sessions/${sessionId}/permissions/${permissionId}`, {
      method: "POST",
      body: JSON.stringify({ response }),
    });
    return true;
  } catch (e) {
    showToast(`权限应答失败：${(e as Error).message}`);
    return false;
  }
}

/**
 * 恢复未决的权限卡片（页面刷新后，SSE 不会回放已经过去的 permission_asked）。
 * GET /api/sessions/{sessionId}/permissions → 每条 pending 推入 store，
 * pushPermissionRequest 内部按 permissionId 去重，已存在同 id 的会跳过。
 *
 * 取舍说明：刷新场景下如果当时没有挂着的 EventSource，用户应答后这里不再主动
 * 重挂 ESL 事件流——后端会把应答/后续消息持久化，下一次拉历史即可看到，避免为了
 * 恢复实时流额外引入一整套会话续接逻辑；实时应答仍由已挂着的 consumeSessionStream
 * 通过 permission_replied 事件即时反馈。
 */
export async function loadSessionPermissions(no: string, sessionId: string) {
  try {
    const data = await api<{ permissions: RawPermissionAsk[] }>(
      `/api/sessions/${sessionId}/permissions`,
    );
    for (const p of data.permissions ?? []) {
      if (p.permission_id) notePendingPermission(p.permission_id, no, sessionId);
      pushPermissionRequest(no, mapPermissionAsk(p));
    }
  } catch {
    /* 权限恢复失败不阻断会话打开 */
  }
}

/** 提交一次提问回答：POST /api/sessions/{sid}/questions/{rid}/reply，body {answers}。 */
export async function answerSessionQuestion(
  sessionId: string,
  requestId: string,
  answers: string[][],
): Promise<boolean> {
  try {
    await api(`/api/sessions/${sessionId}/questions/${encodeURIComponent(requestId)}/reply`, {
      method: "POST",
      body: JSON.stringify({ answers }),
    });
    return true;
  } catch (e) {
    showToast(`回答提交失败：${(e as Error).message}`);
    return false;
  }
}

/** 跳过一次提问：POST /api/sessions/{sid}/questions/{rid}/reject。 */
export async function rejectSessionQuestion(sessionId: string, requestId: string): Promise<boolean> {
  try {
    await api(`/api/sessions/${sessionId}/questions/${encodeURIComponent(requestId)}/reject`, {
      method: "POST",
      body: "{}",
    });
    return true;
  } catch (e) {
    showToast(`跳过失败：${(e as Error).message}`);
    return false;
  }
}

/** 恢复未决的提问卡片（页面刷新后 SSE 不会回放已经过去的 question_asked）。 */
export async function loadSessionQuestions(no: string, sessionId: string) {
  try {
    const data = await api<{ questions: RawQuestionAsk[] }>(
      `/api/sessions/${sessionId}/questions`,
    );
    for (const q of data.questions ?? []) {
      if (q.request_id) {
        notePendingQuestion(q.request_id, no, sessionId);
        pushQuestionRequest(no, mapQuestionAsk(q));
      }
    }
  } catch {
    /* 提问恢复失败不阻断会话打开 */
  }
}
