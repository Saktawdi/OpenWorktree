import { useCallback, useEffect, useState } from "react";
import { motion } from "motion/react";
import { Check, Link, PencilSimple, PlugsConnected, Plus, Robot, Trash, WarningCircle } from "@phosphor-icons/react";
import { deleteProvider, fetchProviders } from "@/features/settings";
import { showToast } from "@/store";
import { useT } from "@/i18n";
import type { LlmProvider } from "@/shared/types";
import { CopyButton, Spinner } from "@/shared/components/ui";
import { ProviderDialog } from "./ProviderDialog";
import { ModelEditor } from "./ModelEditor";

/** LLM 设置区块：Provider 列表 + 模型管理。 */
export function LlmBlock() {
  const t = useT();
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
      showToast(t("llm.providerDeleted"));
      await load();
    } catch (e) { showToast((e as Error).message); }
    finally { setDeleteConfirm(null); }
  };

  if (loading) return <div className="card p-8 flex items-center gap-2 text-[12.5px] text-faint"><Spinner /> {t("llm.loadingProviders")}</div>;
  if (error) return <div className="card p-6"><div className="text-[13px] text-danger flex items-center gap-1.5"><WarningCircle size={14} weight="fill" /> {t("mcp.loadFailed")}：{error}</div><button className="btn mt-3" onClick={() => void load()}>{t("common.retry")}</button></div>;

  return (
    <div className="space-y-4">
      <div className="flex items-center gap-2.5">
        <span className="kicker !mb-0">Providers</span>
        <span className="chip border border-edge-strong bg-raised text-faint">{t("llm.providerCount", { n: providers.length })}</span>
        <span className="flex-1" />
        <button className="btn btn-primary btn-sm" onClick={() => setDialog("new")}><Plus size={12} weight="bold" /> {t("llm.newProvider")}</button>
      </div>

      {providers.length === 0 ? (
        <div className="card border-dashed p-10 text-center">
          <div className="w-11 h-11 rounded-xl border border-dashed border-edge-strong grid place-items-center mx-auto">
            <Robot size={20} className="text-faint" />
          </div>
          <div className="mt-3 text-[13.5px] text-dim">{t("llm.noProviders")}</div>
          <div className="mt-1 text-[12px] text-faint">{t("llm.noProvidersHint")}</div>
          <button className="btn btn-primary btn-sm mt-4" onClick={() => setDialog("new")}><Plus size={12} weight="bold" /> {t("llm.newProvider")}</button>
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
                    {p.credential_configured ? <span className="chip border border-accent/30 bg-accent/10 text-accent text-[10.5px]"><Check size={11} weight="bold" /> {t("llm.keyConfigured")}</span> : <span className="chip border border-warn/30 bg-warn/10 text-warn text-[10.5px]">{t("llm.keyMissing")}</span>}
                    <span className="chip border border-edge-strong bg-raised text-dim text-[10.5px]">{p.type}</span>
                    <span className="chip border border-info/25 bg-info/10 text-info font-mono text-[10.5px]">{t("llm.modelCount", { n: p.model_count })}</span>
                  </div>
                  <div className="mt-1 flex items-center gap-1.5 font-mono text-[11.5px] text-faint break-all">
                    <Link size={12} className="shrink-0" />{p.base_url}
                    <CopyButton text={p.base_url} label={t("llm.copyBaseUrl")} />
                  </div>
                </div>
                <div className="flex items-center gap-1 shrink-0">
                  <button className="icon-btn" title={t("common.edit")} onClick={() => setDialog(p)}><PencilSimple size={13} /></button>
                  {deleteConfirm === p.id ? (
                    <span className="flex items-center gap-1">
                      <button className="chip border border-danger/40 bg-danger/10 text-danger cursor-pointer" onClick={() => void handleDelete(p.id)}>{t("llm.confirmDelete")}</button>
                      <button className="chip border border-edge-strong text-dim cursor-pointer" onClick={() => setDeleteConfirm(null)}>{t("llm.back")}</button>
                    </span>
                  ) : (
                    <button className="icon-btn hover:!text-danger" title={t("common.delete")} onClick={() => setDeleteConfirm(p.id)}><Trash size={13} /></button>
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
