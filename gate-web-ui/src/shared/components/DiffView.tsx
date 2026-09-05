import { memo, useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { ReactNode } from "react";
import { CaretDown, CaretUp, FileCode, Warning } from "@phosphor-icons/react";
import { NO_DIFF, useApp } from "@/store";
import type { DiffFile, DiffHunk } from "@/shared/types";
import { diffTotals } from "@/shared/diff";

/* 超大 diff（如未加入 git 忽略的 node_modules 整目录入库）下渲染层根治：
   文件列表采用窗口化虚拟渲染——只挂载视口 ± 缓冲范围内的文件块，滚出即卸载，
   任意规模（数万文件/数十万行）DOM 规模都保持恒定；文件内部行仍按批展开。 */
const FILE_LINES_PER_BATCH = 2000; // 单个文件展开时单批追加的行数（FileBlock 内部状态）
const ROW_GAP = 12; // 文件块之间的垂直间距（替代原 space-y-3）
const COLLAPSED_ROW_H = 42; // 折叠态固定高度：h-10 头部 40px + 卡片上下边框 2px
const OVERSCAN_PX = 2400; // 视口上下各多挂载的缓冲高度（滚速越快缓冲越大）
const EST_HEADER_H = 25; // 估算展开高：hunk 头行
const EST_MORE_BAR_H = 33; // 估算展开高："展开剩余 N 行"栏

/** 按行数上限裁剪 hunks：超过部分丢弃；返回保留的 hunks 与实际行数。 */
function sliceHunks(hunks: DiffHunk[], cap: number): { hunks: DiffHunk[]; shown: number } {
  const out: DiffHunk[] = [];
  let shown = 0;
  for (const h of hunks) {
    if (shown >= cap) break;
    const take = Math.min(h.lines.length, cap - shown);
    out.push(take < h.lines.length ? { ...h, lines: h.lines.slice(0, take) } : h);
    shown += take;
  }
  return { hunks: out, shown };
}

/** 审查目标行（add 行的 newNo）是文件展开序列里的第几条渲染行；找不到返回 0（无需强制展开）。 */
function revealLineAt(f: DiffFile, line: number): number {
  let idx = 0;
  for (const h of f.hunks) {
    for (const l of h.lines) {
      if (l.type === "add" && l.newNo === line) return idx + 1;
      idx++;
    }
  }
  return 0;
}

const FileBlock = memo(
  function FileBlock({
    file,
    highlightLine,
    open,
    onToggle,
    minLines,
  }: {
    file: DiffFile;
    highlightLine?: number;
    open: boolean;
    onToggle: (path: string) => void;
    /** 审查跳转要求文件至少展开到该行数（配合文件内行分片也能定位到目标行）。 */
    minLines?: number;
  }) {
    const [cap, setCap] = useState(FILE_LINES_PER_BATCH);
    const effCap = Math.max(cap, minLines ?? 0);
    const { hunks, shown } = useMemo(() => sliceHunks(file.hunks, effCap), [file, effCap]);
    const total = file.hunks.reduce((n, h) => n + h.lines.length, 0);
    const more = total - shown;
    return (
      <div className="border border-edge rounded-xl overflow-hidden bg-panel">
        <button
          className="w-full flex items-center gap-2.5 px-3.5 h-10 border-b border-edge bg-raised/50 hover:bg-raised transition-colors cursor-pointer text-left"
          onClick={() => onToggle(file.path)}
        >
          <FileCode size={14} className="text-dim shrink-0" />
          <span className="font-mono text-[12.5px] text-ink truncate">{file.path}</span>
          <span
            className={`chip border shrink-0 ${
              file.status === "added"
                ? "border-accent/30 bg-accent/10 text-accent"
                : "border-info/25 bg-info/10 text-info"
            }`}
          >
            {file.status === "added" ? "新增" : "修改"}
          </span>
          <span className="flex-1" />
          <span className="font-mono text-[11.5px] tabular-nums">
            <span className="text-accent">+{file.additions}</span>{" "}
            <span className="text-danger">−{file.deletions}</span>
          </span>
          {open ? (
            <CaretUp size={12} className="text-faint" />
          ) : (
            <CaretDown size={12} className="text-faint" />
          )}
        </button>
        {open && (
          <div className="overflow-x-auto">
            <div className="min-w-max">
              {hunks.map((h, hi) => (
                <div key={hi}>
                  <div className="px-3 py-1 font-mono text-[11px] text-faint bg-sunken border-y border-edge">
                    {h.header}
                  </div>
                  {h.lines.map((l, li) => {
                    const isHighlight =
                      highlightLine !== undefined &&
                      l.type === "add" &&
                      l.newNo === highlightLine;
                    return (
                      <div
                        key={li}
                        data-line={l.newNo ? `${file.path}:${l.newNo}` : undefined}
                        className={`flex font-mono text-[12px] leading-[19px] whitespace-pre ${
                          l.type === "add"
                            ? "bg-accent/[0.07] shadow-[inset_2px_0_0_var(--color-accent)]"
                            : l.type === "del"
                              ? "bg-danger/[0.07] shadow-[inset_2px_0_0_var(--color-danger)]"
                              : ""
                        } ${isHighlight ? "animate-flash-line" : ""}`}
                      >
                        <span className="w-11 shrink-0 pr-2 text-right select-none text-faint/70 border-r border-edge">
                          {l.oldNo ?? ""}
                        </span>
                        <span className="w-11 shrink-0 pr-2 text-right select-none text-faint/70 border-r border-edge">
                          {l.newNo ?? ""}
                        </span>
                        <span
                          className={`pl-3 pr-4 flex-1 ${
                            l.type === "add" ? "text-accent-hi/90" : l.type === "del" ? "text-danger/80" : "text-dim"
                          }`}
                        >
                          {l.content || " "}
                        </span>
                      </div>
                    );
                  })}
                </div>
              ))}
              {more > 0 && (
                <div className="border-t border-edge bg-sunken">
                  <button
                    className="w-full flex items-center gap-2 px-3.5 py-2 font-mono text-[11.5px] text-dim hover:text-ink hover:bg-raised transition-colors cursor-pointer"
                    onClick={() => setCap((c) => c + FILE_LINES_PER_BATCH)}
                  >
                    <CaretDown size={12} className="text-faint shrink-0" />
                    展开该文件剩余 {more} 行
                    <span className="flex-1" />
                    <span className="text-faint tabular-nums">已展示 {shown} / {total} 行</span>
                  </button>
                </div>
              )}
            </div>
          </div>
        )}
      </div>
    );
  },
  // 折叠态文件行只由头部字段决定：diff 刷新替换整棵树时，避免视口内大量折叠块重渲染
  (p, n) =>
    p.open === n.open &&
    p.highlightLine === n.highlightLine &&
    p.minLines === n.minLines &&
    p.file.path === n.file.path &&
    (p.open ||
      (p.file.status === n.file.status &&
        p.file.additions === n.file.additions &&
        p.file.deletions === n.file.deletions)),
);

/**
 * 虚拟列表行：绝对定位在滚动容器内。折叠态高度由 CSS 固定（h-10 头部）确定，无需测量；
 * 仅展开行需要挂 RO 实测高度回报，用于维护"测量值优先"的槽位表并校正离线行的估算高度。
 */
function VirtualRow({
  path,
  top,
  open,
  onHeight,
  children,
}: {
  path: string;
  top: number;
  open: boolean;
  onHeight: (path: string, height: number) => void;
  children: ReactNode;
}) {
  const ref = useRef<HTMLDivElement | null>(null);
  const report = useCallback(() => {
    const el = ref.current;
    if (el) onHeight(path, el.getBoundingClientRect().height);
  }, [path, onHeight]);

  useEffect(() => {
    if (!open) return;
    const el = ref.current;
    if (!el) return;
    report();
    const ro = new ResizeObserver(report);
    ro.observe(el);
    return () => ro.disconnect();
  }, [open, report]);

  return (
    <div ref={ref} className="absolute left-0 right-0" style={{ top }}>
      {children}
    </div>
  );
}

/** 估算某文件块的渲染高度：折叠态取 CSS 固定高；展开行已实测优先，未实测按行数粗估（挂载后即校正）。 */
function estimateRowHeight(
  f: DiffFile,
  open: boolean,
  measured: Record<string, number>,
): number {
  if (!open) return COLLAPSED_ROW_H;
  const m = measured[f.path];
  if (m !== undefined) return m;
  let rows = 0;
  let hunks = 0;
  for (const h of f.hunks) {
    hunks++;
    rows += h.lines.length;
  }
  const shown = Math.min(rows, FILE_LINES_PER_BATCH);
  return COLLAPSED_ROW_H + hunks * EST_HEADER_H + shown * 19 + (rows > shown ? EST_MORE_BAR_H : 0) + 3;
}

/** 槽位表：slots[i] = 第 i 个文件块距列表顶部的偏移；含行间距。 */
function buildSlots(
  files: DiffFile[],
  openPaths: ReadonlySet<string>,
  measured: Record<string, number>,
): number[] {
  const slots = new Array<number>(files.length + 1);
  slots[0] = 0;
  let acc = 0;
  for (let i = 0; i < files.length; i++) {
    acc += estimateRowHeight(files[i], openPaths.has(files[i].path), measured) + ROW_GAP;
    slots[i + 1] = acc;
  }
  return slots;
}

export function DiffView({ ticketNo }: { ticketNo: string }) {
  const files = useApp((s) => s.diffs[ticketNo] ?? NO_DIFF);
  const eolWarning = useApp((s) => s.diffWarnings[ticketNo] ?? "");
  // 与 Workbench 分支徽标同源：项目主分支（建单基线）优先，未挂项目的工单退回自身锁定的目标分支
  const targetRef = useApp((s) => {
    const t = s.tickets.find((x) => x.ticketNo === ticketNo);
    return s.projects.find((p) => p.id === t?.projectId)?.targetRef ?? t?.targetRef;
  });
  const highlight = useApp((s) => s.highlight);
  const totals = useMemo(() => diffTotals(files), [files]);
  const [openPaths, setOpenPaths] = useState<Set<string>>(new Set());
  // 审查跳转目标文件的行数下限（配合文件内行分片定位）。
  const [revealLines, setRevealLines] = useState<{ path: string; min: number } | null>(null);
  // 已实测的文件块高度（path → 高度，跨刷新/跨工单会话累积，挂载即复测校正）。
  const [measured, setMeasured] = useState<Record<string, number>>({});
  // 当前挂载窗口 [start, end)。
  const [range, setRange] = useState<{ start: number; end: number }>({ start: 0, end: 0 });
  const scrollerRef = useRef<HTMLDivElement | null>(null);
  const listRef = useRef<HTMLDivElement | null>(null);
  const filesRef = useRef(files);
  filesRef.current = files;

  // 槽位表依赖 files / 展开集 / 实测值；scroll 处理器从 ref 读取最新表。
  const slots = useMemo(
    () => buildSlots(files, openPaths, measured),
    [files, openPaths, measured],
  );
  const slotsRef = useRef(slots);
  slotsRef.current = slots;

  const recordHeight = useCallback((path: string, h: number) => {
    setMeasured((prev) => {
      const cur = prev[path];
      if (cur !== undefined && Math.abs(cur - h) < 0.5) return prev;
      return { ...prev, [path]: h };
    });
  }, []);

  useEffect(() => {
    setOpenPaths(new Set());
    setRevealLines(null);
    setRange({ start: 0, end: 0 });
    const sc = scrollerRef.current;
    if (sc) sc.scrollTop = 0;
  }, [ticketNo]);

  const togglePath = useCallback((path: string) => {
    setOpenPaths((prev) => {
      const next = new Set(prev);
      if (next.has(path)) next.delete(path);
      else next.add(path);
      return next;
    });
  }, []);

  // 计算当前滚动位置对应的挂载窗口（读 DOM 与槽位表，不依赖上次渲染结果）。
  const layoutRange = useCallback((forceIdx?: number): { start: number; end: number } => {
    const sc = scrollerRef.current;
    const list = listRef.current;
    const slots = slotsRef.current;
    const n = filesRef.current.length;
    if (!sc || !list || n === 0) return { start: 0, end: 0 };
    const scRect = sc.getBoundingClientRect();
    const listTop =
      sc.scrollTop + (list.getBoundingClientRect().top - scRect.top);
    const viewTop = sc.scrollTop - listTop;
    const viewH = sc.clientHeight;

    let start = 0;
    const target = viewTop - OVERSCAN_PX;
    if (target > 0) {
      let lo = 0;
      let hi = n;
      while (lo < hi) {
        const mid = (lo + hi) >> 1;
        if ((slots[mid] ?? 0) < target) lo = mid + 1;
        else hi = mid;
      }
      start = Math.max(0, lo - 1);
    }
    if (forceIdx !== undefined) start = Math.max(0, Math.min(start, forceIdx - 2));

    let end = start + 1;
    const limit = viewTop + viewH + OVERSCAN_PX;
    while (end < n && (slots[end] ?? 0) <= limit) end++;
    if (forceIdx !== undefined) end = Math.max(end, Math.min(n, forceIdx + 3));
    return { start, end: Math.min(end, n) };
  }, []);

  const applyRange = useCallback(
    (forceIdx?: number) => {
      const next = layoutRange(forceIdx);
      setRange((r) => (r.start === next.start && r.end === next.end ? r : next));
    },
    [layoutRange],
  );

  // 滚动 / 容器尺寸变化时跟随更新挂载窗口（rAF 节流）。
  // hasFiles 翻转后滚动容器才存在：初次空态占位无 scroller，数据到达后需补挂监听。
  const hasFiles = files.length > 0;
  useEffect(() => {
    const sc = scrollerRef.current;
    if (!sc) return;
    let raf = 0;
    const onScroll = () => {
      if (raf) return;
      raf = requestAnimationFrame(() => {
        raf = 0;
        applyRange();
      });
    };
    sc.addEventListener("scroll", onScroll, { passive: true });
    const ro = new ResizeObserver(() => applyRange());
    ro.observe(sc);
    applyRange();
    return () => {
      sc.removeEventListener("scroll", onScroll);
      cancelAnimationFrame(raf);
      ro.disconnect();
    };
  }, [applyRange, hasFiles]);

  // 数据 / 展开集 / 实测高度变化后刷新窗口。
  useEffect(() => {
    applyRange();
  }, [files, openPaths, measured, applyRange]);

  // 审查定位跳转（每个高亮只处理一次，diff 刷新不重复强制展开已手动收起的文件）：
  // 自动展开目标文件并确保展开深度覆盖目标行；数据未就绪时等 files 变化再处理。
  const handledHighlight = useRef<string | null>(null);
  useEffect(() => {
    if (!highlight) {
      handledHighlight.current = null;
      setRevealLines(null);
      return;
    }
    const key = `${highlight.path}:${highlight.line}`;
    if (handledHighlight.current === key) return;
    const idx = files.findIndex((f) => f.path === highlight.path);
    if (idx < 0) return;
    handledHighlight.current = key;
    setOpenPaths((prev) => {
      if (prev.has(highlight.path)) return prev;
      const next = new Set(prev);
      next.add(highlight.path);
      return next;
    });
    const need = revealLineAt(files[idx], highlight.line);
    if (need > 0) {
      setRevealLines((r) =>
        r?.path === highlight.path && r.min >= need ? r : { path: highlight.path, min: need },
      );
    }
  }, [highlight, files]);

  // 跳转落地：先滚动到目标文件块的估算位置，等窗口随滚动迁移挂载、目标行入 DOM 后，
  // 再做精确 scrollIntoView 居中；文件数据晚到/被刷新时由轮询自行重试。
  useEffect(() => {
    if (!highlight) return;
    const sc = scrollerRef.current;
    if (!sc) return;
    const target = `[data-line="${highlight.path}:${highlight.line}"]`;
    let tries = 0;
    let raf = 0;
    let jumped = false;
    const attempt = () => {
      applyRange();
      const el = document.querySelector(target);
      if (el) {
        el.scrollIntoView({ behavior: "smooth", block: "center" });
        return;
      }
      if (!jumped) {
        const idx = filesRef.current.findIndex((f) => f.path === highlight.path);
        const list = listRef.current;
        if (idx >= 0 && list) {
          const scRect = sc.getBoundingClientRect();
          const listTop = sc.scrollTop + (list.getBoundingClientRect().top - scRect.top);
          sc.scrollTo({
            top: Math.max(0, listTop + (slotsRef.current[idx] ?? 0) - 96),
            behavior: "smooth",
          });
          jumped = true;
        }
      }
      if (tries++ < 300) raf = requestAnimationFrame(attempt);
    };
    raf = requestAnimationFrame(attempt);
    return () => cancelAnimationFrame(raf);
  }, [highlight, applyRange]);

  const totalH = files.length > 0 ? slots[files.length] : 0;

  if (files.length === 0) {
    return (
      <div className="flex-1 min-h-0 grid place-items-center">
        <div className="text-center max-w-[320px]">
          <div className="mx-auto w-11 h-11 rounded-xl border border-dashed border-edge-strong grid place-items-center mb-3">
            <FileCode size={20} className="text-faint" />
          </div>
          <div className="text-[13.5px] text-dim">沙箱内暂无变更</div>
          <div className="mt-1 text-[12px] text-faint leading-relaxed">
            Agent 在会话中的每次编辑都会实时反映到这里，预提审时将整体锁定为快照。
          </div>
        </div>
      </div>
    );
  }

  const rows: ReactNode[] = [];
  const lo = Math.max(0, range.start);
  const hi = Math.min(range.end, files.length);
  for (let i = lo; i < hi; i++) {
    const f = files[i];
    const open = openPaths.has(f.path);
    rows.push(
      <VirtualRow
        key={`${ticketNo}:${f.path}`}
        path={f.path}
        top={slots[i]}
        open={open}
        onHeight={recordHeight}
      >
        <FileBlock
          file={f}
          open={open}
          onToggle={togglePath}
          highlightLine={highlight?.path === f.path ? highlight.line : undefined}
          minLines={revealLines?.path === f.path ? revealLines.min : undefined}
        />
      </VirtualRow>,
    );
  }

  return (
    <div ref={scrollerRef} className="flex-1 min-h-0 overflow-auto px-5 py-4">
      <div className="mx-auto w-full max-w-[900px] space-y-3">
        {eolWarning && (
          <div className="rounded-xl border border-warn/30 bg-warn/[0.06] px-3.5 py-2.5 flex items-start gap-2">
            <Warning size={14} className="text-warn shrink-0 mt-0.5" weight="fill" />
            <span className="text-[12.5px] text-warn leading-relaxed">{eolWarning}</span>
          </div>
        )}
        <div className="flex items-center gap-2 pb-1 flex-wrap">
          <span className="text-[12.5px] text-dim">{totals.files} 个文件变更</span>
          <span className="font-mono text-[12px] text-accent">+{totals.additions}</span>
          <span className="font-mono text-[12px] text-danger">−{totals.deletions}</span>
          <span className="flex-1" />
          {targetRef && <span className="text-[11.5px] text-faint">相对基线 {targetRef}</span>}
          <span className="text-[11.5px] text-faint">·</span>
          <button
            className="text-[11.5px] text-dim hover:text-ink cursor-pointer transition-colors"
            onClick={() => setOpenPaths(new Set(files.map((f) => f.path)))}
          >
            展开全部
          </button>
          <span className="text-[11.5px] text-faint">·</span>
          <button
            className="text-[11.5px] text-dim hover:text-ink cursor-pointer transition-colors"
            onClick={() => setOpenPaths(new Set())}
          >
            收起全部
          </button>
        </div>
        <div ref={listRef} className="relative" style={{ height: totalH }}>
          {rows}
        </div>
      </div>
    </div>
  );
}
