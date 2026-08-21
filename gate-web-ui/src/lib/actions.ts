import * as demo from "./engine";
import * as live from "./api";
import {
  appStore,
  createTicket,
  pushSystemMessage,
  seedDemo,
  selectTicket,
  setStage,
  setVerdict,
  wipePersisted,
} from "./store";

export const actions = {
  sendPrompt(no: string, text: string) {
    if (appStore.getState().mode === "live") return live.liveSendPrompt(no, text);
    return demo.demoSendPrompt(no, text);
  },
  returnWithFindings(no: string) {
    const st = appStore.getState();
    const findings = st.findings[no] ?? [];
    if (findings.length === 0) return;
    if (st.mode === "live") {
      const text = ["请按以下审查意见逐条修复：", demo.findingsToPromptText(findings)].join("\n");
      return live.liveSendPrompt(no, text);
    }
    return demo.demoReturnWithFindings(no);
  },
  presubmit(no: string) {
    return appStore.getState().mode === "live" ? live.livePresubmit(no) : demo.demoPresubmit(no);
  },
  review(no: string) {
    return appStore.getState().mode === "live" ? live.liveReview(no) : demo.demoReview(no);
  },
  publish(no: string) {
    return appStore.getState().mode === "live" ? live.livePublish(no) : demo.demoPublish(no);
  },
  newTicket(title: string, priority: "P0" | "P1" | "P2" | "P3") {
    if (appStore.getState().mode === "live") {
      const no = `T-${Date.now().toString().slice(-5)}`;
      fetch(`/api/tickets`, {
        method: "POST",
        headers: { "Content-Type": "application/json", Authorization: `Bearer ${appStore.getState().token}` },
        body: JSON.stringify({ ticket_no: no, title, priority }),
      })
        .then(() => live.loadTickets())
        .then(() => selectTicket(no))
        .catch((e) => console.error(e));
      return no;
    }
    return createTicket(title, priority);
  },
  openTicket(no: string) {
    appStore.setState({ view: "workbench" });
    if (appStore.getState().mode === "live") return live.selectTicketLive(no);
    selectTicket(no);
  },
  async connectLive(token: string) {
    const ok = await live.verifyToken(token);
    if (!ok) return false;
    wipePersisted();
    appStore.setState({ mode: "live", token, conn: "ok" });
    try {
      await live.loadTickets();
      const first = appStore.getState().tickets[0];
      if (first) await live.selectTicketLive(first.ticketNo);
    } catch {
      /* 列表加载失败不阻断连接 */
    }
    return true;
  },
  useDemo() {
    wipePersisted();
    seedDemo(true);
  },
  overridePass(no: string) {
    setVerdict(no, {
      verdict: "PASS",
      reason: "人工核准放行",
      engineId: "human/override",
      round: appStore.getState().snapshots[no]?.length ?? 1,
      authorizationId: "manual-" + Date.now().toString(36),
    });
    setStage(no, "READY_TO_PUBLISH");
    pushSystemMessage(no, "人工核准通过 · 发布授权已签发", "success");
  },
};
export async function boot() {
  seedDemo();
  const conn = await live.detectBackend();
  appStore.setState({ conn });
}
