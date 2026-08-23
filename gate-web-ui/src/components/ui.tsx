import type { Priority, Severity, Stage } from "../lib/types";
import { PRIORITY_COLOR, SEVERITY_LABEL, STAGE_LABEL } from "../lib/format";
import { useEffect, useRef, useState } from "react";
import { Copy } from "@phosphor-icons/react";

export function LogoMark({ size = 22 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" aria-hidden>
      <path
        d="M12 2.4l7.6 2.85v5.9c0 4.62-3.23 7.86-7.6 10.05-4.37-2.19-7.6-5.43-7.6-10.05v-5.9L12 2.4z"
        stroke="var(--color-accent)"
        strokeWidth="1.6"
        strokeLinejoin="round"
      />
      <path
        d="M8.6 11.8l2.5 2.5 4.4-5"
        stroke="var(--color-accent)"
        strokeWidth="1.8"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
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
