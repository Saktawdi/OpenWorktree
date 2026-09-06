import { memo, useCallback, useEffect, useMemo, useRef, useState } from "react";
import { createPortal } from "react-dom";
import type { RefObject } from "react";
import { AnimatePresence, motion } from "motion/react";
import {
  Brain,
  CaretDown,
  CaretRight,
  Check,
  CircleNotch,
  Code,
  FileCode,
  FileText,
  Globe,
  Info,
  ListChecks,
  MagnifyingGlass,
  PencilSimple,
  Question,
  Sparkle,
  TerminalWindow,
  Warning,
  X,
} from "@phosphor-icons/react";
import { NO_CHAT, useApp } from "@/store";
import { fetchBlobUrl } from "@/net";
import type { ChatItem, ToolCallView, ToolIconKind } from "@/shared/types";
import { hhmmss, variantLabel } from "@/shared/format";
import { parseQuotedText, stripQuoteMarkers } from "@/shared/quotes";
import { PermissionCard } from "@/features/session/components/PermissionCard";
import { QuestionCard } from "@/features/session/components/QuestionCard";
import { Markdown } from "@/shared/components/Markdown";
import { QuoteChip } from "@/shared/components/QuoteChip";
import { CopyButton } from "@/shared/components/ui";

const TOOL_ICONS: Record<ToolIconKind, typeof TerminalWindow> = {
  file: FileText,
  search: MagnifyingGlass,
  edit: PencilSimple,
  terminal: TerminalWindow,
  test: TerminalWindow,
  code: Code,
  question: Question,
  web: Globe,
  custom: FileCode,
  todo: ListChecks,
};

/** 用户消息里的图片缩略图：data URL 直接渲染；工作区路径经带鉴权的端点取 object URL。 */
function ChatImage({
  ticketNo,
  src,
  onZoom,
}: {
  ticketNo: string;
  src: string;
  onZoom: (src: string) => void;
}) {
  const [url, setUrl] = useState<string | null>(src.startsWith("data:") ? src : null);
  useEffect(() => {
    if (src.startsWith("data:")) {
      setUrl(src);
      return;
    }
    let alive = true;
    let made: string | null = null;
    const name = src.split("/").pop() ?? src;
    fetchBlobUrl(`/api/tickets/${ticketNo}/chat-images/${encodeURIComponent(name)}`)
      .then((u) => {
        if (alive) {
          made = u;
          setUrl(u);
        } else {
          URL.revokeObjectURL(u);
        }
      })
      .catch(() => {});
    return () => {
      alive = false;
      if (made) URL.revokeObjectURL(made);
    };
  }, [src, ticketNo]);
  if (!url) {
    return <span className="inline-block h-28 w-40 animate-pulse rounded-lg border border-edge bg-sunken" />;
  }
  return (
    <img
      src={url}
      alt="随消息发送的图片"
      className="max-h-56 max-w-[280px] cursor-zoom-in rounded-lg border border-edge object-contain"
      onClick={() => onZoom(url)}
    />
  );
}

/** 灯箱：点击/Esc 关闭，展示可得的最高清版本（后端存的是 ≤1024px 缩略图）。 */
function ImageLightbox({ src, onClose }: { src: string; onClose: () => void }) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [onClose]);
  return createPortal(
    <div
      className="fixed inset-0 z-[95] grid place-items-center bg-black/80 p-6 cursor-zoom-out"
      onClick={onClose}
    >
      <img
        src={src}
        alt="图片预览"
        className="max-h-[92vh] max-w-[92vw] rounded-xl border border-edge shadow-2xl"
      />
    </div>,
    document.body,
  );
}

/** 用户消息正文：引用标记（⟦引用⟧…⟦/引用⟧）还原为胶囊；图片缩略图置顶；其余文本照旧走 Markdown。 */
function UserMessageBody({
  ticketNo,
  text,
  images,
}: {
  ticketNo: string;
  text: string;
  images?: string[];
}) {
  const [zoom, setZoom] = useState<string | null>(null);
  const hasImages = !!images && images.length > 0;
  // 有图可渲时，文本里的 [图片 #n] / [图片引用 #n] 引用行完成使命，不再重复展示
  const display = hasImages
    ? text
        .split("\n")
        .filter((l) => !/^\[图片(引用)? #\d+\]/.test(l.trim()))
        .join("\n")
        .trim()
    : text;
  const segments = useMemo(() => parseQuotedText(display), [display]);
  return (
    <div className="space-y-1.5">
      {hasImages && (
        <div className="flex flex-wrap justify-end gap-1.5">
          {images!.map((src, i) => (
            <ChatImage key={i} ticketNo={ticketNo} src={src} onZoom={setZoom} />
          ))}
        </div>
      )}
      {segments
        ? segments.map((seg, i) =>
            seg.kind === "quote" ? (
              <div key={i} className="flex justify-end">
                <QuoteChip text={seg.text} tipRight />
              </div>
            ) : (
              seg.text.trim() && (
                <Markdown key={i} className="md-body">
                  {seg.text}
                </Markdown>
              )
            ),
          )
        : display && <Markdown className="md-body">{display}</Markdown>}
      {zoom && <ImageLightbox src={zoom} onClose={() => setZoom(null)} />}
    </div>
  );
}

function splitToolArgs(tool: ToolCallView): { toolName: string; argsPart: string } {  // todo 类工具行：api 层已把 argsSummary 写成紧凑摘要（避免整段 todos JSON 刷屏）。
  if (tool.icon === "todo") {
    return { toolName: tool.name || tool.toolName || "任务清单", argsPart: tool.argsSummary };
  }
  if (tool.toolName) {
    const rawArgs = tool.args !== undefined ? tool.args : tool.argsSummary.slice(tool.toolName.length);
    return { toolName: tool.name || tool.toolName, argsPart: rawArgs };
  }
  // 兼容未单独拆出 toolName 的历史旧数据：如 "bash{\"command\":\"...\"}" 或 "read_file ..."
  const summary = tool.argsSummary || tool.name || "";
  const match = summary.match(/^([a-zA-Z0-9_\-.:]+)([\s({[].*|$)/);
  if (match) {
    return { toolName: match[1], argsPart: match[2] || "" };
  }
  return { toolName: summary, argsPart: "" };
}

const ThinkingBlock = memo(function ThinkingBlock({
  thinking,
}: {
  thinking: NonNullable<Extract<ChatItem, { kind: "assistant" }>["thinking"]>;
}) {
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
});

const ToolRow = memo(function ToolRow({ tool }: { tool: ToolCallView }) {
  const [open, setOpen] = useState(false);
  const Icon = TOOL_ICONS[tool.icon] ?? TerminalWindow;
  const { toolName, argsPart } = splitToolArgs(tool);
  // IN 优先完整参数（live 行随 SSE 快照/分片累积，历史行为落库的 arguments_json），
  // 缺省时退回紧凑摘要；OUT 为工具输出（终态事件或历史 result_json）。
  const inText = tool.args !== undefined && tool.args !== "" ? tool.args : tool.argsSummary;
  const outText = tool.resultDetail;
  const expandable = inText.trim().length > 0 || !!outText;

  return (
    <div>
      <button
        className="w-full flex items-center gap-2.5 px-3 h-9 text-left hover:bg-raised/60 transition-colors cursor-pointer"
        onClick={() => expandable && setOpen(!open)}
      >
        {tool.status === "running" ? (
          <CircleNotch size={14} className="text-accent animate-[spin_0.9s_linear_infinite]" />
        ) : tool.status === "ok" ? (
          <Check size={14} className="text-accent" weight="bold" />
        ) : (
          <X size={14} className="text-danger" weight="bold" />
        )}
        <Icon size={14} className="text-dim shrink-0" />
        <span className="font-mono text-[11.5px] truncate flex-1 min-w-0">
          <strong className="font-semibold text-ink">{toolName}</strong>
          {argsPart && <span className="text-faint">{argsPart}</span>}
        </span>
        {tool.resultSummary && (
          <span className="font-mono text-[11px] text-faint shrink-0 hidden sm:inline">{tool.resultSummary}</span>
        )}
        {expandable &&
          (open ? <CaretDown size={11} className="text-faint" /> : <CaretRight size={11} className="text-faint" />)}
      </button>
      {open && expandable && (
        <div className="mx-3 mb-2 overflow-hidden rounded-md border border-edge bg-sunken text-[11px]">
          {inText.trim() && (
            <div className={outText ? "border-b border-edge" : undefined}>
              <div className="flex items-center gap-2 px-2.5 h-8">
                <span className="font-mono text-[10px] font-semibold tracking-wide text-info">IN</span>
                <span className="flex-1" />
                <CopyButton text={inText} label="复制输入参数" />
              </div>
              <pre className="max-h-44 overflow-auto px-2.5 pb-2 font-mono leading-relaxed text-dim whitespace-pre-wrap break-all">
                {inText}
              </pre>
            </div>
          )}
          {outText ? (
            <div>
              <div className="flex items-center gap-2 px-2.5 h-8">
                <span className="font-mono text-[10px] font-semibold tracking-wide text-accent">OUT</span>
                <span className="flex-1" />
                <CopyButton text={outText} label="复制输出结果" />
              </div>
              <pre className="max-h-44 overflow-auto px-2.5 pb-2 font-mono leading-relaxed text-dim whitespace-pre-wrap break-all">
                {outText}
              </pre>
            </div>
          ) : (
            tool.status === "running" && <div className="px-2.5 pb-2 text-faint">执行中，暂无输出…</div>
          )}
        </div>
      )}
    </div>
  );
});

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

// 流式期间 chat 数组每次补丁都换新引用，未受影响的消息行必须 memo 跳过重渲染，
// 否则整列表的 Markdown 全量重新解析，主线程卡顿放大滚动/点击的一切延迟
const AssistantMessage = memo(function AssistantMessage({
  item,
}: {
  item: Extract<ChatItem, { kind: "assistant" }>;
}) {
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
});

const SystemMessage = memo(function SystemMessage({
  item,
}: {
  item: Extract<ChatItem, { kind: "system" }>;
}) {
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
});

const RAIL_MIN_MESSAGES = 3; // 用户消息达到该数量才出现导航刻度（短会话用不上）
const RAIL_MIN_OVERFLOW = 120; // 内容超出视口该像素才需要跳转
const RAIL_HOVER_DELAY_MS = 180; // 悬浮该时长后才弹预览，避免扫过刻度时闪现
const RAIL_HIDE_GRACE_MS = 150; // 移出刻度后的宽限期：在刻度间移动时预览不闪烁

/**
 * 会话流右缘的消息刻度导航（openchamber 式）：
 * 每条用户消息一根横杠，贴着消息列（max-w-760）右缘垂直居中；
 * 当前视口所在的消息横杠加宽提亮；悬浮片刻弹出消息预览，点击平滑跳转到该消息。
 * 会话不够长（消息少或内容不溢出）时整条隐藏。
 */
function ChatRail({
  chat,
  scrollRef,
}: {
  chat: ChatItem[];
  scrollRef: RefObject<HTMLDivElement | null>;
}) {
  const userMsgs = useMemo(() => chat.filter((i) => i.kind === "user"), [chat]);
  const [tops, setTops] = useState<number[]>([]);
  const [overflowPx, setOverflowPx] = useState(0);
  const [viewportH, setViewportH] = useState(0);
  const [containerW, setContainerW] = useState(0);
  const [activeIdx, setActiveIdx] = useState(-1);
  const [hoverIdx, setHoverIdx] = useState<number | null>(null);
  const showTimer = useRef<number | null>(null);
  const hideTimer = useRef<number | null>(null);
  const mountedRef = useRef(false);

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      if (showTimer.current) window.clearTimeout(showTimer.current);
      if (hideTimer.current) window.clearTimeout(hideTimer.current);
    };
  }, []);

  const show = userMsgs.length >= RAIL_MIN_MESSAGES && overflowPx > RAIL_MIN_OVERFLOW;

  // 测量每条用户消息在滚动内容中的文档偏移：基准取内容原点（容器可视顶 − scrollTop），
  // 消息视口位置减基准即得内容坐标，与当前滚动位置无关——跳转 scrollTo 与刻度高亮
  // 共用该坐标系。（旧实现把 scrollTop 加进基准，偏差随滚动距离翻倍：滚到底后 tops
  // 全为负值，点击刻度 scrollTo(0) 直接跳到会话顶部、activeIdx 永远停在最后一根。）
  const measure = useCallback(() => {
    const el = scrollRef.current;
    if (!el) return;
    const base = el.getBoundingClientRect().top - el.scrollTop;
    const next: number[] = [];
    el.querySelectorAll<HTMLElement>("[data-chat-msg]").forEach((n) => {
      const top = n.getBoundingClientRect().top - base;
      // 消息按 DOM 顺序天然递增；monotonic 兜底吸收入场动画/图片加载造成的瞬时抖动
      next.push(next.length === 0 ? Math.max(0, top) : Math.max(next[next.length - 1], top));
    });
    setTops((prev) =>
      prev.length === next.length && prev.every((v, i) => Math.abs(v - next[i]) < 0.5) ? prev : next,
    );
    setOverflowPx(el.scrollHeight - el.clientHeight);
    setViewportH(el.clientHeight);
    setContainerW(el.clientWidth);
  }, [scrollRef]);

  useEffect(() => {
    measure();
  }, [measure, chat]);

  useEffect(() => {
    const el = scrollRef.current;
    if (!el) return;
    const ro = new ResizeObserver(measure);
    ro.observe(el);
    if (el.firstElementChild) ro.observe(el.firstElementChild);
    return () => ro.disconnect();
  }, [measure, scrollRef]);

  // 滚动位置 → 视口顶部附近所在的消息刻度
  useEffect(() => {
    const el = scrollRef.current;
    if (!el) return;
    let raf = 0;
    const onScroll = () => {
      cancelAnimationFrame(raf);
      raf = requestAnimationFrame(() => {
        const probe = el.scrollTop + Math.min(72, el.clientHeight * 0.12);
        let idx = -1;
        for (let i = 0; i < tops.length; i++) {
          if (tops[i] <= probe) idx = i;
          else break;
        }
        setActiveIdx(idx);
      });
    };
    el.addEventListener("scroll", onScroll, { passive: true });
    onScroll();
    return () => {
      cancelAnimationFrame(raf);
      el.removeEventListener("scroll", onScroll);
    };
  }, [tops, scrollRef]);

  const onTickEnter = (i: number) => {
    if (hideTimer.current) {
      window.clearTimeout(hideTimer.current);
      hideTimer.current = null;
    }
    if (hoverIdx === i) return;
    if (showTimer.current) window.clearTimeout(showTimer.current);
    showTimer.current = window.setTimeout(() => setHoverIdx(i), RAIL_HOVER_DELAY_MS);
  };
  const onTickLeave = () => {
    if (showTimer.current) {
      window.clearTimeout(showTimer.current);
      showTimer.current = null;
    }
    if (hideTimer.current) window.clearTimeout(hideTimer.current);
    hideTimer.current = window.setTimeout(() => setHoverIdx(null), RAIL_HIDE_GRACE_MS);
  };

  const jumpTo = (i: number) => {
    const el = scrollRef.current;
    if (!el) return;
    el.scrollTo({ top: Math.max(0, tops[i] - 16), behavior: "smooth" });
  };

  // 刻度条锚定滚动区右缘（滚动条内侧）；消息列已预留右内边距，气泡不再被压住
  const railLeft = Math.max(6, containerW - 34);
  const bubbleW = Math.max(180, Math.min(300, railLeft - 12));
  const railHeight = Math.min(userMsgs.length * 13, Math.max(90, viewportH * 0.5));

  return (
    <AnimatePresence>
      {show && (
        <motion.div
          key="chat-rail"
          initial={{ opacity: 0, x: 10, y: "-50%" }}
          animate={{ opacity: 1, x: 0, y: "-50%" }}
          exit={{ opacity: 0, x: 10, y: "-50%" }}
          transition={{ duration: 0.25, ease: [0.16, 1, 0.3, 1] }}
          className="absolute top-1/2 z-20"
          style={{ left: railLeft, height: railHeight }}
          onWheel={(e) => {
            // 刻度条悬在滚动容器外侧（absolute 兄弟节点），滚轮落在其上不会驱动聊天滚动；
            // 手动转发给滚动容器，避免“光标停在刻度条上滚轮失灵”的观感
            const el = scrollRef.current;
            if (!el) return;
            el.scrollTop += e.deltaMode === 1 ? e.deltaY * 16 : e.deltaY;
          }}
        >
          <div className="flex h-full flex-col">
            {userMsgs.map((m, i) => {
              const active = i === activeIdx;
              return (
                <motion.button
                  key={m.id}
                  initial={{ opacity: 0, x: 8 }}
                  animate={{ opacity: 1, x: 0 }}
                  transition={{
                    duration: 0.2,
                    // 仅首次出现的整批刻度做级联入场；流式新增的单根刻度即时出现
                    delay: mountedRef.current ? 0 : Math.min(i * 0.02, 0.24),
                  }}
                  className="group/tick relative min-h-[6px] flex-1 w-7 cursor-pointer"
                  aria-label={`跳转到第 ${i + 1} 条消息`}
                  onMouseEnter={() => onTickEnter(i)}
                  onMouseLeave={onTickLeave}
                  onClick={() => jumpTo(i)}
                >
                  <motion.span
                    animate={{ width: active ? 16 : 10 }}
                    transition={{ duration: 0.18, ease: "easeOut" }}
                    className={`absolute left-1/2 top-1/2 h-[2px] -translate-x-1/2 -translate-y-1/2 rounded-full transition-colors duration-150 ${
                      active ? "bg-ink" : "bg-ink/25 group-hover/tick:bg-ink/55"
                    }`}
                  />
                  <AnimatePresence>
                    {hoverIdx === i && (
                      <motion.div
                        initial={{ opacity: 0, x: -10, y: "-42%", scale: 0.9 }}
                        animate={{ opacity: 1, x: 0, y: "-50%", scale: 1 }}
                        exit={{
                          opacity: 0,
                          x: -6,
                          y: "-53%",
                          scale: 0.96,
                          transition: { duration: 0.12, ease: "easeOut" },
                        }}
                        transition={{ type: "spring", stiffness: 380, damping: 24, mass: 0.7 }}
                        className="pointer-events-none absolute top-1/2 right-[calc(100%+10px)] z-30 rounded-xl border border-edge bg-overlay px-3.5 py-2.5 shadow-xl"
                        style={{ width: bubbleW }}
                      >
                        <div className="line-clamp-4 text-[12px] leading-relaxed text-ink whitespace-pre-wrap break-words">
                          {stripQuoteMarkers(m.text)}
                        </div>
                      </motion.div>
                    )}
                  </AnimatePresence>
                </motion.button>
              );
            })}
          </div>
        </motion.div>
      )}
    </AnimatePresence>
  );
}

export function ChatStream({ ticketNo }: { ticketNo: string }) {
  const chat = useApp((s) => s.chats[ticketNo] ?? NO_CHAT);
  const sessionId = useApp((s) => s.activeSessionId[ticketNo] ?? "");
  const cancelled = useApp((s) => s.tickets.find((t) => t.ticketNo === ticketNo)?.stage === "CANCELLED");
  const bottomRef = useRef<HTMLDivElement>(null);
  const scrollRef = useRef<HTMLDivElement>(null);
  const stick = useRef(true);
  const lastTop = useRef(0);

  useEffect(() => {
    const el = scrollRef.current;
    if (!el) return;
    lastTop.current = el.scrollTop;
    const onScroll = () => {
      const goingUp = el.scrollTop < lastTop.current - 1;
      lastTop.current = el.scrollTop;
      // 方向感知吸附：用户向上滚立即解除（哪怕只滚出一格，流式更新不再把视口拽回底部），
      // 向下滚回贴底范围才恢复。旧逻辑按“距底 <120px”单向判定，从底部上滚的头几下
      // 始终落在阈值内，配合每次 chat 更新的 smooth 回底，表现为“卡在最底部滚不动”。
      if (goingUp) stick.current = false;
      else if (el.scrollHeight - el.scrollTop - el.clientHeight < 120) stick.current = true;
    };
    el.addEventListener("scroll", onScroll, { passive: true });
    return () => el.removeEventListener("scroll", onScroll);
  }, []);

  const last = chat[chat.length - 1];
  const streaming = !!last && last.kind === "assistant" && last.streaming === true;

  useEffect(() => {
    if (!stick.current) return;
    if (streaming) {
      // 流式期间高频更新会把 smooth 动画反复打断重启，观感即“滚不动”，改为瞬时贴底
      const el = scrollRef.current;
      if (el) el.scrollTop = el.scrollHeight;
    } else {
      bottomRef.current?.scrollIntoView({ behavior: "smooth", block: "end" });
    }
  }, [chat, streaming]);

  return (
    <div className="relative flex-1 min-h-0">
      <div ref={scrollRef} className="absolute inset-0 overflow-y-auto pl-5 pr-[42px] py-4">
        <div className="max-w-[760px] mx-auto space-y-4">
          {chat.map((item) =>
            item.kind === "user" ? (
              /* quote-line 见 styles.css：引用胶囊的原文提示跨行显示时需要整行提层 */
              <div
                key={item.id}
                data-chat-msg={item.id}
                className="chat-msg-line relative flex justify-end animate-rise"
              >
                <div className="max-w-[82%] rounded-xl rounded-tr-sm border border-edge bg-raised px-3.5 py-2 text-[13.5px] leading-relaxed">
                  <UserMessageBody ticketNo={ticketNo} text={item.text} images={item.images} />
                </div>
              </div>
            ) : item.kind === "assistant" ? (
              <AssistantMessage key={item.id} item={item} />
            ) : item.kind === "permission" ? (
              <PermissionCard
                key={item.id}
                ticketNo={ticketNo}
                sessionId={sessionId}
                item={item}
                locked={cancelled}
              />
            ) : item.kind === "question" ? (
              <QuestionCard
                key={item.id}
                ticketNo={ticketNo}
                sessionId={sessionId}
                item={item}
                locked={cancelled}
              />
            ) : (
              <SystemMessage key={item.id} item={item} />
            ),
          )}
          <div ref={bottomRef} />
        </div>
      </div>
      <ChatRail chat={chat} scrollRef={scrollRef} />
    </div>
  );
}
