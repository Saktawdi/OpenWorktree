/**
 * 判决卡片（需求文档 §五.1「判决卡片要能解释自己」）：
 * 把 GatePolicy 的结构化 reason/detail 翻译成人话，并给出每类原因对应的下一步动作。
 * 这是整条证据链上唯一需要用户"理解"的东西，视觉权重最高。
 */
import { ArrowUUpLeft, CircleNotch, FileMagnifyingGlass, Question, SealCheck, ShieldWarning, Warning, X } from "@phosphor-icons/react";
import { actions } from "@/app/actions";
import { useApp } from "@/store";
import type { VerdictInfo } from "@/shared/types";

/** 每类判决原因的人话解释与建议动作类型。 */
export interface DecisionExpl {
  /** 一句话人话解释（为什么是这个判决）。 */
  summary: string;
  /** 详细依据（逐条）。 */
  points: string[];
  /** 建议动作（按优先级，最多渲染两个）。 */
  actions: DecisionAction[];
}

type DecisionAction =
  | { kind: "retryReview"; label: string }
  | { kind: "returnWithFindings"; label: string }
  | { kind: "humanApprove"; label: string }
  | { kind: "goFindings"; label: string }
  | { kind: "trimDiff"; label: string };

/**
 * reason → 人话 + 动作。GatePolicy 的 reason 文案是稳定的字符串前缀
 * （见 gate-domain GatePolicy.decide），按前缀匹配；未识别的回退为原文直出。
 */
export function explainDecision(verdict: VerdictInfo): DecisionExpl {
  const reason = verdict.reason ?? "";
  const detail = verdict.detail ?? [];

  // coverage gap: NEEDS_HUMAN — 引擎没有覆盖全部变更文件
  if (reason.startsWith("coverage gap")) {
    return {
      summary: "引擎没有审查到全部改动文件，无法自动放行",
      points: detail.length > 0 ? detail : ["未覆盖的文件清单见审计日志"],
      actions: [
        { kind: "humanApprove", label: "人工审阅后核准" },
        { kind: "goFindings", label: "查看审查发现" },
      ],
    };
  }
  // diff 超限（字节/行）： NEEDS_HUMAN
  if (reason.startsWith("diff exceeds")) {
    const bytes = reason.includes("byte");
    return {
      summary: bytes ? "本轮改动体量超过字节上限" : "本轮改动体量超过行数上限",
      points: detail.length > 0 ? detail : [reason],
      actions: [{ kind: "trimDiff", label: "拆分或缩减改动后重新提审" }],
    };
  }
  // 引擎降级/故障：REJECT（degraded）—— 可原地重试
  if (reason.startsWith("engine failure") || reason.startsWith("engine adapter reported degraded")) {
    return {
      summary: "引擎输出不完整或未能完成判决，已按不可信处理",
      points: detail.length > 0 ? detail : [reason],
      actions: [
        { kind: "retryReview", label: "重试 AI 审查（不消耗轮次）" },
        { kind: "humanApprove", label: "人工审查放行" },
      ],
    };
  }
  // 旧快照重放
  if (reason.startsWith("evidence describes a different tree")) {
    return {
      summary: "审查结果对应的是旧快照，已被拒绝重放",
      points: detail.length > 0 ? detail : [reason],
      actions: [{ kind: "trimDiff", label: "重新预提审" }],
    };
  }
  // 有达到严格度阈值的发现：REJECT
  if (reason.startsWith("findings at or above")) {
    return {
      summary: "存在达到驳回阈值的发现，需修复后重新提审",
      points: detail,
      actions: [{ kind: "returnWithFindings", label: "带着发现返回会话" }],
    };
  }
  if (reason.startsWith("cannot mint authorization")) {
    return {
      summary: "快照缺少目标分支或基线绑定，无法签发发布授权",
      points: detail.length > 0 ? detail : [reason],
      actions: [{ kind: "trimDiff", label: "重新预提审" }],
    };
  }
  if (verdict.verdict === "PASS") {
    return {
      summary: reason || "全部策略通过，发布授权已签发",
      points: [],
      actions: [{ kind: "goFindings", label: "查看审查发现" }],
    };
  }
  return {
    summary: reason || "需人工判断",
    points: detail,
    actions:
      verdict.verdict === "REQUIRES_HUMAN"
        ? [{ kind: "humanApprove", label: "人工核准" }, { kind: "goFindings", label: "查看审查发现" }]
        : [{ kind: "goFindings", label: "查看审查发现" }],
  };
}

function ActionButtons({ ticketNo, items }: { ticketNo: string; items: DecisionAction[] }) {
  const gateBusy = useApp((s) => s.gateBusy[ticketNo] ?? false);
  const engine = useApp((s) => s.engine);
  return (
    <div className="mt-3 flex flex-wrap gap-2">
      {items.slice(0, 2).map((a) => {
        const disabled =
          gateBusy || (a.kind === "retryReview" && engine?.configured !== true);
        const onClick = () => {
          if (a.kind === "retryReview") actions.reviewAi(ticketNo);
          else if (a.kind === "returnWithFindings") actions.returnWithFindings(ticketNo);
          else if (a.kind === "humanApprove") actions.reviewHuman(ticketNo);
        };
        if (a.kind === "goFindings") return null; // 判决卡常驻发现页，跳转动作无意义
        return (
          <button
            key={a.kind}
            className="btn btn-sm h-8 text-[12px]"
            disabled={disabled}
            title={disabled && a.kind === "retryReview" ? "未配置审查引擎，无法 AI 审查" : undefined}
            onClick={onClick}
          >
            {a.kind === "retryReview" && <CircleNotch size={13} />}
            {a.kind === "returnWithFindings" && <ArrowUUpLeft size={13} />}
            {a.kind === "humanApprove" && <SealCheck size={13} weight="fill" />}
            {a.kind === "trimDiff" && <FileMagnifyingGlass size={13} />}
            {a.label}
          </button>
        );
      })}
    </div>
  );
}

/**
 * 判决卡片主体。tone 决定配色：pass=绿 / reject=红 / human=琥珀。
 * findingsCount 仅在 REJECT 时展示徽标。
 */
export function DecisionCard({
  ticketNo,
  verdict,
  tone,
  findingsCount,
  compact,
}: {
  ticketNo: string;
  verdict: VerdictInfo;
  tone: "pass" | "reject" | "human";
  findingsCount?: number;
  compact?: boolean;
}) {
  const expl = explainDecision(verdict);
  const title =
    tone === "pass" ? "门禁放行" : tone === "human" ? "需人工核准" : verdict.degraded ? "审查引擎未完成判决" : "门禁驳回";
  const cls =
    tone === "pass"
      ? "border-accent/30 bg-accent/[0.06]"
      : tone === "human"
        ? "border-warn/30 bg-warn/[0.06]"
        : "border-danger/30 bg-danger/[0.05]";
  const titleCls =
    tone === "pass" ? "text-accent" : tone === "human" ? "text-warn" : "text-danger";
  const Icon =
    tone === "pass" ? SealCheck : tone === "human" ? Question : verdict.degraded ? Warning : X;

  return (
    <div className={`rounded-xl border ${cls} ${compact ? "p-3" : "p-3.5"} animate-slide-in`}>
      <div className="flex items-center gap-2">
        <Icon size={16} weight="fill" className={titleCls} />
        <span className={`text-[13px] font-semibold ${titleCls}`}>{title}</span>
        {verdict.degraded && (
          <span className="chip border border-warn/30 text-warn bg-warn/10">可重试 · 不消耗轮次</span>
        )}
        {tone === "reject" && !verdict.degraded && typeof findingsCount === "number" && (
          <span className="chip border border-danger/30 text-danger bg-danger/10">{findingsCount} 项发现</span>
        )}
        <span className="flex-1" />
        <span className="font-mono text-[10.5px] text-faint">第 {verdict.round} 轮</span>
      </div>
      <div className="mt-1.5 text-[12.5px] leading-relaxed text-ink">{expl.summary}</div>
      {expl.points.length > 0 && (
        <ul className="mt-1.5 space-y-1">
          {expl.points.slice(0, 8).map((p, i) => (
            <li key={i} className="font-mono text-[11.5px] leading-relaxed text-dim break-all">
              • {p}
            </li>
          ))}
          {expl.points.length > 8 && (
            <li className="text-[11px] text-faint">… 共 {expl.points.length} 条依据</li>
          )}
        </ul>
      )}
      <ActionButtons ticketNo={ticketNo} items={expl.actions} />
      <div className="mt-2 font-mono text-[10.5px] text-faint">
        引擎 {verdict.engineId}
        {verdict.authorizationId ? ` · 授权 ${verdict.authorizationId}` : ""}
      </div>
    </div>
  );
}
