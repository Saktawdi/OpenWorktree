import type { Priority, Severity, Stage } from "../lib/types";
import { PRIORITY_COLOR, SEVERITY_LABEL, STAGE_LABEL } from "../lib/format";
import { useEffect, useRef, useState } from "react";
import { Copy, X } from "@phosphor-icons/react";
import { useApp } from "../lib/store";

/** 品牌 OW 徽章：跟随主题切换静态图（不播动画，动画版见 TopBar 的 BrandMark）。 */
export function LogoMark({ size = 22 }: { size?: number }) {
  const theme = useApp((s) => s.theme);
  return (
    <img
      src={theme === "dark" ? "/brand/ow-dark-badge-64.png" : "/brand/ow-light-badge-64.png"}
      alt=""
      width={size}
      height={size}
      draggable={false}
      aria-hidden
      className="select-none shrink-0"
    />
  );
}

export function StageDot({ stage }: { stage: Stage }) {
  const color =
    stage === "REJECTED"
      ? "bg-danger"
      : stage === "NEEDS_HUMAN"
        ? "bg-warn"
        : stage === "IN_PROGRESS" || stage === "IN_REVIEW"
          ? "bg-info"
          : stage === "PENDING" || stage === "CANCELLED"
            ? "bg-faint"
            : "bg-accent";
  return <span className={`inline-block w-1.5 h-1.5 rounded-full ${color}`} />;
}

export function StageBadge({ stage }: { stage: Stage }) {
  const tone =
    stage === "REJECTED"
      ? "text-danger border-danger/30 bg-danger/10"
      : stage === "NEEDS_HUMAN"
        ? "text-warn border-warn/30 bg-warn/10"
        : stage === "IN_PROGRESS" || stage === "IN_REVIEW"
          ? "text-info border-info/25 bg-info/10"
          : stage === "PENDING" || stage === "CANCELLED"
            ? "text-dim border-edge-strong bg-raised"
            : "text-accent border-accent/30 bg-accent/10";
  return (
    <span className={`chip border ${tone}`}>
      <StageDot stage={stage} />
      {STAGE_LABEL[stage]}
    </span>
  );
}

export function PriorityChip({ priority, muted = false }: { priority: Priority; muted?: boolean }) {
  return (
    <span
      className={`chip border font-mono ${
        muted ? "text-faint border-edge-strong bg-raised opacity-50" : PRIORITY_COLOR[priority] ?? PRIORITY_COLOR.P3
      }`}
    >
      {priority}
    </span>
  );
}

export function SeverityChip({ severity }: { severity: Severity }) {
  const map: Record<Severity, string> = {
    BLOCKER: "text-danger border-danger/40 bg-danger/15",
    WARNING: "text-warn border-warn/40 bg-warn/15",
    NIT: "text-dim border-edge-strong bg-raised",
    INFO: "text-info border-info/30 bg-info/10",
  };
  return <span className={`chip border ${map[severity]}`}>{SEVERITY_LABEL[severity]}</span>;
}

export function Spinner({ className = "" }: { className?: string }) {
  return (
    <span
      className={`inline-block w-3.5 h-3.5 rounded-full border-[1.5px] border-current border-t-transparent animate-[spin_0.8s_linear_infinite] ${className}`}
    />
  );
}

export function HashReveal({ hash, className = "" }: { hash: string; className?: string }) {
  const [shown, setShown] = useState(hash);
  const raf = useRef<number>(0);

  useEffect(() => {
    const reduce = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    if (reduce) {
      setShown(hash);
      return;
    }
    const start = performance.now();
    const dur = 850;
    const chars = "0123456789abcdef";
    const tick = (t: number) => {
      const p = Math.min(1, (t - start) / dur);
      const reveal = Math.floor(p * hash.length);
      let out = hash.slice(0, reveal);
      for (let i = reveal; i < hash.length; i++) {
        out += chars[Math.floor(Math.random() * chars.length)];
      }
      setShown(out);
      if (p < 1) raf.current = requestAnimationFrame(tick);
      else setShown(hash);
    };
    raf.current = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf.current);
  }, [hash]);

  return <span className={className}>{shown}</span>;
}

export function CopyButton({ text, label }: { text: string; label?: string }) {
  const [done, setDone] = useState(false);
  return (
    <button
      className="icon-btn"
      title={label ?? "复制"}
      aria-label={label ?? "复制"}
      onClick={() => {
        navigator.clipboard?.writeText(text).then(() => {
          setDone(true);
          setTimeout(() => setDone(false), 1400);
        });
      }}
    >
      {done ? (
        <span className="text-accent text-[13px] leading-none">✓</span>
      ) : (
        <Copy size={14} weight="regular" />
      )}
    </button>
  );
}

const COMMON_LABELS = ["开发", "BUG", "优化", "重构", "文档", "安全", "性能", "测试"];
const MAX_LABELS = 20;

/**
 * 标签输入框：已选标签渲染为可删除的 chip；输入中按回车（或逗号）自动分割成标签，
 * 空草稿按退格删除末尾标签；下方提供常用标签一键填充（suggestions 可按场景定制），再次点击取消。
 */
export function LabelInput({
  labels,
  onChange,
  placeholder = "输入后回车添加，可用逗号分隔",
  suggestions = COMMON_LABELS,
}: {
  labels: string[];
  onChange: (next: string[]) => void;
  placeholder?: string;
  suggestions?: string[];
}) {
  const [draft, setDraft] = useState("");
  const inputRef = useRef<HTMLInputElement>(null);

  const addParts = (parts: string[]) => {
    const next = [...labels];
    for (const part of parts) {
      const tag = part.trim();
      if (!tag || next.length >= MAX_LABELS) continue;
      if (!next.some((l) => l.toLowerCase() === tag.toLowerCase())) next.push(tag);
    }
    if (next.length !== labels.length) onChange(next);
  };

  const remove = (tag: string) => onChange(labels.filter((l) => l !== tag));

  return (
    <div>
      <div
        className="flex w-full min-h-9 flex-wrap items-center gap-1 rounded-lg border border-edge bg-sunken px-2 py-1 cursor-text transition-all duration-150 hover:border-edge-strong focus-within:border-accent/50"
        onClick={() => inputRef.current?.focus()}
      >
        {labels.map((l) => (
          <span
            key={l}
            className="inline-flex h-6 items-center gap-0.5 rounded-md border border-edge-strong bg-raised pl-1.5 pr-1 text-[11px] font-medium leading-none text-dim"
          >
            {l}
            <button
              type="button"
              className="grid h-4 w-4 place-items-center rounded text-faint cursor-pointer hover:text-danger"
              onClick={() => remove(l)}
              aria-label={`移除标签 ${l}`}
            >
              <X size={9} weight="bold" />
            </button>
          </span>
        ))}
        <input
          ref={inputRef}
          className="h-7 min-w-[72px] flex-1 bg-transparent text-[13px] text-ink placeholder:text-faint outline-none"
          value={draft}
          placeholder={labels.length === 0 ? placeholder : ""}
          onChange={(e) => {
            const v = e.target.value;
            if (/[,，]/.test(v)) {
              const parts = v.split(/[,，]/);
              const tail = parts.pop() ?? "";
              addParts(parts);
              setDraft(tail);
            } else {
              setDraft(v);
            }
          }}
          onKeyDown={(e) => {
            if (e.key === "Enter") {
              e.preventDefault();
              addParts([draft]);
              setDraft("");
            } else if (e.key === "Backspace" && draft === "" && labels.length > 0) {
              onChange(labels.slice(0, -1));
            }
          }}
          onPaste={(e) => {
            const text = e.clipboardData.getData("text");
            if (/[,，\n]/.test(text)) {
              e.preventDefault();
              addParts(text.split(/[,，\n]/));
            }
          }}
          onBlur={() => {
            if (draft.trim()) {
              addParts([draft]);
              setDraft("");
            }
          }}
        />
      </div>
      <div className="mt-1.5 flex flex-wrap items-center gap-1">
        <span className="mr-0.5 text-[10.5px] text-faint">常用</span>
        {suggestions.map((tag) => {
          const existing = labels.find((l) => l.toLowerCase() === tag.toLowerCase());
          return (
            <button
              key={tag}
              type="button"
              className={`h-[22px] rounded-md border px-1.5 text-[11px] leading-none cursor-pointer transition-colors ${
                existing
                  ? "border-accent/50 bg-accent/10 text-accent"
                  : "border-edge text-faint hover:text-dim hover:border-edge-strong hover:bg-raised"
              }`}
              onClick={() => (existing ? remove(existing) : addParts([tag]))}
            >
              {tag}
            </button>
          );
        })}
      </div>
    </div>
  );
}
