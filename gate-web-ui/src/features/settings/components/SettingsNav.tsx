import { motion } from "motion/react";
import { Database, Faders, PlugsConnected, Robot, Rocket, Sparkle, Wrench } from "@phosphor-icons/react";
import { useT } from "@/i18n";

export type SettingsTab = "prefs" | "storage" | "toml" | "mcp" | "llm" | "assistant" | "app";

const NAV_ITEMS = [
  { key: "prefs", labelKey: "settings.nav.prefs", descKey: "settings.nav.prefsDesc", Icon: Faders },
  { key: "storage", labelKey: "settings.nav.storage", descKey: "settings.nav.storageDesc", Icon: Database },
  { key: "toml", labelKey: "settings.nav.toml", descKey: "settings.nav.tomlDesc", Icon: Wrench },
  { key: "mcp", labelKey: "settings.nav.mcp", descKey: "settings.nav.mcpDesc", Icon: PlugsConnected },
  { key: "llm", labelKey: "settings.nav.llm", descKey: "settings.nav.llmDesc", Icon: Robot },
  { key: "assistant", labelKey: "settings.nav.assistant", descKey: "settings.nav.assistantDesc", Icon: Sparkle },
  { key: "app", labelKey: "settings.nav.app", descKey: "settings.nav.appDesc", Icon: Rocket },
] as const;

/** 设置中心左侧分区导航（布局共享 layoutId 高亮）。 */
export function SettingsNav({ tab, onChange }: { tab: SettingsTab; onChange: (t: SettingsTab) => void }) {
  const t = useT();
  return (
    <nav
      className="flex md:flex-col gap-1 overflow-x-auto md:overflow-visible md:sticky md:top-5 md:self-start -mx-1 px-1 py-1 md:py-0"
      aria-label={t("settings.nav.aria")}
    >
      {NAV_ITEMS.map(({ key, labelKey, descKey, Icon }) => {
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
              <span className="block text-[12.5px] font-medium leading-tight whitespace-nowrap">{t(labelKey)}</span>
              <span className="hidden md:block text-[10.5px] text-faint leading-tight mt-px whitespace-nowrap">{t(descKey)}</span>
            </span>
          </button>
        );
      })}
    </nav>
  );
}
