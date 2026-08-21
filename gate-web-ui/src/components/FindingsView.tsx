import { motion } from "motion/react";
import { ArrowUUpLeft, SealCheck } from "@phosphor-icons/react";
import { actions } from "../lib/actions";
import { jumpToFinding, NO_FINDINGS, useApp } from "../lib/store";
import type { Finding } from "../lib/types";
import { SeverityChip } from "./ui";

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
      {finding.suggestion && (
        <div className="mt-2.5 rounded-lg bg-sunken border border-edge px-3 py-2 text-[12.5px] leading-relaxed text-dim">
          <span className="text-faint">建议 </span>
          {finding.suggestion}
        </div>
      )}
    </motion.div>
  );
}

export function FindingsView({ ticketNo }: { ticketNo: string }) {
  const findings = useApp((s) => s.findings[ticketNo] ?? NO_FINDINGS);
  const verdict = useApp((s) => s.verdicts[ticketNo]);
  const stage = useApp((s) => s.tickets.find((t) => t.ticketNo === ticketNo)?.stage);

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
