<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { getTicket, presubmitTicket, updateTicket } from '@/api/tickets';
import { listTicketSessions, startSession as apiStartSession } from '@/api/sessions';
import { listAgentConfigs } from '@/api/agentConfig';
import { GBadge, GButton, GCard, GField, GIcon, GInput, GModal, GSkeleton } from '@/components/ui';
import type { AgentConfig } from '@/types/agentConfig';
import type { Session } from '@/types/session';
import type { Ticket } from '@/types/ticket';
import { TICKET_STAGE_LABELS } from '@/types/stage';
import type { TicketStage } from '@/types/stage';

type AppIcon = 'chat' | 'chart' | 'chevron-left' | 'comment' | 'external' | 'folder' | 'git-branch' | 'plus' | 'shield' | 'spark';
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
const projectId = String(route.params.projectId ?? '');
const ticket = ref<Ticket>({
  no,
  title: '读取工单中...',
  description: null,
  note: null,
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
  labels: [],
});
const loading = ref(true);
const loadError = ref('');

const agentConfigs = ref<AgentConfig[]>([]);
const localSessions = ref<Session[]>([]);
const selectedSessionId = ref(localSessions.value[0]?.id ?? '');
const showDecision = ref(false);
const decisionIntent = ref<DecisionIntent>('approve');
const decisionNote = ref('');
const copiedLabel = ref('');
const actionNotice = ref('');
const showEdit = ref(false);
const savingEdit = ref(false);
const editError = ref('');
const editTitle = ref('');
const editDescription = ref('');
const editNote = ref('');
const editLabels = ref('');

const decisionState = ref<DecisionState>(initialDecisionState(ticket.value.stage));
const activities = ref<WorkActivity[]>([
  {
    id: 'anchor',
    type: 'review',
    title: ticket.value.treeHash ? '最近一次预提审已锚定' : '尚未建立审核锚点',
    detail: ticket.value.treeHash
      ? `树锚点 ${ticket.value.treeHash} 已绑定到 ${ticket.value.targetRef}`
      : '执行预提审后将生成可审核的树锚点。',
    time: formatTime(ticket.value.updatedAt),
  },
  {
    id: 'session',
    type: 'session',
    title: localSessions.value.length ? `已关联 ${localSessions.value.length} 个执行会话` : '尚未关联执行会话',
    detail: localSessions.value.length ? '可直接续接最近的智能体上下文。' : '新建会话后可将任务交给指定智能体。',
    time: formatTime(ticket.value.createdAt),
  },
]);

const lifecycle = [
  { stage: 'PENDING' as const, label: '待开始', note: '建立执行上下文' },
  { stage: 'IN_PROGRESS' as const, label: '执行中', note: '准备变更与证据' },
  { stage: 'PRESUBMITTED' as const, label: '已预提审', note: '冻结树锚点' },
  { stage: 'IN_REVIEW' as const, label: '审核中', note: '记录审核决定' },
  { stage: 'READY_TO_PUBLISH' as const, label: '可发布', note: '等待发布动作' },
  { stage: 'DONE' as const, label: '已完成', note: '闭环留痕' },
];

const assignedAgent = computed(() =>
  agentConfigs.value.find((agent) => agent.id === ticket.value.agentConfigId) ?? null,
);
const ticketSessions = computed(() => localSessions.value.filter((session) => session.ticketNo === ticket.value.no));
const selectedSession = computed(() =>
  ticketSessions.value.find((session) => session.id === selectedSessionId.value) ?? ticketSessions.value[0] ?? null,
);
const isTerminal = computed(() => ['DONE', 'CANCELLED'].includes(ticket.value.stage));
const contextItems = computed<ContextItem[]>(() => [
  { label: '项目', value: ticket.value.project ?? '--', copyValue: null, icon: 'folder' },
  { label: '执行分支', value: ticket.value.branch ?? '--', copyValue: ticket.value.branch ?? null, icon: 'git-branch' },
  { label: '目标引用', value: ticket.value.targetRef || '--', copyValue: ticket.value.targetRef || null, icon: 'external' },
  { label: '审核轮次', value: `R${ticket.value.reviewRound ?? 0}`, copyValue: null, icon: 'shield' },
  { label: '累计令牌', value: formatTokens(ticket.value.execTokenTotal), copyValue: null, icon: 'chart' },
  { label: '令牌来源', value: tokenSourceLabel(ticket.value.execTokenSource), copyValue: null, icon: 'spark' },
  { label: '树哈希', value: ticket.value.treeHash ?? '--', copyValue: ticket.value.treeHash, icon: 'shield' },
  { label: '基线提交', value: ticket.value.baseCommit ?? '--', copyValue: ticket.value.baseCommit, icon: 'folder' },
]);
const diffPreview = computed(
  () => `+ <TreeHashBar :tree-hash="${ticket.value.treeHash ?? 'pending'}" />\n- <DiffViewer :diff="diff" />\n+ <DiffViewer :diff="diff" :anchor="treeHash" />`,
);
const nextAction = computed(() => actionForStage(ticket.value.stage));

const decisionMeta: Record<DecisionState, { label: string; detail: string; tone: 'neutral' | 'accent' | 'success' | 'warning' | 'danger' }> = {
  pending: { label: '待审核决定', detail: '当前没有可执行的审批结论。', tone: 'neutral' },
  reviewing: { label: '审核进行中', detail: '请在审核台确认变更与锚点后记录决定。', tone: 'accent' },
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
    const [loaded, sessionRows] = await Promise.all([
      getTicket(no, projectId),
      listTicketSessions(no).catch(() => []),
    ]);
    ticket.value = {
      ...loaded,
      dependencies: [...(loaded.dependencies ?? [])],
      labels: [...(loaded.labels ?? [])],
    };
    localSessions.value = sessionRows;
    selectedSessionId.value = localSessions.value[0]?.id ?? '';
    decisionState.value = initialDecisionState(loaded.stage);
    activities.value = [
      {
        id: 'anchor',
        type: 'review',
        title: loaded.treeHash ? '最近一次预提审已锚定' : '尚未建立审核锚点',
        detail: loaded.treeHash ? `树锚点 ${loaded.treeHash} 已绑定到 ${loaded.targetRef}` : '执行预提审后将生成可审核的树锚点。',
        time: formatTime(loaded.updatedAt),
      },
      {
        id: 'session',
        type: 'session',
        title: localSessions.value.length ? `已关联 ${localSessions.value.length} 个执行会话` : '尚未关联执行会话',
        detail: localSessions.value.length ? '可直接续接最近的智能体上下文。' : '新建会话后可将任务交给指定智能体。',
        time: formatTime(loaded.createdAt),
      },
    ];
    void listAgentConfigs().then((configs) => { agentConfigs.value = configs; }).catch(() => { agentConfigs.value = []; });
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

function tokenSourceLabel(source: Ticket['execTokenSource']) {
  if (source === 'agent_cli') return '智能体命令行';
  if (source === 'manual') return '人工回填';
  return '未记录';
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
      return { kind: 'start', label: '开始执行', title: '先建立执行上下文', detail: '进入执行阶段后，可新建或关联智能体会话。', icon: 'spark' };
    case 'IN_PROGRESS':
      return { kind: 'presubmit', label: '提交预审', title: '变更准备好了吗？', detail: '预审会冻结树锚点，并将工单交给审核流程。', icon: 'shield' };
    case 'PRESUBMITTED':
      return { kind: 'review', label: '进入审核台', title: '审核锚点已建立', detail: '在审核台检查变更、证据与可发布性。', icon: 'shield' };
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

function parseEditLabels(value: string): string[] {
  return [...new Set(value.split(/[,，\n]/).map((label) => label.trim()).filter(Boolean))];
}

function openEdit() {
  if (loading.value || loadError.value) return;
  editTitle.value = ticket.value.title;
  editDescription.value = ticket.value.description ?? '';
  editNote.value = ticket.value.note ?? '';
  editLabels.value = (ticket.value.labels ?? []).join(', ');
  editError.value = '';
  showEdit.value = true;
}

async function saveEdit() {
  const title = editTitle.value.trim();
  if (!title) {
    editError.value = '标题不能为空。';
    return;
  }
  if (savingEdit.value) return;

  savingEdit.value = true;
  editError.value = '';
  try {
    const updated = await updateTicket(ticket.value.no, {
      title,
      description: editDescription.value.trim() || null,
      note: editNote.value.trim() || null,
      labels: parseEditLabels(editLabels.value),
    }, projectId);
    ticket.value = {
      ...ticket.value,
      ...updated,
      dependencies: [...(ticket.value.dependencies ?? [])],
      labels: [...(updated.labels ?? [])],
    };
    addActivity('work', '工单信息已更新', '标题、需求描述、备注或标签已保存。');
    actionNotice.value = '工单信息已保存';
    showEdit.value = false;
  } catch {
    editError.value = '保存失败，请检查后端连接后重试。';
  } finally {
    savingEdit.value = false;
  }
}

async function performPrimaryAction() {
  switch (nextAction.value.kind) {
    case 'start':
      updateStage('IN_PROGRESS', '工单已进入执行阶段', '现在可以关联智能体会话并开始处理变更。');
      break;
    case 'presubmit': {
      actionNotice.value = '正在提交预审并固化树锚点……';
      try {
        const result = await presubmitTicket(ticket.value.no);
        ticket.value.treeHash = result.treeHash;
        ticket.value.baseCommit = result.baseCommit;
        ticket.value.reviewRound = result.reviewRound;
        decisionState.value = 'reviewing';
        updateStage('PRESUBMITTED', '已生成预审锚点', `第 ${result.reviewRound} 轮审核可从此树锚点开始。`);
      } catch {
        actionNotice.value = '预审提交失败，请检查后端日志。';
      }
      break;
    }
    case 'review':
      router.push({ name: 'review', params: { projectId, no: ticket.value.no } });
      break;
    case 'publish':
      router.push({ name: 'review', params: { projectId, no: ticket.value.no } });
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

async function startSession() {
  const agent = assignedAgent.value ?? {
    id: 'claude-sonnet-default',
    name: 'Claude Sonnet',
    cli: 'CLAUDE' as const,
    providerId: 'newapi',
    model: '',
    systemPrompt: null,
    extraFlags: [],
    description: null,
  };
  try {
    const created = await apiStartSession(ticket.value.no, { agentConfigId: agent.id });
    localSessions.value.unshift(created);
    selectedSessionId.value = created.id;
    addActivity('session', '已创建新的执行会话', `会话 ${created.id} 已关联到 ${agent.name}。`);
    actionNotice.value = '新的执行会话已关联';
  } catch {
    actionNotice.value = '创建会话失败，请检查智能体配置与后端日志。';
  }
}

function openSession() {
  router.push({ name: 'session', params: { projectId, no: ticket.value.no } });
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
      <GButton variant="ghost" size="sm" class="back-button" @click="router.push({ name: 'kanban', params: { projectId } })">
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
        <GButton variant="secondary" size="sm" :disabled="loading || Boolean(loadError)" @click="openEdit">
          <GIcon name="edit" :size="14" />
          编辑工单
        </GButton>
        <GButton variant="ghost" size="sm" @click="openSession">
          <GIcon name="chat" :size="14" />
          会话
        </GButton>
        <GButton variant="ghost" size="sm" @click="router.push({ name: 'review', params: { projectId, no: ticket.no } })">
          <GIcon name="shield" :size="14" />
          审核台
        </GButton>
      </div>
    </header>

    <div v-if="loading" class="detail-skeleton" role="status" aria-label="正在读取工单数据">
      <div class="detail-skeleton__overview">
        <GSkeleton variant="text" width="38%" height="16px" />
        <GSkeleton variant="text" :lines="2" />
      </div>
      <div class="detail-skeleton__grid">
        <div class="detail-skeleton__main">
          <GSkeleton variant="block" height="180px" />
          <GSkeleton variant="block" height="120px" />
        </div>
        <div class="detail-skeleton__side">
          <GSkeleton variant="block" height="140px" />
          <GSkeleton variant="block" height="90px" />
        </div>
      </div>
    </div>
    <p v-else-if="loadError" class="detail-data-state detail-data-state--error" role="alert">
      {{ loadError }}
      <button type="button" @click="loadTicket">重新读取</button>
    </p>

    <p class="sr-notice" role="status" aria-live="polite">{{ actionNotice }}</p>

    <section class="ticket-overview" aria-label="工单概览">
      <div class="ticket-overview__summary">
        <p class="ticket-overview__lede">
          {{ ticket.project ?? '本地项目' }}
          <span aria-hidden="true">/</span>
          {{ ticket.branch ?? ticket.targetRef }}
        </p>
        <div class="meta-list">
          <span><GIcon name="git-branch" :size="13" />{{ ticket.dependencies?.length ?? 0 }} 个依赖</span>
          <span><GIcon name="comment" :size="13" />{{ ticket.commentCount ?? 0 }} 条讨论</span>
          <span>更新于 {{ formatTime(ticket.updatedAt) }}</span>
        </div>
      </div>
      <div class="ticket-overview__labels" aria-label="工单标签">
        <GBadge v-for="label in ticket.labels" :key="label" tone="neutral">{{ label }}</GBadge>
        <span v-if="!ticket.labels?.length" class="ticket-overview__empty-label">暂无标签</span>
      </div>
    </section>

    <section class="primary-action" aria-label="下一步动作">
      <div class="primary-action__icon" aria-hidden="true"><GIcon :name="nextAction.icon" :size="19" /></div>
      <div class="primary-action__copy">
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
    </section>

    <GCard class="editable-content-card">
      <template #head>
        <div class="card-heading">
          <strong>需求与备注</strong>
        </div>
      </template>

      <div class="editable-content-grid">
        <article class="editable-content-block editable-content-block--wide">
          <span>需求描述</span>
          <p v-if="ticket.description">{{ ticket.description }}</p>
          <p v-else class="editable-content-block__empty">还没有填写需求描述</p>
        </article>
        <article class="editable-content-block">
          <span>备注</span>
          <p v-if="ticket.note">{{ ticket.note }}</p>
          <p v-else class="editable-content-block__empty">还没有填写备注</p>
        </article>
      </div>
    </GCard>

    <div class="workbench-grid">
      <section class="workbench-main" aria-label="工单流程和上下文">
        <GCard class="lifecycle-card">
          <template #head>
            <div class="card-heading">
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
        </GCard>

        <GCard class="context-card">
          <template #head>
            <div class="card-heading">
              <span class="card-heading__eyebrow">工单事实</span>
              <strong>流程与运行时信息</strong>
            </div>
            <span class="context-card__hint">流程产物由 Gate 自动写入</span>
          </template>

          <div class="context-grid">
            <div v-for="item in contextItems" :key="item.label" class="context-item">
              <div class="context-item__head">
                <span><GIcon :name="item.icon" :size="13" />{{ item.label }}</span>
                <GButton
                  v-if="item.copyValue"
                  variant="ghost"
                  size="sm"
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
              <div><span>累计令牌</span><strong class="mono">{{ formatTokens(selectedSession.cumulativeUsage?.totalTokens) }}</strong></div>
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
            <p>建立会话后，智能体会带着本工单的上下文开始处理。</p>
            <GButton variant="secondary" size="sm" @click="startSession">创建首个会话</GButton>
          </div>
        </GCard>

        <GCard class="assignment-card">
          <template #head>
            <div class="card-heading">
              <strong>执行责任</strong>
            </div>
          </template>
          <div class="assignment-card__content">
            <span class="assignment-card__avatar" aria-hidden="true">{{ (assignedAgent?.name ?? '系统').slice(0, 1) }}</span>
            <div>
              <strong>{{ assignedAgent?.name ?? '系统默认智能体' }}</strong>
              <p>{{ assignedAgent?.model ?? '尚未指定模型配置' }}</p>
            </div>
          </div>
        </GCard>
      </aside>
    </div>

    <GModal :show="showEdit" title="编辑工单" width="620px" @close="showEdit = false">
      <div class="ticket-edit-form">
        <p class="ticket-edit-form__intro">修改会直接保存到当前工单，并同步到看板与后续执行上下文。</p>
        <GField label="标题" for-id="detail-edit-title" required>
          <GInput id="detail-edit-title" v-model="editTitle" name="ticket-title" autocomplete="off" aria-label="工单标题" placeholder="输入工单标题" required />
        </GField>
        <GField label="需求描述" for-id="detail-edit-description" hint="描述范围、验收标准和不希望被改动的部分。">
          <GInput
            id="detail-edit-description"
            v-model="editDescription"
            type="textarea"
            :rows="5"
            aria-label="需求描述"
            placeholder="描述要完成什么、范围和验收标准"
          />
        </GField>
        <GField label="备注" for-id="detail-edit-note" hint="补充处理人需要知道的上下文。">
          <GInput
            id="detail-edit-note"
            v-model="editNote"
            type="textarea"
            :rows="3"
            aria-label="工单备注"
            placeholder="补充处理人需要知道的上下文"
          />
        </GField>
        <GField label="标签" for-id="detail-edit-labels" hint="标签会自动去重；清空输入即可移除全部标签。">
          <GInput
            id="detail-edit-labels"
            v-model="editLabels"
            name="ticket-labels"
            autocomplete="off"
            aria-label="工单标签"
            placeholder="用逗号或换行分隔，例如：前端、体验优化"
          />
        </GField>
        <p v-if="editError" class="ticket-edit-form__error" role="alert">{{ editError }}</p>
      </div>
      <template #footer>
        <GButton variant="ghost" :disabled="savingEdit" @click="showEdit = false">取消</GButton>
        <GButton variant="primary" :loading="savingEdit" @click="saveEdit">保存修改</GButton>
      </template>
    </GModal>

    <GModal :show="showDecision" title="记录审批决定" width="500px" @close="showDecision = false">
      <div class="decision-modal">
        <p>选择本次审核的处理方式。决定会立即反映到本页的生命周期和活动记录中。</p>
        <div class="decision-options" role="group" aria-label="审批决定类型">
          <button type="button" :class="{ active: decisionIntent === 'approve' }" :aria-pressed="decisionIntent === 'approve'" @click="decisionIntent = 'approve'">通过并准备发布</button>
          <button type="button" :class="{ active: decisionIntent === 'reject' }" :aria-pressed="decisionIntent === 'reject'" @click="decisionIntent = 'reject'">驳回并回流执行</button>
          <button type="button" :class="{ active: decisionIntent === 'human' }" :aria-pressed="decisionIntent === 'human'" @click="decisionIntent = 'human'">转交人工处理</button>
        </div>
        <GField label="决定说明（可选）" for-id="decision-note" hint="留下一句可供后续处理者复盘的上下文。">
          <GInput id="decision-note" v-model="decisionNote" type="textarea" :rows="3" placeholder="记录能帮助下一位处理者理解决定的上下文" />
        </GField>
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

.detail-skeleton {
  display: grid;
  gap: 16px;
  margin: 6px 0 18px;
}

.detail-skeleton__overview {
  display: grid;
  gap: 10px;
  padding: 16px;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--panel);
}

.detail-skeleton__grid {
  display: grid;
  grid-template-columns: minmax(0, 2.1fr) minmax(0, 1fr);
  gap: 16px;
}

.detail-skeleton__main,
.detail-skeleton__side {
  display: grid;
  gap: 16px;
  align-content: start;
}

@media (max-width: 1024px) {
  .detail-skeleton__grid {
    grid-template-columns: 1fr;
  }
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
  box-shadow: var(--shadow-panel);
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

.ticket-overview__empty-label {
  color: var(--text-faint);
  font-size: 11px;
}

.editable-content-card {
  margin-top: 18px;
  border-color: var(--border);
  border-radius: 14px;
  background: var(--panel);
  box-shadow: var(--shadow-panel);
}

.editable-content-card :deep(.g-card__head) {
  min-height: 58px;
  padding: 13px 17px;
}

.editable-content-card :deep(.g-card__body) {
  padding: 15px 17px 17px;
}

.editable-content-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.35fr) minmax(230px, 0.65fr);
  gap: 10px;
}

.editable-content-block {
  min-width: 0;
  padding: 12px 13px;
  border: 1px solid var(--border);
  border-radius: 10px;
  background: var(--panel-2);
}

.editable-content-block > span {
  display: block;
  color: var(--text-muted);
  font-size: 10px;
  font-weight: 760;
}

.editable-content-block p {
  min-height: 24px;
  margin: 7px 0 0;
  color: var(--text-secondary);
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.editable-content-block__empty {
  color: var(--text-faint) !important;
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
  box-shadow: var(--shadow-panel);
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
  border-color: color-mix(in srgb, var(--success) 30%, transparent);
  color: var(--success);
  background: var(--success-soft);
}

.lifecycle__item--complete .lifecycle__index {
  border-color: var(--success);
  color: var(--accent-contrast);
  background: var(--success);
}

.lifecycle__item--current {
  border-color: var(--accent-border);
  color: var(--accent-hover);
  background: var(--accent-soft);
  box-shadow: inset 0 0 0 1px var(--accent-focus-ring);
}

.lifecycle__item--current .lifecycle__index {
  border-color: var(--accent);
  color: var(--accent-contrast);
  background: var(--accent);
}

.lifecycle__item--blocked {
  border-color: color-mix(in srgb, var(--warning) 40%, transparent);
  color: var(--warning);
  background: var(--warning-soft);
}

.lifecycle__item--blocked .lifecycle__index {
  border-color: var(--warning);
  color: var(--accent-contrast);
  background: var(--warning);
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
  border-color: var(--accent-border);
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
  border: 1px solid var(--accent-border);
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

.ticket-edit-form {
  display: grid;
  gap: 15px;
}

.ticket-edit-form__intro {
  margin: 0;
  color: var(--text-secondary);
  font-size: 12px;
  line-height: 1.6;
}

.ticket-edit-field {
  display: grid;
  gap: 7px;
}

.ticket-edit-field > span {
  color: var(--text-secondary);
  font-size: 12px;
  font-weight: 750;
}

.ticket-edit-field > small {
  color: var(--text-faint);
  font-size: 10px;
}

.ticket-edit-form__error {
  margin: 0;
  padding: 9px 11px;
  border: 1px solid var(--danger-soft);
  border-radius: 8px;
  color: var(--danger);
  background: var(--danger-soft);
  font-size: 12px;
  line-height: 1.5;
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
  border-color: var(--accent-border);
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

/* The ticket state and next action carry the page. Other sections stay quiet. */
.detail-workbench {
  width: 100%;
  max-width: 1600px;
  padding: 22px clamp(20px, 3vw, 42px) 32px;
}

.workbench-head {
  gap: 14px;
}

.ticket-heading h2 {
  font-size: clamp(24px, 3vw, 34px);
}

.head-actions {
  gap: 4px;
}

.head-actions :deep(.g-btn) {
  border-radius: var(--radius-sm);
}

.ticket-overview {
  align-items: center;
  gap: 18px;
  margin-top: 20px;
  padding: 13px 0;
  border-top: 1px solid var(--border);
  border-right: 0;
  border-bottom: 1px solid var(--border);
  border-left: 0;
  border-radius: 0;
  background: transparent;
  box-shadow: none;
}

.ticket-overview__lede {
  margin-top: 0;
  font-size: 12px;
}

.meta-list {
  gap: 6px 14px;
  margin-top: 8px;
}

.primary-action {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: 12px;
  margin-top: 16px;
  padding: 14px 15px;
  border: 1px solid var(--accent-border);
  border-left: 3px solid var(--accent);
  border-radius: var(--radius-md);
  background: var(--accent-soft);
}

.primary-action__icon {
  display: grid;
  width: 36px;
  height: 36px;
  place-items: center;
  border-radius: var(--radius-sm);
  color: var(--accent-hover);
  background: color-mix(in srgb, var(--accent) 12%, var(--panel));
}

.primary-action__copy {
  min-width: 0;
}

.primary-action__copy > span {
  display: block;
  color: var(--accent-hover);
  font-size: 10px;
  font-weight: 800;
}

.primary-action__copy strong {
  display: block;
  margin-top: 2px;
  color: var(--ink);
  font-size: 13px;
}

.primary-action__copy p {
  margin: 2px 0 0;
  overflow: hidden;
  color: var(--text-muted);
  font-size: 11px;
  line-height: 1.45;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.primary-action :deep(.g-btn) {
  min-width: 108px;
  border-radius: var(--radius-sm);
}

.editable-content-card {
  margin-top: 16px;
  border-radius: var(--radius-md);
  box-shadow: none;
}

.editable-content-card :deep(.g-card__head) {
  min-height: 51px;
  padding: 12px 16px;
}

.editable-content-card :deep(.g-card__body) {
  padding: 14px 16px 16px;
}

.editable-content-block {
  padding: 0;
  border: 0;
  border-radius: 0;
  background: transparent;
}

.editable-content-block + .editable-content-block {
  padding-left: 18px;
  border-left: 1px solid var(--border);
}

.workbench-grid {
  gap: 16px;
  margin-top: 18px;
}

.workbench-main,
.workbench-side {
  gap: 14px;
}

.workbench-main > .context-card {
  order: -1;
}

.workbench-main > :deep(.g-card),
.workbench-side > :deep(.g-card) {
  border-radius: var(--radius-md);
  box-shadow: none;
}

.workbench-main > :deep(.g-card) :deep(.g-card__head),
.workbench-side > :deep(.g-card) :deep(.g-card__head) {
  min-height: 51px;
  padding: 12px 16px;
}

.lifecycle-card :deep(.g-card__body) {
  padding: 14px 16px 16px;
}

.lifecycle {
  gap: 6px;
}

.lifecycle__item {
  min-height: 88px;
  padding: 10px 9px;
  border-radius: var(--radius-sm);
}

.lifecycle__index {
  width: 21px;
  height: 21px;
  margin-bottom: 10px;
}

.context-card :deep(.g-card__body),
.activity-card :deep(.g-card__body),
.decision-card :deep(.g-card__body),
.session-card :deep(.g-card__body),
.assignment-card :deep(.g-card__body) {
  padding: 14px 16px;
}

.context-item {
  border-radius: var(--radius-sm);
  background: var(--panel-2);
}

.decision-card__round {
  padding: 9px 0;
  border-right: 0;
  border-left: 0;
  border-radius: 0;
  background: transparent;
}

.session-metrics > div {
  border-radius: var(--radius-sm);
}

@media (max-width: 760px) {
  .detail-workbench {
    padding: 17px 14px 24px;
  }

  .primary-action {
    grid-template-columns: auto minmax(0, 1fr);
  }

  .primary-action :deep(.g-btn) {
    grid-column: 1 / -1;
    width: 100%;
  }

  .editable-content-block + .editable-content-block {
    padding-top: 14px;
    padding-left: 0;
    border-top: 1px solid var(--border);
    border-left: 0;
  }
}

@media (max-width: 520px) {
  .ticket-overview {
    align-items: flex-start;
    flex-direction: column;
    gap: 10px;
    padding: 12px 0;
  }

  .ticket-overview__labels {
    justify-content: flex-start;
  }

  .primary-action__copy p {
    white-space: normal;
  }

  .editable-content-card :deep(.g-card__body),
  .lifecycle-card :deep(.g-card__body),
  .context-card :deep(.g-card__body),
  .activity-card :deep(.g-card__body),
  .decision-card :deep(.g-card__body),
  .session-card :deep(.g-card__body),
  .assignment-card :deep(.g-card__body) {
    padding: 13px 14px;
  }
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

  .editable-content-grid {
    grid-template-columns: 1fr;
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
