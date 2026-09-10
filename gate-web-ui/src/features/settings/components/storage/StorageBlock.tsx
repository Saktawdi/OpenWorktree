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

/* ─── 展示元数据：后端只下发事实（key/id/占用），文案与图标由前端持有 ─── */

const DIR_META: Record<string, { label: string; desc: string }> = {
  gate_home: { label: "数据主目录", desc: "配置、数据库、审计与缓存的根目录" },
  clones_root: { label: "工单克隆根", desc: "各工单的隔离工作区（Git 克隆），删除会影响未发布工单" },
  db: { label: "SQLite 数据库", desc: "工单、会话与 Provider 等结构化数据" },
  blob_root: { label: "Blob 存储", desc: "快照差异与审查输出的原始内容（证据链）" },
  audit: { label: "审计日志", desc: "哈希链审计流水，追加写（证据链）" },
};

const CACHE_META: Record<string, { label: string; desc: string }> = {
  proc_temp: { label: "进程临时日志", desc: "git 等子进程的 stdout/stderr 临时文件，可安全清理" },
  gate_tmp: { label: "Git 临时目录", desc: "门禁 git 操作的临时工作文件，可安全清理" },
  adapters_log: { label: "适配器诊断日志", desc: "会话适配器的结构化运行日志，清空后从零重新记录" },
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

/** 字节占用徽标（approx 时加「约」）。 */
function BytesChip({ bytes, approx }: { bytes: number; approx?: boolean }) {
  return (
    <span className="chip border border-edge-strong bg-raised text-dim font-mono text-[10.5px]" title="估算值">
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
function formatIdle(lastActiveMs: number | null): string {
  if (lastActiveMs == null) return "无改动记录";
  const days = Math.floor((Date.now() - lastActiveMs) / 86_400_000);
  if (days <= 0) return "今天有改动";
  if (days === 1) return "1 天前";
  return `${days} 天前`;
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
  const [opening, setOpening] = useState<string | null>(null);

  const open = async (key: string) => {
    if (opening) return;
    setOpening(key);
    try {
      await openStorageDir(key);
      showToast("已请求系统打开目录");
    } catch (e) {
      showToast(`打开失败：${(e as Error).message}`);
    } finally {
      setOpening(null);
    }
  };

  return (
    <div className="card p-5">
      <CardHead
        Icon={HardDrives}
        title="数据目录"
        hint="门禁数据在本机的存放位置"
        actions={
          <button className="icon-btn" onClick={onRefresh} title="重新统计占用" aria-label="重新统计占用">
            {loading ? <Spinner /> : <ArrowClockwise size={14} />}
          </button>
        }
      />
      {error && (
        <div className="mt-3 text-[12.5px] text-danger flex items-center gap-1.5">
          <WarningCircle size={14} weight="fill" /> {error}
          <button className="btn btn-sm ml-1" onClick={onRefresh}>重试</button>
        </div>
      )}
      {loading && !overview && (
        <div className="mt-4 flex items-center gap-2 text-[12.5px] text-faint">
          <Spinner /> 正在统计数据目录占用 …
        </div>
      )}
      {overview && (
        <div className="mt-3 grid gap-2">
          {overview.toml_path && (
            <div className="rounded-lg border border-edge bg-sunken px-3 py-2.5">
              <div className="flex items-center gap-2">
                <span className="text-[12.5px] font-medium text-ink">配置文件 gate.toml</span>
                <span className="flex-1" />
                <CopyButton text={overview.toml_path} label="复制配置文件路径" />
              </div>
              <div className="mt-1 font-mono text-[11px] text-faint break-all">{overview.toml_path}</div>
            </div>
          )}
          {overview.dirs.map((d) => {
            const meta = DIR_META[d.key] ?? { label: d.key, desc: "" };
            return (
              <div key={d.key} className="rounded-lg border border-edge bg-sunken px-3 py-2.5">
                <div className="flex items-center gap-2 flex-wrap">
                  <span className="text-[12.5px] font-medium text-ink">{meta.label}</span>
                  <BytesChip bytes={d.bytes} approx={d.approx} />
                  {!d.exists && <span className="chip border border-edge bg-raised text-faint">未创建</span>}
                  <span className="flex-1" />
                  <CopyButton text={d.path} label={`复制 ${meta.label} 路径`} />
                  {d.openable && (
                    <button
                      className="btn btn-sm"
                      disabled={opening !== null}
                      onClick={() => void open(d.key)}
                      title="在系统文件管理器中打开"
                    >
                      {opening === d.key ? <Spinner /> : <FolderOpen size={13} />}
                      打开
                    </button>
                  )}
                </div>
                <div className="mt-1 font-mono text-[11px] text-faint break-all">{d.path}</div>
                {meta.desc && <div className="mt-0.5 text-[11px] text-faint">{meta.desc}</div>}
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
  const askClean = (id: string) => {
    const meta = CACHE_META[id] ?? { label: id, desc: "" };
    const entry = caches?.caches.find((c) => c.id === id);
    const size = entry ? formatBytes(entry.bytes) : "";
    const files = entry?.files ?? 0;
    onAsk({
      title: `清理「${meta.label}」`,
      impact:
        `将删除 ${entry?.path ?? "该类别"} 下的 ${files} 个临时文件（约 ${size}）。` +
        `${meta.desc}工单数据、审查快照与审计记录不受影响。`,
      confirmLabel: "确认清理",
      run: async () => {
        try {
          const r = await cleanStorageCache(id);
          showToast(`已清理「${meta.label}」· 释放 ${formatBytes(r.removed_bytes)}`);
          onRefresh();
        } catch (e) {
          showToast(`清理失败：${(e as Error).message}`);
        }
      },
    });
  };

  return (
    <div className="card p-5">
      <CardHead
        Icon={Broom}
        title="本地缓存清理"
        hint="仅清理可再生成的临时文件，不触碰证据链"
        actions={
          <button className="icon-btn" onClick={onRefresh} title="重新统计占用" aria-label="重新统计占用">
            {loading ? <Spinner /> : <ArrowClockwise size={14} />}
          </button>
        }
      />
      {error && (
        <div className="mt-3 text-[12.5px] text-danger flex items-center gap-1.5">
          <WarningCircle size={14} weight="fill" /> {error}
          <button className="btn btn-sm ml-1" onClick={onRefresh}>重试</button>
        </div>
      )}
      {loading && !caches && (
        <div className="mt-4 flex items-center gap-2 text-[12.5px] text-faint">
          <Spinner /> 正在统计缓存占用 …
        </div>
      )}
      {caches && (
        <div className="mt-3 grid gap-2">
          {caches.caches.map((c) => {
            const meta = CACHE_META[c.id] ?? { label: c.id, desc: "" };
            return (
              <div key={c.id} className="rounded-lg border border-edge bg-sunken px-3 py-2.5">
                <div className="flex items-center gap-2 flex-wrap">
                  <span className="text-[12.5px] font-medium text-ink">{meta.label}</span>
                  <BytesChip bytes={c.bytes} approx={c.approx} />
                  <span className="text-[11px] text-faint font-mono">{c.files} 个文件</span>
                  <span className="flex-1" />
                  <button
                    className="btn btn-sm btn-danger-ghost"
                    disabled={c.files === 0}
                    onClick={() => askClean(c.id)}
                    title={c.files === 0 ? "暂无可清理内容" : "清理该类缓存（需确认）"}
                  >
                    <Trash size={13} />
                    清理
                  </button>
                </div>
                <div className="mt-0.5 text-[11px] text-faint">{meta.desc}</div>
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
 * 天数（可按闲置/占用排序）与可再生目录（node_modules/构建产物）一键清理。
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
  const [sort, setSort] = useState<WorkspaceSort>("idle");

  // 排序（展示层职责，后端保持事实原序）：闲置最久在前（无改动记录视为最久）；占用从大到小。
  const sorted = useMemo(() => {
    const list = [...(data?.workspaces ?? [])];
    const idleMs = (w: StorageWorkspace) =>
      w.last_active_ms == null ? Number.MAX_SAFE_INTEGER : Date.now() - w.last_active_ms;
    list.sort(sort === "bytes" ? (a, b) => b.bytes - a.bytes : (a, b) => idleMs(b) - idleMs(a));
    return list;
  }, [data, sort]);

  const askPrune = (ws: StorageWorkspace) => {
    const dirs = ws.prunable.filter((p) => p.bytes > 0 || p.files > 0);
    if (dirs.length === 0) return;
    const listing = dirs
      .map((p) => `· ${p.name}（${p.files} 个文件，约 ${formatBytes(p.bytes)}）`)
      .join("\n");
    onAsk({
      title: `清理工作区 ${ws.id} 的可再生文件`,
      impact:
        `将删除以下 ${dirs.length} 个可再生目录（合计约 ${formatBytes(ws.prunable_bytes)}）：\n${listing}\n\n` +
        "依赖与构建产物删后可由包管理器/构建工具重新生成；源代码与 Git 历史不受影响。正在运行的构建/IDE 若持有文件，对应文件会跳过删除。",
      confirmLabel: "确认清理",
      run: async () => {
        try {
          const r = await pruneStorageWorkspace(ws.id);
          showToast(
            r.removed_bytes > 0
              ? `已清理工作区 ${ws.id} · 释放 ${formatBytes(r.removed_bytes)}`
              : `工作区 ${ws.id} 没有可清理的内容`,
          );
          onRefresh();
        } catch (e) {
          showToast(`清理失败：${(e as Error).message}`);
        }
      },
    });
  };

  return (
    <div className="card p-5">
      <CardHead
        Icon={Package}
        title="工作区存储管理"
        hint="克隆工作区是磁盘占用大头，可清理可再生文件"
        actions={
          <div className="flex items-center gap-2">
            <SortChip active={sort === "idle"} label="按闲置" onClick={() => setSort("idle")} />
            <SortChip active={sort === "bytes"} label="按占用" onClick={() => setSort("bytes")} />
            <button className="icon-btn" onClick={onRefresh} title="重新统计占用" aria-label="重新统计占用">
              {loading ? <Spinner /> : <ArrowClockwise size={14} />}
            </button>
          </div>
        }
      />
      {error && (
        <div className="mt-3 text-[12.5px] text-danger flex items-center gap-1.5">
          <WarningCircle size={14} weight="fill" /> {error}
          <button className="btn btn-sm ml-1" onClick={onRefresh}>重试</button>
        </div>
      )}
      {loading && !data && (
        <div className="mt-4 flex items-center gap-2 text-[12.5px] text-faint">
          <Spinner /> 正在统计工作区占用 …
        </div>
      )}
      {data && sorted.length === 0 && (
        <div className="mt-3 rounded-lg border border-edge bg-sunken px-3.5 py-3 text-[12px] text-faint">
          克隆根下暂无工作区。新建工单后，其隔离工作区会出现在这里。
        </div>
      )}
      {sorted.length > 0 && (
        <div className="mt-3 grid gap-2">
          {sorted.map((ws) => {
            const cleanable = ws.prunable_bytes > 0;
            return (
              <div key={ws.id} className="rounded-lg border border-edge bg-sunken px-3 py-2.5">
                <div className="flex items-center gap-2 flex-wrap">
                  <span className="text-[12.5px] font-medium text-ink font-mono">{ws.id}</span>
                  {ws.ticket?.title && (
                    <span className="text-[11.5px] text-dim truncate max-w-[320px]" title={ws.ticket.title}>
                      {ws.ticket.title}
                    </span>
                  )}
                  {ws.ticket?.project_id && (
                    <span className="chip border border-edge bg-raised text-faint text-[10px]">
                      {ws.ticket.project_id}
                    </span>
                  )}
                  {!ws.ticket && <span className="text-[11px] text-faint">未关联工单</span>}
                  <BytesChip bytes={ws.bytes} approx={ws.approx} />
                  {cleanable && <BytesChip bytes={ws.prunable_bytes} approx={ws.prunable_approx} />}
                  <span className="flex-1" />
                  <span
                    className="text-[11px] text-faint font-mono whitespace-nowrap"
                    title="最后改动（重装依赖/git 操作不计入）"
                  >
                    最后改动 {formatIdle(ws.last_active_ms)}
                  </span>
                  <button
                    className="btn btn-sm btn-danger-ghost"
                    disabled={!cleanable}
                    onClick={() => askPrune(ws)}
                    title={cleanable ? "清理 node_modules/构建产物等可再生目录（需确认）" : "没有可清理的可再生目录"}
                  >
                    <Trash size={13} />
                    清理
                  </button>
                </div>
                <div className="mt-1 font-mono text-[10.5px] text-faint/80 break-all">{ws.path}</div>
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
          <button className="icon-btn" onClick={close} aria-label="关闭">
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
            取消
          </button>
          <button className="btn btn-danger-ghost" disabled={working} onClick={() => void run()}>
            {working ? (
              <>
                <Spinner />
                处理中…
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
          <CardHead Icon={HardDrives} title="数据目录 · 工作区存储 · 本地缓存" hint="需要连接后端" />
          <div className="mt-3 rounded-lg border border-edge bg-sunken px-3.5 py-3 text-[12px] text-faint leading-relaxed">
            演示模式没有后端：连接本地后端（live 模式）后，这里将展示数据主目录/工单克隆根/数据库/Blob/审计日志的路径与占用，
            并支持「在系统中打开」；可查看各工单工作区的关联项目、总占用与最后改动距今天数（支持按闲置/占用排序），一键清理
            node_modules/构建产物等可再生文件；还可按类清理进程临时日志、Git 临时目录与适配器诊断日志。
          </div>
        </div>
      )}
      {confirm && <StorageConfirm req={confirm} onClose={() => setConfirm(null)} />}
    </div>
  );
}
