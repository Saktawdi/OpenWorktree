import { useEffect, useLayoutEffect, useRef, useState } from "react";
import { AnimatePresence, motion } from "motion/react";
import { CaretDown, FolderOpen, GitBranch, Plus, TerminalWindow } from "@phosphor-icons/react";
import "@xterm/xterm/css/xterm.css";
import { Terminal } from "@xterm/xterm";
import { FitAddon } from "@xterm/addon-fit";
import { loadProjectTerminals, openTerminalSocket, activateTerminalSession, closeAllTerminalSessions, closeTerminalSession, minimizeTerminal, openTerminalSession, restoreTerminal } from "@/features/project";
import { showToast, useApp } from "@/store";
import type { Project, TerminalEntry, TerminalSessionMeta } from "@/shared/types";
import { useBackdropClose } from "@/shared/components/ui";

/**
 * 项目终端：入口（项目卡片/工作台"+"）先列出项目所属目录（工作区 + 各工单克隆），
 * 选中后以该目录为工作目录拉起一个终端会话。工作台（TerminalWorkbench）支持多标签、
 * 最小化到顶栏圆钮后台运行——xterm 与 WebSocket 挂在常驻 host 上，最小化只是隐藏。
 * 管道式 shell 无回显，输入编辑与历史在本地补齐；Ctrl+C 由服务端回收子进程模拟中断。
 */

/* ─── 目录选择弹窗 ─── */

/** 选择要打开的目录；project 缺省时（工作台"+"入口）可先选项目。 */
export function TerminalPickerDialog({ project, onClose }: { project?: Project | null; onClose: () => void }) {
  const projects = useApp((s) => s.projects);
  const activeId = useApp((s) => s.activeProjectId);
  const [projectId, setProjectId] = useState(
    project?.id ?? activeId ?? projects[0]?.id ?? "",
  );
  const selected = projects.find((p) => p.id === projectId) ?? null;
  const backdrop = useBackdropClose(onClose);

  const launch = (entry: TerminalEntry) => {
    if (!selected || !entry.exists) return;
    openTerminalSession({
      projectId: selected.id,
      projectName: selected.name,
      dir: entry.path,
      label: entry.label,
    });
    onClose();
  };

  return (
    <div className="fixed inset-0 z-50 grid place-items-center bg-black/55 backdrop-blur-[2px]" {...backdrop}>
      <div
        className="w-[640px] max-w-[92vw] card shadow-2xl shadow-black/60 animate-rise overflow-hidden flex flex-col"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center gap-2 px-4 h-11 border-b border-edge shrink-0">
          <TerminalWindow size={14} className="text-accent" />
          <span className="text-[13px] font-semibold">终端</span>
          <span className="text-[12px] text-dim truncate">{selected ? selected.name : "选择目录"}</span>
          <span className="flex-1" />
          <button className="icon-btn" onClick={onClose} aria-label="关闭">
            <svg width="13" height="13" viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round">
              <path d="M2 2l8 8M10 2l-8 8" />
            </svg>
          </button>
        </div>

        <div className="px-4 pt-3 text-[11.5px] text-faint">
          点击目录即启动终端标签页（克隆目录即工单沙箱，可放心操作）
        </div>

        <div className="px-4 py-3 overflow-y-auto max-h-[400px] space-y-1.5">
          {!project && projects.length > 0 && (
            <select
              className="text-input h-9 w-full mb-1"
              value={projectId}
              onChange={(e) => setProjectId(e.target.value)}
              aria-label="选择项目"
            >
              {projects.map((p) => (
                <option key={p.id} value={p.id}>
                  {p.name}
                </option>
              ))}
            </select>
          )}
          <DirList project={selected} onPick={launch} />
        </div>

        <div className="flex justify-end gap-2 px-4 py-3.5 border-t border-edge">
          <button className="btn" onClick={onClose}>
            取消
          </button>
        </div>
      </div>
    </div>
  );
}

function DirList({ project, onPick }: { project: Project | null; onPick: (e: TerminalEntry) => void }) {
  const [entries, setEntries] = useState<TerminalEntry[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loadingId, setLoadingId] = useState(0);

  useEffect(() => {
    if (!project) return;
    let alive = true;
    setError(null);
    setEntries(null);
    setLoadingId((n) => n + 1);
    loadProjectTerminals(project.id)
      .then((list) => {
        if (alive) setEntries(list);
      })
      .catch((e) => {
        if (alive) setError((e as Error).message);
      });
    return () => {
      alive = false;
    };
  }, [project?.id]);

  if (!project) {
    return <div className="p-6 text-center text-[12.5px] text-faint">暂无项目</div>;
  }
  if (error) {
    return (
      <div className="p-6 text-center">
        <div className="text-[12.5px] text-warn leading-relaxed">{error}</div>
        <button className="btn mt-3" onClick={() => setLoadingId((n) => n + 1)}>
          重试
        </button>
      </div>
    );
  }
  if (entries === null) {
    return <div className="p-6 text-center text-[12.5px] text-faint">目录扫描中…</div>;
  }
  if (entries.length === 0) {
    return <div className="p-6 text-center text-[12.5px] text-faint">项目暂无可用目录</div>;
  }
  void loadingId;
  return (
    <>
      {entries.map((e) => (
        <button
          key={e.path}
          disabled={!e.exists}
          onClick={() => onPick(e)}
          title={e.path}
          className="w-full flex items-center gap-2.5 px-3 h-11 rounded-lg border border-edge text-left transition-colors cursor-pointer hover:border-edge-strong hover:bg-raised/60 disabled:cursor-not-allowed disabled:opacity-45"
        >
          {e.type === "workspace" ? (
            <FolderOpen size={15} className="text-accent shrink-0" />
          ) : (
            <GitBranch size={14} className="text-info shrink-0" />
          )}
          <span
            className={`chip shrink-0 border font-mono text-[10.5px] ${
              e.type === "workspace"
                ? "border-accent/30 bg-accent/10 text-accent"
                : "border-edge-strong bg-raised text-dim"
            }`}
          >
            {e.label}
          </span>
          <span className="font-mono text-[11.5px] text-dim truncate flex-1">{e.path}</span>
          {e.ticketTitle && (
            <span className="text-[11px] text-faint truncate max-w-[160px] hidden sm:inline">{e.ticketTitle}</span>
          )}
          {!e.exists && <span className="chip border border-warn/30 bg-warn/10 text-warn shrink-0">目录不存在</span>}
        </button>
      ))}
    </>
  );
}

/* ─── 终端工作台（全局常驻） ─── */

const DIM = "\x1b[90m";
const RED = "\x1b[31m";
const RESET = "\x1b[0m";

/**
 * 终端多标签工作台。只要还有会话就保持挂载：弹窗用 display 切换（最小化），
 * 非激活 tab 的 xterm 宿主也用 display 隐藏——WebSocket 与 shell 进程全程存活。
 */
export function TerminalWorkbench() {
  const sessions = useApp((s) => s.terminalSessions);
  const activeId = useApp((s) => s.activeTerminalId);
  const view = useApp((s) => s.terminalView);
  const mode = useApp((s) => s.mode);
  const [pickerOpen, setPickerOpen] = useState(false);
  const backdrop = useBackdropClose(minimizeTerminal);

  if (sessions.length === 0) return null;
  const active = sessions.find((t) => t.id === activeId) ?? sessions[sessions.length - 1];

  return (
    <>
      <div
        className={view === "open" ? "fixed inset-0 z-50 grid place-items-center bg-black/55 backdrop-blur-[2px]" : "hidden"}
        {...backdrop}
      >
        <div
          className="w-[960px] max-w-[96vw] card shadow-2xl shadow-black/60 animate-rise overflow-hidden flex flex-col"
          onClick={(e) => e.stopPropagation()}
        >
          {/* 标签条 */}
          <div className="flex items-center gap-1 px-2.5 h-11 border-b border-edge shrink-0">
            <div className="flex items-center gap-1 min-w-0 flex-1 overflow-x-auto">
              <AnimatePresence initial={false}>
                {sessions.map((t) => (
                  <motion.div
                    key={t.id}
                    initial={{ opacity: 0, scale: 0.85 }}
                    animate={{ opacity: 1, scale: 1 }}
                    exit={{ opacity: 0, scale: 0.85 }}
                    transition={{ type: "spring", stiffness: 520, damping: 32 }}
                    onClick={() => activateTerminalSession(t.id)}
                    title={t.dir}
                    className={`flex items-center gap-1.5 h-7 pl-2.5 pr-1 rounded-md border text-[11.5px] font-mono cursor-pointer shrink-0 ${
                      t.id === active?.id
                        ? "bg-raised text-ink border-edge"
                        : "text-dim border-transparent hover:bg-raised/60"
                    }`}
                  >
                    <TerminalWindow size={11} className={t.id === active?.id ? "text-accent" : "text-faint"} />
                    <span className="truncate max-w-[130px]">{t.label}</span>
                    <button
                      className="w-4 h-4 grid place-items-center rounded text-faint hover:text-danger hover:bg-danger/10 border-0 bg-transparent p-0 cursor-pointer"
                      onClick={(e) => {
                        e.stopPropagation();
                        closeTerminalSession(t.id);
                      }}
                      aria-label={`关闭 ${t.label} 终端`}
                    >
                      <svg width="8" height="8" viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
                        <path d="M2 2l8 8M10 2l-8 8" />
                      </svg>
                    </button>
                  </motion.div>
                ))}
              </AnimatePresence>
              <button
                className="w-7 h-7 grid place-items-center rounded-md text-dim hover:text-ink hover:bg-raised border border-transparent bg-transparent cursor-pointer shrink-0"
                title="新建终端标签"
                aria-label="新建终端标签"
                onClick={() =>
                  mode === "live"
                    ? setPickerOpen(true)
                    : showToast("终端需要连接本地后端（live 模式）后使用")
                }
              >
                <Plus size={14} />
              </button>
            </div>
            <span className="hidden lg:inline font-mono text-[10.5px] text-faint truncate max-w-[240px]" title={active?.dir}>
              {active?.dir}
            </span>
            <button className="icon-btn shrink-0" title="最小化到后台（进程保持运行）" aria-label="最小化终端" onClick={minimizeTerminal}>
              <CaretDown size={13} />
            </button>
            <button
              className="icon-btn shrink-0"
              title="关闭全部终端并结束进程"
              aria-label="关闭全部终端"
              onClick={closeAllTerminalSessions}
            >
              <svg width="13" height="13" viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round">
                <path d="M2 2l8 8M10 2l-8 8" />
              </svg>
            </button>
          </div>

          {/* 会话宿主：全部常驻挂载，仅切换可见性 */}
          <div className="relative h-[540px] max-h-[70vh]">
            {sessions.map((t) => (
              <TerminalSessionHost key={t.id} meta={t} visible={t.id === active?.id} />
            ))}
          </div>
        </div>
      </div>
      {pickerOpen && <TerminalPickerDialog onClose={() => setPickerOpen(false)} />}
    </>
  );
}

/** 单个终端会话：一条 WebSocket + 一个 xterm 实例，随会话存在而常驻。 */
function TerminalSessionHost({ meta, visible }: { meta: TerminalSessionMeta; visible: boolean }) {
  const hostRef = useRef<HTMLDivElement | null>(null);
  const termRef = useRef<Terminal | null>(null);
  const fitRef = useRef<FitAddon | null>(null);

  useEffect(() => {
    const host = hostRef.current;
    if (!host) return;

    const term = new Terminal({
      fontFamily: '"Geist Mono Variable", ui-monospace, Consolas, monospace',
      fontSize: 12.5,
      lineHeight: 1.35,
      cursorBlink: true,
      convertEol: true,
      scrollback: 4000,
      theme: {
        background: "#0d1117",
        foreground: "#c9d4cf",
        cursor: "#35d99e",
        selectionBackground: "#3a4a44",
      },
    });
    const fit = new FitAddon();
    termRef.current = term;
    fitRef.current = fit;
    term.loadAddon(fit);
    term.open(host);
    try {
      fit.fit();
    } catch {
      /* 布局未就绪时忽略 */
    }
    if (visible) {
      term.focus();
    }

    // 管道 shell 不回显输入：行编辑、回显与历史都在本地补齐
    const lineRef = { current: "" };
    const historyRef: { current: string[] } = { current: [] };
    const histIdxRef = { current: -1 };
    let disposed = false;
    let ws: WebSocket | null = null;

    const replaceTyped = (next: string) => {
      term.write(`\x1b[${lineRef.current.length}D\x1b[K${next}`);
      lineRef.current = next;
    };

    const sendLine = (text: string) => {
      ws?.send(JSON.stringify({ op: "input", data: text }));
    };

    term.onData((data) => {
      if (data === "\r") {
        term.write("\r\n");
        const line = lineRef.current;
        lineRef.current = "";
        histIdxRef.current = -1;
        if (line.trim() && historyRef.current[historyRef.current.length - 1] !== line) {
          historyRef.current.push(line);
          if (historyRef.current.length > 100) historyRef.current.shift();
        }
        sendLine(line + "\r\n");
        return;
      }
      if (data === "\u007f") {
        if (lineRef.current.length > 0) {
          lineRef.current = lineRef.current.slice(0, -1);
          term.write("\b \b");
        }
        return;
      }
      if (data === "\u0003") {
        lineRef.current = "";
        histIdxRef.current = -1;
        sendLine("\u0003");
        return;
      }
      if (data === "\x1b[A" || data === "\x1b[B") {
        const hist = historyRef.current;
        if (data === "\x1b[A") {
          if (hist.length === 0) return;
          histIdxRef.current = histIdxRef.current === -1 ? hist.length - 1 : Math.max(0, histIdxRef.current - 1);
          replaceTyped(hist[histIdxRef.current]);
        } else {
          if (histIdxRef.current === -1) return;
          histIdxRef.current -= 1;
          replaceTyped(histIdxRef.current === -1 ? "" : hist[histIdxRef.current]);
        }
        return;
      }
      if (data === "\t" || data.startsWith("\x1b")) return;
      if (data >= " " || data.length > 1) {
        lineRef.current += data;
        term.write(data);
      }
    });

    ws = openTerminalSocket(meta.projectId, meta.dir, {
      onStarted: () => {
        term.writeln(`${DIM}-- 终端已连接：${meta.dir}（Ctrl+C 中断当前命令）--${RESET}`);
      },
      onData: (text) => term.write(text),
      onExit: (code) => {
        term.writeln(`${DIM}-- 进程已退出（code ${code}）· 重新打开目录可重启终端 --${RESET}`);
      },
      onError: (message) => {
        term.writeln(`${RED}-- 错误：${message} --${RESET}`);
      },
    });
    ws.onclose = () => {
      if (!disposed) {
        term.writeln(`${DIM}-- 连接已断开 --${RESET}`);
      }
    };

    const onResize = () => {
      if (!visible) return;
      try {
        fit.fit();
      } catch {
        /* ignore */
      }
    };
    window.addEventListener("resize", onResize);

    return () => {
      disposed = true;
      window.removeEventListener("resize", onResize);
      try {
        ws?.close();
      } catch {
        /* ignore */
      }
      term.dispose();
      termRef.current = null;
      fitRef.current = null;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [meta.id]);

  // 从隐藏恢复为可见（切 tab / 最小化恢复）时重排尺寸并聚焦
  useLayoutEffect(() => {
    if (!visible) return;
    try {
      fitRef.current?.fit();
    } catch {
      /* ignore */
    }
    termRef.current?.focus();
  }, [visible]);

  return (
    <div
      ref={hostRef}
      className={visible ? "absolute inset-0 px-1.5 py-1 bg-[#0d1117]" : "hidden"}
    />
  );
}

/** 最小化时的顶栏胶囊 chip：放在连接状态 chip 左边，点击恢复工作台。 */
export function TerminalMinimizedChip() {
  const sessions = useApp((s) => s.terminalSessions);
  const view = useApp((s) => s.terminalView);
  const minimized = sessions.length > 0 && view === "minimized";

  return (
    <AnimatePresence>
      {minimized && (
        <motion.button
          key="terminal-minimized-chip"
          initial={{ scale: 0.6, opacity: 0, y: -8 }}
          animate={{ scale: 1, opacity: 1, y: 0 }}
          exit={{ scale: 0.6, opacity: 0, y: -8 }}
          transition={{ type: "spring", stiffness: 480, damping: 26 }}
          onClick={restoreTerminal}
          title={sessions.map((t) => `${t.projectName} · ${t.label}\n${t.dir}`).join("\n——\n")}
          aria-label="恢复终端"
          className="inline-flex items-center gap-1.5 h-7 px-2.5 rounded-full border border-accent/40 bg-accent/10 text-accent cursor-pointer hover:bg-accent/20 transition-colors"
        >
          <TerminalWindow size={12} weight="fill" />
          <span className="text-[11.5px] font-medium">终端</span>
          {sessions.length > 1 && (
            <span className="font-mono text-[10px] leading-none px-1.5 py-0.5 rounded-full bg-accent/20">
              {sessions.length}
            </span>
          )}
        </motion.button>
      )}
    </AnimatePresence>
  );
}
