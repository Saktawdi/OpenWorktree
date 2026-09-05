import { motion } from "motion/react";
import { PlugsConnected, Robot, Rocket, Wrench } from "@phosphor-icons/react";

export type SettingsTab = "toml" | "mcp" | "llm" | "app";

const NAV_ITEMS = [
  { key: "toml", label: "系统设置", desc: "运行键值与默认值", Icon: Wrench },
  { key: "mcp", label: "MCP 状态", desc: "服务与工具清单", Icon: PlugsConnected },
  { key: "llm", label: "LLM 设置", desc: "Provider 与模型", Icon: Robot },
  { key: "app", label: "应用设置", desc: "版本 · 更新 · 仓库", Icon: Rocket },
] as const;

/** 设置中心左侧分区导航（布局共享 layoutId 高亮）。 */
export function SettingsNav({ tab, onChange }: { tab: SettingsTab; onChange: (t: SettingsTab) => void }) {
  return (
    <nav
      className="flex md:flex-col gap-1 overflow-x-auto md:overflow-visible md:sticky md:top-5 md:self-start -mx-1 px-1 py-1 md:py-0"
      aria-label="设置分区"
    >
      {NAV_ITEMS.map(({ key, label, desc, Icon }) => {
        const active = tab === key;
        return (
          <button
            key={key}
            onClick={() => onChange(key)}
            aria-current={active ? "page" : undefined}
            className={`relative shrink-0 flex md:items-center items-center gap-2.5 h-10 md:h-auto md:py-2 px-2.5 rounded-lg text-left cursor-pointer transition-colors md:w-full ${
              active ? "text-ink" : "text-dim hover:text-ink hover:bg-raised/40"
            }`}
          >
            {active && (
              <motion.div
                layoutId="settings-nav-active"
                className="absolute inset-0 rounded-lg bg-raised"
                transition={{ type: "spring", stiffness: 500, damping: 35 }}
              />
            )}
            <span
              className={`relative z-10 grid place-items-center shrink-0 transition-colors ${
                active ? "text-accent" : "text-faint"
              }`}
            >
              <Icon size={15} weight={active ? "fill" : "regular"} />
            </span>
            <span className="relative z-10 min-w-0">
              <span className="block text-[12.5px] font-medium leading-tight whitespace-nowrap">{label}</span>
              <span className="hidden md:block text-[10.5px] text-faint leading-tight mt-px whitespace-nowrap">{desc}</span>
            </span>
          </button>
        );
      })}
    </nav>
  );
}
