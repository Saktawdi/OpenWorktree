import { GitBranch, NotePencil, Sparkle } from "@phosphor-icons/react";
import { NO_CHAT, openTicketCreator, openTicketEditor, useApp } from "../lib/store";
import { ChatStream } from "./ChatStream";
import { Composer } from "./Composer";
import { DiffView } from "./DiffView";
import { FindingsView } from "./FindingsView";
import { GatePanel } from "./GatePanel";
import { TicketEditDialog } from "./TicketEditDialog";
import { TicketList } from "./TicketList";
import { StageBadge } from "./ui";
import { setCenterTab } from "../lib/store";

function ContextStrip({ ticketNo }: { ticketNo: string }) {
  const ticket = useApp((s) => s.tickets.find((t) => t.ticketNo === ticketNo));
  const agent = useApp((s) => s.agents.find((a) => a.id === s.agentId));
  if (!ticket) return null;
  return (
    <div className="h-12 shrink-0 flex items-center gap-3 px-5 border-b border-edge">
      <span className="font-mono text-[12.5px] text-accent bg-accent/10 border border-accent/25 rounded-md px-2 py-0.5">
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
      <StageBadge stage={ticket.stage} />
      <span className="flex-1" />
      {ticket.labels.length > 0 && (
        <span className="hidden xl:flex gap-1">
          {ticket.labels.slice(0, 3).map((l) => (
            <span key={l} className="chip border border-edge-strong bg-raised text-faint">
              {l}
            </span>
          ))}
        </span>
      )}
      <span className="hidden lg:inline-flex items-center gap-1.5 text-[12px] text-dim">
        <GitBranch size={13} className="text-faint" />
        <span className="font-mono">main</span>
      </span>
      {agent && (
        <span className="hidden md:inline-flex items-center gap-1.5 text-[12px] text-dim">
          <Sparkle size={12} className="text-accent" weight="fill" />
          {agent.name} · <span className="font-mono text-[11px] text-faint">{agent.model}</span>
        </span>
      )}
      <button
        className="icon-btn shrink-0"
        title="编辑工单"
        aria-label="编辑工单"
        onClick={() => openTicketEditor(ticketNo)}
      >
        <NotePencil size={14} />
      </button>
    </div>
  );
}

function CenterTabs({ ticketNo }: { ticketNo: string }) {
  const tab = useApp((s) => s.centerTab);
  const diffCount = useApp((s) => s.diffs[ticketNo]?.length ?? 0);
  const findingsCount = useApp((s) => s.findings[ticketNo]?.length ?? 0);

  const item = (key: "chat" | "diff" | "findings", label: string, badge?: number) => (
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
      {item("chat", "会话")}
      {item("diff", "变更对比", diffCount)}
      {item("findings", "审查发现", findingsCount)}
    </div>
  );
}

export function Workbench() {
  const selectedNo = useApp((s) => s.selectedNo);
  const chat = useApp((s) => (s.selectedNo ? s.chats[s.selectedNo] : undefined) ?? NO_CHAT);
  const tab = useApp((s) => s.centerTab);

  if (!selectedNo) {
    // Even with no ticket selected (fresh project, nothing in progress yet) the
    // ticket list stays mounted — otherwise an empty project has no visible way
    // to create the first ticket and the whole flow deadlocks.
    return (
      <div className="flex-1 min-h-0 flex">
        <TicketList />
        <div className="flex-1 grid place-items-center">
          <div className="text-center text-faint">
            <div className="text-[14px]">当前项目还没有工单</div>
            <div className="mt-1 text-[12.5px]">创建第一个工单，开启沙箱协作</div>
            <button className="btn btn-primary mt-4" onClick={() => openTicketCreator()}>
              <NotePencil size={14} />
              新建工单
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
        {tab === "chat" && <ChatStream ticketNo={selectedNo} />}
        {tab === "diff" && <DiffView ticketNo={selectedNo} />}
        {tab === "findings" && <FindingsView ticketNo={selectedNo} />}
        {tab === "chat" && <Composer ticketNo={selectedNo} />}
      </main>
      <GatePanel ticketNo={selectedNo} />
      <TicketEditDialog />
    </div>
  );
}

export function chatIsEmpty(n: number) {
  return n === 0;
}
