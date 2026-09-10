/**
 * 门禁域流程（gate flows）：预提审（锁定快照）、基座同步、审查（AI/人工）与发布。
 * 都是对后端异步任务（/presubmit、/review、/publish）的编排 + 进度回写。
 */
import { t } from "@/i18n";
import { api, pollTask } from "@/net";
import { appStore } from "@/store";
import { showToast } from "@/store/ui";
import { sleep } from "@/shared/format";
import { refreshTicket } from "@/features/ticket/api";
import { setStage } from "@/features/ticket/state";
import { pushSystemMessage } from "@/features/session/chat";
import { addSnapshot, clearReviewEnded, markReviewEnded, setFindings, setGateBusy, setOutcome, setReviewError, setTask, setVerdict } from "./state";
import { loadEvidence, loadReviewState } from "./api";

export async function livePresubmit(no: string) {
  setGateBusy(no, true);
  setFindings(no, []);
  setVerdict(no, null);
  clearReviewEnded(no);
  try {
    setTask(no, { kind: "presubmit", percent: 50, label: t("kanban.toast.lockingSnapshot").replace("…", ""), done: false });
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
    await Promise.all([refreshTicket(no), loadEvidence(no)]);
    pushSystemMessage(no, t("gateflow.snapshotLocked", { round: r.review_round, hash: r.tree_hash.slice(0, 10) }), "success");
  } catch (e) {
    showToast(t("gateflow.presubmitFailed", { err: (e as Error).message }));
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
      showToast(t("gateflow.syncSkipped", { reason: r.skipped_reason ?? t("common.unknown") }));
    } else if (r.import_kind === "skipped" && r.import_reason) {
      // 基线本身没动（多为源仓库历史分叉等需人工处理的情况），必须可见，不能静默。
      pushSystemMessage(no, t("gateflow.syncNoImport", { reason: r.import_reason }), "warn");
    } else if (r.import_kind === "fast_forwarded" || r.import_kind === "adopted") {
      pushSystemMessage(
        no,
        r.import_kind === "adopted"
          ? t("gateflow.syncAdopted")
          : t("gateflow.syncImported", { n: r.behind }),
        "success",
      );
    } else if (r.status === "up_to_date") {
      pushSystemMessage(no, t("gateflow.syncUpToDate"), "info");
    } else if (r.status === "replanted") {
      pushSystemMessage(no, t("gateflow.syncReplanted"), "success");
    } else if (r.conflicts.length > 0) {
      pushSystemMessage(
        no,
        t("gateflow.syncConflicts", { n: r.behind, conflicts: r.conflicts.join(t("common.listSep")) }),
        "warn",
      );
    } else {
      pushSystemMessage(
        no,
        t("gateflow.syncDone", { n: r.behind }) +
          (r.stash_kept ? t("gateflow.syncStashKept") : ""),
        "success",
      );
    }
  } catch (e) {
    showToast(t("gateflow.syncFailed", { err: (e as Error).message }));
  } finally {
    setGateBusy(no, false);
  }
}

export async function liveReview(no: string, opts?: { humanPass?: boolean; note?: string }) {
  setGateBusy(no, true);
  setStage(no, "IN_REVIEW");
  setReviewError(no, null);
  clearReviewEnded(no);
  try {
    setTask(no, {
      kind: "review",
      percent: 30,
      label: opts ? t("gateflow.commitHumanVerdict") : t("findings.engineRunning"),
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
    // 轮询进度回写任务卡（面板/工单列表可见推进），但不切换用户当前所在视图。
    const outcome = await pollTask(task_id, (percent, label) => {
      setTask(no, { kind: "review", percent, label, done: false });
    });
    if (!outcome.ok) {
      // 失败原因必须可见：审查发现页挂错误卡片 + 会话流追加同文，替代笼统的"异常结束"。
      const detail = outcome.error?.message || t("common.unknown");
      setReviewError(no, detail);
      setTask(no, { kind: "review", percent: 100, label: t("gateflow.reviewFailed"), done: true, failed: true });
      pushSystemMessage(no, t("gateflow.reviewTaskFailed", { err: detail }), "warn");
      showToast(t("gateflow.reviewFailedToast", { err: detail }));
      await refreshTicket(no).catch(() => {});
      return;
    }
    setTask(no, { kind: "review", percent: 100, label: t("gateflow.verdictDone"), done: true });
    await sleep(300);
    await Promise.all([loadReviewState(no), loadEvidence(no)]);
    await refreshTicket(no);
    // 结果呈现不打断用户当前视图（不切发现页）：右侧面板判决卡 + 会话流结论消息 +
    // 工单列表「已审查/驳回」徽标三路通知。
    const verdict = appStore.getState().verdicts[no];
    if (verdict) {
      markReviewEnded(no, verdict.verdict);
      if (!opts) {
        const text =
          verdict.verdict === "PASS"
            ? t("gateflow.aiPass")
            : verdict.verdict === "REQUIRES_HUMAN"
              ? t("gateflow.aiHuman")
              : t("gateflow.aiReject", { n: (appStore.getState().findings[no] ?? []).length });
        pushSystemMessage(no, text, verdict.verdict === "PASS" ? "success" : "warn");
      }
    }
    if (opts?.humanPass === true) pushSystemMessage(no, t("actions.overrideMsg"), "success");
    if (opts?.humanPass === false) pushSystemMessage(no, t("actions.rejectMsg"), "warn");
  } catch (e) {
    const detail = (e as Error).message;
    setReviewError(no, detail);
    showToast(t("gateflow.reviewFailedToast", { err: detail }));
    pushSystemMessage(no, t("gateflow.reviewTaskFailed", { err: detail }), "warn");
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
    setTask(no, { kind: "publish", percent: 40, label: t("gateflow.publishingAtomic"), done: false });
    const { task_id } = await api<{ task_id: string }>(`/api/tickets/${no}/publish`, {
      method: "POST",
      body: "{}",
    });
    const outcome = await pollTask(task_id, (percent, label) => {
      setTask(no, { kind: "publish", percent, label, done: false });
    });
    if (!outcome.ok) {
      const detail = outcome.error?.message || t("common.unknown");
      showToast(t("gateflow.publishFailed", { err: detail }));
      await refreshTicket(no).catch(() => {});
      return;
    }
    setTask(no, { kind: "publish", percent: 100, label: t("gateflow.publishDone"), done: true });
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
    await Promise.all([refreshTicket(no), loadEvidence(no)]);
    if (outcome.ok) showToast(t("gateflow.publishSuccess"));
  } catch (e) {
    showToast(t("gateflow.publishFailed", { err: (e as Error).message }));
  } finally {
    setTask(no, null);
    setGateBusy(no, false);
  }
}
