/**
 * 门禁域流程（gate flows）：预提审（锁定快照）、基座同步、审查（AI/人工）与发布。
 * 都是对后端异步任务（/presubmit、/review、/publish）的编排 + 进度回写。
 */
import { api, pollTask } from "@/net";
import { setCenterTab, showToast } from "@/store/ui";
import { sleep } from "@/shared/format";
import { refreshTicket } from "@/features/ticket/api";
import { setStage } from "@/features/ticket/state";
import { pushSystemMessage } from "@/features/session/chat";
import { addSnapshot, setFindings, setGateBusy, setOutcome, setReviewError, setTask, setVerdict } from "./state";
import { loadReviewState } from "./api";

export async function livePresubmit(no: string) {
  setGateBusy(no, true);
  setFindings(no, []);
  setVerdict(no, null);
  try {
    setTask(no, { kind: "presubmit", percent: 50, label: "正在锁定快照", done: false });
    const r = await api<{
      ticket_no: string;
      review_round: number;
      tree_hash: string;
      base_commit: string;
      target_ref: string;
      diff_bytes: number;
      changed_paths: string[];
    }>(`/api/tickets/${no}/presubmit`, { method: "POST", body: "{}" });
    addSnapshot(no, {
      round: r.review_round,
      treeHash: r.tree_hash,
      baseCommit: r.base_commit,
      targetRef: r.target_ref,
      diffBytes: r.diff_bytes,
      changedPaths: r.changed_paths,
      capturedAt: Date.now(),
    });
    await refreshTicket(no);
    pushSystemMessage(no, `第 ${r.review_round} 轮快照已锁定 · 指纹 ${r.tree_hash.slice(0, 10)}…`, "success");
  } catch (e) {
    showToast(`预提审失败：${(e as Error).message}`);
  } finally {
    setTask(no, null);
    setGateBusy(no, false);
  }
}

/**
 * T-118 基座同步：先把源仓库（注册工作区）的基分支导入权威镜像，再把工单 clone 与权威分支
 * 快进到基分支最新 tip，未提交改动 stash 后原样重放。
 */
export async function liveSyncBase(no: string) {
  setGateBusy(no, true);
  try {
    const r = await api<{
      ticket_no: string;
      status: "synced" | "healed" | "replanted" | "up_to_date" | "skipped";
      behind: number;
      from_tip: string | null;
      to_tip: string | null;
      branch_moved: boolean;
      conflicts: string[];
      stash_kept: boolean;
      skipped_reason?: string;
      import_kind?: "up_to_date" | "fast_forwarded" | "adopted" | "skipped";
      import_reason?: string;
    }>(`/api/tickets/${no}/sync-base`, { method: "POST", body: JSON.stringify({ allow_dirty: true }) });
    await refreshTicket(no);
    if (r.status === "skipped") {
      showToast(`基座同步已跳过：${r.skipped_reason ?? "未知原因"}`);
    } else if (r.import_kind === "skipped" && r.import_reason) {
      // 基线本身没动（多为源仓库历史分叉等需人工处理的情况），必须可见，不能静默。
      pushSystemMessage(no, `基座同步完成，但源仓库基线未导入：${r.import_reason}`, "warn");
    } else if (r.import_kind === "fast_forwarded" || r.import_kind === "adopted") {
      pushSystemMessage(
        no,
        r.import_kind === "adopted"
          ? "已从源仓库采纳基线并同步工单基座：源仓库历史已进入克隆目录"
          : `已从源仓库导入基线并同步工单基座（前进 ${r.behind} 个提交）`,
        "success",
      );
    } else if (r.status === "up_to_date") {
      pushSystemMessage(no, "基座已是最新，无需同步", "info");
    } else if (r.status === "replanted") {
      pushSystemMessage(no, "工单分支已重锚到源仓库基线，未提交改动已原样保留", "success");
    } else if (r.conflicts.length > 0) {
      pushSystemMessage(
        no,
        `基座已同步（前进 ${r.behind} 个提交），重放你的改动时出现冲突：${r.conflicts.join("、")}。请在沙箱中解决冲突标记后继续编码。`,
        "warn",
      );
    } else {
      pushSystemMessage(
        no,
        `基座已同步：工单分支快进 ${r.behind} 个提交，未提交改动已原样保留` +
          (r.stash_kept ? "（部分改动仍留在 stash 中）" : ""),
        "success",
      );
    }
  } catch (e) {
    showToast(`基座同步失败：${(e as Error).message}`);
  } finally {
    setGateBusy(no, false);
  }
}

export async function liveReview(no: string, opts?: { humanPass?: boolean; note?: string }) {
  setGateBusy(no, true);
  setStage(no, "IN_REVIEW");
  setReviewError(no, null);
  try {
    setTask(no, {
      kind: "review",
      percent: 30,
      label: opts ? "正在提交人工判决" : "引擎执行中",
      done: false,
    });
    const body: Record<string, unknown> = {};
    if (opts) {
      if (opts.humanPass !== undefined) body.human_pass = opts.humanPass;
      if (opts.note !== undefined) body.note = opts.note;
    }
    const { task_id } = await api<{ task_id: string }>(`/api/tickets/${no}/review`, {
      method: "POST",
      body: JSON.stringify(body),
    });
    // 轮询期间同步进度到审查发现页：用户被自动切到该页后能看到推进而不是"无事发生"。
    const outcome = await pollTask(task_id, (percent, label) => {
      setTask(no, { kind: "review", percent, label, done: false });
    });
    if (!outcome.ok) {
      // 失败原因必须可见：审查发现页挂错误卡片 + 会话流追加同文，替代笼统的"异常结束"。
      const detail = outcome.error?.message || "未知错误";
      setReviewError(no, detail);
      setTask(no, { kind: "review", percent: 100, label: "审查失败", done: true, failed: true });
      pushSystemMessage(no, `审查任务失败：${detail}`, "warn");
      showToast(`审查失败：${detail}`);
      await refreshTicket(no).catch(() => {});
      setCenterTab("findings");
      return;
    }
    setTask(no, { kind: "review", percent: 100, label: "判决完成", done: true });
    await sleep(300);
    await loadReviewState(no);
    await refreshTicket(no);
    if (opts?.humanPass === true) pushSystemMessage(no, "人工核准通过 · 发布授权已签发", "success");
    if (opts?.humanPass === false) pushSystemMessage(no, "人工驳回 · 请根据审查意见修复后重新提审", "warn");
    setCenterTab("findings");
  } catch (e) {
    const detail = (e as Error).message;
    setReviewError(no, detail);
    showToast(`审查失败：${detail}`);
    pushSystemMessage(no, `审查任务失败：${detail}`, "warn");
    await refreshTicket(no).catch(() => {
      /* 状态回刷失败忽略 */
    });
  } finally {
    setTask(no, null);
    setGateBusy(no, false);
  }
}

export async function livePublish(no: string) {
  setGateBusy(no, true);
  try {
    setTask(no, { kind: "publish", percent: 40, label: "原子推送中", done: false });
    const { task_id } = await api<{ task_id: string }>(`/api/tickets/${no}/publish`, {
      method: "POST",
      body: "{}",
    });
    const outcome = await pollTask(task_id, (percent, label) => {
      setTask(no, { kind: "publish", percent, label, done: false });
    });
    if (!outcome.ok) {
      const detail = outcome.error?.message || "未知错误";
      showToast(`发布失败：${detail}`);
      await refreshTicket(no).catch(() => {});
      return;
    }
    setTask(no, { kind: "publish", percent: 100, label: "发布完成", done: true });
    await sleep(250);
    try {
      const t = await api<{ result_json?: string }>(`/api/tasks/${task_id}`);
      if (t.result_json) {
        const r = JSON.parse(t.result_json);
        setOutcome(no, {
          commitSha: r.commit_sha ?? "",
          refBefore: r.ref_before ?? "",
          refAfter: r.ref_after ?? "",
          targetRef: r.target_ref ?? "refs/heads/main",
          publishedAt: Date.now(),
          workspaceSyncStatus: r.workspace_sync_status ?? null,
          workspaceSyncNote: r.workspace_sync_note ?? null,
        });
      }
    } catch {
      /* 忽略结果解析失败 */
    }
    await refreshTicket(no);
    if (outcome.ok) showToast("发布成功，主分支已更新");
  } catch (e) {
    showToast(`发布失败：${(e as Error).message}`);
  } finally {
    setTask(no, null);
    setGateBusy(no, false);
  }
}
