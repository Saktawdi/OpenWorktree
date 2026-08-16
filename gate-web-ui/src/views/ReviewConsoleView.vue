<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { getTicket } from '@/api/tickets';
import { GBadge, GButton, GIcon, GInput, GModal } from '@/components/ui';
import { TICKET_STAGE_LABELS } from '@/types/stage';
import type { Ticket } from '@/types/ticket';
import type { TicketStage } from '@/types/stage';

type ReviewRunState = 'idle' | 'running' | 'complete';
type ReviewPhase = 'anchor' | 'llm' | 'human' | 'publish';
type FindingSeverity = 'blocker' | 'attention' | 'note';
type FindingStatus = 'open' | 'verified';
type DecisionType = 'approved' | 'rejected';
type DiffKind = 'context' | 'add' | 'remove';

interface ReviewFinding {
  id: string;
  severity: FindingSeverity;
  status: FindingStatus;
  title: string;
  detail: string;
  location: string;
  lines: [number, number];
}

interface DiffLine {
  id: string;
  kind: DiffKind;
  oldLine: number | null;
  newLine: number | null;
  code: string;
}

interface DecisionRecord {
  type: DecisionType;
  reason: string;
  at: string;
}

interface AuditEntry {
  id: string;
  title: string;
  detail: string;
  at: string;
  tone: 'neutral' | 'accent' | 'success' | 'warning' | 'danger';
}

interface ReviewPhaseDefinition {
  id: ReviewPhase;
  label: string;
  description: string;
}

const route = useRoute();
const router = useRouter();
const ticketNo = String(route.params.no ?? 'T-104');
const ticket = ref<Ticket>({
  no: ticketNo,
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

const reviewRunState = ref<ReviewRunState>('idle');
const activePhase = ref<ReviewPhase>('anchor');
const completedReviewSteps = ref(0);
const decisionRecord = ref<DecisionRecord | null>(null);
const actionNotice = ref('');
const selectedFindingId = ref<string | null>('F-01');
const selectedDiffLineId = ref<string | null>(null);
const showDecisionModal = ref(false);
const showPublishModal = ref(false);
const decisionType = ref<DecisionType>('approved');
const decisionReason = ref('');
const decisionConfirmed = ref(false);
const publishConfirmed = ref(false);
const decisionError = ref('');
const publishError = ref('');
let auditSequence = 3;
let reviewTimers: Array<ReturnType<typeof setTimeout>> = [];

const reviewPhases: ReviewPhaseDefinition[] = [
  { id: 'anchor', label: '锚点', description: '确认 tree、base 与 target' },
  { id: 'llm', label: 'LLM 审核', description: '读取变更并形成结论' },
  { id: 'human', label: '人工决定', description: '通过或驳回审核结论' },
  { id: 'publish', label: '发布', description: '受控完成最终交付' },
];

const reviewSteps = ['读取审核锚点', '收集变更证据', '交叉检查风险', '整理审核结论'];

const findings = ref<ReviewFinding[]>([
  {
    id: 'F-01',
    severity: 'blocker',
    status: 'open',
    title: '锚点信息在主视图中没有稳定承载',
    detail: '审核者离开顶部后无法确认 tree_hash 与 base_commit 是否仍匹配当前证据。',
    location: 'ReviewConsoleView.vue',
    lines: [17, 20],
  },
  {
    id: 'F-02',
    severity: 'attention',
    status: 'open',
    title: '发现项没有反向定位到 Diff 行',
    detail: '问题描述与代码证据断开，复核时需要手动查找受影响区域。',
    location: 'ReviewConsoleView.vue',
    lines: [29, 33],
  },
  {
    id: 'F-03',
    severity: 'note',
    status: 'verified',
    title: '发布动作缺少前置门槛说明',
    detail: '可发布状态、人工决定与锚点完整性应同时满足后再开放发布。',
    location: 'ReviewConsoleView.vue',
    lines: [43, 47],
  },
]);

const diffLines: DiffLine[] = [
  { id: 'd-01', kind: 'context', oldLine: 11, newLine: 11, code: 'const ticket = ref(currentTicket);' },
  { id: 'd-02', kind: 'context', oldLine: 12, newLine: 12, code: 'const reviewRound = computed(() => ticket.value.reviewRound ?? 0);' },
  { id: 'd-03', kind: 'remove', oldLine: 13, newLine: null, code: 'const running = ref(false);' },
  { id: 'd-04', kind: 'add', oldLine: null, newLine: 13, code: "const reviewRunState = ref<'idle' | 'running' | 'complete'>('idle');" },
  { id: 'd-05', kind: 'add', oldLine: null, newLine: 14, code: "const activePhase = ref<ReviewPhase>('anchor');" },
  { id: 'd-06', kind: 'add', oldLine: null, newLine: 15, code: 'const hasAnchor = computed(() => Boolean(treeHash.value && baseCommit.value && targetRef.value));' },
  { id: 'd-07', kind: 'context', oldLine: 14, newLine: 16, code: '' },
  { id: 'd-08', kind: 'add', oldLine: null, newLine: 17, code: 'function setReviewAnchor() {' },
  { id: 'd-09', kind: 'add', oldLine: null, newLine: 18, code: '  if (!hasAnchor.value) throw new Error("审核锚点不完整");' },
  { id: 'd-10', kind: 'add', oldLine: null, newLine: 19, code: '  audit.write({ treeHash, baseCommit, targetRef });' },
  { id: 'd-11', kind: 'add', oldLine: null, newLine: 20, code: '}' },
  { id: 'd-12', kind: 'context', oldLine: 15, newLine: 21, code: '' },
  { id: 'd-13', kind: 'add', oldLine: null, newLine: 29, code: 'function focusFinding(finding: Finding) {' },
  { id: 'd-14', kind: 'add', oldLine: null, newLine: 30, code: '  selectedFindingId.value = finding.id;' },
  { id: 'd-15', kind: 'add', oldLine: null, newLine: 31, code: '  scrollToDiffRange(finding.location, finding.lines);' },
  { id: 'd-16', kind: 'add', oldLine: null, newLine: 32, code: '  highlightedLines.value = finding.lines;' },
  { id: 'd-17', kind: 'add', oldLine: null, newLine: 33, code: '}' },
  { id: 'd-18', kind: 'context', oldLine: 16, newLine: 34, code: '' },
  { id: 'd-19', kind: 'remove', oldLine: 30, newLine: null, code: 'function publish() { ticket.stage = "DONE"; }' },
  { id: 'd-20', kind: 'add', oldLine: null, newLine: 43, code: 'function canPublish() {' },
  { id: 'd-21', kind: 'add', oldLine: null, newLine: 44, code: '  return hasAnchor.value && decision.type === "approved";' },
  { id: 'd-22', kind: 'add', oldLine: null, newLine: 45, code: '    && ticket.stage === "READY_TO_PUBLISH" && !isRunning.value;' },
  { id: 'd-23', kind: 'add', oldLine: null, newLine: 46, code: '}' },
  { id: 'd-24', kind: 'add', oldLine: null, newLine: 47, code: 'function publishWithConfirmation() { /* audit then publish */ }' },
];

const auditEntries = ref<AuditEntry[]>([
  {
    id: 'A-01',
    title: '审核锚点已加载',
    detail: 'tree、base 与 target 已绑定到本次审核。',
    at: ticket.value.updatedAt,
    tone: 'accent',
  },
  {
    id: 'A-02',
    title: '证据已准备',
    detail: '当前 Diff 可直接关联发现项。',
    at: ticket.value.updatedAt,
    tone: 'neutral',
  },
]);

const isRunning = computed(() => reviewRunState.value === 'running');
const hasAnchor = computed(() => Boolean(ticket.value.treeHash && ticket.value.baseCommit && ticket.value.targetRef));
const selectedFinding = computed(() => findings.value.find((finding) => finding.id === selectedFindingId.value) ?? null);
const openFindingsCount = computed(() => findings.value.filter((finding) => finding.status === 'open').length);
const canDecide = computed(() => reviewRunState.value === 'complete' && ticket.value.stage === 'NEEDS_HUMAN' && !isRunning.value);
const canPublish = computed(
  () =>
    hasAnchor.value &&
    decisionRecord.value?.type === 'approved' &&
    ticket.value.stage === 'READY_TO_PUBLISH' &&
    !isRunning.value,
);

const highlightedDiffLineIds = computed(() => {
  const finding = selectedFinding.value;
  if (!finding) return new Set<string>();
  return new Set(
    diffLines
      .filter((line) => {
        const lineNumber = line.newLine ?? line.oldLine;
        return lineNumber != null && lineNumber >= finding.lines[0] && lineNumber <= finding.lines[1];
      })
      .map((line) => line.id),
  );
});

const primaryAction = computed(() => {
  if (ticket.value.stage === 'DONE') return { label: '发布已完成', disabled: true };
  if (!hasAnchor.value) return { label: '补齐审核锚点', disabled: false };
  if (isRunning.value) return { label: 'LLM 审核进行中', disabled: true };
  if (ticket.value.stage === 'NEEDS_HUMAN') return { label: '处理人工决定', disabled: false };
  if (ticket.value.stage === 'READY_TO_PUBLISH' && canPublish.value) return { label: '确认发布', disabled: false };
  if (ticket.value.stage === 'REJECTED') return { label: '返回工单处理', disabled: false };
  return { label: reviewRunState.value === 'complete' ? '重新运行 LLM 审核' : '运行 LLM 审核', disabled: false };
});

const publishGates = computed(() => [
  { label: '审核锚点完整', detail: 'tree、base 与 target 已确认', passed: hasAnchor.value },
  { label: '人工决定为通过', detail: decisionRecord.value?.type === 'approved' ? '已记录人工通过' : '尚未记录人工通过', passed: decisionRecord.value?.type === 'approved' },
  { label: '工单处于可发布', detail: TICKET_STAGE_LABELS[ticket.value.stage], passed: ticket.value.stage === 'READY_TO_PUBLISH' },
  { label: '当前没有运行中的审核', detail: isRunning.value ? 'LLM 审核仍在执行' : '审核未在执行', passed: !isRunning.value },
]);

function toneFor(stage: TicketStage): 'neutral' | 'accent' | 'success' | 'warning' | 'danger' {
  if (stage === 'REJECTED' || stage === 'CANCELLED') return 'danger';
  if (stage === 'DONE' || stage === 'READY_TO_PUBLISH') return 'success';
  if (stage === 'NEEDS_HUMAN') return 'warning';
  if (stage === 'PRESUBMITTED' || stage === 'IN_REVIEW') return 'accent';
  return 'neutral';
}

function findingTone(severity: FindingSeverity): 'neutral' | 'accent' | 'success' | 'warning' | 'danger' {
  if (severity === 'blocker') return 'danger';
  if (severity === 'attention') return 'warning';
  return 'neutral';
}

function severityLabel(severity: FindingSeverity) {
  if (severity === 'blocker') return '阻塞';
  if (severity === 'attention') return '关注';
  return '建议';
}

function phaseState(phase: ReviewPhase) {
  if (phase === 'anchor') return hasAnchor.value ? 'complete' : activePhase.value === 'anchor' ? 'active' : 'pending';
  if (phase === 'llm') {
    if (reviewRunState.value === 'complete') return 'complete';
    return isRunning.value || activePhase.value === 'llm' ? 'active' : 'pending';
  }
  if (phase === 'human') {
    if (decisionRecord.value) return 'complete';
    return activePhase.value === 'human' ? 'active' : 'pending';
  }
  if (ticket.value.stage === 'DONE') return 'complete';
  return activePhase.value === 'publish' ? 'active' : 'pending';
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

function addAudit(title: string, detail: string, tone: AuditEntry['tone']) {
  auditSequence += 1;
  auditEntries.value.unshift({
    id: 'A-' + auditSequence,
    title,
    detail,
    at: new Date().toISOString(),
    tone,
  });
}

function updateTicketStage(stage: TicketStage) {
  ticket.value.stage = stage;
  ticket.value.updatedAt = new Date().toISOString();
}

function findingForDiffLine(line: DiffLine) {
  const lineNumber = line.newLine ?? line.oldLine;
  if (lineNumber == null) return null;
  return findings.value.find((finding) => lineNumber >= finding.lines[0] && lineNumber <= finding.lines[1]) ?? null;
}

function isHighlightedLine(line: DiffLine) {
  return highlightedDiffLineIds.value.has(line.id);
}

function selectFinding(finding: ReviewFinding, shouldScroll = true) {
  selectedFindingId.value = finding.id;
  const target = diffLines.find((line) => {
    const lineNumber = line.newLine ?? line.oldLine;
    return lineNumber != null && lineNumber >= finding.lines[0] && lineNumber <= finding.lines[1];
  });
  selectedDiffLineId.value = target?.id ?? null;
  if (!shouldScroll || !target || typeof document === 'undefined') return;
  requestAnimationFrame(() => document.getElementById('diff-line-' + target.id)?.scrollIntoView({ behavior: 'smooth', block: 'center' }));
}

function selectDiffLine(line: DiffLine) {
  selectedDiffLineId.value = line.id;
  const finding = findingForDiffLine(line);
  selectedFindingId.value = finding?.id ?? null;
}

function toggleFindingStatus(finding: ReviewFinding) {
  finding.status = finding.status === 'open' ? 'verified' : 'open';
  actionNotice.value = finding.status === 'verified' ? '已标记为已核实。' : '已重新标记为待处理。';
}

function clearReviewTimers() {
  reviewTimers.forEach((timer) => clearTimeout(timer));
  reviewTimers = [];
}

function runReviewStep(index: number) {
  const timer = setTimeout(() => {
    completedReviewSteps.value = index + 1;
    if (index < reviewSteps.length - 1) {
      runReviewStep(index + 1);
      return;
    }
    reviewTimers = [];
    reviewRunState.value = 'complete';
    activePhase.value = 'human';
    updateTicketStage('NEEDS_HUMAN');
    ticket.value.reviewRound = (ticket.value.reviewRound ?? 0) + 1;
    addAudit('LLM 审核完成', '第 ' + ticket.value.reviewRound + ' 轮审核已完成，等待人工决定。', 'warning');
    actionNotice.value = 'LLM 审核已完成，现需人工记录通过或驳回结论。';
  }, 650);
  reviewTimers.push(timer);
}

function runLLMReview() {
  if (!hasAnchor.value) {
    activePhase.value = 'anchor';
    actionNotice.value = '审核锚点不完整，请先在工单详情补齐 tree、base 与 target。';
    return;
  }
  if (isRunning.value || ticket.value.stage === 'DONE') return;

  clearReviewTimers();
  reviewRunState.value = 'running';
  activePhase.value = 'llm';
  completedReviewSteps.value = 0;
  decisionRecord.value = null;
  updateTicketStage('IN_REVIEW');
  actionNotice.value = 'LLM 正在按四个阶段审核当前锚定的变更。';
  runReviewStep(0);
}

function openDecision(type: DecisionType) {
  if (!canDecide.value) {
    actionNotice.value = '请先完成 LLM 审核，再记录人工决定。';
    return;
  }
  decisionType.value = type;
  decisionReason.value = '';
  decisionConfirmed.value = false;
  decisionError.value = '';
  showDecisionModal.value = true;
}

function closeDecisionModal() {
  showDecisionModal.value = false;
  decisionError.value = '';
}

function confirmDecision() {
  if (decisionType.value === 'rejected' && decisionReason.value.trim().length < 4) {
    decisionError.value = '驳回理由至少需要 4 个字符。';
    return;
  }
  if (decisionType.value === 'approved' && !decisionConfirmed.value) {
    decisionError.value = '请确认已核对审核结论与发布前置条件。';
    return;
  }

  const isApproved = decisionType.value === 'approved';
  const reason = decisionReason.value.trim() || '人工已确认审核结论与当前证据一致。';
  decisionRecord.value = {
    type: decisionType.value,
    reason,
    at: new Date().toISOString(),
  };
  updateTicketStage(isApproved ? 'READY_TO_PUBLISH' : 'REJECTED');
  activePhase.value = isApproved ? 'publish' : 'human';
  addAudit(isApproved ? '人工通过审核' : '人工驳回审核', reason, isApproved ? 'success' : 'danger');
  actionNotice.value = isApproved ? '已记录人工通过，发布门槛已开放核对。' : '已记录人工驳回，工单已回到待处理状态。';
  closeDecisionModal();
}

function openPublishModal() {
  if (!canPublish.value) {
    actionNotice.value = '发布门槛尚未全部满足，请先检查右侧清单。';
    activePhase.value = 'publish';
    return;
  }
  publishConfirmed.value = false;
  publishError.value = '';
  showPublishModal.value = true;
}

function closePublishModal() {
  showPublishModal.value = false;
  publishError.value = '';
}

function confirmPublish() {
  if (!canPublish.value) {
    publishError.value = '发布门槛已变化，请重新核对。';
    return;
  }
  if (!publishConfirmed.value) {
    publishError.value = '请确认将以当前审核锚点完成发布。';
    return;
  }
  updateTicketStage('DONE');
  activePhase.value = 'publish';
  addAudit('已完成发布', '以当前审核锚点完成受控发布。', 'success');
  actionNotice.value = '发布已完成，工单已归档。';
  closePublishModal();
}

function scrollToSection(id: string) {
  if (typeof document === 'undefined') return;
  document.getElementById(id)?.scrollIntoView({ behavior: 'smooth', block: 'start' });
}

function focusPhase(phase: ReviewPhase) {
  activePhase.value = phase;
  const targetId: Record<ReviewPhase, string> = {
    anchor: 'review-anchor',
    llm: 'audit-trail',
    human: 'decision-panel',
    publish: 'publish-gates',
  };
  scrollToSection(targetId[phase]);
}

function performPrimaryAction() {
  if (primaryAction.value.disabled) return;
  if (!hasAnchor.value) {
    openDetail();
    return;
  }
  if (ticket.value.stage === 'NEEDS_HUMAN') {
    focusPhase('human');
    return;
  }
  if (ticket.value.stage === 'READY_TO_PUBLISH' && canPublish.value) {
    openPublishModal();
    return;
  }
  if (ticket.value.stage === 'REJECTED') {
    openDetail();
    return;
  }
  runLLMReview();
}

function openDetail() {
  router.push({ name: 'ticket-detail', params: { no: ticket.value.no } });
}

function openSession() {
  router.push({ name: 'session', params: { no: ticket.value.no } });
}

async function loadTicket() {
  loading.value = true;
  loadError.value = '';
  try {
    ticket.value = await getTicket(ticketNo);
  } catch {
    loadError.value = '无法读取工单数据，请确认工单仍存在且 Gate 后端正在运行。';
    ticket.value.title = '工单数据不可用';
  } finally {
    loading.value = false;
  }
}

onMounted(() => void loadTicket());
onBeforeUnmount(clearReviewTimers);
</script>

<template>
  <div class="review-page">
    <header class="review-header">
      <div class="review-header__copy">
        <p class="section-kicker">审核工作台</p>
        <h2>先锁定证据，再作出决定。</h2>
        <p>审核结论、人工决定和发布动作都绑定到同一份变更锚点。</p>
      </div>
      <div class="review-header__actions">
        <GBadge :tone="toneFor(ticket.stage)">{{ TICKET_STAGE_LABELS[ticket.stage] }}</GBadge>
        <GButton size="sm" variant="secondary" @click="openDetail">
          <GIcon name="external" :size="14" />
          工单详情
        </GButton>
        <GButton size="sm" variant="secondary" @click="openSession">
          <GIcon name="chat" :size="14" />
          会话
        </GButton>
      </div>
    </header>

    <p v-if="loading" class="review-data-state" role="status">正在读取后端工单数据...</p>
    <p v-else-if="loadError" class="review-data-state review-data-state--error" role="alert">
      {{ loadError }}
      <button type="button" @click="loadTicket">重新读取</button>
    </p>

    <section class="command-panel" aria-label="审核命令面板">
      <div class="command-panel__top">
        <div>
          <span class="mono command-panel__ticket">{{ ticket.no }}</span>
          <strong>{{ ticket.title }}</strong>
          <span class="command-panel__meta">第 {{ ticket.reviewRound ?? 0 }} 轮审核</span>
        </div>
        <GButton
          variant="primary"
          :loading="isRunning"
          :disabled="primaryAction.disabled"
          @click="performPrimaryAction"
        >
          <GIcon v-if="!isRunning" name="spark" :size="15" />
          {{ primaryAction.label }}
        </GButton>
      </div>

      <div class="review-flow" aria-label="审核流程">
        <button
          v-for="phase in reviewPhases"
          :key="phase.id"
          type="button"
          class="review-flow__phase"
          :class="['review-flow__phase--' + phaseState(phase.id), { 'review-flow__phase--current': activePhase === phase.id }]"
          @click="focusPhase(phase.id)"
        >
          <span class="review-flow__state">{{ phaseState(phase.id) === 'complete' ? '已完成' : phaseState(phase.id) === 'active' ? '处理中' : '待处理' }}</span>
          <strong>{{ phase.label }}</strong>
          <small>{{ phase.description }}</small>
        </button>
      </div>
    </section>

    <p v-if="actionNotice" class="action-notice" role="status">{{ actionNotice }}</p>

    <div class="review-grid">
      <main class="evidence-panel" aria-labelledby="evidence-heading">
        <header class="evidence-panel__head">
          <div>
            <p class="section-label">变更证据</p>
            <h3 id="evidence-heading">ReviewConsoleView.vue</h3>
          </div>
          <span class="mono evidence-panel__stats">{{ diffLines.length }} 行变更</span>
        </header>

        <section id="review-anchor" class="anchor-strip" aria-label="审核锚点">
          <div class="anchor-strip__label">
            <GIcon name="shield" :size="15" />
            <span>审核锚点</span>
          </div>
          <code class="mono"><small>tree</small>{{ ticket.treeHash || '--' }}</code>
          <code class="mono"><small>base</small>{{ ticket.baseCommit || '--' }}</code>
          <code class="mono"><small>target</small>{{ ticket.targetRef || '--' }}</code>
        </section>

        <div class="diff-caption">
          <span>点击证据行可反选关联发现项。</span>
          <span v-if="selectedFinding" class="diff-caption__active">当前聚焦：{{ selectedFinding.id }}</span>
        </div>

        <div class="diff-view" aria-label="变更 Diff">
          <button
            v-for="line in diffLines"
            :id="'diff-line-' + line.id"
            :key="line.id"
            type="button"
            class="diff-line mono"
            :class="[
              'diff-line--' + line.kind,
              {
                'diff-line--highlighted': isHighlightedLine(line),
                'diff-line--selected': selectedDiffLineId === line.id,
              },
            ]"
            :aria-label="'Diff 行 ' + (line.newLine ?? line.oldLine ?? '')"
            @click="selectDiffLine(line)"
          >
            <span class="diff-line__marker">{{ line.kind === 'add' ? '+' : line.kind === 'remove' ? '-' : ' ' }}</span>
            <span class="diff-line__number">{{ line.oldLine ?? '' }}</span>
            <span class="diff-line__number">{{ line.newLine ?? '' }}</span>
            <code>{{ line.code || ' ' }}</code>
          </button>
        </div>
      </main>

      <aside class="review-rail" aria-label="审核控制与发现项">
        <section class="rail-panel findings-panel" aria-labelledby="findings-heading">
          <header class="rail-panel__head">
            <div>
              <p class="section-label">发现项</p>
              <h3 id="findings-heading">{{ openFindingsCount }} 个待核实</h3>
            </div>
            <span class="mono rail-panel__count">{{ findings.length }}</span>
          </header>

          <div class="findings-list">
            <button
              v-for="finding in findings"
              :key="finding.id"
              type="button"
              class="finding-row"
              :class="{ 'finding-row--active': selectedFindingId === finding.id }"
              @click="selectFinding(finding)"
            >
              <span class="finding-row__top">
                <span class="mono">{{ finding.id }}</span>
                <GBadge :tone="findingTone(finding.severity)">{{ severityLabel(finding.severity) }}</GBadge>
                <em :class="{ 'finding-row__verified': finding.status === 'verified' }">{{ finding.status === 'verified' ? '已核实' : '待处理' }}</em>
              </span>
              <strong>{{ finding.title }}</strong>
              <small class="mono">{{ finding.location }}:{{ finding.lines[0] }}-{{ finding.lines[1] }}</small>
              <span>{{ finding.detail }}</span>
            </button>
          </div>

          <div v-if="selectedFinding" class="finding-control">
            <span>当前：{{ selectedFinding.id }}</span>
            <GButton size="sm" variant="secondary" @click="toggleFindingStatus(selectedFinding)">
              {{ selectedFinding.status === 'open' ? '标记已核实' : '恢复待处理' }}
            </GButton>
          </div>
        </section>

        <section id="audit-trail" class="rail-panel audit-panel" aria-labelledby="audit-heading">
          <header class="rail-panel__head">
            <div>
              <p class="section-label">审核轨迹</p>
              <h3 id="audit-heading">本地审计</h3>
            </div>
            <span v-if="isRunning" class="audit-panel__running">{{ completedReviewSteps }}/{{ reviewSteps.length }}</span>
          </header>

          <div v-if="isRunning" class="review-steps" aria-live="polite">
            <div
              v-for="(step, index) in reviewSteps"
              :key="step"
              :class="{ 'review-steps__item--done': completedReviewSteps > index, 'review-steps__item--active': completedReviewSteps === index }"
            >
              <span>{{ completedReviewSteps > index ? '完成' : completedReviewSteps === index ? '处理中' : '等待' }}</span>
              <strong>{{ step }}</strong>
            </div>
          </div>

          <ol v-else class="audit-list">
            <li v-for="entry in auditEntries.slice(0, 4)" :key="entry.id">
              <span class="audit-list__tone" :class="'audit-list__tone--' + entry.tone" />
              <div>
                <strong>{{ entry.title }}</strong>
                <p>{{ entry.detail }}</p>
                <time>{{ formatDate(entry.at) }}</time>
              </div>
            </li>
          </ol>
        </section>

        <section id="decision-panel" class="rail-panel decision-panel" aria-labelledby="decision-heading">
          <header class="rail-panel__head">
            <div>
              <p class="section-label">人工决定</p>
              <h3 id="decision-heading">{{ decisionRecord ? (decisionRecord.type === 'approved' ? '已通过' : '已驳回') : '等待结论' }}</h3>
            </div>
            <GBadge :tone="decisionRecord?.type === 'approved' ? 'success' : decisionRecord?.type === 'rejected' ? 'danger' : 'warning'">
              {{ decisionRecord ? (decisionRecord.type === 'approved' ? '已记录' : '已驳回') : '未记录' }}
            </GBadge>
          </header>

          <p v-if="decisionRecord" class="decision-summary">{{ decisionRecord.reason }}</p>
          <p v-else class="decision-summary">LLM 审核完成后，由人工确认通过或说明驳回原因。</p>
          <div class="decision-actions">
            <GButton size="sm" variant="success" :disabled="!canDecide" @click="openDecision('approved')">人工通过</GButton>
            <GButton size="sm" variant="danger" :disabled="!canDecide" @click="openDecision('rejected')">人工驳回</GButton>
          </div>
        </section>

        <section id="publish-gates" class="rail-panel gate-panel" aria-labelledby="gates-heading">
          <header class="rail-panel__head">
            <div>
              <p class="section-label">发布门槛</p>
              <h3 id="gates-heading">受控发布</h3>
            </div>
            <GBadge :tone="canPublish ? 'success' : 'neutral'">{{ canPublish ? '可发布' : '未就绪' }}</GBadge>
          </header>

          <ul class="gate-list">
            <li v-for="gate in publishGates" :key="gate.label">
              <span class="gate-list__state" :class="{ 'gate-list__state--passed': gate.passed }">{{ gate.passed ? '通过' : '缺失' }}</span>
              <div>
                <strong>{{ gate.label }}</strong>
                <small>{{ gate.detail }}</small>
              </div>
            </li>
          </ul>
          <GButton size="sm" variant="secondary" block :disabled="!canPublish" @click="openPublishModal">打开发布确认</GButton>
        </section>
      </aside>
    </div>

    <GModal :show="showDecisionModal" :title="decisionType === 'approved' ? '确认人工通过' : '记录人工驳回'" width="500px" @close="closeDecisionModal">
      <div class="decision-modal">
        <p v-if="decisionType === 'approved'">通过后，工单会进入可发布状态，且该决定会写入本地审计轨迹。</p>
        <p v-else>驳回后，工单会回到待处理状态。请留下可以指导后续执行的理由。</p>

        <label class="field">
          <span>{{ decisionType === 'approved' ? '补充说明（可选）' : '驳回理由' }}</span>
          <GInput
            v-model="decisionReason"
            type="textarea"
            :rows="3"
            :placeholder="decisionType === 'approved' ? '例如：锚点、Diff 与审核结论已经核对。' : '例如：tree_hash 与当前 Diff 不一致，需要重新提审。'"
          />
        </label>

        <label v-if="decisionType === 'approved'" class="confirmation-field">
          <input v-model="decisionConfirmed" type="checkbox" />
          <span>我已核对审核结论、变更证据与发布前置条件。</span>
        </label>

        <p v-if="decisionError" class="modal-error" role="alert">{{ decisionError }}</p>
      </div>
      <template #footer>
        <GButton variant="ghost" @click="closeDecisionModal">取消</GButton>
        <GButton :variant="decisionType === 'approved' ? 'success' : 'danger'" @click="confirmDecision">
          {{ decisionType === 'approved' ? '确认通过' : '确认驳回' }}
        </GButton>
      </template>
    </GModal>

    <GModal :show="showPublishModal" title="确认受控发布" width="500px" @close="closePublishModal">
      <div class="decision-modal">
        <p>发布将使用当前 tree、base 与 target 作为审计锚点，并将工单状态更新为已完成。</p>
        <label class="confirmation-field">
          <input v-model="publishConfirmed" type="checkbox" />
          <span>确认以当前审核锚点完成发布，且不再保留未决审核动作。</span>
        </label>
        <p v-if="publishError" class="modal-error" role="alert">{{ publishError }}</p>
      </div>
      <template #footer>
        <GButton variant="ghost" @click="closePublishModal">取消</GButton>
        <GButton variant="primary" @click="confirmPublish">确认发布</GButton>
      </template>
    </GModal>
  </div>
</template>

<style scoped>
.review-page {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 14px;
  min-height: 0;
  width: min(100%, 1500px);
  margin: 0 auto;
  padding: clamp(20px, 2.6vw, 34px) clamp(18px, 2.7vw, 42px) 30px;
  overflow: auto;
}

.review-data-state {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: -2px 0 0;
  padding: 9px 12px;
  border: 1px solid var(--border);
  color: var(--text-muted);
  background: var(--panel-2);
  font-size: 12px;
}

.review-data-state--error {
  border-color: var(--danger-soft);
  color: var(--danger);
  background: var(--danger-soft);
}

.review-data-state button {
  padding: 0;
  border: 0;
  color: var(--accent-hover);
  background: transparent;
  font-size: inherit;
  font-weight: 750;
  cursor: pointer;
}

.review-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 24px;
}

.review-header__copy {
  min-width: 0;
}

.section-kicker,
.section-label {
  margin: 0 0 7px;
  color: var(--accent);
  font-size: 10px;
  font-weight: 800;
  letter-spacing: 0.12em;
  text-transform: uppercase;
}

.review-header h2,
.evidence-panel h3,
.rail-panel h3 {
  margin: 0;
  color: var(--text);
  font-weight: 780;
  letter-spacing: -0.035em;
}

.review-header h2 {
  font-size: clamp(25px, 3vw, 35px);
}

.review-header__copy > p:last-child {
  max-width: 620px;
  margin: 8px 0 0;
  color: var(--text-muted);
  font-size: 13px;
}

.review-header__actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  flex-wrap: wrap;
}

.command-panel,
.evidence-panel,
.rail-panel {
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  background: var(--panel);
  box-shadow: var(--shadow-panel);
}

.command-panel {
  overflow: hidden;
}

.command-panel__top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  padding: 15px 18px;
  border-bottom: 1px solid var(--border);
}

.command-panel__top > div {
  display: flex;
  align-items: baseline;
  gap: 9px;
  min-width: 0;
}

.command-panel__ticket {
  color: var(--accent-hover);
  font-size: 11px;
  font-weight: 750;
}

.command-panel__top strong {
  overflow: hidden;
  color: var(--text);
  font-size: 13px;
  font-weight: 720;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.command-panel__meta {
  flex: none;
  color: var(--text-faint);
  font-size: 11px;
}

.review-flow {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
}

.review-flow__phase {
  display: grid;
  gap: 2px;
  min-height: 82px;
  padding: 12px 15px;
  border: 0;
  border-right: 1px solid var(--border);
  color: var(--text-secondary);
  background: transparent;
  text-align: left;
  cursor: pointer;
  transition: background-color 0.16s ease, box-shadow 0.16s ease;
}

.review-flow__phase:last-child {
  border-right: 0;
}

.review-flow__phase:hover {
  background: var(--hover);
}

.review-flow__phase--current {
  background: var(--accent-soft);
  box-shadow: inset 0 3px 0 var(--accent);
}

.review-flow__phase--complete .review-flow__state {
  color: var(--success);
}

.review-flow__phase--active .review-flow__state {
  color: var(--accent-hover);
}

.review-flow__state {
  color: var(--text-faint);
  font-size: 10px;
  font-weight: 750;
}

.review-flow__phase strong {
  color: var(--text);
  font-size: 13px;
  font-weight: 740;
}

.review-flow__phase small {
  color: var(--text-muted);
  font-size: 10px;
  line-height: 1.4;
}

.action-notice {
  margin: -2px 0 0;
  padding: 9px 12px;
  border: 1px solid rgba(9, 105, 218, 0.24);
  border-radius: var(--radius-sm);
  color: var(--accent-hover);
  background: var(--accent-soft);
  font-size: 12px;
  font-weight: 650;
}

.review-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 338px;
  gap: 14px;
  align-items: start;
}

.evidence-panel {
  min-height: 620px;
  overflow: hidden;
}

.evidence-panel__head,
.rail-panel__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 17px 18px 14px;
}

.evidence-panel__head {
  border-bottom: 1px solid var(--border);
}

.evidence-panel h3,
.rail-panel h3 {
  font-size: 17px;
}

.evidence-panel__stats,
.rail-panel__count {
  flex: none;
  padding: 4px 7px;
  border-radius: 999px;
  color: var(--accent-hover);
  background: var(--accent-soft);
  font-size: 10px;
  font-weight: 750;
}

.anchor-strip {
  display: grid;
  grid-template-columns: auto repeat(3, minmax(0, 1fr));
  gap: 8px;
  align-items: center;
  padding: 12px 18px;
  border-bottom: 1px solid var(--border);
  background: var(--panel-2);
}

.anchor-strip__label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--text-secondary);
  font-size: 11px;
  font-weight: 750;
  white-space: nowrap;
}

.anchor-strip code {
  display: grid;
  gap: 1px;
  min-width: 0;
  padding: 5px 7px;
  border: 1px solid var(--border);
  border-radius: 7px;
  color: var(--ink-soft);
  background: var(--panel);
  font-size: 10px;
  overflow: hidden;
}

.anchor-strip code small {
  color: var(--text-faint);
  font-family: var(--font-sans);
  font-size: 9px;
  font-weight: 700;
}

.anchor-strip code {
  text-overflow: ellipsis;
  white-space: nowrap;
}

.diff-caption {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 10px 18px;
  color: var(--text-muted);
  font-size: 11px;
}

.diff-caption__active {
  color: var(--accent-hover);
  font-weight: 700;
}

.diff-view {
  overflow: auto;
  padding: 0 0 16px;
  background: var(--canvas);
}

.diff-line {
  display: grid;
  grid-template-columns: 20px 40px 40px minmax(max-content, 1fr);
  width: 100%;
  min-width: 660px;
  padding: 0;
  border: 0;
  color: var(--text-secondary);
  background: transparent;
  font-size: 12px;
  line-height: 1.75;
  text-align: left;
  cursor: pointer;
  transition: background-color 0.14s ease, box-shadow 0.14s ease;
}

.diff-line:hover {
  background: var(--hover);
}

.diff-line--add {
  background: rgba(35, 114, 79, 0.07);
}

.diff-line--remove {
  background: rgba(173, 63, 55, 0.07);
}

.diff-line--highlighted {
  background: rgba(9, 105, 218, 0.14);
  box-shadow: inset 3px 0 0 var(--accent);
}

.diff-line--selected {
  outline: 2px solid rgba(9, 105, 218, 0.62);
  outline-offset: -2px;
}

.diff-line__marker,
.diff-line__number {
  color: var(--text-faint);
  background: rgba(23, 37, 54, 0.03);
  text-align: right;
  user-select: none;
}

.diff-line__marker {
  padding-right: 5px;
  text-align: center;
}

.diff-line--add .diff-line__marker {
  color: var(--success);
}

.diff-line--remove .diff-line__marker {
  color: var(--danger);
}

.diff-line__number {
  padding-right: 7px;
  border-left: 1px solid var(--border);
  font-size: 10px;
}

.diff-line code {
  display: block;
  min-width: 0;
  padding: 0 12px;
  color: inherit;
  font: inherit;
  white-space: pre;
}

.review-rail {
  display: grid;
  gap: 12px;
}

.rail-panel {
  overflow: hidden;
}

.rail-panel__head {
  padding-bottom: 12px;
}

.findings-list {
  display: grid;
}

.finding-row {
  display: grid;
  gap: 5px;
  padding: 12px 15px;
  border: 0;
  border-top: 1px solid var(--border);
  color: var(--text-secondary);
  background: transparent;
  text-align: left;
  cursor: pointer;
  transition: background-color 0.14s ease, box-shadow 0.14s ease;
}

.finding-row:hover {
  background: var(--hover);
}

.finding-row--active {
  background: rgba(9, 105, 218, 0.085);
  box-shadow: inset 3px 0 0 var(--accent);
}

.finding-row__top {
  display: flex;
  align-items: center;
  gap: 5px;
}

.finding-row__top > span {
  color: var(--accent-hover);
  font-size: 10px;
  font-weight: 750;
}

.finding-row__top :deep(.g-badge) {
  font-size: 9px;
}

.finding-row__top em {
  margin-left: auto;
  color: var(--warning);
  font-size: 10px;
  font-style: normal;
  font-weight: 700;
}

.finding-row__top .finding-row__verified {
  color: var(--success);
}

.finding-row strong {
  color: var(--text);
  font-size: 12px;
  font-weight: 720;
  line-height: 1.45;
}

.finding-row small {
  color: var(--text-faint);
  font-size: 10px;
}

.finding-row > span:last-child {
  color: var(--text-muted);
  font-size: 11px;
  line-height: 1.45;
}

.finding-control {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 10px 12px;
  border-top: 1px solid var(--border);
  background: var(--panel-2);
  color: var(--text-muted);
  font-size: 11px;
}

.audit-panel__running {
  color: var(--accent-hover);
  font-family: var(--font-mono);
  font-size: 11px;
  font-weight: 750;
}

.review-steps {
  display: grid;
  gap: 0;
  border-top: 1px solid var(--border);
}

.review-steps > div {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 14px;
  border-bottom: 1px solid var(--border);
  color: var(--text-faint);
}

.review-steps > div:last-child {
  border-bottom: 0;
}

.review-steps span {
  width: 38px;
  color: var(--text-faint);
  font-size: 10px;
  font-weight: 700;
}

.review-steps strong {
  color: var(--text-secondary);
  font-size: 12px;
  font-weight: 650;
}

.review-steps__item--done span,
.review-steps__item--done strong {
  color: var(--success);
}

.review-steps__item--active {
  background: var(--accent-soft);
}

.review-steps__item--active span,
.review-steps__item--active strong {
  color: var(--accent-hover);
}

.audit-list {
  display: grid;
  gap: 0;
  margin: 0;
  padding: 0;
  list-style: none;
  border-top: 1px solid var(--border);
}

.audit-list li {
  display: grid;
  grid-template-columns: 8px minmax(0, 1fr);
  gap: 8px;
  padding: 11px 14px;
  border-bottom: 1px solid var(--border);
}

.audit-list li:last-child {
  border-bottom: 0;
}

.audit-list__tone {
  width: 6px;
  height: 6px;
  margin-top: 6px;
  border-radius: 50%;
  background: var(--border-strong);
}

.audit-list__tone--accent {
  background: var(--accent);
}

.audit-list__tone--success {
  background: var(--success);
}

.audit-list__tone--warning {
  background: var(--warning);
}

.audit-list__tone--danger {
  background: var(--danger);
}

.audit-list strong {
  color: var(--text-secondary);
  font-size: 11px;
  font-weight: 720;
}

.audit-list p {
  margin: 2px 0 3px;
  color: var(--text-muted);
  font-size: 10px;
  line-height: 1.45;
}

.audit-list time {
  color: var(--text-faint);
  font-family: var(--font-mono);
  font-size: 9px;
}

.decision-summary {
  margin: 0;
  padding: 0 15px 12px;
  color: var(--text-muted);
  font-size: 11px;
  line-height: 1.5;
}

.decision-actions {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 7px;
  padding: 0 15px 15px;
}

.decision-actions :deep(.g-btn) {
  width: 100%;
}

.gate-list {
  display: grid;
  gap: 0;
  margin: 0 0 12px;
  padding: 0;
  list-style: none;
  border-top: 1px solid var(--border);
}

.gate-list li {
  display: grid;
  grid-template-columns: 42px minmax(0, 1fr);
  gap: 8px;
  align-items: start;
  padding: 10px 14px;
  border-bottom: 1px solid var(--border);
}

.gate-list__state {
  display: inline-grid;
  min-height: 19px;
  place-items: center;
  border-radius: 5px;
  color: var(--text-muted);
  background: var(--panel-2);
  font-size: 9px;
  font-weight: 800;
}

.gate-list__state--passed {
  color: var(--success);
  background: var(--success-soft);
}

.gate-list strong,
.gate-list small {
  display: block;
}

.gate-list strong {
  color: var(--text-secondary);
  font-size: 11px;
  font-weight: 700;
}

.gate-list small {
  margin-top: 2px;
  color: var(--text-faint);
  font-size: 10px;
  line-height: 1.4;
}

.gate-panel > :deep(.g-btn) {
  margin: 0 14px 14px;
  width: calc(100% - 28px);
}

.decision-modal {
  display: grid;
  gap: 15px;
}

.decision-modal > p {
  margin: 0;
  color: var(--text-secondary);
  font-size: 13px;
  line-height: 1.55;
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

.confirmation-field {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  color: var(--text-secondary);
  font-size: 12px;
  line-height: 1.5;
  cursor: pointer;
}

.confirmation-field input {
  width: 15px;
  height: 15px;
  margin: 2px 0 0;
  flex: none;
  accent-color: var(--accent);
}

.modal-error {
  margin: 0;
  color: var(--danger);
  font-size: 12px;
  font-weight: 650;
}

@media (max-width: 980px) {
  .review-header {
    align-items: flex-start;
    flex-direction: column;
  }

  .review-header__actions {
    justify-content: flex-start;
  }

  .review-grid {
    grid-template-columns: minmax(0, 1fr) 310px;
  }
}

@media (max-width: 820px) {
  .review-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .review-rail {
    grid-template-columns: minmax(0, 1fr);
  }
}

@media (max-width: 640px) {
  .review-page {
    padding: 16px 14px 24px;
  }

  .command-panel__top {
    align-items: stretch;
    flex-direction: column;
  }

  .command-panel__top > div {
    align-items: flex-start;
    flex-wrap: wrap;
  }

  .command-panel__top :deep(.g-btn) {
    width: 100%;
  }

  .review-flow {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .review-flow__phase:nth-child(2) {
    border-right: 0;
  }

  .review-flow__phase:nth-child(-n + 2) {
    border-bottom: 1px solid var(--border);
  }

  .anchor-strip {
    grid-template-columns: minmax(0, 1fr);
  }

  .anchor-strip__label {
    margin-bottom: 2px;
  }

  .diff-caption {
    align-items: flex-start;
    flex-direction: column;
  }

}
</style>
