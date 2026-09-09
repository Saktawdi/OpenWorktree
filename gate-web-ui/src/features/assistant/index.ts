/**
 * LLM 小助手域（assistant）公共出口：原生内置 mini 对话的状态动作、
 * 顶栏胶囊与悬浮面板（T-109；插件版已退役，llm.chat 数据面保留给插件系统）。
 */
export {
  askAssistant,
  clampAssistantIntoViewport,
  clearAssistantHistory,
  commitAssistantLayout,
  ensureAssistantProviders,
  getAssistantTurnStartedAt,
  goSettingsTab,
  loadAssistantProviders,
  NO_PROVIDERS,
  selectAssistantProviders,
  sendAssistantMessage,
  setAssistantDraft,
  setAssistantMinimized,
  setAssistantOpen,
  setAssistantPosLive,
  setAssistantSizeLive,
  stopAssistantMessage,
  toggleAssistant,
  updateAssistantSettings,
  setAssistantModel,
} from "./state";
export { AssistantCapsule } from "./components/AssistantCapsule";
export { AssistantPanel } from "./components/AssistantPanel";
