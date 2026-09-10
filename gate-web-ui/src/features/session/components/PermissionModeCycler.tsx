import { useState } from "react";
import { ArrowsClockwise, ShieldCheck } from "@phosphor-icons/react";
import { useApp } from "@/store";
import { actions } from "@/app/actions";
import { useT, type MsgKey } from "@/i18n";

/**
 * claude 权限模式轮询（V24 方案 A）：acceptEdits → plan → auto → bypassPermissions 循环，
 * 点击 PATCH 会话档位。headless 每次发送新进程读 session.permissionMode——切换在
 * <b>下一次发送时生效</b>，进行中的回合不受影响（hover 有提示）。opencode 的自动授权
 * 按钮不受影响（Composer 按 isClaude 三元挂载，两链互斥）。
 * 文案口径：https://code.claude.com/docs/en/permission-modes
 */

const MODE_CYCLE = ["acceptEdits", "plan", "auto", "bypassPermissions"] as const;
export type PermissionMode = (typeof MODE_CYCLE)[number];

const MODE_META: Record<PermissionMode, { labelKey: MsgKey; hintKey: MsgKey }> = {
  acceptEdits: {
    labelKey: "permMode.acceptEdits.label",
    hintKey: "permMode.acceptEdits.hint",
  },
  plan: {
    labelKey: "permMode.plan.label",
    hintKey: "permMode.plan.hint",
  },
  auto: {
    labelKey: "permMode.auto.label",
    hintKey: "permMode.auto.hint",
  },
  bypassPermissions: {
    labelKey: "permMode.bypassPermissions.label",
    hintKey: "permMode.bypassPermissions.hint",
  },
};

export function PermissionModeCycler({ ticketNo }: { ticketNo: string }) {
  const t = useT();
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
      title={`${t(meta.hintKey)}\n${t("permMode.effectHint")}\n${t("permMode.switchTo", { label: t(MODE_META[next].labelKey) })}`}
      disabled={pending}
      onClick={() => void cycle()}
    >
      <ShieldCheck size={13} weight={mode === "acceptEdits" ? "regular" : "fill"} />
      {t(meta.labelKey)}
      <ArrowsClockwise size={10} className="text-faint" />
    </button>
  );
}
