import { useState } from "react";
import { motion } from "motion/react";
import { ArrowUUpLeft, CircleNotch, SealCheck, Warning, X } from "@phosphor-icons/react";
import { actions } from "@/app/actions";
import { jumpToFinding, NO_FINDINGS, useApp } from "@/store";
import type { Finding } from "@/shared/types";
import { ManualReviewDialog } from "@/features/gate";
import { SeverityChip } from "@/shared/components/ui";

function FindingCard({ finding, index }: { finding: Finding; index: number }) {
  return (
    <motion.div
      className="card p-4"
      initial={{ opacity: 0, y: 12 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ type: "spring", stiffness: 300, damping: 25, delay: index * 0.05 }}
    >
      <div className="flex items-center gap-2">
        <SeverityChip severity={finding.severity} />
        {finding.ruleId && (
          <span className="font-mono text-[11px] text-faint">{finding.ruleId}</span>
        )}
      </div>
      <div className="mt-2.5 text-[13.5px] leading-relaxed text-ink">{finding.message}</div>
      {finding.path && finding.path !== "." && (
        <div className="mt-2 flex items-center gap-3">
          <button
            className="font-mono text-[12px] text-info hover:underline cursor-pointer bg-transparent border-0 p-0"
            onClick={() => jumpToFinding(finding.path, finding.lineStart ?? 1)}
            title="在变更对比中查看"
          >
            {finding.path}
            {finding.lineStart ? `:${finding.lineStart}` : ""}
          </button>
        </div>
      )}
      {finding.suggestion && (
        <div className="mt-2.5 rounded-lg bg-sunken border border-edge px-3 py-2 text-[12.5px] leading-relaxed text-dim">
          <span className="text-faint">建议 </span>
          {finding.suggestion}
        </div>
      )}
    </motion.div>
  );
}

/** 审查执行中：进度卡片，替代"跳过来发现无事发生"的空白态。 */
function ReviewProgressCard({ percent, label }: { percent: number; label: string }) {
  const engine = useApp((s) => s.engine);
  const timeout = engine?.timeoutSeconds ?? null;
  return (
    <div className="flex-1 min-h-0 grid place-items-center">
      <div className="text-center max-w-[360px] animate-rise w-full">
        <div className="mx-auto w-12 h-12 rounded-full bg-info/10 border border-info/40 grid place-items-center mb-3">
          <CircleNotch size={22} className="text-info animate-[spin_0.9s_linear_infinite]" />
        </div>
        <div className="text-[14.5px] font-semibold">门禁审查执行中</div>
        <div className="mt-1.5 text-[12.5px] text-faint">{label || "引擎执行中"}</div>
        <div className="mt-3 h-1.5 rounded-full bg-edge overflow-hidden">
          <div
            className="h-full rounded-full bg-info transition-all duration-500"
            style={{ width: `${Math.max(4, Math.min(percent, 100))}%` }}
          />
        </div>
        <div className="mt-1.5 font-mono text-[11px] text-faint tabular-nums">{percent}%</div>
        <div className="mt-3 text-[11.5px] text-faint leading-relaxed">
          判决落盘前不会触碰主分支；完成后此处展示全部发现。
          {timeout != null && (
            <span className="block mt-0.5">
              引擎超时上限 {timeout}s，超时后本轮自动判为驳回并给出原因。
            </span>
          )}
        </div>
      </div>
    </div>
  );
}

/** 审查任务失败：展示真实失败原因 + 重试/人工兜底入口。 */
function ReviewErrorCard({ ticketNo, message }: { ticketNo: string; message: string }) {
  const engine = useApp((s) => s.engine);
  const gateBusy = useApp((s) => s.gateBusy[ticketNo] ?? false);
  const snaps = useApp((s) => s.snapshots[ticketNo]);
  const [confirmHuman, setConfirmHuman] = useState(false);
  const aiReady = engine?.configured === true;
  const round = snaps?.[snaps.length - 1]?.round ?? 0;

  return (
    <div className="flex-1 min-h-0 grid place-items-center">
      <div className="text-center max-w-[420px] w-full animate-rise">
        <div className="card p-4 text-left border-danger/30">
          <div className="flex items-center gap-2">
            <X size={16} className="text-danger" weight="fill" />
            <span className="text-[13px] font-semibold text-danger">审查任务失败</span>
          </div>
          <div className="mt-2 rounded-lg bg-sunken border border-edge px-3 py-2.5 font-mono text-[11.5px] leading-relaxed text-dim break-all whitespace-pre-wrap">
            {message}
          </div>
          <div className="mt-2.5 text-[11.5px] text-faint leading-relaxed">
            {aiReady
              ? "可重试 AI 审查；若为引擎配置/凭据问题，请先到 设置 → gate.toml 检查 [engine]，并在 设置 → LLM Providers 确认已填 API Key，或改用人工审查。"
              : "当前未配置审查引擎（设置 → gate.toml [engine]）；可先人工审查，或配置引擎后重试。"}
          </div>
          <div className="mt-3 grid grid-cols-2 gap-2">
            <button
              className="btn btn-sm h-8 text-[12px]"
              disabled={!aiReady || gateBusy}
              title={aiReady ? "重新发起 AI 审查" : "未配置审查引擎，无法 AI 审查"}
              onClick={() => actions.reviewAi(ticketNo)}
            >
              重试 AI 审查
            </button>
            <button
              className="btn btn-sm h-8 text-[12px] border-warn/40 text-warn hover:bg-warn/10"
              disabled={gateBusy}
              onClick={() => setConfirmHuman(true)}
            >
              <Warning size={12} weight="fill" />
              人工审查放行
            </button>
          </div>
        </div>
        <div className="mt-2 text-[11px] text-faint">失败原因同时已写入会话流，可返回会话查看上下文</div>
      </div>
      {confirmHuman && (
        <ManualReviewDialog
          ticketNo={ticketNo}
          round={round}
          busy={gateBusy}
          onClose={() => setConfirmHuman(false)}
        />
      )}
    </div>
  );
}

/** 引擎故障型驳回（degraded）：原地重试入口——不消耗轮次，同一轮快照重新判决。 */
function EngineRetryStrip({ ticketNo }: { ticketNo: string }) {
  const engine = useApp((s) => s.engine);
  const gateBusy = useApp((s) => s.gateBusy[ticketNo] ?? false);
  const snaps = useApp((s) => s.snapshots[ticketNo]);
  const [confirmHuman, setConfirmHuman] = useState(false);
  const aiReady = engine?.configured === true;
  const round = snaps?.[snaps.length - 1]?.round ?? 0;

  return (
    <div className="card p-3.5 border-warn/30 animate-slide-in">
      <div className="flex items-center gap-2">
        <Warning size={14} className="text-warn" weight="fill" />
        <span className="text-[12.5px] font-semibold text-warn">本轮为引擎侧故障，非代码驳回</span>
        <span className="flex-1" />
        <span className="font-mono text-[10.5px] text-faint">第 {round} 轮 · 不消耗轮次</span>
      </div>
      <div className="mt-2 grid grid-cols-2 gap-2">
        <button
          className="btn btn-sm h-8 text-[12px]"
          disabled={!aiReady || gateBusy}
          title={aiReady ? "同一轮快照重新发起 AI 审查" : "未配置审查引擎，无法 AI 审查"}
          onClick={() => actions.reviewAi(ticketNo)}
        >
          重试 AI 审查
        </button>
        <button
          className="btn btn-sm h-8 text-[12px] border-warn/40 text-warn hover:bg-warn/10"
          disabled={gateBusy}
          onClick={() => setConfirmHuman(true)}
        >
          人工审查放行
        </button>
      </div>
      {confirmHuman && (
        <ManualReviewDialog
          ticketNo={ticketNo}
          round={round}
          busy={gateBusy}
          onClose={() => setConfirmHuman(false)}
        />
      )}
    </div>
  );
}

export function FindingsView({ ticketNo }: { ticketNo: string }) {
  const findings = useApp((s) => s.findings[ticketNo] ?? NO_FINDINGS);
  const verdict = useApp((s) => s.verdicts[ticketNo]);
  const stage = useApp((s) => s.tickets.find((t) => t.ticketNo === ticketNo)?.stage);
  const task = useApp((s) => s.tasks[ticketNo]);
  const reviewError = useApp((s) => s.reviewErrors[ticketNo]);

  const reviewing = stage === "IN_REVIEW" || task?.kind === "review";

  // 执行中优先展示进度；失败且没有（新一轮）判决时展示失败原因。
  if (reviewing && !reviewError) {
    return <ReviewProgressCard percent={task?.percent ?? 30} label={task?.label ?? "引擎执行中"} />;
  }
  if (reviewError && !verdict) {
    return <ReviewErrorCard ticketNo={ticketNo} message={reviewError} />;
  }

  // 引擎故障型驳回（degraded）：列表顶部给原地重试入口。
  const engineFailedRound = verdict?.verdict === "REJECT" && verdict.degraded === true;

  if (!verdict && findings.length === 0) {
    return (
      <div className="flex-1 min-h-0 grid place-items-center">
        <div className="text-center max-w-[340px]">
          <div className="mx-auto w-11 h-11 rounded-xl border border-dashed border-edge-strong grid place-items-center mb-3">
            <SealCheck size={20} className="text-faint" />
          </div>
          <div className="text-[13.5px] text-dim">尚无审查结论</div>
          <div className="mt-1 text-[12px] text-faint leading-relaxed">
            预提审并触发门禁审查后，判决与全部发现会展示在这里。
          </div>
        </div>
      </div>
    );
  }

  if (findings.length === 0 && verdict?.verdict === "PASS") {
    return (
      <div className="flex-1 min-h-0 grid place-items-center">
        <div className="text-center max-w-[360px] animate-rise">
          <div className="mx-auto w-12 h-12 rounded-full bg-accent-dim border border-accent/40 grid place-items-center mb-3">
            <SealCheck size={24} className="text-accent" weight="fill" />
          </div>
          <div className="text-[15px] font-semibold">本轮审查未发现缺陷</div>
          <div className="mt-1.5 text-[12.5px] text-faint leading-relaxed">{verdict.reason}</div>
          <div className="mt-1 font-mono text-[11px] text-faint">引擎 {verdict.engineId} · 第 {verdict.round} 轮</div>
        </div>
      </div>
    );
  }

  return (
    <div className="flex-1 min-h-0 overflow-y-auto px-5 py-4">
      <div className="max-w-[720px] mx-auto space-y-3">
        {engineFailedRound && <EngineRetryStrip ticketNo={ticketNo} />}
        <div className="flex items-center gap-2 pb-1">
          <span className="text-[12.5px] text-dim">共 {findings.length} 项发现</span>
          <span className="flex-1" />
          {stage === "REJECTED" && (
            <button
              className="btn h-7 text-[12px]"
              onClick={() => actions.returnWithFindings(ticketNo)}
            >
              <ArrowUUpLeft size={13} />
              带意见返回会话
            </button>
          )}
        </div>
        {findings.map((f, i) => (
          <FindingCard key={i} finding={f} index={i} />
        ))}
      </div>
    </div>
  );
}
