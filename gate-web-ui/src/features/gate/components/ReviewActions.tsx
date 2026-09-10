import { useState } from "react";
import { Sparkle, UserFocus } from "@phosphor-icons/react";
import { actions } from "@/app/actions";
import { useApp } from "@/store";
import { ManualReviewDialog } from "./ManualReviewDialog";
import { useT } from "@/i18n";

/** 审查入口：AI 审查 / 人工审查（PRESUBMITTED 阶段的双按钮区）。 */
export function ReviewActions({ ticketNo, gateBusy, round }: { ticketNo: string; gateBusy: boolean; round: number }) {
  const t = useT();
  const engine = useApp((s) => s.engine);
  const [confirmOpen, setConfirmOpen] = useState(false);
  // live 模式配置未加载完成（engine === null）时先禁用，避免把"未加载"误判成"未配置"。
  const aiReady = engine?.configured === true;
  const engineLabel = engine?.providerId
    ? `${engine.providerId}${engine.model ? ` · ${engine.model}` : ""}`
    : "";

  return (
    <div className="shrink-0 border-t border-edge bg-surface p-3.5">
      <div className="grid grid-cols-2 gap-2">
        <button
          className="btn btn-primary h-10 flex-col gap-0.5 leading-tight"
          disabled={gateBusy || !aiReady}
          title={
            aiReady
              ? t("review.aiTip", { engine: engineLabel, round: round })
              : engine === null
                ? t("review.engineLoading")
                : t("review.engineUnconfigured")
          }          onClick={() => actions.reviewAi(ticketNo)}
        >
          <span className="flex items-center gap-1.5 text-[13px] font-medium">
            <Sparkle size={14} weight="fill" />
            {t("review.ai")}
          </span>
          <span className="text-[10px] font-normal opacity-75 truncate max-w-full px-1">
            {aiReady ? engineLabel || t("review.configured") : engine === null ? t("review.engineReading") : t("review.engineMissing")}
          </span>
        </button>
        <button
          className="btn h-10 flex-col gap-0.5 leading-tight"
          disabled={gateBusy}
          title={t("review.humanTip")}
          onClick={() => setConfirmOpen(true)}
        >
          <span className="flex items-center gap-1.5 text-[13px] font-medium">
            <UserFocus size={14} weight="fill" />
            {t("review.human")}
          </span>
          <span className="text-[10px] font-normal opacity-75">{t("review.humanSub")}</span>
        </button>
      </div>
      <div className="mt-2 text-center text-[11.5px] text-faint">
        {aiReady
          ? t("review.aiVerdictNote", { round: round })
          : engine === null
            ? t("review.engineLoading")
            : t("review.aiNeedsConfig")}
      </div>
      {confirmOpen && (
        <ManualReviewDialog
          ticketNo={ticketNo}
          round={round}
          busy={gateBusy}
          onClose={() => setConfirmOpen(false)}
        />
      )}
    </div>
  );
}
