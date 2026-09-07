/**
 * 会话域（session）公共出口：会话/消息/流/SSE 相关的 API、状态与组件。
 */
export {
  loadTicketSessions,
  refreshTicketSessionsMeta,
  patchSessionLive,
  deleteSessionLive,
  loadSessionMessages,
  syncSessionTodos,
  ticketNoOfSession,
  uploadChatFile,
} from "./api";
export {
  loadSessionCatalog,
  switchSessionModelLive,
} from "./catalog";
export {
  answerSessionPermission,
  loadSessionPermissions,
  answerSessionQuestion,
  loadSessionQuestions,
  rejectSessionQuestion,
} from "./permissions";
export { liveSendPrompt, abortLive } from "./stream";
export { draftCatalogFromOc } from "./model";
export type { PermissionResponse } from "./model";
export { focusComposer, insertIntoComposer, registerComposerBridge } from "./composerBridge";
export {
  setSessionModels,
  setSessionModelSel,
  setDraftModelSel,
  clearDraftModelSel,
  setComposerDraft,
  clearComposerDraft,
  setPendingQuotes,
  addPendingQuote,
  removePendingQuote,
  clearPendingQuotes,
  setCreatingSession,
  setBusy,
  setSessionBusy,
  refreshTicketBusy,
  markSessionEnded,
  clearSessionEnded,
  notePendingPermission,
  notePendingQuestion,
  dropSessionPendings,
  addUsage,
  setTodos,
  setContextTokens,
  setContextLimit,
  createSession,
  startSessionDraft,
  ensureCurrentSession,
  archiveSession,
  restoreSession,
  switchSession,
  deleteSession,
} from "./state";
export {
  pushChatItem,
  pushUserMessage,
  attachUserImages,
  pushSystemMessage,
  removeChatItem,
  pushAssistantPlaceholder,
  patchAssistant,
  finishAssistant,
  applyReplyMetaDefaults,
  pushPermissionRequest,
  resolvePermission,
  revertPermission,
  pushQuestionRequest,
  resolveQuestion,
  revertQuestion,
  startLiveTurn,
  updateLiveTurn,
  finishLiveTurn,
  dropLiveTurn,
} from "./chat";
export { ChatStream } from "./components/ChatStream";
export { Composer } from "./components/Composer";
export { SelectionQuoteLayer } from "./components/SelectionQuoteLayer";
export { SessionRail } from "./components/SessionRail";
export { PermissionCard } from "./components/PermissionCard";
export { QuestionCard } from "./components/QuestionCard";
export { SessionSection } from "./components/SessionList";
