import { useEffect, useMemo, useRef, useState } from "react";
import { useT } from "@/i18n";
import { CaretDown } from "@phosphor-icons/react";
import { parseLimit } from "./presets";

/** 限制编辑框：聚焦展开档位下拉、点选即填，也可手输 16K / 200000 等任意值。 */
export function LimitCombo({
  presets,
  value,
  onChange,
  placeholder,
  ariaLabel,
}: {
  presets: string[];
  value: string;
  onChange: (v: string) => void;
  placeholder: string;
  ariaLabel: string;
}) {
  const t = useT();
  const [open, setOpen] = useState(false);
  const [up, setUp] = useState(false);
  const [hi, setHi] = useState(-1);
  const rootRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (!open) return;
    // 面板是 overflow-y-auto 滚动容器：贴近底部时下拉会被裁掉，空间不足改为向上弹出。
    const box = rootRef.current?.getBoundingClientRect();
    if (box) {
      const scroller = rootRef.current?.closest(".overflow-y-auto");
      const limit = scroller ? scroller.getBoundingClientRect().bottom : window.innerHeight;
      setUp(limit - box.bottom < 190);
    }
    const onDown = (e: PointerEvent) => {
      if (rootRef.current && !rootRef.current.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener("pointerdown", onDown);
    return () => document.removeEventListener("pointerdown", onDown);
  }, [open]);

  useEffect(() => {
    if (!open) return;
    const list = rootRef.current?.querySelector("[data-combo-list]");
    const el = list?.children[hi] as HTMLElement | undefined;
    el?.scrollIntoView({ block: "nearest" });
  }, [hi, open]);

  const query = value.trim().toLowerCase();
  const options = useMemo(() => {
    if (!query) return presets;
    return presets.filter((p) => {
      if (p.toLowerCase().includes(query)) return true;
      const n = parseLimit(p);
      return n != null && String(n).includes(query);
    });
  }, [presets, query]);

  const pick = (p: string) => {
    onChange(p);
    setOpen(false);
    setHi(-1);
  };

  return (
    <div ref={rootRef} className="relative">
      <input
        ref={inputRef}
        className="text-input font-mono text-[12px] pr-8"
        placeholder={placeholder}
        aria-label={ariaLabel}
        value={value}
        onFocus={() => setOpen(true)}
        onChange={(e) => {
          onChange(e.target.value);
          setOpen(true);
          setHi(-1);
        }}
        onKeyDown={(e) => {
          if (e.key === "ArrowDown") {
            e.preventDefault();
            setHi((i) => Math.min(i + 1, options.length - 1));
            setOpen(true);
          } else if (e.key === "ArrowUp" && open) {
            e.preventDefault();
            setHi((i) => Math.max(i - 1, -1));
          } else if (e.key === "Enter") {
            e.preventDefault();
            if (open && (hi >= 0 ? options[hi] : options.length === 1)) pick(options[hi >= 0 ? hi : 0]);
            else setOpen(false);
          } else if (e.key === "Escape") {
            setOpen(false);
            setHi(-1);
          } else if (e.key === "Tab") {
            setOpen(false);
          }
        }}
      />
      <button
        type="button"
        className="absolute right-1 top-1/2 -translate-y-1/2 p-1.5 rounded-md text-faint hover:text-ink cursor-pointer bg-transparent border-0"
        tabIndex={-1}
        aria-label={open ? t("limit.collapse") : t("limit.expand")}
        onMouseDown={(e) => e.preventDefault()}
        onClick={() => setOpen((v) => !v)}
      >
        <CaretDown size={12} className={`transition-transform ${open ? "rotate-180" : ""}`} />
      </button>
      {open && (
        <div
          data-combo-list
          className={`absolute z-30 left-0 right-0 rounded-lg border border-edge bg-canvas shadow-lg shadow-black/25 py-1 max-h-[176px] overflow-y-auto ${
            up ? "bottom-[calc(100%+4px)]" : "top-[calc(100%+4px)]"
          }`}
        >
          {options.length === 0 && (
            <div className="px-3 py-1.5 text-[11.5px] text-faint">{t("limit.noMatch")}</div>
          )}
          {options.map((p, i) => {
            const n = parseLimit(p);
            const selected = parseLimit(value) != null && parseLimit(value) === n;
            return (
              <button
                key={p}
                type="button"
                className={`w-full flex items-center gap-2 px-3 py-1.5 text-left font-mono text-[12px] cursor-pointer border-0 bg-transparent ${
                  selected ? "text-accent bg-accent/10" : hi === i ? "bg-raised text-ink" : "text-dim"
                }`}
                onMouseEnter={() => setHi(i)}
                onMouseDown={(e) => e.preventDefault()}
                onClick={() => pick(p)}
              >
                {p}
                {n != null && (
                  <span className="ml-auto text-[10.5px] text-faint tracking-normal">{n.toLocaleString("en-US")}</span>
                )}
              </button>
            );
          })}
        </div>
      )}
    </div>
  );
}
