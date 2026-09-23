import { useEffect, useRef, useState } from "react";
import { PencilSimple, Plug, Sparkle, Trash, X } from "@phosphor-icons/react";
import { actions } from "@/app/actions";
import { useApp } from "@/store";
import { fetchOcModelsLive, matchOcModelsLive, testOcModelLive } from "@/features/agent";
import type { OpenCodeModelEntry, OpenCodeProvider } from "@/shared/types";
import { NPM_PRESETS, fillGaps, modelTags, normalizeModelEntries, type ProbeState } from "./presets";
import { ModelEditor } from "./ModelEditor";
import { useT } from "@/i18n";

/** 智能匹配的展示位（与上游探测的 ProbeState 分开：两者可能同时在跑）。 */
interface MatchState {
  kind: "idle" | "matching" | "done" | "error";
  ok?: boolean;
  text?: string;
}

/** 自动匹配的合并窗口：连续勾选（含「全选」）压成一次请求。 */
const AUTO_MATCH_DEBOUNCE_MS = 350;

/**
 * 供应商编辑面板：由 OpenCodeProvidersModal 以右侧拼接面板承载（motion 动效在父级）。
 *
 * <p>新增或勾选模型时会自动去线上目录（models.dev）匹配最新配置项并回填；已手改过的字段不被覆盖
 * （见 {@link fillGaps}）。匹配到的配置同时驱动模型行上的小标签（1M / 视觉）。
 */
export function OcProviderPanel({
  initial,
  onClose,
}: {
  initial: OpenCodeProvider | null;
  onClose: () => void;
}) {
  const t = useT();
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
  const [match, setMatch] = useState<MatchState>({ kind: "idle" });
  /** 匹配命中来源（本地 id → 目录里的 id/供应商），只作展示。 */
  const [matchedFrom, setMatchedFrom] = useState<Record<string, { provider: string; matchedId: string }>>({});
  const [saving, setSaving] = useState(false);

  /** 待匹配的 id 与合并窗口定时器（跨渲染保留，不能用 state）。 */
  const pendingRef = useRef<string[]>([]);
  const timerRef = useRef<number | null>(null);
  /** 组件卸载后到达的响应不再 setState。 */
  const aliveRef = useRef(true);
  useEffect(() => {
    aliveRef.current = true;
    return () => {
      aliveRef.current = false;
      if (timerRef.current != null) window.clearTimeout(timerRef.current);
    };
  }, []);

  const keyValid = /^[A-Za-z0-9._\-/]+$/.test(key.trim());
  const canSave = keyValid && name.trim().length > 0 && !saving;
  const canProbe = mode === "live" && !!baseURL.trim() && probe.kind !== "fetching" && probe.kind !== "testing";
  // 匹配不要求 baseURL：填了能把范围收窄到该端点对应那家，留空则全库匹配。
  const canMatch = mode === "live" && models.length > 0 && match.kind !== "matching";
  const selectedIds = models.map((m) => m.id);

  /**
   * 执行一次匹配并回填。只补空缺（fillGaps），所以对已配好的模型是幂等的。
   * silent：自动匹配（勾选触发）不弹「全部未命中」这类噪音提示。
   */
  const runMatch = async (ids: string[], silent = false) => {
    const targets = [...new Set(ids.filter((id) => id.trim() !== ""))];
    if (targets.length === 0 || mode !== "live") return;
    setMatch({ kind: "matching" });
    try {
      const r = await matchOcModelsLive(baseURL.trim(), targets);
      if (!aliveRef.current) return;
      if (!r.catalog_ok) {
        setMatch({ kind: "error", ok: false, text: t("oc.matchFailed", { err: r.catalog_error ?? "" }) });
        return;
      }
      // 合并走函数式更新（避免闭包里的旧 models 覆盖用户刚做的编辑）。
      setModels((prev) =>
        prev.map((m) => {
          const hit = r.matched[m.id];
          return hit ? { id: m.id, config: fillGaps(m.config, hit.config) } : m;
        }),
      );
      const source: Record<string, { provider: string; matchedId: string }> = {};
      for (const [id, hit] of Object.entries(r.matched)) {
        source[id] = { provider: hit.provider, matchedId: hit.matched_id };
      }
      setMatchedFrom((prev) => ({ ...prev, ...source }));
      const hitCount = Object.keys(source).length;
      const miss = targets.length - hitCount;
      if (hitCount === 0) {
        setMatch(
          silent
            ? { kind: "idle" }
            : { kind: "error", ok: false, text: t("oc.matchNone") },
        );
      } else {
        setMatch({
          kind: "done",
          ok: true,
          text: miss > 0 ? t("oc.matchDoneSome", { n: hitCount, miss }) : t("oc.matchDone", { n: hitCount }),
        });
      }
    } catch (e) {
      if (aliveRef.current) {
        setMatch({ kind: "error", ok: false, text: t("oc.matchFailed", { err: (e as Error).message }) });
      }
    }
  };

  /** 把新加入的 id 排进合并窗口；窗口到点后合并成一次请求。 */
  const scheduleAutoMatch = (ids: string[]) => {
    if (mode !== "live") return;
    pendingRef.current = [...new Set([...pendingRef.current, ...ids])];
    if (timerRef.current != null) window.clearTimeout(timerRef.current);
    timerRef.current = window.setTimeout(() => {
      timerRef.current = null;
      const batch = pendingRef.current;
      pendingRef.current = [];
      void runMatch(batch, true);
    }, AUTO_MATCH_DEBOUNCE_MS);
  };

  const toggleModel = (id: string) => {
    const removing = models.some((m) => m.id === id);
    setModels((prev) => (removing ? prev.filter((m) => m.id !== id) : [...prev, { id, config: {} }]));
    if (!removing) scheduleAutoMatch([id]);
  };

  const addCustomModel = () => {
    const id = customModel.trim();
    if (!id) return;
    const exists = models.some((m) => m.id === id);
    if (!exists) {
      setModels((prev) => [...prev, { id, config: {} }]);
      scheduleAutoMatch([id]);
    }
    setCustomModel("");
  };

  const fetchModels = async () => {
    if (!canProbe) return;
    setProbe({ kind: "fetching" });
    try {
      const list = await fetchOcModelsLive(baseURL.trim(), apiKey.trim());
      setFetched(list);
      // 已选但上游没返回的手工模型保留；上游新模型默认不勾选，由用户多选。
      setProbe({ kind: "done", ok: true, text: t("oc.probeDone", { n: list.length }) });
    } catch (e) {
      setProbe({ kind: "error", ok: false, text: (e as Error).message });
    }
  };

  const testModel = async () => {
    if (!canProbe) return;
    const model = models[0]?.id ?? fetched[0];
    if (!model) {
      setProbe({ kind: "error", ok: false, text: t("oc.probeNeedModels") });
      return;
    }
    setProbe({ kind: "testing" });
    try {
      const r = await testOcModelLive(baseURL.trim(), apiKey.trim(), model);
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
            {initial ? t("oc.editProvider", { key: initial.key }) : t("oc.newProvider")}
          </span>
          <span className="flex-1" />
          <button className="icon-btn" title={t("common.close")} aria-label={t("common.close")} onClick={onClose}>
            <X size={15} />
          </button>
        </div>

        <div className="p-5 space-y-4 flex-1 overflow-y-auto">
          <div>
            <label className="field-label">{t("oc.keyLabel")}</label>
            <input
              autoFocus={!initial}
              disabled={!!initial}
              className="text-input font-mono text-[12px] disabled:opacity-50"
              placeholder={t("oc.keyPlaceholder")}
              value={key}
              onChange={(e) => setKey(e.target.value)}
            />
            {key.trim() && !keyValid && (
              <div className="mt-1 text-[11px] text-warn">{t("oc.keyPattern")}</div>
            )}
            <div className="mt-1 text-[11px] text-faint">
              {t("oc.keyNote", { key: key.trim() || "key" })}
            </div>
          </div>

          <div>
            <label className="field-label">{t("oc.nameLabel")}</label>
            <input
              className="text-input"
              placeholder={t("oc.namePlaceholder")}
              value={name}
              onChange={(e) => setName(e.target.value)}
            />
          </div>

          <div>
            <label className="field-label">{t("oc.npmLabel")}</label>
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
            <label className="field-label">{t("oc.apiKeyLabel")}{initial?.apiKey ? t("oc.apiKeySaved") : ""}</label>
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
              <label className="field-label !mb-0">{t("oc.modelsLabel")}</label>
              <span className="flex-1" />
              <button
                type="button"
                className="chip border border-edge-strong bg-raised text-dim cursor-pointer disabled:opacity-40 disabled:pointer-events-none"
                disabled={!canProbe}
                onClick={fetchModels}
                title={mode === "live" ? t("oc.probeTip") : t("oc.probeTipDemo")}
              >
                {probe.kind === "fetching" ? t("llm.probing") : t("oc.probe")}
              </button>
              <button
                type="button"
                className="chip border border-info/30 bg-info/10 text-info cursor-pointer disabled:opacity-40 disabled:pointer-events-none"
                disabled={!canProbe}
                onClick={testModel}
                title={t("oc.testTip")}
              >
                {probe.kind === "testing" ? t("oc.testing") : t("oc.test")}
              </button>
              <button
                type="button"
                className="chip border border-accent/30 bg-accent/10 text-accent cursor-pointer disabled:opacity-40 disabled:pointer-events-none"
                disabled={!canMatch}
                onClick={() => void runMatch(models.map((m) => m.id))}
                title={mode === "live" ? t("oc.matchTip") : t("oc.matchTipDemo")}
              >
                <Sparkle size={11} weight="fill" />
                {match.kind === "matching" ? t("oc.matching") : t("oc.match")}
              </button>
            </div>

            {mode !== "live" && (
              <div className="mb-2 text-[11px] text-warn">
                {t("oc.demoNoBackend")}
              </div>
            )}
            {mode === "live" && !baseURL.trim() && (
              <div className="mb-2 text-[11px] text-faint">{t("oc.probeNote")}</div>
            )}

            {probe.kind !== "idle" && probe.kind !== "fetching" && probe.kind !== "testing" && (
              <div className={`mb-2 text-[11.5px] ${probe.ok ? "text-accent" : "text-danger"}`}>
                {probe.ok ? "✓ " : "✗ "}
                {probe.text}
              </div>
            )}
            {match.kind !== "idle" && match.kind !== "matching" && (
              <div className={`mb-2 text-[11.5px] ${match.ok ? "text-info" : "text-warn"}`}>
                {match.ok ? "✦ " : ""}
                {match.text}
              </div>
            )}

            {fetched.length > 0 && (
              <div className="mb-2 rounded-lg border border-edge bg-canvas/50 max-h-[180px] overflow-y-auto p-2">
                <div className="flex items-center gap-2 px-1 pb-1.5 text-[11px] text-faint">
                  <span>{t("oc.upstreamCount", { n: fetched.length })}</span>
                  <span className="flex-1" />
                  <button
                    type="button"
                    className="cursor-pointer bg-transparent border-0 p-0 text-dim hover:text-accent"
                    onClick={() => {
                      const have = new Set(models.map((m) => m.id));
                      const added = fetched.filter((id) => !have.has(id));
                      if (added.length === 0) return;
                      setModels((prev) => [...prev, ...added.map((id) => ({ id, config: {} }))]);
                      scheduleAutoMatch(added);
                    }}
                  >
                    {t("llm.selectAll")}
                  </button>
                  <span>·</span>
                  <button
                    type="button"
                    className="cursor-pointer bg-transparent border-0 p-0 text-dim hover:text-accent"
                    onClick={() => setModels(models.filter((m) => !fetched.includes(m.id)))}
                  >
                    {t("llm.clearUpstream")}
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
                placeholder={t("oc.manualModelPlaceholder")}
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
                {t("common.add")}
              </button>
            </div>

            {models.length > 0 && (
              <div className="mt-2 space-y-2">
                <div className="text-[11px] text-faint">{t("oc.matchHint")}</div>
                {models.map((m) => (
                  <div key={m.id}>
                    <div className="flex items-center gap-2 rounded-lg border border-edge bg-raised/40 px-3 py-2">
                      <span
                        className="font-mono text-[12px] text-dim truncate"
                        title={
                          matchedFrom[m.id]
                            ? t("oc.matchSourceTip", {
                                matched: matchedFrom[m.id].matchedId,
                                provider: matchedFrom[m.id].provider,
                              })
                            : undefined
                        }
                      >
                        {m.id}
                      </span>
                      {modelTags(m.config).map((tag) => (
                        <span
                          key={tag.kind}
                          title={t(`oc.tag.${tag.kind}`, { label: tag.label })}
                          className="chip border border-edge-strong bg-raised font-mono text-[10px] text-dim"
                        >
                          {tag.kind === "vision" ? t("oc.tagLabel.vision") : tag.label}
                        </span>
                      ))}
                      {typeof m.config.name === "string" && m.config.name && (
                        <span className="text-[11.5px] text-faint truncate">{m.config.name}</span>
                      )}
                      <span className="flex-1" />
                      <button
                        type="button"
                        className="icon-btn"
                        title={t("oc.editModel")}
                        aria-label={t("oc.editModel")}
                        onClick={() => setEditingModel(editingModel === m.id ? null : m.id)}
                      >
                        <PencilSimple size={12} />
                      </button>
                      <button
                        type="button"
                        className="icon-btn hover:!text-danger"
                        title={t("oc.removeModel")}
                        aria-label={t("oc.removeModel")}
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
            {t("oc.writeNote")}
          </div>
        </div>

        <div className="flex justify-end gap-2 px-5 py-4 border-t border-edge shrink-0">
          <button className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button className="btn btn-primary" disabled={!canSave} onClick={save}>
            {saving ? t("llm.saving") : t("common.save")}
          </button>
        </div>
    </div>
  );
}
