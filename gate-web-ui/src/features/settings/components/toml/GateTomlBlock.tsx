import { useCallback, useEffect, useMemo, useState } from "react";
import { AnimatePresence, motion } from "motion/react";
import { ArrowClockwise, Check, GearSix, WarningCircle } from "@phosphor-icons/react";
import { fetchGateToml, fetchProviders, updateGateToml } from "@/features/settings";
import { useT } from "@/i18n";
import { showToast } from "@/store";
import type { GateTomlResponse, GateTomlKey, GateTomlOption, LlmProvider } from "@/shared/types";
import { CopyButton, Spinner } from "@/shared/components/ui";
import { StringListEditor, BoolSwitch, KeySelect } from "./controls";

// 选项依赖运行期 LLM Provider 列表、后端无法静态下发的键：
// provider 类直接列 Providers；model 类的选项跟随各自的 provider 键当前值。
const PROVIDER_SELECT_KEYS = new Set(["engine.provider_id", "agent.default_provider"]);
const MODEL_SELECT_KEYS = new Set(["engine.model", "agent.default_model"]);
const MODEL_SOURCE: Record<string, string> = {
  "engine.model": "engine.provider_id",
  "agent.default_model": "agent.default_provider",
};

function formatDefault(v: unknown, noneLabel: string): string {
  if (v === null || v === undefined) return noneLabel;
  if (Array.isArray(v)) return v.length ? JSON.stringify(v) : "[]";
  if (typeof v === "boolean") return v ? "true" : "false";
  return String(v);
}

function keyFullName(section: string, key: string): string {
  // 兼容旧后端：键已带分区前缀（如 engine.provider_id）时直接使用，避免拼出 engine.engine.*
  if (!section || key.startsWith(`${section}.`) || key.includes(".")) return key;
  return `${section}.${key}`;
}

/**
 * 未设置键的输入框 placeholder：优先后端下发的 placeholder（运行期行为如实描述，
 * 如提交身份「默认：本机 git 作者」）；否则回退静态默认值。
 */
function inputPlaceholder(k: GateTomlKey, unsetLabel: string, defaultPrefix: string): string {
  if (k.placeholder) return k.placeholder;
  if (k.default === null || k.default === undefined) return unsetLabel;
  return `${defaultPrefix} ${formatDefault(k.default, "")}`.trim();
}

/** 数值约束的展示文案；两侧都未约束时返回 null。 */
function rangeLabel(min?: number, max?: number): string | null {
  if (min === undefined && max === undefined) return null;
  if (min !== undefined && max !== undefined) return `${min} – ${max}`;
  if (min !== undefined) return `≥ ${min}`;
  return `≤ ${max}`;
}

/** 系统设置（gate.toml）区块：分区键值编辑、跨键校验与未保存变更条。 */
export function GateTomlBlock() {
  const t = useT();
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

  // —— provider/model 下拉：复用「LLM 设置」中的 Provider 与模型数据，engine 与 agent 键共用 ——

  const findKey = useCallback((full: string): { section: string; k: GateTomlKey } | null => {
    if (!data) return null;
    for (const sec of data.sections) {
      for (const k of sec.keys) {
        if (keyFullName(sec.section, k.key) === full) return { section: sec.section, k };
      }
    }
    return null;
  }, [data]);

  const providerOptions = useMemo(
    () => providers.map((p) => ({ value: p.id, label: `${p.id} · ${p.name}` })),
    [providers],
  );

  /** model 选项跟随的 provider 键当前值（如 engine.model ← engine.provider_id）。 */
  const getModelSourceProvider = useCallback((full: string): string => {
    const src = MODEL_SOURCE[full];
    if (!src) return "";
    const hit = findKey(src);
    if (!hit) return "";
    const cur = getCurrent(src, hit.section, hit.k);
    return typeof cur === "string" ? cur : "";
  }, [findKey, getCurrent]);

  const modelOptionsOf = useCallback((providerId: string): GateTomlOption[] => {
    const p = providers.find((x) => x.id === providerId);
    return (p?.models ?? []).map((m) => ({ value: m, label: m }));
  }, [providers]);

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

  // —— 跨键校验：各键当前值快照，供联动约束（如端口下界 ≤ 上界）提示 ——
  const curNumbers = useMemo(() => {
    const m: Record<string, number | null> = {};
    if (!data) return m;
    for (const sec of data.sections) {
      for (const k of sec.keys) {
        const full = keyFullName(sec.section, k.key);
        const cur = getCurrent(full, sec.section, k);
        m[full] = typeof cur === "number" ? cur : null;
      }
    }
    return m;
  }, [data, getCurrent]);

  const crossKeyWarning = useCallback((full: string): string | null => {
    if (full === "session.port_range_min" || full === "session.port_range_max") {
      const lo = curNumbers["session.port_range_min"];
      const hi = curNumbers["session.port_range_max"];
      if (lo !== null && hi !== null && lo > hi) {
        return t("toml.portRangeWarning", { lo: lo, hi: hi });
      }
    }
    return null;
  }, [curNumbers]);

  const handleSave = async () => {
    if (!hasDirty || saving) return;
    setSaving(true);
    try {
      await updateGateToml(updates);
      showToast(t("toml.savedToast"));
      await load();
    } catch (e) {
      showToast((e as Error).message);
    } finally { setSaving(false); }
  };

  if (loading) {
    return <div className="card p-8 flex items-center gap-2 text-[12.5px] text-faint"><Spinner /> {t("toml.loading")}</div>;
  }
  if (error) {
    return (
      <div className="card p-6">
        <div className="text-[13px] text-danger flex items-center gap-1.5"><WarningCircle size={14} weight="fill" /> {t("mcp.loadFailed")}：{error}</div>
        <button className="btn mt-3" onClick={() => void load()}>{t("common.retry")}</button>
      </div>
    );
  }
  if (!data) return null;

  return (
    <div className="space-y-4">
      <div className="card px-4 py-3 flex flex-wrap items-center gap-2.5">
        <span className="w-6 h-6 rounded-md bg-sunken border border-edge grid place-items-center text-faint shrink-0"><GearSix size={12} /></span>
        <code className="font-mono text-[11.5px] text-dim bg-sunken border border-edge rounded-md px-2 py-1 break-all max-w-[380px]">{data.toml_path}</code>
        <CopyButton text={data.toml_path} label={t("toml.copyPath")} />
        <span className="flex-1" />
        {data.restart_required && (
          <span className="chip border border-warn/30 bg-warn/10 text-warn"><ArrowClockwise size={11} /> {t("toml.restartRequired")}</span>
        )}
        {!hasDirty && (
          <span className="chip border border-accent/25 bg-accent/10 text-accent"><Check size={11} weight="bold" /> {t("toml.inSync")}</span>
        )}
      </div>

      {data.sections.map((sec) => {
        // 只读键不放出来（后端已过滤，前端兜底再滤一次）；空分区不渲染
        const keys = sec.keys.filter((k) => k.editable);
        if (keys.length === 0) return null;
        return (
        <div key={sec.section || "__root__"} className="card overflow-hidden">
          <div className="px-4 h-9 flex items-center gap-2 border-b border-edge bg-raised/40">
            <span className="text-[12.5px] font-semibold">{sec.title || t("toml.advanced")}</span>
            {sec.section && <span className="font-mono text-[11px] text-faint">[{sec.section}]</span>}
            <span className="chip border border-edge-strong bg-sunken text-faint ml-auto">{t("mcp.toolCount", { n: keys.length })}</span>
          </div>
          <div className="divide-y divide-edge">
            {keys.map((k) => {
              const full = keyFullName(sec.section, k.key);
              const cur = getCurrent(full, sec.section, k);
              const isUnset = k.value === null || k.value === undefined;
              // 只读键已被过滤，此处的键全部可编辑
              const disabled = false;
              const isProviderKey = k.type === "string" && PROVIDER_SELECT_KEYS.has(full);
              const isModelKey = k.type === "string" && MODEL_SELECT_KEYS.has(full);
              const modelSource = isModelKey ? getModelSourceProvider(full) : "";
              // 动态选项可用则优先；否则回退后端下发的静态枚举；都没有 → 自由输入
              const dynamicOptions = isProviderKey ? providerOptions : isModelKey ? modelOptionsOf(modelSource) : null;
              const selectOptions = dynamicOptions && dynamicOptions.length > 0 ? dynamicOptions : k.options;
              const useSelect = k.type === "string" && !disabled && selectOptions != null && selectOptions.length > 0;
              const range = k.type === "int" ? rangeLabel(k.min, k.max) : null;
              const curNum = k.type === "int" && typeof cur === "number" ? cur : null;
              const outOfRange = curNum !== null && ((k.min !== undefined && curNum < k.min) || (k.max !== undefined && curNum > k.max));
              const crossWarn = crossKeyWarning(full);
              const isRefList = k.type === "string_list" && full === "target_ref_whitelist";
              const isNonEmptyList = k.type === "string_list" && (full === "target_ref_whitelist" || full === "web.allowed_origins");
              const listEmpty = isNonEmptyList && !disabled && !isUnset && Array.isArray(cur) && (cur as string[]).length === 0;
              return (
                <div key={k.key} className={`px-4 py-3 flex gap-4 items-start transition-colors hover:bg-raised/25`}>
                  <div className="min-w-0 flex-1">
                    <div className="flex items-center gap-2 flex-wrap">
                      <span className="font-mono text-[12.5px] font-medium text-ink">{k.key}</span>
                      <span className="chip border border-edge-strong bg-raised text-faint text-[10.5px]">{k.type}</span>
                      {isUnset && (
                        <span className="text-[11px] text-faint">
                          {t("toml.unset")}{k.default != null ? t("toml.defaultParens", { v: formatDefault(k.default, t("common.none")) }) : ""}
                        </span>
                      )}
                    </div>
                    {k.hint && <div className="mt-1 text-[11px] text-faint leading-relaxed max-w-[560px]">{k.hint}</div>}
                    {!disabled && isUnset && cur === null && (
                      <div className="mt-1 text-[11px] text-faint">{t("toml.unwrittenNote")}</div>
                    )}
                  </div>
                  <div className="w-[320px] shrink-0 space-y-1.5">
                    {useSelect && (
                      <>
                        <KeySelect
                          value={typeof cur === "string" ? cur : ""}
                          options={selectOptions}
                          emptyLabel={
                            isUnset
                              ? k.default != null
                                ? t("toml.unsetDefault", { v: formatDefault(k.default, t("common.none")) })
                                : t("toml.unset")
                              : t("toml.unsetClear")
                          }
                          disabled={disabled}
                          onChange={(v) => (v === "" ? clearKey(full) : setKeyValue(full, v))}
                        />
                        {!disabled && (isProviderKey || isModelKey) && (
                          <div className="text-[11px] text-faint">
                            {isProviderKey
                              ? t("toml.providerOptionsNote")
                              : t("toml.modelOptionsNote", { src: modelSource })}
                          </div>
                        )}
                      </>
                    )}
                    {!useSelect && k.type === "int" && (
                      <>
                        <input
                          type="number"
                          min={k.min}
                          max={k.max}
                          disabled={disabled}
                          className="text-input font-mono text-[12px] disabled:opacity-50"
                          value={cur === null || cur === undefined ? "" : String(cur as number)}
                          placeholder={isUnset ? inputPlaceholder(k, t("toml.unset"), t("common.default")) : undefined}
                          onChange={(e) => {
                            const v = e.target.value;
                            if (v === "") { setCleared((c) => ({ ...c, [full]: true })); setEdits((m) => { const n = { ...m }; delete n[full]; return n; }); }
                            else { setCleared((c) => { const n = { ...c }; delete n[full]; return n; }); const num = Number(v); setEdits((m) => ({ ...m, [full]: Number.isNaN(num) ? v : num })); }
                          }}
                        />
                        {!disabled && range && !outOfRange && <div className="text-[11px] text-faint">{t("toml.allowedRange")}：{range}</div>}
                        {!disabled && outOfRange && <div className="text-[11px] text-danger">{t("toml.outOfRange")}：{range}</div>}
                      </>
                    )}
                    {!useSelect && k.type === "bool" && (
                      <div className="flex items-center gap-2 h-9">
                        <BoolSwitch value={Boolean(cur)} onChange={(v) => { setCleared((c) => { const n = { ...c }; delete n[full]; return n; }); setEdits((m) => ({ ...m, [full]: v })); }} disabled={disabled} />
                        <span className="text-[12px] text-dim">{Boolean(cur) ? t("common.enabled") : t("common.disabled")}</span>
                        {isUnset && k.default != null && <span className="text-[11px] text-faint ml-1">{t("toml.defaultPrefix", { v: formatDefault(k.default, t("common.none")) })}</span>}
                      </div>
                    )}
                    {!useSelect && k.type === "string" && (
                      <input
                        disabled={disabled}
                        className="text-input font-mono text-[12px] disabled:opacity-50"
                        value={cur === null || cur === undefined ? "" : String(cur)}
                        placeholder={isUnset ? inputPlaceholder(k, t("toml.unset"), t("common.default")) : undefined}
                        onChange={(e) => {
                          const v = e.target.value;
                          if (v === "" && isUnset) { setCleared((c) => ({ ...c, [full]: true })); setEdits((m) => { const n = { ...m }; delete n[full]; return n; }); }
                          else { setCleared((c) => { const n = { ...c }; delete n[full]; return n; }); setEdits((m) => ({ ...m, [full]: v })); }
                        }}
                      />
                    )}
                    {k.type === "string_list" && (
                      <>
                        <StringListEditor
                          value={Array.isArray(cur) ? cur as string[] : []}
                          disabled={disabled}
                          itemPattern={isRefList ? /^refs\/heads\/\S+$/ : undefined}
                          patternHint={isRefList ? t("toml.refPatternHint") : undefined}
                          placeholder={isUnset && !disabled && k.default != null ? t("toml.defaultColon", { v: formatDefault(k.default, t("common.none")) }) : undefined}
                          onChange={(v) => { setCleared((c) => { const n = { ...c }; delete n[full]; return n; }); setEdits((m) => ({ ...m, [full]: v })); }}
                        />
                        {listEmpty && <div className="text-[11px] text-danger">{t("toml.listEmptyWarning")}</div>}
                      </>
                    )}
                    {k.editable && !disabled && (
                      <div className="flex justify-end">
                        <button
                          className="text-[11px] text-faint hover:text-dim cursor-pointer bg-transparent border-0 p-0"
                          onClick={() => { setCleared((c) => ({ ...c, [full]: true })); setEdits((m) => { const n = { ...m }; delete n[full]; return n; }); }}
                          title={t("toml.restoreDefaultTip")}
                        >
                          {t("toml.restoreDefault")}
                        </button>
                      </div>
                    )}
                    {!disabled && crossWarn && <div className="text-[11px] text-danger">{crossWarn}</div>}
                  </div>
                </div>
              );
            })}
          </div>
        </div>
        );
      })}

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
                {t("toml.dirtyCount", { n: Object.keys(updates).length })}
              </span>
              <span className="flex-1" />
              <button className="btn btn-sm" disabled={saving} onClick={() => { setEdits(Object.create(null)); setCleared(Object.create(null)); }}>{t("toml.discardChanges")}</button>
              <button className="btn btn-primary btn-sm" disabled={saving} onClick={() => void handleSave()}>
                {saving ? <><Spinner /> {t("llm.saving")}</> : t("toml.saveWrite")}
              </button>
            </div>
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
}
