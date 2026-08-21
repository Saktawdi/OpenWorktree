import { useMemo, useState } from "react";
import { MagnifyingGlass, Plus } from "@phosphor-icons/react";
import { actions } from "../lib/actions";
import { relativeTime, STAGE_LABEL } from "../lib/format";
import { appStore, useApp, NO_DIFF } from "../lib/store";
import type { Priority } from "../lib/types";
import { PriorityChip, StageDot } from "./ui";

const PRIORITIES: Priority[] = ["P0", "P1", "P2", "P3"];

function NewTicketButton() {
  const [open, setOpen] = useState(false);
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
    setOpen(false);
  };

  return (
    <div className="relative">
      <button className="btn h-7 px-2.5 text-[12px]" onClick={() => setOpen(!open)}>
        <Plus size={13} weight="bold" />
        新建
      </button>
      {open && (
        <>
          <div className="fixed inset-0 z-30" onClick={() => setOpen(false)} />
          <div className="absolute right-0 top-9 z-40 w-[320px] card p-4 shadow-2xl shadow-black/50 animate-rise">
            <div className="text-[13px] font-semibold mb-3">新建工单</div>
            <label className="field-label">标题</label>
            <input
              autoFocus
              className="text-input mb-3"
              placeholder="例如：为订单接口添加幂等保护"
              value={title}
              onChange={(e) => setTitle(e.target.value)}
              onKeyDown={(e) => e.key === "Enter" && submit()}
            />
            <label className="field-label">优先级</label>
            <div className="flex gap-1 mb-3">
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
            <label className="field-label">描述（可选）</label>
            <textarea
              className="text-input h-16 py-2 resize-none mb-3"
              placeholder="背景、验收标准…"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
            />
            <label className="field-label">标签（逗号分隔，可选）</label>
            <input
              className="text-input mb-3"
              placeholder="backend, security"
              value={labels}
              onChange={(e) => setLabels(e.target.value)}
            />
            <label className="field-label">协作智能体</label>
            <div className="flex gap-1 mb-4">
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
            <button className="btn btn-primary w-full" disabled={!title.trim()} onClick={submit}>
              创建并打开沙箱
            </button>
          </div>
        </>
      )}
    </div>
  );
}

export function TicketList() {
  const ticketsAll = useApp((s) => s.tickets);
  const activeProjectId = useApp((s) => s.activeProjectId);
  const selectedNo = useApp((s) => s.selectedNo);
  const diffs = useApp((s) => s.diffs);
  const orderMap = useApp((s) => s.order);
  const [query, setQuery] = useState("");

  const tickets = useMemo(
    () =>
      ticketsAll
        .filter((t) => t.projectId === activeProjectId)
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
          return (
            <button
              key={t.ticketNo}
              onClick={() => actions.openTicket(t.ticketNo)}
              className={`relative w-full text-left rounded-lg px-3 py-2.5 transition-colors cursor-pointer group ${
                active ? "bg-raised" : "hover:bg-panel"
              }`}
            >
              {active && (
                <span className="absolute left-0 top-2 bottom-2 w-[2px] rounded-full bg-accent" />
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
                {hasDiff && (
                  <>
                    <span className="text-edge-strong">·</span>
                    <span>有变更</span>
                  </>
                )}
                <span className="flex-1" />
                <span>{relativeTime(t.updatedAt)}</span>
              </div>
            </button>
          );
        })}
        {filtered.length === 0 && (
          <div className="mt-10 text-center text-[12.5px] text-faint">没有匹配的工单</div>
        )}
      </div>
    </aside>
  );
}
