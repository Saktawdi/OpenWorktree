import { useEffect, useMemo, useRef, useState } from "react";
import {
  Brain,
  CaretDown,
  Check,
  CheckCircle,
  Cpu,
  Eye,
  Lock,
  LockKey,
  MagnifyingGlass,
  PaperPlaneRight,
  ShieldCheck,
  Sparkle,
  Stop,
  TerminalWindow,
  Wrench,
  X,
} from "@phosphor-icons/react";
import type { Icon } from "@phosphor-icons/react";
import { actions } from "@/app/actions";
import { appStore, NO_CHAT, showToast, useApp } from "@/store";
import {
  clearComposerDraft,
  clearDraftModelSel,
  draftCatalogFromOc,
  setComposerDraft,
} from "@/features/session";
import { setAgentId } from "@/features/agent";
import { formatTokens, variantLabel } from "@/shared/format";
import { extractAbsolutePath,
  isAttachableImage,
  toPendingAttachment, } from "@/shared/attachments";
import type { CatalogProvider, PendingAttachment, SessionModelSel } from "@/shared/types";

function AgentPicker({ ticketNo }: { ticketNo: string }) {
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
          title={locked ? "会话已创建 · 协作 Agent 已锁定，新建会话可重新选择" : "选择协作的 Agent"}
        >
          <Sparkle size={12} className={locked ? "text-faint" : "text-accent"} weight="fill" />
          {current ? (current.model ? `${current.name} · ${current.model}` : current.name) : "选择 Agent"}
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
const CLAUDE_MODEL_PRESETS: { label: string; id?: string; clear?: boolean }[] = [
  { label: "默认", clear: true },
  { label: "Haiku", id: "haiku" },
  { label: "Sonnet", id: "sonnet" },
  { label: "Opus", id: "opus" },
  { label: "Fable", id: "fable" },
];

/** Splits an AgentConfig default model ref ("provider/model") into its halves. */
function splitModelRef(model?: string | null): { provider: string | null; model: string | null } {
  if (!model || !model.trim()) return { provider: null, model: null };
  const slash = model.indexOf("/");
  if (slash <= 0 || slash >= model.length - 1) return { provider: null, model };
  return { provider: model.slice(0, slash), model: model.slice(slash + 1) };
}

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
  const pickPreset = async (p: (typeof CLAUDE_MODEL_PRESETS)[number]) => {
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
              ? "草稿态没有可浏览的模型目录：首条消息将使用 Agent 默认模型，会话创建后可切换"
              : "模型目录不可用"
            : isClaude && catalogEmpty
              ? "输入 Claude 网关可用的模型 ID（目录未配置）"
              : draft
                ? "选择首条消息使用的模型（创建会话时一并生效）"
                : "切换本会话使用的模型（下一回合生效，可随时切换）"
        }
      >
        <Cpu size={12} className="text-info" weight="fill" />
        <span className="truncate font-mono text-[11px]">
          {sel
            ? `${sel.providerId} · ${sel.modelId}`
            : draft
              ? "跟随 Agent 默认"
              : "模型"}
        </span>
        {sel?.overridden ? (
          <span className="size-1.5 rounded-full bg-success shrink-0" title="已覆盖默认模型" />
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
                      key={p.label}
                      className={`px-2 h-6 rounded-lg text-[11px] cursor-pointer transition-colors ${
                        active ? "bg-raised text-ink" : "text-dim hover:bg-raised hover:text-ink"
                      }`}
                      onClick={() => void pickPreset(p)}
                    >
                      {p.label}
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
                  placeholder="搜索模型…"
                  className="w-full bg-transparent text-[12px] text-ink placeholder:text-faint focus:outline-none"
                />
              </div>
            )}
            <div className="max-h-[300px] overflow-y-auto">
              {filtered.map((p) => (
                <div key={p.id}>
                  <div className="px-2.5 pt-2 pb-1 text-[10.5px] font-medium uppercase tracking-wide text-faint">
                    {p.name}
                  </div>
                  {p.models.map((m) => {
                    const active = sel?.providerId === p.id && sel?.modelId === m.id;
                    return (
                      <button
                        key={`${p.id}/${m.id}`}
                        className={`w-full flex items-center gap-2 px-2.5 h-8 rounded-lg text-left text-[12px] cursor-pointer transition-colors ${
                          active ? "bg-raised text-ink" : "text-dim hover:bg-raised hover:text-ink"
                        }`}
                        onClick={() => void pick(p.id, m.id)}
                      >
                        <span className="font-mono text-[11.5px] truncate">{m.id}</span>
                        {m.variants.length > 0 && (
                          <span className="text-[10px] text-faint shrink-0">{m.variants.length} 档强度</span>
                        )}
                        <span className="flex-1" />
                        {active && <Check size={13} className="text-accent" weight="bold" />}
                      </button>
                    );
                  })}
                </div>
              ))}
              {filtered.length === 0 && (
                <div className="px-3 py-4 text-center text-[12px] text-faint">
                  {catalogEmpty ? "无目录模型，可直接在下方输入模型 ID" : "无匹配模型"}
                </div>
              )}
            </div>
            {isClaude && (
              <div className="border-t border-[color:var(--line)] mt-1 px-2 pt-1.5 pb-1">
                <div className="text-[10.5px] font-medium uppercase tracking-wide text-faint pb-1">
                  自定义模型 ID（claude 网关实际可用为准）
                </div>
                <div className="flex items-center gap-1.5">
                  <input
                    value={customModel}
                    onChange={(e) => setCustomModel(e.target.value)}
                    onKeyDown={(e) => {
                      if (e.key === "Enter") void pickCustom();
                    }}
                    placeholder="如 glm-5.2"
                    className="w-full bg-raised rounded-lg px-2 h-7 font-mono text-[11.5px] text-ink placeholder:text-faint focus:outline-none"
                  />
                  <button
                    className="composer-btn shrink-0"
                    onClick={() => void pickCustom()}
                    disabled={!customModel.trim()}
                  >
                    使用
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
        title="切换本会话的推理强度（variant，下一回合生效）"
      >
        <Brain size={12} className="text-warning" weight="fill" />
        {sel.variant ? `推理·${variantLabel(sel.variant)}` : "推理"}
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
                      <span className="font-medium">默认</span>
                      <span className="flex-1" />
                      <span className="text-[10.5px] text-faint">不传 variant</span>
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
  const live = mode === "live";
  // 草稿文本收进全局 store（按工单键自动保存 + localStorage 落盘）：
  // 切 tab/工单/页面再回来时原样还原，发送成功或工单终态时自动清除。
  const text = useApp((s) => s.composerDrafts[ticketNo] ?? "");
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

  // 工单进入终态后输入框锁定，遗留草稿永远发不出去：清除自动保存，避免以后切回时
  // 在禁用输入框里看到无法再发送的幽灵文本。
  useEffect(() => {
    if (terminal) clearComposerDraft(ticketNo);
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

  const addPendingImages = async (files: File[]) => {
    for (const file of files) {
      try {
        const att = await toPendingAttachment(file);
        if (att) setPendingAttachments((prev) => [...prev, att]);
      } catch {
        showToast(`读取图片失败：${file.name}`);
      }
    }
  };

  /* 粘贴（参考 OpenChamber ChatInput.handlePaste）：
   * · 图片 → 模型支持时暂存为附件并插入 [图片 #n] 引用；不支持则提示后丢弃；
   * · 非图片文件 → 自动转为绝对路径文本（浏览器拿不到路径时给出指引）。 */
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
    if (allFiles.length === 0) return;

    const imageFiles = allFiles.filter(isAttachableImage);

    if (imageFiles.length > 0) {
      // 需求①：判断当前选择模型是否支持输入 image。
      if (live && sel && imageSupported === false) {
        e.preventDefault();
        showToast(`当前模型 ${sel.providerId}/${sel.modelId} 不支持图片输入，已忽略 ${imageFiles.length} 张图片`);
        return;
      }
      e.preventDefault();
      const caretStart = taRef.current?.selectionStart ?? text.length;
      const caretEnd = taRef.current?.selectionEnd ?? caretStart;
      let citations = "";
      for (let i = 0; i < imageFiles.length; i++) {
        if (i > 0 || text.slice(0, caretStart).trim().length > 0) citations += "\n\n";
        citations += `[图片 #${pendingAttachments.length + i + 1}] ${imageFiles[i].name}`;
      }
      setComposerDraft(ticketNo, text.slice(0, caretStart) + citations + text.slice(caretEnd));
      await addPendingImages(imageFiles);
      return;
    }

    // 需求②：非图片文件 → 自动转为绝对路径。
    e.preventDefault();
    const nonImage = allFiles[0];
    const absPath = extractAbsolutePath([
      (() => {
        try {
          return dt.getData("text/uri-list");
        } catch {
          return "";
        }
      })(),
      (() => {
        try {
          return dt.getData("text/plain");
        } catch {
          return "";
        }
      })(),
    ]);
    if (absPath) {
      insertAtCursor(absPath + " ");
      showToast(`已将「${nonImage.name}」转为绝对路径`);
    } else {
      insertAtCursor(`[文件] ${nonImage.name} `);
      showToast("浏览器无法获取该文件的绝对路径：请直接拖拽文件到输入框，或在资源管理器中复制文件路径后粘贴");
    }
  };

  const removeAttachment = (id: string) =>
    setPendingAttachments((prev) => prev.filter((a) => a.id !== id));

  const send = () => {
    const t = text.trim();
    if ((!t && pendingAttachments.length === 0) || busy || terminal) return;
    const prevText = t;
    const prevAttachments = pendingAttachments;
    clearComposerDraft(ticketNo);
    setPendingAttachments([]);
    void Promise.resolve(actions.sendPrompt(ticketNo, t, prevAttachments)).then((ok) => {
      // 草稿建会话失败（如端口占用）：还原输入与附件，错误卡片已给出原因，
      // 用户改完直接重发即可，不必重新打字。
      if (ok === false) {
        setComposerDraft(ticketNo, prevText);
        setPendingAttachments(prevAttachments);
      }
    });
  };

  const quick = [
    diffs > 0 && !terminal
      ? { label: "预提审", prompt: "__presubmit__", Icon: LockKey }
      : null,
    diffs > 0 ? { label: "解释当前变更", prompt: "请解释当前工作区的全部改动", Icon: Eye } : null,
    { label: "运行本地单测", prompt: "运行本地单元测试并汇总结果", Icon: TerminalWindow },
    // 重启过的活跃工单才有「重启理由」注入上下文（AgentContextPrompt），语录才有意义
    restartCount > 0
      ? { label: "完成此工单", prompt: "完成此工单，处理下重启理由", Icon: CheckCircle }
      : null,
    findingsCount > 0 && stage === "REJECTED"
      ? { label: "按审查意见修复", prompt: "__findings__", Icon: Wrench }
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
            <span className="flex-1" />
            {usage && (
              <span
                className="font-mono text-[11px] text-faint tabular-nums whitespace-nowrap"
                title="本工单累计 token 用量（↑ 输入 / ↓ 输出）"
              >
                ↑ {formatTokens(usage.promptTokens)} · ↓ {formatTokens(usage.completionTokens)}
              </span>
            )}
          </div>
        )}

        {/* 统一输入卡：textarea 与控制栏同卡，聚焦时整卡亮起（参考 OpenChamber） */}
        <div
          className={`composer-shell${busy ? " composer-shell-busy" : ""}${
            terminal ? " composer-shell-done" : ""
          }`}
        >
          {pendingAttachments.length > 0 && (
            <div className="flex flex-wrap gap-2 px-3 pt-3">
              {pendingAttachments.map((att, i) => (
                <div key={att.id} className="composer-attach" title={`${att.filename} · ${att.mime}`}>
                  <img src={att.dataUrl} alt={att.filename} className="composer-attach-thumb" />
                  <div className="min-w-0">
                    <div className="truncate text-[11px] font-medium text-ink max-w-[120px]">
                      [图片 #{i + 1}] {att.filename}
                    </div>
                    <div className="font-mono text-[10px] text-faint">{att.mime}</div>
                  </div>
                  <button
                    className="ml-1 grid place-items-center size-5 rounded-full text-faint hover:text-ink hover:bg-raised cursor-pointer transition-colors"
                    title="移除附件"
                    aria-label={`移除附件 ${att.filename}`}
                    onClick={() => removeAttachment(att.id)}
                  >
                    <X size={11} weight="bold" />
                  </button>
                </div>
              ))}
              {live && sel && imageSupported === false && (
                <span className="self-center text-[11px] text-warning">
                  当前模型不支持图片输入，发送前请切换模型
                </span>
              )}
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
                send();
              }
            }}
            onPaste={(e) => void handlePaste(e)}
            rows={1}
            placeholder={
              cancelled
                ? "工单已取消 · 协作已锁定，不可继续操作"
                : stage === "DONE"
                  ? "工单已完成并归档"
                  : busy
                    ? live && !activeSessionId
                      ? "正在创建会话…"
                      : "Agent 正在工作，可点击右下按钮中断；切换的模型/推理强度将在下一回合生效…"
                    : "向 Agent 描述任务…（Enter 发送，Shift+Enter 换行，可粘贴图片/文件）"
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
                  {activeSessionId && (
                    <button
                      className={`composer-btn ${autoAccept ? "composer-btn-active" : ""}`}
                      title={
                        autoAccept
                          ? "权限请求将被服务端自动允许，不再弹出确认卡片"
                          : "开启自动允许：权限请求将被服务端自动允许，不再弹出确认卡片"
                      }
                      onClick={() => void actions.setSessionAutoAccept(ticketNo, !autoAccept)}
                    >
                      <ShieldCheck size={13} weight={autoAccept ? "fill" : "regular"} />
                      {autoAccept ? "权限：自动允许" : "权限：询问"}
                    </button>
                  )}
                </>
              )}
            </div>
            <span className="flex-1" />
            {text.length > 0 && <span className="composer-count">{text.length}</span>}
            {busy ? (
              <button
                className="composer-stop"
                title="中断生成"
                aria-label="中断生成"
                onClick={() => actions.abort(ticketNo)}
              >
                <Stop size={14} weight="fill" />
              </button>
            ) : (
              <button
                className="composer-send"
                title={pendingAttachments.length > 0 ? "发送（含图片附件）" : "发送"}
                aria-label="发送"
                disabled={(!text.trim() && pendingAttachments.length === 0) || terminal}
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
