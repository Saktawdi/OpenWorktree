import { useCallback, useEffect, useMemo, useState } from "react";
import {
  ArrowClockwise,
  ArrowDown,
  Broom,
  FolderOpen,
  HardDrives,
  Package,
  Trash,
  Warning,
  WarningCircle,
} from "@phosphor-icons/react";
import {
  cleanStorageCache,
  fetchStorageCaches,
  fetchStorageOverview,
  fetchStorageWorkspaces,
  openStorageDir,
  pruneAllStorageWorkspaces,
  pruneStorageWorkspace,
} from "@/features/settings";
import type {
  StorageCachesResponse,
  StorageOverview,
  StorageWorkspace,
  StorageWorkspacesResponse,
} from "@/shared/types";
import { CopyButton, Spinner, useBackdropClose } from "@/shared/components/ui";
import { formatBytes } from "@/shared/format";
import { showToast, useApp } from "@/store";
import { useT, type MsgKey, type Translate } from "@/i18n";

/* ─── 展示元数据：后端只下发事实（key/id/占用），文案与图标由前端持有 ─── */

const DIR_META: Record<string, { labelKey: MsgKey; descKey: MsgKey }> = {
  gate_home: { labelKey: "storage.dirMeta.gate_home.label", descKey: "storage.dirMeta.gate_home.desc" },
  clones_root: { labelKey: "storage.dirMeta.clones_root.label", descKey: "storage.dirMeta.clones_root.desc" },
  db: { labelKey: "storage.dirMeta.db.label", descKey: "storage.dirMeta.db.desc" },
  blob_root: { labelKey: "storage.dirMeta.blob_root.label", descKey: "storage.dirMeta.blob_root.desc" },
  audit: { labelKey: "storage.dirMeta.audit.label", descKey: "storage.dirMeta.audit.desc" },
};

const CACHE_META: Record<string, { labelKey: MsgKey; descKey: MsgKey }> = {
  proc_temp: { labelKey: "storage.cacheMeta.proc_temp.label", descKey: "storage.cacheMeta.proc_temp.desc" },
  gate_tmp: { labelKey: "storage.cacheMeta.gate_tmp.label", descKey: "storage.cacheMeta.gate_tmp.desc" },
  adapters_log: { labelKey: "storage.cacheMeta.adapters_log.label", descKey: "storage.cacheMeta.adapters_log.desc" },
};

/** 二次确认弹窗请求（清理/清空共用）：impact 必须写清影响范围。 */
interface ConfirmRequest {
  title: string;
  impact: string;
  confirmLabel: string;
  run: () => Promise<void>;
}

/** 排序键：闲置最久在前（默认，方便找出可以下手清理的工作区）/ 占用从大到小。 */
type WorkspaceSort = "idle" | "bytes";

/** 卡片头（图标 + 标题 + 右侧动作位）。 */
function CardHead({
  Icon,
  title,
  hint,
  actions,
}: {
  Icon: typeof HardDrives;
  title: string;
  hint?: string;
  actions?: React.ReactNode;
}) {
  return (
    <div className="flex items-center gap-2.5">
      <span className="w-6 h-6 rounded-md grid place-items-center border shrink-0 bg-accent-dim border-accent/30 text-accent">
        <Icon size={13} />
      </span>
      <span className="text-[13px] font-semibold">{title}</span>
      {hint && <span className="hidden sm:inline text-[11px] text-faint">{hint}</span>}
      <span className="flex-1" />
      {actions}
    </div>
  );
}

/** 字节占用徽标（approx 时加「约」；label 写明数字口径，tone=reclaim 标出可回收量）。 */
function BytesChip({
  bytes,
  approx,
  label,
  tip,
  tone = "neutral",
}: {
  bytes: number;
  approx?: boolean;
  /** 数字前的小字口径（如「总占用」「可清理」）。 */
  label?: string;
  /** 悬浮说明；缺省回退到 label，approx 时自动补「估算值」。 */
  tip?: string;
  tone?: "neutral" | "reclaim";
}) {
  const t = useT();
  const title = [tip ?? label, approx ? t("storage.approxTip") : null].filter(Boolean).join(" · ");
  return (
    <span
      className={
        "chip border font-mono text-[10.5px] " +
        (tone === "reclaim"
          ? "border-accent/30 bg-accent-dim text-accent"
          : "border-edge-strong bg-raised text-dim")
      }
      title={title || undefined}
    >
      {label && <span className="font-sans text-[10px] opacity-75">{label}</span>}
      {approx ? "≈" : ""}
      {formatBytes(bytes)}
    </span>
  );
}

/** 排序切换 chip（active 高亮）。 */
function SortChip({
  active,
  label,
  onClick,
}: {
  active: boolean;
  label: string;
  onClick: () => void;
}) {
  return (
    <button
      className={
        "chip border text-[10.5px] cursor-pointer " +
        (active
          ? "border-accent/40 bg-accent-dim text-accent"
          : "border-edge bg-raised text-faint hover:text-dim")
      }
      onClick={onClick}
    >
      <ArrowDown size={10} className={active ? "" : "opacity-60"} />
      {label}
    </button>
  );
}

/** 「最后改动距今」的展示文案（与排序同口径：无工作文件改动记录视为最久闲置）。 */
function formatIdle(lastActiveMs: number | null, t: Translate): string {
  if (lastActiveMs == null) return t("storage.idleNone");
  const days = Math.floor((Date.now() - lastActiveMs) / 86_400_000);
  if (days <= 0) return t("storage.idleToday");
  return t("storage.idleDays", { n: days });
}

/** 数据目录分区（live）：配置文件 + 五个核心数据位置，支持在系统中打开。 */
function DataDirsCard({
  overview,
  loading,
  error,
  onRefresh,
}: {
  overview: StorageOverview | null;
  loading: boolean;
  error: string | null;
  onRefresh: () => void;
}) {
  const t = useT();
  const [opening, setOpening] = useState<string | null>(null);

  const open = async (key: string) => {
    if (opening) return;
    setOpening(key);
    try {
      await openStorageDir(key);
      showToast(t("storage.openRequested"));
    } catch (e) {
      showToast(t("storage.openFailed", { err: (e as Error).message }));
    } finally {
      setOpening(null);
    }
  };

  return (
    <div className="card p-5">
      <CardHead
        Icon={HardDrives}
        title={t("storage.dirs.title")}
        hint={t("storage.dirs.hint")}
        actions={
          <button className="icon-btn" onClick={onRefresh} title={t("storage.refreshTip")} aria-label={t("storage.refreshTip")}>
            {loading ? <Spinner /> : <ArrowClockwise size={14} />}
          </button>
        }
      />
      {error && (
        <div className="mt-3 text-[12.5px] text-danger flex items-center gap-1.5">
          <WarningCircle size={14} weight="fill" /> {error}
          <button className="btn btn-sm ml-1" onClick={onRefresh}>{t("common.retry")}</button>
        </div>
      )}
      {loading && !overview && (
        <div className="mt-4 flex items-center gap-2 text-[12.5px] text-faint">
          <Spinner /> {t("storage.loadDirs")}
        </div>
      )}
      {overview && (
        <div className="mt-3 grid gap-2">
          {overview.toml_path && (
            <div className="rounded-lg border border-edge bg-sunken px-3 py-2.5">
              <div className="flex items-center gap-2">
                <span className="text-[12.5px] font-medium text-ink">{t("storage.configFile")}</span>
                <span className="flex-1" />
                <CopyButton text={overview.toml_path} label={t("storage.copyConfigPath")} />
              </div>
              <div className="mt-1 font-mono text-[11px] text-faint break-all">{overview.toml_path}</div>
            </div>
          )}
          {overview.dirs.map((d) => {
            const meta = DIR_META[d.key];
            const label = meta ? t(meta.labelKey) : d.key;
            const desc = meta ? t(meta.descKey) : "";
            return (
              <div key={d.key} className="rounded-lg border border-edge bg-sunken px-3 py-2.5">
                <div className="flex items-center gap-2 flex-wrap">
                  <span className="text-[12.5px] font-medium text-ink">{label}</span>
                  <BytesChip bytes={d.bytes} approx={d.approx} />
                  {!d.exists && <span className="chip border border-edge bg-raised text-faint">{t("storage.notCreated")}</span>}
                  <span className="flex-1" />
                  <CopyButton text={d.path} label={t("storage.copyPathOf", { name: label })} />
                  {d.openable && (
                    <button
                      className="btn btn-sm"
                      disabled={opening !== null}
                      onClick={() => void open(d.key)}
                      title={t("storage.openInSystem")}
                    >
                      {opening === d.key ? <Spinner /> : <FolderOpen size={13} />}
                      {t("storage.open")}
                    </button>
                  )}
                </div>
                <div className="mt-1 font-mono text-[11px] text-faint break-all">{d.path}</div>
                {desc && <div className="mt-0.5 text-[11px] text-faint">{desc}</div>}
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}

/** 本地缓存清理分区（live）：后端三类非证据缓存，按类清理（二次确认）。 */
function CacheCleanCard({
  caches,
  loading,
  error,
  onRefresh,
  onAsk,
}: {
  caches: StorageCachesResponse | null;
  loading: boolean;
  error: string | null;
  onRefresh: () => void;
  onAsk: (req: ConfirmRequest) => void;
}) {
  const t = useT();
  const askClean = (id: string) => {
    const meta = CACHE_META[id];
    const label = meta ? t(meta.labelKey) : id;
    const desc = meta ? t(meta.descKey) : "";
    const entry = caches?.caches.find((c) => c.id === id);
    const size = entry ? formatBytes(entry.bytes) : "";
    const files = entry?.files ?? 0;
    onAsk({
      title: t("storage.cache.confirmTitle", { label }),
      impact: t("storage.cache.confirmImpact", {
        path: entry?.path ?? label,
        files,
        size,
        desc,
      }),
      confirmLabel: t("storage.cache.confirm"),
      run: async () => {
        try {
          const r = await cleanStorageCache(id);
          showToast(t("storage.cache.cleaned", { label, bytes: formatBytes(r.removed_bytes) }));
          onRefresh();
        } catch (e) {
          showToast(t("storage.cleanFailed", { err: (e as Error).message }));
        }
      },
    });
  };

  return (
    <div className="card p-5">
      <CardHead
        Icon={Broom}
        title={t("storage.cache.title")}
        hint={t("storage.cache.hint")}
        actions={
          <button className="icon-btn" onClick={onRefresh} title={t("storage.refreshTip")} aria-label={t("storage.refreshTip")}>
            {loading ? <Spinner /> : <ArrowClockwise size={14} />}
          </button>
        }
      />
      {error && (
        <div className="mt-3 text-[12.5px] text-danger flex items-center gap-1.5">
          <WarningCircle size={14} weight="fill" /> {error}
          <button className="btn btn-sm ml-1" onClick={onRefresh}>{t("common.retry")}</button>
        </div>
      )}
      {loading && !caches && (
        <div className="mt-4 flex items-center gap-2 text-[12.5px] text-faint">
          <Spinner /> {t("storage.loadCaches")}
        </div>
      )}
      {caches && (
        <div className="mt-3 grid gap-2">
          {caches.caches.map((c) => {
            const meta = CACHE_META[c.id];
            const label = meta ? t(meta.labelKey) : c.id;
            const desc = meta ? t(meta.descKey) : "";
            return (
              <div key={c.id} className="rounded-lg border border-edge bg-sunken px-3 py-2.5">
                <div className="flex items-center gap-2 flex-wrap">
                  <span className="text-[12.5px] font-medium text-ink">{label}</span>
                  <BytesChip bytes={c.bytes} approx={c.approx} />
                  <span className="text-[11px] text-faint font-mono">{t("storage.filesCount", { n: c.files })}</span>
                  <span className="flex-1" />
                  <button
                    className="btn btn-sm btn-danger-ghost"
                    disabled={c.files === 0}
                    onClick={() => askClean(c.id)}
                    title={c.files === 0 ? t("storage.cache.none") : t("storage.cache.cleanTip")}
                  >
                    <Trash size={13} />
                    {t("storage.clean")}
                  </button>
                </div>
                <div className="mt-0.5 text-[11px] text-faint">{desc}</div>
                <div className="mt-1 font-mono text-[10.5px] text-faint/80 break-all">{c.path}</div>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}

/**
 * 工作区存储管理分区（live）：克隆根下各工作区的关联工单、总占用、最后改动距今
 * 天数（可按闲置/占用排序）与可再生目录（node_modules/构建产物）清理。
 *
 * <p>清理边界：有会话正在运行时不可清理——一键清理（后端自己遍历全部工作区）在任一
 * 会话有进行中回合时整体拒绝；单个工作区只在自己名下有运行中会话时禁用。运行中集合
 * 取自既有的 /api/agents/busy 全局轮询（store.runningAgents），前端据此提前禁用并
 * 说明原因，后端在真正删除前再兜底判一次。
 */
function WorkspacesCard({
  data,
  loading,
  error,
  onRefresh,
  onAsk,
}: {
  data: StorageWorkspacesResponse | null;
  loading: boolean;
  error: string | null;
  onRefresh: () => void;
  onAsk: (req: ConfirmRequest) => void;
}) {
  const t = useT();
  const [sort, setSort] = useState<WorkspaceSort>("idle");

  // 选择器只取原始值：busy 轮询每拍都新建 sessions 数组，取对象/集合会让整卡每 3 秒白重渲染
  const runningCount = useApp((s) => s.runningAgents.sessions.length);
  const runningTicketNos = useApp((s) =>
    s.runningAgents.sessions
      .map((r) => r.ticket_no ?? "")
      .filter(Boolean)
      .sort()
      .join(","),
  );
  const busyWorkspaces = useMemo(
    () => new Set(runningTicketNos ? runningTicketNos.split(",") : []),
    [runningTicketNos],
  );

  // 排序（展示层职责，后端保持事实原序）：闲置最久在前（无改动记录视为最久）；占用从大到小。
  const sorted = useMemo(() => {
    const list = [...(data?.workspaces ?? [])];
    const idleMs = (w: StorageWorkspace) =>
      w.last_active_ms == null ? Number.MAX_SAFE_INTEGER : Date.now() - w.last_active_ms;
    list.sort(sort === "bytes" ? (a, b) => b.bytes - a.bytes : (a, b) => idleMs(b) - idleMs(a));
    return list;
  }, [data, sort]);

  // 有可清理内容的工作区（口径与行内按钮一致：目录存在且非空）
  const prunableWorkspaces = useMemo(
    () => sorted.filter((w) => w.prunable.some((p) => p.bytes > 0 || p.files > 0)),
    [sorted],
  );
  const totalPrunableBytes = useMemo(
    () => prunableWorkspaces.reduce((sum, w) => sum + w.prunable_bytes, 0),
    [prunableWorkspaces],
  );

  const dirtyDirs = (ws: StorageWorkspace) => ws.prunable.filter((p) => p.bytes > 0 || p.files > 0);

  const askPrune = (ws: StorageWorkspace) => {
    const dirs = dirtyDirs(ws);
    if (dirs.length === 0) return;
    const listing = dirs
      .map((p) => t("storage.ws.pruneListing", { name: p.name, files: p.files, bytes: formatBytes(p.bytes) }))
      .join("\n");
    onAsk({
      title: t("storage.ws.pruneTitle", { id: ws.id }),
      impact: t("storage.ws.pruneImpact", {
        n: dirs.length,
        bytes: formatBytes(ws.prunable_bytes),
        listing,
      }),
      confirmLabel: t("storage.cache.confirm"),
      run: async () => {
        try {
          const r = await pruneStorageWorkspace(ws.id);
          showToast(
            r.removed_bytes > 0
              ? t("storage.ws.pruned", { id: ws.id, bytes: formatBytes(r.removed_bytes) })
              : t("storage.ws.prunedNothing", { id: ws.id }),
          );
          onRefresh();
        } catch (e) {
          showToast(t("storage.cleanFailed", { err: (e as Error).message }));
        }
      },
    });
  };

  /** 一键清理：确认框列出「当前快照」下的可清理工作区，真正删除时后端重新遍历。 */
  const askPruneAll = () => {
    if (runningCount > 0 || prunableWorkspaces.length === 0) return;
    const listing = prunableWorkspaces
      .map((w) =>
        t("storage.ws.pruneAllListing", {
          id: w.id,
          dirs: dirtyDirs(w).length,
          bytes: formatBytes(w.prunable_bytes),
        }),
      )
      .join("\n");
    onAsk({
      title: t("storage.ws.pruneAllTitle"),
      impact: t("storage.ws.pruneAllImpact", {
        n: prunableWorkspaces.length,
        bytes: formatBytes(totalPrunableBytes),
        listing,
      }),
      confirmLabel: t("storage.cache.confirm"),
      run: async () => {
        try {
          const r = await pruneAllStorageWorkspaces();
          showToast(
            r.workspaces.length > 0
              ? t("storage.ws.pruneAllDone", { n: r.workspaces.length, bytes: formatBytes(r.removed_bytes) })
              : t("storage.ws.pruneAllNothing"),
          );
          onRefresh();
        } catch (e) {
          showToast(t("storage.cleanFailed", { err: (e as Error).message }));
        }
      },
    });
  };

  const blockedByRunning = runningCount > 0;
  const nothingToClean = !loading && data != null && prunableWorkspaces.length === 0;
  const pruneAllTip = blockedByRunning
    ? t("storage.ws.pruneAllBusyTip")
    : nothingToClean
      ? t("storage.ws.pruneAllNoneTip")
      : t("storage.ws.pruneAllTip");

  return (
    <div className="card p-5">
      <CardHead
        Icon={Package}
        title={t("storage.ws.title")}
        hint={t("storage.ws.hint")}
        actions={
          <div className="flex items-center gap-2 flex-wrap justify-end">
            {blockedByRunning && (
              <span
                className="chip border border-warn/40 bg-warn-dim/60 text-warn text-[10.5px]"
                title={t("storage.ws.runningTip")}
              >
                <WarningCircle size={11} weight="fill" />
                {t("storage.ws.runningCount", { n: runningCount })}
              </span>
            )}
            <SortChip active={sort === "idle"} label={t("storage.ws.sortIdle")} onClick={() => setSort("idle")} />
            <SortChip active={sort === "bytes"} label={t("storage.ws.sortBytes")} onClick={() => setSort("bytes")} />
            <button
              className="btn btn-sm btn-danger-ghost"
              disabled={blockedByRunning || nothingToClean || loading || data == null}
              onClick={askPruneAll}
              title={pruneAllTip}
            >
              <Broom size={13} />
              {t("storage.ws.pruneAll")}
            </button>
            <button className="icon-btn" onClick={onRefresh} title={t("storage.refreshTip")} aria-label={t("storage.refreshTip")}>
              {loading ? <Spinner /> : <ArrowClockwise size={14} />}
            </button>
          </div>
        }
      />
      {error && (
        <div className="mt-3 text-[12.5px] text-danger flex items-center gap-1.5">
          <WarningCircle size={14} weight="fill" /> {error}
          <button className="btn btn-sm ml-1" onClick={onRefresh}>{t("common.retry")}</button>
        </div>
      )}
      {loading && !data && (
        <div className="mt-4 flex items-center gap-2 text-[12.5px] text-faint">
          <Spinner /> {t("storage.loadWorkspaces")}
        </div>
      )}
      {data && sorted.length === 0 && (
        <div className="mt-3 rounded-lg border border-edge bg-sunken px-3.5 py-3 text-[12px] text-faint">
          {t("storage.ws.empty")}
        </div>
      )}
      {sorted.length > 0 && (
        <div className="mt-3 grid gap-2">
          {sorted.map((ws) => {
            const dirs = dirtyDirs(ws);
            const cleanable = dirs.length > 0;
            const busy = busyWorkspaces.has(ws.id);
            return (
              <div
                key={ws.id}
                className={"rounded-lg border bg-sunken px-3 py-2.5 " + (busy ? "border-warn/30" : "border-edge")}
              >
                {/* 第一行：工单标识 + 标题（截断吸收宽度差）+ 右侧「最后改动 / 清理」恒定不换行 */}
                <div className="flex items-center gap-2 min-w-0">
                  <span className="font-mono text-[12.5px] font-medium text-ink shrink-0">{ws.id}</span>
                  {ws.ticket?.title ? (
                    <span className="min-w-0 flex-1 truncate text-[11.5px] text-dim" title={ws.ticket.title}>
                      {ws.ticket.title}
                    </span>
                  ) : (
                    <span className="min-w-0 flex-1 truncate text-[11px] text-faint">{t("storage.ws.noTicket")}</span>
                  )}
                  {busy && (
                    <span
                      className="chip border border-warn/40 bg-warn-dim/60 text-warn text-[10px]"
                      title={t("storage.ws.busyTip")}
                    >
                      <WarningCircle size={10} weight="fill" />
                      {t("storage.ws.busyBadge")}
                    </span>
                  )}
                  <span
                    className="shrink-0 whitespace-nowrap font-mono text-[11px] text-faint"
                    title={t("storage.ws.lastActiveTip")}
                  >
                    {t("storage.ws.lastActive", { idle: formatIdle(ws.last_active_ms, t) })}
                  </span>
                  <button
                    className="btn btn-sm btn-danger-ghost shrink-0"
                    disabled={!cleanable || busy}
                    onClick={() => askPrune(ws)}
                    title={
                      busy
                        ? t("storage.ws.busyTip")
                        : cleanable
                          ? t("storage.ws.pruneTip")
                          : t("storage.ws.pruneNoneTip")
                    }
                  >
                    <Trash size={13} />
                    {t("storage.clean")}
                  </button>
                </div>
                {/* 第二行：占用口径 chips + 路径（窄屏时路径整行折到下一行） */}
                <div className="mt-1.5 flex items-center flex-wrap gap-x-2 gap-y-1 min-w-0">
                  {ws.ticket?.project_id && (
                    <span className="chip border border-edge bg-raised text-faint text-[10px]">
                      {ws.ticket.project_id}
                    </span>
                  )}
                  <BytesChip
                    bytes={ws.bytes}
                    approx={ws.approx}
                    label={t("storage.ws.totalBytes")}
                    tip={t("storage.ws.totalBytesTip")}
                  />
                  {cleanable && (
                    <BytesChip
                      bytes={ws.prunable_bytes}
                      approx={ws.prunable_approx}
                      label={t("storage.ws.prunableBytes")}
                      tip={t("storage.ws.prunableBytesTip")}
                      tone="reclaim"
                    />
                  )}
                  <span className="ml-auto min-w-0 max-w-full truncate font-mono text-[10.5px] text-faint/80" title={ws.path}>
                    {ws.path}
                  </span>
                </div>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}

/** 破坏性操作二次确认弹窗：明确标注影响范围，处理期间禁止关闭。 */
function StorageConfirm({
  req,
  onClose,
}: {
  req: ConfirmRequest;
  onClose: () => void;
}) {
  const t = useT();
  const [working, setWorking] = useState(false);
  const close = useCallback(() => {
    if (working) return;
    onClose();
  }, [working, onClose]);
  const backdrop = useBackdropClose(close);

  const run = async () => {
    if (working) return;
    setWorking(true);
    try {
      await req.run();
    } finally {
      setWorking(false);
      onClose();
    }
  };

  return (
    <div
      className="fixed inset-0 z-50 grid place-items-center bg-black/55 backdrop-blur-[2px]"
      {...backdrop}
    >
      <div className="w-[440px] card shadow-2xl shadow-black/60 animate-rise" onClick={(e) => e.stopPropagation()}>
        <div className="flex items-center gap-2.5 px-5 h-12 border-b border-edge">
          <Warning size={15} className="text-warn" weight="fill" />
          <span className="text-[13.5px] font-semibold">{req.title}</span>
          <span className="flex-1" />
          <button className="icon-btn" onClick={close} aria-label={t("common.close")}>
            ✕
          </button>
        </div>
        <div className="p-5">
          <div className="rounded-lg border border-warn/30 bg-warn-dim/60 px-3.5 py-2.5 text-[12px] text-dim leading-relaxed whitespace-pre-wrap">
            {req.impact}
          </div>
        </div>
        <div className="flex justify-end gap-2 px-5 py-4 border-t border-edge">
          <button className="btn" onClick={close} disabled={working}>
            {t("common.cancel")}
          </button>
          <button className="btn btn-danger-ghost" disabled={working} onClick={() => void run()}>
            {working ? (
              <>
                <Spinner />
                {t("storage.working")}
              </>
            ) : (
              <>
                <Trash size={13} />
                {req.confirmLabel}
              </>
            )}
          </button>
        </div>
      </div>
    </div>
  );
}

/**
 * 存储设置（T-116）：数据目录 / 工作区存储管理 / 本地缓存清理。
 * live 模式下依赖后端 API；demo 模式只展示能力说明，不发起必然失败的存储请求。
 */
export function StorageBlock() {
  const t = useT();
  const mode = useApp((s) => s.mode);
  const [overview, setOverview] = useState<StorageOverview | null>(null);
  const [caches, setCaches] = useState<StorageCachesResponse | null>(null);
  const [workspaces, setWorkspaces] = useState<StorageWorkspacesResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [confirm, setConfirm] = useState<ConfirmRequest | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [ov, ca, ws] = await Promise.all([
        fetchStorageOverview(),
        fetchStorageCaches(),
        fetchStorageWorkspaces(),
      ]);
      setOverview(ov);
      setCaches(ca);
      setWorkspaces(ws);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setLoading(false);
    }
  }, []);

  const live = mode === "live";

  useEffect(() => {
    // 目录/缓存/工作区统计仅 live 有后端（demo 下不发请求、只展示能力说明）
    if (live) void load();
  }, [live, load]);

  const ask = (req: ConfirmRequest) => setConfirm(req);

  return (
    <div className="space-y-4">
      {live ? (
        <>
          <DataDirsCard overview={overview} loading={loading} error={error} onRefresh={() => void load()} />
          <WorkspacesCard
            data={workspaces}
            loading={loading}
            error={error}
            onRefresh={() => void load()}
            onAsk={ask}
          />
          <CacheCleanCard
            caches={caches}
            loading={loading}
            error={error}
            onRefresh={() => void load()}
            onAsk={ask}
          />
        </>
      ) : (
        <div className="card p-5">
          <CardHead Icon={HardDrives} title={t("storage.demo.title")} hint={t("storage.demo.hint")} />
          <div className="mt-3 rounded-lg border border-edge bg-sunken px-3.5 py-3 text-[12px] text-faint leading-relaxed">
            {t("storage.demo.desc")}
          </div>
        </div>
      )}
      {confirm && <StorageConfirm req={confirm} onClose={() => setConfirm(null)} />}
    </div>
  );
}
