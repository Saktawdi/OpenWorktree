/**
 * 新手引导状态（onboarding）：首次启动的六步配置向导步骤机。
 *
 * 弹出判据与进度都在 store（onboardingOpen / onboardingStep / onboardingPaused），
 * 「是否已看过」落盘 localStorage（gate-onboarding-done）——完成后不再自动弹，
 * 设置 → 偏好里可随时重看（重看不影响已置位的完成标记）。
 *
 * 中途收起（步骤里的「前往 xxx」动作）只改 paused、不动完成标记：
 * 右下角「继续引导」浮标可回到原步骤，避免用户点一下跳转就丢掉整条引导。
 */
import { appStore } from "@/store";
import { saveOnboardingDone } from "@/store/prefs";

/** 步骤顺序（与需求一致：语言偏好 → 接入项目 → 智能体 → LLM → 看板 → 工作台）。 */
export const ONBOARDING_STEPS = ["lang", "project", "agent", "llm", "kanban", "workbench"] as const;
export type OnboardingStepId = (typeof ONBOARDING_STEPS)[number];
export const ONBOARDING_TOTAL = ONBOARDING_STEPS.length;

function clampStep(step: number): number {
  if (!Number.isFinite(step)) return 0;
  return Math.min(Math.max(Math.trunc(step), 0), ONBOARDING_TOTAL - 1);
}

/** 打开引导（首次自动弹出与设置页「重新查看」共用）；可指定起始步骤。 */
export function openOnboarding(step = 0) {
  appStore.setState({ onboardingOpen: true, onboardingStep: clampStep(step), onboardingPaused: false });
}

/** 跳到指定步骤（步骤条点击 / 上一步 / 下一步）。 */
export function setOnboardingStep(step: number) {
  appStore.setState({ onboardingStep: clampStep(step) });
}

/** 中途收起：保留进度并亮出「继续引导」浮标（末步收起视为完成，不再亮标）。 */
export function pauseOnboarding() {
  const st = appStore.getState();
  appStore.setState({
    onboardingOpen: false,
    onboardingPaused: st.onboardingStep < ONBOARDING_TOTAL - 1,
  });
}

/** 从浮标回到引导。 */
export function resumeOnboarding() {
  appStore.setState({ onboardingOpen: true, onboardingPaused: false });
}

/** 完成/跳过：置位完成标记、收起弹窗与浮标（可再次从设置打开）。 */
export function finishOnboarding() {
  saveOnboardingDone(true);
  appStore.setState({ onboardingOpen: false, onboardingPaused: false });
}
