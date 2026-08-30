import { useCallback, useEffect, useMemo, useState } from "react";
import type { ReactElement } from "react";
import { motion } from "motion/react";
import {
  ArrowsClockwise,
  ArrowsOutSimple,
  CaretRight,
  FileText,
  FolderOpen,
  GitBranch,
  TreeStructure,
  WarningCircle,
} from "@phosphor-icons/react";
import { loadProjectRepoView, loadProjectTree, syncProjectWorkspace } from "../lib/api";
import { relativeTime, shortHash } from "../lib/format";
import { closeRepoView, openRepoView, showToast, useApp } from "../lib/store";
import type { GitCommit, GitRepoView, GitTreeEntry, Project } from "../lib/types";
import { CopyButton } from "./ui";

const LANE_COLORS = ["var(--color-accent)", "#7cc7f7", "#c79bf5", "#f5b84f"];
const ROW_H = 40;
const LANE_W = 28;

function CommitGraph({ commits }: { commits: GitCommit[] }) {
  const laneCount = Math.max(1, ...commits.map((c) => c.lane)) + 1;
  const graphW = laneCount * LANE_W;
  const height = commits.length * ROW_H;

  const rowOf = useMemo(() => {
    const m = new Map<string, number>();
    commits.forEach((c, i) => m.set(c.sha, i));
    return m;
  }, [commits]);

  const cx = (lane: number) => lane * LANE_W + LANE_W / 2;
  const cy = (row: number) => row * ROW_H + ROW_H / 2;

  return (
    <svg width={graphW} height={height} className="shrink-0 block">
      {commits.map((c, i) =>
        c.parents.map((p, pi) => {
          const j = rowOf.get(p);
          if (j === undefined) return null;
          const x1 = cx(c.lane);
          const x2 = cx(commits[j].lane);
          const y1 = cy(i);
          const y2 = cy(j);
          const color = LANE_COLORS[c.lane % LANE_COLORS.length];
          if (x1 === x2) {
            return (
              <line key={`${c.sha}-${pi}`} x1={x1} y1={y1} x2={x2} y2={y2} stroke={color} strokeOpacity={0.35} strokeWidth={1.5} />
            );
          }
          const midY = (y1 + y2) / 2;
          return (
            <path
              key={`${c.sha}-${pi}`}
              d={`M ${x1} ${y1} C ${x1} ${midY}, ${x2} ${midY}, ${x2} ${y2}`}
              fill="none"
              stroke={color}
              strokeOpacity={0.35}
              strokeWidth={1.5}
            />
          );
        }),
      )}
      {commits.map((c, i) => {
        const color = LANE_COLORS[c.lane % LANE_COLORS.length];
        const hasRefs = c.refs.length > 0;
        return (
          <g key={c.sha}>
            {hasRefs && (
              <circle cx={cx(c.lane)} cy={cy(i)} r={8} fill={color} fillOpacity={0.12} />
            )}
            <circle cx={cx(c.lane)} cy={cy(i)} r={5} fill="var(--color-canvas)" stroke={color} strokeWidth={1.8} />
            {hasRefs && <circle cx={cx(c.lane)} cy={cy(i)} r={2.5} fill={color} />}
          </g>
        );
      })}
    </svg>
  );
}

function GraphTab({ git, projectId, onReload }: { git: GitRepoView; projectId: string; onReload: () => Promise<void> }) {
  const [syncing, setSyncing] = useState(false);
  const laneCount = Math.max(1, ...git.commits.map((c) => c.lane)) + 1;
  const graphW = laneCount * LANE_W;

  const auth = git.auth;
  const authTip = auth?.tip?.trim();
  const targetRef = auth?.target_ref || "refs/heads/main";
  const branchName = targetRef.replace(/^refs\/heads\//, "");
  const targetBranch = git.branches.find(
    (b) => b.name === targetRef || b.name === branchName || b.name.replace(/^refs\/heads\//, "") === branchName
  );
  // 目标分支缺失（如历史发布从未回写）视作最大落后——恰是需要补同步的场景。
  const isBehind = Boolean(authTip && targetBranch?.tip !== authTip);

  const handleSync = async () => {
    setSyncing(true);
    try {
      await syncProjectWorkspace(projectId);
      await onReload();
    } finally {
      setSyncing(false);
    }
  };

  return (
    <div>
      {isBehind && (
        <div className="flex items-center justify-between gap-3 px-4 py-2 bg-warn/10 border-b border-warn/25 text-warn text-[12px]">
          <div className="flex items-center gap-2">
            <WarningCircle size={15} weight="fill" className="shrink-0" />
            <span>
              工作区落后于权威库（<span className="font-mono">{shortHash(authTip!, 8, 0)}</span>）
            </span>
          </div>
          <button
            className="btn btn-sm border-warn/30 text-warn hover:bg-warn/15 h-6 px-2.5 text-[11.5px] cursor-pointer"
            disabled={syncing}
            onClick={() => void handleSync()}
          >
            {syncing ? (
              <>
                <ArrowsClockwise size={12} className="animate-spin mr-1 inline" />
                同步中…
              </>
            ) : (
              "同步工作区"
            )}
          </button>
        </div>
      )}
      <div className="flex flex-wrap gap-2 px-4 py-3 border-b border-edge">
        {git.branches.map((b) => (
          <span
            key={b.name}
            className="inline-flex items-center gap-1.5 h-6 px-2.5 rounded-md border border-edge-strong bg-raised text-dim font-mono text-[11.5px]"
          >
            <GitBranch size={11} className={b.lane === 0 ? "text-accent" : "text-info"} />
            <span>{b.name.replace("refs/heads/", "")}</span>
            <span className="text-faint">·</span>
            <span className="text-faint">{shortHash(b.tip, 6, 0)}</span>
          </span>
        ))}
      </div>
      <div className="overflow-x-auto">
        <div className="min-w-max relative" style={{ paddingLeft: graphW }}>
          <div className="absolute left-3 top-0 bottom-0">
            <CommitGraph commits={git.commits} />
          </div>
          {git.commits.map((c) => (
            <div
              key={c.sha}
              className="flex items-center gap-3 px-4 hover:bg-raised/50 transition-colors border-b border-edge/40 last:border-b-0"
              style={{ height: ROW_H }}
            >
              <span className="text-[12.5px] text-ink truncate max-w-[400px]">{c.message}</span>
              {c.refs.map((ref) => (
                <span
                  key={ref}
                  className={`inline-flex items-center shrink-0 h-5 px-1.5 rounded border font-mono text-[10.5px] ${
                    ref.startsWith("tag:")
                      ? "border-warn/30 bg-warn/8 text-warn"
                      : "border-accent/25 bg-accent/8 text-accent"
                  }`}
                >
                  {ref.startsWith("tag:") && (
                    <svg width="9" height="9" viewBox="0 0 12 12" fill="none" className="mr-0.5">
                      <path d="M2 6h8M8 3l2 3-2 3" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round"/>
                    </svg>
                  )}
                  {ref.replace("refs/heads/", "").replace("tag:", "")}
                </span>
              ))}
              <span className="flex-1" />
              <span className="text-[11px] text-faint shrink-0">
                {c.author}
                <span className="mx-1 text-faint/50">·</span>
                {relativeTime(c.time)}
              </span>
              <CopyButton text={c.sha} label="复制提交 ID" />
            </div>
          ))}
        </div>
      </div>
      {git.truncated && (
        <div className="px-4 py-2.5 border-t border-edge text-[11.5px] text-faint">
          仅显示最近 100 条提交，更早的历史未在图中呈现
        </div>
      )}
    </div>
  );
}

/** 文件树：目录按需懒加载（每次展开拉取一层），子目录结果缓存在组件内。 */
function RepoTreeTab({ projectId, root }: { projectId: string; root: GitTreeEntry[] }) {
  const mode = useApp((s) => s.mode);
  const [children, setChildren] = useState<Record<string, GitTreeEntry[]>>({});
  const [expanded, setExpanded] = useState<Record<string, boolean>>({});
  const [busy, setBusy] = useState<string | null>(null);

  const toggle = async (dir: string) => {
    const nextOpen = !expanded[dir];
    setExpanded((e) => ({ ...e, [dir]: nextOpen }));
    if (!nextOpen || children[dir] || mode !== "live") return;
    setBusy(dir);
    try {
      const list = await loadProjectTree(projectId, dir);
      setChildren((c) => ({ ...c, [dir]: list }));
    } catch (e) {
      showToast(`读取目录失败：${(e as Error).message}`);
      setExpanded((e) => ({ ...e, [dir]: false }));
    } finally {
      setBusy(null);
    }
  };

  const rows: ReactElement[] = [];
  const render = (entries: GitTreeEntry[], depth: number) => {
    for (const e of entries) {
      const isOpen = !!expanded[e.path];
      rows.push(
        <div
          key={e.path}
          className="flex items-center gap-2 pr-3 h-9 rounded-lg hover:bg-raised/60 transition-colors"
          style={{ paddingLeft: 10 + depth * 18 }}
        >
          {e.type === "dir" ? (
            <>
              <button
                className="shrink-0 w-4 h-4 grid place-items-center text-faint hover:text-ink cursor-pointer bg-transparent border-0 p-0"
                onClick={() => void toggle(e.path)}
                aria-label={isOpen ? `折叠 ${e.path}` : `展开 ${e.path}`}
              >
                <CaretRight size={12} className={`transition-transform ${isOpen ? "rotate-90" : ""}`} />
              </button>
              <FolderOpen size={14} className="text-info/70 shrink-0" />
            </>
          ) : (
            <>
              <span className="w-4 shrink-0" />
              <FileText size={14} className="text-faint shrink-0" />
            </>
          )}
          <span className="font-mono text-[12.5px] text-ink truncate">{e.path.split("/").pop()}</span>
          <span className="flex-1" />
          {busy === e.path && <span className="text-[11px] text-faint shrink-0">加载中…</span>}
          <span className="hidden md:inline text-[11.5px] text-faint truncate max-w-[280px]">{e.lastMessage}</span>
          <span className="font-mono text-[11px] text-faint w-[60px] text-right shrink-0">
            {e.type === "file" ? `${((e.size ?? 0) / 1024).toFixed(1)} KB` : ""}
          </span>
          <span className="font-mono text-[11px] text-faint w-[58px] text-right shrink-0">{e.lastCommitShort}</span>
        </div>,
      );
      if (e.type === "dir" && isOpen) {
        render(children[e.path] ?? [], depth + 1);
      }
    }
  };
  render(root, 0);

  if (root.length === 0) {
    return <div className="p-8 text-center text-[12.5px] text-faint">HEAD 中没有已提交的文件</div>;
  }
  return <div className="p-2">{rows}</div>;
}

type RepoTab = "graph" | "tree";
const REPO_TABS: Array<{ key: RepoTab; label: string }> = [
  { key: "graph", label: "分支图 · 提交历史" },
  { key: "tree", label: "文件树" },
];

/** 弹窗/整页共用的数据加载与 tab 状态。 */
function useRepoViewData(project: Project) {
  const mode = useApp((s) => s.mode);
  const git = useApp((s) => s.gitViews[project.id]);
  const treeRoot = useApp((s) => s.treeViews[project.id]) ?? [];
  const [tab, setTab] = useState<RepoTab>("graph");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const reload = useCallback(async () => {
    if (mode !== "live") return;
    setLoading(true);
    setError(null);
    try {
      await Promise.all([loadProjectRepoView(project.id), loadProjectTree(project.id)]);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setLoading(false);
    }
  }, [mode, project.id]);

  useEffect(() => {
    void reload();
  }, [reload]);

  return { git, treeRoot, tab, setTab, loading, error, reload };
}

function TabButtons({ tab, setTab }: { tab: RepoTab; setTab: (t: RepoTab) => void }) {
  return (
    <>
      {REPO_TABS.map((t) => (
        <button
          key={t.key}
          onClick={() => setTab(t.key)}
          className={`h-7 px-2.5 rounded-md text-[12px] cursor-pointer transition-colors ${
            tab === t.key ? "bg-raised text-ink border border-edge" : "text-dim hover:text-ink border border-transparent"
          }`}
        >
          {t.label}
        </button>
      ))}
    </>
  );
}

function RepoViewBody({
  project,
  tab,
  git,
  treeRoot,
  loading,
  error,
  reload,
}: {
  project: Project;
  tab: RepoTab;
  git: GitRepoView | undefined;
  treeRoot: GitTreeEntry[];
  loading: boolean;
  error: string | null;
  reload: () => Promise<void>;
}) {
  return (
    <div className="flex-1 min-h-0 overflow-auto">
      {error ? (
        <div className="p-8 text-center">
          <div className="text-[12.5px] text-warn leading-relaxed">{error}</div>
          <button className="btn mt-3" onClick={() => void reload()}>
            重试
          </button>
        </div>
      ) : !git ? (
        <div className="p-8 text-center text-[12.5px] text-faint">
          {loading ? "仓库视图加载中…" : "当前项目暂无仓库数据"}
        </div>
      ) : (
        <>
          {tab === "graph" && <GraphTab git={git} projectId={project.id} onReload={reload} />}
          {tab === "tree" && <RepoTreeTab projectId={project.id} root={treeRoot} />}
        </>
      )}
    </div>
  );
}

/** 仓库视图弹窗：live 模式打开即拉取分支图与文件树根目录，失败可重试；放大按钮跳转整页。 */
export function RepoViewDialog({ project, onClose }: { project: Project; onClose: () => void }) {
  const { git, treeRoot, tab, setTab, loading, error, reload } = useRepoViewData(project);

  return (
    <div className="fixed inset-0 z-50 grid place-items-center bg-black/55 backdrop-blur-[2px]" onClick={onClose}>
      <div
        className="w-[880px] max-h-[85vh] card shadow-2xl shadow-black/60 animate-rise overflow-hidden flex flex-col"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center gap-2 px-4 h-11 border-b border-edge shrink-0">
          <TreeStructure size={14} className="text-dim" />
          <span className="text-[13px] font-semibold">{project.name}</span>
          <span className="font-mono text-[11px] text-faint">· 仓库视图</span>
          <span className="flex-1" />
          <TabButtons tab={tab} setTab={setTab} />
          <button
            className="icon-btn ml-1"
            title="放大为整页"
            aria-label="放大为整页"
            onClick={() => {
              openRepoView(project.id);
              onClose();
            }}
          >
            <ArrowsOutSimple size={13} />
          </button>
          <button
            className="icon-btn"
            onClick={onClose}
            aria-label="关闭"
          >
            <svg width="13" height="13" viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round">
              <path d="M2 2l8 8M10 2l-8 8" />
            </svg>
          </button>
        </div>

        <RepoViewBody
          project={project}
          tab={tab}
          git={git}
          treeRoot={treeRoot}
          loading={loading}
          error={error}
          reload={reload}
        />
      </div>
    </div>
  );
}

/** 仓库视图整页：与弹窗同一套布局（分支图/文件树），仅把弹窗外壳换成页面容器。 */
export function RepoViewPage() {
  const project = useApp((s) => s.projects.find((p) => p.id === s.repoViewProjectId));

  if (!project) {
    // 直接刷新落到该视图时可能还没有项目数据：回项目列表兜底。
    return (
      <div className="flex-1 min-h-0 grid place-items-center">
        <button className="btn" onClick={() => closeRepoView()}>
          返回项目列表
        </button>
      </div>
    );
  }

  return <RepoViewPageInner key={project.id} project={project} />;
}

function RepoViewPageInner({ project }: { project: Project }) {
  const { git, treeRoot, tab, setTab, loading, error, reload } = useRepoViewData(project);

  return (
    <div className="flex-1 min-h-0 flex flex-col">
      <motion.div
        initial={{ opacity: 0, scale: 0.965, y: 12 }}
        animate={{ opacity: 1, scale: 1, y: 0 }}
        transition={{ type: "spring", stiffness: 380, damping: 32 }}
        className="flex-1 min-h-0 w-full max-w-[1400px] mx-auto px-6 pt-4 pb-5 flex flex-col"
      >
        <div className="flex items-center gap-2 pb-3 shrink-0">
          <button className="btn h-8" onClick={() => closeRepoView()}>
            <svg width="12" height="12" viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round">
              <path d="M7.5 2L3.5 6l4 4" />
            </svg>
            返回项目
          </button>
          <TreeStructure size={14} className="text-dim ml-1" />
          <span className="text-[13px] font-semibold">{project.name}</span>
          <span className="font-mono text-[11px] text-faint">· 仓库视图</span>
          <span className="flex-1" />
          <TabButtons tab={tab} setTab={setTab} />
        </div>
        <div className="flex-1 min-h-0 card overflow-hidden flex flex-col">
          <RepoViewBody
            project={project}
            tab={tab}
            git={git}
            treeRoot={treeRoot}
            loading={loading}
            error={error}
            reload={reload}
          />
        </div>
      </motion.div>
    </div>
  );
}
