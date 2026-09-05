import { useEffect, useState } from "react";
import { NotePencil, Trash } from "@phosphor-icons/react";
import { actions } from "../lib/actions";
import { openStageChangeConfirm, openTicketEditor, useApp } from "../lib/store";
import type { Priority } from "../lib/types";
import { LabelInput, useBackdropClose } from "./ui";

const PRIORITIES: Priority[] = ["P0", "P1", "P2", "P3"];

export function TicketEditDialog() {
  const editingNo = useApp((s) => s.editingTicketNo);
  const ticket = useApp((s) => s.tickets.find((t) => t.ticketNo === s.editingTicketNo));

  const [title, setTitle] = useState("");
  const [priority, setPriority] = useState<Priority>("P1");
  const [description, setDescription] = useState("");
  const [note, setNote] = useState("");
  const [labels, setLabels] = useState<string[]>([]);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    if (!ticket) return;
    setTitle(ticket.title);
    setPriority(ticket.priority);
    setDescription(ticket.description ?? "");
    setNote(ticket.note ?? "");
    setLabels([...ticket.labels]);
  }, [ticket, editingNo]);

  const backdrop = useBackdropClose(() => openTicketEditor(null));

  if (!editingNo || !ticket) return null;

  const save = async () => {
    if (!title.trim()) return;
    setSaving(true);
    // 协作 Agent 归会话管（会话创建时独立选择），工单编辑不再涉及 agent 绑定。
    await actions.editTicket(editingNo, {
      title: title.trim(),
      priority,
      description: description.trim() || undefined,
      note: note.trim() || undefined,
      labels,
    });
    setSaving(false);
    openTicketEditor(null);
  };

  const terminal = ticket.stage === "DONE" || ticket.stage === "CANCELLED";

  return (
    <div
      className="fixed inset-0 z-50 grid place-items-center bg-black/55 backdrop-blur-[2px]"
      {...backdrop}
    >
      <div
        className="w-[480px] card shadow-2xl shadow-black/60 animate-rise"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center gap-2.5 px-5 h-12 border-b border-edge">
          <NotePencil size={15} className="text-dim" />
          <span className="font-mono text-[12.5px] text-accent">{ticket.ticketNo}</span>
          <span className="text-[13.5px] font-semibold">编辑工单</span>
          <span className="flex-1" />
          <button className="icon-btn" onClick={() => openTicketEditor(null)} aria-label="关闭">
            ✕
          </button>
        </div>

        <div className="p-5 space-y-4 max-h-[70vh] overflow-y-auto">
          <div>
            <label className="field-label">标题</label>
            <input className="text-input" value={title} onChange={(e) => setTitle(e.target.value)} />
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
            <label className="field-label">描述</label>
            <textarea
              className="text-input h-20 py-2 resize-none"
              placeholder="背景、验收标准…"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
            />
          </div>

          <div>
            <label className="field-label">备注</label>
            <textarea
              className="text-input h-16 py-2 resize-none"
              placeholder="压测基线、关联信息…"
              value={note}
              onChange={(e) => setNote(e.target.value)}
            />
          </div>

          <div>
            <label className="field-label">标签</label>
            <LabelInput labels={labels} onChange={setLabels} />
          </div>

          {!terminal && (
            <div className="rounded-lg border border-danger/25 bg-danger/[0.04] p-3">
              <button
                className="inline-flex items-center gap-1.5 text-[12.5px] text-danger/80 hover:text-danger cursor-pointer bg-transparent border-0 p-0"
                onClick={() => {
                  // V19: 取消工单统一走共享的终态流转确认弹窗（状态变更理由必填）
                  openTicketEditor(null);
                  openStageChangeConfirm(editingNo, "CANCELLED");
                }}
              >
                <Trash size={13} />
                取消工单（转入已取消状态）
              </button>
            </div>
          )}
        </div>

        <div className="flex justify-end gap-2 px-5 py-4 border-t border-edge">
          <button className="btn" onClick={() => openTicketEditor(null)}>
            关闭
          </button>
          <button className="btn btn-primary" disabled={!title.trim() || saving} onClick={save}>
            {saving ? "保存中…" : "保存修改"}
          </button>
        </div>
      </div>
    </div>
  );
}
