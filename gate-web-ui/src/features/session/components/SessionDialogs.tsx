/**
 * 会话域对话框群（T-105）：会话重命名 / 删除确认 / 移入分组，与分组的新建 /
 * 编辑 / 删除确认。全部由 SessionList 的右键菜单或分组头按钮触发，样式对齐
 * 既有弹窗（RestartDialog 等）：遮罩 + card 弹层，motion 弹入弹出（动效库与
 * 项目其余部分一致，均为 motion/react）。
 */
import { useEffect, useRef, useState, type ReactNode } from "react";
import { AnimatePresence, motion } from "motion/react";
import { Check, FolderMinus, FolderPlus, NotePencil, Trash, X } from "@phosphor-icons/react";
import { actions } from "@/app/actions";
import { showToast, useApp } from "@/store";
import { useBackdropClose } from "@/shared/components/ui";
import { GROUP_COLOR_PALETTE, GROUP_NAME_MAX } from "@/features/session/state";
import type { SessionGroup } from "@/shared/types";
import { useT } from "@/i18n";

/** 会话列表对话框的打开状态：记录目标会话/分组与用途。 */
export type SessionDialogState =
  | { kind: "rename"; sessionId: string }
  | { kind: "delete"; sessionId: string }
  | { kind: "move-group"; sessionId: string }
  | { kind: "group-create"; moveSessionId?: string }
  | { kind: "group-edit"; groupId: string }
  | { kind: "group-delete"; groupId: string };

const NO_GROUPS: SessionGroup[] = [];
const TITLE_MAX = 60;

function DialogShell({
  title,
  icon,
  onClose,
  children,
  footer,
}: {
  title: string;
  icon: ReactNode;
  onClose: () => void;
  children: ReactNode;
  footer: ReactNode;
}) {
  const t = useT();
  const backdrop = useBackdropClose(onClose);
  return (
    <motion.div
      className="fixed inset-0 z-50 grid place-items-center bg-black/55 backdrop-blur-[2px]"
      initial={{ opacity: 0 }}
      animate={{ opacity: 1 }}
      exit={{ opacity: 0 }}
      transition={{ duration: 0.15 }}
      {...backdrop}
    >
      <motion.div
        className="w-[380px] card shadow-2xl shadow-black/60"
        initial={{ opacity: 0, scale: 0.96, y: 10 }}
        animate={{ opacity: 1, scale: 1, y: 0 }}
        exit={{ opacity: 0, scale: 0.96, y: 10 }}
        transition={{ duration: 0.22, ease: [0.16, 1, 0.3, 1] }}
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center gap-2.5 px-5 h-12 border-b border-edge">
          {icon}
          <span className="text-[13.5px] font-semibold">{title}</span>
          <span className="flex-1" />
          <button className="icon-btn" onClick={onClose} aria-label={t("common.close")}>
            <X size={14} />
          </button>
        </div>
        <div className="p-5 space-y-4">{children}</div>
        <div className="flex justify-end gap-2 px-5 py-4 border-t border-edge">{footer}</div>
      </motion.div>
    </motion.div>
  );
}

/* ─── 重命名会话（替代原生 window.prompt 的 in-app 弹窗） ─── */

function RenameSessionDialog({
  ticketNo,
  sessionId,
  onClose,
}: {
  ticketNo: string;
  sessionId: string;
  onClose: () => void;
}) {
  const t = useT();
  const session = useApp((s) => (s.sessions[ticketNo] ?? []).find((x) => x.id === sessionId));
  const [title, setTitle] = useState(() => session?.title ?? "");
  const [submitting, setSubmitting] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);
  useEffect(() => {
    inputRef.current?.select();
  }, []);

  if (!session) return null;
  const next = title.trim();
  const valid = next !== "" && next !== session.title && title.length <= TITLE_MAX;

  return (
    <DialogShell
      title={t("sess.rename.title")}
      icon={<NotePencil size={15} className="text-accent" weight="fill" />}
      onClose={onClose}
      footer={
        <>
          <button className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button
            className="btn btn-primary"
            disabled={!valid || submitting}
            onClick={() => {
              if (!valid || submitting) return;
              setSubmitting(true);
              void Promise.resolve(actions.renameSession(ticketNo, sessionId, next)).then(() => {
                setSubmitting(false);
                onClose();
              });
            }}
          >
            {t("sess.rename.confirm")}
          </button>
        </>
      }
    >
      <div className="font-mono text-[11px] text-faint truncate">{session.id}</div>
      <div>
        <label className="field-label">
          {t("sess.rename.nameLabel")}<span className="text-danger">*</span>
        </label>
        <input
          ref={inputRef}
          className="text-input"
          placeholder={t("sess.rename.placeholder")}
          value={title}
          maxLength={TITLE_MAX}
          onChange={(e) => setTitle(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter" && valid) {
              actions.renameSession(ticketNo, sessionId, next);
              onClose();
            }
          }}
        />
        <div className="mt-1 text-right font-mono text-[10.5px] text-faint">
          {title.length}/{TITLE_MAX}
        </div>
      </div>
    </DialogShell>
  );
}

/* ─── 删除会话（确认弹窗：删除不可恢复） ─── */

function DeleteSessionDialog({
  ticketNo,
  sessionId,
  onClose,
}: {
  ticketNo: string;
  sessionId: string;
  onClose: () => void;
}) {
  const t = useT();
  const session = useApp((s) => (s.sessions[ticketNo] ?? []).find((x) => x.id === sessionId));
  if (!session) return null;
  return (
    <DialogShell
      title={t("sess.delete.title")}
      icon={<Trash size={15} className="text-danger" weight="fill" />}
      onClose={onClose}
      footer={
        <>
          <button className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button
            className="btn btn-danger-ghost"
            onClick={() => {
              actions.deleteSession(ticketNo, sessionId);
              onClose();
            }}
          >
            <Trash size={13} />
            {t("llm.confirmDelete")}
          </button>
        </>
      }
    >
      <div className="rounded-lg border border-edge bg-sunken/60 px-3.5 py-2.5 text-[12px] text-dim leading-relaxed">
        {t("sess.delete.line", { title: session.title, id: session.id })}
      </div>
      <div className="text-[11.5px] text-danger/90">{t("sess.delete.warning")}</div>
    </DialogShell>
  );
}

/* ─── 移入分组：列出分组 + 新建分组 + 移出分组 ─── */

function MoveSessionGroupDialog({
  ticketNo,
  sessionId,
  onOpenDialog,
  onClose,
}: {
  ticketNo: string;
  sessionId: string;
  onOpenDialog: (d: SessionDialogState) => void;
  onClose: () => void;
}) {
  const t = useT();
  const groups = useApp((s) => s.sessionGroups[ticketNo] ?? NO_GROUPS);
  const members = useApp((s) => s.sessionGroupMembers);
  const sessions = useApp((s) => s.sessions[ticketNo] ?? []);

  const currentId = members[sessionId] && groups.some((g) => g.id === members[sessionId])
    ? members[sessionId]
    : null;
  const countOf = (groupId: string | null) =>
    sessions.filter((x) => {
      const gid = members[x.id];
      const mine = gid && groups.some((g) => g.id === gid) ? gid : null;
      return mine === groupId;
    }).length;
  const move = (groupId: string | null) => {
    void actions.moveSessionToGroup(ticketNo, sessionId, groupId);
    onClose();
  };

  return (
    <DialogShell
      title={t("sess.moveToGroup")}
      icon={<FolderPlus size={15} className="text-accent" weight="fill" />}
      onClose={onClose}
      footer={
        <>
          {currentId && (
            <button className="btn" onClick={() => move(null)}>
              <FolderMinus size={13} />
              {t("sess.moveOut")}
            </button>
          )}
          <button className="btn" onClick={() => onOpenDialog({ kind: "group-create", moveSessionId: sessionId })}>
            <FolderPlus size={13} />
            {t("sess.group.create")}…
          </button>
          <button className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
        </>
      }
    >
      <div className="space-y-1 max-h-64 overflow-y-auto -mx-1 px-1">
        {groups.length === 0 && (
          <div className="px-2 py-3 text-[12px] text-faint">{t("sess.noGroupsYet")}</div>
        )}
        {groups.map((g) => (
          <button
            key={g.id}
            className={`w-full flex items-center gap-2.5 px-2.5 py-2 rounded-md text-[12px] text-left cursor-pointer transition-colors ${
              currentId === g.id ? "bg-raised text-ink" : "text-dim hover:bg-raised hover:text-ink"
            }`}
            onClick={() => move(g.id)}
          >
            <span className="w-2 h-2 rounded-full shrink-0" style={{ background: g.color }} />
            <span className="truncate flex-1">{g.name}</span>
            {currentId === g.id ? (
              <span className="chip text-accent border border-accent/30 bg-accent/10 !px-1.5 !leading-[16px] !text-[10px]">
                {t("sess.current")}
              </span>
            ) : (
              <span className="font-mono text-[10px] text-faint">{countOf(g.id)}</span>
            )}
          </button>
        ))}
        {groups.length > 0 && (
          <button
            className="w-full flex items-center gap-2.5 px-2.5 py-2 rounded-md text-[12px] text-left cursor-pointer transition-colors text-dim hover:bg-raised hover:text-ink"
            onClick={() => move(null)}
          >
            <span className="w-2 h-2 rounded-full shrink-0 bg-faint/50" />
            <span className="flex-1">{t("sess.ungrouped")}</span>
            {currentId === null ? (
              <span className="chip text-faint border border-edge bg-raised !px-1.5 !leading-[16px] !text-[10px]">
                {t("sess.current")}
              </span>
            ) : (
              <span className="font-mono text-[10px] text-faint">{countOf(null)}</span>
            )}
          </button>
        )}
      </div>
    </DialogShell>
  );
}

/* ─── 分组新建 / 编辑（名称 + 颜色） ─── */

function GroupDialog({
  ticketNo,
  groupId,
  moveSessionId,
  onClose,
}: {
  ticketNo: string;
  groupId: string | null;
  moveSessionId?: string;
  onClose: () => void;
}) {
  const t = useT();
  const existing = useApp((s) => (s.sessionGroups[ticketNo] ?? NO_GROUPS).find((g) => g.id === groupId));
  const [name, setName] = useState(() => existing?.name ?? "");
  const [color, setColor] = useState(() => existing?.color ?? GROUP_COLOR_PALETTE[0]);
  const [busy, setBusy] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);
  useEffect(() => {
    inputRef.current?.focus();
  }, []);

  if (groupId !== null && !existing) return null;
  const editing = groupId !== null;
  const nameNext = name.trim();
  const valid = nameNext !== "" && (!existing || nameNext !== existing.name || color !== existing.color);

  const submit = async () => {
    if (valid === false || busy) return;
    setBusy(true);
    if (editing && groupId) {
      await actions.updateSessionGroup(ticketNo, groupId, { name: nameNext, color });
    } else {
      const newId = await actions.createSessionGroup(ticketNo, nameNext, color);
      if (moveSessionId && newId) {
        await actions.moveSessionToGroup(ticketNo, moveSessionId, newId);
      }
    }
    setBusy(false);
    onClose();
  };

  return (
    <DialogShell
      title={editing ? t("sess.group.edit") : t("sess.group.create")}
      icon={
        editing ? (
          <NotePencil size={15} className="text-accent" weight="fill" />
        ) : (
          <FolderPlus size={15} className="text-accent" weight="fill" />
        )
      }
      onClose={onClose}
      footer={
        <>
          <button className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button className="btn btn-primary" disabled={!valid || busy} onClick={submit}>
            {editing ? t("llm.saveChanges") : moveSessionId ? t("sess.group.createMove") : t("sess.group.createAction")}
          </button>
        </>
      }
    >
      <div>
        <label className="field-label">
          {t("sess.group.nameLabel")}<span className="text-danger">*</span>
        </label>
        <input
          ref={inputRef}
          className="text-input"
          placeholder={t("sess.group.namePlaceholder")}
          value={name}
          maxLength={GROUP_NAME_MAX}
          onChange={(e) => setName(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter" && valid) void submit();
          }}
        />
        <div className="mt-1 text-right font-mono text-[10.5px] text-faint">
          {name.length}/{GROUP_NAME_MAX}
        </div>
      </div>
      <div>
        <label className="field-label">{t("sess.group.colorLabel")}</label>
        <div className="flex flex-wrap gap-2">
          {GROUP_COLOR_PALETTE.map((c) => (
            <button
              key={c}
              type="button"
              className="w-7 h-7 rounded-full grid place-items-center cursor-pointer transition-transform duration-150 hover:scale-110"
              style={{
                background: c,
                boxShadow: color === c ? `0 0 0 2px var(--color-panel), 0 0 0 3.5px ${c}` : undefined,
              }}
              onClick={() => setColor(c)}
              aria-label={t("sess.group.pickColor", { color: c })}
            >
              {color === c && <Check size={12} weight="bold" className="text-black/70" />}
            </button>
          ))}
        </div>
      </div>
    </DialogShell>
  );
}

/* ─── 删除分组确认（组内会话回到未分组） ─── */

function DeleteGroupDialog({
  ticketNo,
  groupId,
  onClose,
}: {
  ticketNo: string;
  groupId: string;
  onClose: () => void;
}) {
  const t = useT();
  const group = useApp((s) => (s.sessionGroups[ticketNo] ?? NO_GROUPS).find((g) => g.id === groupId));
  const members = useApp((s) => s.sessionGroupMembers);
  const sessions = useApp((s) => s.sessions[ticketNo] ?? []);
  if (!group) return null;
  const count = sessions.filter((x) => members[x.id] === groupId).length;
  return (
    <DialogShell
      title={t("sess.group.delete")}
      icon={<Trash size={15} className="text-danger" weight="fill" />}
      onClose={onClose}
      footer={
        <>
          <button className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button
            className="btn btn-danger-ghost"
            onClick={() => {
              actions.deleteSessionGroup(ticketNo, groupId);
              showToast(count > 0 ? t("sess.group.deletedWithSessions", { n: count }) : t("sess.group.deleted"));
              onClose();
            }}
          >
            <Trash size={13} />
            {t("llm.confirmDelete")}
          </button>
        </>
      }
    >
      <div className="rounded-lg border border-edge bg-sunken/60 px-3.5 py-2.5 text-[12px] text-dim leading-relaxed">
        {t("sess.group.deleteLine", { name: group.name })}
        {count > 0 ? (
          <>
            {t("sess.group.deleteKeepSessions", { n: count })}
          </>
        ) : (
          <>{t("sess.group.deleteEmpty")}</>
        )}
      </div>
    </DialogShell>
  );
}

/* ─── 路由：按 dialog kind 渲染对应弹窗（AnimatePresence 由调用方包裹） ─── */

function SessionDialogBody({
  ticketNo,
  dialog,
  onOpenDialog,
  onClose,
}: {
  ticketNo: string;
  dialog: SessionDialogState;
  onOpenDialog: (d: SessionDialogState) => void;
  onClose: () => void;
}) {
  switch (dialog.kind) {
    case "rename":
      return <RenameSessionDialog ticketNo={ticketNo} sessionId={dialog.sessionId} onClose={onClose} />;
    case "delete":
      return <DeleteSessionDialog ticketNo={ticketNo} sessionId={dialog.sessionId} onClose={onClose} />;
    case "move-group":
      return (
        <MoveSessionGroupDialog
          ticketNo={ticketNo}
          sessionId={dialog.sessionId}
          onOpenDialog={onOpenDialog}
          onClose={onClose}
        />
      );
    case "group-create":
      return (
        <GroupDialog
          ticketNo={ticketNo}
          groupId={null}
          moveSessionId={dialog.moveSessionId}
          onClose={onClose}
        />
      );
    case "group-edit":
      return <GroupDialog ticketNo={ticketNo} groupId={dialog.groupId} onClose={onClose} />;
    case "group-delete":
      return <DeleteGroupDialog ticketNo={ticketNo} groupId={dialog.groupId} onClose={onClose} />;
  }
}

/** 弹窗 key：同一 kind 不同目标（会话/分组）间切换时强制重挂载弹层。 */
export function sessionDialogKey(dialog: SessionDialogState): string {
  switch (dialog.kind) {
    case "rename":
    case "delete":
    case "move-group":
      return `${dialog.kind}-${dialog.sessionId}`;
    case "group-edit":
    case "group-delete":
      return `${dialog.kind}-${dialog.groupId}`;
    case "group-create":
      return `group-create-${dialog.moveSessionId ?? "none"}`;
  }
}

/** 弹窗群总装：SessionList 持有 SessionDialogState，经 AnimatePresence 驱动进出场动画
 *  （key 加在 AnimatePresence 的直接子元素上，保证切换/关闭的进出场正确）。 */
export function SessionDialogs({
  ticketNo,
  dialog,
  onOpenDialog,
  onClose,
}: {
  ticketNo: string;
  dialog: SessionDialogState;
  onOpenDialog: (d: SessionDialogState) => void;
  onClose: () => void;
}) {
  return (
    <SessionDialogBody ticketNo={ticketNo} dialog={dialog} onOpenDialog={onOpenDialog} onClose={onClose} />
  );
}
