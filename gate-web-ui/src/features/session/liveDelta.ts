/**
 * live 回合增量的合帧与时间线拼装（session）。
 *
 * 后端一个 token 一帧（Claude 的 content_block_delta 原样转发，见 SessionSseHandler），
 * 高 t/s 时每秒上百次 store 更新：每次更新都让聊天列表重渲染，并把已累积的整段正文交给
 * Markdown 全量重解析——解析量随正文长度线性增长，而增量次数同样线性增长，合计 O(n²)。
 * 实测（4k/8k 字正文，单个增量）为 8.6ms/22.0ms，逐帧刷新足以吃满主线程（T-119）。
 *
 * 这里只放纯逻辑：增量按到达序攒成 runs，回放时逐条走与"逐帧到达"完全相同的拼装函数，
 * 因此合帧只减少中间态、不改变最终数据与段落顺序（唯一差别是整帧共用一个 now，
 * 思考段的起止时间戳误差不超过一个合帧间隔）。
 *
 * 调度与 store 写入（定时器、updateLiveTurn）留在 stream.ts 的 SSE 消费侧。
 */
import type { ChatItem, TimelinePart } from "@/shared/types";

/** 一帧内到达的一个增量：text = 正文 token，thinking = 思考增量。 */
export interface DeltaRun {
  kind: "text" | "thinking";
  text: string;
}

/** 增量作用于的条目形态：live 回合就是流式中的 assistant 条目。 */
type LiveAssistant = Extract<ChatItem, { kind: "assistant" }>;

function lastThinkingIndex(parts: TimelinePart[] | undefined): number {
  if (!parts || parts.length === 0) return -1;
  const last = parts[parts.length - 1];
  return last.type === "thinking" ? parts.length - 1 : -1;
}

/** thinking_delta 到达：延续最后一段未封口思考（末位恰为 thinking），否则开新段。 */
export function upsertThinkingPart(
  parts: TimelinePart[] | undefined,
  prevThinkingDone: boolean,
  delta: string,
  now: number,
): TimelinePart[] {
  const list = parts ?? [];
  const idx = prevThinkingDone ? -1 : lastThinkingIndex(list);
  if (idx >= 0) {
    const seg = list[idx] as Extract<TimelinePart, { type: "thinking" }>;
    const next = list.slice();
    next[idx] = {
      type: "thinking",
      text: seg.text + delta,
      startedAt: seg.startedAt ?? now,
      endedAt: undefined,
    };
    return next;
  }
  return [...list, { type: "thinking", text: delta, startedAt: now, endedAt: undefined }];
}

/** 正文 token 到达：封口末段未封口思考（记录 endedAt）；无未封口段时原样返回。 */
export function sealThinkingPart(parts: TimelinePart[] | undefined, now: number): TimelinePart[] | undefined {
  if (!parts || parts.length === 0) return parts;
  const idx = lastThinkingIndex(parts);
  if (idx < 0) return parts;
  const seg = parts[idx] as Extract<TimelinePart, { type: "thinking" }>;
  if (seg.endedAt) return parts;
  const next = parts.slice();
  next[idx] = { ...seg, endedAt: now };
  return next;
}

/** 正文增量接入时间线：末位是文本段则续写（含流式光标锚点），否则新开一段；空增量跳过。 */
export function appendTextPart(parts: TimelinePart[] | undefined, delta: string): TimelinePart[] {
  const list = parts ?? [];
  if (!delta) return list;
  const last = list[list.length - 1];
  if (last && last.type === "text") {
    const next = list.slice();
    next[list.length - 1] = { type: "text", text: last.text + delta };
    return next;
  }
  return [...list, { type: "text", text: delta }];
}

/**
 * 把一批增量按到达序回放到 live 条目上。逐条应用语义与"每个增量单独到达"逐字一致：
 * 正文增量封口思考段并续写/新开文本段，思考增量延续或新开思考段。
 */
export function applyDeltaRuns(
  item: LiveAssistant,
  runs: readonly DeltaRun[],
  now: number,
): LiveAssistant {
  let next = item;
  for (const run of runs) {
    if (run.kind === "text") {
      next = {
        ...next,
        text: next.text + run.text,
        thinking: next.thinking && !next.thinking.done ? { ...next.thinking, done: true } : next.thinking,
        parts: appendTextPart(sealThinkingPart(next.parts, now), run.text),
      };
    } else {
      next = {
        ...next,
        thinking: {
          text: (next.thinking?.text ?? "") + run.text,
          startedAt: next.thinking?.startedAt ?? now,
          done: false,
        },
        parts: upsertThinkingPart(next.parts, next.thinking?.done === true, run.text, now),
      };
    }
  }
  return next;
}
