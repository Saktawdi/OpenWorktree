import { useState } from "react";
import { motion } from "motion/react";
import {
  CaretDown,
  Check,
  FolderOpen,
  GearSix,
  Kanban,
  Plus,
  SquaresFour,
  Sparkle,
  FolderPlus,
  Sun,
  Moon,
} from "@phosphor-icons/react";
import { appStore, openConnect, setView, switchProject, toggleTheme, useApp } from "../lib/store";
import { LogoMark } from "./ui";

const VIEWS = [
  { key: "workbench", label: "工作台", Icon: SquaresFour },
  { key: "kanban", label: "看板", Icon: Kanban },
  { key: "projects", label: "项目", Icon: FolderOpen },
  { key: "agents", label: "智能体", Icon: Sparkle },
] as const;

function ViewSwitch() {
  const view = useApp((s) => s.view);
  return (
    <div className="flex items-center gap-0.5 bg-sunken rounded-lg p-0.5 border border-edge">
      {VIEWS.map(({ key, label, Icon }) => (
        <button
          key={key}
          onClick={() => setView(key)}
          className={`relative inline-flex items-center gap-1.5 h-7 px-3 rounded-md text-[12.5px] font-medium cursor-pointer ${
            view === key ? "text-ink" : "text-dim hover:text-ink"
          }`}
        >
          {view === key && (
            <motion.div
              layoutId="view-switch-active"
              className="absolute inset-0 bg-raised rounded-md border border-edge shadow-sm"
              transition={{ type: "spring", stiffness: 500, damping: 35 }}
            />
          )}
          <span className="relative z-10 inline-flex items-center gap-1.5">
            <Icon size={14} weight={view === key ? "fill" : "regular"} />
            {label}
          </span>
        </button>
      ))}
    </div>
  );
}

function ProjectSwitcher() {
  const projects = useApp((s) => s.projects);
  const activeId = useApp((s) => s.activeProjectId);
  const [open, setOpen] = useState(false);
  const active = projects.find((p) => p.id === activeId);

  if (!active) {
    return (
      <button
        onClick={() => setView("projects")}
        className="inline-flex items-center gap-1.5 h-7 px-2.5 rounded-md text-[12.5px] text-warn hover:bg-raised transition-colors cursor-pointer"
        title="尚未接入项目，点击前往接入"
      >
        <FolderPlus size={14} />
        接入项目
      </button>
    );
  }

  return (
    <div className="relative">
      <button
        onClick={() => setOpen(!open)}
        className="inline-flex items-center gap-1.5 h-7 px-2.5 rounded-md text-[12.5px] text-dim hover:text-ink hover:bg-raised transition-colors cursor-pointer"
        title={active.workspacePath}
      >
        <FolderOpen size={14} className="text-faint" />
        {active.name}
        <CaretDown size={11} className={`transition-transform ${open ? "rotate-180" : ""}`} />
      </button>
      {open && (
        <>
          <div className="fixed inset-0 z-30" onClick={() => setOpen(false)} />
          <motion.div
            initial={{ opacity: 0, y: -4, scale: 0.97 }}
            animate={{ opacity: 1, y: 0, scale: 1 }}
            transition={{ type: "spring", stiffness: 500, damping: 30 }}
            className="absolute right-0 top-9 z-40 w-[280px] card p-1.5 shadow-xl shadow-black/50"
          >
            <div className="kicker px-2.5 pt-1.5 pb-1">切换项目</div>
            {projects.map((p) => (
              <button
                key={p.id}
                className={`w-full flex items-center gap-2 px-2.5 h-9 rounded-lg text-left text-[12.5px] cursor-pointer transition-colors ${
                  p.id === activeId ? "bg-raised text-ink" : "text-dim hover:bg-raised hover:text-ink"
                }`}
                onClick={() => {
                  switchProject(p.id);
                  setOpen(false);
                }}
              >
                <FolderOpen size={13} className="text-faint shrink-0" />
                <span className="truncate">{p.name}</span>
                <span className="flex-1" />
                {p.id === activeId && <Check size={13} className="text-accent" weight="bold" />}
              </button>
            ))}
            <div className="divider my-1.5" />
            <button
              className="w-full flex items-center gap-2 px-2.5 h-9 rounded-lg text-left text-[12.5px] text-dim hover:text-accent hover:bg-accent/10 transition-colors cursor-pointer"
              onClick={() => {
                setView("projects");
                setOpen(false);
                window.setTimeout(() => window.dispatchEvent(new CustomEvent("gate:new-project")), 60);
              }}
            >
              <Plus size={14} weight="bold" />
              接入新项目…
            </button>
          </motion.div>
        </>
      )}
    </div>
  );
}

function ThemeToggle() {
  const theme = useApp((s) => s.theme);
  return (
    <motion.button
      className="icon-btn"
      onClick={toggleTheme}
      title={theme === "dark" ? "切换亮色模式" : "切换暗色模式"}
      aria-label={theme === "dark" ? "切换亮色模式" : "切换暗色模式"}
      whileTap={{ scale: 0.9, rotate: 15 }}
      transition={{ type: "spring", stiffness: 400, damping: 15 }}
    >
      <motion.div
        key={theme}
        initial={{ rotate: -90, opacity: 0 }}
        animate={{ rotate: 0, opacity: 1 }}
        transition={{ type: "spring", stiffness: 300, damping: 20 }}
      >
        {theme === "dark" ? <Sun size={16} /> : <Moon size={16} />}
      </motion.div>
    </motion.button>
  );
}

function RunningAgentsBadge() {
  const mode = useApp((s) => s.mode);
  const conn = useApp((s) => s.conn);
  const runningAgents = useApp((s) => s.runningAgents);
  // 未连接后端：灰色破折号
  if (mode !== "live" || conn !== "ok") {
    return (
      <span
        className="hidden xl:inline text-[12px] text-faint border-l border-edge pl-3 ml-1"
        title="未连接后端"
      >
        —
      </span>
    );
  }
  const count = runningAgents.count;
  const sessions = runningAgents.sessions;
  // 构建 tooltip：工单号 · 标题（cli），最多 5 条
  const title = (() => {
    if (count === 0 || sessions.length === 0) return "暂无运行中的智能体";
    const lines = sessions.slice(0, 5).map((s) => {
      const no = s.ticket_no ?? "—";
      const ttl = s.title ?? "无标题";
      const cli = s.cli ? `（${s.cli}）` : "";
      return `${no} · ${ttl}${cli}`;
    });
    if (sessions.length > 5) lines.push(`等 ${sessions.length - 5} 个`);
    return lines.join("\n");
  })();
  if (count === 0) {
    return (
      <span
        className="hidden xl:inline text-[12px] text-faint border-l border-edge pl-3 ml-1"
        title={title}
      >
        0 个智能体运行
      </span>
    );
  }
  return (
    <span
      className="hidden xl:inline-flex text-[12px] text-accent border-l border-edge pl-3 ml-1 items-center gap-1.5"
      title={title}
    >
      <span className="inline-block w-1.5 h-1.5 rounded-full bg-accent animate-breathe shrink-0" />
      {count} 个智能体运行中
    </span>
  );
}

export function TopBar() {
  const conn = useApp((s) => s.conn);
  const mode = useApp((s) => s.mode);

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
    <header className="h-13 shrink-0 flex items-center gap-4 px-4 border-b border-edge bg-surface">
      <div className="flex items-center gap-2.5 min-w-0">
        <LogoMark />
        <span className="font-semibold tracking-tight text-[15px]">Gate</span>
        <RunningAgentsBadge />
      </div>

      <div className="hidden md:block">
        <ViewSwitch />
      </div>

      <div className="flex-1" />

      <ProjectSwitcher />

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
        <span className="hidden sm:inline">{connLabel}</span>
      </button>

      <button className="icon-btn hidden sm:inline-flex" onClick={openConnect} title="设置" aria-label="设置">
        <GearSix size={16} />
      </button>
      <ThemeToggle />
    </header>
  );
}

export function openConnectionSettings() {
  appStore.setState({ connectOpen: true });
}
