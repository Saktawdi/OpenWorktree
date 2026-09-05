import { useState } from "react";
import { Check, CircleNotch, Question as QuestionIcon, X } from "@phosphor-icons/react";
import { pushQuestionRequest, pushSystemMessage, resolveQuestion, revertQuestion, answerSessionQuestion, rejectSessionQuestion } from "@/features/session";
import type { ChatItem, QuestionPromptView } from "@/shared/types";

type QuestionItem = Extract<ChatItem, { kind: "question" }>;

/** 每个问题的本地作答状态：选中的 label 集合 + 自定义输入。 */
interface AnswerState {
  picked: string[];
  customOpen: boolean;
  customText: string;
}

function initialAnswers(questions: QuestionPromptView[]): AnswerState[] {
  return questions.map(() => ({ picked: [], customOpen: false, customText: "" }));
}

function OptionRow({
  option,
  selected,
  multiple,
  onToggle,
}: {
  option: { label: string; description?: string };
  selected: boolean;
  multiple: boolean;
  onToggle: () => void;
}) {
  return (
    <button
      className={`w-full text-left rounded-lg border px-3 py-2 transition-colors cursor-pointer ${
        selected
          ? "border-accent/60 bg-accent/10"
          : "border-edge bg-panel/40 hover:border-edge-strong hover:bg-raised/60"
      }`}
      onClick={onToggle}
    >
      <div className="flex items-center gap-2">
        <span
          className={`shrink-0 grid place-items-center border ${
            multiple ? "rounded-[4px] w-3.5 h-3.5" : "rounded-full w-3.5 h-3.5"
          } ${selected ? "border-accent bg-accent text-canvas" : "border-edge-strong"}`}
        >
          {selected && <Check size={10} weight="bold" />}
        </span>
        <span className={`text-[12.5px] ${selected ? "text-ink font-medium" : "text-dim"}`}>
          {option.label}
        </span>
      </div>
      {option.description && (
        <div className="mt-1 pl-[22px] text-[11.5px] leading-relaxed text-faint">
          {option.description}
        </div>
      )}
    </button>
  );
}

function QuestionBlock({
  prompt,
  state,
  index,
  onChange,
}: {
  prompt: QuestionPromptView;
  state: AnswerState;
  index: number;
  onChange: (next: AnswerState) => void;
}) {
  const toggle = (label: string) => {
    if (prompt.multiple) {
      const picked = state.picked.includes(label)
        ? state.picked.filter((x) => x !== label)
        : [...state.picked, label];
      onChange({ ...state, picked });
    } else {
      onChange({ ...state, picked: state.picked.includes(label) ? [] : [label] });
    }
  };

  const answered = state.picked.length > 0 || (state.customOpen && state.customText.trim().length > 0);

  return (
    <div className="px-3 py-2.5 space-y-2">
      <div className="flex items-center gap-2">
        <span className="chip border border-edge-strong bg-raised text-info">{prompt.header || `问题 ${index + 1}`}</span>
        {prompt.multiple && <span className="text-[10.5px] text-faint">可多选</span>}
        {answered && <Check size={12} className="text-accent" weight="bold" />}
      </div>
      <div className="text-[13px] leading-relaxed text-ink">{prompt.question}</div>
      <div className="space-y-1.5 pt-0.5">
        {prompt.options.map((o) => (
          <OptionRow
            key={o.label}
            option={o}
            selected={state.picked.includes(o.label)}
            multiple={prompt.multiple}
            onToggle={() => toggle(o.label)}
          />
        ))}
        {prompt.custom && (
          <div className="pt-1">
            <button
              className={`text-[11.5px] transition-colors cursor-pointer ${
                state.customOpen ? "text-accent" : "text-faint hover:text-dim"
              }`}
              onClick={() => onChange({ ...state, customOpen: !state.customOpen })}
            >
              {state.customOpen ? "− 自定义回答" : "+ 自定义回答…"}
            </button>
            {state.customOpen && (
              <input
                className="mt-1.5 w-full rounded-md border border-edge bg-sunken px-2.5 py-1.5 text-[12.5px] text-ink outline-none focus:border-accent/60"
                placeholder="输入自定义答案后回车提交"
                value={state.customText}
                onChange={(e) => onChange({ ...state, customText: e.target.value })}
              />
            )}
          </div>
        )}
      </div>
    </div>
  );
}

export function QuestionCard({
  ticketNo,
  sessionId,
  item,
  locked = false,
}: {
  ticketNo: string;
  sessionId: string;
  item: QuestionItem;
  /** 工单已取消等终态：禁止作答，仅展示。 */
  locked?: boolean;
}) {
  const { request, status } = item;
  const [answers, setAnswers] = useState<AnswerState[]>(() => initialAnswers(request.questions));
  const [busy, setBusy] = useState<"submit" | "skip" | null>(null);

  const decided = status !== "pending";
  const allAnswered = request.questions.every(
    (_, i) =>
      answers[i]?.picked.length > 0 ||
      (answers[i]?.customOpen && (answers[i]?.customText.trim().length ?? 0) > 0),
  );

  const patch = (i: number, next: AnswerState) => {
    setAnswers((prev) => prev.map((a, j) => (j === i ? next : a)));
  };

  const buildAnswers = (): string[][] =>
    request.questions.map((_, i) => {
      const a = answers[i];
      const labels = [...a.picked];
      const custom = a.customText.trim();
      if (a.customOpen && custom) labels.push(custom);
      return labels;
    });

  const submit = async () => {
    if (busy || !allAnswered) return;
    setBusy("submit");
    const payload = buildAnswers();
    // 乐观移除卡片（与权限卡片同语义），失败时恢复待决。
    resolveQuestion(ticketNo, request.requestId, false);
    const ok = await answerSessionQuestion(sessionId, request.requestId, payload);
    if (!ok) {
      revertQuestion(ticketNo, request.requestId);
      pushQuestionRequest(ticketNo, request);
      pushSystemMessage(ticketNo, "回答提交失败，已恢复待答状态", "warn");
    }
    setBusy(null);
  };

  const skip = async () => {
    if (busy) return;
    setBusy("skip");
    resolveQuestion(ticketNo, request.requestId, true);
    const ok = await rejectSessionQuestion(sessionId, request.requestId);
    if (!ok) {
      revertQuestion(ticketNo, request.requestId);
      pushQuestionRequest(ticketNo, request);
      pushSystemMessage(ticketNo, "跳过提交失败，已恢复待答状态", "warn");
    }
    setBusy(null);
  };

  return (
    <div className={`rounded-lg border divide-y divide-edge overflow-hidden ${decided ? "border-edge permission-decided" : "border-info/50 question-pending"}`}>
      <div className="px-3 py-2.5 flex items-center gap-2">
        <QuestionIcon size={15} weight="fill" className={decided ? "text-faint" : "text-info"} />
        <span className="text-[13px] font-semibold text-ink">Agent 需要你的回答</span>
        <span className={`chip ${decided ? "badge-dim border border-edge text-dim" : "text-info bg-info/10"}`}>
          {request.questions.length} 个问题
        </span>
        <span className="flex-1" />
        {status === "answered" && <span className="badge-accent">已回答</span>}
        {status === "rejected" && <span className="badge-danger">已跳过</span>}
      </div>
      {request.questions.map((q, i) => (
        <QuestionBlock
          key={`${request.requestId}-${i}`}
          prompt={q}
          state={answers[i] ?? { picked: [], customOpen: false, customText: "" }}
          index={i}
          onChange={(next) => patch(i, next)}
        />
      ))}
      {!decided && (
        <div className="px-3 py-2 bg-panel/40">
          {!locked ? (
            <div className="flex items-center gap-2">
              <button
                className="btn btn-primary btn-sm disabled:opacity-40 disabled:cursor-not-allowed"
                disabled={!allAnswered || !!busy}
                onClick={() => void submit()}
                title={allAnswered ? "提交全部回答" : "请先为每个问题作出选择"}
              >
                {busy === "submit" ? (
                  <CircleNotch size={12} className="animate-[spin_0.9s_linear_infinite]" />
                ) : (
                  <Check size={12} weight="bold" />
                )}
                提交回答
              </button>
              <button
                className="btn btn-danger-ghost btn-sm"
                disabled={!!busy}
                onClick={() => void skip()}
                title="跳过本次提问，Agent 会收到提问被搁置的通知"
              >
                {busy === "skip" ? (
                  <CircleNotch size={12} className="animate-[spin_0.9s_linear_infinite]" />
                ) : (
                  <X size={12} weight="bold" />
                )}
                跳过
              </button>
            </div>
          ) : (
            <div className="flex items-center gap-1.5 text-[11.5px] text-faint">
              <QuestionIcon size={12} />
              工单已取消 · 提问已锁定
            </div>
          )}
        </div>
      )}
    </div>
  );
}
