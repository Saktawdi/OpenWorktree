import { useEffect, useRef, useState } from "react";
import { CaretDown, Check, PaperPlaneRight, Sparkle, Stop } from "@phosphor-icons/react";
import { actions } from "../lib/actions";
import { appStore, requestCancel, setAgentId, useApp } from "../lib/store";
import { formatTokens } from "../lib/format";

function AgentPicker() {
  const agents = useApp((s) => s.agents);
  const agentId = useApp((s) => s.agentId);
  const [open, setOpen] = useState(false);
  const current = agents.find((a) => a.id === agentId) ?? agents[0];

  return (
    <div className="relative">
      <button
        className="btn h-7 px-2.5 text-[12px]"
        onClick={() => setOpen(!open)}
        title="选择协作的 Agent"
      >
        <Sparkle size={12} className="text-accent" weight="fill" />
        {current ? `${current.name} · ${current.model}` : "选择 Agent"}
        <CaretDown size={11} />
      </button>
      {open && (
        <>
          <div className="fixed inset-0 z-30" onClick={() => setOpen(false)} />
          <div className="absolute bottom-9 left-0 z-40 w-[260px] card p-1.5 shadow-2xl shadow-black/50 animate-rise">
            {agents.map((a) => (
              <button
                key={a.id}
                className={`w-full flex items-center gap-2 px-2.5 h-9 rounded-lg text-left text-[12.5px] cursor-pointer transition-colors ${
                  a.id === agentId ? "bg-raised text-ink" : "text-dim hover:bg-raised hover:text-ink"
                }`}
                onClick={() => {
                  setAgentId(a.id);
                  setOpen(false);
                }}
              >
                <Sparkle size={13} className={a.cli === "claude" ? "text-accent" : "text-info"} weight="fill" />
                <span className="font-medium">{a.name}</span>
                <span className="font-mono text-[11px] text-faint">{a.model}</span>
                <span className="flex-1" />
                {a.id === agentId && <Check size={13} className="text-accent" weight="bold" />}
              </button>
            ))}
          </div>
        </>
      )}
    </div>
  );
}

export function Composer({ ticketNo }: { ticketNo: string }) {
  const busy = useApp((s) => s.busy[ticketNo] ?? false);
  const stage = useApp((s) => s.tickets.find((t) => t.ticketNo === ticketNo)?.stage);
  const diffs = useApp((s) => s.diffs[ticketNo]?.length ?? 0);
  const findingsCount = useApp((s) => s.findings[ticketNo]?.length ?? 0);
  const usage = useApp((s) => s.usage[ticketNo]);
  const [text, setText] = useState("");
  const taRef = useRef<HTMLTextAreaElement>(null);

  useEffect(() => {
    const ta = taRef.current;
    if (!ta) return;
    ta.style.height = "0px";
    ta.style.height = Math.min(140, Math.max(40, ta.scrollHeight)) + "px";
  }, [text]);

  const terminal = stage === "DONE" || stage === "CANCELLED";

  const send = () => {
    const t = text.trim();
    if (!t || busy || terminal) return;
    setText("");
    actions.sendPrompt(ticketNo, t);
  };

  const quick = [
    diffs === 0 && !terminal
      ? { label: "实现速率限制", prompt: "为 POST /api/checkout 添加速率限制，超限返回 429" }
      : null,
    diffs > 0 ? { label: "解释当前变更", prompt: "请解释当前工作区的全部改动" } : null,
    { label: "运行本地单测", prompt: "运行本地单元测试并汇总结果" },
    findingsCount > 0 && stage === "REJECTED"
      ? { label: "按审查意见修复", prompt: "__findings__" }
      : null,
  ].filter(Boolean) as Array<{ label: string; prompt: string }>;

  return (
    <div className="shrink-0 border-t border-edge bg-panel/50 px-5 py-3">
      <div className="max-w-[760px] mx-auto space-y-2.5">
        {!terminal && (
          <div className="flex flex-wrap gap-1.5">
            {quick.map((q) => (
              <button
                key={q.label}
                disabled={busy}
                className="chip border border-edge bg-canvas text-dim hover:text-ink hover:border-edge-strong transition-colors cursor-pointer disabled:opacity-40 disabled:pointer-events-none h-6.5 px-2.5"
                onClick={() => {
                  if (q.prompt === "__findings__") actions.returnWithFindings(ticketNo);
                  else actions.sendPrompt(ticketNo, q.prompt);
                }}
              >
                {q.label}
              </button>
            ))}
          </div>
        )}

        <div className="relative">
          <textarea
            ref={taRef}
            value={text}
            disabled={terminal}
            onChange={(e) => setText(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter" && !e.shiftKey) {
                e.preventDefault();
                send();
              }
            }}
            rows={1}
            placeholder={
              terminal
                ? "工单已完成并归档"
                : busy
                  ? "Agent 正在工作，可点击右侧按钮中断…"
                  : "向 Agent 描述任务…（Enter 发送，Shift+Enter 换行）"
            }
            className="w-full resize-none rounded-xl border border-edge bg-sunken pl-3.5 pr-14 py-2.5 text-[13.5px] leading-relaxed placeholder:text-faint focus:border-accent/50 focus:outline-none transition-colors disabled:opacity-60"
          />
          <div className="absolute right-2.5 bottom-2.5">
            {busy ? (
              <button
                className="btn btn-danger-ghost w-8 h-8 p-0 rounded-lg"
                title="中断生成"
                aria-label="中断生成"
                onClick={() => requestCancel(ticketNo)}
              >
                <Stop size={15} weight="fill" />
              </button>
            ) : (
              <button
                className="btn btn-primary w-8 h-8 p-0 rounded-lg"
                title="发送"
                aria-label="发送"
                disabled={!text.trim() || terminal}
                onClick={send}
              >
                <PaperPlaneRight size={15} weight="fill" />
              </button>
            )}
          </div>
        </div>

        <div className="flex items-center gap-2">
          <AgentPicker />
          <span className="flex-1" />
          {usage && (
            <span className="font-mono text-[11px] text-faint tabular-nums">
              ↑ {formatTokens(usage.promptTokens)} · ↓ {formatTokens(usage.completionTokens)}
            </span>
          )}
        </div>
      </div>
    </div>
  );
}

export function composerAlive(ticketNo: string): boolean {
  return appStore.getState().busy[ticketNo] === true;
}
