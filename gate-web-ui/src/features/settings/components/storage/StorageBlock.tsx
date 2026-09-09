import { useCallback, useEffect, useState } from "react";
import {
  ArrowClockwise,
  Broom,
  ChatCircleDots,
  Database,
  FolderOpen,
  HardDrives,
  NotePencil,
  Quotes,
  Trash,
  Warning,
  WarningCircle,
} from "@phosphor-icons/react";
import {
  cleanStorageCache,
  fetchStorageCaches,
  fetchStorageOverview,
  openStorageDir,
} from "@/features/settings";
import { collectLocalData, clearAllLocalData, clearLocalDataCategory, type LocalDataEntry } from "./localData";
import type { StorageCachesResponse, StorageOverview } from "@/shared/types";
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

const LOCAL_ICONS: Record<LocalDataEntry["id"], typeof ChatCircleDots> = {
  queued_messages: ChatCircleDots,
  composer_drafts: NotePencil,
  pending_quotes: Quotes,
  assistant_history: ChatCircleDots,
};

const LOCAL_IMPACT: Record<LocalDataEntry["id"], string> = {
  queued_messages:
    "将删除全部会话的排队消息（含图片附件）。这些消息尚未发送，清空后不会再投递给 Agent，且无法恢复。",
  composer_drafts:
    "将删除全部工单的输入框草稿，包括当前输入框中的文字（立即清空，无法找回）。已发送的消息不受影响。",
  pending_quotes:
    "将删除全部工单挂起的引用胶囊。已随消息发送过的引用不受影响，仅丢弃尚未发送的胶囊。",
  assistant_history:
    "将删除 LLM 小助手的全部本地对话气泡（不上传后端，无法恢复）。小助手的偏好设置不受影响。",
};

/** 二次确认弹窗请求（清理/清空共用）：impact 必须写清影响范围。 */
interface ConfirmRequest {
  title: string;
  impact: string;
  confirmLabel: string;
  run: () => Promise<void>;
}

/** 卡片头（图标 + 标题 + 右侧动作位）。 */
function CardHead({
  Icon,
  title,
  hint,
  actions,
}: {
  Icon: typeof Database;
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

/** 本地数据管理分区（端侧）：排队消息 / 草稿 / 引用胶囊 / 小助手历史，查看占用与一键清空。 */
function LocalDataCard({
  entries,
  onRefresh,
  onAsk,
}: {
  entries: LocalDataEntry[];
  onRefresh: () => void;
  onAsk: (req: ConfirmRequest) => void;
}) {
  const askClear = (entry: LocalDataEntry) => {
    onAsk({
      title: `清空「${entry.label}」`,
      impact: `${LOCAL_IMPACT[entry.id as keyof typeof LOCAL_IMPACT] ?? "该操作不可恢复。"}当前占用 ${formatBytes(entry.bytes)}。仅清空这一类数据，其他类别不受影响。`,
      confirmLabel: "确认清空",
      run: async () => {
        const removed = clearLocalDataCategory(entry.id);
        showToast(removed > 0 ? `已清空「${entry.label}」· ${removed} 项` : `「${entry.label}」当前为空`);
        onRefresh();
      },
    });
  };

  const totalBytes = entries.reduce((n, e) => n + e.bytes, 0);

  return (
    <div className="card p-5">
      <CardHead
        Icon={Database}
        title="本地数据管理"
        hint="仅存于本浏览器，不上传后端"
        actions={
          <button className="icon-btn" onClick={onRefresh} title="重新统计占用" aria-label="重新统计占用">
            <ArrowClockwise size={14} />
          </button>
        }
      />
      <div className="mt-3 grid gap-2">
        {entries.map((entry) => {
          const Icon = LOCAL_ICONS[entry.id as keyof typeof LOCAL_ICONS] ?? Database;
          const empty = (entry.count ?? 0) === 0;
          return (
            <div key={entry.id} className="rounded-lg border border-edge bg-sunken px-3 py-2.5">
              <div className="flex items-center gap-2 flex-wrap">
                <Icon size={14} className="text-faint shrink-0" />
                <span className="text-[12.5px] font-medium text-ink">{entry.label}</span>
                <BytesChip bytes={entry.bytes} />
                {entry.count !== null && (
                  <span className="text-[11px] text-faint font-mono">{entry.count} 条</span>
                )}
                <span className="flex-1" />
                <button
                  className="btn btn-sm btn-danger-ghost"
                  disabled={empty}
                  onClick={() => askClear(entry)}
                  title={empty ? "当前为空" : "清空该类数据（需确认）"}
                >
                  <Trash size={13} />
                  清空
                </button>
              </div>
              <div className="mt-0.5 text-[11px] text-faint">{entry.hint}</div>
            </div>
          );
        })}
      </div>
      <div className="mt-3 flex items-center gap-2.5 flex-wrap">
        <span className="text-[11.5px] text-faint">
          合计约 <span className="font-mono text-dim">{formatBytes(totalBytes)}</span>
        </span>
        <span className="flex-1" />
        <button
          className="btn btn-sm btn-danger-ghost"
          disabled={totalBytes === 0}
          onClick={() =>
            onAsk({
              title: "一键清空本地数据",
              impact:
                `将同时清空排队消息、输入框草稿、引用胶囊与小助手对话历史（合计约 ${formatBytes(totalBytes)}）。` +
                "清空后无法恢复；已发送的消息与后端工单数据不受影响。",
              confirmLabel: "全部清空",
              run: async () => {
                const touched = clearAllLocalData();
                showToast(touched > 0 ? `已清空本地偏好数据（${touched} 类）` : "没有可清空的本地数据");
                onRefresh();
              },
            })
          }
        >
          <Trash size={13} />
          一键清空全部
        </button>
      </div>
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
          <div className="rounded-lg border border-warn/30 bg-warn-dim/60 px-3.5 py-2.5 text-[12px] text-dim leading-relaxed">
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
 * 存储设置（T-116）：数据目录 / 本地缓存清理 / 本地数据管理。
 * 前两类依赖后端 API（live），本地数据管理仅读写本浏览器，两种模式下均可用。
 */
export function StorageBlock() {
  const mode = useApp((s) => s.mode);
  const [overview, setOverview] = useState<StorageOverview | null>(null);
  const [caches, setCaches] = useState<StorageCachesResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [localEntries, setLocalEntries] = useState<LocalDataEntry[]>(() => collectLocalData());
  const [confirm, setConfirm] = useState<ConfirmRequest | null>(null);

  const refreshLocal = useCallback(() => {
    setLocalEntries(collectLocalData());
  }, []);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [ov, ca] = await Promise.all([fetchStorageOverview(), fetchStorageCaches()]);
      setOverview(ov);
      setCaches(ca);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setLoading(false);
    }
  }, []);

  // 本地数据管理两端可用；目录/缓存统计仅 live 有后端，demo 下只展示能力说明，
  // 不发起必然失败的存储 API 请求。
  const live = mode === "live";

  useEffect(() => {
    // 本地数据管理两端可用；目录/缓存统计仅 live 有后端（demo 下不发请求、只展示能力说明）
    refreshLocal();
    if (live) void load();
  }, [live, load, refreshLocal]);

  const ask = (req: ConfirmRequest) => setConfirm(req);

  return (
    <div className="space-y-4">
      {live ? (
        <>
          <DataDirsCard overview={overview} loading={loading} error={error} onRefresh={() => void load()} />
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
          <CardHead Icon={HardDrives} title="数据目录 · 本地缓存清理" hint="需要连接后端" />
          <div className="mt-3 rounded-lg border border-edge bg-sunken px-3.5 py-3 text-[12px] text-faint leading-relaxed">
            演示模式没有后端：数据目录占用统计、缓存清理与「在系统中打开」需要连接本地后端（live
            模式）后使用——届时将展示数据主目录/工单克隆根/数据库/Blob/审计日志的路径与占用，并可按类清理进程临时日志、Git
            临时目录与适配器诊断日志。
          </div>
        </div>
      )}
      <LocalDataCard entries={localEntries} onRefresh={refreshLocal} onAsk={ask} />
      {confirm && <StorageConfirm req={confirm} onClose={() => setConfirm(null)} />}
    </div>
  );
}
