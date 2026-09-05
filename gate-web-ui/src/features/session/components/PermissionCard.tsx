import { useState } from "react";
import { CaretDown, CaretRight, Check, CircleNotch, ShieldCheck, X } from "@phosphor-icons/react";
import { pushPermissionRequest, pushSystemMessage, resolvePermission, revertPermission, answerSessionPermission } from "@/features/session";
import type { ChatItem, PermissionStatus } from "@/shared/types";

type PermissionItem = Extract<ChatItem, { kind: "permission" }>;

const PERMISSION_LABEL: Record<string, string> = {
  bash: "终端命令",
  shell: "终端命令",
  edit: "文件修改",
  write: "文件修改",
  read: "文件读取",
  grep: "文件读取",
  glob: "文件读取",
  webfetch: "网络请求",
  external_directory: "访问会话外目录",
};

function permissionLabel(permission: string): string {
  return PERMISSION_LABEL[permission] ?? permission;
}

const DECIDED_BADGE: Record<Exclude<PermissionStatus, "pending">, { text: string; cls: string }> = {
  once: { text: "已允许", cls: "badge-accent" },
  always: { text: "已始终允许", cls: "badge-accent" },
  reject: { text: "已拒绝", cls: "badge-danger" },
  auto: { text: "已自动允许", cls: "badge-info" },
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
  const { request, status } = item;
  const [busy, setBusy] = useState<"once" | "always" | "reject" | null>(null);
  const [detailOpen, setDetailOpen] = useState(false);

  const decided = status !== "pending";

  const respond = async (response: "once" | "always" | "reject") => {
    if (busy) return;
    setBusy(response);
    // 乐观移除卡片（openchamber 语义），失败时再恢复待决。
    resolvePermission(ticketNo, request.permissionId, response, false);
    const ok = await answerSessionPermission(sessionId, request.permissionId, response);
    if (!ok) {
      // 失败恢复：清墓碑并重新入队待决卡片。
      revertPermission(ticketNo, request.permissionId);
      pushPermissionRequest(ticketNo, request);
      pushSystemMessage(
        ticketNo,
        `权限应答提交失败（${response === "once" ? "允许一次" : response === "always" ? "始终允许" : "拒绝"}），已恢复待决`,
        "warn",
      );
    }
    setBusy(null);
  };

  const alwaysTitle =
    request.always.length > 0 ? `始终允许以下匹配：${request.always.join(", ")}` : "始终允许该类权限";

  const badge = decided ? DECIDED_BADGE[status as Exclude<PermissionStatus, "pending">] : null;

  return (
    <div className={`rounded-lg border divide-y divide-edge overflow-hidden ${decided ? "border-edge permission-decided" : "border-warn/50 permission-pending"}`}>
      <div className="px-3 py-2.5">
        <div className="flex items-center gap-2">
          <ShieldCheck size={15} weight="fill" className={decided ? "text-faint" : "text-warn"} />
          <span className="text-[13px] font-semibold text-ink">需要权限确认</span>
          <span className={`chip ${decided ? "badge-dim border border-edge text-dim" : "text-warn bg-warn/10"}`}>
            {permissionLabel(request.permission)}
          </span>
          <span className="flex-1" />
          {badge && <span className={badge.cls}>{badge.text}</span>}
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
              详情
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
            <button className="btn btn-primary btn-sm" disabled={!!busy} onClick={() => void respond("once")} title="允许这一次执行">
              {busy === "once" ? <CircleNotch size={12} className="animate-[spin_0.9s_linear_infinite]" /> : <Check size={12} weight="bold" />}
              允许一次
            </button>
            <button className="btn btn-outline btn-sm" disabled={!!busy} onClick={() => void respond("always")} title={alwaysTitle}>
              {busy === "always" ? <CircleNotch size={12} className="animate-[spin_0.9s_linear_infinite]" /> : <ShieldCheck size={12} weight="regular" />}
              始终允许
            </button>
            <button className="btn btn-danger-ghost btn-sm" disabled={!!busy} onClick={() => void respond("reject")} title="拒绝本次并中断该权限">
              {busy === "reject" ? <CircleNotch size={12} className="animate-[spin_0.9s_linear_infinite]" /> : <X size={12} weight="bold" />}
              拒绝
            </button>
          </div>
        )}
        {!decided && locked && (
          <div className="flex items-center gap-1.5 text-[11.5px] text-faint">
            <ShieldCheck size={12} />
            工单已取消 · 权限应答已锁定
          </div>
        )}
      </div>
    </div>
  );
}
