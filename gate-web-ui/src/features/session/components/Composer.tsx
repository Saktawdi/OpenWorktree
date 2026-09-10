import { useEffect, useMemo, useRef, useState } from "react";
import {
  ArrowDown,
  ArrowUp,
  Brain,
  CaretDown,
  Check,
  CheckCircle,
  Clock,
  Cpu,
  Eye,
  Lightning,
  Lock,
  LockKey,
  MagnifyingGlass,
  PaperPlaneRight,
  PencilSimple,
  Quotes,
  ShieldCheck,
  Sparkle,
  Stop,
  Ticket,
  Wrench,
  X as XIcon,
} from "@phosphor-icons/react";
import type { Icon } from "@phosphor-icons/react";
import { actions } from "@/app/actions";
import { appStore, NO_CHAT, NO_QUOTES, showToast, useApp } from "@/store";
import { ChatActionChips } from "@/app/plugins/components/ChatActionChips";
import {
  addMessageToQueue,
  addPendingQuote,
  clearComposerDraft,
  clearDraftModelSel,
  clearPendingQuotes,
  draftCatalogFromOc,
  kickQueuePump,
  popQueuedMessageToInput,
  registerComposerBridge,
  removePendingQuote,
  removeQueuedMessage,
  reorderQueuedMessages,
  setComposerDraft,
  setPendingQuotes,
  splitModelRef,
  updatePendingQuoteText,
  uploadChatFile,
} from "@/features/session";
import { PermissionModeCycler } from "./PermissionModeCycler";
import { setAgentId } from "@/features/agent";
import { formatTokens, variantLabel } from "@/shared/format";
import {
  QUOTE_PASTE_MIN_CHARS,
  stripQuoteMarkers,
  wrapQuote,
} from "@/shared/quotes";
import { QuoteChip } from "@/shared/components/QuoteChip";
import { QuoteEditCard } from "@/shared/components/QuoteEditCard";
import { GroupedModelMenu } from "@/shared/components/GroupedModelMenu";
import {
  extractAbsolutePath,
  isAttachableImage,
  toPendingAttachment,
  withImageCitations,
} from "@/shared/attachments";
import type {
  CatalogProvider,
  PendingAttachment,
  QueuedMessage,
  SessionModelSel,
} from "@/shared/types";
import { useT } from "@/i18n";

/** 粘贴/拖入文件的大小上限（MB）：与后端 /chat-files 端点的落盘上限一致。 */
const MAX_CHAT_FILE_MB = 50;

/** 无排队消息时的稳定空引用（避免反复创建数组引发重渲染）。 */
const NO_QUEUE: QueuedMessage[] = [];

/** clipboardData.getData 在部分 MIME/浏览器组合下会抛错：包一层，失败返回空串。 */
function tryData(read: () => string): string {
  try {
    return read();
  } catch {
    return "";
  }
}

/**
 * 外部粘贴文本是否「成段」：满足其一即收进引用胶囊而非直接插入正文——
 * 多行（含换行/制表缩进的整段内容）或长度达到阈值（URL/路径等短单行不受影响）。
 */
function isPasteQuoteWorthy(text: string): boolean {
  const t = text.trim();
  if (!t) return false;
  return t.includes("\n") || t.includes("\t") || t.length >= QUOTE_PASTE_MIN_CHARS;
}

function AgentPicker({ ticketNo }: { ticketNo: string }) {
  const t = useT();
  const agents = useApp((s) => s.agents);
  const agentId = useApp((s) => s.agentId);
  // agent 是会话级 1:1 且创建时固化：会话一激活（哪怕还没发过消息）选择器即锁定，
  // 切换只改「新会话默认」、对本会话无效；「新建会话」进入草稿态后解锁重选。
  const hasSession = useApp((s) => (s.activeSessionId[ticketNo] ?? "") !== "");
  const locked =
    useApp((s) => (s.chats[ticketNo] ?? NO_CHAT).some((m) => m.kind === "user")) ||
    hasSession;
  const [open, setOpen] = useState(false);
  const current = agents.find((a) => a.id === agentId) ?? agents[0];

  /* 锁定后向左收缩直至移除，为右侧控件腾出空间；
     挂载时即已锁定（如直接打开进行中的工单）则不渲染、不播动画。 */
  const [phase, setPhase] = useState<"idle" | "collapsing" | "gone">(
    locked ? "gone" : "idle",
  );

  useEffect(() => {
    if (locked) {
      setOpen(false);
      setPhase((p) => (p === "idle" ? "collapsing" : p));
    } else {
      setPhase("idle");
    }
  }, [locked]);

  if (phase === "gone") return null;

  return (
    <div className="relative">
      {/* 收缩动画的裁剪层只包住按钮本身：若连同下拉菜单一起包住，
          向上弹出的菜单（absolute bottom-9）会被 overflow-hidden 裁成不可见，
          表现为点击按钮无反应。 */}
      <div
        aria-hidden={phase === "collapsing"}
        className={`overflow-hidden whitespace-nowrap transition-all duration-300 ease-[cubic-bezier(0.16,1,0.3,1)] ${
          phase === "collapsing"
            ? "max-w-0 opacity-0 pointer-events-none"
            : "max-w-[360px] animate-rise"
        }`}
        onTransitionEnd={(e) => {
          if (phase === "collapsing" && e.propertyName === "max-width") setPhase("gone");
        }}
      >
        <button
          className="composer-btn disabled:opacity-50 disabled:pointer-events-none"
          onClick={() => setOpen(!open)}
          disabled={locked}
          title={locked ? t("composer.agentLockedTip") : t("composer.agentPickTip")}
        >
          <Sparkle size={12} className={locked ? "text-faint" : "text-accent"} weight="fill" />
          {current ? (current.model ? `${current.name} · ${current.model}` : current.name) : t("composer.pickAgent")}
          {locked ? <Lock size={11} className="text-faint" weight="fill" /> : <CaretDown size={11} />}
        </button>
      </div>
      {open && !locked && (
        <>
          <div className="fixed inset-0 z-30" onClick={() => setOpen(false)} />
          <div className="absolute bottom-9 left-0 z-40 w-[260px] card p-1.5 shadow-2xl shadow-black/50 animate-rise">
            {agents.map((a) => (
              <button
                key={a.id}
                className={`w-full flex items-center gap-2 px-2.5 h-9 rounded-lg text-left text-[12.5px] cursor-pointer transition-colors ${
                  a.id === agentId ? "bg-raised text-ink" : "text-dim hover:bg-raised hover:text-ink"
                }`}
                onClick={() => {
                  setAgentId(a.id);
                  // 换 Agent 后原模型/推理选择大概率不适用：清空草稿选择，回退新 Agent 默认。
                  clearDraftModelSel(ticketNo);
                  setOpen(false);
                }}
              >
                <Sparkle size={13} className={a.cli === "claude" ? "text-accent" : "text-info"} weight="fill" />
                <span className="font-medium">{a.name}</span>
                <span className="font-mono text-[11px] text-faint">{a.model}</span>
                <span className="flex-1" />
                {a.id === agentId && <Check size={13} className="text-accent" weight="bold" />}
              </button>
            ))}
          </div>
        </>
      )}
    </div>
  );
}

/* ─── 会话内实时切换模型 / 推理强度（参考 OpenChamber ModelControls） ─── */

/**
 * claude 会话的推理强度档位：claude --effort 接受 low/medium/high/xhigh/max，但网关的
 * output_config.effort 枚举只认这四档（xhigh 会被 400 拒绝），取交集。与 opencode 走
 * serve 目录 variants 的路径不同，claude 的档位是 CLI 固有枚举，不随模型目录变化。
 */
const CLAUDE_EFFORTS = ["low", "medium", "high", "max"];

/**
 * claude 原生模型预设（claude --model 接受的别名，参考 open-design 的分组）：
 * "默认"=清空覆盖、不传 --model，由 claude 自身配置决定；其余以别名直传。
 * 与目录无关——网关自定义模型仍走下方自定义输入。
 */
const CLAUDE_MODEL_PRESETS: { labelKey: "composer.modelDefault" | "composer.modelPreset"; id?: string; clear?: boolean }[] = [
  { labelKey: "composer.modelDefault", clear: true },
  { labelKey: "composer.modelPreset", id: "haiku" },
  { labelKey: "composer.modelPreset", id: "sonnet" },
  { labelKey: "composer.modelPreset", id: "opus" },
  { labelKey: "composer.modelPreset", id: "fable" },
];

interface EffectiveSel extends SessionModelSel {
  /** True when resolved from persisted override rather than config defaults. */
  overridden: boolean;
}

function useEffectiveSel(ticketNo: string): {
  sessionId: string;
  sel: EffectiveSel | null;
  providers: CatalogProvider[];
  currentVariants: string[];
  /** 当前选中模型是否支持图片输入；catalog 缺失时为 undefined（未知，不拦截）。 */
  imageSupported?: boolean;
  /** claude 会话走独立路径：档位是 CLI 固有枚举，模型可自定义输入（不依赖目录）。 */
  isClaude: boolean;
  /** claude 自定义模型落到哪个 provider 分组（Agent 绑定的 provider，缺省 custom）。 */
  agentProviderId: string | null;
} {
  const sessionId = useApp((s) => s.activeSessionId[ticketNo] ?? "");
  const sessionProviders = useApp((s) => (sessionId ? s.sessionModels[sessionId] : undefined));
  const ocProviders = useApp((s) => s.ocProviders);
  // 会话态读已持久化的覆盖；草稿态读草稿暂存（随首条消息持久化）。
  const stored = useApp((s) => (sessionId ? s.sessionModelSel[sessionId] : s.draftModelSel[ticketNo]));
  const sess = useApp((s) => (s.sessions[ticketNo] ?? []).find((x) => x.id === sessionId));
  const agents = useApp((s) => s.agents);
  const globalAgentId = useApp((s) => s.agentId);

  return useMemo(() => {
    const isDraft = sessionId === "";
    // 草稿态 agent = 全局当前选择（AgentPicker 的值）；会话态 = 会话固化的 agent。
    const agent = agents.find((a) => a.id === (sess?.agentConfigId ?? globalAgentId));
    const isClaude = agent?.cli === "claude";
    const agentProviderId = agent?.providerId ?? null;
    // 草稿目录来自 opencode 配置文件（与 serve 目录同源）；claude 用内置预设，不依赖目录。
    const providers = isDraft ? draftCatalogFromOc(ocProviders) : (sessionProviders ?? []);
    if (!agent) {
      return { sessionId, sel: null, providers, currentVariants: [], isClaude, agentProviderId };
    }
    const fallback = splitModelRef(agent.model);
    let sel: EffectiveSel;
    if (stored?.providerId && stored.modelId) {
      sel = { ...stored, overridden: true };
    } else if (sess?.overrideProvider && sess.overrideModel) {
      sel = {
        providerId: sess.overrideProvider,
        modelId: sess.overrideModel,
        variant: sess.overrideVariant ?? null,
        overridden: true,
      };
    } else if (fallback.provider && fallback.model) {
      sel = { providerId: fallback.provider, modelId: fallback.model, variant: null, overridden: false };
    } else {
      return { sessionId, sel: null, providers, currentVariants: [], isClaude, agentProviderId };
    }
    const modelEntry = providers
      .find((p) => p.id === sel.providerId)
      ?.models.find((m) => m.id === sel.modelId);
    return {
      sessionId,
      sel,
      providers,
      // opencode：档位来自目录里该模型的 variants；claude：CLI 固有枚举，
      // 目录缺条目（自定义模型/空目录）也不影响选强度。草稿的图片支持未知，不拦截。
      currentVariants: modelEntry?.variants ?? (isClaude ? CLAUDE_EFFORTS : []),
      imageSupported: isDraft ? undefined : modelEntry?.imageInput,
      isClaude,
      agentProviderId,
    };
  }, [sessionId, sessionProviders, ocProviders, stored, sess, agents, globalAgentId]);
}

function ModelPicker({
  ticketNo,
  sel,
  providers,
  isClaude,
  agentProviderId,
  draft = false,
}: {
  ticketNo: string;
  sel: EffectiveSel | null;
  providers: CatalogProvider[];
  /** claude 会话：目录仅是可选的快捷列表，模型 ID 永远可手输（claude 用自己的网关鉴权）。 */
  isClaude: boolean;
  agentProviderId: string | null;
  /** 草稿态：选择暂存草稿，随首条消息创建会话时一并生效。 */
  draft?: boolean;
}) {
  const t = useT();
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState("");
  const [customModel, setCustomModel] = useState("");
  const busySwitching = useRef(false);
  const q = query.trim().toLowerCase();

  const filtered = useMemo(() => {
    if (!q) return providers;
    return providers
      .map((p) => ({
        ...p,
        models: p.models.filter(
          (m) =>
            m.id.toLowerCase().includes(q) ||
            m.name.toLowerCase().includes(q) ||
            p.name.toLowerCase().includes(q),
        ),
      }))
      .filter((p) => p.models.length > 0);
  }, [providers, q]);

  const pick = async (providerId: string, modelId: string) => {
    if (busySwitching.current) return;
    if (sel && sel.providerId === providerId && sel.modelId === modelId && !sel.variant) {
      setOpen(false);
      return;
    }
    // 换模型时清空推理强度：新模型未必提供同名 variant。
    busySwitching.current = true;
    await actions.switchSessionModel(ticketNo, { providerId, modelId, variant: null });
    busySwitching.current = false;
    setOpen(false);
  };

  /** claude 专属：目录之外的模型 ID 直接生效（下一回合以 --model 传给 claude）。 */
  const pickCustom = async () => {
    const modelId = customModel.trim();
    if (!modelId || busySwitching.current) return;
    setCustomModel("");
    await pick(agentProviderId ?? "custom", modelId);
  };

  /** claude 专属：原生预设（默认=清空覆盖；别名=直接作为 model ID）。 */
  const pickPreset = async (p: { labelKey: "composer.modelDefault" | "composer.modelPreset"; id?: string; clear?: boolean }) => {
    setOpen(false);
    if (p.clear) {
      await actions.switchSessionModel(ticketNo, { providerId: "", modelId: "", variant: "" });
      return;
    }
    await pick(agentProviderId ?? "custom", p.id!);
  };

  const catalogEmpty = providers.length === 0;

  return (
    <div className="relative">
      <button
        className="composer-btn max-w-[240px] disabled:opacity-50 disabled:pointer-events-none"
        onClick={() => {
          setQuery("");
          setOpen(!open);
        }}
        disabled={catalogEmpty && !isClaude}
        title={
          catalogEmpty && !isClaude
            ? draft
              ? t("composer.modelDraftNoCatalog")
              : t("composer.modelNoCatalog")
            : isClaude && catalogEmpty
              ? t("composer.claudeCustomTip")
              : draft
                ? t("composer.modelDraftTip")
                : t("composer.modelSwitchTip")
        }
      >
        <Cpu size={12} className="text-info" weight="fill" />
        <span className="truncate font-mono text-[11px]">
          {sel
            ? `${sel.providerId} · ${sel.modelId}`
            : draft
              ? t("composer.followAgentDefault")
              : t("composer.model")}
        </span>
        {sel?.overridden ? (
          <span className="size-1.5 rounded-full bg-success shrink-0" title={t("composer.modelOverridden")} />
        ) : null}
        <CaretDown size={11} />
      </button>
      {open && (providers.length > 0 || isClaude) && (
        <>
          <div className="fixed inset-0 z-30" onClick={() => setOpen(false)} />
          <div className="absolute bottom-9 left-0 z-40 w-[320px] card p-1.5 shadow-2xl shadow-black/50 animate-rise">
            {isClaude && (
              <div className="flex flex-wrap gap-1 px-2 pt-1.5 pb-1.5 border-b border-[color:var(--line)] mb-1">
                {CLAUDE_MODEL_PRESETS.map((p) => {
                  const active = p.clear ? !sel?.overridden : sel?.modelId === p.id;
                  return (
                    <button
                      key={p.labelKey + (p.id ?? "")}
                      className={`px-2 h-6 rounded-lg text-[11px] cursor-pointer transition-colors ${
                        active ? "bg-raised text-ink" : "text-dim hover:bg-raised hover:text-ink"
                      }`}
                      onClick={() => void pickPreset(p)}
                    >
                      {p.clear ? t(p.labelKey) : p.id}
                    </button>
                  );
                })}
              </div>
            )}
            {providers.length > 0 && (
              <div className="flex items-center gap-1.5 px-2 h-8 mb-1">
                <MagnifyingGlass size={12} className="text-faint shrink-0" />
                <input
                  autoFocus
                  value={query}
                  onChange={(e) => setQuery(e.target.value)}
                  placeholder={t("composer.searchModel")}
                  className="w-full bg-transparent text-[12px] text-ink placeholder:text-faint focus:outline-none"
                />
              </div>
            )}
            <GroupedModelMenu
              groups={filtered.map((p) => ({
                id: p.id,
                name: p.name,
                models: p.models.map((m) => ({
                  id: m.id,
                  label: m.id,
                  active: sel?.providerId === p.id && sel?.modelId === m.id,
                  trailing: m.variants.length > 0 ? t("composer.variantCount", { n: m.variants.length }) : null,
                })),
              }))}
              onPick={(providerId, modelId) => void pick(providerId, modelId)}
              emptyText={catalogEmpty ? t("composer.noCatalogModels") : t("composer.noMatchModels")}
            />
            {isClaude && (
              <div className="border-t border-[color:var(--line)] mt-1 px-2 pt-1.5 pb-1">
                <div className="text-[10.5px] font-medium uppercase tracking-wide text-faint pb-1">
                  {t("composer.customModelLabel")}
                </div>
                <div className="flex items-center gap-1.5">
                  <input
                    value={customModel}
                    onChange={(e) => setCustomModel(e.target.value)}
                    onKeyDown={(e) => {
                      if (e.key === "Enter") void pickCustom();
                    }}
                    placeholder={t("composer.customModelPlaceholder")}
                    className="w-full bg-raised rounded-lg px-2 h-7 font-mono text-[11.5px] text-ink placeholder:text-faint focus:outline-none"
                  />
                  <button
                    className="composer-btn shrink-0"
                    onClick={() => void pickCustom()}
                    disabled={!customModel.trim()}
                  >
                    {t("composer.use")}
                  </button>
                </div>
              </div>
            )}
          </div>
        </>
      )}
    </div>
  );
}

function VariantPicker({
  ticketNo,
  sel,
  variants,
}: {
  ticketNo: string;
  sel: EffectiveSel | null;
  variants: string[];
}) {
  const t = useT();
  const [open, setOpen] = useState(false);
  if (!sel || variants.length === 0) return null;

  const pick = async (variant: string | null) => {
    setOpen(false);
    if ((sel.variant ?? null) === variant) return;
    await actions.switchSessionModel(ticketNo, {
      providerId: sel.providerId,
      modelId: sel.modelId,
      variant,
    });
  };

  return (
    <div className="relative">
      <button
        className="composer-btn"
        onClick={() => setOpen(!open)}
        title={t("composer.variantTip")}
      >
        <Brain size={12} className="text-warning" weight="fill" />
        {sel.variant ? t("composer.variantWith", { v: variantLabel(sel.variant) }) : t("composer.variant")}
        <CaretDown size={11} />
      </button>
      {open && (
        <>
          <div className="fixed inset-0 z-30" onClick={() => setOpen(false)} />
          <div className="absolute bottom-9 left-0 z-40 w-[180px] card p-1.5 shadow-2xl shadow-black/50 animate-rise">
            {[null, ...variants].map((v) => {
              const active = (sel.variant ?? null) === v;
              return (
                <button
                  key={v ?? "default"}
                  className={`w-full flex items-center gap-2 px-2.5 h-8 rounded-lg text-left text-[12.5px] cursor-pointer transition-colors ${
                    active ? "bg-raised text-ink" : "text-dim hover:bg-raised hover:text-ink"
                  }`}
                  onClick={() => void pick(v)}
                >
                  {v === null ? (
                    <>
                      <span className="font-medium">{t("common.default")}</span>
                      <span className="flex-1" />
                      <span className="text-[10.5px] text-faint">{t("composer.variantNone")}</span>
                    </>
                  ) : (
                    <>
                      <span className="font-medium">{variantLabel(v)}</span>
                      <span className="flex-1" />
                      <span className="font-mono text-[10.5px] text-faint">{v}</span>
                    </>
                  )}
                  {active && <Check size={13} className="text-accent" weight="bold" />}
                </button>
              );
            })}
          </div>
        </>
      )}
    </div>
  );
}

export function Composer({ ticketNo }: { ticketNo: string }) {
  const t = useT();
  const mode = useApp((s) => s.mode);
  const activeSessionId = useApp((s) => s.activeSessionId[ticketNo] ?? "");
  // 按钮状态跟随「当前查看的会话」：A 在生成、切到 B 时 B 应显示发送而非中止。
  // demo 模式没有真实的会话流，退回工单级 busy。
  const busy = useApp((s) =>
    s.mode === "live"
      ? (activeSessionId
          ? s.sessionBusy[activeSessionId] === true
          : (s.creatingSession[ticketNo] ?? false))
      : (s.busy[ticketNo] ?? false),
  );
  const stage = useApp((s) => s.tickets.find((t) => t.ticketNo === ticketNo)?.stage);
  const restartCount = useApp(
    (s) => s.tickets.find((t) => t.ticketNo === ticketNo)?.restartCount ?? 0,
  );
  const diffs = useApp((s) => s.diffs[ticketNo]?.length ?? 0);
  const findingsCount = useApp((s) => s.findings[ticketNo]?.length ?? 0);
  const usage = useApp((s) => s.usage[ticketNo]);
  const activeSession = useApp((s) =>
    activeSessionId ? (s.sessions[ticketNo] ?? []).find((x) => x.id === activeSessionId) : undefined,
  );
  const autoAccept = activeSession?.permissionAutoAccept ?? false;
  const [pendingAttachments, setPendingAttachments] = useState<PendingAttachment[]>([]);
  const taRef = useRef<HTMLTextAreaElement>(null);
  // 编辑中的引用胶囊 id（外部粘贴/划选胶囊点开编辑原文；null=无编辑卡）。
  const [editingQuoteId, setEditingQuoteId] = useState<string | null>(null);
  const live = mode === "live";
  // 草稿文本收进全局 store（按工单键自动保存 + localStorage 落盘）：
  // 切 tab/工单/页面再回来时原样还原，发送成功或工单终态时自动清除。
  const text = useApp((s) => s.composerDrafts[ticketNo] ?? "");
  // 引用片段胶囊（划选文字 → 添加到对话框）：同样按工单键暂存，随下一条消息发出。
  const pendingQuotes = useApp((s) => s.pendingQuotes[ticketNo] ?? NO_QUOTES);
  const { sel, providers, currentVariants, imageSupported, isClaude, agentProviderId } =
    useEffectiveSel(ticketNo);

  const resize = () => {
    const ta = taRef.current;
    if (!ta) return;
    ta.style.height = "0px";
    ta.style.height = Math.min(160, Math.max(44, ta.scrollHeight)) + "px";
  };

  useEffect(() => {
    resize();
    // 字体加载完成后复测一次，避免占位符按回退字体量出偏大的初始高度。
    document.fonts?.ready.then(resize).catch(() => {});
  }, [text]);

  const terminal = stage === "DONE" || stage === "CANCELLED";
  const cancelled = stage === "CANCELLED";

  // 工单进入终态后输入框锁定，遗留草稿永远发不出去：清除自动保存（含引用胶囊），
  // 避免以后切回时在禁用输入框里看到无法再发送的幽灵内容。
  useEffect(() => {
    if (terminal) {
      clearComposerDraft(ticketNo);
      clearPendingQuotes(ticketNo);
    }
  }, [terminal, ticketNo]);

  /** 在光标处插入文本（粘贴引用/绝对路径），插入后把光标移到插入文本之后。 */
  const insertAtCursor = (insert: string) => {
    if (!insert) return;
    const ta = taRef.current;
    const start = ta?.selectionStart ?? text.length;
    const end = ta?.selectionEnd ?? start;
    setComposerDraft(ticketNo, text.slice(0, start) + insert + text.slice(end));
    requestAnimationFrame(() => {
      const el = taRef.current;
      if (!el) return;
      el.focus();
      el.setSelectionRange(start + insert.length, start + insert.length);
    });
  };

  /* 输入桥注册：划选菜单/插件动作从这里获得「往当前工单输入框插文本/聚焦」的能力。 */
  useEffect(() => {
    return registerComposerBridge({
      insert: (t) => insertAtCursor(t),
      focus: () => taRef.current?.focus(),
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ticketNo, text]);

  const addPendingImages = async (files: File[]) => {
    for (const file of files) {
      try {
        const att = await toPendingAttachment(file);
        if (att) setPendingAttachments((prev) => [...prev, att]);
      } catch {
        showToast(t("composer.imageReadFailed", { name: file.name }));
      }
    }
  };

  /* 粘贴/拖入的文件统一路由（参考 OpenChamber ChatInput.handlePaste）：
   * · 图片 → 模型支持时暂存为附件，输入框上方出现 chip 胶囊占位（引用胶囊同款），
   *   不再往正文插 [图片 #n] 引用文本——引用行在发送时统一追加在消息尾部，
   *   杜绝引用与正文同行导致的渲染吞字；不支持则提示后丢弃；
   * · 非图片文件 → 载荷文本带绝对路径（资源管理器「复制文件地址」）直接插入；
   *   是文件本体（浏览器拿不到路径）则上传落盘工单克隆，把路径插进光标处。 */
  const handleIncomingFiles = async (files: File[], payloads: string[]) => {
    const imageFiles = files.filter(isAttachableImage);

    if (imageFiles.length > 0) {
      // 需求①：判断当前选择模型是否支持输入 image。
      if (live && sel && imageSupported === false) {
        showToast(t("composer.imageUnsupported", { model: `${sel.providerId}/${sel.modelId}`, n: imageFiles.length }));
        return;
      }
      await addPendingImages(imageFiles);
      return;
    }

    // 需求②：非图片文件 → 自动转为绝对路径。
    const absPath = extractAbsolutePath(payloads);
    if (absPath) {
      insertAtCursor(absPath + " ");
      showToast(t("composer.absPathDone", { name: files[0].name }));
      return;
    }
    await insertChatFiles(files);
  };

  /* 非图片文件的本体上传：浏览器拿不到被复制文件的真实路径，与其留下占位提示，
   * 不如把内容送到 Agent 的工作区（.gate/chat-files/，会话 cwd 即克隆根），
   * 再把落盘相对路径插进光标处。demo 没有后端，维持占位提示。 */
  const insertChatFiles = async (files: File[]) => {
    if (!live) {
      insertAtCursor(`[${t("composer.fileMarker")}] ${files[0].name} `);
      showToast(t("composer.demoNoBackend"));
      return;
    }
    const paths: string[] = [];
    const failed: string[] = [];
    for (const file of files) {
      if (file.size > MAX_CHAT_FILE_MB * 1024 * 1024) {
        failed.push(t("composer.fileTooLarge", { name: file.name, max: MAX_CHAT_FILE_MB }));
        continue;
      }
      try {
        paths.push(await uploadChatFile(ticketNo, file));
      } catch (e) {
        failed.push(`${file.name}: ${(e as Error).message}`);
      }
    }
    if (paths.length > 0) insertAtCursor(paths.map((p) => p + " ").join(""));
    if (paths.length > 0 && failed.length === 0) {
      showToast(t("composer.uploadDone", { name: files[0].name }));
    } else if (failed.length > 0) {
      if (paths.length === 0) insertAtCursor(`[${t("composer.fileMarker")}] ${files[0].name} `);
      showToast(
        paths.length > 0
          ? t("composer.uploadPartial", { ok: paths.length, total: files.length, err: failed[0] })
          : t("composer.uploadFailed", { err: failed[0] }),
      );
    }
  };

  const handlePaste = async (e: React.ClipboardEvent<HTMLTextAreaElement>) => {
    if (terminal) return;
    const dt = e.clipboardData;
    if (!dt) return;

    const seen = new Map<string, File>();
    for (const file of Array.from(dt.files ?? [])) {
      if (file.size >= 0 && !seen.has(`${file.name}-${file.size}`)) seen.set(`${file.name}-${file.size}`, file);
    }
    for (const item of Array.from(dt.items ?? [])) {
      if (item.kind !== "file") continue;
      const file = item.getAsFile();
      if (file && !seen.has(`${file.name}-${file.size}`)) seen.set(`${file.name}-${file.size}`, file);
    }
    const allFiles = Array.from(seen.values());
    if (allFiles.length === 0) {
      // 纯文本粘贴（无文件）：成段文本收进引用胶囊，短单行保持直接插入光标处。
      const text = tryData(() => dt.getData("text/plain"));
      if (!text) return;
      e.preventDefault();
      handleTextPaste(text);
      return;
    }

    // 剪贴板文本载荷（uri-list / plain）优先供路径解析；纯文本粘贴无文件时走默认行为。
    e.preventDefault();
    await handleIncomingFiles(allFiles, [
      tryData(() => dt.getData("text/uri-list")),
      tryData(() => dt.getData("text/plain")),
    ]);
  };

  /* 外部粘贴文本的路由（与「添加到对话框」同一落点，来源标注「剪贴板」）：
   * 成段文本（多行/制表缩进/达到阈值长度）收进引用胶囊——胶囊可编辑原文、可一键
   * 转为正文插回光标处，改动需求在胶囊上就地解决；短单行（URL/路径等）保持直接
   * 插入正文。注意：这只拦 files 为空的纯文本粘贴；带文件/绝对路径的粘贴仍走
   * handleIncomingFiles（含浏览器拿不到路径时的纯文本兜底），已在上方 return。 */
  const handleTextPaste = (text: string) => {
    if (stripQuoteMarkers(text) !== text) {
      // 剪贴板里带着引用标记外壳（复制的整条胶囊/含标记的消息）：按纯文本原样插入，
      // 不重复包壳（否则嵌套 ⟦引用⟧ 让 Agent 侧看到双层标记）。
      insertAtCursor(text);
      return;
    }
    if (isPasteQuoteWorthy(text)) {
      addPendingQuote(ticketNo, text, t("quote.sourceClipboard"));
      showToast(t("composer.quoteAddedToast"));
    } else {
      insertAtCursor(text);
    }
  };

  /* 拖拽与粘贴同一条路由；提示语一直让用户「拖拽文件到输入框」，
   * 但拖入此前从未被处理过——dragover 不拦下会整页导航到拖入的文件。 */
  const handleDragOver = (e: React.DragEvent<HTMLTextAreaElement>) => {
    e.preventDefault();
  };

  const handleDrop = (e: React.DragEvent<HTMLTextAreaElement>) => {
    if (terminal) return;
    e.preventDefault();
    const dt = e.dataTransfer;
    const files = Array.from(dt?.files ?? []);
    if (files.length === 0) {
      // 拖入纯文本（从其他应用拖选文字）：与纯文本粘贴同一条路由。
      const text = tryData(() => dt?.getData("text/plain") ?? "");
      if (text) handleTextPaste(text);
      return;
    }
    void handleIncomingFiles(files, [
      tryData(() => dt?.getData("text/uri-list") ?? ""),
      tryData(() => dt?.getData("text/plain") ?? ""),
    ]);
  };

  const removeAttachment = (id: string) =>
    setPendingAttachments((prev) => prev.filter((a) => a.id !== id));

  /** 引用胶囊「转为正文」：原文插回光标处并移除胶囊，改成普通文字随意编辑。 */
  const quoteChipToText = (q: (typeof pendingQuotes)[number]) => {
    removePendingQuote(ticketNo, q.id);
    insertAtCursor(q.text);
    requestAnimationFrame(() => taRef.current?.focus());
  };

  const send = () => {
    const t = text.trim();
    if ((!t && pendingQuotes.length === 0 && pendingAttachments.length === 0) || terminal)
      return;
    if (busy && activeSessionId) {
      // Agent 正在输出：Enter 语义按跟随行为设定走（排队/插队），发送按钮在 busy
      // 态已被 Stop 按钮替代，这里只可能是键盘/快捷键触发。
      if (followUpBehavior === "steer") steerComposed();
      else queueComposed();
      return;
    }
    if (busy) return;
    const composed =
      withImageCitations(t, pendingAttachments) +
      pendingQuotes.map((q) => `\n${wrapQuote(q.text)}`).join("");
    const prevText = t;
    const prevQuotes = pendingQuotes;
    const prevAttachments = pendingAttachments;
    clearComposerDraft(ticketNo);
    setPendingQuotes(ticketNo, []);
    setPendingAttachments([]);
    void Promise.resolve(actions.sendPrompt(ticketNo, composed, prevAttachments)).then((ok) => {
      // 草稿建会话失败（如端口占用）：还原输入与附件，错误卡片已给出原因，
      // 用户改完直接重发即可，不必重新打字。
      if (ok === false) {
        setComposerDraft(ticketNo, prevText);
        setPendingQuotes(ticketNo, prevQuotes);
        setPendingAttachments(prevAttachments);
      }
    });
  };

  /* ─── T-107：Agent 输出时的排队 / 插队（参考 OpenChamber followUpBehavior） ─── */

  // 行为偏好（设置中心·偏好设置可切换，本处只读）：queue（默认）——回车排队，空闲后自动发送；steer——回车直接插队当前回合。
  const followUpBehavior = useApp((s) => s.followUpBehavior);
  // 排队消息列表（按会话隔离；localStorage 持久化）。
  const queue = useApp((s) =>
    activeSessionId ? (s.queuedMessages[activeSessionId] ?? NO_QUEUE) : NO_QUEUE,
  );
  // 插队（delivery=steer）仅 opencode 运行时支持；claude/demo 降级为排队并提示。
  const steerSupported = live && activeSessionId !== "" && !isClaude;

  // 当前正在编辑原文的胶囊（编辑卡的数据源；胶囊被移除/发送后自动收回 null）。
  const editingQuote = pendingQuotes.find((q) => q.id === editingQuoteId) ?? null;

  /** 组装当前输入（正文 + 图片引用 + 引用胶囊内联文本），与 send() 同口径。 */
  const composedPayload = () =>
    withImageCitations(text.trim(), pendingAttachments) +
    pendingQuotes.map((q) => `\n${wrapQuote(q.text)}`).join("");

  const clearComposerInput = () => {
    clearComposerDraft(ticketNo);
    setPendingQuotes(ticketNo, []);
    setPendingAttachments([]);
  };

  /** 入队：正文与附件整体进入会话队列，清空输入区。 */
  const queueComposed = () => {
    if (!activeSessionId) {
      send();
      return;
    }
    const payload = composedPayload();
    if (!payload.trim() && pendingAttachments.length === 0) return;
    const attachments = pendingAttachments;
    addMessageToQueue(ticketNo, activeSessionId, payload, attachments);
    clearComposerInput();
    showToast(
      followUpBehavior === "steer"
        ? t("composer.queuedToastSteer")
        : t("composer.queuedToastQueue"),
    );
  };

  /** 插队：直接投递给运行中的回合（仅 opencode 运行时支持）；否则降级入队并提示。 */
  const steerComposed = () => {
    const payload = composedPayload();
    if (!payload.trim() && pendingAttachments.length === 0) return;
    if (!steerSupported || !activeSessionId) {
      if (busy) {
        queueComposed();
        showToast(t("composer.steerFallbackToast"));
      } else {
        send();
      }
      return;
    }
    const prevText = text;
    const prevQuotes = pendingQuotes;
    const prevAttachments = pendingAttachments;
    clearComposerInput();
    void Promise.resolve(
      actions.sendPrompt(ticketNo, payload, prevAttachments, "steer"),
    ).then((ok) => {
      if (ok === false) {
        setComposerDraft(ticketNo, prevText);
        setPendingQuotes(ticketNo, prevQuotes);
        setPendingAttachments(prevAttachments);
      }
    });
  };

  /** 排队的单条消息「立即发送」：busy 时=插队；空闲时=直接泵出。 */
  const sendQueuedNow = (m: QueuedMessage) => {
    if (!activeSessionId) return;
    if (busy) {
      if (!steerSupported) {
        showToast(t("composer.steerUnsupportedToast"));
        return;
      }
      // 插队成功前消息先留在队列：失败则原样保留，避免静默丢失。
      void Promise.resolve(
        actions.sendPrompt(ticketNo, m.content, m.attachments ?? [], "steer"),
      ).then((ok) => {
        if (ok !== false) removeQueuedMessage(activeSessionId, m.id);
      });
      return;
    }
    // 空闲：把该条挪到队首并泵出（其余排队消息仍按 FIFO 随后发送）。
    const list = appStore.getState().queuedMessages[activeSessionId] ?? [];
    const idx = list.findIndex((x) => x.id === m.id);
    if (idx > 0) reorderQueuedMessages(activeSessionId, m.id, list[0].id);
    void kickQueuePump(activeSessionId);
  };

  const editQueued = (m: QueuedMessage) => {
    if (!activeSessionId) return;
    const popped = popQueuedMessageToInput(activeSessionId, m.id);
    if (!popped) return;
    setComposerDraft(ticketNo, popped.content);
    if (popped.attachments && popped.attachments.length > 0) {
      setPendingAttachments((prev) => [...prev, ...(popped.attachments ?? [])]);
    }
    requestAnimationFrame(() => taRef.current?.focus());
  };

  /**
   * 原生快捷 chip（Composer 域一等能力，不进插件注册表）：插件全禁用/宿主初始化
   * 失败时依然完整可用；插件的追加 chip 由 ChatActionChips 渲染在本段之后。
   */
  const quick = [
    diffs > 0 && !terminal
      ? { label: t("composer.quick.presubmit"), prompt: "__presubmit__", Icon: LockKey }
      : null,
    diffs > 0 ? { label: t("composer.quick.explain"), prompt: t("composer.quick.explainPrompt"), Icon: Eye } : null,
    // 派单：指导 Agent 现在不要建单，等用户下一条消息给出需求描述后，
    // 再走 MCP（服务器注册名 gate）的 ticket_create 建单，并据此补全标题与描述
    {
      label: t("composer.quick.dispatch"),
      prompt:
        t("composer.dispatchPrompt"),
      Icon: Ticket,
    },
    // 重启过的活跃工单才有「重启理由」注入上下文（AgentContextPrompt），语录才有意义
    restartCount > 0
      ? { label: t("composer.quick.finish"), prompt: t("composer.quick.finishPrompt"), Icon: CheckCircle }
      : null,
    findingsCount > 0 && stage === "REJECTED"
      ? { label: t("composer.quick.fixFindings"), prompt: "__findings__", Icon: Wrench }
      : null,
  ].filter(Boolean) as Array<{ label: string; prompt: string; Icon: Icon }>;

  return (
    <div className="shrink-0 px-5 py-3">
      <div className="max-w-[760px] mx-auto space-y-2">
        {!terminal && (quick.length > 0 || usage) && (
          <div className="flex items-center gap-3">
            {quick.length > 0 && (
              <div className="flex flex-wrap gap-1.5 min-w-0">
                {quick.map(({ label, prompt, Icon: QIcon }) => (
                  <button
                    key={label}
                    disabled={busy}
                    className="composer-chip"
                    onClick={() => {
                      if (prompt === "__findings__") actions.returnWithFindings(ticketNo);
                      else if (prompt === "__presubmit__") actions.presubmit(ticketNo);
                      else actions.sendPrompt(ticketNo, prompt);
                    }}
                  >
                    <QIcon size={12} weight="fill" className="opacity-60" />
                    {label}
                  </button>
                ))}
              </div>
            )}
            {/* 插件追加 chip 段：无插件贡献时整体不渲染 */}
            <ChatActionChips ticketNo={ticketNo} busy={busy} insertText={insertAtCursor} />
            <span className="flex-1" />
            {usage && (
              <span
                className="font-mono text-[11px] text-faint tabular-nums whitespace-nowrap"
                title={t("composer.usageTip")}
              >
                ↑ {formatTokens(usage.promptTokens)} · ↓ {formatTokens(usage.completionTokens)}
              </span>
            )}
          </div>
        )}

        {/* T-107：排队消息面板（按会话隔离；支持上移/下移排序、编辑回填、删除、立即发送=插队） */}
        {activeSessionId && queue.length > 0 && (
          <div className="queue-panel">
            <div className="queue-panel-header">
              <Clock size={11} className="text-faint" />
              {t("composer.queueTitle")}
              <span className="font-mono text-[10.5px] text-faint">{queue.length}</span>
              <span className="flex-1" />
              <span className="text-[10.5px] text-faint">{t("composer.queueHint")}</span>
            </div>
            <div className="queue-panel-list">
              {queue.map((m, i) => {
                const firstLine = m.content.split("\n").find((l) => l.trim() !== "") ?? "";
                return (
                  <div key={m.id} className="queue-item">
                    <button
                      className="queue-icon-btn"
                      disabled={i === 0}
                      title={t("composer.moveUp")}
                      aria-label={t("composer.moveUp")}
                      onClick={() => reorderQueuedMessages(activeSessionId, m.id, queue[i - 1].id)}
                    >
                      <ArrowUp size={11} weight="bold" />
                    </button>
                    <button
                      className="queue-icon-btn"
                      disabled={i === queue.length - 1}
                      title={t("composer.moveDown")}
                      aria-label={t("composer.moveDown")}
                      onClick={() => reorderQueuedMessages(activeSessionId, m.id, queue[i + 1].id)}
                    >
                      <ArrowDown size={11} weight="bold" />
                    </button>
                    <span
                      className="queue-item-text"
                      title={m.content}
                      onClick={() => editQueued(m)}
                    >
                      {firstLine.length > 80 ? firstLine.slice(0, 80) + "…" : firstLine}
                    </span>
                    {m.attachments && m.attachments.length > 0 && (
                      <span className="queue-item-meta">{t("composer.imageCount", { n: m.attachments.length })}</span>
                    )}
                    <button
                      className="queue-icon-btn queue-icon-btn-go"
                      title={busy ? (steerSupported ? t("composer.steerNowTip") : t("composer.steerUnavailableTip")) : t("composer.sendNowTip")}
                      aria-label={t("composer.sendNowTip")}
                      onClick={() => sendQueuedNow(m)}
                    >
                      <Lightning size={11} weight="fill" />
                    </button>
                    <button
                      className="queue-icon-btn"
                      title={t("composer.editBackTip")}
                      aria-label={t("common.edit")}
                      onClick={() => editQueued(m)}
                    >
                      <PencilSimple size={11} />
                    </button>
                    <button
                      className="queue-icon-btn"
                      title={t("common.remove")}
                      aria-label={t("common.remove")}
                      onClick={() => removeQueuedMessage(activeSessionId, m.id)}
                    >
                      <XIcon size={11} weight="bold" />
                    </button>
                  </div>
                );
              })}
            </div>
          </div>
        )}

        {/* 统一输入卡：textarea 与控制栏同卡，聚焦时整卡亮起（参考 OpenChamber） */}
        <div
          className={`composer-shell${busy ? " composer-shell-busy" : ""}${
            terminal ? " composer-shell-done" : ""
          }`}
        >
          {editingQuote && (
            <QuoteEditCard
              initialText={editingQuote.text}
              onSave={(next) => {
                updatePendingQuoteText(ticketNo, editingQuote.id, next);
                setEditingQuoteId(null);
              }}
              onClose={() => setEditingQuoteId(null)}
            />
          )}
          {pendingAttachments.length > 0 && (
            <div className="flex flex-wrap gap-2 px-3 pt-3">
              {pendingAttachments.map((att, i) => (
                <div key={att.id} className="composer-attach" title={`${att.filename} · ${att.mime}`}>
                  <img src={att.dataUrl} alt={att.filename} className="composer-attach-thumb" />
                  <div className="min-w-0">
                    <div className="truncate text-[11px] font-medium text-ink max-w-[120px]">
                      [{t("composer.imageRef", { n: i + 1 })}] {att.filename}
                    </div>
                    <div className="font-mono text-[10px] text-faint">{att.mime}</div>
                  </div>
                  <button
                    className="ml-1 grid place-items-center size-5 rounded-full text-faint hover:text-ink hover:bg-raised cursor-pointer transition-colors"
                    title={t("composer.removeAttachment")}
                    aria-label={t("composer.removeAttachmentName", { name: att.filename })}
                    onClick={() => removeAttachment(att.id)}
                  >
                    <XIcon size={11} weight="bold" />
                  </button>
                </div>
              ))}
              {live && sel && imageSupported === false && (
                <span className="self-center text-[11px] text-warning">
                  {t("composer.imageUnsupportedInline")}
                </span>
              )}
            </div>
          )}
          {pendingQuotes.length > 0 && (
            <div className="flex flex-wrap gap-1.5 px-3 pt-2.5">
              {pendingQuotes.map((q) => (
                <QuoteChip
                  key={q.id}
                  text={q.text}
                  source={q.source}
                  onEdit={() => setEditingQuoteId(q.id)}
                  onToText={() => quoteChipToText(q)}
                  onRemove={() => removePendingQuote(ticketNo, q.id)}
                />
              ))}
            </div>
          )}
          <textarea
            ref={taRef}
            value={text}
            disabled={terminal}
            onChange={(e) => setComposerDraft(ticketNo, e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter" && !e.shiftKey) {
                e.preventDefault();
                // T-107：Agent 输出中按跟随行为路由——queue 模式 Enter=排队、
                // Ctrl+Enter=插队；steer 模式 Enter=插队、Ctrl+Enter=排队。
                // 空闲时 Enter/Ctrl+Enter 均为直接发送。
                if (busy && activeSessionId) {
                  const isCtrl = e.ctrlKey || e.metaKey;
                  const wantSteer = followUpBehavior === "steer" ? !isCtrl : isCtrl;
                  if (wantSteer) steerComposed();
                  else queueComposed();
                  return;
                }
                send();
              }
            }}
            onPaste={(e) => void handlePaste(e)}
            onDragOver={handleDragOver}
            onDrop={handleDrop}
            rows={1}
            placeholder={
              cancelled
                ? t("composer.placeholder.cancelled")
                : stage === "DONE"
                  ? t("composer.placeholder.done")
                  : busy
                    ? live && !activeSessionId
                      ? t("composer.placeholder.creating")
                      : followUpBehavior === "steer"
                        ? t("composer.placeholder.busySteer")
                        : t("composer.placeholder.busyQueue")
                    : t("composer.placeholder.idle")
            }
            className="composer-ta"
          />

          <div className="flex items-center gap-2 px-2.5 pb-2.5 pt-0.5">
            <div
              className={`flex items-center gap-1 flex-wrap min-w-0 ${
                terminal ? "pointer-events-none opacity-45" : ""
              }`}
            >
              <AgentPicker ticketNo={ticketNo} />
              {live && (
                <>
                  <ModelPicker
                    ticketNo={ticketNo}
                    sel={sel}
                    providers={providers}
                    isClaude={isClaude}
                    agentProviderId={agentProviderId}
                    draft={activeSessionId === ""}
                  />
                  <VariantPicker ticketNo={ticketNo} sel={sel} variants={currentVariants} />
                  {activeSessionId &&
                    (isClaude ? (
                      // claude：权限模式轮询（V24）——headless 专属档位，下次发送生效。
                      <PermissionModeCycler ticketNo={ticketNo} />
                    ) : (
                      // opencode：自动授权按钮原样保留（两链互斥）。
                      <button
                        className={`composer-btn ${autoAccept ? "composer-btn-active" : ""}`}
                        title={
                          autoAccept
                            ? t("composer.autoAcceptOnTip")
                            : t("composer.autoAcceptOffTip")
                        }
                        onClick={() => void actions.setSessionAutoAccept(ticketNo, !autoAccept)}
                      >
                        <ShieldCheck size={13} weight={autoAccept ? "fill" : "regular"} />
                        {autoAccept ? t("composer.autoAcceptOn") : t("composer.autoAcceptOff")}
                      </button>
                    ))}
                </>
              )}
            </div>
            <span className="flex-1" />
            {text.length > 0 && <span className="composer-count">{text.length}</span>}
            {busy ? (
              <button
                className="composer-stop"
                title={t("composer.abortTip")}
                aria-label={t("composer.abortTip")}
                onClick={() => actions.abort(ticketNo)}
              >
                <Stop size={14} weight="fill" />
              </button>
            ) : (
              <button
                className="composer-send"
                title={
                  pendingQuotes.length > 0
                    ? t("composer.sendWithQuotes")
                    : pendingAttachments.length > 0
                      ? t("composer.sendWithAttachments")
                      : t("common.send")
                }
                aria-label={t("common.send")}
                disabled={
                  (!text.trim() && pendingQuotes.length === 0 && pendingAttachments.length === 0) ||
                  terminal
                }
                onClick={send}
              >
                <PaperPlaneRight size={15} weight="fill" />
              </button>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}

export function composerAlive(ticketNo: string): boolean {
  return appStore.getState().busy[ticketNo] === true;
}
