import { useEffect, useMemo, useRef, useState } from "react";
import {
  ArrowClockwise,
  CaretDown,
  CheckCircle,
  CircleNotch,
  Lightning,
  Plus,
  PencilSimple,
  Plug,
  Sparkle,
  TerminalWindow,
  Trash,
  WarningCircle,
  X,
} from "@phosphor-icons/react";
import { actions } from "../lib/actions";
import * as live from "../lib/api";
import { appStore, useApp } from "../lib/store";
import type { AgentConfig, OpenCodeModelEntry, OpenCodeProvider } from "../lib/types";
import { useBackdropClose } from "./ui";

const CLI_LABEL: Record<string, string> = { claude: "Claude Code", opencode: "OpenCode" };

/* ── 模型选择（抄 OpenDesign SettingsDialog 的 agent-model 字段）── */

const CUSTOM_MODEL_SENTINEL = "__custom__";

/** 与后端 RuntimeInfoService 的 model_source 对齐：cli=实时探测，其余为内置/兜底列表。 */
function modelSourceBadge(source: string | undefined): { label: string; live: boolean } {
  if (source === "cli" || source === "cli-loading") return { label: "来自 CLI 的实时列表", live: true };
  if (source === "cli-hints") return { label: "CLI 常用别名", live: false };
  return { label: "内置列表", live: false };
}

function splitModelOptions(models: string[]): { flat: string[]; groups: Array<[string, string[]]> } {
  const flat: string[] = [];
  const groups = new Map<string, string[]>();
  for (const m of models) {
    if (m === "default") continue;
    const slash = m.indexOf("/");
    if (slash <= 0) {
      flat.push(m);
      continue;
    }
    const provider = m.slice(0, slash);
    const arr = groups.get(provider) ?? [];
    arr.push(m);
    groups.set(provider, arr);
  }
  return { flat, groups: Array.from(groups.entries()) };
}

function RuntimeCards({ onManageProviders }: { onManageProviders: () => void }) {
  const runtimes = useApp((s) => s.runtimes);
  return (
    <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
      {runtimes.map((r) => (
        <div key={r.name} className="card p-4">
          <div className="flex items-center gap-2.5">
            <span className="w-8 h-8 rounded-lg bg-raised border border-edge grid place-items-center">
              <TerminalWindow size={16} className={r.available ? "text-accent" : "text-faint"} />
            </span>
            <div className="min-w-0">
              <div className="text-[13px] font-semibold flex items-center gap-2">
                {CLI_LABEL[r.name] ?? r.name}
                <span className="font-mono text-[11px] text-faint font-normal">{r.version ?? ""}</span>
              </div>
              <div
                className={`text-[11.5px] flex items-center gap-1 ${
                  r.available ? "text-accent" : "text-warn"
                }`}
              >
                {r.available ? <CheckCircle size={12} weight="fill" /> : <WarningCircle size={12} weight="fill" />}
                {r.available ? "已安装 · 可托管会话" : (r.note ?? "未安装")}
              </div>
            </div>
            <span className="flex-1" />
            {r.name === "opencode" && (
              <button className="btn h-7 text-[12px]" title="管理 opencode.json 里的供应商" onClick={onManageProviders}>
                <Plug size={12} />
                供应商管理
              </button>
            )}
            <button
              className="icon-btn"
              title="重新检测"
              aria-label="重新检测"
              onClick={() => actions.refreshRuntimes()}
            >
              <ArrowClockwise size={14} />
            </button>
          </div>
          <div className="mt-3 pt-3 border-t border-edge">
            <div className="field-label mb-1.5">可用模型 · {r.modelSource === "cli" ? "来自 CLI 探测" : r.modelSource === "cli-hints" ? "CLI 常用别名" : "默认"}<span className="ml-1 normal-case tracking-normal">共 {r.models.length > 0 ? r.models.length : 1} 个</span></div>
            <div
              className="font-mono text-[11px] text-dim whitespace-nowrap overflow-hidden text-ellipsis"
              title={(r.models.length > 0 ? r.models : ["default"]).join("  ")}
            >
              {(r.models.length > 0 ? r.models : ["default"]).join("  ")}
            </div>
          </div>
        </div>
      ))}
    </div>
  );
}

/* ── OpenCode 供应商管理（读写 opencode.json(c) 的 provider 节点，参考 ai-toolbox） ── */

const NPM_PRESETS = [
  "@ai-sdk/openai-compatible",
  "@ai-sdk/anthropic",
  "@ai-sdk/google",
];

/** 保存前的上游探测结果（拉取模型 / 连通测试共用一个展示位）。 */
interface ProbeState {
  kind: "idle" | "fetching" | "testing" | "done" | "error";
  ok?: boolean;
  text?: string;
}

/** opencode 模型配置里已知的结构化字段；其余键作为「额外参数」JSON 透传。 */
const KNOWN_MODEL_KEYS = new Set([
  "name",
  "limit",
  "modalities",
  "reasoning",
  "tool_call",
  "temperature",
  "attachment",
  "variants",
]);

const MODALITY_PRESETS = ["text", "image", "pdf", "video", "audio"];

/** 限制档位（模仿 ai-toolbox）：上下文给大档位，输出给小档位，也可手输任意值。 */
const CONTEXT_LIMIT_PRESETS = ["16K", "32K", "64K", "128K", "200K", "256K", "1M", "2M"];
const OUTPUT_LIMIT_PRESETS = ["2K", "4K", "8K", "16K", "32K", "64K"];

/** 模型变体一键模板：低/高 + 顶档两档，顶档推理强度分别为 max / xhigh。 */
const VARIANT_TEMPLATES = [
  {
    label: "max 模板",
    title: "填入 low/high/max 三档变体，最高推理到 max",
    json: `{
  "low": {
    "reasoningEffort": "low"
  },
  "high": {
    "reasoningEffort": "high"
  },
  "max": {
    "reasoningEffort": "max"
  }
}`,
  },
  {
    label: "xhigh 模板",
    title: "填入 low/high/xhigh 三档变体，最高推理到 xhigh",
    json: `{
  "low": {
    "reasoningEffort": "low"
  },
  "high": {
    "reasoningEffort": "high"
  },
  "xhigh": {
    "reasoningEffort": "xhigh"
  }
}`,
  },
];

/** 200000 → "200K"、1000000 → "1M"；非整除的值原样显示。 */
function formatLimit(v: unknown): string {
  const n = typeof v === "number" ? v : Number(v);
  if (Number.isFinite(n) && n > 0) {
    if (n % 1_000_000 === 0) return `${n / 1_000_000}M`;
    if (n % 1000 === 0) return `${n / 1000}K`;
  }
  return String(v);
}

/** 解析限制输入：支持 "200K" / "1M" 简写与纯数字；非法返回 null。 */
function parseLimit(raw: string): number | null {
  const s = raw.trim().toUpperCase();
  if (!s) return null;
  const m = s.match(/^(\d+(?:\.\d+)?)([KM])$/);
  if (m) {
    const base = Number(m[1]);
    if (!Number.isFinite(base)) return null;
    return Math.round(base * (m[2] === "K" ? 1000 : 1_000_000));
  }
  return /^\d+$/.test(s) ? Number(s) : null;
}

/** 兼容旧持久化形状（string[]）：统一归一为 {id, config} 条目。 */
function normalizeModelEntries(models: (OpenCodeModelEntry | string)[] | undefined): OpenCodeModelEntry[] {
  return (models ?? []).map((m) => (typeof m === "string" ? { id: m, config: {} } : m));
}

function parseModelConfig(cfg: Record<string, unknown>) {
  const limit = (cfg.limit ?? {}) as Record<string, unknown>;
  const mods = (cfg.modalities ?? {}) as Record<string, unknown>;
  const extra: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(cfg)) {
    if (!KNOWN_MODEL_KEYS.has(k)) extra[k] = v;
  }
  return {
    name: typeof cfg.name === "string" ? cfg.name : "",
    context: limit.context != null ? formatLimit(limit.context) : "",
    output: limit.output != null ? formatLimit(limit.output) : "",
    input: Array.isArray(mods.input) ? (mods.input as string[]) : [],
    outputMods: Array.isArray(mods.output) ? (mods.output as string[]) : [],
    reasoning: cfg.reasoning === true,
    toolCall: cfg.tool_call === true,
    temperature: cfg.temperature === true,
    attachment: cfg.attachment === true,
    variants: cfg.variants != null ? JSON.stringify(cfg.variants, null, 2) : "",
    extra: Object.keys(extra).length > 0 ? JSON.stringify(extra, null, 2) : "",
  };
}

/** ai-toolbox 式限制编辑框：聚焦展开档位下拉、点选即填，也可手输 16K / 200000 等任意值。 */
function LimitCombo({
  presets,
  value,
  onChange,
  placeholder,
  ariaLabel,
}: {
  presets: string[];
  value: string;
  onChange: (v: string) => void;
  placeholder: string;
  ariaLabel: string;
}) {
  const [open, setOpen] = useState(false);
  const [up, setUp] = useState(false);
  const [hi, setHi] = useState(-1);
  const rootRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (!open) return;
    // 面板是 overflow-y-auto 滚动容器：贴近底部时下拉会被裁掉，空间不足改为向上弹出。
    const box = rootRef.current?.getBoundingClientRect();
    if (box) {
      const scroller = rootRef.current?.closest(".overflow-y-auto");
      const limit = scroller ? scroller.getBoundingClientRect().bottom : window.innerHeight;
      setUp(limit - box.bottom < 190);
    }
    const onDown = (e: PointerEvent) => {
      if (rootRef.current && !rootRef.current.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener("pointerdown", onDown);
    return () => document.removeEventListener("pointerdown", onDown);
  }, [open]);

  useEffect(() => {
    if (!open) return;
    const list = rootRef.current?.querySelector("[data-combo-list]");
    const el = list?.children[hi] as HTMLElement | undefined;
    el?.scrollIntoView({ block: "nearest" });
  }, [hi, open]);

  const query = value.trim().toLowerCase();
  const options = useMemo(() => {
    if (!query) return presets;
    return presets.filter((p) => {
      if (p.toLowerCase().includes(query)) return true;
      const n = parseLimit(p);
      return n != null && String(n).includes(query);
    });
  }, [presets, query]);

  const pick = (p: string) => {
    onChange(p);
    setOpen(false);
    setHi(-1);
  };

  return (
    <div ref={rootRef} className="relative">
      <input
        ref={inputRef}
        className="text-input font-mono text-[12px] pr-8"
        placeholder={placeholder}
        aria-label={ariaLabel}
        value={value}
        onFocus={() => setOpen(true)}
        onChange={(e) => {
          onChange(e.target.value);
          setOpen(true);
          setHi(-1);
        }}
        onKeyDown={(e) => {
          if (e.key === "ArrowDown") {
            e.preventDefault();
            setHi((i) => Math.min(i + 1, options.length - 1));
            setOpen(true);
          } else if (e.key === "ArrowUp" && open) {
            e.preventDefault();
            setHi((i) => Math.max(i - 1, -1));
          } else if (e.key === "Enter") {
            e.preventDefault();
            if (open && (hi >= 0 ? options[hi] : options.length === 1)) pick(options[hi >= 0 ? hi : 0]);
            else setOpen(false);
          } else if (e.key === "Escape") {
            setOpen(false);
            setHi(-1);
          } else if (e.key === "Tab") {
            setOpen(false);
          }
        }}
      />
      <button
        type="button"
        className="absolute right-1 top-1/2 -translate-y-1/2 p-1.5 rounded-md text-faint hover:text-ink cursor-pointer bg-transparent border-0"
        tabIndex={-1}
        aria-label={open ? "收起档位列表" : "展开档位列表"}
        onMouseDown={(e) => e.preventDefault()}
        onClick={() => setOpen((v) => !v)}
      >
        <CaretDown size={12} className={`transition-transform ${open ? "rotate-180" : ""}`} />
      </button>
      {open && (
        <div
          data-combo-list
          className={`absolute z-30 left-0 right-0 rounded-lg border border-edge bg-canvas shadow-lg shadow-black/25 py-1 max-h-[176px] overflow-y-auto ${
            up ? "bottom-[calc(100%+4px)]" : "top-[calc(100%+4px)]"
          }`}
        >
          {options.length === 0 && (
            <div className="px-3 py-1.5 text-[11.5px] text-faint">没有匹配的档位，可直接使用输入的值</div>
          )}
          {options.map((p, i) => {
            const n = parseLimit(p);
            const selected = parseLimit(value) != null && parseLimit(value) === n;
            return (
              <button
                key={p}
                type="button"
                className={`w-full flex items-center gap-2 px-3 py-1.5 text-left font-mono text-[12px] cursor-pointer border-0 bg-transparent ${
                  selected ? "text-accent bg-accent/10" : hi === i ? "bg-raised text-ink" : "text-dim"
                }`}
                onMouseEnter={() => setHi(i)}
                onMouseDown={(e) => e.preventDefault()}
                onClick={() => pick(p)}
              >
                {p}
                {n != null && (
                  <span className="ml-auto text-[10.5px] text-faint tracking-normal">{n.toLocaleString("en-US")}</span>
                )}
              </button>
            );
          })}
        </div>
      )}
    </div>
  );
}

/** ai-toolbox 式模型编辑器：限制 / 模态 / 能力 / 变体 / 额外参数，内联展开在模型行下方。 */
function ModelEditor({
  entry,
  onSave,
  onCancel,
}: {
  entry: OpenCodeModelEntry;
  onSave: (next: OpenCodeModelEntry) => void;
  onCancel: () => void;
}) {
  const parsed = parseModelConfig(entry.config);
  const [name, setName] = useState(parsed.name);
  const [context, setContext] = useState(parsed.context);
  const [output, setOutput] = useState(parsed.output);
  // 所有模型都支持 text 输入输出：配置未写模态时默认勾上 text，显式配置（如仅 image）则原样展示。
  const [input, setInput] = useState<string[]>(parsed.input.length > 0 ? parsed.input : ["text"]);
  const [outputMods, setOutputMods] = useState<string[]>(parsed.outputMods.length > 0 ? parsed.outputMods : ["text"]);
  const [reasoning, setReasoning] = useState(parsed.reasoning);
  const [toolCall, setToolCall] = useState(parsed.toolCall);
  const [temperature, setTemperature] = useState(parsed.temperature);
  const [attachment, setAttachment] = useState(parsed.attachment);
  const [variants, setVariants] = useState(parsed.variants);
  const [extra, setExtra] = useState(parsed.extra);
  const [advancedOpen, setAdvancedOpen] = useState(
    parsed.input.length > 0 ||
      parsed.outputMods.length > 0 ||
      parsed.reasoning ||
      parsed.toolCall ||
      parsed.temperature ||
      parsed.attachment ||
      parsed.variants !== "" ||
      parsed.extra !== "",
  );
  const [jsonError, setJsonError] = useState<string | null>(null);

  const toggleModality = (list: string[], set: (v: string[]) => void, m: string) =>
    set(list.includes(m) ? list.filter((x) => x !== m) : [...list, m]);

  const save = () => {
    let parsedVariants: unknown = undefined;
    let parsedExtra: Record<string, unknown> = {};
    try {
      if (variants.trim()) parsedVariants = JSON.parse(variants);
      if (extra.trim()) parsedExtra = JSON.parse(extra) as Record<string, unknown>;
    } catch (e) {
      setJsonError(`JSON 解析失败：${(e as Error).message}`);
      return;
    }
    const ctx = context.trim() ? parseLimit(context) : null;
    const outNum = output.trim() ? parseLimit(output) : null;
    const bad = [context.trim() && ctx == null ? "上下文限制" : "", output.trim() && outNum == null ? "输出限制" : ""].filter(Boolean);
    if (bad.length > 0) {
      setJsonError(`${bad.join("、")}需为数字或 16K / 1M 这类简写`);
      return;
    }
    setJsonError(null);
    const cfg: Record<string, unknown> = { ...parsedExtra };
    if (name.trim()) cfg.name = name.trim();
    const limit: Record<string, number> = {};
    if (ctx != null) limit.context = ctx;
    if (outNum != null) limit.output = outNum;
    if (Object.keys(limit).length > 0) cfg.limit = limit;
    const mods: Record<string, string[]> = {};
    if (input.length > 0) mods.input = input;
    if (outputMods.length > 0) mods.output = outputMods;
    if (Object.keys(mods).length > 0) cfg.modalities = mods;
    if (reasoning) cfg.reasoning = true;
    if (toolCall) cfg.tool_call = true;
    if (temperature) cfg.temperature = true;
    if (attachment) cfg.attachment = true;
    if (parsedVariants !== undefined) cfg.variants = parsedVariants;
    onSave({ id: entry.id, config: cfg });
  };

  const cap = (label: string, v: boolean, set: (b: boolean) => void) => (
    <label className="flex items-center gap-1.5 cursor-pointer text-[12.5px]">
      <input type="checkbox" className="accent-accent" checked={v} onChange={(e) => set(e.target.checked)} />
      {label}
    </label>
  );

  return (
    <div className="rounded-lg border border-accent/30 bg-raised/40 p-4 space-y-3.5">
      <div className="flex items-center gap-2">
        <span className="text-[12.5px] font-semibold">编辑模型</span>
        <span className="chip border border-edge-strong bg-canvas text-dim font-mono">{entry.id}</span>
        <span className="flex-1" />
        <button type="button" className="icon-btn" title="取消" aria-label="取消编辑模型" onClick={onCancel}>
          <X size={13} />
        </button>
      </div>

      <div className="grid grid-cols-2 gap-3">
        <div>
          <label className="field-label">模型名称</label>
          <input className="text-input" placeholder={entry.id} value={name} onChange={(e) => setName(e.target.value)} />
        </div>
        <div className="grid grid-cols-2 gap-3">
          <div>
            <label className="field-label">上下文限制</label>
            <LimitCombo
              presets={CONTEXT_LIMIT_PRESETS}
              value={context}
              onChange={setContext}
              placeholder="200000 或 200K"
              ariaLabel="上下文限制"
            />
          </div>
          <div>
            <label className="field-label">输出限制</label>
            <LimitCombo
              presets={OUTPUT_LIMIT_PRESETS}
              value={output}
              onChange={setOutput}
              placeholder="16000 或 16K"
              ariaLabel="输出限制"
            />
          </div>
        </div>
      </div>

      <button
        type="button"
        className="flex items-center gap-1 text-[12px] text-accent cursor-pointer bg-transparent border-0 p-0"
        onClick={() => setAdvancedOpen((v) => !v)}
      >
        <CaretDown size={12} className={`transition-transform ${advancedOpen ? "" : "-rotate-90"}`} />
        高级设置
      </button>

      {advancedOpen && (
        <div className="space-y-3.5">
          <div>
            <label className="field-label">输入模态</label>
            <div className="flex flex-wrap gap-1.5">
              {MODALITY_PRESETS.map((m) => (
                <button
                  key={m}
                  type="button"
                  className={`chip cursor-pointer font-mono ${
                    input.includes(m)
                      ? "border-accent/40 bg-accent/10 text-accent"
                      : "border-edge-strong bg-canvas text-faint"
                  }`}
                  onClick={() => toggleModality(input, setInput, m)}
                >
                  {m}
                </button>
              ))}
            </div>
          </div>
          <div>
            <label className="field-label">输出模态</label>
            <div className="flex flex-wrap gap-1.5">
              {MODALITY_PRESETS.map((m) => (
                <button
                  key={m}
                  type="button"
                  className={`chip cursor-pointer font-mono ${
                    outputMods.includes(m)
                      ? "border-accent/40 bg-accent/10 text-accent"
                      : "border-edge-strong bg-canvas text-faint"
                  }`}
                  onClick={() => toggleModality(outputMods, setOutputMods, m)}
                >
                  {m}
                </button>
              ))}
            </div>
            <div className="mt-1 text-[11px] text-faint">配置模型支持的输入输出类型，如 text、image、pdf、video、audio 等</div>
          </div>
          <div>
            <label className="field-label">模型能力</label>
            <div className="flex flex-wrap gap-4">
              {cap("推理", reasoning, setReasoning)}
              {cap("工具调用", toolCall, setToolCall)}
              {cap("温度", temperature, setTemperature)}
              {cap("附件", attachment, setAttachment)}
            </div>
            <div className="mt-1 text-[11px] text-faint">模型是否具备相应能力</div>
          </div>
          <div>
            <div className="flex items-center gap-1.5 mb-1.5">
              <label className="field-label !mb-0">模型变体（JSON）</label>
              <span className="flex-1" />
              <span className="text-[10.5px] text-faint">一键填入</span>
              {VARIANT_TEMPLATES.map((t) => (
                <button
                  key={t.label}
                  type="button"
                  title={t.title}
                  className="chip border border-edge-strong bg-canvas text-dim cursor-pointer hover:border-accent/40 hover:text-accent transition-colors"
                  onClick={() => setVariants(t.json)}
                >
                  {t.label}
                </button>
              ))}
            </div>
            <textarea
              className="text-input font-mono text-[12px] h-36 resize-y"
              placeholder={'{\n  "high": { "reasoningEffort": "high" }\n}'}
              value={variants}
              onChange={(e) => setVariants(e.target.value)}
            />
            <div className="mt-1 text-[11px] text-faint">配置模型的不同变体，如推理强度、输出详细程度等</div>
          </div>
          <div>
            <label className="field-label">额外参数（JSON）</label>
            <textarea
              className="text-input font-mono text-[12px] h-20 resize-y"
              placeholder={'{\n  "store": false\n}'}
              value={extra}
              onChange={(e) => setExtra(e.target.value)}
            />
          </div>
        </div>
      )}
      {jsonError && <div className="text-[11.5px] text-danger">{jsonError}</div>}
      <div className="flex justify-end gap-2 pt-1">
        <button type="button" className="btn h-8" onClick={onCancel}>
          取消
        </button>
        <button type="button" className="btn btn-primary h-8" onClick={save}>
          应用模型配置
        </button>
      </div>
    </div>
  );
}

/**
 * 供应商编辑面板：由 OpenCodeProvidersModal 以右侧拼接面板承载（motion 动效在父级）。
 */
function OcProviderPanel({
  initial,
  onClose,
}: {
  initial: OpenCodeProvider | null;
  onClose: () => void;
}) {
  const mode = useApp((s) => s.mode);
  const [key, setKey] = useState(initial?.key ?? "");
  const [name, setName] = useState(initial?.name ?? "");
  const [npm, setNpm] = useState(initial?.npm ?? NPM_PRESETS[0]);
  const [baseURL, setBaseURL] = useState(initial?.baseURL ?? "");
  const [apiKey, setApiKey] = useState(initial?.apiKey ?? "");
  const [models, setModels] = useState<OpenCodeModelEntry[]>(normalizeModelEntries(initial?.models));
  const [fetched, setFetched] = useState<string[]>([]);
  const [customModel, setCustomModel] = useState("");
  const [editingModel, setEditingModel] = useState<string | null>(null);
  const [probe, setProbe] = useState<ProbeState>({ kind: "idle" });
  const [saving, setSaving] = useState(false);

  const keyValid = /^[A-Za-z0-9._\-/]+$/.test(key.trim());
  const canSave = keyValid && name.trim().length > 0 && !saving;
  const canProbe = mode === "live" && !!baseURL.trim() && probe.kind !== "fetching" && probe.kind !== "testing";
  const selectedIds = models.map((m) => m.id);

  const toggleModel = (id: string) =>
    setModels((prev) =>
      prev.some((m) => m.id === id)
        ? prev.filter((m) => m.id !== id)
        : [...prev, { id, config: {} }],
    );

  const addCustomModel = () => {
    const id = customModel.trim();
    if (!id) return;
    setModels((prev) => (prev.some((m) => m.id === id) ? prev : [...prev, { id, config: {} }]));
    setCustomModel("");
  };

  const fetchModels = async () => {
    if (!canProbe) return;
    setProbe({ kind: "fetching" });
    try {
      const list = await live.fetchOcModelsLive(baseURL.trim(), apiKey.trim());
      setFetched(list);
      // 已选但上游没返回的手工模型保留；上游新模型默认不勾选，由用户多选。
      setProbe({ kind: "done", ok: true, text: `拉取到 ${list.length} 个模型，勾选要写入配置的模型` });
    } catch (e) {
      setProbe({ kind: "error", ok: false, text: (e as Error).message });
    }
  };

  const testModel = async () => {
    if (!canProbe) return;
    const model = models[0]?.id ?? fetched[0];
    if (!model) {
      setProbe({ kind: "error", ok: false, text: "先拉取或填写至少一个模型再测试" });
      return;
    }
    setProbe({ kind: "testing" });
    try {
      const r = await live.testOcModelLive(baseURL.trim(), apiKey.trim(), model);
      setProbe(
        r.ok
          ? { kind: "done", ok: true, text: `${model} · ${r.latency_ms}ms${r.reply ? ` · ${r.reply}` : ""}` }
          : { kind: "error", ok: false, text: `${model} · ${r.error ?? `HTTP ${r.status_code}`}` },
      );
    } catch (e) {
      setProbe({ kind: "error", ok: false, text: (e as Error).message });
    }
  };

  const save = async () => {
    if (!canSave) return;
    setSaving(true);
    const ok = await actions.saveOcProvider({
      key: key.trim(),
      name: name.trim(),
      npm: npm.trim() || null,
      baseURL: baseURL.trim() || null,
      apiKey: apiKey.trim() || null,
      models,
      modelCount: models.length,
    });
    setSaving(false);
    if (ok) onClose();
  };

  return (
    <div className="flex h-full flex-col bg-canvas">
        <div className="flex items-center gap-2 px-5 h-12 border-b border-edge shrink-0">
          <Plug size={15} className="text-accent" weight="fill" />
          <span className="text-[13.5px] font-semibold">
            {initial ? `编辑供应商 · ${initial.key}` : "新增 OpenCode 供应商"}
          </span>
          <span className="flex-1" />
          <button className="icon-btn" title="关闭" aria-label="关闭" onClick={onClose}>
            <X size={15} />
          </button>
        </div>

        <div className="p-5 space-y-4 flex-1 overflow-y-auto">
          <div>
            <label className="field-label">供应商 key（模型 id 前缀）</label>
            <input
              autoFocus={!initial}
              disabled={!!initial}
              className="text-input font-mono text-[12px] disabled:opacity-50"
              placeholder="例如：deepseek"
              value={key}
              onChange={(e) => setKey(e.target.value)}
            />
            {key.trim() && !keyValid && (
              <div className="mt-1 text-[11px] text-warn">仅允许字母、数字与 . _ - /</div>
            )}
            <div className="mt-1 text-[11px] text-faint">
              写入 opencode.json 后，模型 id 形如 <span className="font-mono">{key.trim() || "key"}/模型名</span>
            </div>
          </div>

          <div>
            <label className="field-label">显示名称</label>
            <input
              className="text-input"
              placeholder="例如：DeepSeek"
              value={name}
              onChange={(e) => setName(e.target.value)}
            />
          </div>

          <div>
            <label className="field-label">npm SDK 包</label>
            <input
              className="text-input font-mono text-[12px]"
              list="oc-npm-presets"
              placeholder="@ai-sdk/openai-compatible"
              value={npm ?? ""}
              onChange={(e) => setNpm(e.target.value)}
            />
            <datalist id="oc-npm-presets">
              {NPM_PRESETS.map((n) => (
                <option key={n} value={n} />
              ))}
            </datalist>
          </div>

          <div>
            <label className="field-label">Base URL</label>
            <input
              className="text-input font-mono text-[12px]"
              placeholder="https://api.deepseek.com/v1"
              value={baseURL ?? ""}
              onChange={(e) => setBaseURL(e.target.value)}
            />
          </div>

          <div>
            <label className="field-label">API Key{initial?.apiKey ? "（已保存，覆盖或清空即改写）" : ""}</label>
            <input
              className="text-input font-mono text-[12px]"
              type="password"
              placeholder={initial?.apiKey ? "••••••••" : "sk-…"}
              value={apiKey ?? ""}
              onChange={(e) => setApiKey(e.target.value)}
            />
          </div>

          <div>
            <div className="flex items-center gap-2 mb-1.5">
              <label className="field-label !mb-0">模型</label>
              <span className="flex-1" />
              <button
                type="button"
                className="chip border border-edge-strong bg-raised text-dim cursor-pointer disabled:opacity-40 disabled:pointer-events-none"
                disabled={!canProbe}
                onClick={fetchModels}
                title={mode === "live" ? "用上方 Base URL / API Key 请求上游 /models" : "仅 live 模式可探测上游"}
              >
                {probe.kind === "fetching" ? "拉取中…" : "拉取模型"}
              </button>
              <button
                type="button"
                className="chip border border-info/30 bg-info/10 text-info cursor-pointer disabled:opacity-40 disabled:pointer-events-none"
                disabled={!canProbe}
                onClick={testModel}
                title="对第一个已选模型发一条最小 chat completion 验证连通"
              >
                {probe.kind === "testing" ? "测试中…" : "测试连通"}
              </button>
            </div>

            {mode !== "live" && (
              <div className="mb-2 text-[11px] text-warn">
                演示模式下无法访问本机后端：连接本地后端（live）后即可拉取模型与测试连通。
              </div>
            )}
            {mode === "live" && !baseURL.trim() && (
              <div className="mb-2 text-[11px] text-faint">填写 Base URL 后可拉取上游模型并测试连通。</div>
            )}

            {probe.kind !== "idle" && probe.kind !== "fetching" && probe.kind !== "testing" && (
              <div className={`mb-2 text-[11.5px] ${probe.ok ? "text-accent" : "text-danger"}`}>
                {probe.ok ? "✓ " : "✗ "}
                {probe.text}
              </div>
            )}

            {fetched.length > 0 && (
              <div className="mb-2 rounded-lg border border-edge bg-canvas/50 max-h-[180px] overflow-y-auto p-2">
                <div className="flex items-center gap-2 px-1 pb-1.5 text-[11px] text-faint">
                  <span>上游返回 {fetched.length} 个 · 勾选写入配置</span>
                  <span className="flex-1" />
                  <button
                    type="button"
                    className="cursor-pointer bg-transparent border-0 p-0 text-dim hover:text-accent"
                    onClick={() =>
                      setModels((prev) => {
                        const have = new Set(prev.map((m) => m.id));
                        return [...prev, ...fetched.filter((id) => !have.has(id)).map((id) => ({ id, config: {} }))];
                      })
                    }
                  >
                    全选
                  </button>
                  <span>·</span>
                  <button
                    type="button"
                    className="cursor-pointer bg-transparent border-0 p-0 text-dim hover:text-accent"
                    onClick={() => setModels(models.filter((m) => !fetched.includes(m.id)))}
                  >
                    清空上游项
                  </button>
                </div>
                {fetched.map((m) => (
                  <label
                    key={m}
                    className="flex items-center gap-2 px-1 py-[3px] rounded-md hover:bg-raised cursor-pointer text-[12px] font-mono"
                  >
                    <input
                      type="checkbox"
                      className="accent-accent"
                      checked={selectedIds.includes(m)}
                      onChange={() => toggleModel(m)}
                    />
                    {m}
                  </label>
                ))}
              </div>
            )}

            <div className="flex items-center gap-2">
              <input
                className="text-input font-mono text-[12px]"
                placeholder="手动补充模型 id，回车添加"
                value={customModel}
                onChange={(e) => setCustomModel(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === "Enter") {
                    e.preventDefault();
                    addCustomModel();
                  }
                }}
              />
              <button type="button" className="btn h-9 shrink-0" onClick={addCustomModel}>
                添加
              </button>
            </div>

            {models.length > 0 && (
              <div className="mt-2 space-y-2">
                {models.map((m) => (
                  <div key={m.id}>
                    <div className="flex items-center gap-2 rounded-lg border border-edge bg-raised/40 px-3 py-2">
                      <span className="font-mono text-[12px] text-dim truncate">{m.id}</span>
                      {typeof m.config.name === "string" && m.config.name && (
                        <span className="text-[11.5px] text-faint truncate">{m.config.name}</span>
                      )}
                      <span className="flex-1" />
                      <button
                        type="button"
                        className="icon-btn"
                        title="编辑模型配置"
                        aria-label="编辑模型配置"
                        onClick={() => setEditingModel(editingModel === m.id ? null : m.id)}
                      >
                        <PencilSimple size={12} />
                      </button>
                      <button
                        type="button"
                        className="icon-btn hover:!text-danger"
                        title="移除模型"
                        aria-label="移除模型"
                        onClick={() => setModels((prev) => prev.filter((x) => x.id !== m.id))}
                      >
                        <Trash size={12} />
                      </button>
                    </div>
                    {editingModel === m.id && (
                      <div className="mt-2">
                        <ModelEditor
                          entry={m}
                          onSave={(next) => {
                            setModels((prev) => prev.map((x) => (x.id === next.id ? next : x)));
                            setEditingModel(null);
                          }}
                          onCancel={() => setEditingModel(null)}
                        />
                      </div>
                    )}
                  </div>
                ))}
              </div>
            )}
          </div>

          <div className="text-[11px] text-faint">
            保存会直接写入本机 OpenCode 配置文件（只改 provider 节点，其余内容原样保留；写前自动备份 .bak）。
          </div>
        </div>

        <div className="flex justify-end gap-2 px-5 py-4 border-t border-edge shrink-0">
          <button className="btn" onClick={onClose}>
            取消
          </button>
          <button className="btn btn-primary" disabled={!canSave} onClick={save}>
            {saving ? "保存中…" : "保存"}
          </button>
        </div>
    </div>
  );
}


/** 供应商管理全屏弹窗：从 OpenCode 运行时卡片的入口进入。 */
function OpenCodeProvidersModal({ onClose }: { onClose: () => void }) {
  const ocProviders = useApp((s) => s.ocProviders);
  const ocConfigPath = useApp((s) => s.ocConfigPath);
  const mode = useApp((s) => s.mode);
  const backdrop = useBackdropClose(onClose);
  const [dialog, setDialog] = useState<{ open: boolean; provider: OpenCodeProvider | null }>({
    open: false,
    provider: null,
  });
  const [confirmDelete, setConfirmDelete] = useState<string | null>(null);
  const [testing, setTesting] = useState<string | null>(null);
  const [testResult, setTestResult] = useState<{ key: string; ok: boolean; text: string } | null>(null);

  useEffect(() => {
    actions.loadOcProviders();
  }, []);

  const testProvider = async (p: OpenCodeProvider) => {
    const model = normalizeModelEntries(p.models)[0]?.id;
    if (!model || !p.baseURL) {
      setTestResult({ key: p.key, ok: false, text: "缺少 Base URL 或模型，无法测试" });
      return;
    }
    setTesting(p.key);
    setTestResult(null);
    try {
      const r = await live.testOcModelLive(p.baseURL, p.apiKey ?? "", model);
      setTestResult({
        key: p.key,
        ok: r.ok,
        text: r.ok ? `${model} · ${r.latency_ms}ms${r.reply ? ` · ${r.reply}` : ""}` : `${model} · ${r.error ?? `HTTP ${r.status_code}`}`,
      });
    } catch (e) {
      setTestResult({ key: p.key, ok: false, text: (e as Error).message });
    } finally {
      setTesting(null);
    }
  };

  return (
    <div className="fixed inset-0 z-50 grid place-items-center bg-black/55 backdrop-blur-[2px]" {...backdrop}>
      {/* 编辑面板打开时整个弹窗加宽：因居中布局，列表自然左移，面板在右侧拼接。
          宽度用 CSS transition（motion 对 auto→px 的宽度插值不可靠），滑入用 motion。 */}
      <div
        className="relative flex card shadow-2xl shadow-black/60 animate-rise overflow-hidden transition-[width] duration-300 ease-out"
        style={{ width: dialog.open ? 1180 : 700, maxWidth: "96vw", height: 640 }}
        onClick={(e) => e.stopPropagation()}
      >
        <div className="w-[700px] max-w-full shrink-0 flex flex-col min-w-0">
        <div className="flex items-center gap-2 px-5 h-12 border-b border-edge shrink-0">
          <Plug size={15} className="text-accent" weight="fill" />
          <span className="text-[13.5px] font-semibold">OpenCode 供应商管理</span>
          {ocConfigPath && <span className="font-mono text-[11px] text-faint truncate">{ocConfigPath}</span>}
          <span className="flex-1" />
          <button className="btn h-8" onClick={() => actions.loadOcProviders()} title="重新读取配置文件">
            <ArrowClockwise size={13} />
            刷新
          </button>
          <button className="btn btn-primary h-8" onClick={() => setDialog({ open: true, provider: null })}>
            <Plus size={14} weight="bold" />
            新增供应商
          </button>
          <button className="icon-btn" title="关闭" aria-label="关闭" onClick={onClose}>
            <X size={15} />
          </button>
        </div>

        <div className="p-5 overflow-y-auto space-y-2 flex-1">
          {ocProviders.map((p) => (
            <div key={p.key} className="card p-4">
              <div className="flex items-center gap-2.5">
                <span className="w-7 h-7 rounded-lg bg-raised border border-edge grid place-items-center text-accent shrink-0">
                  <Plug size={14} />
                </span>
                <span className="text-[13.5px] font-semibold">{p.name}</span>
                <span className="chip border border-edge-strong bg-raised text-dim font-mono">{p.key}</span>
                {p.npm && <span className="chip border border-edge bg-canvas text-faint font-mono">{p.npm}</span>}
                <span className="flex-1" />
                {testResult?.key === p.key && (
                  <span className={`text-[11.5px] truncate max-w-[260px] ${testResult.ok ? "text-accent" : "text-danger"}`}>
                    {testResult.ok ? "✓ " : "✗ "}
                    {testResult.text}
                  </span>
                )}
                {confirmDelete === p.key ? (
                  <span className="flex items-center gap-1">
                    <button
                      className="chip border border-danger/40 bg-danger/10 text-danger cursor-pointer"
                      onClick={() => {
                        void actions.deleteOcProvider(p.key);
                        setConfirmDelete(null);
                      }}
                    >
                      确认删除
                    </button>
                    <button className="chip border border-edge-strong text-dim cursor-pointer" onClick={() => setConfirmDelete(null)}>
                      返回
                    </button>
                  </span>
                ) : (
                  <>
                    <button
                      className="icon-btn"
                      title="测试连通（第一个模型）"
                      aria-label="测试连通"
                      disabled={testing === p.key}
                      onClick={() => void testProvider(p)}
                    >
                      {testing === p.key ? <CircleNotch size={13} className="animate-spin" /> : <Lightning size={13} />}
                    </button>
                    <button
                      className="icon-btn"
                      title="编辑"
                      aria-label="编辑"
                      onClick={() => setDialog({ open: true, provider: p })}
                    >
                      <PencilSimple size={13} />
                    </button>
                    <button
                      className="icon-btn hover:!text-danger"
                      title="删除"
                      aria-label="删除"
                      onClick={() => setConfirmDelete(p.key)}
                    >
                      <Trash size={13} />
                    </button>
                  </>
                )}
              </div>
              <div className="mt-2.5 flex items-center gap-3 text-[11.5px] text-faint">
                {p.baseURL && (
                  <span className="font-mono truncate max-w-[380px]" title={p.baseURL}>
                    {p.baseURL}
                  </span>
                )}
                <span>
                  模型{" "}
                  <span className="font-mono text-dim">
                    {normalizeModelEntries(p.models).length > 0
                      ? normalizeModelEntries(p.models).map((m) => m.id).join(" · ")
                      : "—"}
                  </span>
                </span>
              </div>
            </div>
          ))}
          {ocProviders.length === 0 && (
            <div className="card border-dashed p-8 text-center">
              <div className="text-[13px] text-dim">
                {mode === "live"
                  ? "opencode.json 里还没有 provider，或文件尚未创建"
                  : "还没有供应商配置"}
              </div>
              <div className="mt-1 text-[12px] text-faint">新增一个 OpenCode 兼容供应商后，智能体即可选用它的模型</div>
            </div>
          )}
        </div>
        </div>

        {/* 编辑面板：右侧拼接；滑入用 CSS 关键帧（后台标签页也不受 rAF 节流影响） */}
        {dialog.open && (
          <div
            key={`provider-panel-${dialog.provider?.key ?? "new"}`}
            className="w-[480px] shrink-0 border-l border-edge bg-canvas animate-panel-in"
          >
            <OcProviderPanel initial={dialog.provider} onClose={() => setDialog({ open: false, provider: null })} />
          </div>
        )}
      </div>
    </div>
  );
}

function AgentDialog({ initial, onClose }: { initial: AgentConfig | null; onClose: () => void }) {
  const runtimes = useApp((s) => s.runtimes);
  const backdrop = useBackdropClose(onClose);
  const [name, setName] = useState(initial?.name ?? "");
  const [cli, setCli] = useState<"claude" | "opencode">(initial?.cli ?? "opencode");
  const [model, setModel] = useState(initial?.model ?? "");
  const [customMode, setCustomMode] = useState(false);
  const [systemPrompt, setSystemPrompt] = useState(initial?.systemPrompt ?? "");
  const [injectContext, setInjectContext] = useState(initial?.injectContext ?? true);
  const [extraFlags, setExtraFlags] = useState((initial?.extraFlags ?? []).join(" "));
  const [description, setDescription] = useState(initial?.description ?? "");
  const [saving, setSaving] = useState(false);

  const runtime = runtimes.find((r) => r.name === cli);
  const models = runtime?.models ?? [];
  const modelSource = runtime?.modelSource;
  const loadingModels = modelSource === "cli-loading";
  const badge = modelSourceBadge(modelSource);
  const knownIds = useMemo(
    () => ["default", ...models.filter((m) => m !== "default")],
    [models],
  );
  const customActive =
    customMode || (model !== "" && !knownIds.includes(model));
  const selectValue = customActive ? CUSTOM_MODEL_SENTINEL : model || "default";

  // OpenDesign 式实时拉取：打开弹窗即刷新一次 CLI 运行时（含模型目录）。
  useEffect(() => {
    actions.refreshRuntimes();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // 后端对 OpenCode 的模型目录是异步探测的（model_source=cli-loading），轮询直到落地。
  useEffect(() => {
    if (modelSource !== "cli-loading") return;
    let tries = 0;
    const t = setInterval(() => {
      tries += 1;
      actions.refreshRuntimes();
      if (tries >= 10) clearInterval(t);
    }, 1500);
    return () => clearInterval(t);
  }, [modelSource]);

  const save = async () => {
    if (!name.trim()) return;
    setSaving(true);
    const id =
      initial?.id ??
      `${cli}-${Date.now().toString(36)}`;
    await actions.saveAgentConfig({
      id,
      name: name.trim(),
      cli,
      providerId: null,
      model: model.trim() || "default",
      systemPrompt: systemPrompt.trim() || null,
      extraFlags: extraFlags.split(/\s+/).filter(Boolean),
      description: description.trim() || null,
      injectContext,
    });
    setSaving(false);
    onClose();
  };

  return (
    <div className="fixed inset-0 z-50 grid place-items-center bg-black/55 backdrop-blur-[2px]" {...backdrop}>
      <div className="w-[500px] card shadow-2xl shadow-black/60 animate-rise" onClick={(e) => e.stopPropagation()}>
        <div className="flex items-center gap-2 px-5 h-12 border-b border-edge">
          <Sparkle size={15} className="text-accent" weight="fill" />
          <span className="text-[13.5px] font-semibold">{initial ? "编辑智能体" : "新增智能体员工"}</span>
        </div>

        <div className="p-5 space-y-4 max-h-[70vh] overflow-y-auto">
          {!initial && (
            <div>
              <label className="field-label">第一步 · 选择本地 CLI</label>
              <div className="grid grid-cols-2 gap-2">
                {(["opencode", "claude"] as const).map((c) => {
                  const rt = runtimes.find((r) => r.name === c);
                  const disabled = rt ? !rt.available : false;
                  return (
                    <button
                      key={c}
                      disabled={disabled}
                      onClick={() => {
                        setCli(c);
                        setModel("");
                        setCustomMode(false);
                      }}
                      className={`rounded-xl border p-3 text-left cursor-pointer transition-colors disabled:opacity-40 disabled:pointer-events-none ${
                        cli === c ? "border-accent/50 bg-accent/[0.06]" : "border-edge hover:border-edge-strong hover:bg-raised/60"
                      }`}
                    >
                      <div className="flex items-center gap-2">
                        <TerminalWindow size={14} className={rt?.available ? "text-accent" : "text-faint"} />
                        <span className="text-[13px] font-medium">{CLI_LABEL[c]}</span>
                        <span className="flex-1" />
                        {rt?.available && <CheckCircle size={13} className="text-accent" weight="fill" />}
                      </div>
                      <div className="mt-1 font-mono text-[10.5px] text-faint">
                        {rt?.version ? `v${rt.version}` : (rt?.note ?? "未检测到")}
                      </div>
                    </button>
                  );
                })}
              </div>
            </div>
          )}

          <div>
            <label className="field-label">{initial ? "名称" : "第二步 · 命名与模型"}</label>
            <input
              autoFocus={!initial}
              className="text-input mb-3"
              placeholder="例如：Claude 主力"
              value={name}
              onChange={(e) => setName(e.target.value)}
            />

            <div className="flex items-center gap-2 mb-1.5">
              <span className="text-[11px] font-medium uppercase tracking-[0.08em] text-faint">模型</span>
              <span className={`source-badge ${badge.live ? "live" : "fallback"}`}>{badge.label}</span>
              <span className="flex-1" />
              <button
                className="icon-btn w-5 h-5"
                title="重新拉取模型"
                aria-label="重新拉取模型"
                onClick={() => actions.refreshRuntimes()}
              >
                <ArrowClockwise size={12} className={loadingModels ? "animate-spin" : ""} />
              </button>
            </div>

            {loadingModels ? (
              <div className="text-input h-9 flex items-center gap-2 text-dim" role="status" aria-busy="true">
                <CircleNotch size={13} className="animate-spin text-accent" />
                正在从 CLI 拉取模型…
              </div>
            ) : (
              <div className="relative">
                <select
                  className="text-input appearance-none pr-8 cursor-pointer font-mono text-[12px]"
                  value={selectValue}
                  aria-label="模型"
                  onChange={(e) => {
                    const v = e.target.value;
                    if (v === CUSTOM_MODEL_SENTINEL) {
                      setCustomMode(true);
                      setModel("");
                    } else {
                      setCustomMode(false);
                      setModel(v === "default" ? "" : v);
                    }
                  }}
                >
                  <option value="default">CLI 默认设置</option>
                  {(() => {
                    const { flat, groups } = splitModelOptions(models);
                    return (
                      <>
                        {flat.map((m) => (
                          <option key={m} value={m}>
                            {m}
                          </option>
                        ))}
                        {groups.map(([provider, items]) => (
                          <optgroup key={provider} label={provider}>
                            {items.map((m) => (
                              <option key={m} value={m}>
                                {m.startsWith(`${provider}/`) ? m.slice(provider.length + 1) : m}
                              </option>
                            ))}
                          </optgroup>
                        ))}
                      </>
                    );
                  })()}
                  <option value={CUSTOM_MODEL_SENTINEL}>自定义（在下方输入）…</option>
                </select>
                <CaretDown
                  size={12}
                  className="absolute right-2.5 top-1/2 -translate-y-1/2 pointer-events-none text-faint"
                />
              </div>
            )}
            <div className="mt-1.5 text-[11px] text-faint">
              {badge.live
                ? "模型列表来自这个 CLI；选「CLI 默认设置」会沿用 CLI 自己的配置。"
                : "正在显示内置默认值。点击右上角按钮可从 CLI 重新拉取实时模型。"}
            </div>

            {customActive && (
              <div className="mt-2">
                <label className="field-label">自定义模型 id</label>
                <input
                  className="text-input font-mono text-[12px]"
                  placeholder="例如：anthropic/claude-sonnet-4-5"
                  value={model}
                  autoFocus
                  onChange={(e) => setModel(e.target.value)}
                />
              </div>
            )}
          </div>

          <div>
            <div className="flex items-center gap-2 mb-1.5">
              <span className="text-[11px] font-medium uppercase tracking-[0.08em] text-faint">系统提示词（可选）</span>
              <span className="flex-1" />
              <button
                type="button"
                role="switch"
                aria-checked={injectContext}
                onClick={() => setInjectContext((v) => !v)}
                className="flex items-center gap-1.5 cursor-pointer bg-transparent border-0 p-0 group"
                title="开启后，会话启动时自动把项目信息与工单信息注入系统提示词"
              >
                <span
                  className={`relative inline-block w-8 h-[18px] rounded-full transition-colors duration-150 ${
                    injectContext ? "bg-accent" : "bg-edge-strong"
                  }`}
                >
                  <span
                    className={`absolute top-[2px] left-[2px] w-[14px] h-[14px] rounded-full bg-canvas shadow-sm transition-transform duration-150 ${
                      injectContext ? "translate-x-[14px]" : ""
                    }`}
                  />
                </span>
                <span className={`text-[11.5px] ${injectContext ? "text-accent" : "text-faint"} group-hover:text-ink transition-colors`}>
                  注入项目与工单信息
                </span>
              </button>
            </div>
            <textarea
              className="text-input h-20 py-2 resize-none"
              placeholder="为该智能体设定角色与约束…"
              value={systemPrompt}
              onChange={(e) => setSystemPrompt(e.target.value)}
            />
            {injectContext && (
              <div className="mt-1.5 text-[11px] text-faint">
                会话启动时会自动附加项目名称、工作区、工单号/标题/需求描述等上下文；关闭后仅使用上方自定义提示词。
              </div>
            )}
          </div>

          <div>
            <label className="field-label">额外启动参数（空格分隔，可选）</label>
            <input
              className="text-input font-mono text-[12px]"
              placeholder="-c model_context_limit=200000"
              value={extraFlags}
              onChange={(e) => setExtraFlags(e.target.value)}
            />
          </div>

          <div>
            <label className="field-label">职责描述（可选）</label>
            <input
              className="text-input"
              placeholder="例如：大上下文重构与批量迁移"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
            />
          </div>
        </div>

        <div className="flex justify-end gap-2 px-5 py-4 border-t border-edge">
          <button className="btn" onClick={onClose}>
            取消
          </button>
          <button className="btn btn-primary" disabled={!name.trim() || saving} onClick={save}>
            {saving ? "保存中…" : initial ? "保存修改" : "创建智能体"}
          </button>
        </div>
      </div>
    </div>
  );
}

export function AgentsPage() {
  const agents = useApp((s) => s.agents);
  const tickets = useApp((s) => s.tickets);
  const agentId = useApp((s) => s.agentId);
  const [dialog, setDialog] = useState<{ open: boolean; agent: AgentConfig | null }>({ open: false, agent: null });
  const [confirmDelete, setConfirmDelete] = useState<string | null>(null);
  const [ocModalOpen, setOcModalOpen] = useState(false);

  return (
    <div className="flex-1 min-h-0 overflow-y-auto">
      <div className="max-w-[1080px] mx-auto px-6 py-5 space-y-6">
        <section>
          <div className="flex items-center gap-3 pb-3">
            <span className="kicker">本地 CLI 检测</span>
            <span className="font-mono text-[11px] text-faint">自动探测本机可用的 Agent 运行时</span>
          </div>
          <RuntimeCards onManageProviders={() => setOcModalOpen(true)} />
        </section>

        <section>
          <div className="flex items-center gap-3 pb-3">
            <span className="kicker">智能体员工</span>
            <span className="font-mono text-[11px] text-faint">{agents.length} 个配置</span>
            <span className="flex-1" />
            <button className="btn btn-primary h-8" onClick={() => setDialog({ open: true, agent: null })}>
              <Plus size={14} weight="bold" />
              新增智能体
            </button>
          </div>

          <div className="space-y-2">
            {agents.map((a) => {
              const bound = tickets.filter((t) => t.agentConfigId === a.id).length;
              const isDefault = a.id === agentId;
              return (
                <div key={a.id} className={`card p-4 ${isDefault ? "border-accent/30" : ""}`}>
                  <div className="flex items-center gap-2.5">
                    <span className="w-7 h-7 rounded-lg bg-accent-dim grid place-items-center text-accent shrink-0">
                      <Sparkle size={14} weight="fill" />
                    </span>
                    <span className="text-[13.5px] font-semibold">{a.name}</span>
                    <span className="chip border border-edge-strong bg-raised text-dim">{CLI_LABEL[a.cli]}</span>
                    <span className="chip border border-info/25 bg-info/10 text-info font-mono">{a.model}</span>
                    {isDefault && <span className="chip border border-accent/30 bg-accent/10 text-accent">默认</span>}
                    <span className="flex-1" />
                    {confirmDelete === a.id ? (
                      <span className="flex items-center gap-1">
                        <button
                          className="chip border border-danger/40 bg-danger/10 text-danger cursor-pointer"
                          onClick={() => {
                            void actions.deleteAgentConfig(a.id);
                            setConfirmDelete(null);
                          }}
                        >
                          确认删除
                        </button>
                        <button className="chip border border-edge-strong text-dim cursor-pointer" onClick={() => setConfirmDelete(null)}>
                          返回
                        </button>
                      </span>
                    ) : (
                      <>
                        <button
                          className="icon-btn"
                          title="编辑"
                          aria-label="编辑"
                          onClick={() => setDialog({ open: true, agent: a })}
                        >
                          <PencilSimple size={13} />
                        </button>
                        <button
                          className="icon-btn hover:!text-danger"
                          title="删除"
                          aria-label="删除"
                          onClick={() => setConfirmDelete(a.id)}
                        >
                          <Trash size={13} />
                        </button>
                      </>
                    )}
                  </div>
                  {a.description && <div className="mt-1.5 text-[12.5px] text-dim">{a.description}</div>}
                  <div className="mt-2.5 flex items-center gap-3 text-[11.5px] text-faint">
                    <span>
                      绑定工单 <span className="font-mono text-dim">{bound}</span>
                    </span>
                    {a.systemPrompt && (
                      <span className="truncate max-w-[420px]" title={a.systemPrompt}>
                        提示词：{a.systemPrompt}
                      </span>
                    )}
                    {a.extraFlags.length > 0 && (
                      <span className="font-mono truncate" title={a.extraFlags.join(" ")}>
                        参数 ×{a.extraFlags.length}
                      </span>
                    )}
                    <span className="flex-1" />
                    {!isDefault && (
                      <button
                        className="text-[11.5px] text-dim hover:text-accent cursor-pointer bg-transparent border-0 p-0"
                        onClick={() => appStore.setState({ agentId: a.id })}
                      >
                        设为默认
                      </button>
                    )}
                  </div>
                </div>
              );
            })}
            {agents.length === 0 && (
              <div className="card border-dashed p-10 text-center">
                <div className="text-[13.5px] text-dim">还没有智能体配置</div>
                <div className="mt-1 text-[12px] text-faint">基于检测到的本地 CLI 创建第一个智能体员工</div>
              </div>
            )}
          </div>
        </section>
      </div>

      {dialog.open && <AgentDialog initial={dialog.agent} onClose={() => setDialog({ open: false, agent: null })} />}

      {ocModalOpen && <OpenCodeProvidersModal onClose={() => setOcModalOpen(false)} />}
    </div>
  );
}
