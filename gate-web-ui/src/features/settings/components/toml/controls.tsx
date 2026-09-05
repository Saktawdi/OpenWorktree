import { useState } from "react";
import { X } from "@phosphor-icons/react";

/** 字符串列表编辑器（tag 式追加/移除，支持条目格式约束）。 */
export function StringListEditor({
  value,
  onChange,
  disabled,
  itemPattern,
  patternHint,
  placeholder,
}: {
  value: string[];
  onChange: (v: string[]) => void;
  disabled?: boolean;
  /** 条目格式约束（如 refs/heads/ 前缀）；不匹配的输入回车不加入 */
  itemPattern?: RegExp;
  patternHint?: string;
  placeholder?: string;
}) {
  const [input, setInput] = useState("");
  const invalid = input.trim() !== "" && itemPattern ? !itemPattern.test(input.trim()) : false;
  const add = () => {
    const t = input.trim();
    if (!t || invalid) return;
    if (value.includes(t)) { setInput(""); return; }
    onChange([...value, t]);
    setInput("");
  };
  return (
    <>
      <div className={`rounded-lg border bg-sunken px-2 py-1.5 flex flex-wrap gap-1.5 items-center min-h-9 transition-colors focus-within:border-accent/40 ${invalid ? "border-danger/60 focus-within:border-danger/60" : ""} ${disabled ? "opacity-50 border-edge bg-raised" : "border-edge"}`}>
        {value.map((tag) => (
          <span key={tag} className="inline-flex items-center gap-1 chip border border-edge-strong bg-raised text-dim text-[11.5px] pr-1">
            {tag}
            {!disabled && (
              <button className="grid place-items-center w-4 h-4 rounded-full hover:bg-edge cursor-pointer" onClick={() => onChange(value.filter((x) => x !== tag))} aria-label={`移除 ${tag}`}>
                <X size={10} />
              </button>
            )}
          </span>
        ))}
        {!disabled && (
          <input
            className="flex-1 min-w-[90px] bg-transparent outline-none text-[12.5px] placeholder:text-faint"
            placeholder={placeholder ?? "输入后回车添加"}
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter") { e.preventDefault(); add(); }
              if (e.key === "Backspace" && !input && value.length) onChange(value.slice(0, -1));
            }}
          />
        )}
      </div>
      {invalid && patternHint && (
        <div className="text-[11px] text-danger">{patternHint}</div>
      )}
    </>
  );
}

/** 布尔开关（role=switch）。 */
export function BoolSwitch({ value, onChange, disabled }: { value: boolean; onChange: (v: boolean) => void; disabled?: boolean }) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={value}
      disabled={disabled}
      onClick={() => !disabled && onChange(!value)}
      className={`relative inline-flex w-9 h-[20px] rounded-full transition-colors duration-150 shrink-0 ${disabled ? "opacity-40 cursor-not-allowed bg-edge-strong" : value ? "bg-accent cursor-pointer" : "bg-edge-strong cursor-pointer"}`}
    >
      <span className={`absolute top-[2px] left-[2px] w-4 h-4 rounded-full bg-white shadow-sm transition-transform duration-150 ${value ? "translate-x-[16px]" : ""}`} />
    </button>
  );
}

/** 通用键值选择框：空值 = 未设置；当前文件值不在候选里时自动补一项，避免展示错位。 */
export function KeySelect({
  value,
  options,
  emptyLabel,
  disabled,
  onChange,
}: {
  value: string;
  options: Array<{ value: string; label: string }>;
  emptyLabel: string;
  disabled?: boolean;
  onChange: (v: string) => void;
}) {
  const merged = value && !options.some((o) => o.value === value)
    ? [{ value, label: `${value}（当前文件值）` }, ...options]
    : options;
  return (
    <select
      className="text-input font-mono text-[12px] cursor-pointer disabled:opacity-50"
      value={value}
      disabled={disabled}
      onChange={(e) => onChange(e.target.value)}
    >
      <option value="">{emptyLabel}</option>
      {merged.map((o) => (
        <option key={o.value} value={o.value}>{o.label}</option>
      ))}
    </select>
  );
}
