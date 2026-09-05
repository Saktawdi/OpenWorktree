import { useEffect, useMemo, useState } from "react";
import { ArrowClockwise, CaretDown, CheckCircle, CircleNotch, Sparkle, TerminalWindow } from "@phosphor-icons/react";
import { actions } from "@/app/actions";
import { useApp } from "@/store";
import type { AgentConfig } from "@/shared/types";
import { useBackdropClose } from "@/shared/components/ui";
import { CLI_LABEL } from "./labels";

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

/** 新增/编辑智能体弹窗：CLI 选择、模型目录（实时拉取轮询）、系统提示词与启动参数。 */
export function AgentDialog({ initial, onClose }: { initial: AgentConfig | null; onClose: () => void }) {
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
