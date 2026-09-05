import { Check, X } from "@phosphor-icons/react";
import { shortHash } from "@/shared/format";
import type { Snapshot } from "@/shared/types";

/** 横向门禁流水线（编码→快照→审查→发布四节点）。 */
export function Stepper({ stage, snap, commitSha }: { stage: string; snap?: Snapshot; commitSha?: string }) {
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
    { index: 0, label: "编码协作", sub: coded ? "已完成" : "工作中" },
    { index: 1, label: "预提审快照", sub: snap ? `R${snap.round}·${shortHash(snap.treeHash, 8, 4)}` : undefined },
    { index: 2, label: "门禁审查", sub: rejected ? "已驳回" : reviewed ? "已放行" : undefined },
    { index: 3, label: "发布主分支", sub: commitSha ? shortHash(commitSha, 8, 4) : undefined },
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
          return (
            <span
              key={n.index}
              className={`${circleBase} ${
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
            </span>
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
