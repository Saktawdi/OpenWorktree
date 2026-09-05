import { useEffect, useRef, useState } from "react";
import { motion } from "motion/react";
import {
  CaretDown,
  Check,
  CornersIn,
  CornersOut,
  FolderOpen,
  GearSix,
  Kanban,
  Minus,
  Plus,
  SquaresFour,
  Sparkle,
  FolderPlus,
  Sun,
  Moon,
  X,
} from "@phosphor-icons/react";
import { appStore, openConnect, setView, switchProject, toggleTheme, useApp } from "@/store";
import { RunMonitor } from "@/app/components/RunMonitor";
import { TerminalMinimizedChip } from "@/features/project/components/ProjectTerminal";

/** 主题切换动画时长（抽帧加速版：54 帧 × 37ms ≈ 2.0s，留少量余量）。 */
const ANIM_MS = 2050;

/**
 * 左上角品牌徽章：主题切换时播放「由昼入夜 / 由夜入昼」的 OW 过渡动画，
 * 播完停在目标主题的静态徽章。prefers-reduced-motion 时直接换静态图。
 */
function BrandMark() {
  const theme = useApp((s) => s.theme);
  const prev = useRef(theme);
  const [anim, setAnim] = useState<{ toLight: boolean; nonce: number } | null>(null);

  useEffect(() => {
    if (prev.current === theme) return;
    prev.current = theme;
    if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) return;
    setAnim({ toLight: theme === "light", nonce: Date.now() });
  }, [theme]);

  useEffect(() => {
    if (!anim) return;
    const t = window.setTimeout(() => setAnim(null), ANIM_MS);
    return () => window.clearTimeout(t);
  }, [anim]);

  const src = anim
    ? `/brand/ow-theme-switch${anim.toLight ? "-reverse" : ""}.webp?v=${anim.nonce}`
    : theme === "dark"
      ? "/brand/ow-dark-badge-64.png"
      : "/brand/ow-light-badge-64.png";
  return (
    <img
      src={src}
      alt=""
      width={20}
      height={20}
      draggable={false}
      aria-hidden
      className="select-none shrink-0"
    />
  );
}

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

/** 桌面壳判定：仅当以壳内 iframe 身份运行（boot 时消费过 ow-token）。浏览器直开为 false。 */
function isDesktopShell(): boolean {
  try {
    return window.parent !== window && sessionStorage.getItem("ow-desktop-token") !== null;
  } catch {
    return false;
  }
}

/** 桌面壳窗口操作桥：SPA 在远端 origin 拿不到 Tauri API，经 postMessage 交给
 *  tauri:// 父窗口（启动页）执行。消息不带敏感数据，targetOrigin 用 *。 */
function shellPost(action: string) {
  window.parent.postMessage({ __ow: true, action }, "*");
}

/** 桌面壳窗口控制：最小化/最大化/关闭（紧挨日夜切换，同为 icon-btn 风格）。
 *  关闭钮悬停红，对齐 Windows 标题栏惯例；浏览器/非壳环境整组不渲染。
 *  兼承载托盘桥：收「打开项目工作台」、发「当前选中项目」（托盘绿点数据源）。 */
function WindowControls() {
  const [maximized, setMaximized] = useState(false);
  const desktop = isDesktopShell();
  useEffect(() => {
    if (!desktop) return;
    shellPost("query-maximized");
    const onMessage = (e: MessageEvent) => {
      const d = e.data as { __ow?: boolean; action?: string; value?: boolean; projectId?: string };
      if (!d?.__ow) return;
      if (d.action === "maximized") {
        setMaximized(!!d.value);
      } else if (d.action === "open-project" && d.projectId) {
        // 托盘点项目：与顶栏项目选择器同语义（switchProject 选首个非终态工单），
        // 外加切回工作台视图——托盘入口的用户意图就是"去那个项目的工单界面"。
        switchProject(d.projectId);
        setView("workbench");
      }
    };
    // 选中项目 → 壳 → Rust 托盘画绿点；主题 → 壳 → 托盘面板换肤。仅值变化时推送，
    // 避免高频 store 变更刷爆 postMessage。
    let lastProjectId = appStore.getState().activeProjectId;
    let lastTheme = appStore.getState().theme;
    const pushSelected = () =>
      window.parent.postMessage(
        { __ow: true, action: "tray-selected-project", projectId: lastProjectId },
        "*",
      );
    const pushTheme = () =>
      window.parent.postMessage({ __ow: true, action: "tray-theme", theme: lastTheme }, "*");
    pushSelected();
    pushTheme();
    const unsub = appStore.subscribe((st) => {
      if (st.activeProjectId !== lastProjectId) {
        lastProjectId = st.activeProjectId;
        pushSelected();
      }
      if (st.theme !== lastTheme) {
        lastTheme = st.theme;
        pushTheme();
      }
    });
    window.addEventListener("message", onMessage);
    return () => {
      unsub();
      window.removeEventListener("message", onMessage);
    };
  }, [desktop]);
  if (!desktop) return null;
  return (
    <>
      <button className="icon-btn" onClick={() => shellPost("minimize")} title="最小化" aria-label="最小化">
        <Minus size={16} />
      </button>
      <button
        className="icon-btn"
        onClick={() => shellPost("toggle-maximize")}
        title={maximized ? "向下还原" : "最大化"}
        aria-label={maximized ? "向下还原" : "最大化"}
      >
        {maximized ? <CornersIn size={16} /> : <CornersOut size={16} />}
      </button>
      <button
        className="icon-btn hover:text-danger hover:bg-danger/10 active:scale-95"
        onClick={() => shellPost("close")}
        title="关闭"
        aria-label="关闭"
      >
        <X size={16} />
      </button>
    </>
  );
}

export function TopBar() {
  const conn = useApp((s) => s.conn);
  const mode = useApp((s) => s.mode);

  // 无边框窗口：顶栏空白区拖拽移动 + 双击切换最大化（经 postMessage 桥交给壳执行）；
  // 交互元素上不响应；非壳环境（浏览器直开）不做任何事。
  const onFrameMouseDown = (e: React.MouseEvent) => {
    if (e.button !== 0 || !isDesktopShell()) return;
    const target = e.target as HTMLElement;
    if (target.closest("button, a, input, select, textarea, [role='menu']")) return;
    shellPost("start-drag");
  };
  const onFrameDoubleClick = (e: React.MouseEvent) => {
    if (!isDesktopShell()) return;
    if ((e.target as HTMLElement).closest("button, a, input, select, textarea, [role='menu']")) return;
    shellPost("toggle-maximize");
  };

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
    <header
      className="relative h-13 shrink-0 flex items-center gap-4 px-4 border-b border-edge bg-surface select-none"
      onMouseDown={onFrameMouseDown}
      onDoubleClick={onFrameDoubleClick}
    >
      {/* 限宽 244px：顶栏 px-4 的左内边距 + 8px 间隙后，右缘恰好压在 268px 基准线内
          （ViewSwitch 绝对定位处，与 aside 对齐）；品牌簇越界会与之重叠，
          超出时由运行监控 chip 截断兜底。 */}
      <div className="flex items-center gap-1.5 min-w-0 max-w-[244px]">
        <BrandMark />
        <span className="font-semibold tracking-tight text-[14px] shrink-0">OpenWorktree</span>
        <RunMonitor />
      </div>

      {/* 左缘与工单侧栏（aside w-[268px]）右缘对齐；绝对定位以避开左侧徽标/项目名称的宽度波动 */}
      <div className="hidden md:block absolute left-[268px] top-1/2 -translate-y-1/2">
        <ViewSwitch />
      </div>

      <div className="flex-1" />

      <ProjectSwitcher />

      {/* 最小化到后台的终端会话（进程保持运行，点击恢复工作台） */}
      <TerminalMinimizedChip />

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

      <button className="icon-btn hidden sm:inline-flex" onClick={() => setView("settings")} title="设置" aria-label="设置">
        <GearSix size={16} />
      </button>
      <ThemeToggle />
      <WindowControls />
    </header>
  );
}

export function openConnectionSettings() {
  appStore.setState({ connectOpen: true });
}
