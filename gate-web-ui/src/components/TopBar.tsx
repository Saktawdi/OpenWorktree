import { Kanban, SquaresFour, GearSix, FolderOpen } from "@phosphor-icons/react";
import { appStore, openConnect, setView, useApp } from "../lib/store";
import { LogoMark } from "./ui";

function ViewSwitch() {
  const view = useApp((s) => s.view);
  const item = (key: "workbench" | "kanban", label: string, Icon: typeof Kanban) => (
    <button
      key={key}
      onClick={() => setView(key)}
      className={`inline-flex items-center gap-1.5 h-7 px-3 rounded-md text-[12.5px] font-medium transition-colors cursor-pointer ${
        view === key ? "bg-raised text-ink shadow-sm border border-edge" : "text-dim hover:text-ink border border-transparent"
      }`}
    >
      <Icon size={14} weight={view === key ? "fill" : "regular"} />
      {label}
    </button>
  );
  return (
    <div className="flex items-center gap-0.5 bg-sunken rounded-lg p-0.5 border border-edge">
      {item("workbench", "工作台", SquaresFour)}
      {item("kanban", "看板", Kanban)}
    </div>
  );
}

export function TopBar() {
  const conn = useApp((s) => s.conn);
  const mode = useApp((s) => s.mode);
  const project = useApp((s) => s.projects.find((p) => p.id === s.activeProjectId));

  const connLabel =
    mode === "live"
      ? conn === "ok"
        ? "已连接后端"
        : conn === "unauth"
          ? "待授权"
          : "连接异常"
      : "演示数据";
  const connColor =
    mode === "live" && conn === "ok"
      ? "text-accent border-accent/30 bg-accent/10"
      : mode === "live"
        ? "text-warn border-warn/30 bg-warn/10"
        : "text-dim border-edge-strong bg-raised";

  return (
    <header className="h-13 shrink-0 flex items-center gap-4 px-4 border-b border-edge bg-panel">
      <div className="flex items-center gap-2.5 min-w-0">
        <LogoMark />
        <span className="font-semibold tracking-tight text-[15px]">Gate</span>
        <span className="hidden lg:inline text-[12px] text-faint border-l border-edge pl-3 ml-1">
          本地 Git 门禁工作台
        </span>
      </div>

      <div className="ml-2">
        <ViewSwitch />
      </div>

      <div className="flex-1" />

      {project && (
        <button
          className="hidden md:inline-flex items-center gap-1.5 h-7 px-2.5 rounded-md text-[12.5px] text-dim hover:text-ink hover:bg-raised transition-colors cursor-pointer"
          title={project.workspacePath}
        >
          <FolderOpen size={14} />
          {project.name}
        </button>
      )}

      <button
        onClick={openConnect}
        className={`chip border cursor-pointer transition-opacity hover:opacity-80 ${connColor}`}
        title="连接设置"
      >
        <span
          className={`inline-block w-1.5 h-1.5 rounded-full ${
            mode === "live" && conn === "ok" ? "bg-accent" : mode === "live" ? "bg-warn animate-breathe" : "bg-dim"
          }`}
        />
        {connLabel}
      </button>

      <button className="icon-btn" onClick={openConnect} title="设置" aria-label="设置">
        <GearSix size={16} />
      </button>
    </header>
  );
}

export function openConnectionSettings() {
  appStore.setState({ connectOpen: true });
}
