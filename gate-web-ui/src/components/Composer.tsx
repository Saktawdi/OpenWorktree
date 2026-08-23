import { useEffect, useMemo, useRef, useState } from "react";
import {
  Brain,
  CaretDown,
  Check,
  Cpu,
  Eye,
  Lightning,
  Lock,
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
import { actions } from "../lib/actions";
import { appStore, NO_CHAT, setAgentId, showToast, useApp } from "../lib/store";
import { formatTokens } from "../lib/format";
import {
  extractAbsolutePath,
  isAttachableImage,
  toPendingAttachment,
} from "../lib/attachments";
import type { CatalogProvider, PendingAttachment, SessionModelSel } from "../lib/types";

function AgentPicker({ ticketNo }: { ticketNo: string }) {
  const agents = useApp((s) => s.agents);
  const agentId = useApp((s) => s.agentId);
  const locked = useApp((s) =>
    (s.chats[ticketNo] ?? NO_CHAT).some((m) => m.kind === "user"),
  );
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
    <div
      aria-hidden={phase === "collapsing"}
      className={`relative overflow-hidden whitespace-nowrap transition-all duration-300 ease-[cubic-bezier(0.16,1,0.3,1)] ${
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
        title={locked ? "已发送消息 · 协作 Agent 已锁定，新建会话可重新选择" : "选择协作的 Agent"}
      >
        <Sparkle size={12} className={locked ? "text-faint" : "text-accent"} weight="fill" />
        {current ? `${current.name} · ${current.model}` : "选择 Agent"}
        {locked ? <Lock size={11} className="text-faint" weight="fill" /> : <CaretDown size={11} />}
      </button>
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

const VARIANT_LABELS: Record<string, string> = {
  high: "高",
  medium: "中",
  low: "低",
  max: "最高",
  minimal: "极简",
  none: "关闭",
};

function variantLabel(v: string): string {
  return VARIANT_LABELS[v.toLowerCase()] ?? v;
}

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
} {
  const sessionId = useApp((s) => s.activeSessionId[ticketNo] ?? "");
  const providers = useApp((s) => (sessionId ? s.sessionModels[sessionId] : undefined)) ?? [];
  const stored = useApp((s) => (sessionId ? s.sessionModelSel[sessionId] : undefined));
  const sess = useApp((s) => (s.sessions[ticketNo] ?? []).find((x) => x.id === sessionId));
  const agents = useApp((s) => s.agents);

  return useMemo(() => {
    if (!sessionId) return { sessionId, sel: null, providers, currentVariants: [] };
    const agent = agents.find((a) => a.id === (sess?.agentConfigId ?? ""));
    const fallback = splitModelRef(agent?.model);
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
      return { sessionId, sel: null, providers, currentVariants: [] };
    }
    const modelEntry = providers
      .find((p) => p.id === sel.providerId)
      ?.models.find((m) => m.id === sel.modelId);
    return {
      sessionId,
      sel,
      providers,
      currentVariants: modelEntry?.variants ?? [],
      imageSupported: modelEntry?.imageInput,
    };
  }, [sessionId, providers, stored, sess, agents]);
}

function ModelPicker({
  ticketNo,
  sel,
  providers,
}: {
  ticketNo: string;
  sel: EffectiveSel | null;
  providers: CatalogProvider[];
}) {
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState("");
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

  const label = sel ? `${sel.providerId} · ${sel.modelId}` : "模型";

  return (
    <div className="relative">
      <button
        className="composer-btn max-w-[240px] disabled:opacity-50 disabled:pointer-events-none"
        onClick={() => {
          setQuery("");
          setOpen(!open);
        }}
        disabled={providers.length === 0}
        title={
          providers.length === 0
            ? "模型目录不可用"
            : "切换本会话使用的模型（下一回合生效，可随时切换）"
        }
      >
        <Cpu size={12} className="text-info" weight="fill" />
        <span className="truncate font-mono text-[11px]">{label}</span>
        {sel?.overridden ? (
          <span className="size-1.5 rounded-full bg-success shrink-0" title="已覆盖默认模型" />
        ) : null}
        <CaretDown size={11} />
      </button>
      {open && providers.length > 0 && (
        <>
          <div className="fixed inset-0 z-30" onClick={() => setOpen(false)} />
          <div className="absolute bottom-9 left-0 z-40 w-[320px] card p-1.5 shadow-2xl shadow-black/50 animate-rise">
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
                <div className="px-3 py-6 text-center text-[12px] text-faint">无匹配模型</div>
              )}
            </div>
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
  const busy = useApp((s) => s.busy[ticketNo] ?? false);
  const stage = useApp((s) => s.tickets.find((t) => t.ticketNo === ticketNo)?.stage);
  const mode = useApp((s) => s.mode);
  const diffs = useApp((s) => s.diffs[ticketNo]?.length ?? 0);
  const findingsCount = useApp((s) => s.findings[ticketNo]?.length ?? 0);
  const usage = useApp((s) => s.usage[ticketNo]);
  const activeSessionId = useApp((s) => s.activeSessionId[ticketNo] ?? "");
  const activeSession = useApp((s) =>
    activeSessionId ? (s.sessions[ticketNo] ?? []).find((x) => x.id === activeSessionId) : undefined,
  );
  const autoAccept = activeSession?.permissionAutoAccept ?? false;
  const [text, setText] = useState("");
  const [pendingAttachments, setPendingAttachments] = useState<PendingAttachment[]>([]);
  const taRef = useRef<HTMLTextAreaElement>(null);
  const live = mode === "live";
  const { sel, providers, currentVariants, imageSupported } = useEffectiveSel(ticketNo);

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

  /** 在光标处插入文本（粘贴引用/绝对路径），插入后把光标移到插入文本之后。 */
  const insertAtCursor = (insert: string) => {
    if (!insert) return;
    const ta = taRef.current;
    const start = ta?.selectionStart ?? text.length;
    const end = ta?.selectionEnd ?? start;
    setText((prev) => prev.slice(0, start) + insert + prev.slice(end));
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
      setText((prev) => prev.slice(0, caretStart) + citations + prev.slice(caretEnd));
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
    setText("");
    setPendingAttachments([]);
    actions.sendPrompt(ticketNo, t, pendingAttachments);
  };

  const quick = [
    diffs === 0 && !terminal
      ? { label: "实现速率限制", prompt: "为 POST /api/checkout 添加速率限制，超限返回 429", Icon: Lightning }
      : null,
    diffs > 0 ? { label: "解释当前变更", prompt: "请解释当前工作区的全部改动", Icon: Eye } : null,
    { label: "运行本地单测", prompt: "运行本地单元测试并汇总结果", Icon: TerminalWindow },
    findingsCount > 0 && stage === "REJECTED"
      ? { label: "按审查意见修复", prompt: "__findings__", Icon: Wrench }
      : null,
  ].filter(Boolean) as Array<{ label: string; prompt: string; Icon: Icon }>;

  return (
    <div className="shrink-0 border-t border-edge bg-panel/50 px-5 py-3">
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
            onChange={(e) => setText(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter" && !e.shiftKey) {
                e.preventDefault();
                send();
              }
            }}
            onPaste={(e) => void handlePaste(e)}
            rows={1}
            placeholder={
              terminal
                ? "工单已完成并归档"
                : busy
                  ? "Agent 正在工作，可点击右下按钮中断；切换的模型/推理强度将在下一回合生效…"
                  : "向 Agent 描述任务…（Enter 发送，Shift+Enter 换行，可粘贴图片/文件）"
            }
            className="composer-ta"
          />

          <div className="flex items-center gap-2 px-2.5 pb-2.5 pt-0.5">
            <div className="flex items-center gap-1 flex-wrap min-w-0">
              <AgentPicker ticketNo={ticketNo} />
              {live && (
                <>
                  <ModelPicker ticketNo={ticketNo} sel={sel} providers={providers} />
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
