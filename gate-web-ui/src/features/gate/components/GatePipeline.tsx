import { useState } from "react";
import { AnimatePresence, motion } from "motion/react";
import { ListChecks } from "@phosphor-icons/react";
import { actions } from "@/app/actions";
import { useApp } from "@/store";
import { setGateSection } from "@/features/gate/state";
import type { Snapshot } from "@/shared/types";
import { Stepper } from "./Stepper";
import { TaskCard, TreeHashCard, VerdictBanner, OutcomeCard } from "./cards";
import { ManualReviewDialog } from "./ManualReviewDialog";
import { useT } from "@/i18n";

/** 门禁流水线段（Stepper + 快照/进度/判决/结果卡片 + NEEDS_HUMAN 双按钮）。 */
export function GatePipeline({
  ticketNo,
  stage,
  round,
  snap,
  task,
  verdict,
  findingsCount,
  gateBusy,
  outcome,
}: {
  ticketNo: string;
  stage: string;
  round: number;
  snap?: Snapshot;
  task?: { kind: string; percent: number; label: string };
  verdict?: { verdict: string; reason: string; engineId: string; authorizationId?: string; degraded?: boolean };
  findingsCount: number;
  gateBusy: boolean;
  outcome?: { commitSha: string; refBefore: string; refAfter: string; targetRef: string; publishedAt: number };
}) {
  const t = useT();
  const expanded = useApp((s) => s.gateSections.pipeline);
  const [humanDialog, setHumanDialog] = useState(false);

  return (
    <div className="border-b border-edge">
      <button
        className="w-full sticky top-0 z-10 bg-canvas flex items-center gap-2 px-4 py-2.5 text-left hover:bg-raised transition-colors cursor-pointer"
        onClick={() => setGateSection("pipeline", !expanded)}
      >
        <ListChecks size={14} className="text-faint shrink-0" />
        <span className="text-[12px] font-medium text-dim">{t("gate.pipeline.title")}</span>
        {round > 0 && (
          <span className="chip border border-edge-strong bg-raised text-dim font-mono">{t("gate.history.round", { n: round })}</span>
        )}
        <span className="flex-1" />
        <span
          className={`text-[11px] text-faint transition-transform duration-150 ${expanded ? "rotate-0" : "-rotate-90"}`}
        >
          ▾
        </span>
      </button>
      <AnimatePresence>
        {expanded && (
          <motion.div
            initial={{ height: 0, opacity: 0 }}
            animate={{ height: "auto", opacity: 1 }}
            exit={{ height: 0, opacity: 0 }}
            transition={{ duration: 0.2, ease: [0.16, 1, 0.3, 1] }}
            className="overflow-hidden"
          >
            <div className="px-4 pt-1 pb-3 space-y-3">
              <Stepper stage={stage} snap={snap} commitSha={outcome?.commitSha} ticketNo={ticketNo} />
              {task && <TaskCard task={task} />}
              {snap && !task && <TreeHashCard snap={snap} />}
              {verdict && !task && (
                <VerdictBanner ticketNo={ticketNo} verdict={verdict} findingsCount={findingsCount} />
              )}
              {outcome && <OutcomeCard outcome={outcome} />}
              {stage === "NEEDS_HUMAN" && (
                <div className="grid grid-cols-2 gap-2">
                  <button
                    className="btn btn-primary h-9"
                    disabled={gateBusy}
                    onClick={() => setHumanDialog(true)}
                    title={t("gate.pipeline.humanApproveTip")}
                  >
                    {t("gate.pipeline.humanApprove")}
                  </button>
                  <button
                    className="btn btn-danger-ghost h-9"
                    disabled={gateBusy}
                    onClick={() => actions.rejectTicket(ticketNo)}
                  >
                    {t("gate.pipeline.reject")}
                  </button>
                </div>
              )}
            </div>
          </motion.div>
        )}
      </AnimatePresence>
      {humanDialog && (
        <ManualReviewDialog
          ticketNo={ticketNo}
          round={round || 1}
          busy={gateBusy}
          onClose={() => setHumanDialog(false)}
        />
      )}
    </div>
  );
}
