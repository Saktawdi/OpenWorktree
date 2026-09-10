/** 新手引导域（onboarding）：首次启动的七步配置向导。 */
export {
  finishOnboarding,
  ONBOARDING_STEPS,
  ONBOARDING_TOTAL,
  openOnboarding,
  pauseOnboarding,
  resumeOnboarding,
  setOnboardingStep,
  type OnboardingStepId,
} from "./state";
export { OnboardingWizard } from "./components/OnboardingWizard";
export { OnboardingResume } from "./components/OnboardingResume";
