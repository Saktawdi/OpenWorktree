import { useState } from "react";
import { CaretDown, CaretRight, Check, CircleNotch, ShieldCheck, X } from "@phosphor-icons/react";
import { pushPermissionRequest, pushSystemMessage, resolvePermission, revertPermission, answerSessionPermission } from "@/features/session";
import type { ChatItem, PermissionStatus } from "@/shared/types";
import { useT, type Translate } from "@/i18n";

type PermissionItem = Extract<ChatItem, { kind: "permission" }>;

const PERMISSION_LABEL: Record<string, "perm.bash" | "perm.edit" | "perm.read" | "perm.webfetch" | "perm.externalDir"> = {
  bash: "perm.bash",
  shell: "perm.bash",
  edit: "perm.edit",
  write: "perm.edit",
  read: "perm.read",
  grep: "perm.read",
  glob: "perm.read",
  webfetch: "perm.webfetch",
  external_directory: "perm.externalDir",
};

function permissionLabel(permission: string, t: Translate): string {
  const key = PERMISSION_LABEL[permission];
  return key ? t(key) : permission;
}

const DECIDED_BADGE: Record<Exclude<PermissionStatus, "pending">, { textKey: "perm.allowedOnce" | "perm.allowedAlways" | "perm.rejected" | "perm.allowedAuto"; cls: string }> = {
  once: { textKey: "perm.allowedOnce", cls: "badge-accent" },
  always: { textKey: "perm.allowedAlways", cls: "badge-accent" },
  reject: { textKey: "perm.rejected", cls: "badge-danger" },
  auto: { textKey: "perm.allowedAuto", cls: "badge-info" },
};

const str = (v: unknown): string | undefined =>
  typeof v === "string" && v.length > 0 ? v : undefined;

function MetadataDetail({ permission, metadata }: { permission: string; metadata: Record<string, unknown> }) {
  const m = metadata ?? {};
  if (permission === "bash" || permission === "shell") {
    const command = str(m.command);
    const cwd = str(m.cwd);
    return (
      <div className="space-y-1.5">
        {command && <pre className="psm mono-blk">{command}</pre>}
        {cwd && (
          <div className="text-[11px] text-dim break-all">
            <span className="text-faint">cwd</span>
            <code className="ml-1 font-mono text-dim">{cwd}</code>
          </div>
        )}
      </div>
    );
  }
  if (permission === "edit" || permission === "write") {
    const path = str(m.path) ?? str(m.filepath) ?? str(m.file);
    const raw = str(m.diff) ?? str(m.changes) ?? (typeof m.content === "string" ? m.content : undefined);
    return (
      <div className="space-y-1.5">
        {path && (
          <div className="text-[11px] text-dim break-all">
            <span className="text-faint">path</span>
            <code className="ml-1 font-mono text-dim">{path}</code>
          </div>
        )}
        {raw && <pre className="psm mono-blk max-h-44">{raw.split("\n").slice(0, 40).join("\n")}</pre>}
      </div>
    );
  }
  if (permission === "webfetch") {
    const url = str(m.url);
    const method = str(m.method);
    return (
      <div className="space-y-1.5">
        {url && (
          <div className="text-[11px] text-dim break-all">
            <span className="text-faint">url</span>
            <code className="ml-1 font-mono text-dim">{url}</code>
          </div>
        )}
        {method && (
          <div className="text-[11px] text-dim">
            <span className="text-faint">method</span>
            <code className="ml-1 font-mono text-dim">{method}</code>
          </div>
        )}
      </div>
    );
  }
  if (permission === "external_directory") {
    const rows: Array<[string, string]> = [];
    if (m.directories !== undefined) {
      rows.push(["directories", Array.isArray(m.directories) ? (m.directories as string[]).join(", ") : String(m.directories)]);
    }
    const fp = str(m.filepath);
    if (fp) rows.push(["filepath", fp]);
    const cmd = str(m.command);
    if (cmd) rows.push(["command", cmd]);
    if (rows.length > 0) {
      return (
        <div className="space-y-1.5">
          {rows.map(([k, v]) => (
            <div key={k} className="text-[11px] text-dim break-all">
              <span className="text-faint">{k}</span>
              <span className="ml-1 font-mono">{v}</span>
            </div>
          ))}
        </div>
      );
    }
  }
  return <pre className="psm mono-blk max-h-44">{JSON.stringify(m, null, 2)}</pre>;
}
export function PermissionCard({
  ticketNo,
  sessionId,
  item,
  locked = false,
}: {
  ticketNo: string;
  sessionId: string;
  item: PermissionItem;
  /** 工单已取消等终态：禁止应答，仅展示。 */
  locked?: boolean;
}) {
  const t = useT();
  const { request, status } = item;
  const [busy, setBusy] = useState<"once" | "always" | "reject" | null>(null);
  const [detailOpen, setDetailOpen] = useState(false);

  const decided = status !== "pending";

  const respond = async (response: "once" | "always" | "reject") => {
    if (busy) return;
    setBusy(response);
    // 乐观移除卡片，失败时再恢复待决。
    resolvePermission(ticketNo, request.permissionId, response, false);
    const ok = await answerSessionPermission(sessionId, request.permissionId, response);
    if (!ok) {
      // 失败恢复：清墓碑并重新入队待决卡片。
      revertPermission(ticketNo, request.permissionId);
      pushPermissionRequest(ticketNo, request);
      pushSystemMessage(
        ticketNo,
        t("perm.failToast", {
          action:
            response === "once"
              ? t("perm.once")
              : response === "always"
                ? t("perm.always")
                : t("perm.reject"),
        }),
        "warn",
      );
    }
    setBusy(null);
  };

  const alwaysTitle =
    request.always.length > 0 ? t("perm.alwaysPatterns", { patterns: request.always.join(", ") }) : t("perm.alwaysThisKind");

  const badge = decided ? DECIDED_BADGE[status as Exclude<PermissionStatus, "pending">] : null;

  return (
    <div className={`rounded-lg border divide-y divide-edge overflow-hidden ${decided ? "border-edge permission-decided" : "border-warn/50 permission-pending"}`}>
      <div className="px-3 py-2.5">
        <div className="flex items-center gap-2">
          <ShieldCheck size={15} weight="fill" className={decided ? "text-faint" : "text-warn"} />
          <span className="text-[13px] font-semibold text-ink">{t("perm.title")}</span>
          <span className={`chip ${decided ? "badge-dim border border-edge text-dim" : "text-warn bg-warn/10"}`}>
            {permissionLabel(request.permission, t)}
          </span>
          <span className="flex-1" />
          {badge && <span className={badge.cls}>{t(badge.textKey)}</span>}
        </div>
        {request.patterns.length > 0 && (
          <div className="mt-2">
            <code className="mono-cc">{request.patterns.join(", ")}</code>
          </div>
        )}
        {Object.keys(request.metadata ?? {}).length > 0 && (
          <div className="mt-2">
            <button
              className="flex items-center gap-1 text-[11px] text-faint hover:text-dim transition-colors cursor-pointer"
              onClick={() => setDetailOpen(!detailOpen)}
            >
              {detailOpen ? <CaretDown size={11} /> : <CaretRight size={11} />}
              {t("common.details")}
            </button>
            {detailOpen && (
              <div className="mt-1.5">
                <MetadataDetail permission={request.permission} metadata={request.metadata} />
              </div>
            )}
          </div>
        )}
      </div>
      <div className="px-3 py-2 bg-panel/40">
        {!decided && !locked && (
          <div className="flex items-center gap-2">
            <button className="btn btn-primary btn-sm" disabled={!!busy} onClick={() => void respond("once")} title={t("perm.onceTip")}>
              {busy === "once" ? <CircleNotch size={12} className="animate-[spin_0.9s_linear_infinite]" /> : <Check size={12} weight="bold" />}
              {t("perm.once")}
            </button>
            <button className="btn btn-outline btn-sm" disabled={!!busy} onClick={() => void respond("always")} title={alwaysTitle}>
              {busy === "always" ? <CircleNotch size={12} className="animate-[spin_0.9s_linear_infinite]" /> : <ShieldCheck size={12} weight="regular" />}
              {t("perm.always")}
            </button>
            <button className="btn btn-danger-ghost btn-sm" disabled={!!busy} onClick={() => void respond("reject")} title={t("perm.rejectTip")}>
              {busy === "reject" ? <CircleNotch size={12} className="animate-[spin_0.9s_linear_infinite]" /> : <X size={12} weight="bold" />}
              {t("perm.reject")}
            </button>
          </div>
        )}
        {!decided && locked && (
          <div className="flex items-center gap-1.5 text-[11.5px] text-faint">
            <ShieldCheck size={12} />
            {t("perm.lockedNote")}
          </div>
        )}
      </div>
    </div>
  );
}
