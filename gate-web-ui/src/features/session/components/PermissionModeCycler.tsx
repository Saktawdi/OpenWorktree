import { useState } from "react";
import { ArrowsClockwise, ShieldCheck } from "@phosphor-icons/react";
import { useApp } from "@/store";
import { actions } from "@/app/actions";

/**
 * claude 权限模式轮询（V24 方案 A）：acceptEdits → plan → auto → bypassPermissions 循环，
 * 点击 PATCH 会话档位。headless 每次发送新进程读 session.permissionMode——切换在
 * <b>下一次发送时生效</b>，进行中的回合不受影响（hover 有提示）。opencode 的自动授权
 * 按钮不受影响（Composer 按 isClaude 三元挂载，两链互斥）。
 * 文案口径：https://code.claude.com/docs/en/permission-modes
 */

const MODE_CYCLE = ["acceptEdits", "plan", "auto", "bypassPermissions"] as const;
export type PermissionMode = (typeof MODE_CYCLE)[number];

const MODE_META: Record<PermissionMode, { label: string; hint: string }> = {
  acceptEdits: {
    label: "权限：接受编辑",
    hint: "读取、文件编辑与常见文件系统命令（mkdir/touch/mv/cp 等）免确认，其余操作仍会询问。适合边审查边迭代的场景。（默认档）",
  },
  plan: {
    label: "权限：计划",
    hint: "只读探索：Claude 研究代码并产出计划，计划批准前不做任何修改。适合改动前先摸清代码库。",
  },
  auto: {
    label: "权限：自动",
    hint: "所有操作免询问，由后台安全分类器审查并拦截危险动作（需支持的模型）。适合长任务、减少确认疲劳。",
  },
  bypassPermissions: {
    label: "权限：全部放行",
    hint: "跳过所有权限检查，MCP 工具（如预提审）不再被拒。官方文档建议仅用于隔离环境。",
  },
};

const EFFECT_HINT = "切换在下一次发送时生效，进行中的回合不受影响。";

export function PermissionModeCycler({ ticketNo }: { ticketNo: string }) {
  const mode = (useApp((s) => {
    const sid = s.activeSessionId[ticketNo];
    const sess = sid ? (s.sessions[ticketNo] ?? []).find((x) => x.id === sid) : undefined;
    return sess?.permissionMode ?? null;
  }) as PermissionMode | null) ?? "acceptEdits";
  const [pending, setPending] = useState(false);

  const next = MODE_CYCLE[(MODE_CYCLE.indexOf(mode) + 1) % MODE_CYCLE.length];
  const meta = MODE_META[mode];

  const cycle = async () => {
    if (pending) return;
    setPending(true);
    try {
      await actions.setSessionPermissionMode(ticketNo, next);
    } finally {
      setPending(false);
    }
  };

  return (
    <button
      className={`composer-btn ${pending ? "opacity-60" : ""}`}
      title={`${meta.hint}\n${EFFECT_HINT}\n点击切换 → ${MODE_META[next].label}`}
      disabled={pending}
      onClick={() => void cycle()}
    >
      <ShieldCheck size={13} weight={mode === "acceptEdits" ? "regular" : "fill"} />
      {meta.label}
      <ArrowsClockwise size={10} className="text-faint" />
    </button>
  );
}
