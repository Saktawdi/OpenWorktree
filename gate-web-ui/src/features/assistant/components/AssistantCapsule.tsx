/**
 * 小助手顶栏胶囊入口（T-109 原生内置）：点击唤起/隐藏悬浮对话面板。
 * 激活态 accent 胶囊；生成中显示呼吸点（面板收起时也能感知回复进度）。
 */
import { useT } from "@/i18n";
import { motion } from "motion/react";
import { Sparkle } from "@phosphor-icons/react";
import { useApp } from "@/store";
import { toggleAssistant } from "@/features/assistant";

export function AssistantCapsule() {
  const t = useT();
  const open = useApp((s) => s.assistantOpen);
  const loading = useApp((s) => s.assistantLoading);

  return (
    <motion.button
      type="button"
      onClick={toggleAssistant}
      aria-pressed={open}
      title={t("asst.capsuleTip")}
      whileTap={{ scale: 0.94 }}
      transition={{ type: "spring", stiffness: 500, damping: 28 }}
      className={`inline-flex h-7 shrink-0 items-center gap-1.5 rounded-full border px-2.5 text-[12px] font-medium transition-colors cursor-pointer select-none ${
        open
          ? "border-accent/45 bg-accent/12 text-accent"
          : "border-edge-strong bg-raised text-dim hover:text-ink hover:bg-edge/30"
      }`}
    >
      <Sparkle size={13} weight={open ? "fill" : "regular"} className={open ? "" : "text-faint"} />
      <span>{t("asst.capsule")}</span>
      {loading && <span className="w-1.5 h-1.5 rounded-full bg-accent animate-breathe" />}
    </motion.button>
  );
}
