import { useState } from "react";
import { PencilSimple, Plug, Trash, X } from "@phosphor-icons/react";
import { actions } from "@/app/actions";
import { useApp } from "@/store";
import { fetchOcModelsLive, testOcModelLive } from "@/features/agent";
import type { OpenCodeModelEntry, OpenCodeProvider } from "@/shared/types";
import { NPM_PRESETS, normalizeModelEntries, type ProbeState } from "./presets";
import { ModelEditor } from "./ModelEditor";

/**
 * 供应商编辑面板：由 OpenCodeProvidersModal 以右侧拼接面板承载（motion 动效在父级）。
 */
export function OcProviderPanel({
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
      const list = await fetchOcModelsLive(baseURL.trim(), apiKey.trim());
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
