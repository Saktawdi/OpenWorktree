/**
 * LLM 小助手域（assistant，T-109）：原生内置悬浮 mini 对话的状态与动作。
 *
 * 分层约定：
 *  - 数据面在 net/llm（与插件宿主 ctx.llm 共用同一实现）；
 *  - Provider/模型配置读 LLM 设置中心的 /api/providers，不另建配置入口；
 *  - 易失态（草稿/流式增量）只进 appStore，软数据（布局/历史/偏好/模型选择）
 *    统一经 store/prefs 落 localStorage。
 */
import { t } from "@/i18n";
import { appStore, showToast, type AppState } from "@/store";
import { isAbortError, llmChatStream } from "@/net";
import { fetchProviders } from "@/features/settings";
import { uid } from "@/shared/format";
import type { AssistantChatEntry, LlmProvider } from "@/shared/types";
import {
  ASSISTANT_HISTORY_CAP,
  saveAssistantHistory,
  saveAssistantLayout,
  saveAssistantModelSel,
  saveAssistantSettings,
  type AssistantLayout,
} from "@/store/prefs";

const set = appStore.setState;
const s = () => appStore.getState();

/** 进入请求上下文最近多少条对话（错误气泡不回传，不计入有效上下文）。 */
const REQUEST_WINDOW = 40;

/** 用户停止后追加在已生成内容末尾的定格标记。 */
const STOPPED_SUFFIX = () => t("asst.stoppedSuffix");

/** 当前进行中的流式请求（模块级：面板卸载/隐藏不打断对话，停止只经 stopAssistantMessage）。 */
let chatController: AbortController | null = null;

/** 本回合开始时间戳（面板重挂载后计时条也能续上；无进行中回合时是上一回合的残值）。 */
let turnStartedAt = 0;
export function getAssistantTurnStartedAt(): number {
  return turnStartedAt;
}

/* ─── 布局与开合 ─── */

function persistLayout() {
  const st = s();
  const layout: AssistantLayout = {
    open: st.assistantOpen,
    minimized: st.assistantMinimized,
    pos: st.assistantPos,
    size: st.assistantSize,
  };
  saveAssistantLayout(layout);
}

export function setAssistantOpen(open: boolean) {
  set({ assistantOpen: open });
  if (open) ensureAssistantProviders();
  persistLayout();
}

/**
 * 面板可见时确保 Provider 已加载：setAssistantOpen 之外，刷新恢复（持久化
 * open=true）、快照恢复等路径不经显式开合动作，靠面板生命周期调用本函数兜底，
 * 避免已配置模型却误显「还没有可用的模型」的假空态。
 * 已加载 / 加载中 / 上次失败（等用户点重试）时不再触发。
 */
export function ensureAssistantProviders() {
  const st = s();
  if (st.mode !== "live" || st.assistantProviders || st.assistantProvidersLoading || st.assistantProvidersError) {
    return;
  }
  void loadAssistantProviders();
}

export function toggleAssistant() {
  setAssistantOpen(!s().assistantOpen);
}

export function setAssistantMinimized(minimized: boolean) {
  set({ assistantMinimized: minimized });
  persistLayout();
}

/** 拖拽/缩放过程中只进内存（pointermove 高频），松手经 commitAssistantLayout 落盘。 */
export function setAssistantPosLive(pos: { x: number; y: number }) {
  set({ assistantPos: pos });
}

export function setAssistantSizeLive(size: { w: number; h: number }) {
  set({ assistantSize: size });
}

export function commitAssistantLayout() {
  persistLayout();
}

/** 窗口尺寸变化时把面板夹回视口（默认锚定无需处理，渲染时实时计算）。 */
export function clampAssistantIntoViewport() {
  const st = s();
  if (!st.assistantPos) return;
  const vw = window.innerWidth;
  const vh = window.innerHeight;
  const maxX = Math.max(8, vw - st.assistantSize.w - 8);
  const maxY = Math.max(8, vh - 48);
  const x = Math.min(Math.max(8, st.assistantPos.x), maxX);
  const y = Math.min(Math.max(8, st.assistantPos.y), maxY);
  if (x !== st.assistantPos.x || y !== st.assistantPos.y) {
    set({ assistantPos: { x, y } });
    persistLayout();
  }
}

/* ─── 输入与提问 ─── */

export function setAssistantDraft(text: string) {
  set({ assistantDraft: text });
}

/** 划选「询问小助手」入口：预填草稿并唤起面板（不自动发送，用户可改可删）。 */
export function askAssistant(text: string) {
  set({ assistantDraft: text, assistantMinimized: false, assistantAskNonce: s().assistantAskNonce + 1 });
  setAssistantOpen(true);
}

export function clearAssistantHistory() {
  set({ assistantMessages: [] });
  saveAssistantHistory([]);
  showToast(t("asst.clearedToast"));
}

/* ─── 本地偏好 ─── */

export function updateAssistantSettings(partial: Partial<AppState["assistantSettings"]>) {
  const next = { ...s().assistantSettings, ...partial };
  set({ assistantSettings: next });
  saveAssistantSettings(next);
}

/* ─── Provider/模型选择（数据源 = LLM 设置中心） ─── */

function modelSelValid(providers: LlmProvider[], sel: AppState["assistantModelSel"]): boolean {
  if (!sel.providerId || !sel.model) return false;
  const p = providers.find((x) => x.id === sel.providerId);
  return !!p && p.models.includes(sel.model);
}

/** 选择当前模型：立即生效并持久化（下一次发送即用）。 */
export function setAssistantModel(providerId: string, model: string) {
  const sel = { providerId, model };
  set({ assistantModelSel: sel });
  saveAssistantModelSel(sel);
}

/** 已有选择在新列表里失效时，回退首个「已配置密钥且有模型」的 Provider。 */
function reconcileAssistantModel(providers: LlmProvider[]) {
  const sel = s().assistantModelSel;
  if (modelSelValid(providers, sel)) return;
  const usable = providers.filter((p) => p.credential_configured && p.models.length > 0);
  const pool = usable.length > 0 ? usable : providers.filter((p) => p.models.length > 0);
  const chosen = pool[0];
  setAssistantModel(chosen ? chosen.id : "", chosen ? chosen.models[0] : "");
}

export async function loadAssistantProviders(force = false): Promise<void> {
  const st = s();
  if (st.assistantProvidersLoading) return;
  if (!force && st.assistantProviders) return;
  set({ assistantProvidersLoading: true, assistantProvidersError: null });
  try {
    const providers = await fetchProviders();
    set({ assistantProviders: providers, assistantProvidersLoading: false });
    reconcileAssistantModel(providers);
  } catch (e) {
    set({ assistantProvidersLoading: false, assistantProvidersError: (e as Error).message });
  }
}

/* ─── 对话 ─── */

interface ReplyMeta {
  error?: boolean;
  /** 完成本条回复的模型 ID（footer 元信息）。 */
  model?: string;
  /** 生成耗时毫秒（流式成功/停止时记录）。 */
  ms?: number;
}

function commitAssistantReply(content: string, meta: ReplyMeta = {}) {
  const entry: AssistantChatEntry = {
    id: uid("as-a"),
    role: "assistant",
    content,
    ts: Date.now(),
    ...(meta.error ? { error: true } : {}),
    ...(meta.model ? { model: meta.model } : {}),
    ...(meta.ms != null && meta.ms > 0 ? { ms: meta.ms } : {}),
  };
  const capped = [...s().assistantMessages, entry].slice(-ASSISTANT_HISTORY_CAP);
  set({ assistantMessages: capped });
  saveAssistantHistory(capped);
}

/** 发送一条消息（流式）。生成中重复调用被忽略；停止走 stopAssistantMessage。 */
export async function sendAssistantMessage(): Promise<void> {
  const st = s();
  if (st.assistantLoading) return;
  const text = st.assistantDraft.trim();
  if (!text) return;
  if (st.mode !== "live") {
    showToast(t("asst.needLiveToast"));
    return;
  }
  const userEntry: AssistantChatEntry = { id: uid("as-u"), role: "user", content: text, ts: Date.now() };
  const history = [...st.assistantMessages, userEntry];
  set({ assistantMessages: history, assistantDraft: "", assistantLoading: true, assistantStreaming: "" });
  // 用户问题先行落盘：流式期间刷新页面也不会丢提问（回复在完成/停止时再定格）。
  saveAssistantHistory(history.slice(-ASSISTANT_HISTORY_CAP));

  const controller = new AbortController();
  chatController = controller;
  turnStartedAt = Date.now();
  const modelLabel = st.assistantModelSel.model;
  const request = history
    .filter((m) => !m.error)
    .slice(-REQUEST_WINDOW)
    .map((m) => ({ role: m.role, content: m.content }));
  let acc = "";
  try {
    await llmChatStream(
      {
        messages: request,
        providerId: st.assistantModelSel.providerId || undefined,
        model: st.assistantModelSel.model || undefined,
        temperature: st.assistantSettings.temperature,
      },
      (chunk) => {
        acc += chunk;
        set({ assistantStreaming: acc });
      },
      controller.signal,
    );
    commitAssistantReply(acc || t("asst.emptyReply"), { model: modelLabel, ms: Date.now() - turnStartedAt });
  } catch (e) {
    if (controller.signal.aborted || isAbortError(e)) {
      // 用户主动停止不是失败：正常气泡定格已生成部分（无内容时给一行占位说明）。
      commitAssistantReply(acc ? `${acc}${STOPPED_SUFFIX()}` : t("asst.stoppedReply"), {
        model: modelLabel,
        ms: Date.now() - turnStartedAt,
      });
    } else {
      commitAssistantReply(t("asst.requestFailedMsg", { err: (e as Error).message }), { error: true });
    }
  } finally {
    if (chatController === controller) chatController = null;
    set({ assistantLoading: false, assistantStreaming: "" });
  }
}

/** 中止生成中的回复（无进行中请求时为 no-op）。 */
export function stopAssistantMessage() {
  chatController?.abort();
}

/* ─── 便捷选择器（组件用稳定引用，避免每渲染新建数组） ─── */

export function selectAssistantProviders(st: AppState): LlmProvider[] {
  return st.assistantProviders ?? NO_PROVIDERS;
}

export const NO_PROVIDERS: LlmProvider[] = [];

/** 设置中心深链：跨视图跳转并直达指定分区（与 TopBar 的 gate:new-project 同一事件惯例；
 *  事件延迟一拍派发，等 SettingsPage 挂载并挂上监听）。 */
export function goSettingsTab(tab: string) {
  set({ view: "settings" });
  window.setTimeout(() => window.dispatchEvent(new CustomEvent("gate:settings-tab", { detail: tab })), 60);
}
