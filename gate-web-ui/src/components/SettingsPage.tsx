import { useCallback, useEffect, useMemo, useState } from "react";
import { AnimatePresence, motion } from "motion/react";
import {
  ArrowClockwise,
  Check,
  Copy,
  GearSix,
  Link,
  PencilSimple,
  Plus,
  Trash,
  WarningCircle,
  Wrench,
  PlugsConnected,
  Robot,
  X,
} from "@phosphor-icons/react";
import { fetchGateToml, fetchMcpStatus, fetchProviders, updateGateToml, createProvider, updateProvider, deleteProvider, updateProviderModels, fetchUpstreamModels } from "../lib/api";
import { openConnect, showToast, useApp } from "../lib/store";
import type { GateTomlResponse, GateTomlKey, McpStatus, LlmProvider } from "../lib/types";
import { CopyButton, Spinner } from "./ui";

// ──────────────────────────────────────────────────────────────────────────────
// helpers
// ──────────────────────────────────────────────────────────────────────────────

function formatDefault(v: unknown): string {
  if (v === null || v === undefined) return "无";
  if (Array.isArray(v)) return v.length ? JSON.stringify(v) : "[]";
  if (typeof v === "boolean") return v ? "true" : "false";
  return String(v);
}

function keyFullName(section: string, key: string): string {
  // 兼容旧后端：键已带分区前缀（如 engine.provider_id）时直接使用，避免拼出 engine.engine.*
  if (!section || key.startsWith(`${section}.`) || key.includes(".")) return key;
  return `${section}.${key}`;
}

function isPathLike(key: string): boolean {
  return /(path|dir|repo|file|home|root|blob|audit|locks|index|clone)/i.test(key);
}

// ──────────────────────────────────────────────────────────────────────────────
// Gate TOML block
// ──────────────────────────────────────────────────────────────────────────────

function StringListEditor({ value, onChange, disabled }: { value: string[]; onChange: (v: string[]) => void; disabled?: boolean }) {
  const [input, setInput] = useState("");
  const add = () => {
    const t = input.trim();
    if (!t) return;
    if (value.includes(t)) { setInput(""); return; }
    onChange([...value, t]);
    setInput("");
  };
  return (
    <div className={`rounded-lg border bg-sunken px-2 py-1.5 flex flex-wrap gap-1.5 items-center min-h-9 transition-colors focus-within:border-accent/40 ${disabled ? "opacity-50 border-edge bg-raised" : "border-edge"}`}>
      {value.map((tag) => (
        <span key={tag} className="inline-flex items-center gap-1 chip border border-edge-strong bg-raised text-dim text-[11.5px] pr-1">
          {tag}
          {!disabled && (
            <button className="grid place-items-center w-4 h-4 rounded-full hover:bg-edge cursor-pointer" onClick={() => onChange(value.filter((x) => x !== tag))} aria-label={`移除 ${tag}`}>
              <X size={10} />
            </button>
          )}
        </span>
      ))}
      {!disabled && (
        <input
          className="flex-1 min-w-[90px] bg-transparent outline-none text-[12.5px] placeholder:text-faint"
          placeholder="输入后回车添加"
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter") { e.preventDefault(); add(); }
            if (e.key === "Backspace" && !input && value.length) onChange(value.slice(0, -1));
          }}
        />
      )}
    </div>
  );
}

function BoolSwitch({ value, onChange, disabled }: { value: boolean; onChange: (v: boolean) => void; disabled?: boolean }) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={value}
      disabled={disabled}
      onClick={() => !disabled && onChange(!value)}
      className={`relative inline-flex w-9 h-[20px] rounded-full transition-colors duration-150 shrink-0 ${disabled ? "opacity-40 cursor-not-allowed bg-edge-strong" : value ? "bg-accent cursor-pointer" : "bg-edge-strong cursor-pointer"}`}
    >
      <span className={`absolute top-[2px] left-[2px] w-4 h-4 rounded-full bg-white shadow-sm transition-transform duration-150 ${value ? "translate-x-[16px]" : ""}`} />
    </button>
  );
}

/** 通用键值选择框：空值 = 未设置；当前文件值不在候选里时自动补一项，避免展示错位。 */
function KeySelect({
  value,
  options,
  emptyLabel,
  disabled,
  onChange,
}: {
  value: string;
  options: Array<{ value: string; label: string }>;
  emptyLabel: string;
  disabled?: boolean;
  onChange: (v: string) => void;
}) {
  const merged = value && !options.some((o) => o.value === value)
    ? [{ value, label: `${value}（当前文件值）` }, ...options]
    : options;
  return (
    <select
      className="text-input font-mono text-[12px] cursor-pointer disabled:opacity-50"
      value={value}
      disabled={disabled}
      onChange={(e) => onChange(e.target.value)}
    >
      <option value="">{emptyLabel}</option>
      {merged.map((o) => (
        <option key={o.value} value={o.value}>{o.label}</option>
      ))}
    </select>
  );
}

function GateTomlBlock() {
  const [data, setData] = useState<GateTomlResponse | null>(null);
  const [providers, setProviders] = useState<LlmProvider[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  // edits: keyFullName -> edited value; absent = untouched
  const [edits, setEdits] = useState<Record<string, unknown>>(Object.create(null));
  // which keys have been explicitly cleared to default (null)
  const [cleared, setCleared] = useState<Record<string, boolean>>(Object.create(null));
  const [saving, setSaving] = useState(false);

  const load = useCallback(async () => {
    setLoading(true); setError(null);
    try {
      const [toml, provs] = await Promise.all([
        fetchGateToml(),
        // Provider 列表加载失败不阻塞 toml 视图：引擎下拉退化为文本输入
        fetchProviders().catch(() => []),
      ]);
      setData(toml);
      setProviders(provs);
      setEdits(Object.create(null));
      setCleared(Object.create(null));
    } catch (e) { setError((e as Error).message); }
    finally { setLoading(false); }
  }, []);

  useEffect(() => { void load(); }, [load]);

  const getOriginal = useCallback((section: string, k: GateTomlKey): unknown => {
    return k.value;
  }, []);

  const getCurrent = useCallback((full: string, section: string, k: GateTomlKey): unknown => {
    if (cleared[full]) return null;
    if (full in edits) return edits[full];
    return getOriginal(section, k);
  }, [edits, cleared, getOriginal]);

  const updates = useMemo(() => {
    if (!data) return {};
    const out: Record<string, unknown> = {};
    for (const sec of data.sections) {
      for (const k of sec.keys) {
        const full = keyFullName(sec.section, k.key);
        if (!(full in edits) && !cleared[full]) continue;
        const cur = getCurrent(full, sec.section, k);
        const orig = getOriginal(sec.section, k);
        const a = JSON.stringify(cur);
        const b = JSON.stringify(orig);
        if (a !== b) out[full] = cur;
      }
    }
    return out;
  }, [data, edits, cleared, getCurrent, getOriginal]);

  // —— 审查引擎 provider/model 下拉：复用「LLM 设置」中的 Provider 与模型数据 ——

  const findKey = useCallback((full: string): { section: string; k: GateTomlKey } | null => {
    if (!data) return null;
    for (const sec of data.sections) {
      for (const k of sec.keys) {
        if (keyFullName(sec.section, k.key) === full) return { section: sec.section, k };
      }
    }
    return null;
  }, [data]);

  const engineProviderId = useMemo(() => {
    const hit = findKey("engine.provider_id");
    if (!hit) return "";
    const cur = getCurrent("engine.provider_id", hit.section, hit.k);
    return typeof cur === "string" ? cur : "";
  }, [findKey, getCurrent]);

  const engineProviderOptions = useMemo(
    () => providers.map((p) => ({ value: p.id, label: `${p.id} · ${p.name}` })),
    [providers],
  );

  const engineModelOptions = useMemo(() => {
    const p = providers.find((x) => x.id === engineProviderId);
    return (p?.models ?? []).map((m) => ({ value: m, label: m }));
  }, [providers, engineProviderId]);

  const setKeyValue = useCallback((full: string, v: string) => {
    setCleared((c) => {
      const n = { ...c };
      delete n[full];
      return n;
    });
    setEdits((m) => ({ ...m, [full]: v }));
  }, []);

  const clearKey = useCallback((full: string) => {
    setCleared((c) => ({ ...c, [full]: true }));
    setEdits((m) => {
      const n = { ...m };
      delete n[full];
      return n;
    });
  }, []);

  const hasDirty = Object.keys(updates).length > 0;

  const handleSave = async () => {
    if (!hasDirty || saving) return;
    setSaving(true);
    try {
      await updateGateToml(updates);
      showToast("已写入 gate.toml，重启后端进程后生效");
      await load();
    } catch (e) {
      showToast((e as Error).message);
    } finally { setSaving(false); }
  };

  if (loading) {
    return <div className="card p-8 flex items-center gap-2 text-[12.5px] text-faint"><Spinner /> 正在加载 gate.toml …</div>;
  }
  if (error) {
    return (
      <div className="card p-6">
        <div className="text-[13px] text-danger flex items-center gap-1.5"><WarningCircle size={14} weight="fill" /> 加载失败：{error}</div>
        <button className="btn mt-3" onClick={() => void load()}>重试</button>
      </div>
    );
  }
  if (!data) return null;

  return (
    <div className="space-y-4">
      <div className="card px-4 py-3 flex flex-wrap items-center gap-2.5">
        <span className="w-6 h-6 rounded-md bg-sunken border border-edge grid place-items-center text-faint shrink-0"><GearSix size={12} /></span>
        <code className="font-mono text-[11.5px] text-dim bg-sunken border border-edge rounded-md px-2 py-1 break-all max-w-[380px]">{data.toml_path}</code>
        <CopyButton text={data.toml_path} label="复制路径" />
        <span className="flex-1" />
        {data.restart_required && (
          <span className="chip border border-warn/30 bg-warn/10 text-warn"><ArrowClockwise size={11} /> 重启后端后生效</span>
        )}
        {!hasDirty && (
          <span className="chip border border-accent/25 bg-accent/10 text-accent"><Check size={11} weight="bold" /> 与文件一致</span>
        )}
      </div>

      {data.sections.map((sec) => (
        <div key={sec.section || "__root__"} className="card overflow-hidden">
          <div className="px-4 h-9 flex items-center gap-2 border-b border-edge bg-raised/40">
            <span className="text-[12.5px] font-semibold">{sec.title || "基本"}</span>
            {sec.section && <span className="font-mono text-[11px] text-faint">[{sec.section}]</span>}
            <span className="chip border border-edge-strong bg-sunken text-faint ml-auto">{sec.keys.length} 项</span>
          </div>
          <div className="divide-y divide-edge">
            {sec.keys.map((k) => {
              const full = keyFullName(sec.section, k.key);
              const cur = getCurrent(full, sec.section, k);
              const isUnset = k.value === null || k.value === undefined;
              const pathLike = isPathLike(k.key);
              const disabled = !k.editable || pathLike;
              const showGray = disabled;
              const useProviderSelect = k.type === "string" && full === "engine.provider_id" && providers.length > 0;
              const useModelSelect = k.type === "string" && full === "engine.model" && engineModelOptions.length > 0;
              return (
                <div key={k.key} className={`px-4 py-3 flex gap-4 items-start transition-colors ${showGray ? "bg-sunken/40" : "hover:bg-raised/25"}`}>
                  <div className="min-w-0 flex-1">
                    <div className="flex items-center gap-2 flex-wrap">
                      <span className="font-mono text-[12.5px] font-medium text-ink">{k.key}</span>
                      <span className="chip border border-edge-strong bg-raised text-faint text-[10.5px]">{k.type}</span>
                      {k.editable && !pathLike ? <span className="chip border border-accent/30 bg-accent/10 text-accent text-[10.5px]">可编辑</span> : <span className="chip border border-edge-strong bg-raised text-faint text-[10.5px]">只读</span>}
                      {isUnset && <span className="text-[11px] text-faint">未设置（默认 {formatDefault(k.default)}）</span>}
                    </div>
                    {!disabled && isUnset && cur === null && (
                      <div className="mt-1 text-[11px] text-faint">当前未写入文件，保存后将写入该键；点“恢复默认”可保持未设置</div>
                    )}
                  </div>
                  <div className={`w-[320px] shrink-0 space-y-1.5 ${showGray ? "opacity-60" : ""}`}>
                    {k.type === "int" && (
                      <input
                        type="number"
                        disabled={disabled}
                        className="text-input font-mono text-[12px] disabled:opacity-50"
                        value={cur === null || cur === undefined ? "" : String(cur as number)}
                        placeholder={isUnset ? `默认 ${formatDefault(k.default)}` : undefined}
                        onChange={(e) => {
                          const v = e.target.value;
                          if (v === "") { setCleared((c) => ({ ...c, [full]: true })); setEdits((m) => { const n = { ...m }; delete n[full]; return n; }); }
                          else { setCleared((c) => { const n = { ...c }; delete n[full]; return n; }); const num = Number(v); setEdits((m) => ({ ...m, [full]: Number.isNaN(num) ? v : num })); }
                        }}
                      />
                    )}
                    {k.type === "bool" && (
                      <div className="flex items-center gap-2 h-9">
                        <BoolSwitch value={Boolean(cur)} onChange={(v) => { setCleared((c) => { const n = { ...c }; delete n[full]; return n; }); setEdits((m) => ({ ...m, [full]: v })); }} disabled={disabled} />
                        <span className="text-[12px] text-dim">{Boolean(cur) ? "开启" : "关闭"}</span>
                        {isUnset && <span className="text-[11px] text-faint ml-1">默认 {formatDefault(k.default)}</span>}
                      </div>
                    )}
                    {useProviderSelect && (
                      <>
                        <KeySelect
                          value={typeof cur === "string" ? cur : ""}
                          options={engineProviderOptions}
                          emptyLabel={isUnset ? `未设置（默认 ${formatDefault(k.default)}）` : "未设置（清除该键）"}
                          disabled={disabled}
                          onChange={(v) => (v === "" ? clearKey(full) : setKeyValue(full, v))}
                        />
                        {!disabled && (
                          <div className="text-[11px] text-faint">选项来自「LLM 设置」中配置的 Provider；切换后请同步检查 model</div>
                        )}
                      </>
                    )}
                    {useModelSelect && (
                      <>
                        <KeySelect
                          value={typeof cur === "string" ? cur : ""}
                          options={engineModelOptions}
                          emptyLabel={isUnset ? `未设置（默认 ${formatDefault(k.default)}）` : "未设置（清除该键）"}
                          disabled={disabled}
                          onChange={(v) => (v === "" ? clearKey(full) : setKeyValue(full, v))}
                        />
                        {!disabled && (
                          <div className="text-[11px] text-faint">
                            模型列表来自所选 Provider{engineProviderId ? <>（<span className="font-mono">{engineProviderId}</span>）</> : null}；未选 Provider 时可手动输入
                          </div>
                        )}
                      </>
                    )}
                    {k.type === "string" && !useProviderSelect && !useModelSelect && (
                      <input
                        disabled={disabled}
                        className="text-input font-mono text-[12px] disabled:opacity-50"
                        value={cur === null || cur === undefined ? "" : String(cur)}
                        placeholder={isUnset ? `默认 ${formatDefault(k.default)}` : undefined}
                        onChange={(e) => {
                          const v = e.target.value;
                          if (v === "" && isUnset) { setCleared((c) => ({ ...c, [full]: true })); setEdits((m) => { const n = { ...m }; delete n[full]; return n; }); }
                          else { setCleared((c) => { const n = { ...c }; delete n[full]; return n; }); setEdits((m) => ({ ...m, [full]: v })); }
                        }}
                      />
                    )}
                    {k.type === "string_list" && (
                      <StringListEditor
                        value={Array.isArray(cur) ? cur as string[] : cur === null ? [] : []}
                        disabled={disabled}
                        onChange={(v) => { setCleared((c) => { const n = { ...c }; delete n[full]; return n; }); setEdits((m) => ({ ...m, [full]: v })); }}
                      />
                    )}
                    {k.editable && !disabled && (
                      <div className="flex justify-end">
                        <button
                          className="text-[11px] text-faint hover:text-dim cursor-pointer bg-transparent border-0 p-0"
                          onClick={() => { setCleared((c) => ({ ...c, [full]: true })); setEdits((m) => { const n = { ...m }; delete n[full]; return n; }); }}
                          title="删除该键，恢复为默认值"
                        >
                          恢复默认（删除该键）
                        </button>
                      </div>
                    )}
                    {showGray && <div className="text-[11px] text-faint">路径类配置为只读，灰显展示</div>}
                  </div>
                </div>
              );
            })}
          </div>
        </div>
      ))}

      <AnimatePresence>
        {hasDirty && (
          <motion.div
            initial={{ opacity: 0, y: 16 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: 16 }}
            transition={{ type: "spring", stiffness: 420, damping: 32 }}
            className="sticky bottom-4 z-20"
          >
            <div className="card-elevated px-4 h-12 flex items-center gap-3 shadow-lg shadow-black/40">
              <span className="w-6 h-6 rounded-md bg-warn/15 border border-warn/30 grid place-items-center text-warn shrink-0"><WarningCircle size={13} weight="fill" /></span>
              <span className="text-[12.5px] text-dim">
                有 <span className="text-ink font-semibold">{Object.keys(updates).length}</span> 项未保存的修改
              </span>
              <span className="flex-1" />
              <button className="btn btn-sm" disabled={saving} onClick={() => { setEdits(Object.create(null)); setCleared(Object.create(null)); }}>撤销修改</button>
              <button className="btn btn-primary btn-sm" disabled={saving} onClick={() => void handleSave()}>
                {saving ? <><Spinner /> 保存中…</> : `保存写入 gate.toml`}
              </button>
            </div>
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
}

// ──────────────────────────────────────────────────────────────────────────────
// MCP block
// ──────────────────────────────────────────────────────────────────────────────

function McpBlock() {
  const [data, setData] = useState<McpStatus | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true); setError(null);
    try { setData(await fetchMcpStatus()); } catch (e) { setError((e as Error).message); }
    finally { setLoading(false); }
  }, []);

  useEffect(() => { void load(); }, [load]);

  if (loading) return <div className="card p-8 flex items-center gap-2 text-[12.5px] text-faint"><Spinner /> 正在加载 MCP 状态 …</div>;
  if (error) return <div className="card p-6"><div className="text-[13px] text-danger flex items-center gap-1.5"><WarningCircle size={14} weight="fill" /> 加载失败：{error}</div><button className="btn mt-3" onClick={() => void load()}>重试</button></div>;
  if (!data) return null;

  const provisioningColor = data.provisioning === "enabled" ? "border-accent/30 bg-accent/10 text-accent" : "border-warn/30 bg-warn/10 text-warn";

  return (
    <div className="space-y-4">
      <div className="card px-4 py-3.5 flex flex-wrap items-center gap-2.5">
        <span className={`w-6 h-6 rounded-md grid place-items-center border shrink-0 ${data.provisioning === "enabled" ? "bg-accent-dim border-accent/30 text-accent" : "bg-warn-dim border-warn/30 text-warn"}`}>
          <PlugsConnected size={13} />
        </span>
        <span className="text-[13px] font-semibold">MCP 服务</span>
        <span className={`chip border ${provisioningColor}`}>{data.provisioning}</span>
        <span className="chip border border-edge-strong bg-raised text-dim font-mono text-[11px]">{data.transport}</span>
        <span className="flex-1" />
        <span className="text-[11.5px] text-faint">agent 工具 <span className="font-mono text-ink">{data.agent_tool_count}</span> · human 工具 <span className="font-mono text-ink">{data.human_tool_count}</span></span>
      </div>

      <div className="grid md:grid-cols-2 gap-4">
        <div className="card p-4">
          <div className="field-label">启动命令</div>
          <div className="flex items-center gap-1.5 mt-0.5">
            <code className="flex-1 min-w-0 font-mono text-[11.5px] leading-relaxed bg-sunken border border-edge rounded-lg px-3 py-2 break-all text-dim">{data.serve_command}</code>
            <CopyButton text={data.serve_command} label="复制启动命令" />
          </div>
        </div>
        <div className="card p-4">
          <div className="field-label">CLI 注入方式</div>
          <div className="mt-0.5 grid gap-1.5 max-h-[92px] overflow-y-auto">
            {data.cli_integration.map((it) => (
              <div key={it.cli} className="flex gap-2.5 items-start rounded-lg border border-edge bg-sunken px-2.5 py-2">
                <span className="chip border border-accent/30 bg-accent/10 text-accent shrink-0">{it.cli}</span>
                <span className="text-[12px] leading-relaxed text-dim min-w-0">{it.mechanism}</span>
              </div>
            ))}
            {data.cli_integration.length === 0 && <div className="text-[12.5px] text-faint">暂无 CLI 集成说明</div>}
          </div>
        </div>
      </div>

      <div className="card overflow-hidden">
        <div className="px-4 h-9 flex items-center gap-2 border-b border-edge bg-raised/40">
          <span className="text-[12.5px] font-semibold">工具清单</span>
          <span className="chip border border-edge-strong bg-sunken text-faint">{data.tools.length} 个</span>
        </div>
        {data.tools.length === 0 ? (
          <div className="p-8 text-center text-[12.5px] text-faint">暂无工具</div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-left">
              <thead>
                <tr className="border-b border-edge bg-sunken/50">
                  <th className="px-4 py-2 text-[11px] font-semibold text-faint uppercase tracking-wider">名称</th>
                  <th className="px-4 py-2 text-[11px] font-semibold text-faint uppercase tracking-wider">域</th>
                  <th className="px-4 py-2 text-[11px] font-semibold text-faint uppercase tracking-wider">描述</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-edge">
                {data.tools.map((t) => (
                  <tr key={t.name} className="hover:bg-raised/40 transition-colors">
                    <td className="px-4 py-2 font-mono text-[12.5px] text-ink whitespace-nowrap">{t.name}</td>
                    <td className="px-4 py-2">
                      <span className={`chip border text-[10.5px] ${t.domain === "agent" ? "border-accent/30 bg-accent/10 text-accent" : "border-info/30 bg-info/10 text-info"}`}>{t.domain.toUpperCase()}</span>
                    </td>
                    <td className="px-4 py-2 text-[12.5px] text-dim max-w-[420px] break-words">{t.description || "—"}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  );
}

// ──────────────────────────────────────────────────────────────────────────────
// LLM block
// ──────────────────────────────────────────────────────────────────────────────

function ProviderDialog({ initial, onClose, onSaved }: { initial: LlmProvider | null; onClose: () => void; onSaved: () => void }) {
  const isNew = !initial;
  const [id, setId] = useState(initial?.id ?? "");
  const [name, setName] = useState(initial?.name ?? "");
  const [baseUrl, setBaseUrl] = useState(initial?.base_url ?? "");
  const [type, setType] = useState(initial?.type ?? "openai");
  const [apiKeyRef, setApiKeyRef] = useState("");
  const [saving, setSaving] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  const valid = id.trim() && name.trim() && baseUrl.trim() && type.trim();

  const save = async () => {
    if (!valid || saving) return;
    setSaving(true); setErr(null);
    try {
      const body: { id?: string; name: string; base_url: string; type: string; api_key_ref?: string } = {
        name: name.trim(), base_url: baseUrl.trim(), type: type.trim(),
      };
      if (apiKeyRef.trim()) body.api_key_ref = apiKeyRef.trim();
      if (isNew) {
        body.id = id.trim();
        await createProvider(body as { id: string; name: string; base_url: string; type: string; api_key_ref?: string });
      } else {
        await updateProvider(initial!.id, body as { name: string; base_url: string; type: string; api_key_ref?: string });
      }
      showToast(isNew ? "Provider 已创建" : "Provider 已更新");
      onSaved();
      onClose();
    } catch (e) { setErr((e as Error).message); }
    finally { setSaving(false); }
  };

  return (
    <div className="fixed inset-0 z-50 grid place-items-center bg-black/55 backdrop-blur-[2px]" onClick={onClose}>
      <div className="w-[480px] card shadow-2xl shadow-black/60 animate-rise" onClick={(e) => e.stopPropagation()}>
        <div className="flex items-center gap-2 px-5 h-12 border-b border-edge">
          <Robot size={15} className="text-accent" />
          <span className="text-[13.5px] font-semibold">{isNew ? "新建 Provider" : "编辑 Provider"}</span>
          <span className="flex-1" />
          <button className="icon-btn" onClick={onClose} aria-label="关闭"><X size={15} /></button>
        </div>
        <div className="p-5 space-y-4">
          {isNew && (
            <div>
              <label className="field-label">ID *</label>
              <input className="text-input font-mono text-[12px]" placeholder="例如：openai-main" value={id} onChange={(e) => setId(e.target.value)} />
            </div>
          )}
          {!isNew && <div className="text-[11.5px] text-faint">ID：<span className="font-mono text-ink">{initial!.id}</span>（不可修改）</div>}
          <div>
            <label className="field-label">名称 *</label>
            <input className="text-input" placeholder="例如：OpenAI 主用" value={name} onChange={(e) => setName(e.target.value)} />
          </div>
          <div>
            <label className="field-label">Base URL *</label>
            <input className="text-input font-mono text-[12px]" placeholder="https://api.openai.com/v1" value={baseUrl} onChange={(e) => setBaseUrl(e.target.value)} />
          </div>
          <div>
            <label className="field-label">Type *</label>
            <input className="text-input font-mono text-[12px]" placeholder="openai / anthropic / custom" value={type} onChange={(e) => setType(e.target.value)} />
          </div>
          <div>
            <label className="field-label">api_key_ref（可选）</label>
            <input className="text-input font-mono text-[12px]" placeholder="env:OPENAI_API_KEY" value={apiKeyRef} onChange={(e) => setApiKeyRef(e.target.value)} />
            <div className="mt-1.5 text-[11px] text-faint leading-relaxed">填 env:VAR_NAME 引用 .env 中的密钥，明文不入库</div>
          </div>
          {err && <div className="text-[12.5px] text-danger">{err}</div>}
        </div>
        <div className="flex justify-end gap-2 px-5 py-4 border-t border-edge">
          <button className="btn" onClick={onClose}>取消</button>
          <button className="btn btn-primary" disabled={!valid || saving} onClick={() => void save()}>{saving ? "保存中…" : isNew ? "创建" : "保存修改"}</button>
        </div>
      </div>
    </div>
  );
}

function ModelEditor({ provider, onUpdated }: { provider: LlmProvider; onUpdated: () => void }) {
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState(provider.models.join(", "));
  const [saving, setSaving] = useState(false);
  const [fetching, setFetching] = useState(false);
  const [msg, setMsg] = useState<{ ok: boolean; text: string } | null>(null);

  useEffect(() => { if (!editing) setDraft(provider.models.join(", ")); }, [provider.models, editing]);

  const saveModels = async () => {
    const models = draft.split(/[,，\n]/).map((s) => s.trim()).filter(Boolean);
    setSaving(true); setMsg(null);
    try {
      await updateProviderModels(provider.id, models);
      showToast("模型列表已更新");
      setEditing(false);
      onUpdated();
    } catch (e) { setMsg({ ok: false, text: (e as Error).message }); }
    finally { setSaving(false); }
  };

  const doFetch = async () => {
    setFetching(true); setMsg(null);
    try {
      await fetchUpstreamModels(provider.id);
      showToast("已从上游拉取并更新模型列表");
      onUpdated();
    } catch (e) { setMsg({ ok: false, text: (e as Error).message }); }
    finally { setFetching(false); }
  };

  return (
    <div className="mt-3.5 pt-3.5 border-t border-edge">
      <div className="flex items-center gap-2 mb-2">
        <span className="field-label !mb-0">模型</span>
        <span className={`chip border text-[10.5px] ${provider.models.length ? "border-info/25 bg-info/10 text-info" : "border-edge-strong bg-raised text-faint"}`}>{provider.models.length} 个</span>
        <span className="flex-1" />
        {!editing ? (
          <button className="btn btn-sm" onClick={() => setEditing(true)}><PencilSimple size={12} /> 手动编辑</button>
        ) : (
          <span className="flex items-center gap-1">
            <button className="btn btn-sm" onClick={() => setEditing(false)}>取消</button>
            <button className="btn btn-primary btn-sm" disabled={saving} onClick={() => void saveModels()}>{saving ? "保存中…" : "保存"}</button>
          </span>
        )}
        <button className="btn btn-sm" disabled={fetching} onClick={() => void doFetch()} title="从 Provider 接口拉取可用模型列表">
          {fetching ? <><Spinner /> 拉取中…</> : <><ArrowClockwise size={12} /> 拉取上游模型</>}
        </button>
      </div>

      <AnimatePresence mode="wait" initial={false}>
        {!editing ? (
          <motion.div
            key="view"
            initial={{ opacity: 0, y: 4 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: -4 }}
            transition={{ duration: 0.15 }}
            className="flex flex-wrap gap-1.5"
          >
            {provider.models.length ? provider.models.map((m) => (
              <span key={m} className="chip border border-edge-strong bg-raised text-dim font-mono text-[11px]">{m}</span>
            )) : (
              <span className="w-full flex items-center gap-2 rounded-lg border border-dashed border-edge-strong bg-sunken/40 px-3 py-2.5 text-[12px] text-faint">
                <Robot size={13} className="shrink-0" />
                暂无模型 — 点击右上角「拉取上游模型」自动获取，或「手动编辑」填写
              </span>
            )}
          </motion.div>
        ) : (
          <motion.div
            key="edit"
            initial={{ opacity: 0, y: 4 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: -4 }}
            transition={{ duration: 0.15 }}
          >
            <textarea className="textarea font-mono text-[12px]" rows={3} placeholder="逗号分隔，例如：gpt-4o, claude-3-5-sonnet" value={draft} onChange={(e) => setDraft(e.target.value)} autoFocus />
            <div className="mt-1 text-[11px] text-faint">用逗号或换行分隔，保存后整体替换</div>
          </motion.div>
        )}
      </AnimatePresence>
      {msg && <div className={`mt-2 text-[12px] ${msg.ok ? "text-accent" : "text-danger"}`}>{msg.text}</div>}
    </div>
  );
}

function LlmBlock() {
  const [providers, setProviders] = useState<LlmProvider[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [dialog, setDialog] = useState<LlmProvider | null | "new">(null);
  const [deleteConfirm, setDeleteConfirm] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true); setError(null);
    try { setProviders(await fetchProviders()); } catch (e) { setError((e as Error).message); }
    finally { setLoading(false); }
  }, []);

  useEffect(() => { void load(); }, [load]);

  const handleDelete = async (id: string) => {
    try {
      await deleteProvider(id);
      showToast("Provider 已删除");
      await load();
    } catch (e) { showToast((e as Error).message); }
    finally { setDeleteConfirm(null); }
  };

  if (loading) return <div className="card p-8 flex items-center gap-2 text-[12.5px] text-faint"><Spinner /> 正在加载 Providers …</div>;
  if (error) return <div className="card p-6"><div className="text-[13px] text-danger flex items-center gap-1.5"><WarningCircle size={14} weight="fill" /> 加载失败：{error}</div><button className="btn mt-3" onClick={() => void load()}>重试</button></div>;

  return (
    <div className="space-y-4">
      <div className="flex items-center gap-2.5">
        <span className="kicker !mb-0">Providers</span>
        <span className="chip border border-edge-strong bg-raised text-faint">{providers.length} 个</span>
        <span className="flex-1" />
        <button className="btn btn-primary btn-sm" onClick={() => setDialog("new")}><Plus size={12} weight="bold" /> 新建 Provider</button>
      </div>

      {providers.length === 0 ? (
        <div className="card border-dashed p-10 text-center">
          <div className="w-11 h-11 rounded-xl border border-dashed border-edge-strong grid place-items-center mx-auto">
            <Robot size={20} className="text-faint" />
          </div>
          <div className="mt-3 text-[13.5px] text-dim">暂无 Provider</div>
          <div className="mt-1 text-[12px] text-faint">新建一个 Provider 后即可管理接口与模型</div>
          <button className="btn btn-primary btn-sm mt-4" onClick={() => setDialog("new")}><Plus size={12} weight="bold" /> 新建 Provider</button>
        </div>
      ) : (
        <div className="space-y-3">
          {providers.map((p) => (
            <motion.div
              key={p.id}
              layout
              initial={{ opacity: 0, y: 8 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ type: "spring", stiffness: 380, damping: 32 }}
              className="card p-4 transition-colors hover:border-edge-strong"
            >
              <div className="flex items-start gap-3">
                <span className="w-8 h-8 rounded-lg bg-accent-dim border border-accent/25 grid place-items-center text-accent shrink-0"><PlugsConnected size={15} /></span>
                <div className="min-w-0 flex-1">
                  <div className="flex items-center gap-2 flex-wrap">
                    <span className="text-[13.5px] font-semibold">{p.name}</span>
                    <span className="font-mono text-[11px] text-faint">{p.id}</span>
                    {p.credential_configured ? <span className="chip border border-accent/30 bg-accent/10 text-accent text-[10.5px]"><Check size={11} weight="bold" /> 已配置密钥</span> : <span className="chip border border-warn/30 bg-warn/10 text-warn text-[10.5px]">未配置密钥</span>}
                    <span className="chip border border-edge-strong bg-raised text-dim text-[10.5px]">{p.type}</span>
                    <span className="chip border border-info/25 bg-info/10 text-info font-mono text-[10.5px]">{p.model_count} 模型</span>
                  </div>
                  <div className="mt-1 flex items-center gap-1.5 font-mono text-[11.5px] text-faint break-all">
                    <Link size={12} className="shrink-0" />{p.base_url}
                    <CopyButton text={p.base_url} label="复制 Base URL" />
                  </div>
                </div>
                <div className="flex items-center gap-1 shrink-0">
                  <button className="icon-btn" title="编辑" onClick={() => setDialog(p)}><PencilSimple size={13} /></button>
                  {deleteConfirm === p.id ? (
                    <span className="flex items-center gap-1">
                      <button className="chip border border-danger/40 bg-danger/10 text-danger cursor-pointer" onClick={() => void handleDelete(p.id)}>确认删除</button>
                      <button className="chip border border-edge-strong text-dim cursor-pointer" onClick={() => setDeleteConfirm(null)}>返回</button>
                    </span>
                  ) : (
                    <button className="icon-btn hover:!text-danger" title="删除" onClick={() => setDeleteConfirm(p.id)}><Trash size={13} /></button>
                  )}
                </div>
              </div>
              <ModelEditor provider={p} onUpdated={() => void load()} />
            </motion.div>
          ))}
        </div>
      )}

      {dialog !== null && (
        <ProviderDialog initial={dialog === "new" ? null : dialog} onClose={() => setDialog(null)} onSaved={() => void load()} />
      )}
    </div>
  );
}

// ──────────────────────────────────────────────────────────────────────────────
// Page
// ──────────────────────────────────────────────────────────────────────────────

type SettingsTab = "toml" | "mcp" | "llm";

const NAV_ITEMS = [
  { key: "toml", label: "gate.toml 参数", desc: "运行键值与默认值", Icon: Wrench },
  { key: "mcp", label: "MCP 状态", desc: "服务与工具清单", Icon: PlugsConnected },
  { key: "llm", label: "LLM 设置", desc: "Provider 与模型", Icon: Robot },
] as const;

function SettingsNav({ tab, onChange }: { tab: SettingsTab; onChange: (t: SettingsTab) => void }) {
  return (
    <nav
      className="flex md:flex-col gap-1 overflow-x-auto md:overflow-visible md:sticky md:top-5 md:self-start -mx-1 px-1 py-1 md:py-0"
      aria-label="设置分区"
    >
      {NAV_ITEMS.map(({ key, label, desc, Icon }) => {
        const active = tab === key;
        return (
          <button
            key={key}
            onClick={() => onChange(key)}
            aria-current={active ? "page" : undefined}
            className={`relative shrink-0 flex md:items-center items-center gap-2.5 h-10 md:h-auto md:py-2 px-2.5 rounded-lg text-left cursor-pointer transition-colors md:w-full ${
              active ? "text-ink" : "text-dim hover:text-ink hover:bg-raised/40"
            }`}
          >
            {active && (
              <motion.div
                layoutId="settings-nav-active"
                className="absolute inset-0 rounded-lg bg-raised"
                transition={{ type: "spring", stiffness: 500, damping: 35 }}
              />
            )}
            <span
              className={`relative z-10 grid place-items-center shrink-0 transition-colors ${
                active ? "text-accent" : "text-faint"
              }`}
            >
              <Icon size={15} weight={active ? "fill" : "regular"} />
            </span>
            <span className="relative z-10 min-w-0">
              <span className="block text-[12.5px] font-medium leading-tight whitespace-nowrap">{label}</span>
              <span className="hidden md:block text-[10.5px] text-faint leading-tight mt-px whitespace-nowrap">{desc}</span>
            </span>
          </button>
        );
      })}
    </nav>
  );
}

export function SettingsPage() {
  const mode = useApp((s) => s.mode);
  const [tab, setTab] = useState<SettingsTab>("toml");

  if (mode === "demo") {
    return (
      <div className="flex-1 min-h-0 overflow-y-auto">
        <div className="max-w-[880px] mx-auto px-6 py-10">
          <div className="card p-10 text-center">
            <div className="w-12 h-12 rounded-xl bg-raised border border-edge grid place-items-center mx-auto">
              <GearSix size={22} className="text-faint" />
            </div>
            <div className="mt-4 text-[15px] font-semibold">设置中心需要连接后端</div>
            <div className="mt-1.5 text-[12.5px] text-faint leading-relaxed">当前为演示模式，gate.toml / MCP / LLM 设置仅在连接后端后可用</div>
            <button className="btn btn-primary mt-5" onClick={openConnect}>连接后端</button>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="flex-1 min-h-0 overflow-y-auto">
      <div className="max-w-[1080px] mx-auto px-6 py-5">
        <div className="md:grid md:grid-cols-[196px_minmax(0,1fr)] md:gap-5">
          <SettingsNav tab={tab} onChange={setTab} />
          <div className="min-w-0 mt-4 md:mt-0">
            <AnimatePresence mode="wait" initial={false}>
              <motion.div
                key={tab}
                initial={{ opacity: 0, y: 8 }}
                animate={{ opacity: 1, y: 0 }}
                exit={{ opacity: 0, y: -6 }}
                transition={{ duration: 0.18, ease: [0.16, 1, 0.3, 1] }}
              >
                {tab === "toml" && <GateTomlBlock />}
                {tab === "mcp" && <McpBlock />}
                {tab === "llm" && <LlmBlock />}
              </motion.div>
            </AnimatePresence>
          </div>
        </div>
      </div>
    </div>
  );
}
