import { useCallback, useEffect, useState } from "react";
import { motion } from "motion/react";
import { Check, Link, PencilSimple, PlugsConnected, Plus, Robot, Trash, WarningCircle } from "@phosphor-icons/react";
import { deleteProvider, fetchProviders } from "@/features/settings";
import { showToast } from "@/store";
import type { LlmProvider } from "@/shared/types";
import { CopyButton, Spinner } from "@/shared/components/ui";
import { ProviderDialog } from "./ProviderDialog";
import { ModelEditor } from "./ModelEditor";

/** LLM 设置区块：Provider 列表 + 模型管理。 */
export function LlmBlock() {
  const [providers, setProviders] = useState<LlmProvider[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [dialog, setDialog] = useState<LlmProvider | null | "new">(null);
  const [deleteConfirm, setDeleteConfirm] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true); setError(null);
    try { setProviders(await fetchProviders()); } catch (e) { setError((e as Error).message); }
    finally { setLoading(false); }
  }, []);

  useEffect(() => { void load(); }, [load]);

  const handleDelete = async (id: string) => {
    try {
      await deleteProvider(id);
      showToast("Provider 已删除");
      await load();
    } catch (e) { showToast((e as Error).message); }
    finally { setDeleteConfirm(null); }
  };

  if (loading) return <div className="card p-8 flex items-center gap-2 text-[12.5px] text-faint"><Spinner /> 正在加载 Providers …</div>;
  if (error) return <div className="card p-6"><div className="text-[13px] text-danger flex items-center gap-1.5"><WarningCircle size={14} weight="fill" /> 加载失败：{error}</div><button className="btn mt-3" onClick={() => void load()}>重试</button></div>;

  return (
    <div className="space-y-4">
      <div className="flex items-center gap-2.5">
        <span className="kicker !mb-0">Providers</span>
        <span className="chip border border-edge-strong bg-raised text-faint">{providers.length} 个</span>
        <span className="flex-1" />
        <button className="btn btn-primary btn-sm" onClick={() => setDialog("new")}><Plus size={12} weight="bold" /> 新建 Provider</button>
      </div>

      {providers.length === 0 ? (
        <div className="card border-dashed p-10 text-center">
          <div className="w-11 h-11 rounded-xl border border-dashed border-edge-strong grid place-items-center mx-auto">
            <Robot size={20} className="text-faint" />
          </div>
          <div className="mt-3 text-[13.5px] text-dim">暂无 Provider</div>
          <div className="mt-1 text-[12px] text-faint">新建一个 Provider 后即可管理接口与模型</div>
          <button className="btn btn-primary btn-sm mt-4" onClick={() => setDialog("new")}><Plus size={12} weight="bold" /> 新建 Provider</button>
        </div>
      ) : (
        <div className="space-y-3">
          {providers.map((p) => (
            <motion.div
              key={p.id}
              layout
              initial={{ opacity: 0, y: 8 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ type: "spring", stiffness: 380, damping: 32 }}
              className="card p-4 transition-colors hover:border-edge-strong"
            >
              <div className="flex items-start gap-3">
                <span className="w-8 h-8 rounded-lg bg-accent-dim border border-accent/25 grid place-items-center text-accent shrink-0"><PlugsConnected size={15} /></span>
                <div className="min-w-0 flex-1">
                  <div className="flex items-center gap-2 flex-wrap">
                    <span className="text-[13.5px] font-semibold">{p.name}</span>
                    <span className="font-mono text-[11px] text-faint">{p.id}</span>
                    {p.credential_configured ? <span className="chip border border-accent/30 bg-accent/10 text-accent text-[10.5px]"><Check size={11} weight="bold" /> 已配置密钥</span> : <span className="chip border border-warn/30 bg-warn/10 text-warn text-[10.5px]">未配置密钥</span>}
                    <span className="chip border border-edge-strong bg-raised text-dim text-[10.5px]">{p.type}</span>
                    <span className="chip border border-info/25 bg-info/10 text-info font-mono text-[10.5px]">{p.model_count} 模型</span>
                  </div>
                  <div className="mt-1 flex items-center gap-1.5 font-mono text-[11.5px] text-faint break-all">
                    <Link size={12} className="shrink-0" />{p.base_url}
                    <CopyButton text={p.base_url} label="复制 Base URL" />
                  </div>
                </div>
                <div className="flex items-center gap-1 shrink-0">
                  <button className="icon-btn" title="编辑" onClick={() => setDialog(p)}><PencilSimple size={13} /></button>
                  {deleteConfirm === p.id ? (
                    <span className="flex items-center gap-1">
                      <button className="chip border border-danger/40 bg-danger/10 text-danger cursor-pointer" onClick={() => void handleDelete(p.id)}>确认删除</button>
                      <button className="chip border border-edge-strong text-dim cursor-pointer" onClick={() => setDeleteConfirm(null)}>返回</button>
                    </span>
                  ) : (
                    <button className="icon-btn hover:!text-danger" title="删除" onClick={() => setDeleteConfirm(p.id)}><Trash size={13} /></button>
                  )}
                </div>
              </div>
              <ModelEditor provider={p} onUpdated={() => void load()} />
            </motion.div>
          ))}
        </div>
      )}

      {dialog !== null && (
        <ProviderDialog initial={dialog === "new" ? null : dialog} onClose={() => setDialog(null)} onSaved={() => void load()} />
      )}
    </div>
  );
}
