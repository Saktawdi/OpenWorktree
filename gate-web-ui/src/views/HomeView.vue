<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { getSystemConfig, type SystemConfigView } from '@/api/config';
import { getStatus, type StatusResult } from '@/api/status';
import { listTickets } from '@/api/tickets';
import { GBadge, GButton, GIcon, GInput } from '@/components/ui';
import { TICKET_STAGE_LABELS } from '@/types/stage';
import type { Ticket } from '@/types/ticket';
import type { TicketStage } from '@/types/stage';

type BoardView = 'list' | 'priority' | 'size';
type BadgeTone = 'neutral' | 'accent' | 'success' | 'warning' | 'danger';
type SizeBucket = 'small' | 'medium' | 'large';

interface ProjectRecord {
  id: string;
  name: string;
  repository: string;
  cloneRoot: string;
  targetRef: string;
  ticketCount: number;
  activeCount: number;
  reviewCount: number;
  readyCount: number;
  doneCount: number;
  commitCount: number;
  authTip: string;
  highestPriority: Ticket['priority'] | null;
  sizeBucket: SizeBucket;
  lastUpdated: string;
}

interface ProjectGroup {
  key: string;
  label: string;
  hint: string;
  projects: ProjectRecord[];
}

const route = useRoute();
const router = useRouter();

const tickets = ref<Ticket[]>([]);
const config = ref<SystemConfigView | null>(null);
const status = ref<StatusResult | null>(null);
const query = ref('');
const loading = ref(true);
const refreshing = ref(false);
const errorMessage = ref('');

function parseView(value: unknown): BoardView {
  return value === 'priority' || value === 'size' ? value : 'list';
}

const boardView = ref<BoardView>(parseView(route.query.view));

watch(
  () => route.query.view,
  (value) => {
    const next = parseView(value);
    if (next !== boardView.value) boardView.value = next;
  },
);

const statusByNo = computed(() => {
  const map = new Map<string, StatusResult['tickets'][number]>();
  for (const item of status.value?.tickets ?? []) map.set(item.ticketNo, item);
  return map;
});

const boardTickets = computed(() =>
  tickets.value.map((ticket) => {
    const snapshot = statusByNo.value.get(ticket.no);
    if (!snapshot) return ticket;
    return {
      ...ticket,
      stage: snapshot.stage || ticket.stage,
      reviewRound: ticket.reviewRound ?? snapshot.latestRound,
      treeHash: ticket.treeHash ?? snapshot.latestTreeHash,
    };
  }),
);

const normalizedQuery = computed(() => query.value.trim().toLowerCase());
const filteredTickets = computed(() => {
  if (!normalizedQuery.value) return boardTickets.value;
  return boardTickets.value.filter((ticket) =>
    [ticket.no, ticket.title, ticket.targetRef, ticket.clonePath]
      .filter(Boolean)
      .join(' ')
      .toLowerCase()
      .includes(normalizedQuery.value),
  );
});

const project = computed<ProjectRecord | null>(() => {
  const projectConfig = config.value;
  if (!projectConfig && !status.value && tickets.value.length === 0) return null;

  if (normalizedQuery.value) {
    const projectHaystack = [
      projectConfig?.project,
      projectConfig?.auth_repo,
      projectConfig?.clones_root,
      status.value?.targetRef,
    ]
      .filter(Boolean)
      .join(' ')
      .toLowerCase();
    const ticketMatches = boardTickets.value.some((ticket) =>
      [ticket.no, ticket.title, ticket.targetRef, ticket.clonePath]
        .filter(Boolean)
        .join(' ')
        .toLowerCase()
        .includes(normalizedQuery.value),
    );
    if (!projectHaystack.includes(normalizedQuery.value) && !ticketMatches) return null;
  }

  const rows = filteredTickets.value;
  const active = rows.filter((ticket) => !['DONE', 'CANCELLED'].includes(ticket.stage));
  const review = rows.filter((ticket) => ['PRESUBMITTED', 'IN_REVIEW', 'READY_TO_PUBLISH'].includes(ticket.stage));
  const priorities = active
    .map((ticket) => ticket.priority)
    .filter((priority): priority is NonNullable<Ticket['priority']> => Boolean(priority));
  const highestPriority = priorities.sort((left, right) => priorityRank(left) - priorityRank(right))[0] ?? null;
  const commitCount = status.value?.authCommitCount ?? 0;
  const sizeBucket: SizeBucket = commitCount >= 100 || rows.length >= 50 ? 'large' : commitCount >= 20 || rows.length >= 15 ? 'medium' : 'small';
  const lastUpdated = rows
    .map((ticket) => ticket.updatedAt)
    .filter(Boolean)
    .sort((left, right) => Date.parse(right) - Date.parse(left))[0] ?? '';

  return {
    id: 'gate',
    name: projectConfig?.project || 'GATE',
    repository: projectConfig?.auth_repo || '未读取权威仓库',
    cloneRoot: projectConfig?.clones_root || '未读取 Clone 根目录',
    targetRef: status.value?.targetRef || projectConfig?.target_ref_whitelist?.[0] || '未读取目标引用',
    ticketCount: rows.length,
    activeCount: active.length,
    reviewCount: review.length,
    readyCount: rows.filter((ticket) => ticket.stage === 'READY_TO_PUBLISH').length,
    doneCount: rows.filter((ticket) => ticket.stage === 'DONE').length,
    commitCount,
    authTip: status.value?.authTip || '',
    highestPriority,
    sizeBucket,
    lastUpdated,
  };
});

const hasPriorityData = computed(() => boardTickets.value.some((ticket) => Boolean(ticket.priority)));
const dataSourceNote = computed(() => {
  if (!hasPriorityData.value) return '后端当前未提供工单优先级，项目暂归入“未设置”。';
  return '优先级按项目内未完成工单的最高优先级聚合。';
});

const priorityGroups = computed<ProjectGroup[]>(() => {
  if (!project.value) return [];
  const groups: ProjectGroup[] = [
    { key: 'P0', label: 'P0 紧急', hint: '有最高优先级未完成工单', projects: [] },
    { key: 'P1', label: 'P1 高', hint: '需要优先排期', projects: [] },
    { key: 'P2', label: 'P2 普通', hint: '按常规节奏推进', projects: [] },
    { key: 'P3', label: 'P3 低', hint: '可以延后处理', projects: [] },
    { key: 'unset', label: '未设置优先级', hint: '后端尚未提供优先级字段', projects: [] },
  ];
  const key = project.value.highestPriority ?? 'unset';
  groups.find((group) => group.key === key)?.projects.push(project.value);
  return groups.filter((group) => group.projects.length > 0);
});

const sizeGroups = computed<ProjectGroup[]>(() => {
  if (!project.value) return [];
  const groups: ProjectGroup[] = [
    { key: 'small', label: '小型项目', hint: '按提交数与工单数估算', projects: [] },
    { key: 'medium', label: '中型项目', hint: '按提交数与工单数估算', projects: [] },
    { key: 'large', label: '大型项目', hint: '按提交数与工单数估算', projects: [] },
  ];
  groups.find((group) => group.key === project.value?.sizeBucket)?.projects.push(project.value);
  return groups.filter((group) => group.projects.length > 0);
});

const stageSummary = computed(() => {
  const counts = new Map<TicketStage, number>();
  for (const ticket of filteredTickets.value) counts.set(ticket.stage, (counts.get(ticket.stage) ?? 0) + 1);
  return Array.from(counts.entries())
    .sort((left, right) => right[1] - left[1])
    .slice(0, 4)
    .map(([stage, count]) => ({ stage, count }));
});

function priorityRank(priority?: Ticket['priority']): number {
  return { P0: 0, P1: 1, P2: 2, P3: 3 }[priority ?? 'P3'];
}

function priorityLabel(priority: Ticket['priority'] | null): string {
  return priority ? `${priority} ${priority === 'P0' ? '紧急' : priority === 'P1' ? '高' : priority === 'P2' ? '普通' : '低'}` : '未设置';
}

function sizeLabel(bucket: SizeBucket): string {
  return bucket === 'large' ? '大型' : bucket === 'medium' ? '中型' : '小型';
}

function stageTone(stage: TicketStage): BadgeTone {
  if (stage === 'DONE' || stage === 'READY_TO_PUBLISH') return 'success';
  if (stage === 'REJECTED' || stage === 'CANCELLED') return 'danger';
  if (stage === 'NEEDS_HUMAN') return 'warning';
  if (stage === 'PRESUBMITTED' || stage === 'IN_REVIEW') return 'accent';
  return 'neutral';
}

function formatDate(value: string): string {
  if (!value) return '暂无';
  const timestamp = Date.parse(value);
  if (!Number.isFinite(timestamp)) return '暂无';
  return new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false }).format(timestamp);
}

function setView(view: BoardView): void {
  boardView.value = view;
  if (parseView(route.query.view) === view) return;
  const queryValue = view === 'list' ? undefined : view;
  void router.replace({ query: { ...route.query, view: queryValue } });
}

function openKanban(): void {
  void router.push({ name: 'kanban' });
}

function openSettings(): void {
  void router.push({ name: 'settings', query: { section: 'runtime' } });
}

async function load(force = false): Promise<void> {
  if (force) refreshing.value = true;
  else loading.value = true;
  errorMessage.value = '';
  const [configResult, statusResult, ticketsResult] = await Promise.allSettled([getSystemConfig(), getStatus(), listTickets()]);

  if (configResult.status === 'fulfilled') config.value = configResult.value;
  if (statusResult.status === 'fulfilled') status.value = statusResult.value;
  if (ticketsResult.status === 'fulfilled') tickets.value = ticketsResult.value;

  const failures = [configResult, statusResult, ticketsResult].filter((result) => result.status === 'rejected');
  if (failures.length === 3) errorMessage.value = '无法读取项目数据，请确认 Gate 后端正在运行。';
  else if (failures.length > 0) errorMessage.value = '部分项目数据暂时不可用，当前展示已读取的数据。';

  loading.value = false;
  refreshing.value = false;
}

onMounted(() => void load());
</script>

<template>
  <div class="project-board">
    <header class="project-board__header">
      <div class="project-board__heading">
        <p class="project-board__context">PROJECTS / GATE</p>
        <h2>项目看板</h2>
        <p>这里展示真实项目配置和工单状态。打开项目后进入工单看板处理具体流转。</p>
      </div>
      <div class="project-board__actions">
        <GButton variant="secondary" :loading="refreshing" @click="load(true)">
          <GIcon name="refresh" :size="15" />
          刷新
        </GButton>
        <GButton variant="primary" @click="openKanban">
          <GIcon name="kanban" :size="15" />
          工单看板
        </GButton>
      </div>
    </header>

    <section v-if="project" class="project-context" aria-label="项目运行概况">
      <div class="project-context__identity">
        <span class="project-context__mark"><GIcon name="folder" :size="17" /></span>
        <div>
          <strong>{{ project.name }}</strong>
          <span class="mono">{{ project.repository }}</span>
        </div>
      </div>
      <dl class="project-context__facts">
        <div><dt>工单</dt><dd>{{ project.ticketCount }}</dd></div>
        <div><dt>活跃</dt><dd>{{ project.activeCount }}</dd></div>
        <div><dt>提交</dt><dd>{{ project.commitCount }}</dd></div>
        <div><dt>目标</dt><dd class="mono">{{ project.targetRef }}</dd></div>
      </dl>
    </section>

    <div class="project-toolbar">
      <div class="view-tabs" role="tablist" aria-label="项目看板视图">
        <button type="button" role="tab" :aria-selected="boardView === 'list'" :class="{ active: boardView === 'list' }" @click="setView('list')">
          <GIcon name="grid" :size="14" />
          列表
        </button>
        <button type="button" role="tab" :aria-selected="boardView === 'priority'" :class="{ active: boardView === 'priority' }" @click="setView('priority')">
          <GIcon name="shield" :size="14" />
          按优先级
        </button>
        <button type="button" role="tab" :aria-selected="boardView === 'size'" :class="{ active: boardView === 'size' }" @click="setView('size')">
          <GIcon name="chart" :size="14" />
          按项目大小
        </button>
      </div>
      <div class="project-toolbar__tools">
        <GInput v-model="query" aria-label="搜索项目" placeholder="搜索项目、仓库或目标引用" />
        <GButton variant="ghost" size="sm" @click="openSettings">
          <GIcon name="settings" :size="14" />
          项目设置
        </GButton>
      </div>
    </div>

    <p v-if="errorMessage" class="data-message" :class="{ 'data-message--error': !project }" role="status">
      <GIcon name="refresh" :size="14" />
      {{ errorMessage }}
      <button v-if="!project" type="button" @click="load(true)">重新读取</button>
    </p>

    <div v-if="loading" class="project-list project-list--loading" aria-label="正在读取项目">
      <div v-for="index in 3" :key="index" class="project-skeleton"><span /><span /><span /></div>
    </div>

    <div v-else-if="!project && normalizedQuery" class="project-empty">
      <GIcon name="search" :size="24" />
      <strong>没有匹配的项目</strong>
      <span>试试项目名称、权威仓库或目标引用。</span>
      <GButton variant="secondary" size="sm" @click="query = ''">清除搜索</GButton>
    </div>

    <div v-else-if="!project" class="project-empty">
      <GIcon name="folder" :size="24" />
      <strong>还没有可展示的项目</strong>
      <span>请先在系统设置中完成项目和权威仓库配置。</span>
      <GButton variant="secondary" size="sm" @click="openSettings">打开系统设置</GButton>
    </div>

    <template v-else>
      <p v-if="boardView === 'priority'" class="view-note"><GIcon name="shield" :size="13" />{{ dataSourceNote }}</p>
      <p v-if="boardView === 'size'" class="view-note"><GIcon name="chart" :size="13" />项目大小按权威仓库提交数和当前工单数估算，不是后端持久化字段。</p>

      <section v-if="boardView === 'list'" class="project-list" aria-label="项目列表">
        <div class="project-list__head"><span>项目</span><span>当前状态</span><span>运行指标</span><span>最近更新</span><span /></div>
        <button v-if="project" type="button" class="project-row" @click="openKanban">
          <span class="project-row__name">
            <span class="project-row__icon"><GIcon name="folder" :size="16" /></span>
            <span><strong>{{ project.name }}</strong><small class="mono">{{ project.repository }}</small></span>
          </span>
          <span class="project-row__status">
            <GBadge :tone="project.activeCount ? 'accent' : 'success'" dot>{{ project.activeCount ? '有活跃工作' : '暂无活跃工作' }}</GBadge>
            <small>{{ project.reviewCount }} 项审核中，{{ project.readyCount }} 项可发布</small>
          </span>
          <span class="project-row__metrics"><b>{{ project.ticketCount }}</b><small>工单</small><b>{{ project.commitCount }}</b><small>提交</small></span>
          <span class="project-row__updated">{{ formatDate(project.lastUpdated) }}</span>
          <span class="project-row__arrow"><GIcon name="chevron-right" :size="16" /></span>
        </button>
        <div v-else class="project-empty project-empty--inline"><GIcon name="search" :size="18" /><span>没有匹配的项目。</span></div>
      </section>

      <section v-else-if="boardView === 'priority'" class="group-list" aria-label="按优先级分组的项目">
        <div v-for="group in priorityGroups" :key="group.key" class="project-group">
          <header class="project-group__head"><div><h3>{{ group.label }}</h3><span>{{ group.hint }}</span></div><b>{{ group.projects.length }}</b></header>
          <button v-for="item in group.projects" :key="item.id" type="button" class="project-row project-row--grouped" @click="openKanban">
            <span class="project-row__name"><span class="project-row__icon"><GIcon name="folder" :size="16" /></span><span><strong>{{ item.name }}</strong><small>{{ item.repository }}</small></span></span>
            <span class="project-row__status"><GBadge :tone="item.highestPriority === 'P0' ? 'danger' : item.highestPriority === 'P1' ? 'warning' : 'neutral'">{{ priorityLabel(item.highestPriority) }}</GBadge><small>{{ item.activeCount }} 项活跃工单</small></span>
            <span class="project-row__metrics"><b>{{ item.ticketCount }}</b><small>工单</small><b>{{ item.reviewCount }}</b><small>审核</small></span>
            <span class="project-row__arrow"><GIcon name="chevron-right" :size="16" /></span>
          </button>
        </div>
      </section>

      <section v-else class="group-list" aria-label="按项目大小分组的项目">
        <div v-for="group in sizeGroups" :key="group.key" class="project-group">
          <header class="project-group__head"><div><h3>{{ group.label }}</h3><span>{{ group.hint }}</span></div><b>{{ group.projects.length }}</b></header>
          <button v-for="item in group.projects" :key="item.id" type="button" class="project-row project-row--grouped" @click="openKanban">
            <span class="project-row__name"><span class="project-row__icon"><GIcon name="folder" :size="16" /></span><span><strong>{{ item.name }}</strong><small>{{ item.repository }}</small></span></span>
            <span class="project-row__status"><GBadge tone="accent">{{ sizeLabel(item.sizeBucket) }}项目</GBadge><small>{{ item.commitCount }} 次权威提交</small></span>
            <span class="project-row__metrics"><b>{{ item.ticketCount }}</b><small>工单</small><b>{{ item.activeCount }}</b><small>活跃</small></span>
            <span class="project-row__arrow"><GIcon name="chevron-right" :size="16" /></span>
          </button>
        </div>
      </section>

      <section class="project-detail-strip" aria-label="项目阶段摘要">
        <div class="project-detail-strip__head"><div><span>阶段分布</span><strong>{{ project.ticketCount }} 个工单</strong></div><button type="button" @click="openKanban">查看工单看板 <GIcon name="chevron-right" :size="14" /></button></div>
        <div class="stage-summary">
          <span v-for="item in stageSummary" :key="item.stage"><GBadge :tone="stageTone(item.stage)">{{ TICKET_STAGE_LABELS[item.stage] }}</GBadge><b>{{ item.count }}</b></span>
          <span v-if="stageSummary.length === 0" class="stage-summary__empty">暂无工单阶段数据</span>
        </div>
      </section>
    </template>
  </div>
</template>

<style scoped>
.project-board {
  display: flex;
  flex: 1;
  min-height: 0;
  flex-direction: column;
  gap: 16px;
  overflow: auto;
  padding: clamp(22px, 3vw, 38px) clamp(18px, 3.5vw, 52px) 32px;
}

.project-board__header,
.project-context,
.project-toolbar,
.project-list,
.group-list,
.project-detail-strip,
.data-message,
.view-note {
  width: 100%;
  max-width: 1480px;
  margin-inline: auto;
}

.project-board__header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 24px;
}

.project-board__heading { max-width: 700px; }
.project-board__context { margin: 0 0 8px; color: var(--accent-hover); font-family: var(--font-mono); font-size: 10px; font-weight: 750; letter-spacing: .12em; }
.project-board__heading h2 { margin: 0; color: var(--ink); font-size: clamp(27px, 3vw, 38px); font-weight: 780; letter-spacing: -.04em; line-height: 1.12; }
.project-board__heading p:last-child { max-width: 640px; margin: 10px 0 0; color: var(--text-secondary); font-size: 13px; line-height: 1.65; }
.project-board__actions { display: flex; flex: none; gap: 8px; }

.project-context { display: flex; align-items: center; justify-content: space-between; gap: 20px; min-height: 76px; padding: 13px 16px; border-top: 1px solid var(--border); border-bottom: 1px solid var(--border); background: color-mix(in srgb, var(--panel) 64%, transparent); }
.project-context__identity { display: flex; align-items: center; gap: 11px; min-width: 0; }
.project-context__mark { display: grid; width: 36px; height: 36px; flex: none; place-items: center; border: 1px solid var(--border); border-radius: var(--radius-md); color: var(--accent); background: var(--accent-soft); }
.project-context__identity > div { display: grid; gap: 3px; min-width: 0; }
.project-context__identity strong { overflow: hidden; color: var(--text); font-size: 14px; text-overflow: ellipsis; white-space: nowrap; }
.project-context__identity span { overflow: hidden; color: var(--text-muted); font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }
.project-context__facts { display: flex; align-items: center; gap: 24px; margin: 0; }
.project-context__facts div { display: grid; gap: 2px; min-width: 50px; }
.project-context__facts dt { color: var(--text-faint); font-size: 10px; font-weight: 700; }
.project-context__facts dd { max-width: 190px; margin: 0; overflow: hidden; color: var(--text); font-family: var(--font-mono); font-size: 12px; font-weight: 700; text-overflow: ellipsis; white-space: nowrap; }

.project-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.view-tabs { display: flex; align-items: center; gap: 2px; padding: 3px; border: 1px solid var(--border); border-radius: var(--radius-md); background: var(--panel-2); }
.view-tabs button { display: inline-flex; align-items: center; gap: 6px; min-height: 30px; padding: 0 10px; border: 0; border-radius: 5px; color: var(--text-muted); background: transparent; font-size: 11px; font-weight: 700; cursor: pointer; }
.view-tabs button:hover { color: var(--text); background: var(--hover); }
.view-tabs button.active { color: var(--accent-hover); background: var(--panel); box-shadow: var(--shadow-panel); }
.project-toolbar__tools { display: flex; align-items: center; gap: 8px; min-width: min(440px, 100%); }
.project-toolbar__tools :deep(.g-input) { min-height: 34px; font-size: 12px; }

.data-message,
.view-note { display: flex; align-items: center; gap: 7px; min-height: 32px; margin-top: -4px; margin-bottom: -4px; color: var(--text-muted); font-size: 11px; }
.data-message--error { padding: 11px 13px; border: 1px solid var(--danger-soft); background: var(--danger-soft); color: var(--danger); }
.data-message button { padding: 0; border: 0; color: var(--accent-hover); background: transparent; font-size: inherit; font-weight: 700; cursor: pointer; }
.view-note { color: var(--text-muted); }
.view-note :deep(svg) { color: var(--accent); }

.project-list { overflow: hidden; border: 1px solid var(--border); border-radius: var(--radius-lg); background: var(--panel); box-shadow: var(--shadow-panel); }
.project-list__head { display: grid; grid-template-columns: minmax(270px, 1.7fr) minmax(180px, 1fr) minmax(150px, .8fr) 120px 24px; gap: 14px; align-items: center; padding: 10px 16px; border-bottom: 1px solid var(--border); color: var(--text-faint); background: var(--panel-2); font-size: 10px; font-weight: 750; letter-spacing: .04em; text-transform: uppercase; }
.project-row { display: grid; width: 100%; grid-template-columns: minmax(270px, 1.7fr) minmax(180px, 1fr) minmax(150px, .8fr) 120px 24px; gap: 14px; align-items: center; min-height: 86px; padding: 13px 16px; border: 0; border-bottom: 1px solid var(--border); color: inherit; background: var(--panel); text-align: left; cursor: pointer; transition: background-color .16s ease, box-shadow .16s ease; }
.project-row:last-child { border-bottom: 0; }
.project-row:hover { background: var(--hover); box-shadow: inset 3px 0 0 var(--accent); }
.project-row:active { transform: translateY(1px); }
.project-row__name { display: flex; align-items: center; gap: 10px; min-width: 0; }
.project-row__icon { display: grid; width: 32px; height: 32px; flex: none; place-items: center; border: 1px solid var(--border); border-radius: var(--radius-sm); color: var(--accent); background: var(--accent-soft); }
.project-row__name > span:last-child { display: grid; gap: 4px; min-width: 0; }
.project-row__name strong { overflow: hidden; color: var(--text); font-size: 13px; font-weight: 750; text-overflow: ellipsis; white-space: nowrap; }
.project-row__name small { overflow: hidden; color: var(--text-muted); font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
.project-row__status { display: grid; gap: 5px; min-width: 0; }
.project-row__status :deep(.g-badge) { width: fit-content; }
.project-row__status small { overflow: hidden; color: var(--text-muted); font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
.project-row__metrics { display: flex; align-items: baseline; gap: 5px; min-width: 0; color: var(--text-faint); font-size: 10px; }
.project-row__metrics b { margin-left: 3px; color: var(--text); font-family: var(--font-mono); font-size: 13px; }
.project-row__metrics b:first-child { margin-left: 0; }
.project-row__updated { color: var(--text-muted); font-family: var(--font-mono); font-size: 10px; white-space: nowrap; }
.project-row__arrow { display: grid; place-items: center; color: var(--text-faint); }
.project-row:hover .project-row__arrow { color: var(--accent); }

.group-list { display: grid; gap: 12px; }
.project-group { overflow: hidden; border: 1px solid var(--border); border-radius: var(--radius-lg); background: var(--panel); box-shadow: var(--shadow-panel); }
.project-group__head { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 13px 16px; border-bottom: 1px solid var(--border); background: var(--panel-2); }
.project-group__head div { display: grid; gap: 3px; }
.project-group__head h3 { margin: 0; color: var(--text); font-size: 13px; font-weight: 750; }
.project-group__head span { color: var(--text-muted); font-size: 10px; }
.project-group__head b { display: grid; min-width: 24px; height: 24px; place-items: center; border-radius: 50%; color: var(--accent-hover); background: var(--accent-soft); font-family: var(--font-mono); font-size: 11px; }
.project-row--grouped { grid-template-columns: minmax(280px, 1.6fr) minmax(170px, 1fr) minmax(150px, .8fr) 24px; }

.project-detail-strip { display: grid; gap: 14px; padding: 15px 16px; border-top: 1px solid var(--border); background: transparent; }
.project-detail-strip__head { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.project-detail-strip__head > div { display: grid; gap: 3px; }
.project-detail-strip__head span { color: var(--text-muted); font-size: 10px; font-weight: 700; }
.project-detail-strip__head strong { color: var(--text); font-size: 13px; }
.project-detail-strip__head button { display: inline-flex; align-items: center; gap: 4px; padding: 0; border: 0; color: var(--accent-hover); background: transparent; font-size: 11px; font-weight: 700; cursor: pointer; }
.stage-summary { display: flex; flex-wrap: wrap; gap: 10px 18px; }
.stage-summary > span { display: inline-flex; align-items: center; gap: 6px; }
.stage-summary b { color: var(--text); font-family: var(--font-mono); font-size: 12px; }
.stage-summary__empty { color: var(--text-muted); font-size: 11px; }

.project-empty { display: grid; justify-items: center; gap: 8px; min-height: 220px; place-content: center; color: var(--text-muted); text-align: center; }
.project-empty :deep(svg) { color: var(--border-strong); }
.project-empty strong { color: var(--text); font-size: 14px; }
.project-empty span { max-width: 300px; font-size: 12px; }
.project-empty--inline { min-height: 150px; }
.project-skeleton { display: grid; grid-template-columns: 2fr 1fr 1fr 100px 20px; gap: 14px; align-items: center; min-height: 86px; padding: 14px 16px; border-bottom: 1px solid var(--border); background: var(--panel); }
.project-skeleton span { height: 12px; border-radius: 4px; background: var(--panel-2); animation: pulse 1.2s ease-in-out infinite alternate; }
.project-skeleton span:first-child { height: 28px; }
@keyframes pulse { from { opacity: .52; } to { opacity: 1; } }

@media (max-width: 900px) {
  .project-board__header, .project-context, .project-toolbar { align-items: flex-start; flex-direction: column; }
  .project-board__actions, .project-toolbar__tools { width: 100%; }
  .project-toolbar__tools :deep(.g-input) { flex: 1; }
  .project-context__facts { width: 100%; justify-content: space-between; }
  .project-list__head { display: none; }
  .project-row, .project-row--grouped { grid-template-columns: minmax(0, 1fr) auto; gap: 10px 16px; }
  .project-row__name { grid-column: 1 / -1; }
  .project-row__status { grid-column: 1; }
  .project-row__metrics { grid-column: 2; grid-row: 2; justify-content: flex-end; }
  .project-row__updated { display: none; }
  .project-row__arrow { grid-column: 2; grid-row: 1; }
  .project-skeleton { grid-template-columns: 1fr 80px; }
  .project-skeleton span:nth-child(2), .project-skeleton span:nth-child(3) { display: none; }
}

@media (max-width: 560px) {
  .project-board { gap: 13px; padding: 18px 14px 24px; }
  .project-board__heading h2 { font-size: 26px; }
  .project-board__heading p:last-child { font-size: 12px; }
  .project-board__actions { display: grid; grid-template-columns: 1fr 1fr; }
  .project-board__actions :deep(.g-btn) { width: 100%; }
  .project-context { gap: 13px; padding: 12px; }
  .project-context__facts { gap: 10px; overflow-x: auto; }
  .project-context__facts div { min-width: 46px; }
  .project-context__facts dd { max-width: 112px; }
  .view-tabs { width: 100%; overflow-x: auto; }
  .view-tabs button { flex: 1; justify-content: center; white-space: nowrap; }
  .project-toolbar__tools { align-items: stretch; flex-direction: column; }
  .project-toolbar__tools :deep(.g-btn) { justify-content: flex-start; }
  .project-row { padding: 13px; }
  .project-row__status small { max-width: 170px; }
  .project-detail-strip { padding-inline: 3px; }
  .project-detail-strip__head { align-items: flex-start; flex-direction: column; }
}

@media (prefers-reduced-motion: reduce) {
  .project-row, .project-skeleton span { transition: none; animation: none; }
}
</style>
