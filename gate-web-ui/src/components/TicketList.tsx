import { useMemo, useState } from "react";
import { motion } from "motion/react";
import { MagnifyingGlass, Plus } from "@phosphor-icons/react";
import { actions } from "../lib/actions";
import { relativeTime, STAGE_LABEL } from "../lib/format";
import { appStore, closeTicketCreator, openTicketCreator, useApp, NO_DIFF } from "../lib/store";
import type { Priority } from "../lib/types";
import { PriorityChip, StageDot } from "./ui";

const PRIORITIES: Priority[] = ["P0", "P1", "P2", "P3"];

function NewTicketButton() {
  const open = useApp((s) => s.ticketCreatorOpen);
  const [title, setTitle] = useState("");
  const [priority, setPriority] = useState<Priority>("P1");
  const [description, setDescription] = useState("");
  const [labels, setLabels] = useState("");
  const agents = useApp((s) => s.agents);
  const agentId = useApp((s) => s.agentId);

  const submit = () => {
    if (!title.trim()) return;
    actions.newTicket(title.trim(), priority, {
      description: description.trim() || undefined,
      labels: labels
        .split(/[,，]/)
        .map((x) => x.trim())
        .filter(Boolean)
        .slice(0, 20),
      agentConfigId: agentId,
    });
    setTitle("");
    setDescription("");
    setLabels("");
    closeTicketCreator();
  };

  return (
    <>
      <button className="btn h-7 px-2.5 text-[12px]" onClick={() => (open ? closeTicketCreator() : openTicketCreator())}>
        <Plus size={13} weight="bold" />
        新建
      </button>
      {open && (
        <div
          className="fixed inset-0 z-50 grid place-items-center bg-black/55 backdrop-blur-[2px]"
          onClick={() => closeTicketCreator()}
        >
          <div
            className="w-[420px] card shadow-2xl shadow-black/60 animate-rise"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="flex items-center gap-2.5 px-5 h-12 border-b border-edge">
              <span className="text-[13px] font-semibold">新建工单</span>
              <span className="flex-1" />
              <button className="icon-btn" onClick={() => closeTicketCreator()} aria-label="关闭">
                ✕
              </button>
            </div>

            <div className="p-5 space-y-4 max-h-[70vh] overflow-y-auto">
              <div>
                <label className="field-label">标题</label>
                <input
                  autoFocus
                  className="text-input"
                  placeholder="例如：为订单接口添加幂等保护"
                  value={title}
                  onChange={(e) => setTitle(e.target.value)}
                  onKeyDown={(e) => e.key === "Enter" && submit()}
                />
              </div>

              <div>
                <label className="field-label">优先级</label>
                <div className="flex gap-1">
                  {PRIORITIES.map((p) => (
                    <button
                      key={p}
                      onClick={() => setPriority(p)}
                      className={`flex-1 h-8 rounded-lg border font-mono text-[12px] cursor-pointer transition-colors ${
                        priority === p
                          ? "border-accent/50 bg-accent/10 text-accent"
                          : "border-edge text-dim hover:text-ink hover:bg-raised"
                      }`}
                    >
                      {p}
                    </button>
                  ))}
                </div>
              </div>

              <div>
                <label className="field-label">描述（可选）</label>
                <textarea
                  className="text-input h-16 py-2 resize-none"
                  placeholder="背景、验收标准…"
                  value={description}
                  onChange={(e) => setDescription(e.target.value)}
                />
              </div>

              <div>
                <label className="field-label">标签（逗号分隔，可选）</label>
                <input
                  className="text-input"
                  placeholder="backend, security"
                  value={labels}
                  onChange={(e) => setLabels(e.target.value)}
                />
              </div>

              <div>
                <label className="field-label">协作智能体</label>
                <div className="flex gap-1">
                  {agents.map((a) => (
                    <button
                      key={a.id}
                      onClick={() => appStore.setState({ agentId: a.id })}
                      className={`flex-1 h-8 rounded-lg border text-[11.5px] cursor-pointer transition-colors truncate px-2 ${
                        agentId === a.id
                          ? "border-accent/50 bg-accent/10 text-accent"
                          : "border-edge text-dim hover:text-ink hover:bg-raised"
                      }`}
                    >
                      {a.name}
                    </button>
                  ))}
                </div>
              </div>
            </div>

            <div className="flex justify-end gap-2 px-5 py-4 border-t border-edge">
              <button className="btn" onClick={() => closeTicketCreator()}>
                取消
              </button>
              <button className="btn btn-primary" disabled={!title.trim()} onClick={submit}>
                创建并打开沙箱
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  );
}

/** T-120：工单列表运行徽标 —— 会话运行中（均衡器）与 AI 审查运行中（旋转环）动效不同。 */
function RunBadge({ kind }: { kind: "agent" | "review" }) {
  if (kind === "review") {
    return (
      <span className="run-badge run-badge-review" title="AI 审查运行中">
        <span className="review-spin" aria-hidden />
        审查中
      </span>
    );
  }
  return (
    <span className="run-badge run-badge-agent" title="会话运行中">
      <span className="eq-bars" aria-hidden>
        <i />
        <i />
        <i />
      </span>
      运行中
    </span>
  );
}

export function TicketList() {
  const ticketsAll = useApp((s) => s.tickets);
  const activeProjectId = useApp((s) => s.activeProjectId);
  const selectedNo = useApp((s) => s.selectedNo);
  const diffs = useApp((s) => s.diffs);
  const busyMap = useApp((s) => s.busy);
  const gateBusyMap = useApp((s) => s.gateBusy);
  const orderMap = useApp((s) => s.order);
  const [query, setQuery] = useState("");

  const tickets = useMemo(
    () =>
      ticketsAll
        // Unassigned tickets stay visible under any project context; otherwise a ticket
        // with no project would disappear from every list/board.
        .filter((t) => t.projectId === activeProjectId || t.projectId === "")
        .sort((a, b) => (orderMap[a.ticketNo] ?? 0) - (orderMap[b.ticketNo] ?? 0)),
    [ticketsAll, activeProjectId, orderMap],
  );

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return tickets;
    return tickets.filter(
      (t) => t.title.toLowerCase().includes(q) || t.ticketNo.toLowerCase().includes(q),
    );
  }, [tickets, query]);

  return (
    <aside className="w-[268px] shrink-0 border-r border-edge flex flex-col bg-canvas">
      <div className="flex items-center justify-between px-3 pt-3 pb-2">
        <span className="kicker px-1">工单</span>
        <NewTicketButton />
      </div>
      <div className="px-3 pb-2">
        <div className="relative">
          <MagnifyingGlass
            size={14}
            className="absolute left-2.5 top-1/2 -translate-y-1/2 text-faint"
          />
          <input
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="搜索工单"
            className="w-full h-8 rounded-lg border border-edge bg-sunken pl-8 pr-2 text-[12.5px] placeholder:text-faint focus:border-accent/50 focus:outline-none transition-colors"
          />
        </div>
      </div>

      <div className="flex-1 overflow-y-auto px-2 pb-3 space-y-0.5">
        {filtered.map((t) => {
          const active = t.ticketNo === selectedNo;
          const hasDiff = (diffs[t.ticketNo] ?? NO_DIFF).length > 0;
          // T-120：会话运行中（任一会话在跑）与 AI 审查运行中分别以不同动效呈现
          const sessionRunning = busyMap[t.ticketNo] ?? false;
          const reviewRunning = gateBusyMap[t.ticketNo] ?? false;
          const running = sessionRunning || reviewRunning;
          return (
            <motion.button
              key={t.ticketNo}
              onClick={() => actions.openTicket(t.ticketNo)}
              className={`relative w-full text-left rounded-lg px-3 py-2.5 transition-colors cursor-pointer group ${
                active ? "bg-raised" : "hover:bg-panel"
              } ${sessionRunning ? "ticket-item-run-agent" : ""}`}
              whileHover={!active ? { x: 2 } : undefined}
              transition={{ type: "spring", stiffness: 400, damping: 25 }}
            >
              {active && (
                <span className="absolute left-0 top-2 bottom-2 w-[2px] rounded-full bg-accent" />
              )}
              {sessionRunning && (
                <span
                  aria-hidden
                  className="ticket-run-bar absolute left-0 top-2 bottom-2 w-[2px] rounded-full bg-accent"
                />
              )}
              {reviewRunning && (
                <span
                  aria-hidden
                  className="ticket-item-scan pointer-events-none absolute inset-0 rounded-lg overflow-hidden"
                />
              )}
              <div className="flex items-center gap-2">
                <span className={`font-mono text-[11.5px] ${active ? "text-accent" : "text-faint"}`}>
                  {t.ticketNo}
                </span>
                <span className="flex-1" />
                <PriorityChip priority={t.priority} />
              </div>
              <div className={`mt-0.5 text-[13px] leading-snug line-clamp-2 ${active ? "text-ink" : "text-dim group-hover:text-ink"}`}>
                {t.title}
              </div>
              <div className="mt-1.5 flex items-center gap-2 text-[11px] text-faint">
                <StageDot stage={t.stage} />
                <span>{STAGE_LABEL[t.stage]}</span>
                {hasDiff && !running && (
                  <>
                    <span className="text-edge-strong">·</span>
                    <span>有变更</span>
                  </>
                )}
                {running && (
                  <span className="flex items-center gap-1">
                    {sessionRunning && <RunBadge kind="agent" />}
                    {reviewRunning && <RunBadge kind="review" />}
                  </span>
                )}
                <span className="flex-1" />
                <span>{relativeTime(t.updatedAt)}</span>
              </div>
            </motion.button>
          );
        })}
        {filtered.length === 0 && (
          <div className="mt-10 text-center text-[12.5px] text-faint">没有匹配的工单</div>
        )}
      </div>
    </aside>
  );
}
