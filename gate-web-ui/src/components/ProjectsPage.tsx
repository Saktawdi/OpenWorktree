import { useEffect, useMemo, useState } from "react";
import {
  CaretRight,
  FolderOpen,
  FolderPlus,
  GitBranch,
  TreeStructure,
  PencilSimple,
  Play,
  Trash,
} from "@phosphor-icons/react";
import { actions } from "../lib/actions";
import { relativeTime, shortHash } from "../lib/format";
import { setView, switchProject, useApp } from "../lib/store";
import type { GitCommit, Project } from "../lib/types";

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
const ROW_H = 38;
const LANE_W = 30;

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
              <line key={`${c.sha}-${pi}`} x1={x1} y1={y1} x2={x2} y2={y2} stroke={color} strokeOpacity={0.4} strokeWidth={1.6} />
            );
          }
          const midY = (y1 + y2) / 2;
          return (
            <path
              key={`${c.sha}-${pi}`}
              d={`M ${x1} ${y1} C ${x1} ${midY}, ${x2} ${midY}, ${x2} ${y2}`}
              fill="none"
              stroke={color}
              strokeOpacity={0.4}
              strokeWidth={1.6}
            />
          );
        }),
      )}
      {commits.map((c, i) => {
        const color = LANE_COLORS[c.lane % LANE_COLORS.length];
        return (
          <g key={c.sha}>
            <circle cx={cx(c.lane)} cy={cy(i)} r={5.5} fill="var(--color-canvas)" stroke={color} strokeWidth={2} />
            {c.refs.length > 0 && <circle cx={cx(c.lane)} cy={cy(i)} r={2.2} fill={color} />}
          </g>
        );
      })}
    </svg>
  );
}

function GraphTab({ git }: { git: import("../lib/types").GitRepoView }) {
  const laneCount = Math.max(1, ...git.commits.map((c) => c.lane)) + 1;
  const graphW = laneCount * LANE_W;

  return (
    <div>
      <div className="flex flex-wrap gap-1.5 px-4 pt-3 pb-3 border-b border-edge">
        {git.branches.map((b) => (
          <span key={b.name} className="chip border border-edge-strong bg-raised text-dim font-mono">
            <GitBranch size={11} className={b.lane === 0 ? "text-accent" : "text-info"} />
            {b.name.replace("refs/heads/", "")}
            <span className="text-faint">· {shortHash(b.tip, 6, 0)}</span>
          </span>
        ))}
      </div>
      <div className="overflow-x-auto p-2">
        <div className="min-w-max relative" style={{ paddingLeft: graphW }}>
          <div className="absolute left-2 top-2 bottom-2">
            <CommitGraph commits={git.commits} />
          </div>
          {git.commits.map((c) => (
            <div key={c.sha} className="flex items-center gap-3 pr-3 rounded-lg hover:bg-raised/40 transition-colors" style={{ height: ROW_H }}>
              <span className="font-mono text-[11.5px] text-faint w-[64px] shrink-0">{shortHash(c.sha, 7, 0)}</span>
              <span className="text-[12.5px] text-ink truncate max-w-[420px]">{c.message}</span>
              {c.refs.map((ref) => (
                <span
                  key={ref}
                  className={`chip shrink-0 border font-mono ${
                    ref.startsWith("tag:")
                      ? "border-warn/35 bg-warn/10 text-warn"
                      : "border-accent/30 bg-accent/10 text-accent"
                  }`}
                >
                  {ref.replace("refs/heads/", "").replace("tag:", "")}
                </span>
              ))}
              <span className="flex-1" />
              <span className="text-[11px] text-faint shrink-0">
                {c.author} · {relativeTime(c.time)}
              </span>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}

export function ProjectsPage() {
  const projects = useApp((s) => s.projects);
  const activeId = useApp((s) => s.activeProjectId);
  const tickets = useApp((s) => s.tickets);
  const gitViews = useApp((s) => s.gitViews);
  const treeViews = useApp((s) => s.treeViews);
  const [dialog, setDialog] = useState<{ open: boolean; project: Project | null }>({ open: false, project: null });
  const [detailId, setDetailId] = useState<string | null>(null);
  const [tab, setTab] = useState<"graph" | "tree">("graph");
  const [confirmDelete, setConfirmDelete] = useState<string | null>(null);

  useEffect(() => {
    const handler = () => setDialog({ open: true, project: null });
    window.addEventListener("gate:new-project", handler);
    return () => window.removeEventListener("gate:new-project", handler);
  }, []);

  const detail = detailId ? projects.find((p) => p.id === detailId) : null;
  const git = detailId ? gitViews[detailId] : undefined;
  const tree = detailId ? treeViews[detailId] : undefined;

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

        {detail && (
          <div className="mt-4 card animate-rise overflow-hidden">
            <div className="flex items-center gap-2 px-4 h-11 border-b border-edge">
              <TreeStructure size={14} className="text-dim" />
              <span className="text-[13px] font-semibold">{detail.name}</span>
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
            </div>

            {!git && (
              <div className="p-8 text-center text-[12.5px] text-faint leading-relaxed">
                当前连接下后端未提供仓库读取接口，
                <br />
                分支图与文件树仅在演示模式中展示。
              </div>
            )}

            {git && tab === "graph" && <GraphTab git={git} />}

            {git && tab === "tree" && (
              <div className="p-2">
                {(tree ?? []).map((e) => (
                  <div key={e.path} className="flex items-center gap-3 px-3 h-9 rounded-lg hover:bg-raised/60 transition-colors">
                    {e.type === "dir" ? (
                      <FolderOpen size={14} className="text-info/70 shrink-0" />
                    ) : (
                      <TreeStructure size={14} className="text-faint shrink-0" />
                    )}
                    <span className="font-mono text-[12.5px] text-ink">{e.path}</span>
                    <span className="flex-1" />
                    <span className="hidden md:inline text-[11.5px] text-faint truncate max-w-[320px]">{e.lastMessage}</span>
                    <span className="font-mono text-[11px] text-faint w-[60px] text-right shrink-0">
                      {e.type === "file" ? `${((e.size ?? 0) / 1024).toFixed(1)} KB` : ""}
                    </span>
                    <span className="font-mono text-[11px] text-faint w-[58px] text-right shrink-0">{e.lastCommitShort}</span>
                  </div>
                ))}
              </div>
            )}
          </div>
        )}
      </div>

      {dialog.open && <ProjectDialog initial={dialog.project} onClose={() => setDialog({ open: false, project: null })} />}
    </div>
  );
}
