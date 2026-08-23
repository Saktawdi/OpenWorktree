import { useCallback, useEffect, useMemo, useState } from "react";
import type { ReactElement } from "react";
import {
  CaretRight,
  FileText,
  FolderOpen,
  FolderPlus,
  GitBranch,
  TreeStructure,
  PencilSimple,
  Play,
  Trash,
} from "@phosphor-icons/react";
import { actions } from "../lib/actions";
import { loadProjectRepoView, loadProjectTree } from "../lib/api";
import { relativeTime, shortHash } from "../lib/format";
import { setView, showToast, switchProject, useApp } from "../lib/store";
import type { GitCommit, GitRepoView, GitTreeEntry, Project } from "../lib/types";
import { CopyButton } from "./ui";

const SIZE_LABEL: Record<string, string> = { small: "小型", medium: "中型", large: "大型" };

function ProjectDialog({
  initial,
  onClose,
}: {
  initial?: Project | null;
  onClose: () => void;
}) {
  const [name, setName] = useState(initial?.name ?? "");
  const [workspacePath, setWorkspacePath] = useState(initial?.workspacePath ?? "");
  const [initGit, setInitGit] = useState(!initial);
  const [priority, setPriority] = useState<string | null>(initial?.priority ?? null);
  const [size, setSize] = useState<string | null>(initial?.size ?? null);
  const [tags, setTags] = useState((initial?.tags ?? []).join(", "));
  const [saving, setSaving] = useState(false);

  const save = async () => {
    if (!name.trim() || (!initial && !workspacePath.trim())) return;
    setSaving(true);
    const tagList = tags
      .split(/[,，]/)
      .map((x) => x.trim())
      .filter(Boolean)
      .slice(0, 20);
    if (initial) {
      await actions.editProject(initial.id, {
        name: name.trim(),
        priority: priority as Project["priority"] | null,
        size: size as Project["size"] | null,
        tags: tagList,
      });
    } else {
      await actions.createProject({
        name: name.trim(),
        workspacePath: workspacePath.trim(),
        initGit,
        priority,
        size,
        tags: tagList,
      });
    }
    setSaving(false);
    onClose();
  };

  return (
    <div className="fixed inset-0 z-50 grid place-items-center bg-black/55 backdrop-blur-[2px]" onClick={onClose}>
      <div className="w-[460px] card shadow-2xl shadow-black/60 animate-rise" onClick={(e) => e.stopPropagation()}>
        <div className="flex items-center gap-2 px-5 h-12 border-b border-edge">
          <FolderPlus size={15} className="text-accent" />
          <span className="text-[13.5px] font-semibold">{initial ? "编辑项目" : "接入新项目"}</span>
        </div>
        <div className="p-5 space-y-4">
          <div>
            <label className="field-label">项目名称</label>
            <input
              autoFocus
              className="text-input"
              placeholder="例如：Acme Checkout"
              value={name}
              onChange={(e) => setName(e.target.value)}
            />
          </div>
          {!initial && (
            <>
              <div>
                <label className="field-label">工作区路径（绝对路径）</label>
                <input
                  className="text-input font-mono text-[12px]"
                  placeholder="D:/work/my-project"
                  value={workspacePath}
                  onChange={(e) => setWorkspacePath(e.target.value)}
                />
              </div>
              <label className="flex items-center gap-2 text-[12.5px] text-dim cursor-pointer select-none">
                <input
                  type="checkbox"
                  checked={initGit}
                  onChange={(e) => setInitGit(e.target.checked)}
                  className="accent-[#35d99e]"
                />
                目录为空时自动执行 git init（主分支 main）
              </label>
            </>
          )}
          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="field-label">优先级</label>
              <div className="flex gap-1">
                {[null, "P0", "P1", "P2", "P3"].map((p) => (
                  <button
                    key={String(p)}
                    onClick={() => setPriority(p)}
                    className={`flex-1 h-8 rounded-lg border text-[11.5px] cursor-pointer transition-colors ${
                      priority === p
                        ? "border-accent/50 bg-accent/10 text-accent"
                        : "border-edge text-dim hover:text-ink hover:bg-raised"
                    }`}
                  >
                    {p === null ? "无" : p}
                  </button>
                ))}
              </div>
            </div>
            <div>
              <label className="field-label">规模</label>
              <div className="flex gap-1">
                {[null, "small", "medium", "large"].map((sz) => (
                  <button
                    key={String(sz)}
                    onClick={() => setSize(sz)}
                    className={`flex-1 h-8 rounded-lg border text-[11.5px] cursor-pointer transition-colors ${
                      size === sz
                        ? "border-accent/50 bg-accent/10 text-accent"
                        : "border-edge text-dim hover:text-ink hover:bg-raised"
                    }`}
                  >
                    {sz === null ? "无" : SIZE_LABEL[sz]}
                  </button>
                ))}
              </div>
            </div>
          </div>
          <div>
            <label className="field-label">标签（逗号分隔）</label>
            <input
              className="text-input"
              placeholder="电商, 交易链路"
              value={tags}
              onChange={(e) => setTags(e.target.value)}
            />
          </div>
        </div>
        <div className="flex justify-end gap-2 px-5 py-4 border-t border-edge">
          <button className="btn" onClick={onClose}>
            取消
          </button>
          <button
            className="btn btn-primary"
            disabled={!name.trim() || (!initial && !workspacePath.trim()) || saving}
            onClick={save}
          >
            {saving ? "保存中…" : initial ? "保存修改" : "接入项目"}
          </button>
        </div>
      </div>
    </div>
  );
}

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

function GraphTab({ git }: { git: GitRepoView }) {
  const laneCount = Math.max(1, ...git.commits.map((c) => c.lane)) + 1;
  const graphW = laneCount * LANE_W;

  return (
    <div>
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

/** 仓库视图弹窗：live 模式打开即拉取分支图与文件树根目录，失败可重试。 */
function RepoViewDialog({ project, onClose }: { project: Project; onClose: () => void }) {
  const mode = useApp((s) => s.mode);
  const git = useApp((s) => s.gitViews[project.id]);
  const treeRoot = useApp((s) => s.treeViews[project.id]) ?? [];
  const [tab, setTab] = useState<"graph" | "tree">("graph");
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
          {(["graph", "tree"] as const).map((k) => (
            <button
              key={k}
              onClick={() => setTab(k)}
              className={`h-7 px-2.5 rounded-md text-[12px] cursor-pointer transition-colors ${
                tab === k ? "bg-raised text-ink border border-edge" : "text-dim hover:text-ink border border-transparent"
              }`}
            >
              {k === "graph" ? "分支图 · 提交历史" : "文件树"}
            </button>
          ))}
          <button
            className="icon-btn ml-1"
            onClick={onClose}
            aria-label="关闭"
          >
            <svg width="13" height="13" viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round">
              <path d="M2 2l8 8M10 2l-8 8" />
            </svg>
          </button>
        </div>

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
              {tab === "graph" && <GraphTab git={git} />}
              {tab === "tree" && <RepoTreeTab projectId={project.id} root={treeRoot} />}
            </>
          )}
        </div>
      </div>
    </div>
  );
}

export function ProjectsPage() {
  const projects = useApp((s) => s.projects);
  const activeId = useApp((s) => s.activeProjectId);
  const tickets = useApp((s) => s.tickets);
  const [dialog, setDialog] = useState<{ open: boolean; project: Project | null }>({ open: false, project: null });
  const [detailId, setDetailId] = useState<string | null>(null);
  const [confirmDelete, setConfirmDelete] = useState<string | null>(null);

  useEffect(() => {
    const handler = () => setDialog({ open: true, project: null });
    window.addEventListener("gate:new-project", handler);
    return () => window.removeEventListener("gate:new-project", handler);
  }, []);

  const detail = detailId ? projects.find((p) => p.id === detailId) : null;

  return (
    <div className="flex-1 min-h-0 overflow-y-auto">
      <div className="max-w-[1080px] mx-auto px-6 py-5">
        <div className="flex items-center gap-3 pb-4">
          <span className="kicker">项目接入</span>
          <span className="font-mono text-[11px] text-faint">{projects.length} 个项目</span>
          <span className="flex-1" />
          <button className="btn btn-primary h-8" onClick={() => setDialog({ open: true, project: null })}>
            <FolderPlus size={14} weight="fill" />
            接入新项目
          </button>
        </div>

        <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
          {projects.map((p) => {
            const isActive = p.id === activeId;
            const pTickets = tickets.filter((t) => t.projectId === p.id);
            const activeCount = pTickets.filter((t) => t.stage !== "DONE" && t.stage !== "CANCELLED").length;
            return (
              <div
                key={p.id}
                className={`card p-4 transition-colors ${isActive ? "border-accent/35" : "hover:border-edge-strong"}`}
              >
                <div className="flex items-center gap-2">
                  <FolderOpen size={15} className={isActive ? "text-accent" : "text-faint"} />
                  <span className="text-[13.5px] font-semibold truncate">{p.name}</span>
                  {isActive && <span className="chip border border-accent/30 bg-accent/10 text-accent">当前</span>}
                  <span className="flex-1" />
                  <button
                    className="icon-btn"
                    title="编辑项目"
                    aria-label="编辑项目"
                    onClick={() => setDialog({ open: true, project: p })}
                  >
                    <PencilSimple size={13} />
                  </button>
                  {confirmDelete === p.id ? (
                    <span className="flex items-center gap-1">
                      <button
                        className="chip border border-danger/40 bg-danger/10 text-danger cursor-pointer"
                        onClick={() => {
                          void actions.deleteProject(p.id);
                          setConfirmDelete(null);
                        }}
                      >
                        确认移除
                      </button>
                      <button className="chip border border-edge-strong text-dim cursor-pointer" onClick={() => setConfirmDelete(null)}>
                        返回
                      </button>
                    </span>
                  ) : (
                    <button
                      className="icon-btn hover:!text-danger"
                      title="移除项目"
                      aria-label="移除项目"
                      onClick={() => setConfirmDelete(p.id)}
                    >
                      <Trash size={13} />
                    </button>
                  )}
                </div>

                <div className="mt-1.5 font-mono text-[11px] text-faint truncate" title={p.workspacePath}>
                  {p.workspacePath}
                </div>

                <div className="mt-2.5 flex flex-wrap items-center gap-1.5">
                  <span className="chip border border-edge-strong bg-raised text-dim">
                    <GitBranch size={11} />
                    main
                  </span>
                  {p.size && <span className="chip border border-info/25 bg-info/10 text-info">{SIZE_LABEL[p.size]}</span>}
                  {p.priority && <span className="chip border border-warn/30 bg-warn/10 text-warn font-mono">{p.priority}</span>}
                  {p.tags.map((t) => (
                    <span key={t} className="chip border border-edge-strong bg-raised text-faint">
                      {t}
                    </span>
                  ))}
                </div>

                <div className="mt-3 pt-3 border-t border-edge flex items-center gap-3 text-[11.5px] text-faint">
                  <span>
                    工单 <span className="font-mono text-dim">{pTickets.length}</span>
                  </span>
                  <span>
                    活跃 <span className="font-mono text-dim">{activeCount}</span>
                  </span>
                  <span>更新于 {relativeTime(p.updatedAt)}</span>
                  <span className="flex-1" />
                  <button
                    className="inline-flex items-center gap-1 text-[11.5px] text-dim hover:text-ink cursor-pointer bg-transparent border-0 p-0"
                    onClick={() => setDetailId(detailId === p.id ? null : p.id)}
                  >
                    仓库视图
                    <CaretRight size={11} className={`transition-transform ${detailId === p.id ? "rotate-90" : ""}`} />
                  </button>
                  <button
                    className="inline-flex items-center gap-1 text-[11.5px] text-accent hover:text-accent-hi cursor-pointer bg-transparent border-0 p-0"
                    onClick={() => {
                      switchProject(p.id);
                      setView("workbench");
                    }}
                  >
                    <Play size={11} weight="fill" />
                    打开工作台
                  </button>
                </div>
              </div>
            );
          })}
          {projects.length === 0 && (
            <div className="col-span-full card border-dashed p-10 text-center">
              <div className="text-[13.5px] text-dim">尚未接入任何项目</div>
              <div className="mt-1 text-[12px] text-faint">接入本地工作区后，即可创建工单并开始沙箱协作</div>
            </div>
          )}
        </div>

        {detail && <RepoViewDialog project={detail} onClose={() => setDetailId(null)} />}
      </div>

      {dialog.open && <ProjectDialog initial={dialog.project} onClose={() => setDialog({ open: false, project: null })} />}
    </div>
  );
}
