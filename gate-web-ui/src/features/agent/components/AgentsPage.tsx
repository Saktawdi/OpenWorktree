import { useState } from "react";
import { PencilSimple, Plus, Sparkle, Trash } from "@phosphor-icons/react";
import { actions } from "@/app/actions";
import { appStore, useApp } from "@/store";
import type { AgentConfig } from "@/shared/types";
import { RuntimeCards } from "./RuntimeCards";
import { AgentDialog } from "./AgentDialog";
import { OpenCodeProvidersModal } from "./opencode/OpenCodeProvidersModal";
import { CLI_LABEL } from "./labels";
import { useT } from "@/i18n";

/** 智能体页：本地 CLI 运行时检测 + 智能体员工配置管理。 */
export function AgentsPage() {
  const t = useT();
  const agents = useApp((s) => s.agents);
  const agentId = useApp((s) => s.agentId);
  const [dialog, setDialog] = useState<{ open: boolean; agent: AgentConfig | null }>({ open: false, agent: null });
  const [confirmDelete, setConfirmDelete] = useState<string | null>(null);
  const [ocModalOpen, setOcModalOpen] = useState(false);

  return (
    <div className="flex-1 min-h-0 overflow-y-auto scrollbar-none">
      <div className="max-w-[1080px] mx-auto px-6 py-5 space-y-6">
        <section>
          <div className="flex items-center gap-3 pb-3">
            <span className="kicker">{t("agents.cliKicker")}</span>
            <span className="font-mono text-[11px] text-faint">{t("agents.cliDesc")}</span>
          </div>
          <RuntimeCards onManageProviders={() => setOcModalOpen(true)} />
        </section>

        <section>
          <div className="flex items-center gap-3 pb-3">
            <span className="kicker">{t("agents.staffKicker")}</span>
            <span className="font-mono text-[11px] text-faint">{t("agents.staffCount", { n: agents.length })}</span>
            <span className="flex-1" />
            <button className="btn btn-primary h-8" onClick={() => setDialog({ open: true, agent: null })}>
              <Plus size={14} weight="bold" />
              {t("agent.dialog.new")}
            </button>
          </div>

          <div className="space-y-2">
            {agents.map((a) => {
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
                    {isDefault && <span className="chip border border-accent/30 bg-accent/10 text-accent">{t("agents.default")}</span>}
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
                          {t("llm.confirmDelete")}
                        </button>
                        <button className="chip border border-edge-strong text-dim cursor-pointer" onClick={() => setConfirmDelete(null)}>
                          {t("llm.back")}
                        </button>
                      </span>
                    ) : (
                      <>
                        <button
                          className="icon-btn"
                          title={t("agents.edit")}
                          aria-label={t("agents.edit")}
                          onClick={() => setDialog({ open: true, agent: a })}
                        >
                          <PencilSimple size={13} />
                        </button>
                        <button
                          className="icon-btn hover:!text-danger"
                          title={t("common.delete")}
                          aria-label={t("common.delete")}
                          onClick={() => setConfirmDelete(a.id)}
                        >
                          <Trash size={13} />
                        </button>
                      </>
                    )}
                  </div>
                  {a.description && <div className="mt-1.5 text-[12.5px] text-dim">{a.description}</div>}
                  <div className="mt-2.5 flex items-center gap-3 text-[11.5px] text-faint">
                    {a.systemPrompt && (
                      <span className="truncate max-w-[420px]" title={a.systemPrompt}>
                        {t("agents.promptPrefix")}：{a.systemPrompt}
                      </span>
                    )}
                    {a.extraFlags.length > 0 && (
                      <span className="font-mono truncate" title={a.extraFlags.join(" ")}>
                        {t("agents.argsCount", { n: a.extraFlags.length })}
                      </span>
                    )}
                    <span className="flex-1" />
                    {!isDefault && (
                      <button
                        className="text-[11.5px] text-dim hover:text-accent cursor-pointer bg-transparent border-0 p-0"
                        onClick={() => appStore.setState({ agentId: a.id })}
                      >
                        {t("agents.makeDefault")}
                      </button>
                    )}
                  </div>
                </div>
              );
            })}
            {agents.length === 0 && (
              <div className="card border-dashed p-10 text-center">
                <div className="text-[13.5px] text-dim">{t("agents.empty")}</div>
                <div className="mt-1 text-[12px] text-faint">{t("agents.emptyHint")}</div>
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
