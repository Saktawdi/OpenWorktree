/**
 * 工单域编排（ticket flows）：打开工单时聚合拉取 diff/会话/历史/快照/审查结果。
 * 这里是 ticket → session/gate 的唯一编排入口，域内 api 保持单向依赖。
 */
import { appStore } from "@/store";
import { loadTicketDiff } from "./api";
import { rememberLastOpened } from "./state";
import { clearSessionEnded } from "@/features/session/state";
import { clearReviewEnded } from "@/features/gate/state";
import { loadTicketSessions, loadSessionMessages } from "@/features/session/api";
import { loadSessionCatalog } from "@/features/session/catalog";
import { loadSessionPermissions, loadSessionQuestions } from "@/features/session/permissions";
import { loadEvidence, loadPresubmits, loadReviewState } from "@/features/gate/api";

export async function selectTicketLive(no: string, sid?: string) {
  // 打开工单即视为看见"会话已结束"与"审查结果已出"提醒
  clearSessionEnded(no);
  clearReviewEnded(no);
  rememberLastOpened(no);
  appStore.setState({
    selectedNo: no,
    centerTab: "chat",
    highlight: null,
    // 指定会话时（运行监控跳转）优先聚焦该会话，后续加载以其为锚点
    ...(sid ? { activeSessionId: { ...appStore.getState().activeSessionId, [no]: sid } } : {}),
  });
  const loadDiff = () => loadTicketDiff(no);
  const loadSessions = async () => {
    try {
      await loadTicketSessions(no);
      const st = appStore.getState();
      const target = sid || st.activeSessionId[no] || st.sessions[no]?.[st.sessions[no].length - 1]?.id;
      if (target) {
        await loadSessionMessages(no, target);
        void loadSessionCatalog(no, target);
        // 目标会话为 ACTIVE 时恢复未决的权限/提问卡片（若已就绪）。
        const sess = st.sessions[no]?.find((x) => x.id === target);
        if (sess?.status === "active") {
          void loadSessionPermissions(no, target);
          void loadSessionQuestions(no, target);
        }
      }
    } catch {
      /* 会话可能尚未创建 */
    }
  };
  await Promise.all([loadDiff(), loadSessions(), loadPresubmits(no), loadReviewState(no), loadEvidence(no)]);
}
