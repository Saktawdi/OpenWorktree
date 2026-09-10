import { Check, X } from "@phosphor-icons/react";
import { shortHash } from "@/shared/format";
import { useT } from "@/i18n";
import type { Snapshot } from "@/shared/types";

/** 横向门禁流水线（编码→快照→审查→发布四节点）。
 *  有内容的节点是可点击的证据锚点（需求文档 §三.入口2）：点"这个绿灯为什么绿"直达证据链对应轮次。 */
export function Stepper({
  stage,
  snap,
  commitSha,
  ticketNo,
}: {
  stage: string;
  snap?: Snapshot;
  commitSha?: string;
  ticketNo?: string;
}) {
  const t = useT();
  // 节点点击 → 证据链定位（懒 import 避免与 EvidenceView 循环依赖）
  const gotoEvidence = (round: number) => {
    if (!ticketNo) return;
    void import("./EvidenceView").then((m) => m.focusEvidenceRound(ticketNo, round));
  };
  const gated = ["PRESUBMITTED", "IN_REVIEW", "REJECTED", "READY_TO_PUBLISH", "NEEDS_HUMAN", "DONE"].includes(stage);
  const coded = gated || snap !== undefined;
  const reviewed = ["READY_TO_PUBLISH", "NEEDS_HUMAN", "DONE"].includes(stage);
  const rejected = stage === "REJECTED";

  const activeIdx =
    stage === "IN_PROGRESS" || stage === "PENDING"
      ? 0
      : stage === "PRESUBMITTED"
        ? 1
        : stage === "IN_REVIEW" || stage === "REJECTED"
          ? 2
          : stage === "READY_TO_PUBLISH" || stage === "NEEDS_HUMAN"
            ? 2
            : 3;

  const st = (i: number): "done" | "active" | "error" | "todo" => {
    if (rejected && i === 2) return "error";
    if (i < activeIdx) return "done";
    if (i === activeIdx) return stage === "DONE" ? "done" : "active";
    return "todo";
  };

  const nodes: Array<{ index: number; label: string; sub?: string }> = [
    { index: 0, label: t("gate.step.code"), sub: coded ? t("gate.step.done") : t("gate.step.working") },
    { index: 1, label: t("gate.step.snapshot"), sub: snap ? `R${snap.round}·${shortHash(snap.treeHash, 8, 4)}` : undefined },
    { index: 2, label: t("gate.step.review"), sub: rejected ? t("stage.REJECTED") : reviewed ? t("gate.step.released") : undefined },
    { index: 3, label: t("gate.step.publish"), sub: commitSha ? shortHash(commitSha, 8, 4) : undefined },
  ];

  const circleSize = "w-[24px] h-[24px]";
  const circleBase = `relative z-10 shrink-0 ${circleSize} rounded-full border grid place-items-center text-[10px] font-mono transition-colors`;

  return (
    <div className="relative px-1 pt-0.5">
      {/* connector lines — positioned behind circles */}
      <div className="absolute top-[12px] left-[calc(12.5%+6px)] right-[calc(12.5%+6px)] h-px bg-edge" />
      <div className="absolute top-[12px] left-[calc(12.5%+6px)] h-px bg-accent/40" style={{ width: `${Math.min(activeIdx / 3, 1) * 75}%` }} />

      {/* circles */}
      <div className="relative flex justify-between">
        {nodes.map((n) => {
          const s = st(n.index);
          // 节点对应轮次：快照=最新轮，审查/发布同轮；无内容时不可点
          const clickable = ticketNo !== undefined && ((n.index === 1 && snap) || (n.index === 2 && reviewed) || (n.index === 3 && commitSha));
          const W = clickable ? "button" : "span";
          return (
            <W
              key={n.index}
              title={clickable ? t("gate.step.evidenceTip", { step: n.label }) : undefined}
              onClick={clickable ? () => gotoEvidence(snap?.round ?? 1) : undefined}
              className={`${circleBase} ${clickable ? "cursor-pointer hover:scale-110 transition-transform" : ""} ${
                s === "done"
                  ? "bg-accent-dim border-accent/50 text-accent"
                  : s === "active"
                    ? "border-accent text-accent bg-canvas shadow-[0_0_0_3px_rgba(53,217,158,0.08)]"
                    : s === "error"
                      ? "bg-danger-dim border-danger/50 text-danger"
                      : "border-edge text-faint bg-sunken"
              }`}
            >
              {s === "done" ? (
                <Check size={12} weight="bold" />
              ) : s === "error" ? (
                <X size={12} weight="bold" />
              ) : s === "active" ? (
                <span className="w-1.5 h-1.5 rounded-full bg-accent animate-breathe" />
              ) : (
                n.index + 1
              )}
            </W>
          );
        })}
      </div>

      {/* labels */}
      <div className="relative flex justify-between mt-2">
        {nodes.map((n) => {
          const s = st(n.index);
          return (
            <div key={n.index} className="w-[24px] flex-none first:flex-none flex flex-col items-center last:items-center">
              <div
                className={`text-center leading-4 ${
                  s === "todo"
                    ? "text-faint"
                    : s === "error"
                      ? "text-danger"
                      : s === "done"
                        ? "text-accent"
                        : "text-ink"
                }`}
              >
                <div className="text-[11px] font-medium whitespace-nowrap">{n.label}</div>
                {n.sub && (
                  <div className="font-mono text-[10px] text-faint truncate leading-3">{n.sub}</div>
                )}
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}
