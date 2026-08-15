<script setup lang="ts">
import { computed, ref } from 'vue';
import { useRouter } from 'vue-router';
import { GAvatar, GBadge, GButton, GIcon, GInput, GModal, GSelect } from '@/components/ui';
import { mockTickets, stageLabels } from '@/mocks/prototypeData';
import type { Ticket } from '@/types/ticket';
import type { TicketStage } from '@/types/stage';

type WorkspaceView = 'workspace' | 'records';
type WorkspaceLane = 'inbox' | 'execution' | 'review' | 'archive';

interface WorkspaceLaneDefinition {
  id: WorkspaceLane;
  label: string;
  description: string;
  stages: TicketStage[];
}

interface NextStep {
  label: string;
  target: TicketStage;
  description: string;
}

const router = useRouter();
const view = ref<WorkspaceView>('workspace');
const workspaceLane = ref<WorkspaceLane>('inbox');
const tickets = ref<Ticket[]>([...mockTickets]);
const query = ref('');
const selectedNo = ref<string | null>(null);
const selectedIds = ref<Set<string>>(new Set());
const bulkStage = ref<TicketStage | null>(null);
const activityMessage = ref('');
const showCreate = ref(false);
const newNo = ref('');
const newTitle = ref('');
const newAgent = ref<string | null>(null);
const createError = ref('');

const stageOrder: TicketStage[] = [
  'PENDING',
  'IN_PROGRESS',
  'PRESUBMITTED',
  'IN_REVIEW',
  'REJECTED',
  'READY_TO_PUBLISH',
  'NEEDS_HUMAN',
  'DONE',
  'CANCELLED',
];

const lanes: WorkspaceLaneDefinition[] = [
  {
    id: 'inbox',
    label: '待处理',
    description: '等待启动、需要人工判断或需要重新处理的工单。',
    stages: ['PENDING', 'NEEDS_HUMAN', 'REJECTED'],
  },
  {
    id: 'execution',
    label: '执行',
    description: '正在由 Agent 或人工推进的工作。',
    stages: ['IN_PROGRESS'],
  },
  {
    id: 'review',
    label: '审核',
    description: '已预提审、审核中与准备发布的交付物。',
    stages: ['PRESUBMITTED', 'IN_REVIEW', 'READY_TO_PUBLISH'],
  },
  {
    id: 'archive',
    label: '归档',
    description: '已完成或已取消的历史工单。',
    stages: ['DONE', 'CANCELLED'],
  },
];

const stageOptions = stageOrder.map((stage) => ({
  label: stageLabels[stage],
  value: stage,
}));

const nextStepByStage: Record<TicketStage, NextStep> = {
  PENDING: { label: '开始执行', target: 'IN_PROGRESS', description: '创建执行上下文并进入处理队列。' },
  IN_PROGRESS: { label: '提交预审', target: 'PRESUBMITTED', description: '固化当前产出，准备进入审核流程。' },
  PRESUBMITTED: { label: '进入审核', target: 'IN_REVIEW', description: '将预提审结果交给审核台处理。' },
  IN_REVIEW: { label: '通过审核', target: 'READY_TO_PUBLISH', description: '审核结论已明确，可以等待发布确认。' },
  REJECTED: { label: '重新执行', target: 'IN_PROGRESS', description: '带着审核反馈回到执行队列。' },
  READY_TO_PUBLISH: { label: '确认发布', target: 'DONE', description: '确认交付已经合并或发布完成。' },
  NEEDS_HUMAN: { label: '继续执行', target: 'IN_PROGRESS', description: '人工判断完成后，恢复正常执行。' },
  DONE: { label: '重新打开', target: 'IN_PROGRESS', description: '需要补充工作时，重新进入执行队列。' },
  CANCELLED: { label: '重新打开', target: 'IN_PROGRESS', description: '恢复已取消的任务并重新安排执行。' },
};

const normalizedQuery = computed(() => query.value.trim().toLowerCase());
const currentLane = computed<WorkspaceLaneDefinition>(
  () => lanes.find((lane) => lane.id === workspaceLane.value) ?? lanes[0]!,
);
const activeCount = computed(() => tickets.value.filter((ticket) => !['DONE', 'CANCELLED'].includes(ticket.stage)).length);
const reviewCount = computed(() => tickets.value.filter((ticket) => ['PRESUBMITTED', 'IN_REVIEW', 'READY_TO_PUBLISH'].includes(ticket.stage)).length);
const readyCount = computed(() => tickets.value.filter((ticket) => ticket.stage === 'READY_TO_PUBLISH').length);
const selectedCount = computed(() => selectedIds.value.size);

const queueTickets = computed(() =>
  tickets.value
    .filter((ticket) => laneFor(ticket.stage) === workspaceLane.value && matchesQuery(ticket))
    .sort((left, right) => Date.parse(right.updatedAt) - Date.parse(left.updatedAt)),
);

const records = computed(() =>
  tickets.value
    .filter(matchesQuery)
    .sort((left, right) => Date.parse(right.updatedAt) - Date.parse(left.updatedAt)),
);

const activeTicket = computed<Ticket | null>(
  () => queueTickets.value.find((ticket) => ticket.no === selectedNo.value) ?? queueTickets.value[0] ?? null,
);

const activeNextStep = computed<NextStep | null>(() =>
  activeTicket.value ? nextStepByStage[activeTicket.value.stage] : null,
);

const allQueueSelected = computed(
  () => queueTickets.value.length > 0 && queueTickets.value.every((ticket) => selectedIds.value.has(ticket.no)),
);

const selectedInQueue = computed(() => queueTickets.value.filter((ticket) => selectedIds.value.has(ticket.no)).length);

function matchesQuery(ticket: Ticket) {
  if (!normalizedQuery.value) return true;
  const haystack = [ticket.no, ticket.title, ticket.project, ticket.branch, ...(ticket.labels ?? [])]
    .filter(Boolean)
    .join(' ')
    .toLowerCase();
  return haystack.includes(normalizedQuery.value);
}

function laneFor(stage: TicketStage): WorkspaceLane {
  return lanes.find((lane) => lane.stages.includes(stage))?.id ?? 'inbox';
}

function laneCount(lane: WorkspaceLane) {
  return tickets.value.filter((ticket) => laneFor(ticket.stage) === lane).length;
}

function toneFor(stage: TicketStage): 'neutral' | 'accent' | 'success' | 'warning' | 'danger' {
  if (stage === 'REJECTED' || stage === 'CANCELLED') return 'danger';
  if (stage === 'DONE' || stage === 'READY_TO_PUBLISH') return 'success';
  if (stage === 'NEEDS_HUMAN') return 'warning';
  if (stage === 'PRESUBMITTED' || stage === 'IN_REVIEW') return 'accent';
  return 'neutral';
}

function agentName(ticket: Ticket) {
  if (!ticket.agentConfigId) return '人工';
  return ticket.agentConfigId.includes('claude') ? 'Claude' : 'OpenCode';
}

function formatDate(value: string) {
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(new Date(value));
}

function formatToken(value: number | null) {
  return value == null ? '--' : new Intl.NumberFormat('zh-CN').format(value);
}

function chooseLane(lane: WorkspaceLane) {
  workspaceLane.value = lane;
  selectedNo.value = null;
}

function chooseTicket(no: string) {
  selectedNo.value = no;
}

function toggleTicketSelection(no: string, checked: boolean) {
  const next = new Set(selectedIds.value);
  if (checked) next.add(no);
  else next.delete(no);
  selectedIds.value = next;
  selectedNo.value = no;
}

function onTicketSelectionChange(no: string, event: Event) {
  toggleTicketSelection(no, (event.target as HTMLInputElement).checked);
}

function toggleAllQueue(checked: boolean) {
  const next = new Set(selectedIds.value);
  queueTickets.value.forEach((ticket) => {
    if (checked) next.add(ticket.no);
    else next.delete(ticket.no);
  });
  selectedIds.value = next;
  if (checked && queueTickets.value[0]) selectedNo.value = queueTickets.value[0].no;
}

function onSelectAllChange(event: Event) {
  toggleAllQueue((event.target as HTMLInputElement).checked);
}

function clearSelection() {
  selectedIds.value = new Set();
  bulkStage.value = null;
}

function setBulkStage(value: string | null) {
  bulkStage.value = value && stageOrder.includes(value as TicketStage) ? (value as TicketStage) : null;
}

function moveTicket(no: string, target: TicketStage) {
  const ticket = tickets.value.find((item) => item.no === no);
  if (!ticket || ticket.stage === target) return;

  const previous = ticket.stage;
  ticket.stage = target;
  ticket.updatedAt = new Date().toISOString();
  workspaceLane.value = laneFor(target);
  selectedNo.value = ticket.no;
  activityMessage.value = `${ticket.no} 已从${stageLabels[previous]}转入${stageLabels[target]}。`;
}

function moveActiveToNextStep() {
  if (!activeTicket.value || !activeNextStep.value) return;
  moveTicket(activeTicket.value.no, activeNextStep.value.target);
}

function updateActiveStage(value: string | null) {
  if (!value || !activeTicket.value || !stageOrder.includes(value as TicketStage)) return;
  moveTicket(activeTicket.value.no, value as TicketStage);
}

function applyBulkStage() {
  if (!bulkStage.value || selectedIds.value.size === 0) return;

  const selected = tickets.value.filter((ticket) => selectedIds.value.has(ticket.no));
  const changed = selected.filter((ticket) => ticket.stage !== bulkStage.value);
  if (changed.length === 0) {
    activityMessage.value = `选中的工单已经处于${stageLabels[bulkStage.value]}。`;
    return;
  }

  const target = bulkStage.value;
  changed.forEach((ticket) => {
    ticket.stage = target;
    ticket.updatedAt = new Date().toISOString();
  });
  workspaceLane.value = laneFor(target);
  selectedNo.value = changed[0]!.no;
  selectedIds.value = new Set();
  bulkStage.value = null;
  activityMessage.value = `已将 ${changed.length} 个工单转入${stageLabels[target]}。`;
}

function open(no: string) {
  router.push({ name: 'ticket-detail', params: { no } });
}

function suggestedTicketNo() {
  const max = tickets.value.reduce((current, ticket) => {
    const match = /^T-(\d+)$/.exec(ticket.no);
    return match ? Math.max(current, Number(match[1])) : current;
  }, 100);
  return `T-${max + 1}`;
}

function openCreate() {
  newNo.value = suggestedTicketNo();
  newTitle.value = '';
  newAgent.value = null;
  createError.value = '';
  showCreate.value = true;
}

function closeCreate() {
  showCreate.value = false;
  createError.value = '';
}

function create() {
  createError.value = '';
  if (!newTitle.value.trim()) {
    createError.value = '请填写工单标题';
    return;
  }

  const no = newNo.value.trim() || suggestedTicketNo();
  if (tickets.value.some((ticket) => ticket.no === no)) {
    createError.value = '工单号已存在，请换一个编号';
    return;
  }

  const timestamp = new Date().toISOString();
  tickets.value.unshift({
    no,
    title: newTitle.value.trim(),
    stage: 'IN_PROGRESS',
    targetRef: 'refs/heads/main',
    reviewRound: 0,
    treeHash: null,
    baseCommit: null,
    execTokenTotal: null,
    execTokenSource: null,
    agentConfigId: newAgent.value,
    createdAt: timestamp,
    updatedAt: timestamp,
  });
  view.value = 'workspace';
  workspaceLane.value = 'execution';
  query.value = '';
  selectedNo.value = no;
  selectedIds.value = new Set();
  activityMessage.value = `${no} 已创建，并放入执行队列。`;
  closeCreate();
}
</script>

<template>
  <div class="kanban-page">
    <header class="workspace-header">
      <div class="workspace-header__copy">
        <p class="section-kicker">工单调度</p>
        <h2>把注意力放在下一步。</h2>
        <p>按处理阶段切换队列，在同一处查看执行上下文并完成流转。</p>
      </div>

      <div class="workspace-header__actions">
        <div class="view-toggle" role="tablist" aria-label="工单视图">
          <button
            id="workspace-tab"
            type="button"
            role="tab"
            aria-controls="ticket-workspace"
            :aria-selected="view === 'workspace'"
            :tabindex="view === 'workspace' ? 0 : -1"
            :class="{ active: view === 'workspace' }"
            @click="view = 'workspace'"
          >
            <GIcon name="kanban" :size="14" />
            工作区
          </button>
          <button
            id="records-tab"
            type="button"
            role="tab"
            aria-controls="ticket-records"
            :aria-selected="view === 'records'"
            :tabindex="view === 'records' ? 0 : -1"
            :class="{ active: view === 'records' }"
            @click="view = 'records'"
          >
            <GIcon name="grid" :size="14" />
            记录
          </button>
        </div>
        <GInput
          v-model="query"
          class="board-search"
          aria-label="搜索工单、项目、分支或标签"
          placeholder="搜索工单、项目或标签"
        />
        <GButton variant="primary" @click="openCreate">
          <GIcon name="plus" :size="15" />
          新建工单
        </GButton>
      </div>
    </header>

    <div class="summary-strip" aria-label="工单摘要">
      <span><strong>{{ activeCount }}</strong> 活跃工单</span>
      <span><strong>{{ reviewCount }}</strong> 审核流转</span>
      <span><strong>{{ readyCount }}</strong> 待发布</span>
      <span class="summary-strip__total">{{ tickets.length }} 个工单</span>
    </div>

    <p v-if="activityMessage" class="activity-message" role="status">{{ activityMessage }}</p>

    <section
      v-if="view === 'workspace'"
      id="ticket-workspace"
      class="workspace-panel"
      role="tabpanel"
      aria-labelledby="workspace-tab"
    >
      <nav class="lane-nav" aria-label="工单分流队列">
        <button
          v-for="lane in lanes"
          :key="lane.id"
          type="button"
          class="lane-nav__item"
          :class="{ 'lane-nav__item--active': workspaceLane === lane.id }"
          :aria-current="workspaceLane === lane.id ? 'page' : undefined"
          @click="chooseLane(lane.id)"
        >
          <span class="lane-nav__label">{{ lane.label }}</span>
          <span class="lane-nav__count">{{ laneCount(lane.id) }}</span>
          <span class="lane-nav__description">{{ lane.description }}</span>
        </button>
      </nav>

      <div class="workspace-grid">
        <section class="queue-panel" aria-labelledby="queue-heading">
          <div class="queue-panel__head">
            <div>
              <p class="queue-panel__label">当前队列</p>
              <h3 id="queue-heading">{{ currentLane.label }}</h3>
              <p>{{ currentLane.description }}</p>
            </div>
            <label v-if="queueTickets.length" class="select-all">
              <input
                type="checkbox"
                :checked="allQueueSelected"
                :aria-label="'全选当前' + currentLane.label + '队列'"
                @change="onSelectAllChange"
              />
              <span>全选</span>
            </label>
          </div>

          <div v-if="selectedCount" class="bulk-bar" aria-label="批量工单操作">
            <div class="bulk-bar__copy">
              <strong>{{ selectedCount }}</strong>
              <span>个已选择</span>
              <small v-if="selectedInQueue && selectedInQueue !== selectedCount">当前队列 {{ selectedInQueue }} 个</small>
            </div>
            <label class="bulk-bar__field">
              <span>转入阶段</span>
              <GSelect
                :model-value="bulkStage"
                :options="stageOptions"
                placeholder="选择阶段"
                @update:model-value="setBulkStage"
              />
            </label>
            <div class="bulk-bar__actions">
              <GButton size="sm" variant="primary" :disabled="!bulkStage" @click="applyBulkStage">应用状态</GButton>
              <GButton size="sm" variant="ghost" @click="clearSelection">取消选择</GButton>
            </div>
          </div>

          <div v-if="queueTickets.length" class="queue-list">
            <article
              v-for="ticket in queueTickets"
              :key="ticket.no"
              class="queue-ticket"
              :class="{
                'queue-ticket--active': activeTicket?.no === ticket.no,
                'queue-ticket--selected': selectedIds.has(ticket.no),
              }"
            >
              <label class="ticket-checkbox" @click.stop>
                <input
                  type="checkbox"
                  :checked="selectedIds.has(ticket.no)"
                  :aria-label="'选择工单 ' + ticket.no"
                  @change="onTicketSelectionChange(ticket.no, $event)"
                />
              </label>
              <button
                type="button"
                class="queue-ticket__open"
                :aria-pressed="activeTicket?.no === ticket.no"
                @click="chooseTicket(ticket.no)"
              >
                <span class="queue-ticket__topline">
                  <span class="mono queue-ticket__no">{{ ticket.no }}</span>
                  <GBadge :tone="toneFor(ticket.stage)">{{ stageLabels[ticket.stage] }}</GBadge>
                  <span v-if="ticket.priority" class="priority" :class="'priority--' + ticket.priority.toLowerCase()">{{ ticket.priority }}</span>
                </span>
                <span class="queue-ticket__title">{{ ticket.title }}</span>
                <span class="queue-ticket__meta">
                  <span v-if="ticket.project" class="ticket-meta-item">
                    <GIcon name="folder" :size="12" />
                    {{ ticket.project }}
                  </span>
                  <span v-if="ticket.branch" class="ticket-meta-item mono">
                    <GIcon name="git-branch" :size="12" />
                    {{ ticket.branch }}
                  </span>
                  <span v-if="ticket.dependencies?.length" class="ticket-meta-item">
                    {{ ticket.dependencies.length }} 项依赖
                  </span>
                  <span class="ticket-meta-item">{{ formatDate(ticket.updatedAt) }}</span>
                </span>
              </button>
              <div class="queue-ticket__agent" :title="'执行者: ' + agentName(ticket)">
                <GAvatar :name="agentName(ticket)" :tone="ticket.agentConfigId ? 'accent' : 'neutral'" />
                <span>{{ agentName(ticket) }}</span>
              </div>
            </article>
          </div>

          <div v-else class="empty-state">
            <GIcon name="search" :size="18" />
            <strong>{{ normalizedQuery ? '没有匹配的工单' : '这个队列暂时为空' }}</strong>
            <span>{{ normalizedQuery ? '换一个关键词，或清除搜索后再试。' : '可以新建工单，或切换到其他队列。' }}</span>
          </div>
        </section>

        <aside class="context-panel" aria-labelledby="context-heading">
          <template v-if="activeTicket">
            <div class="context-panel__head">
              <div>
                <p class="queue-panel__label">当前上下文</p>
                <span class="mono context-panel__no">{{ activeTicket.no }}</span>
                <h3 id="context-heading">{{ activeTicket.title }}</h3>
              </div>
              <GBadge :tone="toneFor(activeTicket.stage)">{{ stageLabels[activeTicket.stage] }}</GBadge>
            </div>

            <section v-if="activeNextStep" class="next-step">
              <p>建议下一步</p>
              <strong>{{ activeNextStep.label }}</strong>
              <span>{{ activeNextStep.description }}</span>
              <GButton variant="primary" block @click="moveActiveToNextStep">
                <GIcon name="spark" :size="15" />
                {{ activeNextStep.label }}
              </GButton>
            </section>

            <label class="stage-control">
              <span>直接调整阶段</span>
              <GSelect
                :model-value="activeTicket.stage"
                :options="stageOptions"
                @update:model-value="updateActiveStage"
              />
            </label>

            <dl class="context-facts">
              <div>
                <dt>执行分支</dt>
                <dd class="mono">{{ activeTicket.branch || '--' }}</dd>
              </div>
              <div>
                <dt>目标引用</dt>
                <dd class="mono">{{ activeTicket.targetRef || '--' }}</dd>
              </div>
              <div>
                <dt>审核轮次</dt>
                <dd>R{{ activeTicket.reviewRound ?? 0 }}</dd>
              </div>
              <div>
                <dt>累计 Token</dt>
                <dd class="mono">{{ formatToken(activeTicket.execTokenTotal) }}</dd>
              </div>
              <div>
                <dt>Tree hash</dt>
                <dd class="mono">{{ activeTicket.treeHash || '--' }}</dd>
              </div>
              <div>
                <dt>Base commit</dt>
                <dd class="mono">{{ activeTicket.baseCommit || '--' }}</dd>
              </div>
            </dl>

            <section class="context-detail">
              <div>
                <span>执行者</span>
                <div class="context-agent">
                  <GAvatar :name="agentName(activeTicket)" size="md" :tone="activeTicket.agentConfigId ? 'accent' : 'neutral'" />
                  <div>
                    <strong>{{ agentName(activeTicket) }}</strong>
                    <small class="mono">{{ activeTicket.agentConfigId || 'manual' }}</small>
                  </div>
                </div>
              </div>
              <div>
                <span>依赖</span>
                <p>{{ activeTicket.dependencies?.length ? activeTicket.dependencies.join('、') : '--' }}</p>
              </div>
              <div>
                <span>标签</span>
                <p v-if="activeTicket.labels?.length" class="context-labels">
                  <b v-for="label in activeTicket.labels" :key="label">{{ label }}</b>
                </p>
                <p v-else>--</p>
              </div>
            </section>

            <div class="context-panel__actions">
              <GButton variant="secondary" block @click="open(activeTicket.no)">
                <GIcon name="external" :size="14" />
                查看详情
              </GButton>
            </div>
          </template>

          <div v-else class="empty-state empty-state--context">
            <GIcon name="kanban" :size="18" />
            <strong>没有可展示的上下文</strong>
            <span>选择一个有工单的队列，或新建工单开始处理。</span>
          </div>
        </aside>
      </div>
    </section>

    <section
      v-else
      id="ticket-records"
      class="records-panel"
      role="tabpanel"
      aria-labelledby="records-tab"
    >
      <div class="records-panel__head">
        <div>
          <p class="queue-panel__label">流转记录</p>
          <h3>按最近更新时间查看全部工单</h3>
          <p>记录视图仅用于回溯和定位，不在这里直接修改状态。</p>
        </div>
        <span class="records-panel__count">{{ records.length }} 条</span>
      </div>

      <div class="records-panel__table">
        <table class="g-table">
          <thead>
            <tr>
              <th>工单</th>
              <th>项目与分支</th>
              <th>阶段</th>
              <th>审核</th>
              <th>Token</th>
              <th>更新时间</th>
              <th><span class="sr-only">操作</span></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="ticket in records" :key="ticket.no">
              <td>
                <div class="record-ticket">
                  <span class="mono">{{ ticket.no }}</span>
                  <strong>{{ ticket.title }}</strong>
                </div>
              </td>
              <td>
                <div class="record-project">
                  <span>{{ ticket.project || '--' }}</span>
                  <small class="mono">{{ ticket.branch || '--' }}</small>
                </div>
              </td>
              <td><GBadge :tone="toneFor(ticket.stage)">{{ stageLabels[ticket.stage] }}</GBadge></td>
              <td class="mono">R{{ ticket.reviewRound ?? 0 }}</td>
              <td class="mono">{{ formatToken(ticket.execTokenTotal) }}</td>
              <td class="record-updated">{{ formatDate(ticket.updatedAt) }}</td>
              <td><GButton size="sm" variant="ghost" @click="open(ticket.no)">查看详情</GButton></td>
            </tr>
            <tr v-if="records.length === 0">
              <td colspan="7" class="table-empty">没有匹配的工单记录</td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <GModal :show="showCreate" title="新建工单" width="460px" @close="closeCreate">
      <div class="form">
        <label class="field"><span>工单号</span><GInput v-model="newNo" /></label>
        <label class="field"><span>标题</span><GInput v-model="newTitle" placeholder="例如：修复审核台锚定" /></label>
        <label class="field">
          <span>Agent 配置</span>
          <GSelect
            v-model="newAgent"
            :options="[
              { label: 'Claude Sonnet', value: 'claude-sonnet-default' },
              { label: 'OpenCode Default', value: 'opencode-default' },
            ]"
            placeholder="不使用 agent（人工）"
          />
        </label>
        <p v-if="createError" class="field-error" role="alert">{{ createError }}</p>
      </div>
      <template #footer>
        <GButton variant="ghost" @click="closeCreate">取消</GButton>
        <GButton variant="primary" @click="create">创建</GButton>
      </template>
    </GModal>
  </div>
</template>

<style scoped>
.kanban-page {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 16px;
  min-height: 0;
  padding: clamp(22px, 3vw, 36px) clamp(20px, 4vw, 58px) 30px;
  overflow: auto;
}

.workspace-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 24px;
}

.workspace-header__copy {
  min-width: 0;
}

.section-kicker,
.queue-panel__label {
  margin: 0 0 7px;
  color: var(--accent);
  font-size: 10px;
  font-weight: 800;
  letter-spacing: 0.12em;
  text-transform: uppercase;
}

.workspace-header h2,
.records-panel h3,
.queue-panel h3,
.context-panel h3 {
  margin: 0;
  color: var(--text);
  font-weight: 780;
  letter-spacing: -0.035em;
}

.workspace-header h2 {
  font-size: clamp(25px, 3vw, 35px);
}

.workspace-header__copy > p:last-child {
  max-width: 560px;
  margin: 8px 0 0;
  color: var(--text-muted);
  font-size: 13px;
}

.workspace-header__actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 9px;
  flex-wrap: wrap;
}

.board-search {
  width: 226px;
}

.board-search :deep(.g-input) {
  min-height: 36px;
  padding-block: 7px;
}

.view-toggle {
  display: inline-flex;
  gap: 2px;
  padding: 3px;
  border: 1px solid var(--border);
  border-radius: 10px;
  background: var(--panel);
}

.view-toggle button {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  min-height: 30px;
  padding: 0 10px;
  border: 0;
  border-radius: 7px;
  color: var(--text-muted);
  background: transparent;
  font-size: 12px;
  font-weight: 700;
  cursor: pointer;
  transition: background-color 0.16s ease, color 0.16s ease, transform 0.12s ease;
}

.view-toggle button:hover {
  color: var(--text);
  background: var(--hover);
}

.view-toggle button:active {
  transform: translateY(1px);
}

.view-toggle button.active {
  color: var(--text);
  background: var(--panel-2);
  box-shadow: 0 1px 2px rgba(23, 37, 61, 0.08);
}

.summary-strip {
  display: flex;
  align-items: center;
  gap: 15px;
  flex-wrap: wrap;
  color: var(--text-muted);
  font-size: 12px;
}

.summary-strip > span {
  display: inline-flex;
  align-items: baseline;
  gap: 5px;
}

.summary-strip strong {
  color: var(--text);
  font-family: var(--font-mono);
  font-size: 13px;
}

.summary-strip__total {
  margin-left: auto;
  color: var(--text-faint);
  font-family: var(--font-mono);
  font-size: 11px;
}

.activity-message {
  margin: -2px 0 0;
  padding: 9px 12px;
  border: 1px solid rgba(176, 75, 28, 0.24);
  border-radius: var(--radius-sm);
  color: var(--accent-hover);
  background: var(--accent-soft);
  font-size: 12px;
  font-weight: 650;
}

.workspace-panel {
  display: grid;
  gap: 12px;
  min-height: 0;
}

.lane-nav {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 9px;
}

.lane-nav__item {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 3px 10px;
  min-height: 94px;
  padding: 13px 14px;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  color: var(--text-secondary);
  background: rgba(255, 253, 250, 0.56);
  text-align: left;
  cursor: pointer;
  transition: border-color 0.16s ease, background-color 0.16s ease, box-shadow 0.16s ease, transform 0.12s ease;
}

.lane-nav__item:hover {
  border-color: var(--border-strong);
  background: var(--panel);
}

.lane-nav__item:active {
  transform: translateY(1px);
}

.lane-nav__item--active {
  border-color: rgba(176, 75, 28, 0.58);
  background: linear-gradient(135deg, rgba(176, 75, 28, 0.13), rgba(255, 253, 250, 0.86));
  box-shadow: 0 8px 22px rgba(101, 64, 36, 0.08);
}

.lane-nav__label {
  color: var(--text);
  font-size: 14px;
  font-weight: 760;
}

.lane-nav__count {
  display: inline-grid;
  min-width: 25px;
  height: 22px;
  place-items: center;
  padding: 0 6px;
  border-radius: 999px;
  color: var(--accent-hover);
  background: var(--accent-soft);
  font-family: var(--font-mono);
  font-size: 11px;
  font-weight: 750;
}

.lane-nav__description {
  grid-column: 1 / -1;
  color: var(--text-muted);
  font-size: 11px;
  line-height: 1.45;
}

.workspace-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.1fr) minmax(330px, 0.9fr);
  gap: 12px;
  align-items: start;
}

.queue-panel,
.context-panel,
.records-panel {
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  background: var(--panel);
  box-shadow: var(--shadow-panel);
}

.queue-panel {
  min-height: 500px;
  overflow: hidden;
}

.queue-panel__head,
.records-panel__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 18px;
  padding: 19px 20px 16px;
  border-bottom: 1px solid var(--border);
}

.queue-panel h3,
.records-panel h3,
.context-panel h3 {
  font-size: 19px;
}

.queue-panel__head > div > p:last-child,
.records-panel__head > div > p:last-child {
  max-width: 520px;
  margin: 6px 0 0;
  color: var(--text-muted);
  font-size: 12px;
}

.select-all {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding-top: 5px;
  color: var(--text-muted);
  font-size: 12px;
  font-weight: 650;
  white-space: nowrap;
  cursor: pointer;
}

.select-all input,
.ticket-checkbox input {
  width: 15px;
  height: 15px;
  margin: 0;
  accent-color: var(--accent);
  cursor: pointer;
}

.bulk-bar {
  display: grid;
  grid-template-columns: minmax(108px, auto) minmax(145px, 190px) auto;
  align-items: end;
  gap: 12px;
  padding: 11px 14px;
  border-bottom: 1px solid rgba(176, 75, 28, 0.2);
  background: rgba(176, 75, 28, 0.07);
}

.bulk-bar__copy {
  display: flex;
  align-items: baseline;
  gap: 4px;
  min-height: 38px;
  color: var(--text-secondary);
  font-size: 12px;
}

.bulk-bar__copy strong {
  color: var(--accent-hover);
  font-family: var(--font-mono);
  font-size: 17px;
}

.bulk-bar__copy small {
  margin-left: 4px;
  color: var(--text-faint);
  font-size: 10px;
}

.bulk-bar__field {
  display: grid;
  gap: 4px;
}

.bulk-bar__field > span,
.stage-control > span {
  color: var(--text-muted);
  font-size: 10px;
  font-weight: 750;
}

.bulk-bar__field :deep(.g-select) {
  min-height: 34px;
  padding-block: 6px;
  font-size: 12px;
}

.bulk-bar__actions {
  display: flex;
  gap: 4px;
}

.queue-list {
  display: grid;
}

.queue-ticket {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  gap: 9px;
  align-items: center;
  min-width: 0;
  padding: 10px 14px;
  border-bottom: 1px solid var(--border);
  background: transparent;
  transition: background-color 0.16s ease, box-shadow 0.16s ease;
}

.queue-ticket:last-child {
  border-bottom: 0;
}

.queue-ticket:hover {
  background: var(--hover);
}

.queue-ticket--active {
  background: linear-gradient(90deg, var(--accent-soft), transparent 78%);
  box-shadow: inset 3px 0 0 var(--accent);
}

.queue-ticket--selected:not(.queue-ticket--active) {
  background: rgba(176, 75, 28, 0.055);
}

.ticket-checkbox {
  display: inline-flex;
  align-items: center;
  padding: 5px 2px;
}

.queue-ticket__open {
  display: grid;
  gap: 5px;
  min-width: 0;
  padding: 2px 0;
  border: 0;
  color: inherit;
  background: transparent;
  text-align: left;
  cursor: pointer;
}

.queue-ticket__open:focus-visible {
  outline: 2px solid var(--accent);
  outline-offset: 3px;
  border-radius: 4px;
}

.queue-ticket__topline,
.queue-ticket__meta {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.queue-ticket__no {
  color: var(--accent-hover);
  font-size: 11px;
  font-weight: 750;
}

.queue-ticket__topline :deep(.g-badge) {
  margin-left: 2px;
}

.priority {
  padding: 2px 5px;
  border-radius: 5px;
  color: var(--text-muted);
  background: var(--panel-2);
  font-size: 9px;
  font-weight: 800;
}

.priority--p0 {
  color: var(--danger);
  background: var(--danger-soft);
}

.priority--p1 {
  color: var(--accent-hover);
  background: var(--accent-soft);
}

.queue-ticket__title {
  overflow: hidden;
  color: var(--text);
  font-size: 13px;
  font-weight: 720;
  line-height: 1.38;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.queue-ticket__meta {
  overflow: hidden;
  color: var(--text-faint);
  font-size: 10px;
}

.ticket-meta-item {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ticket-meta-item:not(:last-child)::after {
  width: 3px;
  height: 3px;
  margin-left: 2px;
  border-radius: 50%;
  background: var(--border-strong);
  content: "";
}

.queue-ticket__agent {
  display: grid;
  justify-items: center;
  gap: 2px;
  width: 45px;
  overflow: hidden;
  color: var(--text-faint);
  font-size: 9px;
  line-height: 1.2;
  text-align: center;
}

.queue-ticket__agent > span {
  max-width: 45px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.context-panel {
  position: sticky;
  top: 12px;
  overflow: hidden;
}

.context-panel__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 14px;
  padding: 19px 20px 16px;
  border-bottom: 1px solid var(--border);
}

.context-panel__no {
  display: block;
  margin-bottom: 5px;
  color: var(--accent-hover);
  font-size: 11px;
  font-weight: 750;
}

.next-step {
  display: grid;
  gap: 5px;
  margin: 16px 20px;
  padding: 15px;
  border: 1px solid rgba(176, 75, 28, 0.24);
  border-radius: var(--radius-md);
  background: linear-gradient(145deg, rgba(176, 75, 28, 0.13), rgba(255, 253, 250, 0.96));
}

.next-step > p {
  margin: 0;
  color: var(--accent-hover);
  font-size: 10px;
  font-weight: 800;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.next-step > strong {
  color: var(--text);
  font-size: 16px;
}

.next-step > span {
  margin-bottom: 7px;
  color: var(--text-muted);
  font-size: 12px;
  line-height: 1.5;
}

.stage-control {
  display: grid;
  gap: 6px;
  margin: 0 20px 16px;
}

.context-facts {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0;
  margin: 0;
  border-top: 1px solid var(--border);
}

.context-facts > div {
  min-width: 0;
  padding: 12px 20px;
  border-bottom: 1px solid var(--border);
}

.context-facts > div:nth-child(odd) {
  border-right: 1px solid var(--border);
}

.context-facts dt,
.context-detail > div > span {
  color: var(--text-faint);
  font-size: 10px;
  font-weight: 750;
}

.context-facts dd {
  margin: 4px 0 0;
  overflow: hidden;
  color: var(--text-secondary);
  font-size: 11px;
  font-weight: 650;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.context-detail {
  display: grid;
  gap: 13px;
  padding: 16px 20px;
}

.context-detail > div {
  display: grid;
  gap: 5px;
}

.context-detail p {
  margin: 0;
  color: var(--text-secondary);
  font-size: 12px;
  line-height: 1.5;
}

.context-agent {
  display: flex;
  align-items: center;
  gap: 8px;
}

.context-agent > div {
  display: grid;
  gap: 1px;
}

.context-agent strong {
  color: var(--text-secondary);
  font-size: 12px;
}

.context-agent small {
  color: var(--text-faint);
  font-size: 10px;
}

.context-labels {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
}

.context-labels b {
  padding: 3px 6px;
  border-radius: 5px;
  color: var(--ink-soft);
  background: rgba(26, 54, 82, 0.08);
  font-size: 10px;
  font-weight: 700;
}

.context-panel__actions {
  padding: 0 20px 20px;
}

.empty-state {
  display: grid;
  justify-items: center;
  gap: 8px;
  min-height: 220px;
  place-content: center;
  padding: 24px;
  color: var(--text-muted);
  text-align: center;
}

.empty-state > svg {
  color: var(--accent);
}

.empty-state strong {
  color: var(--text-secondary);
  font-size: 13px;
}

.empty-state span {
  max-width: 260px;
  color: var(--text-faint);
  font-size: 12px;
  line-height: 1.5;
}

.empty-state--context {
  min-height: 380px;
}

.records-panel {
  overflow: hidden;
}

.records-panel__count {
  flex: none;
  padding: 4px 8px;
  border-radius: 999px;
  color: var(--accent-hover);
  background: var(--accent-soft);
  font-family: var(--font-mono);
  font-size: 11px;
  font-weight: 750;
}

.records-panel__table {
  overflow: auto;
}

.g-table {
  width: 100%;
  min-width: 940px;
  border-collapse: collapse;
  font-size: 12px;
}

.g-table th {
  padding: 11px 14px;
  border-bottom: 1px solid var(--border);
  color: var(--text-muted);
  background: var(--panel-2);
  font-size: 10px;
  font-weight: 800;
  letter-spacing: 0.07em;
  text-align: left;
  text-transform: uppercase;
  white-space: nowrap;
}

.g-table td {
  padding: 13px 14px;
  border-bottom: 1px solid var(--border);
  color: var(--text-secondary);
  vertical-align: middle;
}

.g-table tbody tr:last-child td {
  border-bottom: 0;
}

.g-table tbody tr:hover {
  background: var(--hover);
}

.record-ticket,
.record-project {
  display: grid;
  gap: 3px;
}

.record-ticket > span {
  color: var(--accent-hover);
  font-size: 10px;
  font-weight: 750;
}

.record-ticket strong {
  max-width: 280px;
  overflow: hidden;
  color: var(--text);
  font-size: 12px;
  font-weight: 700;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.record-project > span {
  color: var(--text-secondary);
}

.record-project > small {
  color: var(--text-faint);
  font-size: 10px;
}

.record-updated {
  color: var(--text-muted) !important;
  font-family: var(--font-mono);
  font-size: 11px;
  white-space: nowrap;
}

.table-empty {
  padding: 34px 14px !important;
  color: var(--text-muted) !important;
  text-align: center;
}

.form {
  display: grid;
  gap: 15px;
}

.field {
  display: grid;
  gap: 7px;
}

.field > span {
  color: var(--text-secondary);
  font-size: 12px;
  font-weight: 700;
}

.field-error {
  margin: 0;
  color: var(--danger);
  font-size: 12px;
  font-weight: 650;
}

.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
  border: 0;
}

@media (max-width: 1080px) {
  .workspace-header {
    align-items: flex-start;
    flex-direction: column;
  }

  .workspace-header__actions {
    justify-content: flex-start;
    width: 100%;
  }
}

@media (max-width: 900px) {
  .workspace-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .context-panel {
    position: static;
  }
}

@media (max-width: 700px) {
  .kanban-page {
    padding-inline: 16px;
  }

  .lane-nav {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .lane-nav__item {
    min-height: 86px;
  }

  .bulk-bar {
    grid-template-columns: minmax(0, 1fr) minmax(136px, 0.8fr);
  }

  .bulk-bar__actions {
    grid-column: 1 / -1;
  }
}

@media (max-width: 560px) {
  .workspace-header__actions {
    align-items: stretch;
    flex-direction: column;
  }

  .board-search {
    width: 100%;
  }

  .workspace-header__actions :deep(.g-btn) {
    width: 100%;
  }

  .view-toggle {
    width: fit-content;
  }

  .summary-strip {
    gap: 9px 13px;
  }

  .summary-strip__total {
    width: 100%;
    margin-left: 0;
  }

  .queue-panel__head,
  .records-panel__head {
    gap: 10px;
    padding: 17px 16px 14px;
  }

  .queue-panel__head {
    align-items: flex-start;
    flex-direction: column;
  }

  .select-all {
    padding-top: 0;
  }

  .queue-ticket {
    grid-template-columns: auto minmax(0, 1fr);
    padding: 10px 12px;
  }

  .queue-ticket__agent {
    display: none;
  }

  .queue-ticket__title {
    white-space: normal;
  }

  .context-panel__head,
  .next-step,
  .stage-control {
    margin-inline: 16px;
  }

  .context-panel__head {
    margin: 0;
    padding: 17px 16px 14px;
  }

  .context-facts {
    grid-template-columns: minmax(0, 1fr);
  }

  .context-facts > div,
  .context-facts > div:nth-child(odd) {
    padding-inline: 16px;
    border-right: 0;
  }

  .context-detail,
  .context-panel__actions {
    padding-inline: 16px;
  }
}
</style>
