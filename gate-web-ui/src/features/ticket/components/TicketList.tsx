import { useMemo, useRef, useState } from "react";
import { motion } from "motion/react";
import {
  Check,
  CheckCircle,
  Funnel,
  MagnifyingGlass,
  Plus,
  Question,
  ShieldWarning,
  WarningCircle,
} from "@phosphor-icons/react";
import { actions } from "@/app/actions";
import { ALL_STAGES,
  PRIORITY_RANK,
  relativeTime,
  stageLabel,
  STAGE_SORT_RANK, } from "@/shared/format";
import { closeTicketCreator, openTicketCreator, setVisibleStages } from "@/features/ticket";
import { useApp, NO_DIFF } from "@/store";
import { useT } from "@/i18n";
import type { Priority, Stage } from "@/shared/types";
import { LabelInput, PriorityChip, StageDot, useBackdropClose } from "@/shared/components/ui";

const PRIORITIES: Priority[] = ["P0", "P1", "P2", "P3"];

function NewTicketButton() {
  const t = useT();
  const open = useApp((s) => s.ticketCreatorOpen);
  const [title, setTitle] = useState("");
  const [priority, setPriority] = useState<Priority>("P1");
  const [description, setDescription] = useState("");
  const [labels, setLabels] = useState<string[]>([]);
  const [targetBranch, setTargetBranch] = useState("");
  const backdrop = useBackdropClose(() => closeTicketCreator());

  const submit = () => {
    if (!title.trim()) return;
    // 协作 Agent 不再绑定工单：会话创建时独立选择（见 GatePanel 新建会话草稿态）。
    actions.newTicket(title.trim(), priority, {
      description: description.trim() || undefined,
      labels,
      targetBranch: targetBranch.trim() || undefined,
    });
    setTitle("");
    setDescription("");
    setLabels([]);
    setTargetBranch("");
    closeTicketCreator();
  };

  return (
    <>
      <button className="btn h-7 px-2.5 text-[12px]" onClick={() => (open ? closeTicketCreator() : openTicketCreator())}>
        <Plus size={13} weight="bold" />
        {t("ticket.list.new")}
      </button>
      {open && (
        <div
          className="fixed inset-0 z-50 grid place-items-center bg-black/55 backdrop-blur-[2px]"
          {...backdrop}
        >
          <div
            className="w-[420px] card shadow-2xl shadow-black/60 animate-rise"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="flex items-center gap-2.5 px-5 h-12 border-b border-edge">
              <span className="text-[13px] font-semibold">{t("ticket.new.title")}</span>
              <span className="flex-1" />
              <button className="icon-btn" onClick={() => closeTicketCreator()} aria-label={t("common.close")}>
                ✕
              </button>
            </div>

            <div className="p-5 space-y-4 max-h-[70vh] overflow-y-auto">
              <div>
                <label className="field-label">{t("ticket.new.titleLabel")}</label>
                <input
                  autoFocus
                  className="text-input"
                  placeholder={t("ticket.new.titlePlaceholder")}
                  value={title}
                  onChange={(e) => setTitle(e.target.value)}
                  onKeyDown={(e) => e.key === "Enter" && submit()}
                />
              </div>

              <div>
                <label className="field-label">{t("ticket.new.priorityLabel")}</label>
                <div className="flex gap-1">
                  {PRIORITIES.map((p) => (
                    <button
                      key={p}
                      onClick={() => setPriority(p)}
                      className={`flex-1 h-8 rounded-lg border font-mono text-[12px] cursor-pointer transition-colors ${
                        priority === p
                          ? "border-accent/50 bg-accent/10 text-accent"
                          : "border-edge text-dim hover:text-ink hover:bg-raised"
                      }`}
                    >
                      {p}
                    </button>
                  ))}
                </div>
              </div>

              <div>
                <label className="field-label">{t("ticket.new.branchLabel")}</label>
                <input
                  className="text-input font-mono"
                  placeholder={t("ticket.new.branchPlaceholder")}
                  value={targetBranch}
                  onChange={(e) => setTargetBranch(e.target.value)}
                />
                <div className="mt-1 text-[10.5px] text-faint">
                  {t("ticket.new.branchHint")}
                </div>
              </div>

              <div>
                <label className="field-label">{t("ticket.new.descLabel")}</label>
                <textarea
                  className="text-input h-40 py-2 resize-none"
                  placeholder={t("ticket.new.descPlaceholder")}
                  value={description}
                  onChange={(e) => setDescription(e.target.value)}
                />
              </div>

              <div>
                <label className="field-label">{t("ticket.new.labelsLabel")}</label>
                <LabelInput labels={labels} onChange={setLabels} />
              </div>
            </div>

            <div className="flex justify-end gap-2 px-5 py-4 border-t border-edge">
              <button className="btn" onClick={() => closeTicketCreator()}>
                {t("common.cancel")}
              </button>
              <button className="btn btn-primary" disabled={!title.trim()} onClick={submit}>
                {t("ticket.new.submit")}
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  );
}

/** 状态筛选：勾选要显示的状态（持久化），带各状态工单数与重置。 */
function StageFilterButton() {
  const t = useT();
  const [open, setOpen] = useState(false);
  // 面板用 fixed 定位并夹紧到视口内：侧栏仅 268px 宽，absolute right-0 会把面板左缘推出窗口被裁剪
  const [panelPos, setPanelPos] = useState<{ top: number; left: number }>({ top: 0, left: 8 });
  const btnRef = useRef<HTMLButtonElement>(null);
  const visible = useApp((s) => s.visibleStages);
  const ticketsAll = useApp((s) => s.tickets);
  const activeProjectId = useApp((s) => s.activeProjectId);

  const counts = useMemo(() => {
    const m = new Map<Stage, number>();
    for (const t of ticketsAll) {
      if (t.projectId === activeProjectId || t.projectId === "") {
        m.set(t.stage, (m.get(t.stage) ?? 0) + 1);
      }
    }
    return m;
  }, [ticketsAll, activeProjectId]);

  const filtered = visible.length !== ALL_STAGES.length;

  const toggle = (st: Stage) => {
    setVisibleStages(
      visible.includes(st) ? visible.filter((x) => x !== st) : [...visible, st],
    );
  };

  const PANEL_WIDTH = 224;
  const placePanel = () => {
    const r = btnRef.current?.getBoundingClientRect();
    if (!r) return;
    const left = Math.max(
      8,
      Math.min(r.right - PANEL_WIDTH, window.innerWidth - PANEL_WIDTH - 8),
    );
    setPanelPos({ top: r.bottom + 6, left });
  };

  return (
    <>
      <button
        ref={btnRef}
        className={`btn h-7 px-2 text-[12px] ${filtered ? "!border-accent/50 !text-accent" : ""}`}
        title={t("ticket.list.filterByStage")}
        aria-label={t("ticket.list.filterByStage")}
        onClick={() => {
          if (!open) placePanel();
          setOpen(!open);
        }}
      >
        <Funnel size={13} weight={filtered ? "fill" : "regular"} />
        {t("ticket.list.filter")}
      </button>
      {open && (
        <>
          <div className="fixed inset-0 z-30" onClick={() => setOpen(false)} />
          <div
            className="fixed z-40 card p-1.5 shadow-2xl shadow-black/50 animate-rise"
            style={{ top: panelPos.top, left: panelPos.left, width: PANEL_WIDTH }}
          >
            <div className="flex items-center gap-2 px-2 h-8">
              <span className="text-[11px] font-medium text-dim flex-1">{t("ticket.list.filterTitle")}</span>
              <button
                className="text-[11px] text-faint hover:text-accent cursor-pointer transition-colors"
                onClick={() => setVisibleStages([...ALL_STAGES])}
              >
                {t("ticket.list.filterReset")}
              </button>
            </div>
            <div className="max-h-[320px] overflow-y-auto">
              {ALL_STAGES.map((st) => {
                const on = visible.includes(st);
                const n = counts.get(st) ?? 0;
                return (
                  <button
                    key={st}
                    className={`w-full flex items-center gap-2 px-2 h-8 rounded-lg text-left text-[12.5px] cursor-pointer transition-colors ${
                      on ? "text-ink hover:bg-raised" : "text-faint hover:bg-raised"
                    }`}
                    onClick={() => toggle(st)}
                  >
                    <span
                      className={`grid place-items-center w-[14px] h-[14px] rounded border transition-colors shrink-0 ${
                        on ? "bg-accent/15 border-accent/60 text-accent" : "border-edge-strong text-transparent"
                      }`}
                    >
                      <Check size={10} weight="bold" />
                    </span>
                    <StageDot stage={st} />
                    <span className="flex-1 truncate">{stageLabel(st, t)}</span>
                    <span className="font-mono text-[10.5px] text-faint">{n}</span>
                  </button>
                );
              })}
            </div>
          </div>
        </>
      )}
    </>
  );
}

/** T-120：工单列表运行徽标 —— 会话运行中（均衡器）与 AI 审查运行中（旋转环）动效不同。 */
function RunBadge({ kind, count }: { kind: "agent" | "review"; count?: number }) {
  const t = useT();
  if (kind === "review") {
    return (
      <span className="run-badge run-badge-review" title={t("ticket.badge.reviewRunningTip")}>
        <span className="review-spin" aria-hidden />
        {t("ticket.badge.reviewRunning")}
      </span>
    );
  }
  return (
    <span className="run-badge run-badge-agent" title={t("ticket.badge.sessionRunningTip")}>
      <span className="eq-bars" aria-hidden>
        <i />
        <i />
        <i />
      </span>
      {count !== undefined ? t("ticket.badge.runningCount", { count }) : t("ticket.badge.running")}
    </span>
  );
}

/** T-120 增强：待决询问徽标 —— 智能体的 question/permission 正等待用户处理。 */
function AskBadge({ kind, count }: { kind: "question" | "permission"; count: number }) {
  const t = useT();
  const isQuestion = kind === "question";
  return (
    <span
      className="run-badge run-badge-ask"
      title={isQuestion ? t("ticket.badge.waitAnswerTip") : t("ticket.badge.waitPermitTip")}
    >
      {isQuestion ? <Question size={9} weight="bold" /> : <ShieldWarning size={9} weight="bold" />}
      {isQuestion ? t("ticket.badge.waitAnswer") : t("ticket.badge.waitPermit")}
      {count > 1 ? ` ×${count}` : ""}
    </span>
  );
}

/** T-120 增强：会话结束提醒徽标 —— 正常结束（绿）与出错/中止（红）。 */
function EndBadge({ kind }: { kind: "done" | "failed" }) {
  const t = useT();
  if (kind === "failed") {
    return (
      <span className="run-badge run-badge-end-failed" title={t("ticket.badge.interruptedTip")}>
        <WarningCircle size={9} weight="bold" />
        {t("ticket.badge.interrupted")}
      </span>
    );
  }
  return (
    <span className="run-badge run-badge-end-done" title={t("ticket.badge.endedTip")}>
      <CheckCircle size={9} weight="bold" />
      {t("ticket.badge.ended")}
    </span>
  );
}

/** 审查结果徽标 —— 判决落盘后的列表位提醒：通过/需人工为绿「已审查」，驳回为红「驳回」。 */
function ReviewBadge({ verdict }: { verdict: "PASS" | "REJECT" | "REQUIRES_HUMAN" }) {
  const t = useT();
  if (verdict === "REJECT") {
    return (
      <span className="run-badge run-badge-end-failed" title={t("ticket.badge.rejectedTip")}>
        <WarningCircle size={9} weight="bold" />
        {t("ticket.badge.rejected")}
      </span>
    );
  }
  return (
    <span
      className="run-badge run-badge-end-done"
      title={verdict === "PASS" ? t("ticket.badge.reviewedPassTip") : t("ticket.badge.reviewedHumanTip")}
    >
      <CheckCircle size={9} weight="bold" />
      {t("ticket.badge.reviewed")}
    </span>
  );
}

export function TicketList() {
  const tr = useT();
  const ticketsAll = useApp((s) => s.tickets);
  const activeProjectId = useApp((s) => s.activeProjectId);
  const selectedNo = useApp((s) => s.selectedNo);
  const diffs = useApp((s) => s.diffs);
  const busyMap = useApp((s) => s.busy);
  const sessionBusy = useApp((s) => s.sessionBusy);
  const sessions = useApp((s) => s.sessions);
  const gateBusyMap = useApp((s) => s.gateBusy);
  const sessionEnded = useApp((s) => s.sessionEnded);
  const reviewEnded = useApp((s) => s.reviewEnded);
  const pendingPermissions = useApp((s) => s.pendingPermissions);
  const pendingQuestions = useApp((s) => s.pendingQuestions);
  const orderMap = useApp((s) => s.order);
  const visibleStages = useApp((s) => s.visibleStages);
  const [query, setQuery] = useState("");

  // T-120 增强：每工单的待决询问/权限数量（徽标数据源）
  const pendingAsks = useMemo(() => {
    const m = new Map<string, { questions: number; permissions: number }>();
    const bump = (no: string, key: "questions" | "permissions") => {
      const cur = m.get(no) ?? { questions: 0, permissions: 0 };
      cur[key] += 1;
      m.set(no, cur);
    };
    for (const { ticketNo } of Object.values(pendingPermissions)) bump(ticketNo, "permissions");
    for (const { ticketNo } of Object.values(pendingQuestions)) bump(ticketNo, "questions");
    return m;
  }, [pendingPermissions, pendingQuestions]);

  const tickets = useMemo(
    () =>
      ticketsAll
        // Unassigned tickets stay visible under any project context; otherwise a ticket
        // with no project would disappear from every list/board. 快速模式超级工单（V19）
        // 只挂在所属项目下展示（项目删除后成为无主遗留，不再示人）。
        .filter(
          (t) =>
            (t.isSuper
              ? t.projectId === activeProjectId
              : t.projectId === activeProjectId || t.projectId === "") &&
            visibleStages.includes(t.stage),
        )
        .sort((a, b) => {
          // 超级工单恒置顶（快速模式常驻入口）；其余：越接近发布的活跃工单越靠上，
          // 待处理随后，终态沉底（已取消最末）；同状态内按优先级 P0→P3，再保持手动拖拽顺序。
          if (a.isSuper !== b.isSuper) return a.isSuper ? -1 : 1;
          const lane = STAGE_SORT_RANK[a.stage] - STAGE_SORT_RANK[b.stage];
          if (lane !== 0) return lane;
          const prio = PRIORITY_RANK[a.priority] - PRIORITY_RANK[b.priority];
          if (prio !== 0) return prio;
          return (orderMap[a.ticketNo] ?? 0) - (orderMap[b.ticketNo] ?? 0);
        }),
    [ticketsAll, activeProjectId, orderMap, visibleStages],
  );

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return tickets;
    return tickets.filter(
      (t) => t.title.toLowerCase().includes(q) || t.ticketNo.toLowerCase().includes(q),
    );
  }, [tickets, query]);

  return (
    <aside className="w-[268px] shrink-0 border-r border-edge flex flex-col bg-canvas">
      <div className="flex items-center justify-between px-3 pt-3 pb-2">
        <span className="kicker px-1">{tr("ticket.list.title")}</span>
        <div className="flex items-center gap-1.5">
          <StageFilterButton />
          <NewTicketButton />
        </div>
      </div>
      <div className="px-3 pb-2">
        <div className="relative">
          <MagnifyingGlass
            size={14}
            className="absolute left-2.5 top-1/2 -translate-y-1/2 text-faint"
          />
          <input
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder={tr("ticket.list.search")}
            className="w-full h-8 rounded-lg border border-edge bg-sunken pl-8 pr-2 text-[12.5px] placeholder:text-faint focus:border-accent/50 focus:outline-none transition-colors"
          />
        </div>
      </div>

      <div className="flex-1 overflow-y-auto px-2 pb-3 space-y-0.5">
        {filtered.map((t) => {
          const active = t.ticketNo === selectedNo;
          const hasDiff = (diffs[t.ticketNo] ?? NO_DIFF).length > 0;
          // T-120：会话运行中（任一会话在跑）与 AI 审查运行中分别以不同动效呈现
          const runningSessions = (sessions[t.ticketNo] ?? []).filter((sess) => sessionBusy[sess.id] === true);
          const sessionRunningCount = runningSessions.length > 0 ? runningSessions.length : (busyMap[t.ticketNo] ? 1 : 0);
          const sessionRunning = sessionRunningCount > 0;
          const reviewRunning = gateBusyMap[t.ticketNo] ?? false;
          const running = sessionRunning || reviewRunning;
          // T-120 增强：待决询问（question/permission）、会话结束与审查结果提醒
          const asks = pendingAsks.get(t.ticketNo);
          const ended = !running ? sessionEnded[t.ticketNo]?.kind : undefined;
          const reviewed = !running ? reviewEnded[t.ticketNo]?.verdict : undefined;
          return (
            <motion.button
              key={t.ticketNo}
              onClick={() => actions.openTicket(t.ticketNo)}
              className={`relative w-full text-left rounded-lg px-3 py-2.5 transition-colors cursor-pointer group ${
                active ? "bg-raised" : "hover:bg-panel"
              } ${asks ? "ticket-item-ask" : ""}`}
              whileHover={!active ? { x: 2 } : undefined}
              transition={{ type: "spring", stiffness: 400, damping: 25 }}
            >
              {active && (
                <span className="absolute left-0 top-2 bottom-2 w-[2px] rounded-full bg-accent" />
              )}
              {sessionRunning && (
                <span
                  aria-hidden
                  className="ticket-run-bar absolute left-0 top-2 bottom-2 w-[2px] rounded-full bg-accent"
                />
              )}
              {reviewRunning && (
                <span
                  aria-hidden
                  className="ticket-item-scan pointer-events-none absolute inset-0 rounded-lg overflow-hidden"
                />
              )}
              <div className="flex items-center gap-2">
                <span className={`font-mono text-[11.5px] ${active ? "text-accent" : "text-faint"}`}>
                  {t.ticketNo}
                </span>
                <span className="flex-1" />
                <PriorityChip priority={t.priority} muted={t.stage === "CANCELLED"} />
              </div>
              <div className={`mt-0.5 text-[13px] leading-snug line-clamp-2 ${active ? "text-ink" : "text-dim group-hover:text-ink"}`}>
                {t.title}
              </div>
              <div className="mt-1.5 flex flex-wrap items-center gap-x-1.5 gap-y-0.5 text-[11px] text-faint">
                <StageDot stage={t.stage} />
                <span className="shrink-0">{stageLabel(t.stage, tr)}</span>
                {t.isSuper && (
                  <span
                    className="chip border border-violet/30 bg-violet/10 text-violet"
                    title={tr("ticket.list.quickModeTip")}
                  >
                    {tr("ticket.list.quickMode")}
                  </span>
                )}
                {hasDiff && !running && !ended && !reviewed && !asks && (
                  <>
                    <span className="text-edge-strong">·</span>
                    <span>{tr("ticket.list.hasChanges")}</span>
                  </>
                )}
                {asks && (
                  <>
                    {asks.questions > 0 && <AskBadge kind="question" count={asks.questions} />}
                    {asks.permissions > 0 && <AskBadge kind="permission" count={asks.permissions} />}
                  </>
                )}
                {running && (
                  <span className="flex items-center gap-1.5">
                    {sessionRunning && <RunBadge kind="agent" count={sessionRunningCount} />}
                    {reviewRunning && <RunBadge kind="review" />}
                  </span>
                )}
                {ended && <EndBadge kind={ended} />}
                {reviewed && <ReviewBadge verdict={reviewed} />}
                <span className="ml-auto shrink-0">{relativeTime(t.updatedAt)}</span>
              </div>
            </motion.button>
          );
        })}
        {filtered.length === 0 && (
          <div className="mt-10 text-center text-[12.5px] text-faint">{tr("ticket.list.noMatch")}</div>
        )}
      </div>
    </aside>
  );
}
