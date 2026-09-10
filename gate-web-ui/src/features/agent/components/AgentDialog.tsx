import { useEffect, useMemo, useState } from "react";
import { ArrowClockwise, CaretDown, CheckCircle, CircleNotch, Sparkle, TerminalWindow } from "@phosphor-icons/react";
import { actions } from "@/app/actions";
import { useApp } from "@/store";
import type { AgentConfig } from "@/shared/types";
import { useBackdropClose } from "@/shared/components/ui";
import { CLI_LABEL } from "./labels";
import { useT, type Translate } from "@/i18n";

/* ── 模型选择（抄 OpenDesign SettingsDialog 的 agent-model 字段）── */

const CUSTOM_MODEL_SENTINEL = "__custom__";

/** 与后端 RuntimeInfoService 的 model_source 对齐：cli=实时探测，其余为内置/兜底列表。 */
function modelSourceBadge(source: string | undefined, t: Translate): { label: string; live: boolean } {
  if (source === "cli" || source === "cli-loading") return { label: t("agent.source.cli"), live: true };
  if (source === "cli-hints") return { label: t("agent.source.cliHints"), live: false };
  return { label: t("agent.source.builtin"), live: false };
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

/** 新增/编辑智能体弹窗：CLI 选择、模型目录（实时拉取轮询）、系统提示词与启动参数。 */
export function AgentDialog({ initial, onClose }: { initial: AgentConfig | null; onClose: () => void }) {
  const t = useT();
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
  const badge = modelSourceBadge(modelSource, t);
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
          <span className="text-[13.5px] font-semibold">{initial ? t("agent.dialog.edit") : t("agent.dialog.new")}</span>
        </div>

        <div className="p-5 space-y-4 max-h-[70vh] overflow-y-auto">
          {!initial && (
            <div>
              <label className="field-label">{t("agent.step1")}</label>
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
                        {rt?.version ? `v${rt.version}` : (rt?.note ?? t("agent.notDetected"))}
                      </div>
                    </button>
                  );
                })}
              </div>
            </div>
          )}

          <div>
            <label className="field-label">{initial ? t("common.name") : t("agent.step2")}</label>
            <input
              autoFocus={!initial}
              className="text-input mb-3"
              placeholder={t("agent.namePlaceholder")}
              value={name}
              onChange={(e) => setName(e.target.value)}
            />

            <div className="flex items-center gap-2 mb-1.5">
              <span className="text-[11px] font-medium uppercase tracking-[0.08em] text-faint">{t("agent.modelLabel")}</span>
              <span className={`source-badge ${badge.live ? "live" : "fallback"}`}>{badge.label}</span>
              <span className="flex-1" />
              <button
                className="icon-btn w-5 h-5"
                title={t("agent.refetchModels")}
                aria-label={t("agent.refetchModels")}
                onClick={() => actions.refreshRuntimes()}
              >
                <ArrowClockwise size={12} className={loadingModels ? "animate-spin" : ""} />
              </button>
            </div>

            {loadingModels ? (
              <div className="text-input h-9 flex items-center gap-2 text-dim" role="status" aria-busy="true">
                <CircleNotch size={13} className="animate-spin text-accent" />
                {t("agent.fetchingModels")}
              </div>
            ) : (
              <div className="relative">
                <select
                  className="text-input appearance-none pr-8 cursor-pointer font-mono text-[12px]"
                  value={selectValue}
                  aria-label={t("agent.modelAria")}
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
                  <option value="default">{t("agent.cliDefaultOption")}</option>
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
                  <option value={CUSTOM_MODEL_SENTINEL}>{t("agent.customOption")}</option>
                </select>
                <CaretDown
                  size={12}
                  className="absolute right-2.5 top-1/2 -translate-y-1/2 pointer-events-none text-faint"
                />
              </div>
            )}
            <div className="mt-1.5 text-[11px] text-faint">
              {badge.live
                ? t("agent.modelSourceCliHint")
                : t("agent.modelSourceBuiltinHint")}
            </div>

            {customActive && (
              <div className="mt-2">
                <label className="field-label">{t("agent.customModelLabel")}</label>
                <input
                  className="text-input font-mono text-[12px]"
                  placeholder={t("agent.customModelPlaceholder")}
                  value={model}
                  autoFocus
                  onChange={(e) => setModel(e.target.value)}
                />
              </div>
            )}
          </div>

          <div>
            <div className="flex items-center gap-2 mb-1.5">
              <span className="text-[11px] font-medium uppercase tracking-[0.08em] text-faint">{t("agent.systemPromptLabel")}</span>
              <span className="flex-1" />
              <button
                type="button"
                role="switch"
                aria-checked={injectContext}
                onClick={() => setInjectContext((v) => !v)}
                className="flex items-center gap-1.5 cursor-pointer bg-transparent border-0 p-0 group"
                title={t("agent.systemPromptTip")}
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
                  {t("agent.injectLabel")}
                </span>
              </button>
            </div>
            <textarea
              className="text-input h-20 py-2 resize-none"
              placeholder={t("agent.systemPromptPlaceholder")}
              value={systemPrompt}
              onChange={(e) => setSystemPrompt(e.target.value)}
            />
            {injectContext && (
              <div className="mt-1.5 text-[11px] text-faint">
                {t("agent.injectHint")}
              </div>
            )}
          </div>

          <div>
            <label className="field-label">{t("agent.extraArgsLabel")}</label>
            <input
              className="text-input font-mono text-[12px]"
              placeholder="-c model_context_limit=200000"
              value={extraFlags}
              onChange={(e) => setExtraFlags(e.target.value)}
            />
          </div>

          <div>
            <label className="field-label">{t("agent.dutyLabel")}</label>
            <input
              className="text-input"
              placeholder={t("agent.dutyPlaceholder")}
              value={description}
              onChange={(e) => setDescription(e.target.value)}
            />
          </div>
        </div>

        <div className="flex justify-end gap-2 px-5 py-4 border-t border-edge">
          <button className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button className="btn btn-primary" disabled={!name.trim() || saving} onClick={save}>
            {saving ? t("llm.saving") : initial ? t("llm.saveChanges") : t("agent.create")}
          </button>
        </div>
      </div>
    </div>
  );
}
