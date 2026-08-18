<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { getTicket } from '@/api/tickets';
import {
  abortSession,
  getHistory,
  listTicketSessions,
  startSession as apiStartSession,
} from '@/api/sessions';
import { postSessionStream } from '@/api/stream';
import { listAgentConfigs } from '@/api/agentConfig';
import { renderMarkdown } from '@/utils/markdown';
import { GAvatar, GBadge, GButton, GIcon, GSkeleton } from '@/components/ui';
import { TICKET_STAGE_LABELS } from '@/types/stage';
import type { AgentConfig } from '@/types/agentConfig';
import type { Session } from '@/types/session';
import type { SessionMessage, ToolCallExecution } from '@/types/session-message';
import type { Ticket } from '@/types/ticket';
import type { TicketStage } from '@/types/stage';
import type { GIconName } from '@/components/ui/GIcon.vue';

const route = useRoute();
const router = useRouter();
const no = String(route.params.no ?? 'T-104');
const projectId = String(route.params.projectId ?? '');

const ticket = ref<Ticket>({
  no,
  title: '读取工单中...',
  stage: 'PENDING' as const,
  targetRef: '',
  reviewRound: null as number | null,
  treeHash: null as string | null,
  baseCommit: null as string | null,
  execTokenTotal: null,
  execTokenSource: null,
  agentConfigId: null as string | null,
  createdAt: '',
  updatedAt: '',
});

const loading = ref(true);
const loadError = ref('');
const sessions = ref<Session[]>([]);
const selectedId = ref('');
const messagesBySession = ref<Record<string, SessionMessage[]>>({});
const isCreatingSession = ref(false);
const isAborting = ref(false);
const input = ref('');
const sending = ref(false);
const isStreaming = ref(false);
const streamingThoughtSeconds = ref(0);
let thoughtTimer: ReturnType<typeof setInterval> | null = null;
const feedback = ref('');
const agentConfigs = ref<AgentConfig[]>([]);
const textareaRef = ref<HTMLTextAreaElement | null>(null);
const messagesContainerRef = ref<HTMLElement | null>(null);
const copiedId = ref<string | null>(null);
const openToolDetails = ref<Record<string, boolean>>({});
const openThoughts = ref<Record<string, boolean>>({});

let currentAbortController: AbortController | null = null;

const fallbackAgent: AgentConfig = {
  id: 'claude-sonnet-default',
  name: 'Claude Sonnet',
  cli: 'CLAUDE',
  providerId: 'newapi',
  model: 'claude-3-5-sonnet-20241022',
  systemPrompt: null,
  extraFlags: [],
  description: null,
};

function startThoughtTimer() {
  if (thoughtTimer) clearInterval(thoughtTimer);
  streamingThoughtSeconds.value = 0;
  thoughtTimer = setInterval(() => {
    streamingThoughtSeconds.value += 1;
  }, 1000);
}

function stopThoughtTimer() {
  if (thoughtTimer) {
    clearInterval(thoughtTimer);
    thoughtTimer = null;
  }
}

function getOrCreateStreamingAssistantMessage(sessionId: string): SessionMessage {
  const list = messagesBySession.value[sessionId] ?? (messagesBySession.value[sessionId] = []);
  const last = list[list.length - 1];
  if (last && last.role === 'ASSISTANT' && last.status === 'STREAMING') {
    return last;
  }
  const newMsg: SessionMessage = {
    id: `stream-${Date.now()}`,
    sessionId,
    role: 'ASSISTANT',
    content: '',
    reasoningContent: '',
    toolCalls: [],
    status: 'STREAMING',
    usage: null,
    degraded: false,
    timestamp: new Date().toISOString(),
  };
  list.push(newMsg);
  return newMsg;
}

function dedupeMessages(list: SessionMessage[]): SessionMessage[] {
  const seen = new Set<string>();
  return list.filter((message) => {
    if (seen.has(message.id)) return false;
    seen.add(message.id);
    return true;
  });
}

function scrollToBottom(smooth = true) {
  nextTick(() => {
    if (messagesContainerRef.value) {
      messagesContainerRef.value.scrollTo({
        top: messagesContainerRef.value.scrollHeight,
        behavior: smooth ? 'smooth' : 'auto',
      });
    }
  });
}

async function loadTicket() {
  loading.value = true;
  loadError.value = '';
  try {
    const loaded = await getTicket(no, projectId);
    ticket.value = {
      no: loaded.no,
      title: loaded.title,
      stage: loaded.stage,
      targetRef: loaded.targetRef,
      reviewRound: loaded.reviewRound,
      treeHash: loaded.treeHash,
      baseCommit: loaded.baseCommit,
      execTokenTotal: loaded.execTokenTotal,
      execTokenSource: loaded.execTokenSource,
      agentConfigId: loaded.agentConfigId,
      createdAt: loaded.createdAt,
      updatedAt: loaded.updatedAt,
      ...(loaded.project ? { project: loaded.project } : {}),
      ...(loaded.branch ? { branch: loaded.branch } : {}),
    };
  } catch {
    loadError.value = '无法读取工单数据，请确认工单仍存在且 Gate 后端正在运行。';
    ticket.value.title = '工单数据不可用';
  } finally {
    loading.value = false;
  }
}

async function loadAgentConfigs() {
  try {
    agentConfigs.value = await listAgentConfigs();
  } catch {
    agentConfigs.value = [];
  }
}

async function loadSessions() {
  try {
    sessions.value = await listTicketSessions(no);
    if (sessions.value.length && !sessions.value.some((item) => item.id === selectedId.value)) {
      selectedId.value = sessions.value[0]!.id;
    }
  } catch {
    feedback.value = '无法读取会话列表，请确认 Gate 后端正在运行。';
  }
}

async function loadHistory(sid: string) {
  try {
    const history = await getHistory(sid);
    messagesBySession.value = {
      ...messagesBySession.value,
      [sid]: dedupeMessages(history),
    };
    scrollToBottom(false);
  } catch {
    feedback.value = `无法读取会话 ${sid} 的历史消息。`;
  }
}

const session = computed(() => sessions.value.find((item) => item.id === selectedId.value) ?? sessions.value[0] ?? null);
const messages = computed(() => (session.value ? messagesBySession.value[session.value.id] ?? [] : []));
const activeTab = ref<'active' | 'archived'>('active');
const contextMenuVisible = ref(false);
const contextMenuX = ref(0);
const contextMenuY = ref(0);
const contextMenuTarget = ref<Session | null>(null);

function handleContextMenu(event: MouseEvent, item: Session) {
  event.preventDefault();
  contextMenuTarget.value = item;
  contextMenuX.value = event.clientX;
  contextMenuY.value = event.clientY;
  contextMenuVisible.value = true;
}

function closeContextMenu() {
  contextMenuVisible.value = false;
  contextMenuTarget.value = null;
}

function moveToActive(item: Session) {
  item.status = 'ACTIVE';
  item.finishedAt = null;
  closeContextMenu();
  feedback.value = `会话 ${item.id} 已移入活跃。`;
}

function moveToArchived(item: Session) {
  item.status = 'CLOSED';
  if (!item.finishedAt) item.finishedAt = new Date().toISOString();
  closeContextMenu();
  feedback.value = `会话 ${item.id} 已移入归档。`;
}

const activeSessions = computed(() =>
  sessions.value
    .filter((item) => item.status === 'ACTIVE')
    .sort((a, b) => {
      const ta = new Date(b.finishedAt ?? b.startedAt).getTime();
      const tb = new Date(a.finishedAt ?? a.startedAt).getTime();
      return ta - tb;
    }),
);
const closedSessions = computed(() =>
  sessions.value
    .filter((item) => item.status !== 'ACTIVE')
    .sort((a, b) => {
      const ta = new Date(b.finishedAt ?? b.startedAt).getTime();
      const tb = new Date(a.finishedAt ?? a.startedAt).getTime();
      return ta - tb;
    }),
);

const displayedSessions = computed(() => {
  if (activeTab.value === 'active') {
    return activeSessions.value;
  }
  return closedSessions.value;
});
const preferredAgent = computed(() =>
  agentConfigs.value.find((agent) => agent.id === ticket.value.agentConfigId) ?? agentConfigs.value[0] ?? fallbackAgent,
);
const activeAgent = computed(() => (session.value ? agentFor(session.value) : preferredAgent.value));

const quickPrompts = computed<{ icon: GIconName; label: string; text: string }[]>(() => [
  {
    icon: 'spark',
    label: '推进任务',
    text: `请继续处理工单 ${ticket.value.no}，基于当前代码库上下文给出下一步落地计划或实现修改。`,
  },
  {
    icon: 'shield',
    label: '准备预审',
    text: `请检查当前修改是否符合预审准入要求，并整理提交记录与验证结果。`,
  },
  {
    icon: 'git-branch',
    label: '报告进展',
    text: '请总结目前已完成的工作、关键修改点以及是否存在阻塞项。',
  },
  {
    icon: 'search',
    label: '诊断代码',
    text: '请对当前相关实现做静态诊断，检查潜在边界异常、类型缺陷与架构规范。',
  },
]);

function agentFor(value: Session) {
  return agentConfigs.value.find((agent) => agent.id === value.agentConfigId) ?? fallbackAgent;
}

function stageTone(stage: TicketStage): 'neutral' | 'accent' | 'success' | 'warning' | 'danger' {
  if (stage === 'REJECTED' || stage === 'CANCELLED') return 'danger';
  if (stage === 'DONE' || stage === 'READY_TO_PUBLISH') return 'success';
  if (stage === 'NEEDS_HUMAN') return 'warning';
  if (stage === 'PRESUBMITTED' || stage === 'IN_REVIEW') return 'accent';
  return 'neutral';
}

function sessionTone(value: Session): 'neutral' | 'success' | 'warning' | 'danger' {
  if (value.status === 'ACTIVE') return 'success';
  if (value.status === 'ABORTED') return 'danger';
  return 'neutral';
}

function sessionLabel(value: Session) {
  if (value.status === 'ACTIVE') return '活跃';
  if (value.status === 'ABORTED') return '已中止';
  return '已结束';
}

function formatTime(iso: string | null | undefined) {
  if (!iso) return '刚刚';
  return new Date(iso).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', hour12: false });
}

function formatTokens(value: number | null | undefined) {
  if (value == null) return '未回写';
  return new Intl.NumberFormat('zh-CN').format(value);
}

function lastMessageFor(sessionId: string) {
  const list = messagesBySession.value[sessionId] ?? [];
  return list.length ? list[list.length - 1]!.content : '尚无消息，等待首条指令。';
}

function extractToolSummary(tool: ToolCallExecution): string {
  if (tool.command) return tool.command;
  if (!tool.argumentsJson) return tool.name;
  try {
    const parsed = typeof tool.argumentsJson === 'string' ? JSON.parse(tool.argumentsJson) : tool.argumentsJson;
    if (parsed.query) return `query: "${parsed.query}"`;
    if (parsed.file_path || parsed.path) return String(parsed.file_path || parsed.path);
    if (parsed.command) return String(parsed.command);
    if (parsed.pattern) return `pattern: ${parsed.pattern}`;
  } catch {
    // ignore
  }
  return tool.name;
}

async function createSession(): Promise<Session | null> {
  const agent = preferredAgent.value;
  isCreatingSession.value = true;
  try {
    const created = await apiStartSession(ticket.value.no, {
      agentConfigId: agent.id,
      ...(input.value.trim() ? { initialPrompt: input.value.trim() } : {}),
    });
    sessions.value.unshift(created);
    messagesBySession.value[created.id] = [];
    selectedId.value = created.id;
    feedback.value = `已建立协作会话 ${created.id}`;
    return created;
  } catch {
    feedback.value = '创建会话失败，请检查智能体配置与后端状态。';
    return null;
  } finally {
    isCreatingSession.value = false;
  }
}

async function startSession() {
  await createSession();
}

async function abortCurrentGeneration() {
  if (!session.value || isAborting.value) return;
  isAborting.value = true;
  isStreaming.value = false;
  stopThoughtTimer();
  if (currentAbortController) {
    currentAbortController.abort();
    currentAbortController = null;
  }
  
  // 乐观更新界面上最后一条 Assistant 消息状态为已中止
  const list = messagesBySession.value[session.value.id] ?? [];
  const last = list[list.length - 1];
  if (last && last.role === 'ASSISTANT') {
    last.status = 'INTERRUPTED';
    if (!last.content) {
      last.content = '⚠️ 生成已由用户主动中止。';
    }
  }
  
  feedback.value = '正在中止智能体生成...';
  try {
    await abortSession(session.value.id);
    feedback.value = '智能体生成已成功中止。';
  } catch {
    feedback.value = '中止请求已发送。';
  } finally {
    isAborting.value = false;
  }
}

function resumeSession() {
  if (!session.value) {
    void startSession();
    return;
  }
  if (session.value.status !== 'ACTIVE') {
    session.value.status = 'ACTIVE';
    session.value.finishedAt = null;
    feedback.value = `${session.value.id} 已恢复为活跃状态。`;
  }
  focusTextarea();
}

async function endSession() {
  if (!session.value || session.value.status !== 'ACTIVE') return;
  try {
    await abortSession(session.value.id);
  } catch {
    // 允许离线或无后台任务状态下本地结束
  }
  session.value.status = 'CLOSED';
  session.value.finishedAt = new Date().toISOString();
  isStreaming.value = false;
  stopThoughtTimer();
  if (currentAbortController) {
    currentAbortController.abort();
    currentAbortController = null;
  }
  feedback.value = `${session.value.id} 已结束。`;
}

function applyPrompt(text: string) {
  if (!session.value) {
    void startSession();
  }
  input.value = text;
  focusTextarea();
  adjustTextareaHeight();
}

function focusTextarea() {
  nextTick(() => {
    textareaRef.value?.focus();
  });
}

function adjustTextareaHeight() {
  nextTick(() => {
    if (!textareaRef.value) return;
    textareaRef.value.style.height = 'auto';
    const newHeight = Math.min(Math.max(textareaRef.value.scrollHeight, 40), 160);
    textareaRef.value.style.height = `${newHeight}px`;
  });
}

async function ensureActiveSession(): Promise<Session | null> {
  if (!session.value) return createSession();
  if (session.value.status !== 'ACTIVE') {
    session.value.status = 'ACTIVE';
    session.value.finishedAt = null;
  }
  return session.value;
}

async function copyContent(id: string, text: string) {
  try {
    await navigator.clipboard.writeText(text);
    copiedId.value = id;
    setTimeout(() => {
      if (copiedId.value === id) {
        copiedId.value = null;
      }
    }, 2000);
  } catch {
    feedback.value = '复制失败，请手动选取文字复制。';
  }
}

function toggleToolDetails(key: string) {
  openToolDetails.value[key] = !openToolDetails.value[key];
}

function toggleThoughtDetails(msgId: string) {
  openThoughts.value[msgId] = !openThoughts.value[msgId];
}

async function send() {
  const text = input.value.trim();
  if (!text || sending.value) return;
  const current = await ensureActiveSession();
  if (!current) return;

  const list = messagesBySession.value[current.id] ?? (messagesBySession.value[current.id] = []);
  const localId = `local-${Date.now()}`;
  list.push({
    id: localId,
    sessionId: current.id,
    role: 'USER',
    content: text,
    toolCalls: [],
    usage: null,
    degraded: false,
    timestamp: new Date().toISOString(),
  });

  input.value = '';
  adjustTextareaHeight();
  scrollToBottom();
  sending.value = true;
  isStreaming.value = true;
  startThoughtTimer();

  const assistantMsg = getOrCreateStreamingAssistantMessage(current.id);
  currentAbortController = new AbortController();

  try {
    await postSessionStream(
      current.id,
      text,
      {
        onToken: (delta) => {
          assistantMsg.content += delta;
          scrollToBottom();
        },
        onThinking: (delta) => {
          assistantMsg.reasoningContent = (assistantMsg.reasoningContent ?? '') + delta;
          openThoughts.value[assistantMsg.id] = true;
          scrollToBottom();
        },
        onToolCall: (chunk) => {
          const existing = assistantMsg.toolCalls?.find((t) => t.id === chunk.callId);
          if (existing) {
            if (chunk.argumentDelta) existing.argumentsJson = (existing.argumentsJson || '') + chunk.argumentDelta;
            if (chunk.result) existing.resultJson = chunk.result;
            if (chunk.status) existing.status = chunk.status as any;
          } else {
            assistantMsg.toolCalls?.push({
              id: chunk.callId,
              name: chunk.toolName,
              status: chunk.status as any,
              argumentsJson: chunk.argumentDelta || '',
              resultJson: chunk.result || null,
            });
          }
          scrollToBottom();
        },
        onUsage: (usage) => {
          assistantMsg.usage = {
            promptTokens: usage.promptTokens ?? null,
            completionTokens: usage.completionTokens ?? null,
            totalTokens: usage.totalTokens ?? null,
          };
        },
        onDone: () => {
          assistantMsg.status = 'SUCCESS';
          isStreaming.value = false;
          stopThoughtTimer();
          currentAbortController = null;
        },
        onError: (err) => {
          assistantMsg.status = 'ERROR';
          if (!assistantMsg.content) {
            assistantMsg.content = `⚠️ 执行错误: ${err.message}`;
          }
          isStreaming.value = false;
          stopThoughtTimer();
          currentAbortController = null;
        },
      },
      currentAbortController.signal
    );
  } catch (err: any) {
    if (err.name !== 'AbortError') {
      feedback.value = '消息发送失败，请确认 Gate 后端连接正常。';
    }
    isStreaming.value = false;
    stopThoughtTimer();
  } finally {
    sending.value = false;
    currentAbortController = null;
  }
}

function handleKeydown(event: KeyboardEvent) {
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault();
    send();
  }
}

watch(input, () => {
  adjustTextareaHeight();
});

watch(selectedId, async (id) => {
  if (!id) return;
  await loadHistory(id);
});

onMounted(async () => {
  await Promise.all([loadTicket(), loadAgentConfigs()]);
  await loadSessions();
});

onBeforeUnmount(() => {
  stopThoughtTimer();
  if (currentAbortController) {
    currentAbortController.abort();
    currentAbortController = null;
  }
});
</script>

<template>
  <div class="session-workbench">
    <!-- 加载骨架屏 -->
    <div v-if="loading" class="session-skeleton" role="status" aria-label="正在读取会话数据">
      <div class="session-skeleton__context">
        <GSkeleton width="180px" height="24px" />
        <GSkeleton width="120px" height="16px" />
      </div>
      <div class="session-skeleton__body">
        <GSkeleton width="280px" height="100%" />
        <div class="session-skeleton__main">
          <GSkeleton width="100%" height="48px" />
          <div class="session-skeleton__messages">
            <GSkeleton width="60%" height="64px" />
            <GSkeleton width="80%" height="96px" />
            <GSkeleton width="50%" height="48px" />
          </div>
          <GSkeleton width="100%" height="80px" />
        </div>
      </div>
    </div>

    <!-- 错误横条 -->
    <div v-else-if="loadError" class="session-error" role="alert">
      <GIcon name="alert-triangle" :size="16" />
      <span>{{ loadError }}</span>
      <GButton variant="secondary" size="sm" @click="loadTicket">重试</GButton>
    </div>

    <!-- 主工作台 -->
    <div v-else class="od-workbench">
      <!-- 顶部轻量工单上下文横条 (Ticket Context Strip) -->
      <header class="od-ticket-strip">
        <div class="od-ticket-strip__left">
          <button
            type="button"
            class="od-back-btn"
            aria-label="返回工单看板"
            @click="router.push({ name: 'kanban', params: { projectId } })"
          >
            <GIcon name="chevron-left" :size="14" />
            <span>工单看板</span>
          </button>
          <span class="od-divider">/</span>
          <span class="od-ticket-no mono">{{ ticket.no }}</span>
          <span class="od-ticket-title">{{ ticket.title }}</span>
          <GBadge :tone="stageTone(ticket.stage)" size="sm">
            {{ TICKET_STAGE_LABELS[ticket.stage] }}
          </GBadge>
        </div>

        <div class="od-ticket-strip__right">
          <span class="od-target-ref mono" title="目标分支">
            <GIcon name="git-branch" :size="12" />
            {{ ticket.targetRef || 'refs/heads/main' }}
          </span>
          <GButton
            variant="ghost"
            size="sm"
            @click="router.push({ name: 'review', params: { projectId, no: ticket.no } })"
          >
            <GIcon name="shield" :size="13" />
            <span>进入审核台</span>
          </GButton>
        </div>
      </header>

      <!-- 全局新建会话加载遮罩 (Loading Overlay) -->
      <Teleport to="body">
        <div v-if="isCreatingSession" class="od-modal-backdrop od-modal-backdrop--loading">
          <div class="od-creating-card">
            <div class="od-creating-spinner" />
            <div class="od-creating-title">正在准备协作开发环境...</div>
            <div class="od-creating-desc">
              正在克隆工单工作区 <code>{{ ticket.no }}</code> 并启动 {{ activeAgent.name }} 运行时
            </div>
          </div>
        </div>
      </Teleport>

      <!-- 核心工作区：双栏布局 -->
      <div class="od-workspace-layout">
        <!-- 左侧：会话列表轨道 (Session Rail) -->
        <aside class="od-session-rail" aria-label="执行会话列表" @click="closeContextMenu">
          <div class="od-rail-header">
            <div class="od-rail-header__title">
              <span class="od-rail-label">执行会话 (Sessions)</span>
              <span class="od-rail-count mono">{{ sessions.length }}</span>
            </div>
            <GButton
              variant="secondary"
              size="sm"
              class="od-new-session-btn"
              title="新建会话"
              @click="startSession"
            >
              <GIcon name="plus" :size="13" />
              <span>新建</span>
            </GButton>
          </div>

          <!-- 分类 Tab 切换 -->
          <div class="od-rail-tabs">
            <button
              type="button"
              class="od-rail-tab"
              :class="{ 'is-active': activeTab === 'active' }"
              @click="activeTab = 'active'"
            >
              <span class="od-dot od-dot--active" />
              <span>活跃 ({{ activeSessions.length }})</span>
            </button>
            <button
              type="button"
              class="od-rail-tab"
              :class="{ 'is-active': activeTab === 'archived' }"
              @click="activeTab = 'archived'"
            >
              <span class="od-dot od-dot--idle" />
              <span>归档 ({{ closedSessions.length }})</span>
            </button>
          </div>

          <div v-if="displayedSessions.length" class="od-session-list" role="list">
            <button
              v-for="item in displayedSessions"
              :key="item.id"
              type="button"
              class="od-session-card"
              :class="{ 'is-active': session && session.id === item.id }"
              @click="selectedId = item.id"
              @contextmenu="handleContextMenu($event, item)"
            >
              <div class="od-session-card__head">
                <div class="od-session-card__agent">
                  <span class="od-cli-badge mono">{{ item.cli }}</span>
                  <span class="od-agent-name">{{ agentFor(item).name }}</span>
                </div>
                <span class="od-status-pill" :class="`od-status-pill--${sessionTone(item)}`">
                  <span class="od-status-pill__dot" />
                  {{ sessionLabel(item) }}
                </span>
              </div>

              <div class="od-session-card__preview">
                {{ lastMessageFor(item.id) }}
              </div>

              <div class="od-session-card__foot">
                <span class="od-session-card__id mono">{{ item.id }}</span>
                <span class="od-session-card__time mono">{{ formatTime(item.finishedAt ?? item.startedAt) }}</span>
              </div>
            </button>
          </div>

          <div v-else class="od-rail-empty">
            <div class="od-rail-empty__icon">
              <GIcon name="chat" :size="20" />
            </div>
            <h4>{{ activeTab === 'active' ? '暂无活跃会话' : '暂无归档会话' }}</h4>
            <p>{{ activeTab === 'active' ? '开启会话后，智能体将挂载当前工单的完整 Git 运行时上下文。' : '已结束或被终止的会话将自动沉淀在归档列表中。' }}</p>
            <GButton v-if="activeTab === 'active'" variant="primary" size="sm" @click="startSession">
              立即创建
            </GButton>
          </div>
        </aside>

        <!-- 右侧：现代 Agent 会话与执行 Transcript 舞台 -->
        <main class="od-chat-stage" aria-label="对话与执行转录台">
          <!-- 会话顶栏 -->
          <div class="od-stage-topbar">
            <template v-if="session">
              <div class="od-agent-info">
                <div class="od-agent-info__avatar-wrap">
                  <GAvatar :name="activeAgent.name" size="md" tone="accent" />
                  <span v-if="session.status === 'ACTIVE'" class="od-avatar-badge" />
                </div>
                <div class="od-agent-info__text">
                  <div class="od-agent-info__row">
                    <h3 class="od-agent-info__name">{{ activeAgent.name }}</h3>
                    <span class="od-agent-cli-tag mono">{{ activeAgent.cli }}</span>
                  </div>
                  <div class="od-agent-info__sub mono">
                    <span>{{ session.id }}</span>
                    <span class="od-divider">·</span>
                    <span>{{ activeAgent.model }}</span>
                  </div>
                </div>
              </div>

              <div class="od-stage-actions">
                <div v-if="isStreaming" class="od-streaming-indicator">
                  <span class="od-pulse-dot" />
                  <span>Agent 思考与执行中 ({{ streamingThoughtSeconds }}s)...</span>
                </div>
                <button
                  v-if="session.status === 'ACTIVE'"
                  type="button"
                  class="od-btn-ghost od-btn-ghost--danger"
                  @click="endSession"
                >
                  <GIcon name="x" :size="13" />
                  <span>结束会话</span>
                </button>
                <button
                  v-else
                  type="button"
                  class="od-btn-ghost od-btn-ghost--accent"
                  @click="resumeSession"
                >
                  <GIcon name="refresh" :size="13" />
                  <span>继续会话</span>
                </button>
              </div>
            </template>

            <template v-else>
              <div class="od-agent-info">
                <div class="od-agent-info__avatar-wrap">
                  <GAvatar name="Gate AI" size="md" tone="neutral" />
                </div>
                <div class="od-agent-info__text">
                  <h3 class="od-agent-info__name">就绪模式</h3>
                  <span class="od-agent-info__sub">点击下方快捷引导或输入指令即可开启会话</span>
                </div>
              </div>
            </template>
          </div>

          <!-- 消息流与 Transcript 步骤展示区 -->
          <div ref="messagesContainerRef" class="od-message-stream" aria-label="消息历史">
            <template v-if="session">
              <div v-if="messages.length" class="od-message-list">
                <div
                  v-for="msg in messages"
                  :key="msg.id"
                  class="od-message-item"
                  :class="`od-message-item--${msg.role.toLowerCase()}`"
                >
                  <!-- 角色头像/指示 -->
                  <div class="od-message-item__avatar">
                    <GAvatar
                      v-if="msg.role === 'USER'"
                      name="User"
                      size="sm"
                      tone="neutral"
                    />
                    <GAvatar
                      v-else
                      :name="activeAgent.name"
                      size="sm"
                      tone="accent"
                    />
                  </div>

                  <div class="od-message-item__content-wrap">
                    <!-- 发送者与时间 -->
                    <div class="od-message-item__meta">
                      <span class="od-message-item__author">
                        {{ msg.role === 'USER' ? 'You' : activeAgent.name }}
                      </span>
                      <span class="od-message-item__time mono">
                        {{ formatTime(msg.timestamp) }}
                      </span>
                      <span v-if="msg.usage" class="od-message-item__tokens mono">
                        {{ formatTokens(msg.usage.totalTokens) }} tokens
                      </span>
                    </div>

                    <!-- 1. 思考链卡片 (Thinking / Reasoning Chain) -->
                    <div
                      v-if="msg.reasoningContent"
                      class="od-thought-block"
                      :class="{ 'is-open': openThoughts[msg.id] }"
                    >
                      <button
                        type="button"
                        class="od-thought-header"
                        @click="toggleThoughtDetails(msg.id)"
                      >
                        <div class="od-thought-header__left">
                          <GIcon name="spark" :size="13" class="od-thought-icon" />
                          <span class="od-thought-title">
                            {{ msg.status === 'STREAMING' ? '思考推演中...' : '已完成深度思考' }}
                          </span>
                          <span v-if="msg.reasoningDurationMs" class="od-thought-time mono">
                            {{ (msg.reasoningDurationMs / 1000).toFixed(1) }}s
                          </span>
                        </div>
                        <GIcon
                          name="chevron-down"
                          :size="13"
                          class="od-thought-arrow"
                          :class="{ 'is-open': openThoughts[msg.id] }"
                        />
                      </button>

                      <div v-if="openThoughts[msg.id]" class="od-thought-body">
                        <pre class="od-thought-text">{{ msg.reasoningContent }}</pre>
                      </div>
                    </div>

                    <!-- 2. CLI 工具调用与执行流卡片 (Tool Calls / Executions) -->
                    <div v-if="msg.toolCalls?.length" class="od-tool-calls">
                      <div
                        v-for="(tool, idx) in msg.toolCalls"
                        :key="idx"
                        class="od-tool-card"
                        :class="`od-tool-card--${tool.status ? tool.status.toLowerCase() : 'success'}`"
                      >
                        <button
                          type="button"
                          class="od-tool-card__header"
                          @click="toggleToolDetails(`${msg.id}-${idx}`)"
                        >
                          <div class="od-tool-card__title">
                            <GIcon name="spark" :size="13" class="od-tool-icon" />
                            <span class="od-tool-name mono">{{ tool.name }}</span>
                            <span class="od-tool-summary">{{ extractToolSummary(tool) }}</span>
                          </div>
                          <div class="od-tool-card__status">
                            <span v-if="tool.durationMs" class="od-tool-duration mono">
                              {{ tool.durationMs }}ms
                            </span>
                            <span class="od-tool-card__badge mono" :class="`od-tool-badge--${tool.status ? tool.status.toLowerCase() : 'success'}`">
                              {{ tool.status || (tool.resultJson ? 'SUCCESS' : 'RUNNING') }}
                            </span>
                            <GIcon
                              name="chevron-down"
                              :size="12"
                              class="od-tool-card__arrow"
                              :class="{ 'is-open': openToolDetails[`${msg.id}-${idx}`] }"
                            />
                          </div>
                        </button>

                        <div
                          v-if="openToolDetails[`${msg.id}-${idx}`]"
                          class="od-tool-card__body"
                        >
                          <div v-if="tool.command" class="od-tool-code-block od-tool-code-block--cmd">
                            <span class="od-tool-code-block__label">Command:</span>
                            <pre class="mono">$ {{ tool.command }}</pre>
                          </div>
                          <div v-if="tool.argumentsJson" class="od-tool-code-block">
                            <span class="od-tool-code-block__label">Arguments (JSON):</span>
                            <pre class="mono">{{ tool.argumentsJson }}</pre>
                          </div>
                          <div v-if="tool.stdout" class="od-tool-code-block od-tool-code-block--stdout">
                            <span class="od-tool-code-block__label">Stdout:</span>
                            <pre class="mono">{{ tool.stdout }}</pre>
                          </div>
                          <div v-if="tool.stderr" class="od-tool-code-block od-tool-code-block--stderr">
                            <span class="od-tool-code-block__label">Stderr:</span>
                            <pre class="mono">{{ tool.stderr }}</pre>
                          </div>
                          <div v-if="tool.resultJson && !tool.stdout" class="od-tool-code-block">
                            <span class="od-tool-code-block__label">Output:</span>
                            <pre class="mono">{{ tool.resultJson }}</pre>
                          </div>
                        </div>
                      </div>
                    </div>

                    <!-- 3. 最终输出气泡 (Content Bubble with Markdown Support) -->
                    <div v-if="msg.content" class="od-bubble">
                      <div
                        v-if="msg.role === 'ASSISTANT'"
                        class="od-bubble__text od-markdown-body"
                        v-html="renderMarkdown(msg.content)"
                      />
                      <div v-else class="od-bubble__text">{{ msg.content }}</div>
                    </div>

                    <!-- 消息悬浮快捷操作栏 (Action Hover Bar) -->
                    <div class="od-message-actions">
                      <button
                        type="button"
                        class="od-action-btn"
                        :title="copiedId === msg.id ? '已复制' : '复制内容'"
                        @click="copyContent(msg.id, msg.content)"
                      >
                        <GIcon :name="copiedId === msg.id ? 'check' : 'copy'" :size="13" />
                        <span>{{ copiedId === msg.id ? '已复制' : '复制' }}</span>
                      </button>
                    </div>
                  </div>
                </div>

                <!-- 正在生成呼吸指示器 -->
                <div v-if="isStreaming" class="od-message-item od-message-item--assistant od-message-item--typing">
                  <div class="od-message-item__avatar">
                    <GAvatar :name="activeAgent.name" size="sm" tone="accent" />
                  </div>
                  <div class="od-message-item__content-wrap">
                    <div class="od-typing-bubble">
                      <span class="od-typing-dot" />
                      <span class="od-typing-dot" />
                      <span class="od-typing-dot" />
                    </div>
                  </div>
                </div>
              </div>

              <!-- 空消息态 -->
              <div v-else class="od-stage-empty">
                <div class="od-stage-empty__hero">
                  <div class="od-stage-empty__icon-wrap">
                    <GIcon name="spark" :size="24" />
                  </div>
                  <h3>{{ activeAgent.name }} 已就绪</h3>
                  <p>当前会话已绑定工单 <code>{{ ticket.no }}</code>。点击下方快捷指令或在输入框输入任务要求即可开始协作。</p>
                </div>
              </div>
            </template>

            <div v-else class="od-stage-empty">
              <div class="od-stage-empty__hero">
                <div class="od-stage-empty__icon-wrap">
                  <GIcon name="chat" :size="24" />
                </div>
                <h3>开启首个开发会话</h3>
                <p>让智能体自动化执行任务拆解、代码分析、验证并准备提交。</p>
                <GButton variant="primary" size="md" @click="startSession">
                  新建会话
                </GButton>
              </div>
            </div>
          </div>

          <!-- 底部输入与快捷指令控制台 (OpenDesign Chat Composer) -->
          <footer class="od-chat-footer">
            <!-- 快捷 Prompt Chips 药丸栏 -->
            <div class="od-quick-strip" aria-label="快捷指令选项">
              <span class="od-quick-strip__label">快捷指令:</span>
              <div class="od-quick-strip__scroll">
                <button
                  v-for="prompt in quickPrompts"
                  :key="prompt.label"
                  type="button"
                  class="od-prompt-chip"
                  @click="applyPrompt(prompt.text)"
                >
                  <GIcon :name="prompt.icon" :size="12" />
                  <span>{{ prompt.label }}</span>
                </button>
              </div>
            </div>

            <!-- 集成式输入框容器 -->
            <div class="od-composer" :class="{ 'is-focused': false }">
              <textarea
                ref="textareaRef"
                v-model="input"
                class="od-composer__textarea"
                rows="1"
                placeholder="向智能体输入任务指令... (Enter 发送，Shift + Enter 换行)"
                aria-label="消息输入框"
                :disabled="sending"
                @keydown="handleKeydown"
              />

              <!-- 输入框底部功能条 (Bottom Tool Strip) -->
              <div class="od-composer__toolbar">
                <div class="od-composer__hints">
                  <span class="od-kbd-hint"><kbd>↵</kbd> 发送</span>
                  <span class="od-kbd-hint"><kbd>⇧</kbd><kbd>↵</kbd> 换行</span>
                </div>

                <div class="od-composer__actions">
                  <!-- 正在生成时展示 Stop 停止按钮 (对齐 DSH InputBar 机制) -->
                  <button
                    v-if="isStreaming"
                    type="button"
                    class="od-send-btn od-send-btn--stop"
                    :disabled="isAborting"
                    aria-label="停止生成"
                    title="停止生成 (中断当前轮次)"
                    @click="abortCurrentGeneration"
                  >
                    <span v-if="!isAborting" class="od-stop-square" />
                    <span v-else class="od-send-spinner" />
                    <span>{{ isAborting ? '正在停止...' : '停止' }}</span>
                  </button>

                  <button
                    v-else
                    type="button"
                    class="od-send-btn"
                    :disabled="!input.trim() || sending"
                    :class="{ 'is-active': input.trim().length > 0 }"
                    aria-label="发送消息"
                    @click="send"
                  >
                    <GIcon v-if="!sending" name="arrow-right" :size="14" />
                    <span v-else class="od-send-spinner" />
                    <span>发送</span>
                  </button>
                </div>
              </div>
            </div>
          </footer>
        </main>
      </div>

      <!-- 右键上下文菜单 (Context Menu) -->
      <Teleport to="body">
        <div
          v-if="contextMenuVisible && contextMenuTarget"
          class="od-context-menu-backdrop"
          @click="closeContextMenu"
          @contextmenu.prevent="closeContextMenu"
        >
          <div
            class="od-context-menu"
            :style="{ top: `${contextMenuY}px`, left: `${contextMenuX}px` }"
            @click.stop
          >
            <div class="od-context-menu__head">
              <span class="mono">{{ contextMenuTarget.id }}</span>
            </div>
            <button
              v-if="contextMenuTarget.status !== 'ACTIVE'"
              type="button"
              class="od-context-menu__item"
              @click="moveToActive(contextMenuTarget)"
            >
              <GIcon name="refresh" :size="13" />
              <span>移入活跃 (激活)</span>
            </button>
            <button
              v-if="contextMenuTarget.status === 'ACTIVE'"
              type="button"
              class="od-context-menu__item od-context-menu__item--danger"
              @click="moveToArchived(contextMenuTarget)"
            >
              <GIcon name="x" :size="13" />
              <span>移入归档 (结束)</span>
            </button>
          </div>
        </div>
      </Teleport>
    </div>
  </div>
</template>

<style scoped>
.session-workbench {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
  height: 100%;
  background: var(--canvas);
  overflow: hidden;
}

.session-skeleton {
  display: flex;
  flex-direction: column;
  gap: 16px;
  height: 100%;
  padding: 16px;
}
.session-skeleton__context {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 16px;
  background: var(--panel);
  border-radius: var(--radius-md);
  border: 1px solid var(--border);
}
.session-skeleton__body {
  display: flex;
  flex: 1;
  gap: 16px;
  min-height: 0;
}
.session-skeleton__main {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 16px;
  min-height: 0;
}
.session-skeleton__messages {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 12px;
  padding: 16px;
  background: var(--panel);
  border-radius: var(--radius-md);
  border: 1px solid var(--border);
}

.session-error {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 16px;
  margin: 16px;
  border-radius: var(--radius-md);
  background: var(--danger-soft);
  border: 1px solid var(--danger-border);
  color: var(--danger);
  font-size: 13px;
}

.od-workbench {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
  height: 100%;
}

.od-ticket-strip {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 44px;
  padding: 0 16px;
  background: var(--panel);
  border-bottom: 1px solid var(--border);
  flex: none;
}
.od-ticket-strip__left {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
.od-back-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 8px;
  border: 0;
  background: transparent;
  color: var(--text-muted);
  font-size: 12px;
  cursor: pointer;
  border-radius: var(--radius-xs);
  transition: color 0.14s ease, background-color 0.14s ease;
}
.od-back-btn:hover {
  color: var(--text);
  background: var(--hover);
}
.od-divider {
  color: var(--text-faint);
}
.od-ticket-no {
  color: var(--accent);
  font-weight: 700;
  font-size: 12px;
}
.od-ticket-title {
  color: var(--text);
  font-size: 13px;
  font-weight: 600;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 320px;
}
.od-ticket-strip__right {
  display: flex;
  align-items: center;
  gap: 12px;
}
.od-target-ref {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 3px 7px;
  background: var(--panel-2);
  border: 1px solid var(--border);
  border-radius: var(--radius-xs);
  color: var(--text-muted);
  font-size: 11px;
}

.od-workspace-layout {
  display: flex;
  flex: 1;
  min-height: 0;
  overflow: hidden;
}

.od-session-rail {
  display: flex;
  flex-direction: column;
  width: 270px;
  flex: none;
  background: var(--panel);
  border-right: 1px solid var(--border);
}
.od-rail-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 14px;
  border-bottom: 1px solid var(--border);
}
.od-rail-header__title {
  display: flex;
  align-items: center;
  gap: 6px;
}
.od-rail-label {
  font-size: 11px;
  font-weight: 700;
  color: var(--text-faint);
  text-transform: uppercase;
  letter-spacing: 0.05em;
}
.od-rail-count {
  padding: 1px 5px;
  background: var(--panel-2);
  border-radius: 999px;
  font-size: 10px;
  color: var(--text-muted);
}
.od-rail-tabs {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 8px 10px 4px;
  border-bottom: 1px solid var(--border-subtle);
  background: var(--panel-2);
}
.od-rail-tab {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 10px;
  border: 1px solid transparent;
  border-radius: var(--radius-xs);
  background: transparent;
  color: var(--text-muted);
  font-size: 11px;
  font-weight: 650;
  cursor: pointer;
  transition: all 0.14s ease;
}
.od-rail-tab:hover {
  color: var(--text);
  background: var(--hover);
}
.od-rail-tab.is-active {
  background: var(--panel);
  border-color: var(--border);
  color: var(--text);
  box-shadow: var(--shadow-xs);
}

.od-context-menu-backdrop {
  position: fixed;
  inset: 0;
  z-index: 1000;
  background: transparent;
}
.od-context-menu {
  position: fixed;
  z-index: 1001;
  min-width: 150px;
  background: var(--panel);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  box-shadow: var(--shadow-popover);
  padding: 4px;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.od-context-menu__head {
  padding: 4px 8px;
  font-size: 10px;
  color: var(--text-faint);
  border-bottom: 1px solid var(--border-subtle);
}
.od-context-menu__item {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 6px 8px;
  border: 0;
  border-radius: var(--radius-xs);
  background: transparent;
  color: var(--text);
  font-size: 12px;
  text-align: left;
  cursor: pointer;
  transition: background-color 0.12s ease;
}
.od-context-menu__item:hover {
  background: var(--hover);
}
.od-context-menu__item--danger {
  color: var(--danger);
}
.od-context-menu__item--danger:hover {
  background: var(--danger-soft);
}
.od-session-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 8px;
  overflow-y: auto;
  flex: 1;
}
.od-session-card {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 10px 12px;
  border: 1px solid transparent;
  border-radius: var(--radius-md);
  background: var(--panel-2);
  text-align: left;
  cursor: pointer;
  transition: all 0.14s ease;
}
.od-session-card:hover {
  background: var(--hover);
  border-color: var(--border);
}
.od-session-card.is-active {
  background: var(--panel);
  border-color: var(--accent-border);
  box-shadow: var(--shadow-sm);
}
.od-session-card__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.od-session-card__agent {
  display: flex;
  align-items: center;
  gap: 6px;
}
.od-cli-badge {
  padding: 1px 4px;
  background: var(--ink-soft);
  color: var(--ink-text);
  border-radius: 3px;
  font-size: 9px;
  font-weight: 700;
}
.od-agent-name {
  font-size: 12px;
  font-weight: 650;
  color: var(--text);
}
.od-status-pill {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 10px;
  color: var(--text-muted);
}
.od-status-pill__dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--text-faint);
}
.od-status-pill--success .od-status-pill__dot { background: var(--success); }
.od-status-pill--danger .od-status-pill__dot { background: var(--danger); }
.od-session-card__preview {
  font-size: 11px;
  color: var(--text-secondary);
  line-height: 1.4;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.od-session-card__foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 10px;
  color: var(--text-faint);
}
.od-rail-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 32px 16px;
  text-align: center;
  flex: 1;
}
.od-rail-empty__icon {
  width: 40px;
  height: 40px;
  border-radius: 50%;
  background: var(--panel-2);
  display: grid;
  place-items: center;
  color: var(--text-muted);
  margin-bottom: 12px;
}
.od-rail-empty h4 {
  margin: 0 0 6px;
  font-size: 13px;
  color: var(--text);
}
.od-rail-empty p {
  margin: 0 0 16px;
  font-size: 11px;
  color: var(--text-muted);
}
.od-rail-footer {
  display: flex;
  align-items: center;
  justify-content: space-around;
  padding: 8px 12px;
  border-top: 1px solid var(--border);
  background: var(--panel-2);
  font-size: 11px;
  color: var(--text-muted);
}
.od-rail-stat {
  display: flex;
  align-items: center;
  gap: 5px;
}
.od-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
}
.od-dot--active { background: var(--success); }
.od-dot--idle { background: var(--text-faint); }

.od-chat-stage {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-width: 0;
  background: var(--canvas);
}
.od-stage-topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 52px;
  padding: 0 20px;
  background: var(--panel);
  border-bottom: 1px solid var(--border);
  flex: none;
}
.od-agent-info {
  display: flex;
  align-items: center;
  gap: 10px;
}
.od-agent-info__avatar-wrap {
  position: relative;
}
.od-avatar-badge {
  position: absolute;
  bottom: -1px;
  right: -1px;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--success);
  border: 1.5px solid var(--panel);
}
.od-agent-info__text {
  display: flex;
  flex-direction: column;
}
.od-agent-info__row {
  display: flex;
  align-items: center;
  gap: 6px;
}
.od-agent-info__name {
  margin: 0;
  font-size: 14px;
  font-weight: 700;
  color: var(--text);
}
.od-agent-cli-tag {
  padding: 1px 4px;
  background: var(--accent-soft);
  color: var(--accent);
  border-radius: 3px;
  font-size: 9px;
  font-weight: 700;
}
.od-agent-info__sub {
  font-size: 11px;
  color: var(--text-faint);
}
.od-stage-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}
.od-streaming-indicator {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 8px;
  background: var(--accent-soft);
  border: 1px solid var(--accent-border);
  border-radius: var(--radius-sm);
  color: var(--accent);
  font-size: 11px;
  font-weight: 600;
}
.od-pulse-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--accent);
  animation: pulse-ring 1.2s infinite;
}
@keyframes pulse-ring {
  0% { transform: scale(0.95); box-shadow: 0 0 0 0 rgba(11, 113, 137, 0.7); }
  70% { transform: scale(1); box-shadow: 0 0 0 6px rgba(11, 113, 137, 0); }
  100% { transform: scale(0.95); box-shadow: 0 0 0 0 rgba(11, 113, 137, 0); }
}
.od-btn-ghost {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 8px;
  border: 1px solid transparent;
  border-radius: var(--radius-xs);
  background: transparent;
  font-size: 11px;
  font-weight: 600;
  cursor: pointer;
}
.od-btn-ghost--danger { color: var(--danger); }
.od-btn-ghost--danger:hover { background: var(--danger-soft); }
.od-btn-ghost--accent { color: var(--accent); }
.od-btn-ghost--accent:hover { background: var(--accent-soft); }

.od-message-stream {
  flex: 1;
  overflow-y: auto;
  padding: 20px;
  display: flex;
  flex-direction: column;
}
.od-message-list {
  display: flex;
  flex-direction: column;
  gap: 20px;
  max-width: 860px;
  width: 100%;
  margin: 0 auto;
}
.od-message-item {
  display: flex;
  gap: 12px;
  position: relative;
}
.od-message-item__avatar {
  flex: none;
  margin-top: 2px;
}
.od-message-item__content-wrap {
  display: flex;
  flex-direction: column;
  gap: 6px;
  flex: 1;
  min-width: 0;
}
.od-message-item__meta {
  display: flex;
  align-items: center;
  gap: 8px;
}
.od-message-item__author {
  font-size: 12px;
  font-weight: 700;
  color: var(--text);
}
.od-message-item__time {
  font-size: 10px;
  color: var(--text-faint);
}
.od-message-item__tokens {
  font-size: 10px;
  color: var(--text-muted);
  background: var(--panel-2);
  padding: 1px 4px;
  border-radius: 3px;
}

/* 思考链胶囊 (Thinking Block) */
.od-thought-block {
  display: flex;
  flex-direction: column;
  background: var(--panel-2);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  overflow: hidden;
}
.od-thought-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 10px;
  border: 0;
  background: transparent;
  cursor: pointer;
  color: var(--text-secondary);
  font-size: 11px;
}
.od-thought-header:hover {
  background: var(--hover);
  color: var(--text);
}
.od-thought-header__left {
  display: flex;
  align-items: center;
  gap: 6px;
}
.od-thought-icon {
  color: var(--accent);
}
.od-thought-title {
  font-weight: 600;
}
.od-thought-time {
  font-size: 10px;
  color: var(--text-faint);
}
.od-thought-arrow {
  transition: transform 0.16s ease;
}
.od-thought-arrow.is-open {
  transform: rotate(180deg);
}
.od-thought-body {
  padding: 10px;
  border-top: 1px solid var(--border);
  background: var(--panel);
}
.od-thought-text {
  margin: 0;
  font-size: 11.5px;
  line-height: 1.5;
  color: var(--text-muted);
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 240px;
  overflow-y: auto;
}

/* CLI 工具调用卡片 (Tool Calls) */
.od-tool-calls {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.od-tool-card {
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--panel);
  overflow: hidden;
}
.od-tool-card__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  padding: 6px 10px;
  border: 0;
  background: var(--panel-2);
  cursor: pointer;
  font-size: 11px;
}
.od-tool-card__header:hover {
  background: var(--hover);
}
.od-tool-card__title {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}
.od-tool-icon {
  color: var(--accent);
  flex: none;
}
.od-tool-name {
  font-weight: 700;
  color: var(--text);
  flex: none;
}
.od-tool-summary {
  font-size: 10.5px;
  color: var(--text-muted);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 380px;
}
.od-tool-card__status {
  display: flex;
  align-items: center;
  gap: 6px;
  flex: none;
}
.od-tool-duration {
  font-size: 10px;
  color: var(--text-faint);
}
.od-tool-card__badge {
  padding: 1px 4px;
  border-radius: 3px;
  font-size: 9px;
  font-weight: 700;
}
.od-tool-badge--success { background: var(--success-soft); color: var(--success); }
.od-tool-badge--running { background: var(--accent-soft); color: var(--accent); }
.od-tool-badge--error { background: var(--danger-soft); color: var(--danger); }
.od-tool-card__arrow {
  transition: transform 0.16s ease;
  color: var(--text-faint);
}
.od-tool-card__arrow.is-open {
  transform: rotate(180deg);
}
.od-tool-card__body {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 10px;
  background: var(--panel);
  border-top: 1px solid var(--border);
}
.od-tool-code-block {
  display: flex;
  flex-direction: column;
  gap: 3px;
}
.od-tool-code-block__label {
  font-size: 9.5px;
  font-weight: 700;
  text-transform: uppercase;
  color: var(--text-faint);
}
.od-tool-code-block pre {
  margin: 0;
  padding: 8px;
  background: var(--panel-3);
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-xs);
  font-size: 11px;
  line-height: 1.4;
  color: var(--text);
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 200px;
  overflow-y: auto;
}
.od-tool-code-block--cmd pre {
  background: #111827;
  color: #38bdf8;
}
.od-tool-code-block--stdout pre {
  background: #111827;
  color: #4ade80;
}
.od-tool-code-block--stderr pre {
  background: #111827;
  color: #f87171;
}

/* 消息气泡 */
.od-bubble {
  padding: 10px 14px;
  border-radius: var(--radius-md);
  background: var(--panel);
  border: 1px solid var(--border);
  box-shadow: var(--shadow-xs);
}
.od-message-item--user .od-bubble {
  background: var(--accent-soft);
  border-color: var(--accent-border);
}
.od-bubble__text {
  font-size: 13px;
  line-height: 1.5;
  color: var(--text);
  white-space: pre-wrap;
  word-break: break-word;
}
.od-message-actions {
  display: flex;
  gap: 6px;
  opacity: 0;
  transition: opacity 0.14s ease;
}
.od-message-item:hover .od-message-actions {
  opacity: 1;
}
.od-action-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 6px;
  border: 1px solid var(--border);
  border-radius: var(--radius-xs);
  background: var(--panel);
  color: var(--text-muted);
  font-size: 10px;
  cursor: pointer;
}
.od-action-btn:hover {
  background: var(--hover);
  color: var(--text);
}

.od-typing-bubble {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 8px 12px;
  background: var(--panel);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
}
.od-typing-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--accent);
  animation: typing 1.2s infinite ease-in-out;
}
.od-typing-dot:nth-child(2) { animation-delay: 0.2s; }
.od-typing-dot:nth-child(3) { animation-delay: 0.4s; }
@keyframes typing {
  0%, 80%, 100% { transform: translateY(0); opacity: 0.4; }
  40% { transform: translateY(-4px); opacity: 1; }
}

.od-stage-empty {
  display: flex;
  flex: 1;
  align-items: center;
  justify-content: center;
  padding: 40px 20px;
}
.od-stage-empty__hero {
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  max-width: 420px;
}
.od-stage-empty__icon-wrap {
  width: 52px;
  height: 52px;
  border-radius: 50%;
  background: var(--accent-soft);
  color: var(--accent);
  display: grid;
  place-items: center;
  margin-bottom: 16px;
}
.od-stage-empty__hero h3 {
  margin: 0 0 8px;
  font-size: 16px;
  color: var(--text);
}
.od-stage-empty__hero p {
  margin: 0 0 16px;
  font-size: 12.5px;
  color: var(--text-muted);
  line-height: 1.5;
}

.od-chat-footer {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 12px 20px 16px;
  background: var(--panel);
  border-top: 1px solid var(--border);
  flex: none;
}
.od-quick-strip {
  display: flex;
  align-items: center;
  gap: 8px;
}
.od-quick-strip__label {
  font-size: 10.5px;
  font-weight: 700;
  color: var(--text-faint);
  flex: none;
}
.od-quick-strip__scroll {
  display: flex;
  align-items: center;
  gap: 6px;
  overflow-x: auto;
  padding-bottom: 2px;
}
.od-prompt-chip {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 4px 9px;
  border: 1px solid var(--border);
  border-radius: 999px;
  background: var(--panel-2);
  color: var(--text-secondary);
  font-size: 11px;
  font-weight: 600;
  cursor: pointer;
  white-space: nowrap;
  transition: all 0.14s ease;
}
.od-prompt-chip:hover {
  background: var(--hover);
  border-color: var(--border-strong);
  color: var(--text);
}

.od-composer {
  display: flex;
  flex-direction: column;
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-md);
  background: var(--panel);
  box-shadow: var(--shadow-sm);
  padding: 8px 10px;
  transition: border-color 0.14s ease, box-shadow 0.14s ease;
}
.od-composer:focus-within {
  border-color: var(--accent);
  box-shadow: var(--focus-ring);
}
.od-composer__textarea {
  width: 100%;
  border: 0;
  background: transparent;
  outline: none;
  font-size: 13px;
  line-height: 1.45;
  color: var(--text);
  resize: none;
}
.od-composer__toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 6px;
  padding-top: 6px;
  border-top: 1px solid var(--border-subtle);
}
.od-composer__hints {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 10px;
  color: var(--text-faint);
}
.od-kbd-hint kbd {
  padding: 1px 3px;
  background: var(--panel-2);
  border: 1px solid var(--border);
  border-radius: 3px;
  font-family: var(--font-mono);
}
.od-send-btn {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 4px 12px;
  border: 0;
  border-radius: var(--radius-sm);
  background: var(--panel-3);
  color: var(--text-faint);
  font-size: 12px;
  font-weight: 650;
  cursor: not-allowed;
  transition: all 0.14s ease;
}
.od-send-btn.is-active {
  background: var(--accent);
  color: var(--accent-contrast);
  cursor: pointer;
}
.od-send-btn.is-active:hover {
  background: var(--accent-hover);
}
.od-send-btn--stop {
  background: var(--danger, #e5484d) !important;
  color: #fff !important;
  cursor: pointer !important;
}
.od-send-btn--stop:hover {
  background: var(--danger-hover, #dc383d) !important;
}
.od-stop-square {
  display: inline-block;
  width: 9px;
  height: 9px;
  background: currentColor;
  border-radius: 2px;
}
.od-modal-backdrop--loading {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.45);
  backdrop-filter: blur(4px);
  z-index: 1000;
  display: flex;
  align-items: center;
  justify-content: center;
}
.od-creating-card {
  background: var(--panel, #1e2029);
  border: 1px solid var(--border, rgba(255, 255, 255, 0.12));
  border-radius: var(--radius-md, 12px);
  padding: 24px 32px;
  box-shadow: 0 16px 40px rgba(0, 0, 0, 0.35);
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  max-width: 420px;
  text-align: center;
}
.od-creating-spinner {
  width: 36px;
  height: 36px;
  border: 3px solid var(--border-subtle, rgba(255, 255, 255, 0.1));
  border-top-color: var(--accent, #4d6bfe);
  border-radius: 50%;
  animation: spin 0.8s cubic-bezier(0.4, 0, 0.2, 1) infinite;
}
.od-creating-title {
  font-size: 15px;
  font-weight: 650;
  color: var(--text, #f0f6fc);
}
.od-creating-desc {
  font-size: 12.5px;
  color: var(--text-muted, rgba(255, 255, 255, 0.7));
  line-height: 1.6;
}
.od-creating-desc code {
  padding: 1px 5px;
  background: var(--panel-2, rgba(255, 255, 255, 0.08));
  border-radius: 4px;
}
.od-send-spinner {
  width: 12px;
  height: 12px;
  border: 2px solid currentColor;
  border-right-color: transparent;
  border-radius: 50%;
  animation: spin 0.65s linear infinite;
}
@keyframes spin {
  to { transform: rotate(360deg); }
}

/* Markdown Rendering Styles in Session Bubbles */
.od-markdown-body {
  font-size: 13.5px;
  line-height: 1.65;
  color: var(--text);
  word-break: break-word;
}
.od-markdown-body p {
  margin: 0 0 10px;
}
.od-markdown-body p:last-child {
  margin-bottom: 0;
}
.od-markdown-body pre {
  margin: 10px 0;
  padding: 12px 14px;
  background: var(--panel-2, #181a20);
  border: 1px solid var(--border-subtle, rgba(255, 255, 255, 0.08));
  border-radius: var(--radius-sm, 6px);
  overflow-x: auto;
  font-family: var(--font-mono);
  font-size: 12px;
  line-height: 1.5;
}
.od-markdown-body code {
  padding: 2px 5px;
  background: var(--panel-2, rgba(255, 255, 255, 0.06));
  border-radius: 4px;
  font-family: var(--font-mono);
  font-size: 12px;
}
.od-markdown-body pre code {
  padding: 0;
  background: transparent;
}
.od-markdown-body ul, .od-markdown-body ol {
  margin: 6px 0 10px 20px;
  padding: 0;
}
.od-markdown-body li {
  margin-bottom: 4px;
}
.od-markdown-body blockquote {
  margin: 10px 0;
  padding: 8px 14px;
  border-left: 3px solid var(--accent, #4d6bfe);
  background: var(--panel-2, rgba(255, 255, 255, 0.04));
  border-radius: 0 var(--radius-xs) var(--radius-xs) 0;
  color: var(--text-muted);
}
.od-markdown-body table {
  width: 100%;
  border-collapse: collapse;
  margin: 10px 0;
  font-size: 12.5px;
}
.od-markdown-body th, .od-markdown-body td {
  padding: 6px 10px;
  border: 1px solid var(--border);
  text-align: left;
}
.od-markdown-body th {
  background: var(--panel-2);
  font-weight: 600;
}
</style>
