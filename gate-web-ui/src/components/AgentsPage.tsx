import { useEffect, useMemo, useState } from "react";
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
import type { AgentConfig, OpenCodeProvider } from "../lib/types";

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

function OcProviderDialog({
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
  const [selected, setSelected] = useState<string[]>(initial?.models ?? []);
  const [fetched, setFetched] = useState<string[]>([]);
  const [customModel, setCustomModel] = useState("");
  const [probe, setProbe] = useState<ProbeState>({ kind: "idle" });
  const [saving, setSaving] = useState(false);

  const keyValid = /^[A-Za-z0-9._\-/]+$/.test(key.trim());
  const canSave = keyValid && name.trim().length > 0 && !saving;
  const canProbe = mode === "live" && !!baseURL.trim() && probe.kind !== "fetching" && probe.kind !== "testing";

  const toggleModel = (id: string) =>
    setSelected((prev) => (prev.includes(id) ? prev.filter((m) => m !== id) : [...prev, id]));

  const addCustomModel = () => {
    const id = customModel.trim();
    if (!id) return;
    setSelected((prev) => (prev.includes(id) ? prev : [...prev, id]));
    setCustomModel("");
  };

  const fetchModels = async () => {
    if (!canProbe) return;
    setProbe({ kind: "fetching" });
    try {
      const list = await live.fetchOcModelsLive(baseURL.trim(), apiKey.trim());
      setFetched(list);
      // 已选但上游没返回的手工模型保留在已选里；上游新模型默认不勾选，由用户多选。
      setProbe({ kind: "done", ok: true, text: `拉取到 ${list.length} 个模型，勾选要写入配置的模型` });
    } catch (e) {
      setProbe({ kind: "error", ok: false, text: (e as Error).message });
    }
  };

  const testModel = async () => {
    if (!canProbe) return;
    const model = selected[0] ?? fetched[0];
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
      models: selected,
      modelCount: selected.length,
    });
    setSaving(false);
    if (ok) onClose();
  };

  return (
    <div className="fixed inset-0 z-[60] grid place-items-center bg-black/55 backdrop-blur-[2px]" onClick={onClose}>
      <div className="w-[540px] card shadow-2xl shadow-black/60 animate-rise" onClick={(e) => e.stopPropagation()}>
        <div className="flex items-center gap-2 px-5 h-12 border-b border-edge">
          <Plug size={15} className="text-accent" weight="fill" />
          <span className="text-[13.5px] font-semibold">
            {initial ? "编辑 OpenCode 供应商" : "新增 OpenCode 供应商"}
          </span>
        </div>

        <div className="p-5 space-y-4 max-h-[72vh] overflow-y-auto">
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
                    onClick={() => setSelected(Array.from(new Set([...selected, ...fetched])))}
                  >
                    全选
                  </button>
                  <span>·</span>
                  <button
                    type="button"
                    className="cursor-pointer bg-transparent border-0 p-0 text-dim hover:text-accent"
                    onClick={() => setSelected(selected.filter((m) => !fetched.includes(m)))}
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
                      checked={selected.includes(m)}
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

            {selected.length > 0 && (
              <div className="mt-2 flex flex-wrap gap-1.5">
                {selected.map((m) => (
                  <span key={m} className="chip border border-edge-strong bg-raised text-dim font-mono">
                    {m}
                    <button
                      type="button"
                      className="ml-1 cursor-pointer bg-transparent border-0 p-0 text-faint hover:text-danger"
                      aria-label={`移除 ${m}`}
                      onClick={() => setSelected((prev) => prev.filter((x) => x !== m))}
                    >
                      ×
                    </button>
                  </span>
                ))}
              </div>
            )}
          </div>

          <div className="text-[11px] text-faint">
            保存会直接写入本机 OpenCode 配置文件（只改 provider 节点，其余内容原样保留；写前自动备份 .bak）。
          </div>
        </div>

        <div className="flex justify-end gap-2 px-5 py-4 border-t border-edge">
          <button className="btn" onClick={onClose}>
            取消
          </button>
          <button className="btn btn-primary" disabled={!canSave} onClick={save}>
            {saving ? "保存中…" : "保存"}
          </button>
        </div>
      </div>
    </div>
  );
}

/** 供应商管理全屏弹窗：从 OpenCode 运行时卡片的入口进入。 */
function OpenCodeProvidersModal({ onClose }: { onClose: () => void }) {
  const ocProviders = useApp((s) => s.ocProviders);
  const ocConfigPath = useApp((s) => s.ocConfigPath);
  const mode = useApp((s) => s.mode);
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
    const model = p.models[0];
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
    <div className="fixed inset-0 z-50 grid place-items-center bg-black/55 backdrop-blur-[2px]" onClick={onClose}>
      <div
        className="w-[860px] max-w-[94vw] max-h-[86vh] flex flex-col card shadow-2xl shadow-black/60 animate-rise"
        onClick={(e) => e.stopPropagation()}
      >
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

        <div className="p-5 overflow-y-auto space-y-2">
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
                  模型 <span className="font-mono text-dim">{p.models.length > 0 ? p.models.join(" · ") : "—"}</span>
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

      {dialog.open && (
        <OcProviderDialog initial={dialog.provider} onClose={() => setDialog({ open: false, provider: null })} />
      )}
    </div>
  );
}

function AgentDialog({ initial, onClose }: { initial: AgentConfig | null; onClose: () => void }) {
  const runtimes = useApp((s) => s.runtimes);
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
    <div className="fixed inset-0 z-50 grid place-items-center bg-black/55 backdrop-blur-[2px]" onClick={onClose}>
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
