import { useState } from "react";
import { Sparkle, UserFocus } from "@phosphor-icons/react";
import { actions } from "@/app/actions";
import { useApp } from "@/store";
import { ManualReviewDialog } from "./ManualReviewDialog";

/** 审查入口：AI 审查 / 人工审查（PRESUBMITTED 阶段的双按钮区）。 */
export function ReviewActions({ ticketNo, gateBusy, round }: { ticketNo: string; gateBusy: boolean; round: number }) {
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
              ? `审查引擎 ${engineLabel} 将基于快照 R${round} 判决，与工作区后续改动无关`
              : engine === null
                ? "正在读取引擎配置…"
                : "未配置审查引擎：在 设置 → gate.toml [engine] 完成配置，或改用人工审查"
          }          onClick={() => actions.reviewAi(ticketNo)}
        >
          <span className="flex items-center gap-1.5 text-[13px] font-medium">
            <Sparkle size={14} weight="fill" />
            AI 审查
          </span>
          <span className="text-[10px] font-normal opacity-75 truncate max-w-full px-1">
            {aiReady ? engineLabel || "已配置" : engine === null ? "配置读取中" : "未配置引擎"}
          </span>
        </button>
        <button
          className="btn h-10 flex-col gap-0.5 leading-tight"
          disabled={gateBusy}
          title="人工审阅变更对比后，确认已审阅并出具判决"
          onClick={() => setConfirmOpen(true)}
        >
          <span className="flex items-center gap-1.5 text-[13px] font-medium">
            <UserFocus size={14} weight="fill" />
            人工审查
          </span>
          <span className="text-[10px] font-normal opacity-75">弹窗确认已审阅</span>
        </button>
      </div>
      <div className="mt-2 text-center text-[11.5px] text-faint">
        {aiReady
          ? `AI 判决基于快照 R${round}，与工作区后续改动无关`
          : engine === null
            ? "正在读取引擎配置…"
            : "AI 审查需先在设置中配置 gate.toml [engine]；人工审查随时可用"}
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
