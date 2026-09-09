/**
 * 会话共享原语（shared/components/ChatPrimitives）：本体会话（ChatStream）与
 * LLM 小助手面板共用的消息行结构——用户气泡壳、助手头像行、回复 footer 与
 * 流式光标。视觉与结构以本体会话为唯一基准，改动在这里改一次，两处同时生效。
 */
import { Brain, Sparkle } from "@phosphor-icons/react";
import type { ReactNode } from "react";
import { CopyButton } from "@/shared/components/ui";
import { Markdown } from "@/shared/components/Markdown";
import { hhmmss, variantLabel, formatDuration } from "@/shared/format";

/** 流式出字光标（bg-accent 闪烁块；与 .md-body 正文同行使用）。 */
export function StreamCursor() {
  return <span className="inline-block w-[7px] h-[15px] bg-accent animate-blink align-middle ml-0.5" />;
}

/**
 * 用户消息气泡壳：右对齐 + bg-raised 圆角（右上收角），内容任意（Markdown、
 * 引用胶囊、图片）。dataId 透传为 data-chat-msg（ChatRail 定位用）。
 */
export function UserBubble({ children, dataId }: { children: ReactNode; dataId?: string }) {
  return (
    <div className="chat-msg-line relative flex justify-end animate-rise" data-chat-msg={dataId}>
      <div className="max-w-[82%] rounded-xl rounded-tr-sm border border-edge bg-raised px-3.5 py-2 text-[13.5px] leading-relaxed">
        {children}
      </div>
    </div>
  );
}

/**
 * 助手消息行壳：头像行（徽章 + 名字 + hhmmss）+ ml-8 内容区。
 * 流式回合用 live 传开始时间（无 ts 时显示"思考中"的动态秒表由调用方负责）。
 */
export function AssistantShell({
  name = "助手",
  ts,
  tone = "accent",
  badge,
  children,
}: {
  name?: string;
  /** 完成回合的开始时间戳；undefined 时不显示时间。 */
  ts?: number;
  /** 徽章配色：accent（正常）/ danger（失败）。 */
  tone?: "accent" | "danger";
  /** 自定义徽章图标（缺省 Sparkle fill）。 */
  badge?: ReactNode;
  children: ReactNode;
}) {
  return (
    <div className="group/msg animate-rise">
      <div className="flex items-center gap-2 mb-1.5">
        <span
          className={`w-6 h-6 rounded-md grid place-items-center shrink-0 ${
            tone === "danger" ? "bg-danger/15 border border-danger/25 text-danger" : "bg-accent-dim text-accent"
          }`}
        >
          {badge ?? <Sparkle size={13} weight="fill" />}
        </span>
        <span className={`text-[12.5px] font-semibold ${tone === "danger" ? "text-danger" : ""}`}>{name}</span>
        <span className="flex-1" />
        {ts != null && <span className="font-mono text-[10.5px] text-faint">{hhmmss(ts)}</span>}
      </div>
      <div className="ml-8 space-y-2">{children}</div>
    </div>
  );
}

/**
 * 回复 footer（openchamber 式）：模型 / 推理强度 / 耗时等元信息常驻左对齐，
 * 复制按钮仅 hover 整条回复时出现——绝不推到行尾，避免被误读成用户消息的操作。
 */
export function ReplyFooter({
  model,
  variant,
  durationMs,
  copyText,
}: {
  /** 完成本条回复的模型 ID（如 gemini-3.8-flash）；无则不显示。 */
  model?: string;
  /** 推理强度标注（default/none 视为未标注）；无则不显示。 */
  variant?: string;
  /** 生成耗时毫秒；无则不显示。 */
  durationMs?: number;
  copyText?: string;
}) {
  const modelLabel = (model ?? "").trim() || null;
  const rawVariant = (variant ?? "").trim();
  const variantShown = rawVariant && !/^(default|none)$/i.test(rawVariant) ? rawVariant : null;
  const duration = durationMs != null ? formatDuration(durationMs) : null;
  if (!modelLabel && !variantShown && !duration && !copyText) return null;
  return (
    <div className="flex flex-wrap items-center gap-x-2.5 gap-y-1 text-[11px] text-faint">
      {modelLabel && (
        <span className="flex min-w-0 items-center gap-1" title={`本条回复由 ${modelLabel} 完成`}>
          <Sparkle size={11} weight="fill" className="shrink-0 text-accent/70" />
          <span className="max-w-[240px] truncate font-mono text-[10.5px]">{modelLabel}</span>
        </span>
      )}
      {variantShown && (
        <span className="flex items-center gap-1" title={`推理强度：${variantLabel(variantShown)}`}>
          <Brain size={11} className="shrink-0 text-info/70" />
          <span>{variantLabel(variantShown)}</span>
        </span>
      )}
      {duration && <span>· 持续 {duration}</span>}
      {copyText && (
        <span className="flex items-center opacity-0 pointer-events-none transition-opacity duration-150 focus-within:opacity-100 focus-within:pointer-events-auto group-hover/msg:opacity-100 group-hover/msg:pointer-events-auto [&_.icon-btn]:h-6 [&_.icon-btn]:w-6 [&_.icon-btn]:rounded">
          <CopyButton text={copyText} label="复制回复" />
        </span>
      )}
    </div>
  );
}

/** 流式/完成共用的正文容器：md-body Markdown，流式时尾部挂光标。 */
export function ReplyBody({ text, streaming }: { text: string; streaming?: boolean }) {
  return (
    <div className="text-[13.5px] leading-relaxed text-ink">
      <Markdown className="md-body">{text}</Markdown>
      {streaming && <StreamCursor />}
    </div>
  );
}
