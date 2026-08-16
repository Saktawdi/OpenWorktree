<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { getTicket } from '@/api/tickets';
import { GBadge, GButton, GCard, GIcon, GInput, GModal } from '@/components/ui';
import { mockAgents, mockSessions } from '@/mocks/prototypeData';
import type { Session } from '@/types/session';
import type { Ticket } from '@/types/ticket';
import { TICKET_STAGE_LABELS } from '@/types/stage';
import type { TicketStage } from '@/types/stage';

type AppIcon = 'chat' | 'chevron-left' | 'comment' | 'external' | 'folder' | 'git-branch' | 'plus' | 'shield' | 'spark';
type DecisionIntent = 'approve' | 'reject' | 'human';
type DecisionState = 'pending' | 'reviewing' | 'approved' | 'rejected' | 'human' | 'published';
type WorkAction = 'start' | 'presubmit' | 'review' | 'publish' | 'resume' | 'decision' | 'none';

interface WorkActivity {
  id: string;
  type: 'work' | 'review' | 'session' | 'decision';
  title: string;
  detail: string;
  time: string;
}

interface ContextItem {
  label: string;
  value: string;
  copyValue: string | null;
  icon: AppIcon;
}

const route = useRoute();
const router = useRouter();
const no = String(route.params.no ?? 'T-104');
const ticket = ref<Ticket>({
  no,
  title: '读取工单中...',
  stage: 'PENDING',
  targetRef: '',
  reviewRound: null,
  treeHash: null,
  baseCommit: null,
  execTokenTotal: null,
  execTokenSource: null,
  agentConfigId: null,
  createdAt: '',
  updatedAt: '',
});
const loading = ref(true);
const loadError = ref('');

const localSessions = ref<Session[]>(
  mockSessions.filter((session) => session.ticketNo === ticket.value.no).map((session) => ({ ...session })),
);
const selectedSessionId = ref(localSessions.value[0]?.id ?? '');
const showDecision = ref(false);
const decisionIntent = ref<DecisionIntent>('approve');
const decisionNote = ref('');
const copiedLabel = ref('');
const actionNotice = ref('');

const decisionState = ref<DecisionState>(initialDecisionState(ticket.value.stage));
const activities = ref<WorkActivity[]>([
  {
    id: 'anchor',
    type: 'review',
    title: ticket.value.treeHash ? '最近一次预提审已锚定' : '尚未建立审核锚点',
    detail: ticket.value.treeHash
      ? `tree ${ticket.value.treeHash} 已绑定到 ${ticket.value.targetRef}`
      : '执行预提审后将生成可审核的 tree 锚点。',
    time: formatTime(ticket.value.updatedAt),
  },
  {
    id: 'session',
    type: 'session',
    title: localSessions.value.length ? `已关联 ${localSessions.value.length} 个执行会话` : '尚未关联执行会话',
    detail: localSessions.value.length ? '可直接续接最近的 Agent 上下文。' : '新建会话后可将任务交给指定 Agent。',
    time: formatTime(ticket.value.createdAt),
  },
]);

const lifecycle = [
  { stage: 'PENDING' as const, label: '待开始', note: '建立执行上下文' },
  { stage: 'IN_PROGRESS' as const, label: '执行中', note: '准备变更与证据' },
  { stage: 'PRESUBMITTED' as const, label: '已预提审', note: '冻结 tree 锚点' },
  { stage: 'IN_REVIEW' as const, label: '审核中', note: '记录审核决定' },
  { stage: 'READY_TO_PUBLISH' as const, label: '可发布', note: '等待发布动作' },
  { stage: 'DONE' as const, label: '已完成', note: '闭环留痕' },
];

const assignedAgent = computed(() =>
  mockAgents.find((agent) => agent.id === ticket.value.agentConfigId) ?? null,
);
const ticketSessions = computed(() => localSessions.value.filter((session) => session.ticketNo === ticket.value.no));
const selectedSession = computed(() =>
  ticketSessions.value.find((session) => session.id === selectedSessionId.value) ?? ticketSessions.value[0] ?? null,
);
const isTerminal = computed(() => ['DONE', 'CANCELLED'].includes(ticket.value.stage));
const contextItems = computed<ContextItem[]>(() => [
  { label: '工作分支', value: ticket.value.branch ?? '未建立', copyValue: ticket.value.branch ?? null, icon: 'git-branch' },
  { label: '目标引用', value: ticket.value.targetRef, copyValue: ticket.value.targetRef, icon: 'external' },
  { label: 'tree 锚点', value: ticket.value.treeHash ?? '未建立', copyValue: ticket.value.treeHash, icon: 'shield' },
  { label: '基线提交', value: ticket.value.baseCommit ?? '未建立', copyValue: ticket.value.baseCommit, icon: 'folder' },
]);
const diffPreview = computed(
  () => `+ <TreeHashBar :tree-hash="${ticket.value.treeHash ?? 'pending'}" />\n- <DiffViewer :diff="diff" />\n+ <DiffViewer :diff="diff" :anchor="treeHash" />`,
);
const nextAction = computed(() => actionForStage(ticket.value.stage));

const decisionMeta: Record<DecisionState, { label: string; detail: string; tone: 'neutral' | 'accent' | 'success' | 'warning' | 'danger' }> = {
  pending: { label: '待审核决定', detail: '当前没有可执行的审批结论。', tone: 'neutral' },
  reviewing: { label: '审核进行中', detail: '请在审核台确认 diff 与锚点后记录决定。', tone: 'accent' },
  approved: { label: '已批准发布', detail: '发布动作会将工单推进到完成。', tone: 'success' },
  rejected: { label: '已驳回修改', detail: '工单已回流到执行阶段。', tone: 'danger' },
  human: { label: '等待人工处理', detail: '需要明确的人工判断后才能继续。', tone: 'warning' },
  published: { label: '已完成闭环', detail: '发布记录与审核上下文均已保留。', tone: 'success' },
};

const activeDecision = computed(() => decisionMeta[decisionState.value]);

async function loadTicket() {
  loading.value = true;
  loadError.value = '';
  try {
    const loaded = await getTicket(no);
    ticket.value = {
      ...loaded,
      dependencies: [...(loaded.dependencies ?? [])],
      labels: [...(loaded.labels ?? [])],
    };
    localSessions.value = mockSessions.filter((session) => session.ticketNo === loaded.no).map((session) => ({ ...session }));
    selectedSessionId.value = localSessions.value[0]?.id ?? '';
    decisionState.value = initialDecisionState(loaded.stage);
    activities.value = [
      {
        id: 'anchor',
        type: 'review',
        title: loaded.treeHash ? '最近一次预提审已锚定' : '尚未建立审核锚点',
        detail: loaded.treeHash ? `tree ${loaded.treeHash} 已绑定到 ${loaded.targetRef}` : '执行预提审后将生成可审核的 tree 锚点。',
        time: formatTime(loaded.updatedAt),
      },
      {
        id: 'session',
        type: 'session',
        title: localSessions.value.length ? `已关联 ${localSessions.value.length} 个执行会话` : '尚未关联执行会话',
        detail: localSessions.value.length ? '可直接续接最近的 Agent 上下文。' : '新建会话后可将任务交给指定 Agent。',
        time: formatTime(loaded.createdAt),
      },
    ];
  } catch {
    loadError.value = '无法读取工单详情，请确认工单仍存在且 Gate 后端正在运行。';
    ticket.value.title = '工单详情不可用';
  } finally {
    loading.value = false;
  }
}

function initialDecisionState(stage: TicketStage): DecisionState {
  if (stage === 'DONE') return 'published';
  if (stage === 'READY_TO_PUBLISH') return 'approved';
  if (stage === 'REJECTED') return 'rejected';
  if (stage === 'NEEDS_HUMAN') return 'human';
  if (stage === 'IN_REVIEW' || stage === 'PRESUBMITTED') return 'reviewing';
  return 'pending';
}

function formatTime(iso: string) {
  const timestamp = Date.parse(iso);
  return Number.isFinite(timestamp)
    ? new Date(timestamp).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', hour12: false })
    : '--';
}

function formatTokens(value: number | null | undefined) {
  if (value == null) return '未回写';
  return new Intl.NumberFormat('zh-CN').format(value);
}

function stageTone(stage: TicketStage): 'neutral' | 'accent' | 'success' | 'warning' | 'danger' {
  if (stage === 'REJECTED' || stage === 'CANCELLED') return 'danger';
  if (stage === 'DONE' || stage === 'READY_TO_PUBLISH') return 'success';
  if (stage === 'NEEDS_HUMAN') return 'warning';
  if (stage === 'PRESUBMITTED' || stage === 'IN_REVIEW') return 'accent';
  return 'neutral';
}

function priorityTone(priority?: Ticket['priority']): 'neutral' | 'accent' | 'success' | 'warning' | 'danger' {
  if (priority === 'P0') return 'danger';
  if (priority === 'P1') return 'warning';
  if (priority === 'P2') return 'accent';
  return 'neutral';
}

function lifecycleState(stage: TicketStage) {
  const current = normalisedLifecycleStage(ticket.value.stage);
  const currentIndex = lifecycle.findIndex((item) => item.stage === current);
  const itemIndex = lifecycle.findIndex((item) => item.stage === stage);
  if (ticket.value.stage === 'CANCELLED') return 'pending';
  if (ticket.value.stage === 'REJECTED' && stage === 'IN_PROGRESS') return 'blocked';
  if (ticket.value.stage === 'NEEDS_HUMAN' && stage === 'IN_REVIEW') return 'blocked';
  if (itemIndex < currentIndex) return 'complete';
  if (itemIndex === currentIndex) return 'current';
  return 'pending';
}

function normalisedLifecycleStage(stage: TicketStage) {
  if (stage === 'REJECTED') return 'IN_PROGRESS';
  if (stage === 'NEEDS_HUMAN') return 'IN_REVIEW';
  if (stage === 'CANCELLED') return 'PENDING';
  return stage;
}

function actionForStage(stage: TicketStage): { kind: WorkAction; label: string; title: string; detail: string; icon: AppIcon } {
  switch (stage) {
    case 'PENDING':
      return { kind: 'start', label: '开始执行', title: '先建立执行上下文', detail: '进入执行阶段后，可新建或关联 Agent 会话。', icon: 'spark' };
    case 'IN_PROGRESS':
      return { kind: 'presubmit', label: '提交预审', title: '变更准备好了吗？', detail: '预审会冻结 tree 锚点，并将工单交给审核流程。', icon: 'shield' };
    case 'PRESUBMITTED':
      return { kind: 'review', label: '进入审核台', title: '审核锚点已建立', detail: '在审核台检查 diff、证据与可发布性。', icon: 'shield' };
    case 'IN_REVIEW':
      return { kind: 'review', label: '打开审核台', title: '等待审核结论', detail: '记录通过、驳回或人工处理决定。', icon: 'shield' };
    case 'READY_TO_PUBLISH':
      return { kind: 'publish', label: '确认发布', title: '审批已通过', detail: '发布后工单将完成闭环，并保留审计记录。', icon: 'spark' };
    case 'REJECTED':
      return { kind: 'resume', label: '恢复执行', title: '审核需要修改', detail: '回到执行阶段后，可从最近会话继续处理。', icon: 'spark' };
    case 'NEEDS_HUMAN':
      return { kind: 'decision', label: '记录人工决定', title: '需要人工判断', detail: '选择通过、驳回或继续等待人工处理。', icon: 'shield' };
    case 'DONE':
      return { kind: 'none', label: '工单已完成', title: '交付已闭环', detail: '所有关键上下文仍可在本页继续查看。', icon: 'shield' };
    case 'CANCELLED':
      return { kind: 'none', label: '工单已取消', title: '流程已停止', detail: '若需重新处理，请从看板创建新的工单。', icon: 'shield' };
  }
}

function addActivity(type: WorkActivity['type'], title: string, detail: string) {
  activities.value.unshift({
    id: `${type}-${Date.now()}`,
    type,
    title,
    detail,
    time: formatTime(new Date().toISOString()),
  });
}

function updateStage(stage: TicketStage, title: string, detail: string) {
  ticket.value.stage = stage;
  ticket.value.updatedAt = new Date().toISOString();
  addActivity('work', title, detail);
  actionNotice.value = title;
}

function performPrimaryAction() {
  switch (nextAction.value.kind) {
    case 'start':
      updateStage('IN_PROGRESS', '工单已进入执行阶段', '现在可以关联 Agent 会话并开始处理变更。');
      break;
    case 'presubmit':
      ticket.value.treeHash = ticket.value.treeHash ?? 'local-a7c91e...c4';
      ticket.value.baseCommit = ticket.value.baseCommit ?? 'local-b7d20f...91';
      ticket.value.reviewRound = Math.max(1, ticket.value.reviewRound ?? 0);
      decisionState.value = 'reviewing';
      updateStage('PRESUBMITTED', '已生成预审锚点', `第 ${ticket.value.reviewRound} 轮审核可从此 tree 锚点开始。`);
      break;
    case 'review':
      if (ticket.value.stage === 'PRESUBMITTED') {
        updateStage('IN_REVIEW', '已进入审核阶段', '审核台已接收当前预审锚点。');
      }
      router.push({ name: 'review', params: { no: ticket.value.no } });
      break;
    case 'publish':
      decisionState.value = 'published';
      updateStage('DONE', '发布已记录', '工单已完成，审核与会话上下文已保留。');
      break;
    case 'resume':
      decisionState.value = 'pending';
      updateStage('IN_PROGRESS', '工单已恢复执行', '请根据审核意见继续修改并重新预审。');
      break;
    case 'decision':
      openDecision('human');
      break;
    case 'none':
      break;
  }
}

function openDecision(intent: DecisionIntent) {
  if (isTerminal.value) return;
  decisionIntent.value = intent;
  decisionNote.value = '';
  showDecision.value = true;
}

function confirmDecision() {
  const note = decisionNote.value.trim();
  if (decisionIntent.value === 'approve') {
    decisionState.value = 'approved';
    updateStage('READY_TO_PUBLISH', '审核决定为通过', note || '工单已进入可发布状态。');
  } else if (decisionIntent.value === 'reject') {
    decisionState.value = 'rejected';
    updateStage('REJECTED', '审核决定为驳回', note || '工单已回流至执行阶段。');
  } else {
    decisionState.value = 'human';
    updateStage('NEEDS_HUMAN', '已转交人工处理', note || '等待人工记录最终处理决定。');
  }
  showDecision.value = false;
}

function startSession() {
  const agent = assignedAgent.value ?? mockAgents[0]!;
  const id = `sess-local-${ticket.value.no.toLowerCase()}-${localSessions.value.length + 1}`;
  localSessions.value.unshift({
    id,
    ticketNo: ticket.value.no,
    agentConfigId: agent.id,
    cli: agent.cli,
    status: 'ACTIVE',
    cliSessionId: null,
    clonePath: `local/${ticket.value.no}`,
    allocatedPort: agent.cli === 'OPENCODE' ? 51000 + localSessions.value.length : -1,
    startedAt: new Date().toISOString(),
    finishedAt: null,
    cumulativeUsage: { promptTokens: 0, completionTokens: 0, totalTokens: 0 },
  });
  selectedSessionId.value = id;
  addActivity('session', '已创建新的执行会话', `会话 ${id} 已关联到 ${agent.name}。`);
  actionNotice.value = '新的执行会话已关联';
}

function openSession() {
  router.push({ name: 'session', params: { no: ticket.value.no } });
}

async function copyContext(label: string, value: string | null) {
  if (!value) return;
  try {
    if (!navigator.clipboard) throw new Error('clipboard unavailable');
    await navigator.clipboard.writeText(value);
    copiedLabel.value = label;
    actionNotice.value = `已复制${label}`;
  } catch {
    actionNotice.value = '当前环境无法访问剪贴板，请手动复制。';
  }
}

onMounted(() => void loadTicket());
</script>

<template>
  <div class="detail-workbench">
    <header class="workbench-head">
      <GButton variant="ghost" size="sm" class="back-button" @click="router.push({ name: 'kanban' })">
        <GIcon name="chevron-left" :size="15" />
        返回看板
      </GButton>

      <div class="ticket-heading">
        <div class="ticket-heading__line">
          <span class="ticket-heading__no mono">{{ ticket.no }}</span>
          <GBadge :tone="stageTone(ticket.stage)">{{ TICKET_STAGE_LABELS[ticket.stage] }}</GBadge>
          <GBadge v-if="ticket.priority" :tone="priorityTone(ticket.priority)">{{ ticket.priority }}</GBadge>
        </div>
        <h2>{{ ticket.title }}</h2>
      </div>

      <div class="head-actions">
        <GButton variant="secondary" size="sm" @click="openSession">
          <GIcon name="chat" :size="14" />
          会话
        </GButton>
        <GButton variant="secondary" size="sm" @click="router.push({ name: 'review', params: { no: ticket.no } })">
          <GIcon name="shield" :size="14" />
          审核台
        </GButton>
      </div>
    </header>

    <p v-if="loading" class="detail-data-state" role="status">正在读取后端工单数据...</p>
    <p v-else-if="loadError" class="detail-data-state detail-data-state--error" role="alert">
      {{ loadError }}
      <button type="button" @click="loadTicket">重新读取</button>
    </p>

    <p class="sr-notice" role="status" aria-live="polite">{{ actionNotice }}</p>

    <section class="ticket-overview" aria-label="工单概览">
      <div class="ticket-overview__summary">
        <p class="section-kicker">DELIVERY WORKBENCH</p>
        <p class="ticket-overview__lede">
          {{ ticket.project ?? '本地项目' }}
          <span aria-hidden="true">/</span>
          {{ ticket.branch ?? ticket.targetRef }}
        </p>
        <div class="meta-list">
          <span><GIcon name="folder" :size="13" />{{ ticket.project ?? '未指定项目' }}</span>
          <span><GIcon name="git-branch" :size="13" />{{ ticket.dependencies?.length ?? 0 }} 个依赖</span>
          <span><GIcon name="comment" :size="13" />{{ ticket.commentCount ?? 0 }} 条讨论</span>
          <span>更新于 {{ formatTime(ticket.updatedAt) }}</span>
        </div>
      </div>
      <div class="ticket-overview__labels" aria-label="工单标签">
        <GBadge v-for="label in ticket.labels" :key="label" tone="neutral">{{ label }}</GBadge>
      </div>
    </section>

    <div class="workbench-grid">
      <section class="workbench-main" aria-label="工单流程和上下文">
        <GCard class="lifecycle-card">
          <template #head>
            <div class="card-heading">
              <span class="card-heading__eyebrow">LIFECYCLE</span>
              <strong>交付流程</strong>
            </div>
            <GBadge :tone="stageTone(ticket.stage)">{{ TICKET_STAGE_LABELS[ticket.stage] }}</GBadge>
          </template>

          <ol class="lifecycle" aria-label="工单当前生命周期">
            <li
              v-for="step in lifecycle"
              :key="step.stage"
              class="lifecycle__item"
              :class="'lifecycle__item--' + lifecycleState(step.stage)"
              :aria-current="lifecycleState(step.stage) === 'current' ? 'step' : undefined"
            >
              <span class="lifecycle__index" aria-hidden="true">{{ lifecycle.indexOf(step) + 1 }}</span>
              <strong>{{ step.label }}</strong>
              <small>{{ step.note }}</small>
            </li>
          </ol>

          <div class="next-action">
            <div class="next-action__icon" aria-hidden="true"><GIcon :name="nextAction.icon" :size="19" /></div>
            <div class="next-action__copy">
              <span>下一步</span>
              <strong>{{ nextAction.title }}</strong>
              <p>{{ nextAction.detail }}</p>
            </div>
            <GButton
              variant="primary"
              :disabled="nextAction.kind === 'none'"
              @click="performPrimaryAction"
            >
              {{ nextAction.label }}
            </GButton>
          </div>
        </GCard>

        <GCard class="context-card">
          <template #head>
            <div class="card-heading">
              <span class="card-heading__eyebrow">CONTEXT</span>
              <strong>可复用上下文</strong>
            </div>
            <span class="context-card__hint">复制后可粘贴到审核或会话</span>
          </template>

          <div class="context-grid">
            <div v-for="item in contextItems" :key="item.label" class="context-item">
              <div class="context-item__head">
                <span><GIcon :name="item.icon" :size="13" />{{ item.label }}</span>
                <GButton
                  variant="ghost"
                  size="sm"
                  :disabled="!item.copyValue"
                  :aria-label="`复制${item.label}`"
                  @click="copyContext(item.label, item.copyValue)"
                >
                  {{ copiedLabel === item.label ? '已复制' : '复制' }}
                </GButton>
              </div>
              <code class="mono">{{ item.value }}</code>
            </div>
          </div>

          <details class="change-preview">
            <summary>
              <span>查看本次变更锚点</span>
              <span class="change-preview__summary">ReviewConsoleView.vue</span>
            </summary>
            <pre class="mono">{{ diffPreview }}</pre>
          </details>
        </GCard>

        <GCard class="activity-card">
          <template #head>
            <div class="card-heading">
              <span class="card-heading__eyebrow">RECENT ACTIVITY</span>
              <strong>最近动作</strong>
            </div>
            <span class="activity-card__count">{{ activities.length }} 条</span>
          </template>

          <ol class="activity-list">
            <li v-for="activity in activities" :key="activity.id" class="activity-list__item">
              <span class="activity-list__marker" :class="'activity-list__marker--' + activity.type" aria-hidden="true" />
              <div>
                <strong>{{ activity.title }}</strong>
                <p>{{ activity.detail }}</p>
              </div>
              <time class="mono">{{ activity.time }}</time>
            </li>
          </ol>
        </GCard>
      </section>

      <aside class="workbench-side" aria-label="审批和会话">
        <GCard class="decision-card">
          <template #head>
            <div class="card-heading">
              <span class="card-heading__eyebrow">DECISION</span>
              <strong>审批决定</strong>
            </div>
            <GBadge :tone="activeDecision.tone">{{ activeDecision.label }}</GBadge>
          </template>

          <p class="decision-card__copy">{{ activeDecision.detail }}</p>
          <div class="decision-card__round">
            <span>审核轮次</span>
            <strong>R{{ ticket.reviewRound ?? 0 }}</strong>
          </div>
          <div class="decision-actions" aria-label="记录审批决定">
            <GButton variant="success" size="sm" :disabled="isTerminal" @click="openDecision('approve')">通过</GButton>
            <GButton variant="danger" size="sm" :disabled="isTerminal" @click="openDecision('reject')">驳回</GButton>
            <GButton variant="secondary" size="sm" :disabled="isTerminal" @click="openDecision('human')">转人工</GButton>
          </div>
        </GCard>

        <GCard class="session-card">
          <template #head>
            <div class="card-heading">
              <span class="card-heading__eyebrow">SESSION</span>
              <strong>执行会话</strong>
            </div>
            <GButton variant="ghost" size="sm" aria-label="新建执行会话" @click="startSession">
              <GIcon name="plus" :size="14" />
              新建
            </GButton>
          </template>

          <template v-if="selectedSession">
            <div class="session-highlight">
              <div>
                <span class="session-highlight__agent">{{ assignedAgent?.name ?? selectedSession.agentConfigId }}</span>
                <span class="session-highlight__id mono">{{ selectedSession.id }}</span>
              </div>
              <GBadge :tone="selectedSession.status === 'ACTIVE' ? 'success' : 'neutral'">
                {{ selectedSession.status === 'ACTIVE' ? '进行中' : '已结束' }}
              </GBadge>
            </div>
            <div class="session-metrics">
              <div><span>累计 Token</span><strong class="mono">{{ formatTokens(selectedSession.cumulativeUsage?.totalTokens) }}</strong></div>
              <div><span>执行器</span><strong>{{ selectedSession.cli }}</strong></div>
            </div>
            <div v-if="ticketSessions.length > 1" class="session-switcher" aria-label="选择关联会话">
              <button
                v-for="session in ticketSessions"
                :key="session.id"
                type="button"
                :class="{ active: session.id === selectedSession.id }"
                :aria-pressed="session.id === selectedSession.id"
                @click="selectedSessionId = session.id"
              >
                <span class="mono">{{ session.id }}</span>
                <span>{{ session.status === 'ACTIVE' ? '进行中' : '已结束' }}</span>
              </button>
            </div>
            <GButton variant="secondary" block class="session-card__open" @click="openSession">
              <GIcon name="chat" :size="14" />
              续接会话
            </GButton>
          </template>
          <div v-else class="session-empty">
            <GIcon name="chat" :size="18" aria-hidden="true" />
            <strong>还没有执行会话</strong>
            <p>建立会话后，Agent 会带着本工单的上下文开始处理。</p>
            <GButton variant="secondary" size="sm" @click="startSession">创建首个会话</GButton>
          </div>
        </GCard>

        <GCard class="assignment-card">
          <template #head>
            <div class="card-heading">
              <span class="card-heading__eyebrow">ASSIGNMENT</span>
              <strong>执行责任</strong>
            </div>
          </template>
          <div class="assignment-card__content">
            <span class="assignment-card__avatar" aria-hidden="true">{{ (assignedAgent?.name ?? '系统').slice(0, 1) }}</span>
            <div>
              <strong>{{ assignedAgent?.name ?? '系统默认 Agent' }}</strong>
              <p>{{ assignedAgent?.model ?? '尚未指定模型配置' }}</p>
            </div>
          </div>
        </GCard>
      </aside>
    </div>

    <GModal :show="showDecision" title="记录审批决定" width="500px" @close="showDecision = false">
      <div class="decision-modal">
        <p>选择本次审核的处理方式。决定会立即反映到本页的生命周期和活动记录中。</p>
        <div class="decision-options" role="group" aria-label="审批决定类型">
          <button type="button" :class="{ active: decisionIntent === 'approve' }" :aria-pressed="decisionIntent === 'approve'" @click="decisionIntent = 'approve'">通过并准备发布</button>
          <button type="button" :class="{ active: decisionIntent === 'reject' }" :aria-pressed="decisionIntent === 'reject'" @click="decisionIntent = 'reject'">驳回并回流执行</button>
          <button type="button" :class="{ active: decisionIntent === 'human' }" :aria-pressed="decisionIntent === 'human'" @click="decisionIntent = 'human'">转交人工处理</button>
        </div>
        <label class="decision-note">
          <span>决定说明（可选）</span>
          <GInput v-model="decisionNote" type="textarea" :rows="3" placeholder="记录能帮助下一位处理者理解决定的上下文" />
        </label>
      </div>
      <template #footer>
        <GButton variant="ghost" @click="showDecision = false">取消</GButton>
        <GButton variant="primary" @click="confirmDecision">确认决定</GButton>
      </template>
    </GModal>
  </div>
</template>

<style scoped>
.detail-workbench {
  flex: 1;
  min-height: 0;
  width: min(100%, 1480px);
  margin: 0 auto;
  padding: clamp(20px, 3vw, 38px) clamp(18px, 3.4vw, 52px) 32px;
  overflow: auto;
  color: var(--text);
}

.detail-data-state {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 14px 0 -2px;
  padding: 9px 12px;
  border: 1px solid var(--border);
  color: var(--text-muted);
  background: var(--panel-2);
  font-size: 12px;
}

.detail-data-state--error {
  border-color: var(--danger-soft);
  color: var(--danger);
  background: var(--danger-soft);
}

.detail-data-state button {
  padding: 0;
  border: 0;
  color: var(--accent-hover);
  background: transparent;
  font-size: inherit;
  font-weight: 750;
  cursor: pointer;
}

.workbench-head {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: start;
  gap: 16px;
}

.back-button {
  margin-top: 2px;
}

.ticket-heading {
  min-width: 0;
}

.ticket-heading__line,
.head-actions,
.meta-list,
.ticket-overview__labels,
.decision-actions,
.context-item__head,
.session-highlight {
  display: flex;
  align-items: center;
}

.ticket-heading__line {
  flex-wrap: wrap;
  gap: 7px;
}

.ticket-heading__no {
  color: var(--accent-hover);
  font-size: 12px;
  font-weight: 800;
  letter-spacing: 0.05em;
}

.ticket-heading h2 {
  margin: 7px 0 0;
  color: var(--ink);
  font-size: clamp(23px, 3vw, 34px);
  font-weight: 790;
  letter-spacing: -0.045em;
  line-height: 1.14;
}

.head-actions {
  justify-content: flex-end;
  gap: 8px;
}

.head-actions :deep(.g-btn) {
  border-radius: 9px;
}

.sr-notice {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0 0 0 0);
  white-space: nowrap;
}

.ticket-overview {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 20px;
  margin-top: 28px;
  padding: 18px 20px;
  border: 1px solid var(--border);
  border-left: 3px solid var(--accent);
  border-radius: 14px;
  background: var(--panel-2);
  box-shadow: 0 8px 22px rgba(36, 52, 70, 0.05);
}

.section-kicker,
.card-heading__eyebrow {
  margin: 0;
  color: var(--accent-hover);
  font-family: var(--font-mono);
  font-size: 10px;
  font-weight: 800;
  letter-spacing: 0.12em;
}

.ticket-overview__lede {
  margin: 5px 0 0;
  color: var(--ink);
  font-family: var(--font-mono);
  font-size: 13px;
  font-weight: 700;
}

.ticket-overview__lede span {
  margin: 0 6px;
  color: var(--text-faint);
}

.meta-list {
  flex-wrap: wrap;
  gap: 8px 15px;
  margin-top: 13px;
  color: var(--text-muted);
  font-size: 11px;
}

.meta-list span {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.ticket-overview__labels {
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 6px;
}

.workbench-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.38fr) minmax(300px, 0.62fr);
  gap: 18px;
  margin-top: 18px;
}

.workbench-main,
.workbench-side {
  display: grid;
  align-content: start;
  gap: 18px;
  min-width: 0;
}

.workbench-main > :deep(.g-card),
.workbench-side > :deep(.g-card) {
  border-radius: 14px;
  border-color: var(--border);
  background: var(--panel);
  box-shadow: 0 9px 24px rgba(36, 52, 70, 0.055);
}

.workbench-main > :deep(.g-card) :deep(.g-card__head),
.workbench-side > :deep(.g-card) :deep(.g-card__head) {
  min-height: 58px;
  padding: 13px 17px;
}

.card-heading {
  display: grid;
  gap: 3px;
  min-width: 0;
}

.card-heading strong {
  color: var(--ink);
  font-size: 14px;
  font-weight: 760;
}

.lifecycle-card :deep(.g-card__body) {
  padding: 17px;
}

.lifecycle {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  gap: 7px;
  padding: 0;
  margin: 0;
  list-style: none;
}

.lifecycle__item {
  position: relative;
  min-height: 102px;
  padding: 12px 10px 11px;
  border: 1px solid var(--border);
  border-radius: 10px;
  background: var(--panel-2);
  color: var(--text-muted);
}

.lifecycle__item:not(:last-child)::after {
  position: absolute;
  top: 23px;
  right: -7px;
  z-index: 1;
  width: 7px;
  height: 1px;
  background: var(--border-strong);
  content: '';
}

.lifecycle__index {
  display: grid;
  width: 22px;
  height: 22px;
  place-items: center;
  margin-bottom: 12px;
  border: 1px solid var(--border-strong);
  border-radius: 50%;
  color: var(--text-muted);
  background: var(--panel);
  font-family: var(--font-mono);
  font-size: 10px;
  font-weight: 800;
}

.lifecycle__item strong,
.lifecycle__item small {
  display: block;
}

.lifecycle__item strong {
  color: inherit;
  font-size: 12px;
  font-weight: 760;
}

.lifecycle__item small {
  margin-top: 4px;
  color: inherit;
  font-size: 10px;
  line-height: 1.45;
}

.lifecycle__item--complete {
  border-color: rgba(44, 137, 98, 0.24);
  color: #277755;
  background: var(--success-soft);
}

.lifecycle__item--complete .lifecycle__index {
  border-color: #2c8962;
  color: #fff;
  background: #2c8962;
}

.lifecycle__item--current {
  border-color: rgba(9, 105, 218, 0.46);
  color: var(--accent-hover);
  background: var(--accent-soft);
  box-shadow: inset 0 0 0 1px rgba(9, 105, 218, 0.12);
}

.lifecycle__item--current .lifecycle__index {
  border-color: var(--accent);
  color: var(--accent-contrast);
  background: var(--accent);
}

.lifecycle__item--blocked {
  border-color: rgba(184, 121, 33, 0.33);
  color: #98651a;
  background: var(--warning-soft);
}

.lifecycle__item--blocked .lifecycle__index {
  border-color: var(--warning);
  color: #fff;
  background: var(--warning);
}

.next-action {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: 12px;
  margin-top: 15px;
  padding: 13px;
  border: 1px solid rgba(9, 105, 218, 0.24);
  border-radius: 11px;
  background: var(--accent-soft);
}

.next-action__icon {
  display: grid;
  width: 37px;
  height: 37px;
  place-items: center;
  border-radius: 10px;
  color: var(--accent-hover);
  background: var(--accent-soft);
}

.next-action__copy {
  min-width: 0;
}

.next-action__copy > span {
  display: block;
  color: var(--accent-hover);
  font-size: 10px;
  font-weight: 800;
  letter-spacing: 0.08em;
}

.next-action__copy strong {
  display: block;
  margin-top: 2px;
  color: var(--ink);
  font-size: 13px;
}

.next-action__copy p {
  margin: 2px 0 0;
  color: var(--text-muted);
  font-size: 11px;
  line-height: 1.45;
}

.next-action :deep(.g-btn) {
  min-width: 108px;
  border-radius: 9px;
}

.context-card__hint,
.activity-card__count {
  color: var(--text-muted);
  font-size: 11px;
}

.context-card :deep(.g-card__body) {
  padding: 17px;
}

.context-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 9px;
}

.context-item {
  min-width: 0;
  padding: 10px 11px;
  border: 1px solid var(--border);
  border-radius: 10px;
  background: var(--panel-2);
}

.context-item__head {
  justify-content: space-between;
  gap: 10px;
  color: var(--text-muted);
  font-size: 10px;
  font-weight: 760;
}

.context-item__head > span {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.context-item__head :deep(.g-btn) {
  min-height: 25px;
  padding-inline: 7px;
  color: var(--accent-hover);
  font-size: 10px;
}

.context-item code {
  display: block;
  margin-top: 7px;
  overflow: hidden;
  color: var(--ink);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.change-preview {
  margin-top: 12px;
  border: 1px solid var(--border);
  border-radius: 10px;
  background: var(--panel);
}

.change-preview summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  padding: 11px 12px;
  color: var(--text-secondary);
  font-size: 12px;
  font-weight: 700;
  cursor: pointer;
}

.change-preview__summary {
  min-width: 0;
  overflow: hidden;
  color: var(--text-muted);
  font-family: var(--font-mono);
  font-size: 10px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.change-preview pre {
  margin: 0;
  padding: 12px;
  overflow: auto;
  border-top: 1px solid var(--border);
  color: var(--text-secondary);
  background: var(--panel-2);
  font-size: 11px;
  line-height: 1.7;
}

.activity-card :deep(.g-card__body) {
  padding: 5px 17px 9px;
}

.activity-list {
  padding: 0;
  margin: 0;
  list-style: none;
}

.activity-list__item {
  position: relative;
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  gap: 10px;
  align-items: start;
  padding: 12px 0;
  border-bottom: 1px solid var(--border);
}

.activity-list__item:last-child {
  border-bottom: 0;
}

.activity-list__marker {
  width: 8px;
  height: 8px;
  margin-top: 6px;
  border-radius: 50%;
  background: var(--border-strong);
}

.activity-list__marker--work { background: var(--accent); }
.activity-list__marker--review { background: var(--warning); }
.activity-list__marker--session { background: var(--success); }
.activity-list__marker--decision { background: var(--danger); }

.activity-list strong {
  display: block;
  color: var(--text-secondary);
  font-size: 12px;
}

.activity-list p {
  margin: 3px 0 0;
  color: var(--text-muted);
  font-size: 11px;
  line-height: 1.5;
}

.activity-list time {
  color: var(--text-faint);
  font-size: 10px;
}

.decision-card :deep(.g-card__body),
.session-card :deep(.g-card__body),
.assignment-card :deep(.g-card__body) {
  padding: 17px;
}

.decision-card__copy {
  min-height: 42px;
  margin: 0;
  color: var(--text-secondary);
  font-size: 12px;
  line-height: 1.6;
}

.decision-card__round {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 14px;
  padding: 10px 11px;
  border: 1px solid var(--border);
  border-radius: 9px;
  background: var(--panel-2);
}

.decision-card__round span {
  color: var(--text-muted);
  font-size: 11px;
}

.decision-card__round strong {
  color: var(--accent-hover);
  font-family: var(--font-mono);
  font-size: 13px;
}

.decision-actions {
  flex-wrap: wrap;
  gap: 7px;
  margin-top: 14px;
}

.decision-actions :deep(.g-btn) {
  flex: 1 1 78px;
  border-radius: 8px;
}

.session-card :deep(.g-card__head) :deep(.g-btn) {
  color: var(--accent-hover);
}

.session-highlight {
  justify-content: space-between;
  gap: 12px;
}

.session-highlight__agent,
.session-highlight__id {
  display: block;
}

.session-highlight__agent {
  color: var(--ink);
  font-size: 13px;
  font-weight: 760;
}

.session-highlight__id {
  margin-top: 3px;
  color: var(--text-muted);
  font-size: 10px;
}

.session-metrics {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
  margin-top: 14px;
}

.session-metrics > div {
  padding: 9px 10px;
  border: 1px solid var(--border);
  border-radius: 9px;
  background: var(--panel-2);
}

.session-metrics span,
.session-metrics strong {
  display: block;
}

.session-metrics span {
  color: var(--text-muted);
  font-size: 10px;
}

.session-metrics strong {
  margin-top: 4px;
  overflow: hidden;
  color: var(--text-secondary);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.session-switcher {
  display: grid;
  gap: 5px;
  margin-top: 13px;
}

.session-switcher button {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  width: 100%;
  padding: 8px 9px;
  border: 1px solid transparent;
  border-radius: 8px;
  color: var(--text-muted);
  background: transparent;
  font-size: 10px;
  text-align: left;
  cursor: pointer;
}

.session-switcher button:hover,
.session-switcher button.active {
  border-color: rgba(9, 105, 218, 0.26);
  color: var(--accent-hover);
  background: var(--accent-soft);
}

.session-card__open {
  margin-top: 13px;
  border-radius: 9px;
}

.session-empty {
  display: grid;
  justify-items: start;
  gap: 7px;
  padding: 3px 0 1px;
}

.session-empty :deep(svg) {
  color: var(--accent-hover);
}

.session-empty strong {
  color: var(--text-secondary);
  font-size: 13px;
}

.session-empty p {
  margin: 0 0 3px;
  color: var(--text-muted);
  font-size: 11px;
  line-height: 1.55;
}

.session-empty :deep(.g-btn) {
  border-radius: 8px;
}

.assignment-card__content {
  display: flex;
  align-items: center;
  gap: 11px;
}

.assignment-card__avatar {
  display: grid;
  width: 34px;
  height: 34px;
  place-items: center;
  border: 1px solid rgba(9, 105, 218, 0.24);
  border-radius: 11px;
  color: var(--accent-hover);
  background: var(--accent-soft);
  font-size: 14px;
  font-weight: 800;
}

.assignment-card strong {
  display: block;
  color: var(--text-secondary);
  font-size: 12px;
}

.assignment-card p {
  max-width: 240px;
  margin: 3px 0 0;
  overflow: hidden;
  color: var(--text-muted);
  font-family: var(--font-mono);
  font-size: 10px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.decision-modal {
  display: grid;
  gap: 15px;
}

.decision-modal > p {
  margin: 0;
  color: var(--text-secondary);
  font-size: 13px;
  line-height: 1.6;
}

.decision-options {
  display: grid;
  gap: 7px;
}

.decision-options button {
  width: 100%;
  padding: 10px 12px;
  border: 1px solid var(--border);
  border-radius: 9px;
  color: var(--text-secondary);
  background: var(--panel-2);
  font-size: 12px;
  font-weight: 700;
  text-align: left;
  cursor: pointer;
}

.decision-options button:hover,
.decision-options button.active {
  border-color: rgba(9, 105, 218, 0.42);
  color: var(--accent-hover);
  background: var(--accent-soft);
}

.decision-note {
  display: grid;
  gap: 7px;
}

.decision-note > span {
  color: var(--text-secondary);
  font-size: 12px;
  font-weight: 700;
}

.decision-note :deep(.g-input) {
  min-height: 96px;
  border-radius: 9px;
}

@media (max-width: 1080px) {
  .workbench-grid {
    grid-template-columns: 1fr;
  }

  .workbench-side {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .assignment-card {
    grid-column: 1 / -1;
  }
}

@media (max-width: 760px) {
  .detail-workbench {
    padding: 17px 14px 24px;
  }

  .workbench-head {
    grid-template-columns: auto minmax(0, 1fr);
    gap: 12px;
  }

  .head-actions {
    grid-column: 1 / -1;
    justify-content: stretch;
  }

  .head-actions :deep(.g-btn) {
    flex: 1;
  }

  .ticket-overview {
    align-items: flex-start;
    flex-direction: column;
    gap: 14px;
    margin-top: 20px;
  }

  .ticket-overview__labels {
    justify-content: flex-start;
  }

  .lifecycle {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }

  .lifecycle__item:not(:last-child)::after {
    display: none;
  }

  .workbench-side {
    grid-template-columns: 1fr;
  }

  .assignment-card {
    grid-column: auto;
  }
}

@media (max-width: 520px) {
  .ticket-heading h2 {
    font-size: 24px;
  }

  .ticket-overview {
    padding: 15px;
  }

  .lifecycle-card :deep(.g-card__body),
  .context-card :deep(.g-card__body),
  .decision-card :deep(.g-card__body),
  .session-card :deep(.g-card__body),
  .assignment-card :deep(.g-card__body) {
    padding: 14px;
  }

  .lifecycle {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .lifecycle__item {
    min-height: 93px;
  }

  .next-action {
    grid-template-columns: auto minmax(0, 1fr);
  }

  .next-action :deep(.g-btn) {
    grid-column: 1 / -1;
    width: 100%;
  }

  .context-grid {
    grid-template-columns: 1fr;
  }

  .context-card__hint {
    display: none;
  }

  .activity-list__item {
    grid-template-columns: auto minmax(0, 1fr);
  }

  .activity-list time {
    grid-column: 2;
  }
}
</style>
