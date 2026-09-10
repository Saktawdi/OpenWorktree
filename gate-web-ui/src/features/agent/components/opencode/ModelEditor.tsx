import { useState } from "react";
import { useT } from "@/i18n";
import { CaretDown, X } from "@phosphor-icons/react";
import type { OpenCodeModelEntry } from "@/shared/types";
import {
  CONTEXT_LIMIT_PRESETS,
  MODALITY_PRESETS,
  OUTPUT_LIMIT_PRESETS,
  VARIANT_TEMPLATES,
  parseLimit,
  parseModelConfig,
} from "./presets";
import { LimitCombo } from "./LimitCombo";

/** ai-toolbox 式模型编辑器：限制 / 模态 / 能力 / 变体 / 额外参数，内联展开在模型行下方。 */
export function ModelEditor({
  entry,
  onSave,
  onCancel,
}: {
  entry: OpenCodeModelEntry;
  onSave: (next: OpenCodeModelEntry) => void;
  onCancel: () => void;
}) {
  const t = useT();
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
      setJsonError(t("ocm.jsonParseFailed", { err: (e as Error).message }));
      return;
    }
    const ctx = context.trim() ? parseLimit(context) : null;
    const outNum = output.trim() ? parseLimit(output) : null;
    const bad = [context.trim() && ctx == null ? t("ocm.ctxInvalid") : "", output.trim() && outNum == null ? t("ocm.outInvalid") : ""].filter(Boolean);
    if (bad.length > 0) {
      setJsonError(t("ocm.numInvalid", { bad: bad.join("、") }));
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
        <span className="text-[12.5px] font-semibold">{t("ocm.title")}</span>
        <span className="chip border border-edge-strong bg-canvas text-dim font-mono">{entry.id}</span>
        <span className="flex-1" />
        <button type="button" className="icon-btn" title={t("common.cancel")} aria-label={t("ocm.cancelTip")} onClick={onCancel}>
          <X size={13} />
        </button>
      </div>

      <div className="grid grid-cols-2 gap-3">
        <div>
          <label className="field-label">{t("ocm.nameLabel")}</label>
          <input className="text-input" placeholder={entry.id} value={name} onChange={(e) => setName(e.target.value)} />
        </div>
        <div className="grid grid-cols-2 gap-3">
          <div>
            <label className="field-label">{t("ocm.ctxLabel")}</label>
            <LimitCombo
              presets={CONTEXT_LIMIT_PRESETS}
              value={context}
              onChange={setContext}
              placeholder={t("ocm.ctxPlaceholder")}
              ariaLabel={t("ocm.ctxLabel")}
            />
          </div>
          <div>
            <label className="field-label">{t("ocm.outLabel")}</label>
            <LimitCombo
              presets={OUTPUT_LIMIT_PRESETS}
              value={output}
              onChange={setOutput}
              placeholder={t("ocm.outPlaceholder")}
              ariaLabel={t("ocm.outLabel")}
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
        {t("agent.advanced")}
      </button>

      {advancedOpen && (
        <div className="space-y-3.5">
          <div>
            <label className="field-label">{t("ocm.inModalLabel")}</label>
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
            <label className="field-label">{t("ocm.outModalLabel")}</label>
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
            <div className="mt-1 text-[11px] text-faint">{t("ocm.modalityHint")}</div>
          </div>
          <div>
            <label className="field-label">{t("ocm.capsLabel")}</label>
            <div className="flex flex-wrap gap-4">
              {cap(t("ocm.cap.reasoning"), reasoning, setReasoning)}
              {cap(t("ocm.cap.toolCall"), toolCall, setToolCall)}
              {cap(t("ocm.cap.temperature"), temperature, setTemperature)}
              {cap(t("ocm.cap.attachment"), attachment, setAttachment)}
            </div>
            <div className="mt-1 text-[11px] text-faint">{t("ocm.capsHint")}</div>
          </div>
          <div>
            <div className="flex items-center gap-1.5 mb-1.5">
              <label className="field-label !mb-0">{t("ocm.variantsLabel")}</label>
              <span className="flex-1" />
              <span className="text-[10.5px] text-faint">{t("ocm.fillPreset")}</span>
              {VARIANT_TEMPLATES.map((tpl) => (
                <button
                  key={tpl.labelKey}
                  type="button"
                  title={t(tpl.titleKey)}
                  className="chip border border-edge-strong bg-canvas text-dim cursor-pointer hover:border-accent/40 hover:text-accent transition-colors"
                  onClick={() => setVariants(tpl.json)}
                >
                  {t(tpl.labelKey)}
                </button>
              ))}
            </div>
            <textarea
              className="text-input font-mono text-[12px] h-36 resize-y"
              placeholder={'{\n  "high": { "reasoningEffort": "high" }\n}'}
              value={variants}
              onChange={(e) => setVariants(e.target.value)}
            />
            <div className="mt-1 text-[11px] text-faint">{t("ocm.variantsHint")}</div>
          </div>
          <div>
            <label className="field-label">{t("ocm.extraArgsLabel")}</label>
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
          {t("common.cancel")}
        </button>
        <button type="button" className="btn btn-primary h-8" onClick={save}>
          {t("ocm.applyModel")}
        </button>
      </div>
    </div>
  );
}
