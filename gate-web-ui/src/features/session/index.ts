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
  syncSessionTasks,
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
export {
  liveSendPrompt,
  abortLive,
  liveSendToSession,
  isSessionStreamingLocally,
  consumeBackgroundObserver,
  isBackgroundObserving,
  stopBackgroundObserver,
  backgroundObserverIds,
  stopAllBackgroundObservers,
} from "./stream";
export { draftCatalogFromOc, splitModelRef } from "./model";
export type { PermissionResponse } from "./model";
export { focusComposer, insertIntoComposer, registerComposerBridge } from "./composerBridge";
export { applyTodosSnapshot, dropSessionTodos } from "./todos";
export { applyTasksSnapshot, dropSessionTasks } from "./tasks";
export {
  addMessageToQueue,
  removeQueuedMessage,
  popQueuedMessageToInput,
  reorderQueuedMessages,
  clearSessionQueue,
  kickQueuePump,
  setFollowUpBehavior,
  getQueuedMessages,
  EMPTY_QUEUE,
} from "./queue";
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
  updatePendingQuoteText,
  clearPendingQuotes,
  setCreatingSession,
  setBusy,
  setSessionBusy,
  refreshTicketBusy,
  markSessionEnded,
  clearSessionEnded,
  markSessionInterrupted,
  clearSessionInterrupted,
  notePendingPermission,
  notePendingQuestion,
  dropSessionPendings,
  dismissSessionAsks,
  addUsage,
  setContextTokens,
  setContextLimit,
  createSession,
  startSessionDraft,
  ensureCurrentSession,
  applyDraftGroup,
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
