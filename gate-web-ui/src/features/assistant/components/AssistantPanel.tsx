/**
 * LLM 小助手悬浮面板（T-109 原生内置）：跨视图常驻、标题栏拖拽、右下角缩放。
 * 界面完全复用本体会话的共享原语（ChatPrimitives：用户气泡壳 / 助手 Shell /
 * ReplyBody / ReplyFooter；useStickyScroll 贴底滚动；composer-shell 输入区），
 * 不存在消息渲染的第二实现——视觉跟随本体演进自动同步。
 *
 * - 数据源：Provider/模型来自 LLM 设置中心（net/llm 数据面与插件 llm.chat 同源）；
 * - 流式增量只进 store，关闭面板不打断生成；
 * - 布局（位置/尺寸/开合/最小化）与历史持久化在 localStorage（store/prefs）。
 */
import { useT } from "@/i18n";
import { useCallback, useEffect, useRef, useState } from "react";
import { AnimatePresence, motion } from "motion/react";
import {
  ArrowClockwise,
  Brain,
  CornersOut,
  Eraser,
  Minus,
  PaperPlaneRight,
  PlugsConnected,
  Sparkle,
  Stop,
  WarningCircle,
  X,
} from "@phosphor-icons/react";
import { openConnect, useApp } from "@/store";
import { formatDuration } from "@/shared/format";
import {
  AssistantShell,
  ReplyBody,
  ReplyFooter,
  UserBubble,
} from "@/shared/components/ChatPrimitives";
import { useStickyScroll } from "@/shared/hooks";
import {
  clampAssistantIntoViewport,
  clearAssistantHistory,
  commitAssistantLayout,
  ensureAssistantProviders,
  getAssistantTurnStartedAt,
  goSettingsTab,
  loadAssistantProviders,
  selectAssistantProviders,
  sendAssistantMessage,
  setAssistantDraft,
  setAssistantMinimized,
  setAssistantOpen,
  setAssistantPosLive,
  setAssistantSizeLive,
  stopAssistantMessage,
} from "@/features/assistant";
import { ASSISTANT_SIZE_MIN } from "@/store/prefs";
import type { AssistantChatEntry } from "@/shared/types";
import { AssistantModelPicker } from "./AssistantModelPicker";

const EDGE = 8;
const HEADER_H = 40;

/** 空态快捷提问（点击填入草稿，可再编辑）。 */
const SUGGESTION_KEYS = ["asst.suggestions.0", "asst.suggestions.1", "asst.suggestions.2", "asst.suggestions.3"] as const;

function clamp(v: number, lo: number, hi: number): number {
  return Math.min(Math.max(v, lo), Math.max(lo, hi));
}

type DragMode = "move" | "resize";

export function AssistantPanel() {
  const t = useT();
  const open = useApp((s) => s.assistantOpen);
  const minimized = useApp((s) => s.assistantMinimized);
  const pos = useApp((s) => s.assistantPos);
  const size = useApp((s) => s.assistantSize);
  const messages = useApp((s) => s.assistantMessages);
  const draft = useApp((s) => s.assistantDraft);
  const loading = useApp((s) => s.assistantLoading);
  const streaming = useApp((s) => s.assistantStreaming);
  const modelSel = useApp((s) => s.assistantModelSel);
  const mode = useApp((s) => s.mode);
  const providers = useApp(selectAssistantProviders);
  const providersLoading = useApp((s) => s.assistantProvidersLoading);
  const providersError = useApp((s) => s.assistantProvidersError);
  const askNonce = useApp((s) => s.assistantAskNonce);

  const taRef = useRef<HTMLTextAreaElement>(null);
  const drag = useRef<
    | { mode: DragMode; pointerId: number; startX: number; startY: number; ox: number; oy: number; ow: number; oh: number; moved: boolean }
    | null
  >(null);

  /* 视口夹取：pos 为 null 时按尺寸右下角锚定；resize 窗口后回收越界面板。 */
  const [vp, setVp] = useState(() => ({ w: window.innerWidth, h: window.innerHeight }));
  useEffect(() => {
    const onResize = () => {
      setVp({ w: window.innerWidth, h: window.innerHeight });
      clampAssistantIntoViewport();
    };
    window.addEventListener("resize", onResize);
    return () => window.removeEventListener("resize", onResize);
  }, []);
  const maxX = Math.max(EDGE, vp.w - size.w - EDGE);
  const maxY = Math.max(EDGE, vp.h - HEADER_H - EDGE);
  const anchored = pos ?? { x: maxX - 8, y: 56 };
  const x = clamp(anchored.x, EDGE, maxX);
  const y = clamp(anchored.y, EDGE, maxY);
  const h = clamp(size.h, ASSISTANT_SIZE_MIN.h, vp.h - y - EDGE);

  const canSend = draft.trim() !== "" && !loading && !!modelSel.model;
  const showShell = mode === "live";
  const modelUnavailable =
    showShell && !providersLoading && (!!providersError || providers.length === 0 || !modelSel.model);

  /* Provider 拉取兜底：刷新/快照恢复 open=true 时不经 setAssistantOpen，也要在
   * 面板可见时补拉一次，否则已配置模型会被误判成「还没有可用的模型」假空态。 */
  useEffect(() => {
    if (open && !minimized) ensureAssistantProviders();
  }, [open, minimized, mode]);

  /* 贴底滚动 = 共享 useStickyScroll（与本体 ChatStream 同一口径）；
   * attachKey 跟随开合/最小化，重挂载后恢复监听与贴底。 */
  const { scrollRef, bottomRef } = useStickyScroll(
    [messages, streaming, loading],
    loading && open && !minimized,
    open && !minimized,
  );

  /* 打开/划选提问聚焦；textarea 自增高（composer-ta 无固定高，上限 140px）。 */
  useEffect(() => {
    if (open && !minimized && showShell) {
      const ta = taRef.current;
      if (ta) {
        ta.focus();
        ta.setSelectionRange(ta.value.length, ta.value.length);
      }
    }
  }, [open, minimized, askNonce, showShell]);

  useEffect(() => {
    const ta = taRef.current;
    if (!ta) return;
    ta.style.height = "auto";
    ta.style.height = `${Math.min(ta.scrollHeight, 140)}px`;
  }, [draft]);

  /* ─── 拖拽 / 缩放（pointer capture；松手落盘） ─── */
  const beginDrag = useCallback(
    (e: React.PointerEvent, m: DragMode) => {
      if (e.button !== 0) return;
      if (m === "move" && (e.target as HTMLElement).closest("button, select, [data-no-drag]")) return;
      drag.current = { mode: m, pointerId: e.pointerId, startX: e.clientX, startY: e.clientY, ox: x, oy: y, ow: size.w, oh: h, moved: false };
      (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
      e.preventDefault();
    },
    [x, y, size.w, h],
  );

  const moveDrag = useCallback((e: React.PointerEvent) => {
    const d = drag.current;
    if (!d || e.pointerId !== d.pointerId) return;
    const dx = e.clientX - d.startX;
    const dy = e.clientY - d.startY;
    if (dx !== 0 || dy !== 0) d.moved = true;
    const vw = window.innerWidth;
    const vh = window.innerHeight;
    if (d.mode === "move") {
      setAssistantPosLive({
        x: clamp(d.ox + dx, EDGE, Math.max(EDGE, vw - d.ow - EDGE)),
        y: clamp(d.oy + dy, EDGE, Math.max(EDGE, vh - HEADER_H - EDGE)),
      });
    } else {
      setAssistantSizeLive({
        w: clamp(d.ow + dx, ASSISTANT_SIZE_MIN.w, vw - d.ox - EDGE),
        h: clamp(d.oh + dy, ASSISTANT_SIZE_MIN.h, vh - d.oy - EDGE),
      });
    }
  }, []);

  const endDrag = useCallback((e: React.PointerEvent) => {
    const d = drag.current;
    if (!d || e.pointerId !== d.pointerId) return;
    const moved = d.moved;
    drag.current = null;
    if (moved) commitAssistantLayout();
  }, []);

  const onKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      if (canSend) void sendAssistantMessage();
    }
  };

  /** 错误气泡的重试：把最后一条用户消息回填草稿。 */
  const retryLast = () => {
    const lastUser = [...messages].reverse().find((m) => m.role === "user");
    if (lastUser) setAssistantDraft(lastUser.content);
    taRef.current?.focus();
  };

  const emptyGate =
    mode !== "live" ? (
      <GateCard
        Icon={PlugsConnected}
        title={t("asst.needBackendTitle")}
        desc={t("asst.needBackendDesc")}
        action={
          <button className="btn btn-primary btn-sm" onClick={openConnect}>
            {t("settings.needBackend.connect")}
          </button>
        }
      />
    ) : providersLoading ? (
      <div className="flex items-center gap-2 text-[12px] text-faint">
        <span className="w-1.5 h-1.5 rounded-full bg-accent animate-breathe" />
        {t("llm.loadingProviders")}
      </div>
    ) : providersError ? (
      <GateCard
        Icon={WarningCircle}
        title={t("asst.providerFailed")}
        desc={providersError}
        tone="warn"
        action={
          <button className="btn btn-sm" onClick={() => void loadAssistantProviders(true)}>
            <ArrowClockwise size={12} /> {t("common.retry")}
          </button>
        }
      />
    ) : providers.length === 0 || !modelSel.model ? (
      <GateCard
        Icon={Sparkle}
        title={t("asst.noModelsTitle")}
        desc={t("asst.noModelsDesc")}
        action={
          <button className="btn btn-primary btn-sm" onClick={() => goSettingsTab("llm")}>
            {t("assistant.settings.gotoLlm")}
          </button>
        }
      />
    ) : null;

  return (
    <AnimatePresence>
      {open && (
        <motion.div
          key="assistant-panel"
          initial={{ opacity: 0, y: 10, scale: 0.98 }}
          animate={{ opacity: 1, y: 0, scale: 1 }}
          exit={{ opacity: 0, y: 8, scale: 0.98 }}
          transition={{ type: "spring", stiffness: 480, damping: 34 }}
          className="fixed z-[75] flex flex-col rounded-2xl border border-edge-strong bg-canvas overflow-hidden"
          style={{
            left: x,
            top: y,
            width: size.w,
            height: minimized ? HEADER_H : h,
            maxHeight: vp.h - y - EDGE,
            boxShadow: "0 24px 48px -12px rgba(0,0,0,0.55), 0 0 0 1px rgba(255,255,255,0.04)",
          }}
        >
          {/* 标题栏：拖拽手柄（按钮除外） */}
          <div
            className={`flex h-10 shrink-0 items-center gap-2 border-b bg-surface px-2.5 select-none touch-none cursor-grab active:cursor-grabbing ${
              minimized ? "border-transparent" : "border-edge"
            }`}
            onPointerDown={(e) => beginDrag(e, "move")}
            onPointerMove={moveDrag}
            onPointerUp={endDrag}
            onPointerCancel={endDrag}
          >
            <span className="w-6 h-6 rounded-md bg-accent-dim grid place-items-center text-accent shrink-0">
              <Sparkle size={13} weight="fill" />
            </span>
            <span className="shrink-0 text-[12.5px] font-semibold text-ink">{t("asst.title")}</span>
            {loading && <span className="w-1.5 h-1.5 shrink-0 rounded-full bg-accent animate-breathe" />}
            <span className="flex-1 min-w-0" />
            {!minimized && messages.length > 0 && !loading && (
              <button className="icon-btn" title={t("asst.clearChat")} onClick={clearAssistantHistory}>
                <Eraser size={13} />
              </button>
            )}
            <button className="icon-btn" title={minimized ? t("asst.expand") : t("asst.minimize")} onClick={() => setAssistantMinimized(!minimized)}>
              {minimized ? <CornersOut size={13} /> : <Minus size={13} />}
            </button>
            <button className="icon-btn hover:!text-danger" title={t("common.close")} onClick={() => setAssistantOpen(false)}>
              <X size={13} />
            </button>
          </div>

          {!minimized && (
            <>
              {/* 消息流（本体共享原语渲染） */}
              <div ref={scrollRef} className="flex-1 min-h-0 overflow-y-auto px-3.5 py-3">
                {messages.length === 0 && emptyGate ? (
                  <div className="pt-4">{emptyGate}</div>
                ) : messages.length === 0 ? (
                  <div className="pt-6 text-center">
                    <div className="mx-auto grid place-items-center w-11 h-11 rounded-2xl bg-accent-dim border border-accent/25">
                      <Sparkle size={20} weight="fill" className="text-accent" />
                    </div>
                    <div className="mt-3 text-[13px] font-medium text-ink">{t("asst.welcomeTitle")}</div>
                    <div className="mt-1 text-[11.5px] text-faint leading-relaxed">
                      {t("asst.welcomeHint")}
                    </div>
                    <div className="mt-4 flex flex-wrap justify-center gap-1.5 px-2">
                      {SUGGESTION_KEYS.map((key) => (
                        <button
                          key={key}
                          data-no-drag
                          className="composer-chip"
                          onClick={() => {
                            setAssistantDraft(t(key));
                            taRef.current?.focus();
                          }}
                        >
                          {t(key)}
                        </button>
                      ))}
                    </div>
                  </div>
                ) : (
                  <div className="space-y-4">
                    {messages.map((m) => (
                      <AssistantEntryRow key={m.id} entry={m} onRetry={retryLast} />
                    ))}
                    {loading && <AssistantLiveRow streaming={streaming} />}
                    <div ref={bottomRef} />
                  </div>
                )}
              </div>

              {/* 有历史但模型不可用：输入区上方修复横幅 */}
              {messages.length > 0 && (mode !== "live" || modelUnavailable) && (
                <div className="mx-3 mb-1 rounded-lg border border-warn/30 bg-warn/10 px-2.5 py-1.5 text-[11.5px] text-warn">
                  {mode !== "live" ? (
                    <>
                      {t("asst.demoBlocked")}{" "}
                      <button className="underline underline-offset-2 cursor-pointer" onClick={openConnect}>
                        {t("settings.needBackend.connect")}
                      </button>
                    </>
                  ) : (
                    <>
                      {t("asst.modelUnavailable")}{" "}
                      <button className="underline underline-offset-2 cursor-pointer" onClick={() => goSettingsTab("llm")}>
                        {t("asst.gotoLlmFix")}
                      </button>
                    </>
                  )}
                </div>
              )}

              {/* 输入区（本体 Composer 同款 shell） */}
              {showShell && (
                <div className="shrink-0 border-t border-edge bg-surface/50 p-2.5">
                  <div className={`composer-shell ${loading ? "composer-shell-busy" : ""}`}>
                    <textarea
                      ref={taRef}
                      rows={1}
                      value={draft}
                      data-no-drag
                      onChange={(e) => setAssistantDraft(e.target.value)}
                      onKeyDown={onKeyDown}
                      placeholder={t("asst.inputPlaceholder")}
                      className="composer-ta"
                      style={{ maxHeight: 140 }}
                    />
                    <div className="flex items-center gap-2 px-2.5 pb-2.5 pt-0.5">
                      <AssistantModelPicker />
                      <span className="flex-1" />
                      {draft.length > 0 && <span className="composer-count">{draft.length}</span>}
                      {loading ? (
                        <button className="composer-stop" title={t("composer.abortTip")} aria-label={t("composer.abortTip")} onClick={stopAssistantMessage}>
                          <Stop size={14} weight="fill" />
                        </button>
                      ) : (
                        <button
                          className="composer-send"
                          title={modelSel.model ? t("asst.sendTip") : t("asst.sendPickModel")}
                          aria-label={t("asst.sendTip")}
                          disabled={!canSend}
                          onClick={() => void sendAssistantMessage()}
                        >
                          <PaperPlaneRight size={15} weight="fill" />
                        </button>
                      )}
                    </div>
                  </div>
                </div>
              )}
            </>
          )}

          {/* 右下角缩放手柄 */}
          {!minimized && (
            <div
              className="absolute bottom-0 right-0 h-4 w-4 cursor-nwse-resize touch-none"
              onPointerDown={(e) => beginDrag(e, "resize")}
              onPointerMove={moveDrag}
              onPointerUp={endDrag}
              onPointerCancel={endDrag}
            >
              <svg viewBox="0 0 16 16" className="h-full w-full text-faint opacity-60">
                <path d="M11 15 15 11M15 15 14 15M13 15 15 13" stroke="currentColor" strokeWidth="1.2" fill="none" />
              </svg>
            </div>
          )}
        </motion.div>
      )}
    </AnimatePresence>
  );
}

/* ─── 消息行（共享原语组装，无第二份渲染实现） ─── */

function AssistantEntryRow({ entry, onRetry }: { entry: AssistantChatEntry; onRetry: () => void }) {
  const t = useT();
  if (entry.role === "user") {
    return (
      <UserBubble>
        <ReplyBody text={entry.content} />
      </UserBubble>
    );
  }

  if (entry.error) {
    return (
      <AssistantShell name={t("asst.requestFailed")} ts={entry.ts} tone="danger" badge={<WarningCircle size={13} weight="fill" />}>
        <div className="rounded-lg border border-danger/35 bg-danger/10 px-3 py-2">
          <div className="text-[12.5px] leading-relaxed text-danger break-words">{entry.content}</div>
          <button
            className="mt-1.5 text-[11px] text-dim underline underline-offset-2 hover:text-ink cursor-pointer"
            onClick={onRetry}
          >
            {t("asst.retryLast")}
          </button>
        </div>
      </AssistantShell>
    );
  }

  return (
    <AssistantShell ts={entry.ts}>
      <ReplyBody text={entry.content} />
      <ReplyFooter model={entry.model} durationMs={entry.ms} copyText={entry.content} />
    </AssistantShell>
  );
}

/** 流式中的回合行：「思考中 N 秒」状态条（无正文时）/ md-body + 闪烁光标（有正文时）。 */
function AssistantLiveRow({ streaming }: { streaming: string }) {
  const t = useT();
  const [tick, setTick] = useState(0);
  useEffect(() => {
    const t = setInterval(() => setTick((x) => x + 1), 1000);
    return () => clearInterval(t);
  }, []);
  const startedAt = getAssistantTurnStartedAt();
  const elapsed = Date.now() - startedAt;
  void tick;
  return (
    <AssistantShell ts={startedAt}>
      {!streaming ? (
        <div className="w-full flex items-center gap-2 px-3 h-8 rounded-lg border border-edge bg-sunken text-[12px] text-dim">
          <Brain size={14} className="text-info" weight="fill" />
          <span>{t("asst.thinking")}</span>
          <span className="text-faint tabular-nums">{formatDuration(elapsed) ?? t("chat.zeroDuration")}</span>
          <span className="w-1.5 h-1.5 rounded-full bg-accent animate-breathe" />
        </div>
      ) : (
        <ReplyBody text={streaming} streaming />
      )}
    </AssistantShell>
  );
}

function GateCard({
  Icon,
  title,
  desc,
  action,
  tone = "default",
}: {
  Icon: typeof Sparkle;
  title: string;
  desc: string;
  action: React.ReactNode;
  tone?: "default" | "warn";
}) {
  return (
    <div
      className={`rounded-xl border p-4 text-center ${
        tone === "warn" ? "border-warn/30 bg-warn/10" : "border-dashed border-edge-strong bg-sunken/50"
      }`}
    >
      <Icon size={18} className={`mx-auto ${tone === "warn" ? "text-warn" : "text-faint"}`} />
      <div className="mt-2 text-[12.5px] font-medium text-ink">{title}</div>
      <div className="mt-0.5 text-[11.5px] text-faint leading-relaxed break-words">{desc}</div>
      <div className="mt-3 flex justify-center">{action}</div>
    </div>
  );
}
