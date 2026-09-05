import { useEffect, useState } from "react";
import { CaretDown, CaretUp, FileCode, Warning } from "@phosphor-icons/react";
import { NO_DIFF, useApp } from "@/store";
import type { DiffFile } from "@/shared/types";
import { diffTotals } from "@/shared/diff";

function FileBlock({
  file,
  highlightLine,
  open,
  onToggle,
}: {
  file: DiffFile;
  highlightLine?: number;
  open: boolean;
  onToggle: () => void;
}) {
  return (
    <div className="border border-edge rounded-xl overflow-hidden bg-panel">
      <button
        className="w-full flex items-center gap-2.5 px-3.5 h-10 border-b border-edge bg-raised/50 hover:bg-raised transition-colors cursor-pointer text-left"
        onClick={onToggle}
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
            {file.hunks.map((h, hi) => (
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
          </div>
        </div>
      )}
    </div>
  );
}

export function DiffView({ ticketNo }: { ticketNo: string }) {
  const files = useApp((s) => s.diffs[ticketNo] ?? NO_DIFF);
  const eolWarning = useApp((s) => s.diffWarnings[ticketNo] ?? "");
  const highlight = useApp((s) => s.highlight);
  const totals = diffTotals(files);
  // 文件默认折叠：整仓级 diff（数万行）一次性铺开会把页面压死，点击文件头再渲染内容。
  const [openPaths, setOpenPaths] = useState<Set<string>>(new Set());

  useEffect(() => {
    setOpenPaths(new Set());
  }, [ticketNo]);

  // 审查定位跳转时自动展开目标文件；滚动依赖 openPaths，等重渲染完成后再定位。
  useEffect(() => {
    if (!highlight) return;
    setOpenPaths((prev) => {
      if (prev.has(highlight.path)) return prev;
      const next = new Set(prev);
      next.add(highlight.path);
      return next;
    });
  }, [highlight]);

  useEffect(() => {
    if (!highlight) return;
    const el = document.querySelector(`[data-line="${highlight.path}:${highlight.line}"]`);
    el?.scrollIntoView({ behavior: "smooth", block: "center" });
  }, [highlight, openPaths]);

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

  return (
    <div className="flex-1 min-h-0 overflow-auto px-5 py-4">
      <div className="max-w-[900px] mx-auto space-y-3">
        {eolWarning && (
          <div className="rounded-xl border border-warn/30 bg-warn/[0.06] px-3.5 py-2.5 flex items-start gap-2">
            <Warning size={14} className="text-warn shrink-0 mt-0.5" weight="fill" />
            <span className="text-[12.5px] text-warn leading-relaxed">{eolWarning}</span>
          </div>
        )}
        <div className="flex items-center gap-2 pb-1">
          <span className="text-[12.5px] text-dim">{totals.files} 个文件变更</span>
          <span className="font-mono text-[12px] text-accent">+{totals.additions}</span>
          <span className="font-mono text-[12px] text-danger">−{totals.deletions}</span>
          <span className="flex-1" />
          <span className="text-[11.5px] text-faint">相对基线 refs/heads/main</span>
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
        {files.map((f) => (
          <FileBlock
            key={f.path}
            file={f}
            open={openPaths.has(f.path)}
            onToggle={() =>
              setOpenPaths((prev) => {
                const next = new Set(prev);
                if (next.has(f.path)) next.delete(f.path);
                else next.add(f.path);
                return next;
              })
            }
            highlightLine={highlight?.path === f.path ? highlight.line : undefined}
          />
        ))}
      </div>
    </div>
  );
}
