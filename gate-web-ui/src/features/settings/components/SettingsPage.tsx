import { useEffect, useState } from "react";
import { AnimatePresence, motion } from "motion/react";
import { GearSix } from "@phosphor-icons/react";
import { openConnect, useApp } from "@/store";
import { useT } from "@/i18n";
import { SettingsNav, type SettingsTab } from "./SettingsNav";
import { GateTomlBlock } from "./toml/GateTomlBlock";
import { McpBlock } from "./mcp/McpBlock";
import { LlmBlock } from "./llm/LlmBlock";
import { AssistantSettingsBlock } from "./assistant/AssistantSettingsBlock";
import { AppInfoBlock } from "./app-info/AppInfoBlock";
import { PrefsBlock } from "./prefs/PrefsBlock";
import { StorageBlock } from "./storage/StorageBlock";

/** 设置中心：偏好 / 存储 / 系统（gate.toml）/ MCP / LLM / LLM 助手 / 关于 七个分区（插件管理已移至顶栏一级视图）。 */
export function SettingsPage() {
  const t = useT();
  const mode = useApp((s) => s.mode);
  const [tab, setTab] = useState<SettingsTab>("prefs");

  // 跨视图深链：小助手面板等入口经事件直达指定分区（与 gate:new-project 同一惯例）。
  useEffect(() => {
    const onTab = (e: Event) => {
      const next = (e as CustomEvent<string>).detail as SettingsTab | undefined;
      if (next) setTab(next);
    };
    window.addEventListener("gate:settings-tab", onTab);
    return () => window.removeEventListener("gate:settings-tab", onTab);
  }, []);

  if (mode === "demo") {
    // 演示模式：偏好分区（输入行为 / 语言）是纯端侧能力，照常可用；
    // 其余分区依赖后端，统一给出连接引导。
    return (
      <div className="flex-1 min-h-0 overflow-y-auto scrollbar-none">
        <div className="max-w-[1080px] mx-auto px-6 py-5">
          <div className="md:grid md:grid-cols-[196px_minmax(0,1fr)] md:gap-5">
            <SettingsNav tab={tab} onChange={setTab} />
            <div className="min-w-0 mt-4 md:mt-0">
              {tab === "prefs" ? (
                <PrefsBlock />
              ) : (
                <div className="card p-10 text-center">
                  <div className="w-12 h-12 rounded-xl bg-raised border border-edge grid place-items-center mx-auto">
                    <GearSix size={22} className="text-faint" />
                  </div>
                  <div className="mt-4 text-[15px] font-semibold">{t("settings.needBackend.title")}</div>
                  <div className="mt-1.5 text-[12.5px] text-faint leading-relaxed">{t("settings.needBackend.desc")}</div>
                  <button className="btn btn-primary mt-5" onClick={openConnect}>{t("settings.needBackend.connect")}</button>
                </div>
              )}
            </div>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="flex-1 min-h-0 overflow-y-auto scrollbar-none">
      <div className="max-w-[1080px] mx-auto px-6 py-5">
        <div className="md:grid md:grid-cols-[196px_minmax(0,1fr)] md:gap-5">
          <SettingsNav tab={tab} onChange={setTab} />
          <div className="min-w-0 mt-4 md:mt-0">
            <AnimatePresence mode="wait" initial={false}>
              <motion.div
                key={tab}
                initial={{ opacity: 0, y: 8 }}
                animate={{ opacity: 1, y: 0 }}
                exit={{ opacity: 0, y: -6 }}
                transition={{ duration: 0.18, ease: [0.16, 1, 0.3, 1] }}
              >
                {tab === "prefs" && <PrefsBlock />}
                {tab === "storage" && <StorageBlock />}
                {tab === "toml" && <GateTomlBlock />}
                {tab === "mcp" && <McpBlock />}
                {tab === "llm" && <LlmBlock />}
                {tab === "assistant" && <AssistantSettingsBlock />}
                {tab === "app" && <AppInfoBlock />}
              </motion.div>
            </AnimatePresence>
          </div>
        </div>
      </div>
    </div>
  );
}
