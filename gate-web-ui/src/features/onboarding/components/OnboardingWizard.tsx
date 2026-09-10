/**
 * 新手引导弹窗（OnboardingWizard）：首次启动弹出的六步配置向导。
 *
 * 步骤：语言偏好选择 → 接入项目 → 智能体设置 → LLM 设置（AI 审查调用的上游）
 * → 看板 → 工作台；支持「跳过引导」一键跳过（Esc 同义），完成后不再自动弹出。
 *
 * 交互约定：
 * - 步骤条可直接点跳，底部「上一步 / 下一步」顺序推进，进度条与「第 N/6 步」同步；
 * - 语言步骤内联切换（即时生效、全站文案随语言重渲染，是引导本身的一部分）；
 * - 其余步骤的「前往 xxx」= 收起引导并跳到目标页面，右下角「继续引导」浮标可回到
 *   原步骤继续（末步「前往」即完成，不再亮标）；
 * - 弹窗为普通对话框层级（z-50），「接入项目…」跳转后由既有的接入弹窗接管。
 */
import { useEffect, useRef, useState } from "react";
import { AnimatePresence, motion } from "motion/react";
import type { Icon } from "@phosphor-icons/react";
import {
  CaretLeft,
  CaretRight,
  Check,
  FolderPlus,
  Globe,
  Kanban,
  PlugsConnected,
  Robot,
  Sparkle,
  SquaresFour,
  X,
} from "@phosphor-icons/react";
import { useApp, setView } from "@/store";
import { LOCALES, setLocale, useLocale, useT, type MsgKey } from "@/i18n";
import {
  finishOnboarding,
  ONBOARDING_TOTAL,
  pauseOnboarding,
  setOnboardingStep,
  type OnboardingStepId,
} from "../state";

interface WizardStep {
  id: OnboardingStepId;
  Icon: Icon;
  titleKey: MsgKey;
  descKey: MsgKey;
  actionKey?: MsgKey;
}

const STEPS: WizardStep[] = [
  { id: "lang", Icon: Globe, titleKey: "onboarding.step.lang.title", descKey: "onboarding.step.lang.desc" },
  {
    id: "project",
    Icon: FolderPlus,
    titleKey: "onboarding.step.project.title",
    descKey: "onboarding.step.project.desc",
    actionKey: "onboarding.step.project.action",
  },
  {
    id: "agent",
    Icon: Sparkle,
    titleKey: "onboarding.step.agent.title",
    descKey: "onboarding.step.agent.desc",
    actionKey: "onboarding.step.agent.action",
  },
  {
    id: "llm",
    Icon: Robot,
    titleKey: "onboarding.step.llm.title",
    descKey: "onboarding.step.llm.desc",
    actionKey: "onboarding.step.llm.action",
  },
  {
    id: "kanban",
    Icon: Kanban,
    titleKey: "onboarding.step.kanban.title",
    descKey: "onboarding.step.kanban.desc",
    actionKey: "onboarding.step.kanban.action",
  },
  {
    id: "workbench",
    Icon: SquaresFour,
    titleKey: "onboarding.step.workbench.title",
    descKey: "onboarding.step.workbench.desc",
    actionKey: "onboarding.step.workbench.action",
  },
];

export function OnboardingWizard() {
  const t = useT();
  const open = useApp((s) => s.onboardingOpen);
  const step = useApp((s) => s.onboardingStep);
  const projects = useApp((s) => s.projects.length);
  const locale = useLocale();

  // 内容切换方向（步骤前进/后退的滑动方向）；渲染期先读旧值，effect 里再写回
  const prevStep = useRef(step);
  const dir = step >= prevStep.current ? 1 : -1;
  useEffect(() => {
    prevStep.current = step;
  }, [step]);

  // 步骤条与内容区在窄窗口下的自适应宽度（弹窗本体固定 620px 上限）
  const [narrow, setNarrow] = useState(false);
  useEffect(() => {
    const mq = window.matchMedia("(max-width: 640px)");
    const sync = () => setNarrow(mq.matches);
    sync();
    mq.addEventListener("change", sync);
    return () => mq.removeEventListener("change", sync);
  }, []);

  // Esc 跳过、左右方向键翻步（弹窗内无文本输入，不与编辑冲突）
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") finishOnboarding();
      else if (e.key === "ArrowRight") setOnboardingStep(step + 1);
      else if (e.key === "ArrowLeft") setOnboardingStep(step - 1);
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [open, step]);

  if (!open) return null;

  const current = STEPS[step] ?? STEPS[0];
  const isLast = step >= ONBOARDING_TOTAL - 1;

  /** 「前往 xxx」：先收起引导（末步直接完成），再切到目标视图。 */
  const gotoTarget = (id: OnboardingStepId) => {
    if (isLast) finishOnboarding();
    else pauseOnboarding();
    switch (id) {
      case "project":
        setView("projects");
        // 视图切过去再派发（ProjectsPage 挂载后才监听 gate:new-project，同 TopBar 惯例）
        window.setTimeout(() => window.dispatchEvent(new CustomEvent("gate:new-project")), 60);
        break;
      case "agent":
        setView("agents");
        break;
      case "llm":
        setView("settings");
        // SettingsPage 挂载时才注册 gate:settings-tab 监听：同样延后一拍
        window.setTimeout(
          () => window.dispatchEvent(new CustomEvent("gate:settings-tab", { detail: "llm" })),
          60,
        );
        break;
      case "kanban":
        setView("kanban");
        break;
      case "workbench":
        setView("workbench");
        break;
      default:
        break;
    }
  };

  return (
    <div
      className="fixed inset-0 z-50 grid place-items-center bg-black/60 backdrop-blur-[2px]"
      role="dialog"
      aria-modal="true"
      aria-labelledby="onboarding-title"
    >
      <motion.div
        initial={{ opacity: 0, y: 14, scale: 0.98 }}
        animate={{ opacity: 1, y: 0, scale: 1 }}
        transition={{ type: "spring", stiffness: 420, damping: 34 }}
        className="w-[min(620px,calc(100vw-32px))] card shadow-2xl shadow-black/60 overflow-hidden"
      >
        {/* 头：品牌 + 标题 + 一键跳过 */}
        <div className="flex items-center gap-2.5 px-5 py-3 border-b border-edge">
          <span className="grid place-items-center w-6 h-6 rounded-md border shrink-0 bg-accent-dim border-accent/30 text-accent">
            <Sparkle size={13} weight="fill" />
          </span>
          <span id="onboarding-title" className="text-[13.5px] font-semibold">
            {t("onboarding.title")}
          </span>
          <span className="hidden sm:inline text-[11.5px] text-faint truncate">
            {t("onboarding.subtitle")}
          </span>
          <span className="flex-1" />
          <button
            className="btn btn-sm btn-ghost text-faint shrink-0"
            onClick={finishOnboarding}
            title={t("onboarding.skipTip")}
          >
            <X size={12} />
            {t("onboarding.skip")}
          </button>
        </div>

        {/* 步骤条：可点跳，已走过与当前步高亮 */}
        <div className="flex items-stretch gap-1 px-4 pt-3.5">
          {STEPS.map((s, i) => {
            const active = i === step;
            const done = i < step;
            return (
              <button
                key={s.id}
                onClick={() => setOnboardingStep(i)}
                className={`relative flex-1 min-w-0 flex flex-col items-center gap-1 rounded-lg px-1 py-2 cursor-pointer
                  transition-colors ${active ? "bg-raised" : "hover:bg-raised/50"}`}
                aria-current={active ? "step" : undefined}
                title={t(s.titleKey)}
              >
                <span
                  className={`grid place-items-center w-6 h-6 rounded-full border transition-colors ${
                    active
                      ? "border-accent/50 bg-accent/15 text-accent"
                      : done
                        ? "border-accent/25 bg-accent/5 text-accent/70"
                        : "border-edge bg-sunken text-faint"
                  }`}
                >
                  {done ? <Check size={12} weight="bold" /> : <s.Icon size={13} weight={active ? "fill" : "regular"} />}
                </span>
                {!narrow && (
                  <span className={`text-[10.5px] leading-tight truncate max-w-full ${active ? "text-ink" : "text-faint"}`}>
                    {t(s.titleKey)}
                  </span>
                )}
              </button>
            );
          })}
        </div>
        {/* 进度条 */}
        <div className="mx-4 mt-2 h-[3px] rounded-full bg-sunken overflow-hidden">
          <motion.div
            className="h-full rounded-full bg-accent"
            initial={false}
            animate={{ width: `${((step + 1) / ONBOARDING_TOTAL) * 100}%` }}
            transition={{ type: "spring", stiffness: 380, damping: 34 }}
          />
        </div>

        {/* 内容：方向感知滑动切换 */}
        <div className="px-5 py-4 min-h-[176px]">
          <AnimatePresence mode="wait" initial={false}>
            <motion.div
              key={current.id}
              initial={{ opacity: 0, x: dir * 22 }}
              animate={{ opacity: 1, x: 0 }}
              exit={{ opacity: 0, x: dir * -22 }}
              transition={{ duration: 0.2, ease: [0.16, 1, 0.3, 1] }}
            >
              <div className="flex items-center gap-2">
                <current.Icon size={15} weight="fill" className="text-accent shrink-0" />
                <span className="text-[14px] font-semibold">{t(current.titleKey)}</span>
              </div>
              <p className="mt-2 text-[12.5px] leading-relaxed text-dim">{t(current.descKey)}</p>

              {/* 语言：内联切换（即时生效，无需下一步） */}
              {current.id === "lang" && (
                <div className="mt-3 grid grid-cols-2 gap-2">
                  {LOCALES.map((lang) => {
                    const active = lang.id === locale;
                    return (
                      <button
                        key={lang.id}
                        onClick={() => setLocale(lang.id)}
                        aria-pressed={active}
                        className={`flex items-center gap-2.5 rounded-lg border px-3 py-2.5 text-left cursor-pointer transition-colors ${
                          active
                            ? "border-accent/40 bg-accent/10"
                            : "border-edge bg-sunken hover:border-edge-strong hover:bg-raised/40"
                        }`}
                      >
                        <span
                          className={`grid place-items-center w-5 h-5 rounded-md border shrink-0 ${
                            active ? "bg-accent-dim border-accent/30 text-accent" : "bg-raised border-edge text-faint"
                          }`}
                        >
                          {active ? <Check size={11} weight="bold" /> : <Globe size={11} />}
                        </span>
                        <span className={`text-[12.5px] font-medium ${active ? "text-accent" : "text-ink"}`}>
                          {lang.label}
                        </span>
                      </button>
                    );
                  })}
                </div>
              )}

              {/* 项目：显示当前接入状态 + 直达接入弹窗 */}
              {current.id === "project" && (
                <div className="mt-3 flex flex-wrap items-center gap-2">
                  <span
                    className={`chip border ${
                      projects > 0 ? "border-accent/30 bg-accent/10 text-accent" : "border-edge-strong bg-raised text-faint"
                    }`}
                  >
                    {projects > 0 ? (
                      <>
                        <Check size={10} weight="bold" />
                        {t("onboarding.step.project.connected", { n: projects })}
                      </>
                    ) : (
                      t("onboarding.step.project.empty")
                    )}
                  </span>
                </div>
              )}

              {/* 其余步骤的「前往 xxx」入口 */}
              {current.actionKey && (
                <div className="mt-3">
                  <button className="btn btn-sm btn-outline" onClick={() => gotoTarget(current.id)}>
                    {t(current.actionKey)}
                    <CaretRight size={11} />
                  </button>
                </div>
              )}

              {current.id === "llm" && (
                <div className="mt-3 flex items-start gap-1.5 text-[11px] text-faint leading-relaxed">
                  <PlugsConnected size={12} className="shrink-0 mt-px" />
                  {t("onboarding.step.llm.note")}
                </div>
              )}
            </motion.div>
          </AnimatePresence>
        </div>

        {/* 底：进度 + 上一步 / 下一步（末步为完成） */}
        <div className="flex items-center gap-2 px-5 py-3.5 border-t border-edge">
          <span className="font-mono text-[11px] text-faint">
            {t("onboarding.progress", { n: step + 1, total: ONBOARDING_TOTAL })}
          </span>
          <span className="flex-1" />
          <button
            className="btn btn-sm"
            disabled={step === 0}
            onClick={() => setOnboardingStep(step - 1)}
          >
            <CaretLeft size={11} />
            {t("onboarding.prev")}
          </button>
          {isLast ? (
            <button className="btn btn-sm btn-primary" onClick={finishOnboarding}>
              <Check size={12} weight="bold" />
              {t("onboarding.finish")}
            </button>
          ) : (
            <button className="btn btn-sm btn-primary" onClick={() => setOnboardingStep(step + 1)}>
              {t("onboarding.next")}
              <CaretRight size={11} />
            </button>
          )}
        </div>
      </motion.div>
    </div>
  );
}
