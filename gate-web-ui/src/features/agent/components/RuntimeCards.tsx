import { ArrowClockwise, CheckCircle, Plug, TerminalWindow, WarningCircle } from "@phosphor-icons/react";
import { actions } from "@/app/actions";
import { useApp } from "@/store";
import { CLI_LABEL } from "./labels";

/** 本地 CLI 运行时检测卡（含 opencode 供应商管理入口）。 */
export function RuntimeCards({ onManageProviders }: { onManageProviders: () => void }) {
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
