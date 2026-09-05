import { AnimatePresence, motion } from "motion/react";
import { Sparkle } from "@phosphor-icons/react";
import { useApp } from "@/store";
import { setGateSection } from "@/features/gate/state";

/** 快速模式卡（V19 超级工单：替代门禁流水线）。 */
export function QuickModeCard() {
  const expanded = useApp((s) => s.gateSections.pipeline);

  return (
    <div className="border-b border-edge">
      <button
        className="w-full sticky top-0 z-10 bg-canvas flex items-center gap-2 px-4 py-2.5 text-left hover:bg-raised transition-colors cursor-pointer"
        onClick={() => setGateSection("pipeline", !expanded)}
      >
        <Sparkle size={14} className="text-violet shrink-0" weight="fill" />
        <span className="text-[12px] font-medium text-dim">快速模式</span>
        <span className="chip border border-violet/30 bg-violet/10 text-violet">超级工单</span>
        <span className="flex-1" />
        <span
          className={`text-[11px] text-faint transition-transform duration-150 ${expanded ? "rotate-0" : "-rotate-90"}`}
        >
          ▾
        </span>
      </button>
      <AnimatePresence>
        {expanded && (
          <motion.div
            initial={{ height: 0, opacity: 0 }}
            animate={{ height: "auto", opacity: 1 }}
            exit={{ height: 0, opacity: 0 }}
            transition={{ duration: 0.2, ease: [0.16, 1, 0.3, 1] }}
            className="overflow-hidden"
          >
            <div className="px-4 pb-3 space-y-2 text-[12px] text-dim leading-relaxed">
              <div>直连项目原工作区（不经沙箱克隆），提交用普通 git 直达主分支。</div>
              <div>永不关闭、不参与门禁流转；在会话里随时与 Agent 协作，像原生 opencode 一样直接。</div>
            </div>
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
}
