import { useEffect, useState } from "react";
import { AnimatePresence, motion } from "motion/react";
import { ArrowClockwise, PencilSimple, Robot } from "@phosphor-icons/react";
import { fetchUpstreamModels, updateProviderModels } from "@/features/settings";
import { showToast } from "@/store";
import type { LlmProvider } from "@/shared/types";
import { Spinner } from "@/shared/components/ui";

/** Provider 模型列表编辑器：手动编辑或从上游拉取。 */
export function ModelEditor({ provider, onUpdated }: { provider: LlmProvider; onUpdated: () => void }) {
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
