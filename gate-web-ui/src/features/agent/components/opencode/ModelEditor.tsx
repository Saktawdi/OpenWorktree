import { useState } from "react";
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
