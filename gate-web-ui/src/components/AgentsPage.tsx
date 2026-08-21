import { useState } from "react";
import {
  ArrowClockwise,
  CheckCircle,
  Plus,
  PencilSimple,
  Sparkle,
  TerminalWindow,
  Trash,
  WarningCircle,
} from "@phosphor-icons/react";
import { actions } from "../lib/actions";
import { appStore, useApp } from "../lib/store";
import type { AgentConfig } from "../lib/types";

const CLI_LABEL: Record<string, string> = { claude: "Claude Code", opencode: "OpenCode" };

function RuntimeCards() {
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
            <div className="field-label mb-1.5">可用模型 · {r.modelSource === "cli" ? "来自 CLI 探测" : r.modelSource === "cli-hints" ? "CLI 常用别名" : "默认"}</div>
            <div className="flex flex-wrap gap-1">
              {(r.models.length > 0 ? r.models : ["default"]).map((m) => (
                <span key={m} className="chip border border-edge-strong bg-sunken text-dim font-mono">
                  {m}
                </span>
              ))}
            </div>
          </div>
        </div>
      ))}
    </div>
  );
}

function AgentDialog({ initial, onClose }: { initial: AgentConfig | null; onClose: () => void }) {
  const runtimes = useApp((s) => s.runtimes);
  const [name, setName] = useState(initial?.name ?? "");
  const [cli, setCli] = useState<"claude" | "opencode">(initial?.cli ?? "claude");
  const [model, setModel] = useState(initial?.model ?? "");
  const [systemPrompt, setSystemPrompt] = useState(initial?.systemPrompt ?? "");
  const [extraFlags, setExtraFlags] = useState((initial?.extraFlags ?? []).join(" "));
  const [description, setDescription] = useState(initial?.description ?? "");
  const [saving, setSaving] = useState(false);

  const runtime = runtimes.find((r) => r.name === cli);
  const models = runtime?.models ?? [];

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
                {(["claude", "opencode"] as const).map((c) => {
                  const rt = runtimes.find((r) => r.name === c);
                  const disabled = rt ? !rt.available : false;
                  return (
                    <button
                      key={c}
                      disabled={disabled}
                      onClick={() => {
                        setCli(c);
                        setModel("");
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
              className="text-input mb-2"
              placeholder="例如：Claude 主力"
              value={name}
              onChange={(e) => setName(e.target.value)}
            />
            <div className="flex flex-wrap gap-1">
              {(["default", ...models.filter((m) => m !== "default")]).map((m) => (
                <button
                  key={m}
                  onClick={() => setModel(m)}
                  className={`chip border cursor-pointer transition-colors font-mono ${
                    model === m
                      ? "border-accent/50 bg-accent/10 text-accent"
                      : "border-edge-strong bg-sunken text-dim hover:text-ink"
                  }`}
                >
                  {m}
                </button>
              ))}
            </div>
            {model && !["default", ...models].includes(model) && (
              <input className="text-input mt-2 font-mono text-[12px]" value={model} onChange={(e) => setModel(e.target.value)} />
            )}
            {!model && (
              <input
                className="text-input mt-2 font-mono text-[12px]"
                placeholder="或手动输入模型标识（可选）"
                value={model}
                onChange={(e) => setModel(e.target.value)}
              />
            )}
          </div>

          <div>
            <label className="field-label">系统提示词（可选）</label>
            <textarea
              className="text-input h-20 py-2 resize-none"
              placeholder="为该智能体设定角色与约束…"
              value={systemPrompt}
              onChange={(e) => setSystemPrompt(e.target.value)}
            />
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

  return (
    <div className="flex-1 min-h-0 overflow-y-auto">
      <div className="max-w-[1080px] mx-auto px-6 py-5 space-y-6">
        <section>
          <div className="flex items-center gap-3 pb-3">
            <span className="kicker">本地 CLI 检测</span>
            <span className="font-mono text-[11px] text-faint">自动探测本机可用的 Agent 运行时</span>
          </div>
          <RuntimeCards />
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
    </div>
  );
}
