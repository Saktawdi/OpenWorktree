import { useEffect, useState } from "react";
import {
  CaretRight,
  FolderOpen,
  FolderPlus,
  GitBranch,
  TerminalWindow,
  PencilSimple,
  Play,
  Trash,
} from "@phosphor-icons/react";
import { actions } from "../lib/actions";
import { relativeTime } from "../lib/format";
import { setView, showToast, switchProject, useApp } from "../lib/store";
import type { Project } from "../lib/types";
import { LabelInput } from "./ui";
import { RepoViewDialog } from "./RepoView";
import { TerminalPickerDialog } from "./ProjectTerminal";

const SIZE_LABEL: Record<string, string> = { small: "小型", medium: "中型", large: "大型" };
// 项目标签偏向领域/类型划分，与工单标签（开发、BUG…）区分开
const PROJECT_COMMON_LABELS = ["电商", "内容", "文档", "数据", "AI", "工具", "前端", "后端"];

function ProjectDialog({
  initial,
  onClose,
}: {
  initial?: Project | null;
  onClose: () => void;
}) {
  const [name, setName] = useState(initial?.name ?? "");
  const [workspacePath, setWorkspacePath] = useState(initial?.workspacePath ?? "");
  const [targetBranch, setTargetBranch] = useState(
    initial?.targetRef ? initial.targetRef.replace("refs/heads/", "") : "",
  );
  const [initGit, setInitGit] = useState(!initial);
  const [priority, setPriority] = useState<string | null>(initial?.priority ?? null);
  const [size, setSize] = useState<string | null>(initial?.size ?? null);
  const [tags, setTags] = useState<string[]>(initial?.tags ?? []);
  const [saving, setSaving] = useState(false);

  const save = async () => {
    if (!name.trim() || (!initial && !workspacePath.trim())) return;
    setSaving(true);
    if (initial) {
      await actions.editProject(initial.id, {
        name: name.trim(),
        targetBranch: targetBranch.trim(),
        priority: priority as Project["priority"] | null,
        size: size as Project["size"] | null,
        tags,
      });
    } else {
      await actions.createProject({
        name: name.trim(),
        workspacePath: workspacePath.trim(),
        targetBranch: targetBranch.trim(),
        initGit,
        priority,
        size,
        tags,
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
                目录为空时自动执行 git init（使用下方主分支，默认 main）
              </label>
            </>
          )}
          <div>
            <label className="field-label">主分支</label>
            <input
              className="text-input font-mono text-[12px]"
              placeholder={initial ? initial.targetRef?.replace("refs/heads/", "") || "main" : "留空自动检测（空目录则 main）"}
              value={targetBranch}
              onChange={(e) => setTargetBranch(e.target.value)}
            />
            <div className="mt-1 text-[11px] text-faint">
              master / main 等单段分支名，基座同步与建单基线都用它；修改后对新开工单生效
            </div>
          </div>
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
            <label className="field-label">标签</label>
            <LabelInput labels={tags} onChange={setTags} suggestions={PROJECT_COMMON_LABELS} />
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

export function ProjectsPage() {
  const projects = useApp((s) => s.projects);
  const activeId = useApp((s) => s.activeProjectId);
  const tickets = useApp((s) => s.tickets);
  const mode = useApp((s) => s.mode);
  const [dialog, setDialog] = useState<{ open: boolean; project: Project | null }>({ open: false, project: null });
  const [detailId, setDetailId] = useState<string | null>(null);
  const [confirmDelete, setConfirmDelete] = useState<string | null>(null);
  const [terminalFor, setTerminalFor] = useState<Project | null>(null);

  useEffect(() => {
    const handler = () => setDialog({ open: true, project: null });
    window.addEventListener("gate:new-project", handler);
    return () => window.removeEventListener("gate:new-project", handler);
  }, []);

  const detail = detailId ? projects.find((p) => p.id === detailId) : null;

  const openTerminal = (p: Project) => {
    if (mode !== "live") {
      showToast("终端需要连接本地后端（live 模式）后使用");
      return;
    }
    setTerminalFor(p);
  };

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
                  <span className="chip border border-edge-strong bg-raised text-dim" title="项目主分支">
                    <GitBranch size={11} />
                    {p.targetRef.replace("refs/heads/", "")}
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
                    onClick={() => openTerminal(p)}
                    title="在项目克隆目录中打开终端"
                  >
                    <TerminalWindow size={12} />
                    终端
                  </button>
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
        {terminalFor && (
          <TerminalPickerDialog project={terminalFor} onClose={() => setTerminalFor(null)} />
        )}
      </div>

      {dialog.open && <ProjectDialog initial={dialog.project} onClose={() => setDialog({ open: false, project: null })} />}
    </div>
  );
}
