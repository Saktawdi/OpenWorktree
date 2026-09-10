import { useEffect, useRef, useState } from "react";
import { CornersIn, CornersOut, NotePencil, Trash } from "@phosphor-icons/react";
import { actions } from "@/app/actions";
import { openStageChangeConfirm, openTicketEditor } from "@/features/ticket";
import { useApp } from "@/store";
import type { Priority } from "@/shared/types";
import { LabelInput, useBackdropClose } from "@/shared/components/ui";
import { useT } from "@/i18n";

const PRIORITIES: Priority[] = ["P0", "P1", "P2", "P3"];

export function TicketEditDialog() {
  const t = useT();
  const editingNo = useApp((s) => s.editingTicketNo);
  const ticket = useApp((s) => s.tickets.find((t) => t.ticketNo === s.editingTicketNo));

  const [title, setTitle] = useState("");
  const [priority, setPriority] = useState<Priority>("P1");
  const [description, setDescription] = useState("");
  const [note, setNote] = useState("");
  const [labels, setLabels] = useState<string[]>([]);
  const [saving, setSaving] = useState(false);
  // 专注模式：弹窗放大为近全屏、描述占据主要空间，Esc 退出，长文本编辑不再憋屈
  const [focus, setFocus] = useState(false);

  // 仅在弹窗打开（editingNo 变化）时用 store 值播种表单。此后后台轮询（busy.ts 每 15s
  // 补拉工单列表）与各类任务回刷都会整表重建 tickets 数组、换掉 ticket 对象引用；
  // 若跟随重灌，正在编辑的内容就会被复原回 store 旧值（所有字段一起"自动复原"）。
  const seededNoRef = useRef<string | null>(null);
  useEffect(() => {
    if (!editingNo) {
      seededNoRef.current = null;
      setFocus(false);
      return;
    }
    if (!ticket || seededNoRef.current === editingNo) return;
    seededNoRef.current = editingNo;
    setTitle(ticket.title);
    setPriority(ticket.priority);
    setDescription(ticket.description ?? "");
    setNote(ticket.note ?? "");
    setLabels([...ticket.labels]);
  }, [ticket, editingNo]);

  // 专注模式下 Esc 只退回普通弹窗（保存仍需点按钮，避免误触丢焦点内容）
  useEffect(() => {
    if (!focus) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        e.stopPropagation();
        setFocus(false);
      }
    };
    window.addEventListener("keydown", onKey, true);
    return () => window.removeEventListener("keydown", onKey, true);
  }, [focus]);

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
      className="fixed inset-0 z-50 grid place-items-center bg-black/55 backdrop-blur-[2px] p-4"
      {...backdrop}
    >
      <div
        className={`card shadow-2xl shadow-black/60 animate-rise flex flex-col transition-[width] duration-200 ${
          focus ? "w-full max-w-[1100px] h-full max-h-[calc(100vh-32px)]" : "w-[480px] max-h-[calc(100vh-32px)]"
        }`}
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center gap-2.5 px-5 h-12 border-b border-edge shrink-0">
          <NotePencil size={15} className="text-dim" />
          <span className="font-mono text-[12.5px] text-accent">{ticket.ticketNo}</span>
          <span className="text-[13.5px] font-semibold">{t("wb.editTicket")}</span>
          <span className="flex-1" />
          <button
            className="icon-btn"
            onClick={() => setFocus((v) => !v)}
            title={focus ? t("edit.exitFocus") : t("edit.focusTip")}
            aria-label={focus ? t("edit.exitFocus") : t("edit.focus")}
          >
            {focus ? <CornersIn size={15} /> : <CornersOut size={15} />}
          </button>
          <button className="icon-btn" onClick={() => openTicketEditor(null)} aria-label={t("common.close")}>
            ✕
          </button>
        </div>

        <div className={`p-5 space-y-4 overflow-y-auto ${focus ? "flex-1 min-h-0 flex flex-col" : ""}`}>
          <div className={focus ? "shrink-0" : ""}>
            <label className="field-label">{t("ticket.new.titleLabel")}</label>
            <input className="text-input" value={title} onChange={(e) => setTitle(e.target.value)} />
          </div>

          <div className={focus ? "shrink-0" : ""}>
            <label className="field-label">{t("ticket.new.priorityLabel")}</label>
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

          <div className={focus ? "flex-1 min-h-[240px] flex flex-col" : ""}>
            <div className="flex items-center gap-2 shrink-0">
              <label className="field-label">{t("common.description")}</label>
              <span className="flex-1" />
              <span className="font-mono text-[10.5px] text-faint">{t("edit.charCount", { n: description.length })}</span>
            </div>
            <textarea
              className={`text-input py-2 resize-none leading-relaxed ${
                focus ? "flex-1 min-h-0 !h-auto font-mono text-[12.5px]" : "h-40"
              }`}
              placeholder={t("ticket.new.descPlaceholder")}
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              spellCheck={false}
            />
          </div>

          <div className={focus ? "shrink-0" : ""}>
            <label className="field-label">{t("edit.note")}</label>
            <textarea
              className={`text-input py-2 resize-none ${focus ? "h-20" : "h-16"}`}
              placeholder={t("edit.notePlaceholder")}
              value={note}
              onChange={(e) => setNote(e.target.value)}
            />
          </div>

          <div className={focus ? "shrink-0" : ""}>
            <label className="field-label">{t("edit.labels")}</label>
            <LabelInput labels={labels} onChange={setLabels} />
          </div>

          {!terminal && (
            <div className={`rounded-lg border border-danger/25 bg-danger/[0.04] p-3 ${focus ? "shrink-0" : ""}`}>
              <button
                className="inline-flex items-center gap-1.5 text-[12.5px] text-danger/80 hover:text-danger cursor-pointer bg-transparent border-0 p-0"
                onClick={() => {
                  // V19: 取消工单统一走共享的终态流转确认弹窗（状态变更理由必填）
                  openTicketEditor(null);
                  openStageChangeConfirm(editingNo, "CANCELLED");
                }}
              >
                <Trash size={13} />
                {t("edit.cancelTicket")}
              </button>
            </div>
          )}
        </div>

        <div className="flex justify-end gap-2 px-5 py-4 border-t border-edge shrink-0">
          <button className="btn" onClick={() => openTicketEditor(null)}>
            {t("common.close")}
          </button>
          <button className="btn btn-primary" disabled={!title.trim() || saving} onClick={save}>
            {saving ? t("llm.saving") : t("llm.saveChanges")}
          </button>
        </div>
      </div>
    </div>
  );
}
