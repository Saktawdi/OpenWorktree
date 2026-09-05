import { useState } from "react";
import { AnimatePresence, motion } from "motion/react";
import { GearSix } from "@phosphor-icons/react";
import { openConnect, useApp } from "@/store";
import { SettingsNav, type SettingsTab } from "./SettingsNav";
import { GateTomlBlock } from "./toml/GateTomlBlock";
import { McpBlock } from "./mcp/McpBlock";
import { LlmBlock } from "./llm/LlmBlock";
import { AppInfoBlock } from "./app-info/AppInfoBlock";

/** 设置中心：系统设置（gate.toml）/ MCP / LLM / 应用信息四个分区（插件管理已移至顶栏一级视图）。 */
export function SettingsPage() {
  const mode = useApp((s) => s.mode);
  const [tab, setTab] = useState<SettingsTab>("toml");

  if (mode === "demo") {
    return (
      <div className="flex-1 min-h-0 overflow-y-auto">
        <div className="max-w-[880px] mx-auto px-6 py-10">
          <div className="card p-10 text-center">
            <div className="w-12 h-12 rounded-xl bg-raised border border-edge grid place-items-center mx-auto">
              <GearSix size={22} className="text-faint" />
            </div>
            <div className="mt-4 text-[15px] font-semibold">设置中心需要连接后端</div>
            <div className="mt-1.5 text-[12.5px] text-faint leading-relaxed">当前为演示模式，系统设置 / MCP / LLM / 应用设置仅在连接后端后可用</div>
            <button className="btn btn-primary mt-5" onClick={openConnect}>连接后端</button>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="flex-1 min-h-0 overflow-y-auto">
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
                {tab === "toml" && <GateTomlBlock />}
                {tab === "mcp" && <McpBlock />}
                {tab === "llm" && <LlmBlock />}
                {tab === "app" && <AppInfoBlock />}
              </motion.div>
            </AnimatePresence>
          </div>
        </div>
      </div>
    </div>
  );
}
