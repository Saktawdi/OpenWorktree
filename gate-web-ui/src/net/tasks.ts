/**
 * 任务轮询（net）：后端异步任务（审查/发布）的状态查询。
 * 轮询上限跟随引擎配置，连续失败容忍网络抖动，终态按 FAILED/CANCELLED 处理。
 */
import { api } from "./http";
import { appStore } from "@/store";
import { sleep } from "@/shared/format";

/** 任务失败时后端写入 error_json 的结构（TaskRunner.fail）。 */
export interface TaskError {
  error_code: number;
  error: string;
  message: string;
}

export interface TaskOutcome {
  ok: boolean;
  error?: TaskError;
}

export async function pollTask(
  taskId: string,
  onProgress?: (percent: number, label: string) => void,
): Promise<TaskOutcome> {
  // 轮询查询本身是脆弱链路：任一单次 GET 抖动（代理重启、超时）若直接判死，
  // 会出现"未知错误"卡片而任务其实在后端正常跑完。连续失败 N 次才算查询不可用；
  // 期间任务照常 RUNNING，终态才按 FAILED/CANCELLED 处理。
  const MAX_CONSECUTIVE_ERRORS = 6;
  // 轮询上限跟随引擎配置：gate-engine 慢模型一次要几分钟，没人能把写死的 240×500ms 等完。
  const engine = appStore.getState().engine;
  const engineTimeoutSec = engine?.timeoutSeconds ?? 120;
  // 轮询上限 = 引擎超时 + 心跳缓冲（30s），再加 6 个连续错误的兜底防线
  const maxIterations = Math.ceil((engineTimeoutSec + 30) / 0.5);
  let consecutiveErrors = 0;
  for (let i = 0; i < maxIterations; i++) {
    await sleep(500);
    let t: { status: string; result_json?: string | null; error_json?: string | null };
    try {
      t = await api<{ status: string; result_json?: string | null; error_json?: string | null }>(
        `/api/tasks/${taskId}`,
      );
    } catch {
      consecutiveErrors++;
      if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
        return {
          ok: false,
          error: {
            error_code: 0,
            error: "POLL",
            message: "任务状态查询连续失败（网络或后端抖动），任务可能仍在后台执行——稍后重新打开工单查看结果",
          },
        };
      }
      continue;
    }
    consecutiveErrors = 0;
    if (t.status === "SUCCEEDED") return { ok: true };
    if (t.status === "RUNNING" || t.status === "QUEUED" || t.status === "RETRY_WAIT") {
      // 后端只在 10%/30%/90% 几个锚点写进度（引擎期心跳为 30% + 耗时），原样透传。
      try {
        const p = t.result_json ? (JSON.parse(t.result_json) as { percent?: number; label?: string }) : null;
        if (p && typeof p.percent === "number") onProgress?.(p.percent, p.label ?? "");
      } catch {
        /* 进度体解析失败忽略 */
      }
      continue;
    }
    if (t.status === "FAILED" || t.status === "CANCELLED") {
      let error: TaskError | undefined;
      try {
        if (t.error_json) error = JSON.parse(t.error_json) as TaskError;
      } catch {
        /* error_json 非结构化时按未知错误处理 */
      }
      return { ok: false, error };
    }
  }
  return {
    ok: false,
    error: {
      error_code: 0,
      error: "TIMEOUT",
      message: `任务超过 ${engineTimeoutSec}s 仍未完成（引擎超时上限），请稍后重新打开工单查看结果`,
    },
  };
}
