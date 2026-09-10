import { useCallback, useEffect, useState } from "react";
import { PlugsConnected, WarningCircle } from "@phosphor-icons/react";
import { fetchMcpStatus } from "@/features/settings";
import { useT } from "@/i18n";
import type { McpStatus } from "@/shared/types";
import { CopyButton, Spinner } from "@/shared/components/ui";

/** MCP 状态区块：服务配置、CLI 注入方式与工具清单。 */
export function McpBlock() {
  const t = useT();
  const [data, setData] = useState<McpStatus | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true); setError(null);
    try { setData(await fetchMcpStatus()); } catch (e) { setError((e as Error).message); }
    finally { setLoading(false); }
  }, []);

  useEffect(() => { void load(); }, [load]);

  if (loading) return <div className="card p-8 flex items-center gap-2 text-[12.5px] text-faint"><Spinner /> {t("mcp.loading")}</div>;
  if (error) return <div className="card p-6"><div className="text-[13px] text-danger flex items-center gap-1.5"><WarningCircle size={14} weight="fill" /> {t("mcp.loadFailed")}：{error}</div><button className="btn mt-3" onClick={() => void load()}>{t("common.retry")}</button></div>;
  if (!data) return null;

  const provisioningColor = data.provisioning === "enabled" ? "border-accent/30 bg-accent/10 text-accent" : "border-warn/30 bg-warn/10 text-warn";

  return (
    <div className="space-y-4">
      <div className="card px-4 py-3.5 flex flex-wrap items-center gap-2.5">
        <span className={`w-6 h-6 rounded-md grid place-items-center border shrink-0 ${data.provisioning === "enabled" ? "bg-accent-dim border-accent/30 text-accent" : "bg-warn-dim border-warn/30 text-warn"}`}>
          <PlugsConnected size={13} />
        </span>
        <span className="text-[13px] font-semibold">{t("mcp.servers")}</span>
        <span className={`chip border ${provisioningColor}`}>{data.provisioning}</span>
        <span className="chip border border-edge-strong bg-raised text-dim font-mono text-[11px]">{data.transport}</span>
        <span className="flex-1" />
        <span className="text-[11.5px] text-faint">{t("mcp.agentTools")} <span className="font-mono text-ink">{data.agent_tool_count}</span> · {t("mcp.humanTools")} <span className="font-mono text-ink">{data.human_tool_count}</span></span>
      </div>

      <div className="grid md:grid-cols-2 gap-4">
        <div className="card p-4">
          <div className="field-label">{t("mcp.serveCommand")}</div>
          <div className="flex items-center gap-1.5 mt-0.5">
            <code className="flex-1 min-w-0 font-mono text-[11.5px] leading-relaxed bg-sunken border border-edge rounded-lg px-3 py-2 break-all text-dim">{data.serve_command}</code>
            <CopyButton text={data.serve_command} label={t("mcp.copyServeCommand")} />
          </div>
        </div>
        <div className="card p-4">
          <div className="field-label">{t("mcp.cliIntegration")}</div>
          <div className="mt-0.5 grid gap-1.5 max-h-[92px] overflow-y-auto">
            {data.cli_integration.map((it) => (
              <div key={it.cli} className="flex gap-2.5 items-start rounded-lg border border-edge bg-sunken px-2.5 py-2">
                <span className="chip border border-accent/30 bg-accent/10 text-accent shrink-0">{it.cli}</span>
                <span className="text-[12px] leading-relaxed text-dim min-w-0">{it.mechanism}</span>
              </div>
            ))}
            {data.cli_integration.length === 0 && <div className="text-[12.5px] text-faint">{t("mcp.noCliIntegration")}</div>}
          </div>
        </div>
      </div>

      <div className="card overflow-hidden">
        <div className="px-4 h-9 flex items-center gap-2 border-b border-edge bg-raised/40">
          <span className="text-[12.5px] font-semibold">{t("mcp.tools")}</span>
          <span className="chip border border-edge-strong bg-sunken text-faint">{t("mcp.toolCount", { n: data.tools.length })}</span>
        </div>
        {data.tools.length === 0 ? (
          <div className="p-8 text-center text-[12.5px] text-faint">{t("mcp.noTools")}</div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-left">
              <thead>
                <tr className="border-b border-edge bg-sunken/50">
                  <th className="px-4 py-2 text-[11px] font-semibold text-faint uppercase tracking-wider">{t("common.name")}</th>
                  <th className="px-4 py-2 text-[11px] font-semibold text-faint uppercase tracking-wider">{t("mcp.domain")}</th>
                  <th className="px-4 py-2 text-[11px] font-semibold text-faint uppercase tracking-wider">{t("common.description")}</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-edge">
                {data.tools.map((t) => (
                  <tr key={t.name} className="hover:bg-raised/40 transition-colors">
                    <td className="px-4 py-2 font-mono text-[12.5px] text-ink whitespace-nowrap">{t.name}</td>
                    <td className="px-4 py-2">
                      <span className={`chip border text-[10.5px] ${t.domain === "agent" ? "border-accent/30 bg-accent/10 text-accent" : "border-info/30 bg-info/10 text-info"}`}>{t.domain.toUpperCase()}</span>
                    </td>
                    <td className="px-4 py-2 text-[12.5px] text-dim max-w-[420px] break-words">{t.description || "—"}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  );
}
