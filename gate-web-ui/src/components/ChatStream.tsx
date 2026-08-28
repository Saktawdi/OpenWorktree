import { useEffect, useRef, useState } from "react";
import {
  Brain,
  CaretDown,
  CaretRight,
  Check,
  CircleNotch,
  FileCode,
  Info,
  MagnifyingGlass,
  PencilSimple,
  Sparkle,
  TerminalWindow,
  Warning,
  X,
} from "@phosphor-icons/react";
import { NO_CHAT, useApp } from "../lib/store";
import type { ChatItem, ToolCallView } from "../lib/types";
import { hhmmss, variantLabel } from "../lib/format";
import { PermissionCard } from "./PermissionCard";
import { Markdown } from "./Markdown";
import { CopyButton } from "./ui";

const TOOL_ICONS = {
  file: FileCode,
  search: MagnifyingGlass,
  edit: PencilSimple,
  terminal: TerminalWindow,
  test: TerminalWindow,
} as const;

function ThinkingBlock({ thinking }: { thinking: NonNullable<Extract<ChatItem, { kind: "assistant" }>["thinking"]> }) {
  const [expanded, setExpanded] = useState(!thinking.done);
  const wasDone = useRef(thinking.done);

  useEffect(() => {
    if (thinking.done && !wasDone.current) {
      wasDone.current = true;
      setExpanded(false);
    }
    if (!thinking.done) wasDone.current = false;
  }, [thinking.done]);

  const seconds = Math.max(1, Math.round((Date.now() - thinking.startedAt) / 1000));

  return (
    <div className="rounded-lg border border-edge bg-sunken overflow-hidden">
      <button
        className="w-full flex items-center gap-2 px-3 h-8 text-[12px] text-dim hover:text-ink transition-colors cursor-pointer"
        onClick={() => setExpanded(!expanded)}
      >
        <Brain size={14} className="text-info" weight={thinking.done ? "regular" : "fill"} />
        <span>{thinking.done ? "深度思考" : "深度思考中"}</span>
        {!thinking.done && (
          <>
            <span className="text-faint tabular-nums">{seconds}s</span>
            <span className="w-1.5 h-1.5 rounded-full bg-info animate-breathe" />
          </>
        )}
        <span className="flex-1" />
        {expanded ? <CaretDown size={12} /> : <CaretRight size={12} />}
      </button>
      {expanded && (
        <div className="px-3 pb-2.5 text-[12.5px] leading-relaxed text-faint whitespace-pre-wrap">
          {thinking.text}
          {!thinking.done && <span className="inline-block w-[6px] h-[13px] bg-info/70 animate-blink align-middle ml-0.5" />}
        </div>
      )}
    </div>
  );
}

function ToolRow({ tool }: { tool: ToolCallView }) {
  const [open, setOpen] = useState(false);
  const Icon = TOOL_ICONS[tool.icon] ?? TerminalWindow;
  return (
    <div>
      <button
        className="w-full flex items-center gap-2.5 px-3 h-9 text-left hover:bg-raised/60 transition-colors cursor-pointer"
        onClick={() => tool.resultDetail && setOpen(!open)}
      >
        {tool.status === "running" ? (
          <CircleNotch size={14} className="text-accent animate-[spin_0.9s_linear_infinite]" />
        ) : tool.status === "ok" ? (
          <Check size={14} className="text-accent" weight="bold" />
        ) : (
          <X size={14} className="text-danger" weight="bold" />
        )}
        <Icon size={14} className="text-dim shrink-0" />
        <span className="text-[12.5px] font-medium text-ink shrink-0">{tool.name}</span>
        <span className="font-mono text-[11.5px] text-dim truncate flex-1 min-w-0">{tool.argsSummary}</span>
        {tool.resultSummary && (
          <span className="font-mono text-[11px] text-faint shrink-0 hidden sm:inline">{tool.resultSummary}</span>
        )}
        {tool.resultDetail &&
          (open ? <CaretDown size={11} className="text-faint" /> : <CaretRight size={11} className="text-faint" />)}
      </button>
      {open && tool.resultDetail && (
        <pre className="mx-3 mb-2 p-2.5 rounded-md bg-sunken border border-edge font-mono text-[11px] leading-relaxed text-dim overflow-x-auto max-h-44">
          {tool.resultDetail}
        </pre>
      )}
    </div>
  );
}

function AssistantFooter({ item }: { item: Extract<ChatItem, { kind: "assistant" }> }) {
  // openchamber 式 footer：元信息（完成的 agent + 推理等级）常驻左对齐，
  // 复制按钮紧随其后、仅 hover 整条回复时出现——绝不推到行尾，避免被误读成用户消息的操作。
  if (item.streaming) return null;

  const rawVariant = (item.variant ?? "").trim();
  const variant = rawVariant && !/^(default|none)$/i.test(rawVariant) ? rawVariant : null;
  const agent = (item.agent ?? "").trim() || null;
  if (!agent && !variant && !item.text) return null;

  return (
    <div className="mt-1.5 flex flex-wrap items-center gap-x-2.5 gap-y-1 text-[11px] text-faint">
      {agent && (
        <span
          className="flex min-w-0 items-center gap-1"
          title={`本条回复由 ${agent} 完成`}
        >
          <Sparkle size={11} weight="fill" className="shrink-0 text-accent/70" />
          <span className="max-w-[180px] truncate">{agent}</span>
        </span>
      )}
      {variant && (
        <span className="flex items-center gap-1" title={`推理强度：${variantLabel(variant)}`}>
          <Brain size={11} className="shrink-0 text-info/70" />
          <span>{variantLabel(variant)}</span>
        </span>
      )}
      {item.text && (
        <span className="flex items-center opacity-0 pointer-events-none transition-opacity duration-150 focus-within:opacity-100 focus-within:pointer-events-auto group-hover/msg:opacity-100 group-hover/msg:pointer-events-auto [&_.icon-btn]:h-6 [&_.icon-btn]:w-6 [&_.icon-btn]:rounded">
          <CopyButton text={item.text} label="复制回复" />
        </span>
      )}
    </div>
  );
}

function AssistantMessage({ item }: { item: Extract<ChatItem, { kind: "assistant" }> }) {
  return (
    <div className="group/msg animate-rise">
      <div className="flex items-center gap-2 mb-1.5">
        <span className="w-6 h-6 rounded-md bg-accent-dim grid place-items-center text-accent">
          <Sparkle size={13} weight="fill" />
        </span>
        <span className="text-[12.5px] font-semibold">Agent</span>
        <span className="flex-1" />
        <span className="font-mono text-[10.5px] text-faint">{hhmmss(item.ts)}</span>
      </div>
      <div className="ml-8 space-y-2">
        {item.thinking && <ThinkingBlock thinking={item.thinking} />}
        {item.tools.length > 0 && (
          <div className="rounded-lg border border-edge bg-panel divide-y divide-edge overflow-hidden">
            {item.tools.map((t) => (
              <ToolRow key={t.id} tool={t} />
            ))}
          </div>
        )}
        {(item.text || item.streaming) && (
          <div className="text-[13.5px] leading-relaxed text-ink">
            {item.text && <Markdown className="md-body">{item.text}</Markdown>}
            {item.streaming && (
              <span className="inline-block w-[7px] h-[15px] bg-accent animate-blink align-middle ml-0.5" />
            )}
          </div>
        )}
        <AssistantFooter item={item} />
      </div>
    </div>
  );
}

function SystemMessage({ item }: { item: Extract<ChatItem, { kind: "system" }> }) {
  const Icon = item.tone === "success" ? Check : item.tone === "warn" ? Warning : Info;
  const color =
    item.tone === "success" ? "text-accent" : item.tone === "warn" ? "text-warn" : "text-faint";
  return (
    <div className="flex items-center gap-2.5 animate-rise py-0.5">
      <span className={`shrink-0 ${color}`}>
        <Icon size={13} weight="fill" />
      </span>
      <span className="text-[12px] text-faint">{item.text}</span>
      <span className="flex-1 border-t border-edge" />
    </div>
  );
}

export function ChatStream({ ticketNo }: { ticketNo: string }) {
  const chat = useApp((s) => s.chats[ticketNo] ?? NO_CHAT);
  const sessionId = useApp((s) => s.activeSessionId[ticketNo] ?? "");
  const bottomRef = useRef<HTMLDivElement>(null);
  const scrollRef = useRef<HTMLDivElement>(null);
  const stick = useRef(true);

  useEffect(() => {
    const el = scrollRef.current;
    if (!el) return;
    const onScroll = () => {
      stick.current = el.scrollHeight - el.scrollTop - el.clientHeight < 120;
    };
    el.addEventListener("scroll", onScroll, { passive: true });
    return () => el.removeEventListener("scroll", onScroll);
  }, []);

  useEffect(() => {
    if (stick.current) bottomRef.current?.scrollIntoView({ behavior: "smooth", block: "end" });
  }, [chat]);

  return (
    <div ref={scrollRef} className="flex-1 min-h-0 overflow-y-auto px-5 py-4">
      <div className="max-w-[760px] mx-auto space-y-4">
        {chat.map((item) =>
          item.kind === "user" ? (
            <div key={item.id} className="flex justify-end animate-rise">
              <div className="max-w-[82%] rounded-xl rounded-tr-sm border border-edge bg-raised px-3.5 py-2 text-[13.5px] leading-relaxed">
                <Markdown className="md-body">{item.text}</Markdown>
              </div>
            </div>
          ) : item.kind === "assistant" ? (
            <AssistantMessage key={item.id} item={item} />
          ) : item.kind === "permission" ? (
            <div key={item.id} className="animate-rise">
              <PermissionCard ticketNo={ticketNo} sessionId={sessionId} item={item} />
            </div>
          ) : (
            <SystemMessage key={item.id} item={item} />
          ),
        )}
        <div ref={bottomRef} />
      </div>
    </div>
  );
}
