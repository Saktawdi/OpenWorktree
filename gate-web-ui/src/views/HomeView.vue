<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { getSystemConfig, type SystemConfigView } from '@/api/config';
import { getStatus, type StatusResult } from '@/api/status';
import { listTickets } from '@/api/tickets';
import {
  browseWorkspace,
  createProject,
  listProjects,
  updateProject,
  type ProjectPriority,
  type ProjectSize,
  type ProjectView,
  type WorkspaceView,
} from '@/api/projects';
import { GBadge, GButton, GCheckbox, GEmpty, GField, GIcon, GInput, GModal, GSelect } from '@/components/ui';
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
  priority: ProjectPriority | null;
  highestPriority: Ticket['priority'] | null;
  size: ProjectSize | null;
  sizeBucket: SizeBucket;
  tags: string[];
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
const registeredProjects = ref<ProjectView[]>([]);
const query = ref('');
const loading = ref(true);
const refreshing = ref(false);
const errorMessage = ref('');
const showProjectModal = ref(false);
const projectName = ref('');
const workspacePath = ref('');
const workspace = ref<WorkspaceView | null>(null);
const workspaceLoading = ref(false);
const pathInput = ref('');
const projectSaving = ref(false);
const initGit = ref(true);
const projectError = ref('');

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

function projectMatches(project: ProjectView): Ticket[] {
  const rows = boardTickets.value.filter((ticket) => ticket.projectId === project.id);
  if (!normalizedQuery.value) return rows;
  const projectText = [project.name, project.workspace_path, project.auth_repo, project.target_ref]
    .filter(Boolean).join(' ').toLowerCase();
  if (projectText.includes(normalizedQuery.value)) return rows;
  return rows.filter((ticket) => [ticket.no, ticket.title, ticket.targetRef, ticket.clonePath]
    .filter(Boolean).join(' ').toLowerCase().includes(normalizedQuery.value));
}

const projectRecords = computed<ProjectRecord[]>(() => registeredProjects.value.map((registered) => {
  const rows = projectMatches(registered);
  const active = rows.filter((ticket) => !['DONE', 'CANCELLED'].includes(ticket.stage));
  const review = rows.filter((ticket) => ['PRESUBMITTED', 'IN_REVIEW', 'READY_TO_PUBLISH'].includes(ticket.stage));
  const priorities = active.map((ticket) => ticket.priority)
    .filter((priority): priority is NonNullable<Ticket['priority']> => Boolean(priority));
  const highestPriority = priorities.sort((left, right) => priorityRank(left) - priorityRank(right))[0] ?? null;
  const sizeBucket: SizeBucket = rows.length >= 50 ? 'large' : rows.length >= 15 ? 'medium' : 'small';
  const lastUpdated = rows.map((ticket) => ticket.updatedAt).filter(Boolean)
    .sort((left, right) => Date.parse(right) - Date.parse(left))[0] ?? registered.updated_at;
  return {
    id: registered.id,
    name: registered.name,
    repository: registered.auth_repo || '未配置权威仓库',
    cloneRoot: registered.workspace_path,
    targetRef: registered.target_ref || status.value?.targetRef || '--',
    ticketCount: rows.length,
    activeCount: active.length,
    reviewCount: review.length,
    readyCount: rows.filter((ticket) => ticket.stage === 'READY_TO_PUBLISH').length,
    doneCount: rows.filter((ticket) => ticket.stage === 'DONE').length,
    priority: registered.priority,
    highestPriority,
    size: registered.size,
    sizeBucket,
    tags: registered.tags,
    lastUpdated,
  };
}));

const visibleProjects = computed(() => projectRecords.value.filter((project) => {
  if (!normalizedQuery.value) return true;
  return projectMatches(registeredProjects.value.find((item) => item.id === project.id)!).length > 0
    || [project.name, project.repository, project.cloneRoot, project.targetRef].join(' ').toLowerCase().includes(normalizedQuery.value);
}));
const project = computed<ProjectRecord | null>(() => visibleProjects.value[0] ?? null);

const dataSourceNote = computed(() =>
  '优先级先取项目手动设置；未设置的项目按未完成工单的最高优先级聚合。');

const priorityGroups = computed<ProjectGroup[]>(() => {
  if (!visibleProjects.value.length) return [];
  const groups: ProjectGroup[] = [
    { key: 'P0', label: 'P0 紧急', hint: '手动设置或最高优先级工单', projects: [] },
    { key: 'P1', label: 'P1 高', hint: '需要优先排期', projects: [] },
    { key: 'P2', label: 'P2 普通', hint: '按常规节奏推进', projects: [] },
    { key: 'P3', label: 'P3 低', hint: '可以延后处理', projects: [] },
    { key: 'unset', label: '未设置优先级', hint: '未手动设置且无带优先级的未完成工单', projects: [] },
  ];
  for (const item of visibleProjects.value) {
    const key = item.priority ?? item.highestPriority ?? 'unset';
    groups.find((group) => group.key === key)?.projects.push(item);
  }
  return groups.filter((group) => group.projects.length > 0);
});

const sizeGroups = computed<ProjectGroup[]>(() => {
  if (!visibleProjects.value.length) return [];
  const groups: ProjectGroup[] = [
    { key: 'small', label: '小型项目', hint: '手动设置；未设置时按工单数估算', projects: [] },
    { key: 'medium', label: '中型项目', hint: '手动设置；未设置时按工单数估算', projects: [] },
    { key: 'large', label: '大型项目', hint: '手动设置；未设置时按工单数估算', projects: [] },
  ];
  for (const item of visibleProjects.value) {
    groups.find((group) => group.key === (item.size ?? item.sizeBucket))?.projects.push(item);
  }
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

const workflowOverview = computed(() => {
  const allTickets = boardTickets.value;
  const active = allTickets.filter((ticket) => !['DONE', 'CANCELLED'].includes(ticket.stage)).length;
  const inReview = allTickets.filter((ticket) => ['PRESUBMITTED', 'IN_REVIEW'].includes(ticket.stage)).length;
  const ready = allTickets.filter((ticket) => ticket.stage === 'READY_TO_PUBLISH').length;
  const attention = allTickets.filter((ticket) => ['REJECTED', 'NEEDS_HUMAN'].includes(ticket.stage)).length;
  const closed = allTickets.length - active;

  let headline = '当前没有待处理工作';
  if (attention > 0) {
    headline = `${attention} 张工单需要关注`;
  } else if (ready > 0) {
    headline = `${ready} 张工单等待发布`;
  } else if (active > 0) {
    headline = `${active} 张工单正在推进`;
  }

  return {
    active,
    inReview,
    ready,
    attention,
    headline,
    detail: `${registeredProjects.value.length} 个项目 · ${allTickets.length} 张工单${closed > 0 ? ` · ${closed} 张已结束` : ''}`,
  };
});

const attentionTickets = computed(() => [...boardTickets.value]
  .filter((ticket) => ['NEEDS_HUMAN', 'REJECTED', 'READY_TO_PUBLISH', 'IN_REVIEW'].includes(ticket.stage))
  .sort((left, right) => priorityRank(left.priority) - priorityRank(right.priority)
    || Date.parse(right.updatedAt) - Date.parse(left.updatedAt))
  .slice(0, 4));

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

function openKanban(projectId = project.value?.id): void {
  if (!projectId) {
      errorMessage.value = '请先选择一个项目，再打开对应的工单看板。';
    return;
  }
  void router.push({ name: 'kanban', params: { projectId } });
}

function openTicket(ticket: Ticket): void {
  if (!ticket.projectId) {
    errorMessage.value = `${ticket.no} 尚未绑定项目，无法打开项目工单看板。`;
    return;
  }
  void router.push({ name: 'ticket-detail', params: { projectId: ticket.projectId, no: ticket.no } });
}

function openTicketReview(ticket: Ticket): void {
  if (!ticket.projectId) {
    errorMessage.value = `${ticket.no} 尚未绑定项目，无法打开审核台。`;
    return;
  }
  void router.push({ name: 'review', params: { projectId: ticket.projectId, no: ticket.no } });
}

function attentionActionLabel(ticket: Ticket): string {
  if (ticket.stage === 'READY_TO_PUBLISH') return '确认发布';
  if (ticket.stage === 'IN_REVIEW') return '打开审核';
  if (ticket.stage === 'NEEDS_HUMAN') return '查看处理';
  return '查看回流';
}

function openAttentionAction(ticket: Ticket): void {
  if (ticket.stage === 'IN_REVIEW' || ticket.stage === 'READY_TO_PUBLISH') {
    openTicketReview(ticket);
    return;
  }
  openTicket(ticket);
}

async function openProjectModal(): Promise<void> {
  projectName.value = '';
  workspacePath.value = '';
  workspace.value = null;
  pathInput.value = '';
  projectError.value = '';
  initGit.value = true;
  showProjectModal.value = true;
  await browseWorkspacePath();
}

async function browseWorkspacePath(path?: string): Promise<void> {
  workspaceLoading.value = true;
  projectError.value = '';
  try {
    workspace.value = await browseWorkspace(path);
    workspacePath.value = workspace.value.path;
    pathInput.value = workspace.value.path;
    if (!workspace.value.exists) {
      projectError.value = '该路径当前不存在；可检查后重试，或保留它——创建项目时后端会自动新建目录。';
    }
  } catch {
    projectError.value = '无法读取工作区目录，请确认后端服务有权限访问该路径。';
  } finally {
    workspaceLoading.value = false;
  }
}

/** 直达手动输入/粘贴的完整路径（跨盘场景下比逐级点击快得多）。 */
async function browseTypedPath(): Promise<void> {
  const typed = pathInput.value.trim();
  if (!typed) {
    projectError.value = '请先输入完整的目录路径，例如 D:\\work\\my-project。';
    return;
  }
  await browseWorkspacePath(typed);
}

async function saveProject(): Promise<void> {
  projectError.value = '';
  if (!projectName.value.trim()) {
    projectError.value = '请填写项目名称。';
    return;
  }
  if (!workspacePath.value.trim()) {
    projectError.value = '请选择工作区目录。';
    return;
  }
  projectSaving.value = true;
  try {
    const created = await createProject({
      name: projectName.value.trim(),
      workspacePath: workspacePath.value,
      initGit: initGit.value,
    });
    registeredProjects.value = [...registeredProjects.value, created];
    showProjectModal.value = false;
    errorMessage.value = '';
  } catch {
    projectError.value = '创建项目失败，请检查目录权限、项目名称和后端日志。';
  } finally {
    projectSaving.value = false;
  }
}

// --- 项目元数据（优先级/大小/标签）编辑 -------------------------------------------------------
const showMetaModal = ref(false);
const metaSaving = ref(false);
const metaError = ref('');
const metaProjectId = ref('');
const metaName = ref('');
const metaPriority = ref<string | null>(null);
const metaSize = ref<string | null>(null);
const metaTagsInput = ref('');

const metaPriorityOptions = [
  { label: 'P0 紧急', value: 'P0' },
  { label: 'P1 高', value: 'P1' },
  { label: 'P2 普通', value: 'P2' },
  { label: 'P3 低', value: 'P3' },
];
const metaSizeOptions = [
  { label: '小型', value: 'small' },
  { label: '中型', value: 'medium' },
  { label: '大型', value: 'large' },
];

function openMetaModal(id: string): void {
  const target = registeredProjects.value.find((item) => item.id === id);
  if (!target) return;
  metaProjectId.value = id;
  metaName.value = target.name;
  metaPriority.value = target.priority;
  metaSize.value = target.size;
  metaTagsInput.value = target.tags.join(', ');
  metaError.value = '';
  showMetaModal.value = true;
}

async function saveMeta(): Promise<void> {
  metaError.value = '';
  const name = metaName.value.trim();
  if (!name) {
    metaError.value = '项目名称不能为空。';
    return;
  }
  const tags = metaTagsInput.value.split(/[,，]/).map((tag) => tag.trim()).filter(Boolean);
  if (new Set(tags).size !== tags.length) {
    metaError.value = '标签存在重复项。';
    return;
  }
  if (tags.length > 20) {
    metaError.value = '标签最多 20 个。';
    return;
  }
  metaSaving.value = true;
  try {
    const updated = await updateProject(metaProjectId.value, {
      name,
      priority: metaPriority.value as ProjectPriority | null,
      size: metaSize.value as ProjectSize | null,
      tags,
    });
    registeredProjects.value = registeredProjects.value.map((item) => (item.id === updated.id ? updated : item));
    showMetaModal.value = false;
  } catch {
    metaError.value = '保存失败，请检查输入（标签不超过 32 字符）与后端日志。';
  } finally {
    metaSaving.value = false;
  }
}

function effectivePriority(item: ProjectRecord): Ticket['priority'] | null {
  return item.priority ?? item.highestPriority;
}

function priorityTone(priority: Ticket['priority'] | null): BadgeTone {
  if (priority === 'P0') return 'danger';
  if (priority === 'P1') return 'warning';
  if (priority === 'P2') return 'accent';
  return 'neutral';
}

function openSettings(): void {
  void router.push({ name: 'settings', query: { section: 'runtime' } });
}

async function load(force = false): Promise<void> {
  if (force) refreshing.value = true;
  else loading.value = true;
  errorMessage.value = '';
  const [configResult, statusResult, ticketsResult, projectsResult] = await Promise.allSettled([
    getSystemConfig(),
    getStatus(),
    listTickets(),
    listProjects(),
  ]);

  if (configResult.status === 'fulfilled') config.value = configResult.value;
  if (statusResult.status === 'fulfilled') status.value = statusResult.value;
  if (ticketsResult.status === 'fulfilled') tickets.value = ticketsResult.value;
  if (projectsResult.status === 'fulfilled') registeredProjects.value = projectsResult.value;

  const failures = [configResult, statusResult, ticketsResult, projectsResult].filter((result) => result.status === 'rejected');
  if (failures.length === 4) errorMessage.value = '无法读取项目数据，请确认 Gate 后端正在运行。';
  else if (failures.length > 0) errorMessage.value = '部分项目数据暂时不可用，当前展示已读取的数据。';

  loading.value = false;
  refreshing.value = false;
}

onMounted(() => void load());
</script>

<template>
  <div class="project-board">
    <header class="home-intro">
      <div class="home-intro__copy">
        <p class="home-intro__eyebrow">本地工作区</p>
        <h2>从下一步开始</h2>
      <p>先处理需要你判断的事项，其余工作交给工单流转和智能体执行。</p>
      </div>
      <div class="home-intro__actions">
        <GButton variant="primary" @click="openKanban(project?.id)">
          <GIcon name="kanban" :size="15" />
          打开工单看板
        </GButton>
        <GButton variant="secondary" @click="openProjectModal">
          <GIcon name="plus" :size="15" />
          创建项目
        </GButton>
      </div>
    </header>

    <div class="project-board__actions">
      <span class="sync-note"><GIcon name="refresh" :size="13" />数据来自本地服务实例</span>
      <GButton variant="secondary" :loading="refreshing" @click="load(true)">
        <GIcon name="refresh" :size="15" />
        刷新
      </GButton>
    </div>

    <section v-if="project" class="workflow-overview" aria-label="工作概览">
      <div class="workflow-overview__lead">
        <span class="workflow-overview__mark"><GIcon name="chart" :size="17" /></span>
        <div>
          <span>工作概览</span>
          <strong>{{ workflowOverview.headline }}</strong>
          <small>{{ workflowOverview.detail }}</small>
        </div>
      </div>
      <dl class="workflow-overview__metrics">
        <div><dt>进行中</dt><dd>{{ workflowOverview.active }}</dd><small>未结束工单</small></div>
        <div><dt>审核中</dt><dd>{{ workflowOverview.inReview }}</dd><small>预提审或审核中</small></div>
        <div><dt>可发布</dt><dd>{{ workflowOverview.ready }}</dd><small>已通过审核</small></div>
        <div :class="{ 'workflow-overview__metric--attention': workflowOverview.attention > 0 }">
          <dt>需处理</dt><dd>{{ workflowOverview.attention }}</dd><small>驳回或待人工</small>
        </div>
      </dl>
    </section>

    <section v-if="attentionTickets.length" class="attention-lane" aria-label="需要处理的工单">
      <header class="attention-lane__head">
        <div>
          <p class="section-label">需要你继续</p>
          <h2>处理队列</h2>
        </div>
        <button type="button" class="inline-link" @click="openKanban(project?.id)">
          查看全部工单
          <GIcon name="chevron-right" :size="14" />
        </button>
      </header>
      <div class="attention-lane__items">
        <article v-for="ticket in attentionTickets" :key="ticket.no" class="attention-item">
          <button type="button" class="attention-item__main" @click="openTicket(ticket)">
            <span class="attention-item__topline">
              <span class="mono">{{ ticket.no }}</span>
              <GBadge :tone="stageTone(ticket.stage)" dot>{{ TICKET_STAGE_LABELS[ticket.stage] }}</GBadge>
            </span>
            <strong>{{ ticket.title }}</strong>
            <small>{{ ticket.project || '未绑定项目' }}<span>·</span>{{ formatDate(ticket.updatedAt) }}</small>
          </button>
          <button type="button" class="attention-item__action" @click="openAttentionAction(ticket)">
            {{ attentionActionLabel(ticket) }}
            <GIcon name="chevron-right" :size="13" />
          </button>
        </article>
      </div>
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

    <div v-else-if="!project && normalizedQuery" class="project-empty--wrap">
      <GEmpty icon="search" title="没有匹配的项目" hint="试试项目名称、权威仓库或目标引用。">
        <GButton variant="secondary" size="sm" @click="query = ''">清除搜索</GButton>
      </GEmpty>
    </div>

    <div v-else-if="!project" class="project-empty--wrap">
      <GEmpty icon="folder" title="还没有可展示的项目" hint="选择一个本地工作区，创建项目后再开始管理工单。">
        <GButton variant="secondary" size="sm" @click="openProjectModal">选择工作区并创建</GButton>
      </GEmpty>
    </div>

    <template v-else>
      <p v-if="boardView === 'priority'" class="view-note"><GIcon name="shield" :size="13" />{{ dataSourceNote }}</p>
      <p v-if="boardView === 'size'" class="view-note"><GIcon name="chart" :size="13" />项目大小可按项目手动设置；未设置时按当前工单数量估算。用每行的设置按钮修改。</p>

      <section v-if="boardView === 'list'" class="project-list" aria-label="项目列表">
        <div class="project-list__head"><span>项目</span><span>当前状态</span><span>运行指标</span><span>最近更新</span><span /></div>
         <div v-for="item in visibleProjects" :key="item.id" class="project-row" role="button" tabindex="0" @click="openKanban(item.id)" @keydown.enter.prevent="openKanban(item.id)" @keydown.space.prevent="openKanban(item.id)">
           <span class="project-row__name">
             <span class="project-row__icon"><GIcon name="folder" :size="16" /></span>
             <span>
               <strong>{{ item.name }}</strong><small class="mono">{{ item.cloneRoot }}</small>
               <span v-if="item.tags.length" class="project-row__tags"><em v-for="tag in item.tags" :key="tag">{{ tag }}</em></span>
             </span>
           </span>
           <span class="project-row__status">
             <GBadge :tone="item.activeCount ? 'accent' : 'success'" dot>{{ item.activeCount ? '有活跃工作' : '暂无活跃工作' }}</GBadge>
             <small>{{ item.reviewCount }} 项审核中，{{ item.readyCount }} 项可发布</small>
           </span>
           <span class="project-row__metrics"><b>{{ item.ticketCount }}</b><small>工单</small><b>{{ item.activeCount }}</b><small>活跃</small></span>
           <span class="project-row__updated">{{ formatDate(item.lastUpdated) }}</span>
           <span class="project-row__arrow">
             <button type="button" class="row-edit" aria-label="设置优先级、大小和标签" title="设置优先级、大小和标签" @click.stop="openMetaModal(item.id)"><GIcon name="settings" :size="14" /></button>
             <GIcon name="chevron-right" :size="16" />
           </span>
         </div>
         <div v-if="visibleProjects.length === 0" class="project-empty project-empty--inline"><GIcon name="search" :size="18" /><span>没有匹配的项目。</span></div>
      </section>

      <section v-else-if="boardView === 'priority'" class="group-list" aria-label="按优先级分组的项目">
        <div v-for="group in priorityGroups" :key="group.key" class="project-group">
          <header class="project-group__head"><div><h3>{{ group.label }}</h3><span>{{ group.hint }}</span></div><b>{{ group.projects.length }}</b></header>
          <div v-for="item in group.projects" :key="item.id" class="project-row project-row--grouped" role="button" tabindex="0" @click="openKanban(item.id)" @keydown.enter.prevent="openKanban(item.id)" @keydown.space.prevent="openKanban(item.id)">
            <span class="project-row__name"><span class="project-row__icon"><GIcon name="folder" :size="16" /></span><span><strong>{{ item.name }}</strong><small>{{ item.repository }}</small><span v-if="item.tags.length" class="project-row__tags"><em v-for="tag in item.tags" :key="tag">{{ tag }}</em></span></span></span>
            <span class="project-row__status"><GBadge :tone="priorityTone(effectivePriority(item))">{{ priorityLabel(effectivePriority(item)) }}</GBadge><small>{{ item.priority ? '手动设置' : '按未完成工单推导' }} · {{ item.activeCount }} 项活跃</small></span>
            <span class="project-row__metrics"><b>{{ item.ticketCount }}</b><small>工单</small><b>{{ item.reviewCount }}</b><small>审核</small></span>
            <span class="project-row__arrow">
              <button type="button" class="row-edit" aria-label="设置优先级、大小和标签" title="设置优先级、大小和标签" @click.stop="openMetaModal(item.id)"><GIcon name="settings" :size="14" /></button>
              <GIcon name="chevron-right" :size="16" />
            </span>
          </div>
        </div>
      </section>

      <section v-else class="group-list" aria-label="按项目大小分组的项目">
        <div v-for="group in sizeGroups" :key="group.key" class="project-group">
          <header class="project-group__head"><div><h3>{{ group.label }}</h3><span>{{ group.hint }}</span></div><b>{{ group.projects.length }}</b></header>
          <div v-for="item in group.projects" :key="item.id" class="project-row project-row--grouped" role="button" tabindex="0" @click="openKanban(item.id)" @keydown.enter.prevent="openKanban(item.id)" @keydown.space.prevent="openKanban(item.id)">
            <span class="project-row__name"><span class="project-row__icon"><GIcon name="folder" :size="16" /></span><span><strong>{{ item.name }}</strong><small>{{ item.repository }}</small><span v-if="item.tags.length" class="project-row__tags"><em v-for="tag in item.tags" :key="tag">{{ tag }}</em></span></span></span>
            <span class="project-row__status"><GBadge tone="accent">{{ sizeLabel(item.size ?? item.sizeBucket) }}项目</GBadge><small>{{ item.size ? '手动设置' : '按工单数量估算' }}</small></span>
            <span class="project-row__metrics"><b>{{ item.ticketCount }}</b><small>工单</small><b>{{ item.activeCount }}</b><small>活跃</small></span>
            <span class="project-row__arrow">
              <button type="button" class="row-edit" aria-label="设置优先级、大小和标签" title="设置优先级、大小和标签" @click.stop="openMetaModal(item.id)"><GIcon name="settings" :size="14" /></button>
              <GIcon name="chevron-right" :size="16" />
            </span>
          </div>
        </div>
      </section>

      <section class="project-detail-strip" aria-label="项目阶段摘要">
        <div class="project-detail-strip__head"><div><span>{{ project.name }} · 阶段分布</span><strong>{{ project.ticketCount }} 个工单</strong></div><button type="button" @click="openKanban(project.id)">查看 {{ project.name }} 看板 <GIcon name="chevron-right" :size="14" /></button></div>
        <div class="stage-summary">
          <span v-for="item in stageSummary" :key="item.stage"><GBadge :tone="stageTone(item.stage)">{{ TICKET_STAGE_LABELS[item.stage] }}</GBadge><b>{{ item.count }}</b></span>
          <span v-if="stageSummary.length === 0" class="stage-summary__empty">暂无工单阶段数据</span>
        </div>
      </section>
    </template>
  </div>

  <GModal :show="showProjectModal" title="选择工作区并创建项目" width="620px" @close="showProjectModal = false">
    <div class="project-create-form">
      <p>先选择本地工作区；后端会注册该目录，并可按需初始化代码仓库。</p>
      <GField label="项目名称" for-id="project-name" hint="名称会显示在项目看板和工单上下文中。" required>
        <GInput id="project-name" v-model="projectName" name="project-name" autocomplete="off" placeholder="例如：支付服务" required />
      </GField>
      <section class="workspace-picker" aria-label="工作区目录选择器">
        <header><div><span class="section-label">当前工作区</span><code class="mono">{{ workspacePath || '--' }}</code></div><GButton v-if="workspace?.parent" size="sm" variant="ghost" @click="browseWorkspacePath(workspace.parent ?? undefined)"><GIcon name="chevron-left" :size="13" /> 上一级</GButton></header>
        <div class="workspace-jump">
          <div v-if="workspace?.roots?.length" class="workspace-jump__drives" role="group" aria-label="切换磁盘">
            <button
              v-for="root in workspace.roots"
              :key="root.path"
              type="button"
              class="drive-chip"
              :class="{ 'drive-chip--active': workspace.path === root.path }"
              :title="root.path"
              @click="browseWorkspacePath(root.path)"
            >{{ root.name }}</button>
          </div>
          <div class="workspace-jump__entry">
            <GField class="workspace-path-field" label="工作区路径" for-id="workspace-path" hint="输入完整路径后按 Enter 或点击前往。">
              <GInput id="workspace-path" v-model="pathInput" name="workspace-path" autocomplete="off" placeholder="例如 D:\work\my-project" @keyup.enter="browseTypedPath" />
            </GField>
            <GButton size="sm" variant="secondary" :disabled="workspaceLoading" @click="browseTypedPath">前往</GButton>
          </div>
        </div>
        <div v-if="workspaceLoading" class="workspace-picker__state">正在读取目录……</div>
        <div v-else-if="workspace && workspace.directories.length" class="workspace-list">
          <button v-for="entry in workspace.directories" :key="entry.path" type="button" class="workspace-entry" :disabled="entry.is_registered_project" @click="browseWorkspacePath(entry.path)">
            <GIcon name="folder" :size="15" /><span><strong>{{ entry.name }}</strong><small class="mono">{{ entry.path }}</small></span><GBadge v-if="entry.is_registered_project" tone="neutral">已注册</GBadge><GBadge v-else-if="entry.is_git_repo" tone="accent">代码仓库</GBadge>
          </button>
        </div>
        <div v-else class="workspace-picker__state">当前目录没有可进入的子目录，可以直接把当前目录作为项目工作区。</div>
      </section>
      <GCheckbox v-model="initGit" name="init-git" class="project-init-toggle">如果目录还不是 Git 仓库，创建项目时执行 `git init`。</GCheckbox>
      <p v-if="projectError" class="project-form-error" role="alert">{{ projectError }}</p>
    </div>
    <template #footer><GButton variant="ghost" @click="showProjectModal = false">取消</GButton><GButton variant="primary" :loading="projectSaving" @click="saveProject">创建项目</GButton></template>
  </GModal>

  <GModal :show="showMetaModal" title="项目元数据" width="520px" @close="showMetaModal = false">
    <div class="project-create-form">
      <GField label="项目名称" for-id="meta-project-name" required>
        <GInput id="meta-project-name" v-model="metaName" name="project-name" autocomplete="off" placeholder="项目名称" required />
      </GField>
      <div class="meta-grid">
        <GField label="优先级" for-id="meta-priority">
          <GSelect id="meta-priority" v-model="metaPriority" :options="metaPriorityOptions" placeholder="未设置（按工单推导）" />
        </GField>
        <GField label="大小" for-id="meta-size">
          <GSelect id="meta-size" v-model="metaSize" :options="metaSizeOptions" placeholder="未设置（按工单数估算）" />
        </GField>
      </div>
      <GField label="标签" for-id="meta-tags" hint="最多 20 个标签，每个不超过 32 个字符。">
        <GInput id="meta-tags" v-model="metaTagsInput" name="project-tags" autocomplete="off" placeholder="用逗号分隔，例如：后端、支付、专项" />
      </GField>
      <p class="meta-hint">优先级/大小留空时看板按工单自动推导；标签会显示在项目卡片上（最多 20 个，每个不超过 32 字符）。</p>
      <p v-if="metaError" class="project-form-error" role="alert">{{ metaError }}</p>
    </div>
    <template #footer><GButton variant="ghost" @click="showMetaModal = false">取消</GButton><GButton variant="primary" :loading="metaSaving" @click="saveMeta">保存</GButton></template>
  </GModal>
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

.project-create-form { display: grid; gap: 16px; }
.project-create-form > p { margin: 0; color: var(--text-muted); font-size: 12px; line-height: 1.55; }
.project-create-field { display: grid; gap: 7px; }
.project-create-field > span { color: var(--text-secondary); font-size: 12px; font-weight: 700; }
.workspace-picker { overflow: hidden; border: 1px solid var(--border); background: var(--panel-2); }
.workspace-picker > header { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 12px; border-bottom: 1px solid var(--border); }
.workspace-picker > header > div { display: grid; gap: 5px; min-width: 0; }
.workspace-picker > header code { overflow: hidden; color: var(--text-secondary); font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }
.workspace-jump { display: grid; gap: 8px; padding: 10px 12px; border-bottom: 1px solid var(--border); }
.workspace-jump__drives { display: flex; flex-wrap: wrap; gap: 6px; }
.drive-chip { padding: 3px 10px; border: 1px solid var(--border-strong); border-radius: 999px; color: var(--text-secondary); background: var(--panel); font-family: var(--font-mono); font-size: 11px; font-weight: 700; cursor: pointer; transition: border-color 0.16s ease, color 0.16s ease, background-color 0.16s ease; }
.drive-chip:hover { border-color: var(--accent); color: var(--accent-hover); }
.drive-chip--active { border-color: var(--accent); color: var(--accent-hover); background: var(--accent-soft); }
.workspace-jump__entry { display: flex; gap: 10px; align-items: flex-end; }
.workspace-jump__entry > :first-child { flex: 1; min-width: 0; }
.workspace-jump__entry > :last-child { flex: none; }
.workspace-path-field { gap: 5px; }
.workspace-list { display: grid; max-height: 260px; overflow: auto; }
.workspace-entry { display: grid; grid-template-columns: 18px minmax(0,1fr) auto; gap: 9px; align-items: center; padding: 10px 12px; border: 0; border-bottom: 1px solid var(--border); color: var(--text-secondary); background: transparent; text-align: left; cursor: pointer; }
.workspace-entry:last-child { border-bottom: 0; }
.workspace-entry:hover:not(:disabled) { background: var(--hover); }
.workspace-entry:disabled { cursor: not-allowed; opacity: .55; }
.workspace-entry > span { display: grid; gap: 3px; min-width: 0; }
.workspace-entry strong, .workspace-entry small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.workspace-entry strong { color: var(--text); font-size: 12px; }
.workspace-entry small { color: var(--text-muted); font-size: 10px; }
.workspace-picker__state { padding: 26px 14px; color: var(--text-muted); font-size: 11px; line-height: 1.5; text-align: center; }
.project-init-toggle { width: fit-content; }
.project-form-error { margin: 0; padding: 8px 10px; border-left: 3px solid var(--danger); color: var(--danger); background: var(--danger-soft); font-size: 12px; }
.project-row__tags { display: flex; flex-wrap: wrap; gap: 4px; margin-top: 4px; }
.project-row__tags em { padding: 1px 7px; border: 1px solid var(--border-strong); border-radius: 999px; color: var(--text-muted); font-size: 10px; font-style: normal; font-weight: 650; background: var(--panel-2); }
.row-edit { display: inline-flex; align-items: center; justify-content: center; width: 24px; height: 24px; padding: 0; border: 1px solid transparent; border-radius: var(--radius-sm); color: var(--text-faint); background: transparent; font: inherit; cursor: pointer; transition: color 0.16s ease, border-color 0.16s ease, background-color 0.16s ease; }
.row-edit:hover { color: var(--accent-hover); border-color: var(--border-strong); background: var(--panel-2); }
.row-edit:focus-visible { outline: 2px solid var(--accent); outline-offset: 1px; }
.meta-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; }
.meta-hint { margin: 0; color: var(--text-faint); font-size: 11px; line-height: 1.5; }

.project-board__actions,
.workflow-overview,
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

.project-board__actions { display: flex; justify-content: flex-end; gap: 8px; }

.home-intro {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 24px;
  width: 100%;
  max-width: 1480px;
  margin-inline: auto;
  padding: 2px 0 6px;
}
.home-intro__copy { min-width: 0; }
.home-intro__eyebrow,
.attention-lane .section-label {
  margin: 0 0 7px;
  color: var(--accent-hover);
  font-family: var(--font-mono);
  font-size: 10px;
  font-weight: 800;
  letter-spacing: 0.1em;
  text-transform: uppercase;
}
.home-intro h2,
.attention-lane h2 {
  margin: 0;
  color: var(--text);
  font-size: clamp(22px, 2.4vw, 30px);
  font-weight: 790;
  letter-spacing: -0.04em;
  line-height: 1.12;
}
.home-intro__copy > p:last-child {
  max-width: 480px;
  margin: 8px 0 0;
  color: var(--text-muted);
  font-size: 12px;
  line-height: 1.55;
}
.home-intro__actions {
  display: flex;
  flex: none;
  gap: 8px;
}
.project-board__actions {
  align-items: center;
  justify-content: space-between;
  min-height: 30px;
}
.sync-note {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--text-faint);
  font-size: 11px;
}
.sync-note :deep(svg) { color: var(--success); }

.attention-lane {
  width: 100%;
  max-width: 1480px;
  margin-inline: auto;
  padding: 17px 0 0;
  border-top: 1px solid var(--border);
}
.attention-lane__head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 12px;
}
.attention-lane__head .section-label { margin-bottom: 4px; }
.attention-lane__head h2 { font-size: 18px; letter-spacing: -0.025em; }
.inline-link {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  padding: 0;
  border: 0;
  color: var(--accent-hover);
  background: transparent;
  font-size: 11px;
  font-weight: 750;
  cursor: pointer;
}
.inline-link:hover { color: var(--text); }
.attention-lane__items {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 9px;
}
.attention-item {
  display: grid;
  min-width: 0;
  overflow: hidden;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--panel);
  transition: border-color 0.15s ease, transform 0.15s ease, box-shadow 0.15s ease;
}
.attention-item:hover {
  border-color: var(--accent-border);
  box-shadow: var(--shadow-panel);
  transform: translateY(-1px);
}
.attention-item__main {
  display: grid;
  gap: 8px;
  min-width: 0;
  padding: 12px 12px 10px;
  border: 0;
  color: inherit;
  background: transparent;
  text-align: left;
  cursor: pointer;
}
.attention-item__topline {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  min-width: 0;
  color: var(--accent-hover);
  font-size: 10px;
  font-weight: 800;
}
.attention-item__topline :deep(.g-badge) { flex: none; }
.attention-item__main > strong {
  display: -webkit-box;
  overflow: hidden;
  color: var(--text);
  font-size: 12px;
  font-weight: 730;
  line-height: 1.45;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}
.attention-item__main > small {
  display: flex;
  gap: 6px;
  overflow: hidden;
  color: var(--text-muted);
  font-size: 10px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.attention-item__main > small span { color: var(--text-faint); }
.attention-item__action {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  min-height: 31px;
  padding: 0 12px;
  border: 0;
  border-top: 1px solid var(--border);
  color: var(--accent-hover);
  background: var(--panel-2);
  font-size: 10px;
  font-weight: 750;
  text-align: left;
  cursor: pointer;
}
.attention-item__action:hover { color: var(--text); background: var(--hover); }

.workflow-overview { display: flex; flex: none; align-items: center; justify-content: space-between; gap: 28px; min-height: 92px; padding: 14px 16px; border-top: 1px solid var(--border); border-bottom: 1px solid var(--border); background: color-mix(in srgb, var(--panel) 64%, transparent); }
.workflow-overview__lead { display: flex; align-items: center; gap: 11px; min-width: 240px; }
.workflow-overview__mark { display: grid; width: 36px; height: 36px; flex: none; place-items: center; border: 1px solid var(--border); border-radius: var(--radius-md); color: var(--accent); background: var(--accent-soft); }
.workflow-overview__lead > div { display: grid; gap: 2px; min-width: 0; }
.workflow-overview__lead span:not(.workflow-overview__mark) { color: var(--text-faint); font-size: 10px; font-weight: 750; }
.workflow-overview__lead strong { overflow: hidden; color: var(--text); font-size: 14px; text-overflow: ellipsis; white-space: nowrap; }
.workflow-overview__lead small { color: var(--text-muted); font-size: 10px; white-space: nowrap; }
.workflow-overview__metrics { display: grid; width: min(650px, 58%); grid-template-columns: repeat(4, minmax(92px, 1fr)); margin: 0; }
.workflow-overview__metrics > div { display: grid; grid-template-columns: 1fr auto; gap: 2px 10px; min-width: 0; padding: 2px 16px; border-left: 1px solid var(--border); }
.workflow-overview__metrics dt { color: var(--text-muted); font-size: 10px; font-weight: 700; }
.workflow-overview__metrics dd { grid-column: 2; grid-row: 1 / span 2; margin: 0; align-self: center; color: var(--text); font-family: var(--font-mono); font-size: 18px; font-weight: 750; }
.workflow-overview__metrics small { overflow: hidden; color: var(--text-faint); font-size: 9px; text-overflow: ellipsis; white-space: nowrap; }
.workflow-overview__metric--attention dt,
.workflow-overview__metric--attention dd { color: var(--warning); }

.project-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.view-tabs { display: flex; align-items: center; gap: 2px; padding: 3px; border: 1px solid var(--border); border-radius: var(--radius-md); background: var(--panel-2); }
.view-tabs button { display: inline-flex; align-items: center; gap: 6px; min-height: 30px; padding: 0 10px; border: 0; border-radius: 5px; color: var(--text-muted); background: transparent; font-size: 11px; font-weight: 700; cursor: pointer; }
.view-tabs button:hover { color: var(--text); background: var(--hover); }
.view-tabs button.active { color: var(--accent-hover); background: var(--panel); box-shadow: var(--shadow-panel); }
.project-toolbar__tools { display: flex; align-items: center; gap: 8px; min-width: min(440px, 100%); }
.project-toolbar__tools :deep(.g-input) { flex: 1 1 0; min-width: 0; min-height: 34px; font-size: 12px; }

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
.project-row:focus-visible { outline: 2px solid var(--accent); outline-offset: -2px; }
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

.project-empty--wrap { min-height: 220px; display: grid; place-content: center; }
.project-empty { display: grid; justify-items: center; gap: 8px; min-height: 220px; place-content: center; color: var(--text-muted); text-align: center; }
.project-empty :deep(svg) { color: var(--border-strong); }
.project-empty strong { color: var(--text); font-size: 14px; }
.project-empty span { max-width: 300px; font-size: 12px; }
.project-empty--inline { min-height: 150px; }
.project-skeleton { display: grid; grid-template-columns: 2fr 1fr 1fr 100px 20px; gap: 14px; align-items: center; min-height: 86px; padding: 14px 16px; border-bottom: 1px solid var(--border); background: var(--panel); }
.project-skeleton span { height: 12px; border-radius: 4px; background: var(--skeleton-base); animation: pulse 1.2s ease-in-out infinite alternate; }
.project-skeleton span:first-child { height: 28px; }
@keyframes pulse { from { opacity: .52; } to { opacity: 1; } }

@media (max-width: 900px) {
  .home-intro { align-items: flex-start; flex-direction: column; }
  .home-intro__actions { width: 100%; }
  .home-intro__actions :deep(.g-btn) { flex: 1; }
  .workflow-overview, .project-toolbar { align-items: flex-start; flex-direction: column; }
  .project-board__actions, .project-toolbar__tools { width: 100%; }
  .project-board__actions { align-items: center; flex-direction: row; }
  .project-toolbar__tools :deep(.g-input) { flex: 1; }
  .workflow-overview__metrics { width: 100%; }
  .attention-lane__items { grid-template-columns: repeat(2, minmax(0, 1fr)); }
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
  .project-board__actions { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); }
  .project-board__actions { display: flex; }
  .sync-note { min-width: 0; }
  .sync-note :deep(svg) { flex: none; }
  .home-intro__actions { display: grid; grid-template-columns: 1fr 1fr; }
  .project-board__actions :deep(.g-btn) { width: 100%; }
  .workflow-overview { gap: 13px; padding: 12px; }
  .workflow-overview__lead strong { white-space: normal; }
  .workflow-overview__lead small { white-space: normal; }
  .workflow-overview__metrics { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .workflow-overview__metrics > div { min-height: 46px; padding: 5px 10px; }
  .workflow-overview__metrics > div:nth-child(odd) { border-left: 0; }
  .workflow-overview__metrics > div:nth-child(n + 3) { border-top: 1px solid var(--border); }
  .view-tabs { width: 100%; overflow-x: auto; }
  .view-tabs button { flex: 1; justify-content: center; white-space: nowrap; }
  .project-toolbar__tools { align-items: stretch; flex-direction: column; }
  .project-toolbar__tools :deep(.g-btn) { justify-content: flex-start; }
  .project-row { padding: 13px; }
  .project-row__status small { max-width: 170px; }
  .project-detail-strip { padding-inline: 3px; }
  .project-detail-strip__head { align-items: flex-start; flex-direction: column; }
  .attention-lane__head { align-items: flex-start; flex-direction: column; }
  .attention-lane__items { grid-template-columns: 1fr; }
}

@media (prefers-reduced-motion: reduce) {
  .project-row, .project-skeleton span { transition: none; animation: none; }
}
</style>
