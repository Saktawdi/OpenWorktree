import { useEffect, useMemo, useState, type ReactNode } from "react";
import {
  CaretDown,
  CaretRight,
  DotsSixVertical,
  FolderOpen,
  FolderPlus,
  GitBranch,
  MagnifyingGlass,
  PencilSimple,
  Play,
  Star,
  TerminalWindow,
  Trash,
  X,
} from "@phosphor-icons/react";
import {
  DndContext,
  PointerSensor,
  closestCenter,
  useSensor,
  useSensors,
  type DragEndEvent,
} from "@dnd-kit/core";
import {
  SortableContext,
  arrayMove,
  rectSortingStrategy,
  useSortable,
} from "@dnd-kit/sortable";
import { CSS } from "@dnd-kit/utilities";
import { actions } from "@/app/actions";
import { relativeTime } from "@/shared/format";
import { setView, showToast, switchProject, useApp } from "@/store";
import type { Project } from "@/shared/types";
import { LabelInput, useBackdropClose } from "@/shared/components/ui";
import { RepoViewDialog } from "@/features/project/components/RepoView";
import { TerminalPickerDialog } from "@/features/project/components/ProjectTerminal";
import { WorkspaceBrowserDialog } from "@/features/project/components/WorkspaceBrowserDialog";
import { useT, type Translate } from "@/i18n";

// 项目标签偏向领域/类型划分，与工单标签（开发、BUG…）区分开；建议词走 i18n。
const PROJECT_COMMON_LABEL_KEYS = [
  "project.tag.ecommerce",
  "project.tag.content",
  "project.tag.docs",
  "project.tag.data",
  "project.tag.ai",
  "project.tag.tools",
  "project.tag.frontend",
  "project.tag.backend",
] as const;
function sizeLabel(sz: string, tr: Translate): string {
  return tr(`project.size.${sz}` as never) !== `project.size.${sz}` ? tr(`project.size.${sz}` as never) : sz;
}

type SizeFilter = "__all__" | "small" | "medium" | "large" | "__unset__";
type PriorityFilter = "__all__" | "P0" | "P1" | "P2" | "P3" | "__unset__";

/** 与后端 findAll 一致：星标置顶 → 手动拖拽顺序 → 名称。?? 0 兜底旧会话快照里缺字段的项目。 */
function projectCmp(a: Project, b: Project): number {
  if ((a.starred ? 1 : 0) !== (b.starred ? 1 : 0)) return a.starred ? -1 : 1;
  const oa = a.sortOrder ?? 0;
  const ob = b.sortOrder ?? 0;
  if (oa !== ob) return oa - ob;
  return a.name.localeCompare(b.name);
}

/** 取工作区路径末段作默认项目名：兼容 / 与 \ 分隔符并去掉结尾斜杠；盘符根（D:\）或裸根（/）没有可用目录名 */
function folderNameOf(path: string): string {
  const trimmed = path.trim().replace(/[\\/]+$/, "");
  if (!trimmed) return "";
  const seg = trimmed.split(/[\\/]/).pop() ?? "";
  return /^[A-Za-z]:$/.test(seg) ? "" : seg;
}

function ProjectDialog({
  initial,
  onClose,
}: {
  initial?: Project | null;
  onClose: () => void;
}) {
  const t = useT();
  const mode = useApp((s) => s.mode);
  const backdrop = useBackdropClose(onClose);
  const [name, setName] = useState(initial?.name ?? "");
  const [workspacePath, setWorkspacePath] = useState(initial?.workspacePath ?? "");
  // 上次按路径自动填充的项目名：名称留空或仍是这个值（用户未手改）时，换目录后继续跟随
  const [autoName, setAutoName] = useState("");
  const [targetBranch, setTargetBranch] = useState(
    initial?.targetRef ? initial.targetRef.replace("refs/heads/", "") : "",
  );
  const [initGit, setInitGit] = useState(!initial);
  const [priority, setPriority] = useState<string | null>(initial?.priority ?? null);
  const [size, setSize] = useState<string | null>(initial?.size ?? null);
  const [tags, setTags] = useState<string[]>(initial?.tags ?? []);
  const [saving, setSaving] = useState(false);
  const [browserOpen, setBrowserOpen] = useState(false);
  // 后端运行平台（linux/windows/mac）：路径提示按它适配——容器部署时后端是 Linux，
  // /home/… 风格路径可直接填，注册时自动创建；Windows 才引导盘符风格。
  const [backendPlatform, setBackendPlatform] = useState<string | null>(null);
  const [backendHome, setBackendHome] = useState("");

  useEffect(() => {
    if (initial || mode !== "live") return;
    void actions.browseWorkspace("").then((d) => {
      if (d) {
        setBackendPlatform(d.platform || null);
        setBackendHome(d.userHome || "");
      }
    });
    // 仅在弹窗打开时探测一次即可
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [initial, mode]);

  const pathHint = (() => {
    if (backendPlatform === "linux" || backendPlatform === "mac") {
      return {
        placeholder: `${backendHome || (backendPlatform === "mac" ? "/Users/you" : "/home/you")}/my-project`,
        hint: t("project.pathHint.unix", { platform: backendPlatform === "linux" ? "Linux" : "macOS" }),
      };
    }
    if (backendPlatform === "windows") {
      return {
        placeholder: "D:/work/my-project",
        hint: t("project.pathHint.windows"),
      };
    }
    return {
      placeholder: t("project.pathPlaceholderAny"),
      hint: t("project.pathHint.generic"),
    };
  })();

  // 路径变化（手动输入、粘贴或浏览选择）时同步刷新默认项目名
  const applyWorkspacePath = (path: string) => {
    setWorkspacePath(path);
    const derived = folderNameOf(path);
    if (!derived) return;
    const cur = name.trim();
    if (!cur || cur === autoName) setName(derived);
    setAutoName(derived);
  };

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

  const openBrowser = () => {
    if (mode !== "live") {
      showToast(t("project.browseNeedLive"));
      return;
    }
    setBrowserOpen(true);
  };

  return (
    <div className="fixed inset-0 z-50 grid place-items-center bg-black/55 backdrop-blur-[2px]" {...backdrop}>
      <div className="w-[460px] card shadow-2xl shadow-black/60 animate-rise" onClick={(e) => e.stopPropagation()}>
        <div className="flex items-center gap-2 px-5 h-12 border-b border-edge">
          <FolderPlus size={15} className="text-accent" />
          <span className="text-[13.5px] font-semibold">{initial ? t("project.edit") : t("project.connectNew")}</span>
        </div>
        <div className="p-5 space-y-4">
          <div>
            <label className="field-label">{t("project.nameLabel")}</label>
            <input
              autoFocus
              className="text-input"
              placeholder={t("project.namePlaceholder")}
              value={name}
              onChange={(e) => setName(e.target.value)}
            />
            {!initial && (
              <div className="mt-1 text-[11px] text-faint">{t("project.nameHint")}</div>
            )}
          </div>
          {!initial && (
            <>
              <div>
                <label className="field-label">{t("project.pathLabel")}</label>
                <div className="relative">
                  <input
                    className="text-input font-mono text-[12px] pr-10"
                    placeholder={pathHint.placeholder}
                    value={workspacePath}
                    onChange={(e) => applyWorkspacePath(e.target.value)}
                  />
                  <button
                    type="button"
                    className="absolute right-1.5 top-1/2 -translate-y-1/2 grid place-items-center w-7 h-7 rounded-md text-faint cursor-pointer hover:text-accent hover:bg-raised transition-colors"
                    title={t("project.browse")}
                    aria-label={t("project.browse")}
                    onClick={openBrowser}
                  >
                    <FolderOpen size={15} weight="regular" />
                  </button>
                </div>
                <div className="mt-1 text-[11px] text-faint">
                  {pathHint.hint}
                </div>
              </div>
              <label className="flex items-center gap-2 text-[12.5px] text-dim cursor-pointer select-none">
                <input
                  type="checkbox"
                  checked={initGit}
                  onChange={(e) => setInitGit(e.target.checked)}
                  className="accent-[#35d99e]"
                />
                {t("project.initGitLabel")}
              </label>
            </>
          )}
          <div>
            <label className="field-label">{t("project.mainBranchLabel")}</label>
            <input
              className="text-input font-mono text-[12px]"
              placeholder={initial ? initial.targetRef?.replace("refs/heads/", "") || "main" : t("project.mainBranchPlaceholder")}
              value={targetBranch}
              onChange={(e) => setTargetBranch(e.target.value)}
            />
            <div className="mt-1 text-[11px] text-faint">
              {t("project.mainBranchHint")}
            </div>
          </div>
          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="field-label">{t("ticket.new.priorityLabel")}</label>
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
                    {p === null ? t("common.none") : p}
                  </button>
                ))}
              </div>
            </div>
            <div>
              <label className="field-label">{t("project.sizeLabel")}</label>
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
                    {sz === null ? t("common.none") : sizeLabel(sz, t)}
                  </button>
                ))}
              </div>
            </div>
          </div>
          <div>
            <label className="field-label">{t("edit.labels")}</label>
            <LabelInput labels={tags} onChange={setTags} suggestions={PROJECT_COMMON_LABEL_KEYS.map((k) => t(k))} />
          </div>
        </div>
        <div className="flex justify-end gap-2 px-5 py-4 border-t border-edge">
          <button className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button
            className="btn btn-primary"
            disabled={!name.trim() || (!initial && !workspacePath.trim()) || saving}
            onClick={save}
          >
            {saving ? t("llm.saving") : initial ? t("llm.saveChanges") : t("topbar.project.connect")}
          </button>
        </div>
      </div>

      {browserOpen && (
        <WorkspaceBrowserDialog
          initialPath={workspacePath}
          onPick={(p) => {
            applyWorkspacePath(p);
            setBrowserOpen(false);
          }}
          onClose={() => setBrowserOpen(false)}
        />
      )}
    </div>
  );
}

function FilterSelect({
  label,
  value,
  onChange,
  options,
}: {
  label: string;
  value: string;
  onChange: (next: string) => void;
  options: { value: string; label: string }[];
}) {
  const active = value !== options[0].value;
  return (
    <div className="relative">
      <select
        aria-label={label}
        title={label}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        className={`appearance-none h-8 rounded-lg border bg-sunken pl-2.5 pr-7 text-[12.5px] cursor-pointer
          focus:outline-none transition-colors ${
            active
              ? "border-accent/50 text-accent"
              : "border-edge text-dim hover:border-edge-strong"
          }`}
      >
        {options.map((o) => (
          <option key={o.value} value={o.value} className="bg-panel text-ink">
            {o.label}
          </option>
        ))}
      </select>
      <CaretDown
        size={11}
        className={`absolute right-2 top-1/2 -translate-y-1/2 pointer-events-none ${active ? "text-accent" : "text-faint"}`}
      />
    </div>
  );
}

function SortableProjectCard({
  project,
  children,
}: {
  project: Project;
  children: (handle: ReactNode) => ReactNode;
}) {
  const { attributes, listeners, setNodeRef, setActivatorNodeRef, transform, transition, isDragging } =
    useSortable({ id: project.id });
  const t = useT();
  return (
    <div
      ref={setNodeRef}
      style={{ transform: CSS.Translate.toString(transform), transition }}
      className={isDragging ? "opacity-35 z-10" : ""}
    >
      {children(
        <button
          ref={setActivatorNodeRef}
          {...attributes}
          {...listeners}
          className="grid place-items-center w-5 h-6 -ml-1 rounded text-faint/60 cursor-grab active:cursor-grabbing hover:text-dim hover:bg-raised transition-colors touch-none"
          title={t("project.dragOrderTip")}
          aria-label={t("project.dragOrderAria", { name: project.name })}
        >
          <DotsSixVertical size={13} weight="bold" />
        </button>,
      )}
    </div>
  );
}

// 拖拽结束的瞬间，卡片上的 click 事件可能被连带触发——短暂屏蔽点击（同看板做法）
let dragEndedAt = 0;
function markDragEnded() {
  dragEndedAt = Date.now();
}
function dragJustEnded(): boolean {
  return Date.now() - dragEndedAt < 250;
}

export function ProjectsPage() {
  const t = useT();
  const projects = useApp((s) => s.projects);
  const activeId = useApp((s) => s.activeProjectId);
  const tickets = useApp((s) => s.tickets);
  const mode = useApp((s) => s.mode);
  const [dialog, setDialog] = useState<{ open: boolean; project: Project | null }>({ open: false, project: null });
  const [detailId, setDetailId] = useState<string | null>(null);
  const [confirmDelete, setConfirmDelete] = useState<string | null>(null);
  const [terminalFor, setTerminalFor] = useState<Project | null>(null);
  const [query, setQuery] = useState("");
  const [sizeFilter, setSizeFilter] = useState<SizeFilter>("__all__");
  const [priorityFilter, setPriorityFilter] = useState<PriorityFilter>("__all__");

  useEffect(() => {
    const handler = () => setDialog({ open: true, project: null });
    window.addEventListener("gate:new-project", handler);
    return () => window.removeEventListener("gate:new-project", handler);
  }, []);

  const sensors = useSensors(useSensor(PointerSensor, { activationConstraint: { distance: 6 } }));

  // 星标置顶 → 手动拖拽顺序 → 名称（与后端 findAll 的 ORDER BY 对齐）
  const ordered = useMemo(() => [...projects].sort(projectCmp), [projects]);

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    return ordered.filter((p) => {
      if (q) {
        const haystack = `${p.name}\n${p.workspacePath}\n${p.tags.join("\n")}`.toLowerCase();
        if (!haystack.includes(q)) return false;
      }
      if (sizeFilter === "__unset__" ? p.size !== null : sizeFilter !== "__all__" && p.size !== sizeFilter) {
        return false;
      }
      if (
        priorityFilter === "__unset__"
          ? p.priority !== null
          : priorityFilter !== "__all__" && p.priority !== priorityFilter
      ) {
        return false;
      }
      return true;
    });
  }, [ordered, query, sizeFilter, priorityFilter]);

  const hasFilters = query.trim() !== "" || sizeFilter !== "__all__" || priorityFilter !== "__all__";
  const clearFilters = () => {
    setQuery("");
    setSizeFilter("__all__");
    setPriorityFilter("__all__");
  };

  const detail = detailId ? projects.find((p) => p.id === detailId) : null;

  const openTerminal = (p: Project) => {
    if (mode !== "live") {
      showToast(t("wb.terminalNeedLive"));
      return;
    }
    setTerminalFor(p);
  };

  const toggleStar = (p: Project) => {
    if (dragJustEnded()) return;
    void actions.setProjectStarred(p.id, !p.starred);
  };

  const onDragEnd = (e: DragEndEvent) => {
    markDragEnded();
    const { active, over } = e;
    if (!over) return;
    const activeIdStr = String(active.id);
    const overIdStr = String(over.id);
    if (activeIdStr === overIdStr) return;
    const activeProject = projects.find((p) => p.id === activeIdStr);
    const overProject = projects.find((p) => p.id === overIdStr);
    if (!activeProject || !overProject) return;
    // 星标组固定置顶：不允许把未星标项目拖进星标区（反之亦然），否则顺序会被星标排序吞掉
    if (activeProject.starred !== overProject.starred) {
      showToast(
        activeProject.starred
          ? t("project.starBlock.unstar")
          : t("project.starBlock.star"),
      );
      return;
    }
    const visibleIds = filtered.map((p) => p.id);
    const oldIdx = visibleIds.indexOf(activeIdStr);
    const newIdx = visibleIds.indexOf(overIdStr);
    if (oldIdx < 0 || newIdx < 0 || oldIdx === newIdx) return;
    const nextVisible = arrayMove(visibleIds, oldIdx, newIdx);
    // 筛选状态下只重排可见项，其余项目保持原有相对顺序
    const visibleSet = new Set(visibleIds);
    let vi = 0;
    const nextFull = ordered.map((p) => (visibleSet.has(p.id) ? nextVisible[vi++] : p.id));
    void actions.reorderProjects(nextFull);
  };

  return (
    <div className="flex-1 min-h-0 overflow-y-auto scrollbar-none">
      <div className="max-w-[1080px] mx-auto px-6 py-5">
        <div className="flex flex-wrap items-center gap-2 pb-4">
          <span className="kicker">{t("project.kicker")}</span>
          <span className="font-mono text-[11px] text-faint">
            {hasFilters ? t("project.countFiltered", { shown: filtered.length, total: projects.length }) : t("project.count", { n: projects.length })}
          </span>
          <div className="relative ml-1">
            <MagnifyingGlass
              size={13}
              className="absolute left-2.5 top-1/2 -translate-y-1/2 text-faint pointer-events-none"
            />
            <input
              className="text-input h-8 w-48 pl-7 pr-7 text-[12.5px]"
              placeholder={t("project.search")}
              aria-label={t("project.searchAria")}
              value={query}
              onChange={(e) => setQuery(e.target.value)}
            />
            {query && (
              <button
                className="absolute right-1.5 top-1/2 -translate-y-1/2 grid place-items-center w-5 h-5 rounded text-faint hover:text-ink cursor-pointer"
                aria-label={t("kanban.clearSearch")}
                onClick={() => setQuery("")}
              >
                <X size={11} weight="bold" />
              </button>
            )}
          </div>
          <FilterSelect
            label={t("project.sizeFilter")}
            value={sizeFilter}
            onChange={(v) => setSizeFilter(v as SizeFilter)}
            options={[
              { value: "__all__", label: t("project.sizeAll") },
              { value: "small", label: t("project.size.small") },
              { value: "medium", label: t("project.size.medium") },
              { value: "large", label: t("project.size.large") },
              { value: "__unset__", label: t("project.unset") },
            ]}
          />
          <FilterSelect
            label={t("project.priorityFilter")}
            value={priorityFilter}
            onChange={(v) => setPriorityFilter(v as PriorityFilter)}
            options={[
              { value: "__all__", label: t("project.priorityAll") },
              { value: "P0", label: "P0" },
              { value: "P1", label: "P1" },
              { value: "P2", label: "P2" },
              { value: "P3", label: "P3" },
              { value: "__unset__", label: t("project.unset") },
            ]}
          />
          <span className="flex-1" />
          <button className="btn btn-primary h-8" onClick={() => setDialog({ open: true, project: null })}>
            <FolderPlus size={14} weight="fill" />
            {t("topbar.project.connectNew")}
          </button>
        </div>

        <DndContext sensors={sensors} collisionDetection={closestCenter} onDragEnd={onDragEnd}>
          <SortableContext items={filtered.map((p) => p.id)} strategy={rectSortingStrategy}>
            <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
              {filtered.map((p) => {
                const isActive = p.id === activeId;
                const pTickets = tickets.filter((t) => t.projectId === p.id);
                const activeCount = pTickets.filter((t) => t.stage !== "DONE" && t.stage !== "CANCELLED").length;
                return (
                  <SortableProjectCard key={p.id} project={p}>
                    {(handle) => (
                      <div
                        className={`card p-4 transition-colors ${isActive ? "border-accent/35" : "hover:border-edge-strong"}`}
                      >
                        <div className="flex items-center gap-2">
                          {handle}
                          <FolderOpen size={15} className={isActive ? "text-accent" : "text-faint"} />
                          <span className="text-[13.5px] font-semibold truncate">{p.name}</span>
                          {isActive && <span className="chip border border-accent/30 bg-accent/10 text-accent">{t("sess.current")}</span>}
                          <span className="flex-1" />
                          <button
                            className={`icon-btn ${p.starred ? "!text-warn" : ""}`}
                            title={p.starred ? t("sess.unpin") : t("project.star")}
                            aria-label={p.starred ? t("project.unstarAria", { name: p.name }) : t("project.starAria", { name: p.name })}
                            onClick={() => toggleStar(p)}
                          >
                            <Star size={13} weight={p.starred ? "fill" : "regular"} />
                          </button>
                          <button
                            className="icon-btn"
                            title={t("project.edit")}
                            aria-label={t("project.edit")}
                            onClick={() => {
                              if (dragJustEnded()) return;
                              setDialog({ open: true, project: p });
                            }}
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
                                {t("llm.confirmDelete")}
                              </button>
                              <button className="chip border border-edge-strong text-dim cursor-pointer" onClick={() => setConfirmDelete(null)}>
                                {t("llm.back")}
                              </button>
                            </span>
                          ) : (
                            <button
                              className="icon-btn hover:!text-danger"
                              title={t("project.remove")}
                              aria-label={t("project.remove")}
                              onClick={() => {
                                if (dragJustEnded()) return;
                                setConfirmDelete(p.id);
                              }}
                            >
                              <Trash size={13} />
                            </button>
                          )}
                        </div>

                        <div className="mt-1.5 font-mono text-[11px] text-faint truncate" title={p.workspacePath}>
                          {p.workspacePath}
                        </div>

                        <div className="mt-2.5 flex flex-wrap items-center gap-1.5">
                          <span className="chip border border-edge-strong bg-raised text-dim" title={t("wb.projectBranch")}>
                            <GitBranch size={11} />
                            {p.targetRef.replace("refs/heads/", "")}
                          </span>
                          {p.size && <span className="chip border border-info/25 bg-info/10 text-info">{sizeLabel(p.size, t)}</span>}
                          {p.priority && <span className="chip border border-warn/30 bg-warn/10 text-warn font-mono">{p.priority}</span>}
                          {p.tags.map((t) => (
                            <span key={t} className="chip border border-edge-strong bg-raised text-faint">
                              {t}
                            </span>
                          ))}
                        </div>

                        <div className="mt-3 pt-3 border-t border-edge flex items-center gap-3 text-[11.5px] text-faint">
                          <span>
                            {t("ticket.list.title")} <span className="font-mono text-dim">{pTickets.length}</span>
                          </span>
                          <span>
                            {t("sess.tab.active")} <span className="font-mono text-dim">{activeCount}</span>
                          </span>
                          <span>{t("project.updatedAt", { time: relativeTime(p.updatedAt) })}</span>
                          <span className="flex-1" />
                          <button
                            className="inline-flex items-center gap-1 text-[11.5px] text-dim hover:text-ink cursor-pointer bg-transparent border-0 p-0"
                            onClick={() => openTerminal(p)}
                            title={t("wb.openTerminal")}
                          >
                            <TerminalWindow size={12} />
                            {t("project.terminal")}
                          </button>
                          <button
                            className="inline-flex items-center gap-1 text-[11.5px] text-dim hover:text-ink cursor-pointer bg-transparent border-0 p-0"
                            onClick={() => setDetailId(detailId === p.id ? null : p.id)}
                          >
                            {t("project.repoView")}
                            <CaretRight size={11} className={`transition-transform ${detailId === p.id ? "rotate-90" : ""}`} />
                          </button>
                          <button
                            className="inline-flex items-center gap-1 text-[11.5px] text-accent hover:text-accent-hi cursor-pointer bg-transparent border-0 p-0"
                            onClick={() => {
                              switchProject(p.id);
                              setView("workbench");
                              // V19 快速模式：直达项目的超级工单——原工作区直连 opencode，
                              // 提交直达主分支；无超级工单时退回普通工单列表。
                              if (p.superTicketNo) {
                                actions.openTicket(p.superTicketNo);
                              }
                            }}
                            title={t("project.openWorkbenchTip")}
                          >
                            <Play size={11} weight="fill" />
                            {t("project.openWorkbench")}
                          </button>
                        </div>
                      </div>
                    )}
                  </SortableProjectCard>
                );
              })}
              {projects.length === 0 && (
                <div className="col-span-full card border-dashed p-10 text-center">
                  <div className="text-[13.5px] text-dim">{t("project.empty")}</div>
                  <div className="mt-1 text-[12px] text-faint">{t("project.emptyHint")}</div>
                </div>
              )}
              {projects.length > 0 && filtered.length === 0 && (
                <div className="col-span-full card border-dashed p-10 text-center">
                  <div className="text-[13.5px] text-dim">{t("project.noMatch")}</div>
                  <button className="mt-2 btn btn-sm mx-auto" onClick={clearFilters}>
                    <X size={12} />
                    {t("kanban.clearFilters")}
                  </button>
                </div>
              )}
            </div>
          </SortableContext>
        </DndContext>

        {detail && <RepoViewDialog project={detail} onClose={() => setDetailId(null)} />}
        {terminalFor && (
          <TerminalPickerDialog project={terminalFor} onClose={() => setTerminalFor(null)} />
        )}
      </div>

      {dialog.open && <ProjectDialog initial={dialog.project} onClose={() => setDialog({ open: false, project: null })} />}
    </div>
  );
}
