/**
 * 分组模型菜单（shared/components/GroupedModelMenu）：「Provider 分组 → 模型行」
 * 的弹出列表骨架，本体会话 Composer 的 ModelPicker 与 LLM 小助手的模型选择器
 * 共用同一交互与视觉。调用方负责触发器与弹层壳，这里只渲染分组列表本体。
 */
import { Check } from "@phosphor-icons/react";
import type { ReactNode } from "react";

export interface ModelMenuGroup {
  id: string;
  name: string;
  /** 组级警示徽标（如「未配置密钥」）；无则不显示。 */
  warning?: string | null;
  models: Array<{
    id: string;
    label: string;
    active?: boolean;
    disabled?: boolean;
    /** 行尾附加信息（如「N 档强度」）。 */
    trailing?: ReactNode | null;
  }>;
}

export function GroupedModelMenu({
  groups,
  onPick,
  maxHeight = 300,
  emptyText = "无匹配模型",
  emptyGroupText,
}: {
  groups: ModelMenuGroup[];
  onPick: (groupId: string, modelId: string) => void;
  maxHeight?: number;
  /** 全空（搜索无结果/无目录）时的占位文案。 */
  emptyText?: string;
  /** 组内无模型时的占位文案；不传则整组跳过。 */
  emptyGroupText?: string;
}) {
  const isEmpty = groups.every((g) => g.models.length === 0);
  return (
    <div className="overflow-y-auto" style={{ maxHeight }}>
      {groups.map((g) => {
        if (g.models.length === 0 && !emptyGroupText) return null;
        return (
          <div key={g.id}>
            <div className="flex items-center gap-1.5 px-2.5 pt-2 pb-1 text-[10.5px] font-medium uppercase tracking-wide text-faint">
              <span className="truncate">{g.name}</span>
              {g.warning && (
                <span className="shrink-0 rounded bg-warn/15 px-1 text-[9.5px] normal-case text-warn">{g.warning}</span>
              )}
            </div>
            {g.models.length === 0 ? (
              <div className="px-2.5 pb-1 text-[11px] text-faint italic">{emptyGroupText}</div>
            ) : (
              g.models.map((m) => {
                const disabled = !!m.disabled;
                return (
                  <button
                    key={`${g.id}/${m.id}`}
                    disabled={disabled}
                    className={`w-full flex items-center gap-2 px-2.5 h-8 rounded-lg text-left text-[12px] transition-colors ${
                      disabled ? "opacity-40 cursor-not-allowed" : "cursor-pointer"
                    } ${
                      m.active
                        ? "bg-raised text-ink"
                        : disabled
                          ? "text-faint"
                          : "text-dim hover:bg-raised hover:text-ink"
                    }`}
                    onClick={() => !disabled && onPick(g.id, m.id)}
                  >
                    <span className="font-mono text-[11.5px] truncate">{m.label}</span>
                    {m.trailing && <span className="text-[10px] text-faint shrink-0">{m.trailing}</span>}
                    <span className="flex-1" />
                    {m.active && <Check size={13} className="text-accent shrink-0" weight="bold" />}
                  </button>
                );
              })
            )}
          </div>
        );
      })}
      {isEmpty && <div className="px-3 py-4 text-center text-[12px] text-faint">{emptyText}</div>}
    </div>
  );
}
