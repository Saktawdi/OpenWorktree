/**
 * 「继续引导」浮标（OnboardingResume）：引导被「前往 xxx」中途收起后，
 * 左下角亮出的恢复入口——点主体回到原步骤，点 ✕ 表示不再提示（置位完成标记）。
 * 常态不出现：完成/跳过后 onboardingPaused 已是 false。
 */
import { AnimatePresence, motion } from "motion/react";
import { ArrowRight, Sparkle, X } from "@phosphor-icons/react";
import { useApp } from "@/store";
import { useT } from "@/i18n";
import { finishOnboarding, ONBOARDING_TOTAL, resumeOnboarding } from "../state";

export function OnboardingResume() {
  const t = useT();
  const open = useApp((s) => s.onboardingOpen);
  const paused = useApp((s) => s.onboardingPaused);
  const step = useApp((s) => s.onboardingStep);
  const visible = paused && !open;
  // 引导进入末步后收起：当作已完成（不在末步逗留、不亮浮标）
  const lastStep = step >= ONBOARDING_TOTAL - 1;

  return (
    <AnimatePresence>
      {visible && !lastStep && (
        <motion.div
          initial={{ opacity: 0, y: 12 }}
          animate={{ opacity: 1, y: 0 }}
          exit={{ opacity: 0, y: 12 }}
          transition={{ type: "spring", stiffness: 420, damping: 34 }}
          className="fixed left-5 bottom-5 z-40 flex items-center gap-1 rounded-full border border-edge-strong
            bg-overlay pl-2.5 pr-1 py-1 shadow-xl shadow-black/40"
        >
          <button
            type="button"
            onClick={resumeOnboarding}
            className="inline-flex items-center gap-1.5 h-6 rounded-full pr-1 text-[12px] font-medium text-dim
              cursor-pointer bg-transparent border-0 hover:text-ink transition-colors"
            title={t("onboarding.resumeTip")}
          >
            <Sparkle size={12} weight="fill" className="text-accent" />
            {t("onboarding.resume", { n: step + 1, total: ONBOARDING_TOTAL })}
            <ArrowRight size={11} className="text-faint" />
          </button>
          <button
            type="button"
            onClick={finishOnboarding}
            className="grid place-items-center w-5 h-5 rounded-full text-faint cursor-pointer
              hover:text-ink hover:bg-raised transition-colors"
            title={t("onboarding.resumeDismiss")}
            aria-label={t("onboarding.resumeDismiss")}
          >
            <X size={10} weight="bold" />
          </button>
        </motion.div>
      )}
    </AnimatePresence>
  );
}
