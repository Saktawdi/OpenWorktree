import { memo, useCallback, useEffect, useMemo, useRef, useState } from "react";
import { CaretDown, CaretUp, FileCode, Warning } from "@phosphor-icons/react";
import { NO_DIFF, useApp } from "@/store";
import type { DiffFile, DiffHunk } from "@/shared/types";
import { diffTotals } from "@/shared/diff";

/* 超大 diff（如未加入 git 忽略的 node_modules 整目录入库）下的分批懒加载参数：
   列表按"滚动到末尾再加载下一批"，单个文件展开内容也按行数分批，避免一次性铺开卡死页面。 */
const FILES_PER_BATCH = 80; // 文件列表单批渲染/追加的数量
const FILE_LINES_PER_BATCH = 2000; // 单个文件展开时单批追加的行数

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
    /** 审查跳转要求文件至少展开到该行数（配合文件级/行级分片也能定位到目标行）。 */
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
  // 折叠态的文件行只由头部字段决定：live 刷新替换整棵树时避免成千上万个折叠块全部重渲染
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
  // 文件默认折叠：整仓级 diff（数万行）一次性铺开会把页面压死，点击文件头再渲染内容。
  const [openPaths, setOpenPaths] = useState<Set<string>>(new Set());
  // 懒加载：只挂载前 N 个文件；滚动到列表底部（哨兵可见）再按批追加。
  const [visibleCount, setVisibleCount] = useState(FILES_PER_BATCH);
  // 审查跳转目标文件的行数下限（跨文件分页/行分片定位用）。
  const [revealLines, setRevealLines] = useState<{ path: string; min: number } | null>(null);
  const scrollerRef = useRef<HTMLDivElement | null>(null);
  const sentinelRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    setOpenPaths(new Set());
    setVisibleCount(FILES_PER_BATCH);
    setRevealLines(null);
  }, [ticketNo]);

  const togglePath = useCallback((path: string) => {
    setOpenPaths((prev) => {
      const next = new Set(prev);
      if (next.has(path)) next.delete(path);
      else next.add(path);
      return next;
    });
  }, []);

  // 审查定位跳转：文件还没被懒加载挂载时先把渲染窗口扩展到它；
  // 目标行超出文件行分片时先抬高行数下限，随后滚动 effect 等元素就绪后再定位。
  useEffect(() => {
    if (!highlight) {
      setRevealLines(null);
      return;
    }
    const idx = files.findIndex((f) => f.path === highlight.path);
    if (idx < 0) return;
    setVisibleCount((c) => Math.max(c, idx + 1));
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

  useEffect(() => {
    if (!highlight) return;
    const el = document.querySelector(`[data-line="${highlight.path}:${highlight.line}"]`);
    el?.scrollIntoView({ behavior: "smooth", block: "center" });
  }, [highlight, openPaths, revealLines, visibleCount, files]);

  const hasMore = files.length > 0 && visibleCount < files.length;
  const loadMore = useCallback(() => {
    setVisibleCount((c) => Math.min(files.length, c + FILES_PER_BATCH));
  }, [files.length]);

  // 滚动到底部附近自动追加下一批文件（哨兵进入滚动容器可视区即触发）。
  useEffect(() => {
    if (!hasMore) return;
    const scroller = scrollerRef.current;
    const sentinel = sentinelRef.current;
    if (!scroller || !sentinel) return;
    const io = new IntersectionObserver(
      (entries) => {
        if (entries.some((e) => e.isIntersecting)) loadMore();
      },
      { root: scroller, rootMargin: "0px 0px 800px 0px", threshold: 0 },
    );
    io.observe(sentinel);
    return () => io.disconnect();
  }, [hasMore, loadMore]);

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

  const mounted = files.slice(0, visibleCount);

  return (
    <div ref={scrollerRef} className="flex-1 min-h-0 overflow-auto px-5 py-4">
      <div className="max-w-[900px] mx-auto space-y-3">
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
            onClick={() => setOpenPaths(new Set(mounted.map((f) => f.path)))}
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
        {mounted.map((f) => (
          <FileBlock
            key={f.path}
            file={f}
            open={openPaths.has(f.path)}
            onToggle={togglePath}
            highlightLine={highlight?.path === f.path ? highlight.line : undefined}
            minLines={revealLines?.path === f.path ? revealLines.min : undefined}
          />
        ))}
        {hasMore && (
          <div
            ref={sentinelRef}
            className="flex items-center justify-center gap-3 pt-1 pb-2 text-[12px] text-faint"
          >
            <span className="tabular-nums">
              已加载 {mounted.length} / {files.length} 个文件
            </span>
            <button
              className="text-dim hover:text-ink cursor-pointer transition-colors"
              onClick={loadMore}
            >
              加载下一批
            </button>
            <span className="text-faint/70">·</span>
            <button
              className="text-dim hover:text-ink cursor-pointer transition-colors"
              onClick={() => setVisibleCount(files.length)}
            >
              加载全部
            </button>
          </div>
        )}
      </div>
    </div>
  );
}
