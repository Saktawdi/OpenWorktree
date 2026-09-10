import { CaretDoubleLeft, CaretDoubleRight, GitBranch, NotePencil, Sparkle, TerminalWindow } from "@phosphor-icons/react";
import { AnimatePresence, motion } from "motion/react";
import { NO_CHAT, showToast, useApp, appStore, setCenterTab } from "@/store";
import { openTicketCreator, openTicketEditor, loadDiffFile } from "@/features/ticket";
import { setGatePanelCollapsed } from "@/features/gate";
import { openTerminalSession } from "@/features/project";
import { ChatStream } from "@/features/session/components/ChatStream";
import { Composer } from "@/features/session/components/Composer";
import { DiffView } from "@/shared/components/DiffView";
import { FindingsView } from "@/features/gate/components/FindingsView";
import { EvidenceView } from "@/features/gate/components/EvidenceView";
import { GatePanel } from "@/features/gate";
import { SessionRail } from "@/features/session/components/SessionRail";
import { TicketEditDialog } from "@/features/ticket/components/TicketEditDialog";
import { TicketList } from "@/features/ticket/components/TicketList";
import { StageBadge } from "@/shared/components/ui";
import { useT } from "@/i18n";
function ContextStrip({ ticketNo }: { ticketNo: string }) {
  const t = useT();
  const ticket = useApp((s) => s.tickets.find((t) => t.ticketNo === ticketNo));
  const project = useApp((s) => s.projects.find((p) => p.id === ticket?.projectId));
  // agent 跟随「当前查看的会话」（会话 1:1 agent，创建时固化）；
  // 无激活会话（草稿态）时显示全局默认，即草稿里 AgentPicker 当前选的协作对象。
  const sessionId = useApp((s) => s.activeSessionId[ticketNo] ?? "");
  const sessionAgentId = useApp(
    (s) => (s.sessions[ticketNo] ?? []).find((x) => x.id === sessionId)?.agentConfigId ?? null,
  );
  const agent = useApp((s) => s.agents.find((a) => a.id === (sessionAgentId ?? s.agentId)));
  const agentHint = sessionId
    ? t("wb.agentHint.session")
    : t("wb.agentHint.draft");
  const panelCollapsed = useApp((s) => s.gatePanelCollapsed);
  if (!ticket) return null;
  // 分支徽标展示项目主分支（建单基线）；未挂项目的工单退回显示自身锁定的目标分支
  const branch = (project?.targetRef ?? ticket.targetRef).replace("refs/heads/", "");
  return (
    <div className="h-12 shrink-0 flex items-center gap-3 px-5 border-b border-edge overflow-hidden">
      <span className="font-mono text-[12.5px] text-accent bg-accent/10 border border-accent/25 rounded-md px-2 py-0.5 shrink-0">
        {ticket.ticketNo}
      </span>
      <span
        className="text-[13.5px] font-medium truncate max-w-[360px]"
        title={ticket.description ?? ticket.title}
      >
        {ticket.title}
      </span>
      {(ticket.description || ticket.note) && (
        <span
          className="w-1.5 h-1.5 rounded-full bg-info/70 shrink-0"
          title={[ticket.description, ticket.note].filter(Boolean).join("\n——\n")}
        />
      )}
      {ticket.isSuper && (
        <span
          className="chip shrink-0 border border-violet/30 bg-violet/10 text-violet"
          title={t("wb.superTip")}
        >
          {t("ticket.list.quickMode")}
        </span>
      )}
      {/* 标题是唯一的可收缩项（truncate 吸收挤压）；其余原子元素一律 shrink-0，
          避免“进行中”徽标被压缩成一字一行 */}
      <span className="shrink-0">
        <StageBadge stage={ticket.stage} />
      </span>
      <span className="flex-1" />
      {ticket.labels.length > 0 && (
        <span className="hidden min-[1360px]:flex shrink-0 gap-1">
          {ticket.labels.slice(0, 3).map((l) => (
            <span key={l} className="chip border border-edge-strong bg-raised text-faint">
              {l}
            </span>
          ))}
        </span>
      )}
      <span
        className="hidden lg:inline-flex shrink-0 items-center gap-1.5 text-[12px] text-dim"
        title={project ? t("wb.projectBranch") : t("wb.ticketBranch")}
      >
        <GitBranch size={13} className="text-faint" />
        <span className="font-mono">{branch}</span>
      </span>
      {agent && (
        <span
          className="hidden md:inline-flex shrink-0 items-center gap-1.5 text-[12px] text-dim whitespace-nowrap"
          title={agentHint}
        >
          <Sparkle size={12} className="text-accent" weight="fill" />
          {agent.name}
        </span>
      )}
      <button
        className="icon-btn shrink-0"
        title={t("wb.editTicket")}
        aria-label={t("wb.editTicket")}
        onClick={() => openTicketEditor(ticketNo)}
      >
        <NotePencil size={14} />
      </button>
      {/* 右侧工单面板整栏开关（最右入口）：收起后中部区域占满整行 */}
      <button
        className="icon-btn shrink-0"
        title={panelCollapsed ? t("wb.expandPanel") : t("wb.collapsePanel")}
        aria-label={panelCollapsed ? t("wb.expandPanel") : t("wb.collapsePanel")}
        onClick={() => setGatePanelCollapsed(!panelCollapsed)}
      >
        {panelCollapsed ? <CaretDoubleLeft size={14} /> : <CaretDoubleRight size={14} />}
      </button>
    </div>
  );
}

function CenterTabs({ ticketNo }: { ticketNo: string }) {
  const t = useT();
  const tab = useApp((s) => s.centerTab);
  const diffCount = useApp((s) => s.diffs[ticketNo]?.length ?? 0);
  const findingsCount = useApp((s) => s.findings[ticketNo]?.length ?? 0);
  const mode = useApp((s) => s.mode);

  // 跳过目录选择：直接以当前工单克隆目录为 base 拉起终端标签
  const openTicketTerminal = () => {
    if (mode !== "live") {
      showToast(t("wb.terminalNeedLive"));
      return;
    }
    const ticket = appStore.getState().tickets.find((t) => t.ticketNo === ticketNo);
    if (!ticket?.clonePath) {
      showToast(t("wb.noClonePath"));
      return;
    }
    const project = appStore.getState().projects.find((p) => p.id === ticket.projectId);
    openTerminalSession({
      projectId: ticket.projectId ?? "",
      projectName: project?.name ?? ticket.projectId ?? "",
      dir: ticket.clonePath,
      label: ticket.ticketNo,
    });
  };

  const item = (key: "chat" | "diff" | "findings" | "evidence", label: string, badge?: number) => (
    <button
      onClick={() => setCenterTab(key)}
      className={`relative h-full px-3 text-[12.5px] font-medium transition-colors cursor-pointer ${
        tab === key ? "text-ink" : "text-dim hover:text-ink"
      }`}
    >
      {label}
      {badge !== undefined && badge > 0 && (
        <span
          className={`ml-1.5 font-mono text-[10.5px] rounded-full px-1.5 py-px ${
            key === "findings"
              ? "bg-danger/15 text-danger"
              : "bg-raised border border-edge text-dim"
          }`}
        >
          {badge}
        </span>
      )}
      {tab === key && (
        <span className="absolute left-3 right-3 bottom-0 h-[2px] rounded-full bg-accent" />
      )}
    </button>
  );

  return (
    <div className="h-10 shrink-0 flex items-stretch gap-1 px-4 border-b border-edge">
      {item("chat", t("wb.tab.chat"))}
      {item("diff", t("wb.tab.diff"), diffCount)}
      {item("findings", t("wb.tab.findings"), findingsCount)}
      {item("evidence", t("wb.tab.evidence"))}
      <span className="flex-1" />
      <SessionRail ticketNo={ticketNo} />
      <button
        className="self-center icon-btn"
        title={t("wb.openTerminal")}
        aria-label={t("wb.ticketTerminal")}
        onClick={openTicketTerminal}
      >
        <TerminalWindow size={14} />
      </button>
    </div>
  );
}

export function Workbench() {
  const t = useT();
  const selectedNo = useApp((s) => s.selectedNo);
  const chat = useApp((s) => (s.selectedNo ? s.chats[s.selectedNo] : undefined) ?? NO_CHAT);
  const tab = useApp((s) => s.centerTab);
  const panelCollapsed = useApp((s) => s.gatePanelCollapsed);

  if (!selectedNo) {
    // Even with no ticket selected (fresh project, nothing in progress yet) the
    // ticket list stays mounted — otherwise an empty project has no visible way
    // to create the first ticket and the whole flow deadlocks.
    return (
      <div className="flex-1 min-h-0 flex">
        <TicketList />
        <div className="flex-1 grid place-items-center">
          <div className="text-center text-faint">
            <div className="text-[14px]">{t("wb.noTickets")}</div>
            <div className="mt-1 text-[12.5px]">{t("wb.noTicketsHint")}</div>
            <button className="btn btn-primary mt-4" onClick={() => openTicketCreator()}>
              <NotePencil size={14} />
              {t("kanban.addTicket")}
            </button>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="flex-1 min-h-0 flex">
      <TicketList />
      <main className="flex-1 min-w-0 flex flex-col">
        <ContextStrip ticketNo={selectedNo} />
        <CenterTabs ticketNo={selectedNo} />
        {tab === "chat" && (
          <div className="relative flex-1 min-h-0 flex flex-col">
            <ChatStream ticketNo={selectedNo} />
            <Composer ticketNo={selectedNo} />
          </div>
        )}
        {tab === "diff" && (
          <DiffView
            ticketNo={selectedNo}
            loadFile={(p) => loadDiffFile(selectedNo, p)}
          />
        )}
        {tab === "findings" && <FindingsView ticketNo={selectedNo} />}
        {tab === "evidence" && <EvidenceView ticketNo={selectedNo} />}
      </main>
      {/* 面板整栏收起/展开：宽度过渡（overflow-hidden 裁切内容），与右侧面板分段折叠同一套缓动；
          display:flex 让内部 aside 沿交叉轴撑满全高——否则面板塌陷为内容高度，底部操作区悬在中间 */}
      <AnimatePresence initial={false}>
        {!panelCollapsed && (
          <motion.div
            key="gate-panel"
            initial={{ width: 0, opacity: 0 }}
            animate={{ width: "auto", opacity: 1 }}
            exit={{ width: 0, opacity: 0 }}
            transition={{ duration: 0.2, ease: [0.16, 1, 0.3, 1] }}
            className="flex shrink-0 overflow-hidden"
          >
            <GatePanel ticketNo={selectedNo} />
          </motion.div>
        )}
      </AnimatePresence>
      <TicketEditDialog />
    </div>
  );
}

export function chatIsEmpty(n: number) {
  return n === 0;
}
