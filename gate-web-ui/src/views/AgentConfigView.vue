<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { createAgentConfig, deleteAgentConfig, listAgentConfigs, updateAgentConfig } from '@/api/agentConfig';
import { getAgentRuntimes, getProviders, getRuntime, type AgentRuntimeView, type ProviderView } from '@/api/config';
import { GBadge, GButton, GCombobox, GField, GIcon, GInput, GModal, GSelect } from '@/components/ui';
import type { AgentCli, AgentConfig } from '@/types/agentConfig';

type SettingsTab = 'cli' | 'api';
type PageMode = 'profiles' | 'create';
type TestStatus = 'idle' | 'testing' | 'success' | 'error';

interface AgentForm {
  name: string;
  cli: AgentCli;
  model: string;
  systemPrompt: string;
  extraFlags: string;
  description: string;
}

const CLI_DEFAULT = '__cli_default__';
const defaultConfigStorageKey = 'gate.default-agent-config';
const router = useRouter();

const activeTab = ref<SettingsTab>('cli');
const pageMode = ref<PageMode>('profiles');
const runtimes = ref<AgentRuntimeView[]>([]);
const agents = ref<AgentConfig[]>([]);
const providers = ref<ProviderView[]>([]);
const selectedRuntimeId = ref('opencode');
const selectedAgentId = ref('');
const defaultConfigId = ref('');
const loading = ref(true);
const runtimeLoading = ref(false);
const providersLoading = ref(false);
const providersLoaded = ref(false);
const saving = ref(false);
const dataError = ref('');
const actionNotice = ref('');
const showForm = ref(false);
const editingId = ref<string | null>(null);
const formSubmitted = ref(false);
const formNotice = ref('');
const testStatus = ref<TestStatus>('idle');
let modelRefreshTimer: ReturnType<typeof setTimeout> | null = null;
let modelRefreshAttempts = 0;

const fallbackRuntimes: AgentRuntimeView[] = [
  {
    name: 'claude',
    available: false,
    version: null,
    note: null,
    models: ['default', 'sonnet', 'opus', 'haiku'],
    model_source: 'cli-hints',
  },
  {
    name: 'opencode',
    available: false,
    version: null,
    note: null,
    models: ['default'],
    model_source: 'cli-default',
  },
];

const emptyForm = (): AgentForm => ({
  name: '',
  cli: 'CLAUDE',
  model: CLI_DEFAULT,
  systemPrompt: '',
  extraFlags: '',
  description: '',
});

const form = ref<AgentForm>(emptyForm());

const showCreateView = computed(() => pageMode.value === 'create' || loading.value || agents.value.length === 0);
const runtimeRows = computed(() => (runtimes.value.length ? runtimes.value : fallbackRuntimes));
const selectedRuntime = computed(() =>
  runtimeRows.value.find((runtime) => runtime.name === selectedRuntimeId.value) ?? runtimeRows.value[0] ?? null,
);
const selectedAgent = computed<AgentConfig | null>(() =>
  agents.value.find((agent) => agent.id === selectedAgentId.value) ?? agents.value[0] ?? null,
);

const modelOptions = computed(() => {
  const options = [{ label: 'CLI 默认设置', value: CLI_DEFAULT }];
  const models = selectedRuntime.value?.models ?? [];
  for (const model of models) {
    if (model !== 'default' && !options.some((option) => option.value === model)) {
      options.push({ label: model, value: model });
    }
  }
  const current = form.value.model.trim();
  if (current && current !== CLI_DEFAULT && !options.some((option) => option.value === current)) {
    options.push({ label: `${current}（已保存）`, value: current });
  }
  return options;
});

const formErrors = computed(() => {
  const errors: Partial<Record<keyof AgentForm, string>> = {};
  const name = form.value.name.trim();
  const duplicate = agents.value.some(
    (agent) => agent.name.trim().toLowerCase() === name.toLowerCase() && agent.id !== editingId.value,
  );
  if (name.length < 2) errors.name = '名称至少需要 2 个字符。';
  else if (duplicate) errors.name = '已有同名配置，请换一个名称。';
  return errors;
});

const formValid = computed(() => Object.keys(formErrors.value).length === 0);

function cliForRuntime(name: string): AgentCli {
  return name.toLowerCase() === 'opencode' ? 'OPENCODE' : 'CLAUDE';
}

function cliDisplayName(name: string) {
  return name.toLowerCase() === 'opencode' ? 'OpenCode' : 'Claude Code';
}

function cliDescription(name: string) {
  return name.toLowerCase() === 'opencode' ? 'Open-source agent CLI' : 'Anthropic official CLI';
}

function cliMark(name: string) {
  return name.toLowerCase() === 'opencode' ? 'OC' : 'CC';
}

function runtimeTone(runtime: AgentRuntimeView): 'neutral' | 'success' | 'warning' {
  if (runtime.available) return 'success';
  if (runtime.note) return 'warning';
  return 'neutral';
}

function runtimeStatus(runtime: AgentRuntimeView) {
  if (runtime.available) return '可用';
  if (runtime.note) return '已安装但无法运行';
  return '未安装';
}

function modelSummary(runtime: AgentRuntimeView) {
  if (!runtime.available) return '等待安装 CLI';
  if (runtime.model_source === 'cli') return '来自 CLI 的实时列表';
  if (runtime.model_source === 'cli-loading') return '正在读取 CLI 模型目录';
  return 'CLI 默认设置';
}

function modelForAgent(agent: AgentConfig) {
  return agent.model?.trim() || 'CLI 默认设置';
}

function isDefault(agent: AgentConfig) {
  return agent.id === defaultConfigId.value;
}

function parseFlags(value: string) {
  return value
    .split(/[,\n]/)
    .map((flag) => flag.trim())
    .filter(Boolean);
}

function makeConfigId(name: string) {
  const base =
    name
      .trim()
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, '-')
      .replace(/^-+|-+$/g, '')
      .slice(0, 42) || 'agent';
  let candidate = base;
  let index = 2;
  while (agents.value.some((agent) => agent.id === candidate)) {
    candidate = `${base}-${index}`;
    index += 1;
  }
  return candidate;
}

function persistDefault() {
  try {
    localStorage.setItem(defaultConfigStorageKey, defaultConfigId.value);
  } catch {
    // Browser storage is optional. The current page state remains usable.
  }
}

function readDefault() {
  try {
    defaultConfigId.value = localStorage.getItem(defaultConfigStorageKey) ?? '';
  } catch {
    defaultConfigId.value = '';
  }
}

function selectRuntime(name: string) {
  selectedRuntimeId.value = name;
  testStatus.value = 'idle';
}

function selectAgent(agent: AgentConfig) {
  selectedAgentId.value = agent.id;
  selectedRuntimeId.value = agent.cli === 'OPENCODE' ? 'opencode' : 'claude';
  testStatus.value = 'idle';
}

async function startCreate() {
  pageMode.value = 'create';
  activeTab.value = 'cli';
  actionNotice.value = '';
  dataError.value = '';
  if (!runtimes.value.length) {
    await loadRuntimes();
  }
}

function openCreateForm(runtime = selectedRuntime.value) {
  const cli = runtime ? cliForRuntime(runtime.name) : 'CLAUDE';
  if (runtime) selectedRuntimeId.value = runtime.name;
  editingId.value = null;
  form.value = {
    ...emptyForm(),
    cli,
    name: runtime ? `${cliDisplayName(runtime.name)} 配置` : '本机 CLI 配置',
  };
  formSubmitted.value = false;
  formNotice.value = '';
  showForm.value = true;
}

function leaveCreate() {
  if (agents.value.length === 0) return;
  pageMode.value = 'profiles';
  activeTab.value = 'cli';
  actionNotice.value = '';
  dataError.value = '';
}

function changeFormCli(value: string | null) {
  if (value !== 'CLAUDE' && value !== 'OPENCODE') return;
  form.value.cli = value;
  selectedRuntimeId.value = value === 'OPENCODE' ? 'opencode' : 'claude';
  form.value.model = CLI_DEFAULT;
}

function openEdit(agent: AgentConfig) {
  pageMode.value = 'profiles';
  selectAgent(agent);
  editingId.value = agent.id;
  form.value = {
    name: agent.name,
    cli: agent.cli,
    model: agent.model?.trim() || CLI_DEFAULT,
    systemPrompt: agent.systemPrompt ?? '',
    extraFlags: (agent.extraFlags ?? []).join(', '),
    description: agent.description ?? '',
  };
  formSubmitted.value = false;
  formNotice.value = '';
  showForm.value = true;
}

function closeForm() {
  showForm.value = false;
  formSubmitted.value = false;
  formNotice.value = '';
}

function requestForForm(id?: string) {
  const model = form.value.model === CLI_DEFAULT ? null : form.value.model.trim() || null;
  return {
    ...(id ? { id } : {}),
    name: form.value.name.trim(),
    cli: form.value.cli,
    providerId: null,
    model,
    systemPrompt: form.value.systemPrompt.trim() || null,
    extraFlags: parseFlags(form.value.extraFlags),
    description: form.value.description.trim() || null,
  };
}

async function saveForm() {
  formSubmitted.value = true;
  if (!formValid.value) {
    formNotice.value = '请先处理表单中的校验项。';
    return;
  }
  saving.value = true;
  formNotice.value = '';
  try {
    const saved = editingId.value
      ? await updateAgentConfig(editingId.value, requestForForm(editingId.value))
      : await createAgentConfig({ ...requestForForm(), id: makeConfigId(form.value.name) });
    const index = agents.value.findIndex((agent) => agent.id === saved.id);
    if (index >= 0) agents.value[index] = saved;
    else agents.value.push(saved);
    selectedAgentId.value = saved.id;
    if (!defaultConfigId.value) {
      defaultConfigId.value = saved.id;
      persistDefault();
    }
    if (!editingId.value) {
      pageMode.value = 'profiles';
      activeTab.value = 'cli';
    }
    actionNotice.value = editingId.value ? `已更新 ${saved.name}。` : `已添加 ${saved.name}。`;
    closeForm();
  } catch {
    formNotice.value = '保存失败，请确认 Gate 后端已更新并允许 CLI 默认配置。';
  } finally {
    saving.value = false;
  }
}

function setDefault(agent: AgentConfig) {
  defaultConfigId.value = agent.id;
  persistDefault();
  actionNotice.value = `${agent.name} 已设为默认配置。未指定智能体的新会话会使用它。`;
}

async function copyAgent(agent: AgentConfig) {
  try {
    const name = `${agent.name} 副本`;
    const copied = await createAgentConfig({
      ...requestForAgent(agent),
      id: makeConfigId(name),
      name,
    });
    agents.value.push(copied);
    selectAgent(copied);
    actionNotice.value = `已复制 ${agent.name}。`;
  } catch {
    actionNotice.value = '复制失败，请检查 Gate 后端日志。';
  }
}

function requestForAgent(agent: AgentConfig) {
  return {
    name: agent.name,
    cli: agent.cli,
    providerId: null,
    model: agent.model ?? null,
    systemPrompt: agent.systemPrompt ?? null,
    extraFlags: agent.extraFlags ? [...agent.extraFlags] : [],
    description: agent.description ?? null,
  };
}

async function removeAgent(agent: AgentConfig) {
  if (!window.confirm(`确定删除 ${agent.name} 吗？已有会话不会被删除。`)) return;
  try {
    await deleteAgentConfig(agent.id);
    agents.value = agents.value.filter((item) => item.id !== agent.id);
    if (defaultConfigId.value === agent.id) {
      defaultConfigId.value = agents.value[0]?.id ?? '';
      persistDefault();
    }
    selectedAgentId.value = agents.value[0]?.id ?? '';
    actionNotice.value = `已删除 ${agent.name}。`;
  } catch {
    actionNotice.value = '删除失败，请检查该配置是否仍被工单引用。';
  }
}

async function loadAgents() {
  try {
    agents.value = await listAgentConfigs();
    if (!agents.value.some((agent) => agent.id === selectedAgentId.value)) {
      selectedAgentId.value = agents.value[0]?.id ?? '';
    }
    if (!defaultConfigId.value || !agents.value.some((agent) => agent.id === defaultConfigId.value)) {
      defaultConfigId.value = agents.value[0]?.id ?? '';
      persistDefault();
    }
  } catch {
    dataError.value = '无法读取已保存配置，仍可先查看本机 CLI 探测结果。';
  }
}

async function loadRuntimes(showLoading = true) {
  if (showLoading) runtimeLoading.value = true;
  try {
    runtimes.value = await getAgentRuntimes();
    dataError.value = '';
    if (runtimes.value.length && !runtimes.value.some((runtime) => runtime.name === selectedRuntimeId.value)) {
      selectedRuntimeId.value = runtimes.value[0]!.name;
    }
    if (runtimes.value.some((runtime) => runtime.model_source === 'cli-loading')) {
      scheduleModelRefresh();
    } else {
      modelRefreshAttempts = 0;
    }
  } catch {
    // /api/runtime is the fast, version-only probe used by System Settings. Reuse it when the
    // richer model-catalog endpoint is unavailable, so a slow model lookup never becomes a false
    // "CLI 未安装" state in this page.
    try {
      const runtime = await getRuntime();
      const rows = runtime.agent_clis.map((probe) => {
        const isClaude = probe.name.toLowerCase() === 'claude';
        return {
          ...probe,
          models: isClaude ? ['default', 'sonnet', 'opus', 'haiku'] : ['default'],
          model_source: isClaude ? 'cli-hints' : 'cli-default',
        } satisfies AgentRuntimeView;
      });
      if (!rows.length) throw new Error('no agent cli probe');
      runtimes.value = rows;
      dataError.value = '';
      actionNotice.value = '模型目录接口暂不可用，已复用运行环境中的 CLI 探测结果。';
      if (!rows.some((runtime) => runtime.name === selectedRuntimeId.value)) {
        selectedRuntimeId.value = rows[0]!.name;
      }
    } catch {
      runtimes.value = [];
      dataError.value = '本机 CLI 探测暂不可用，当前显示支持的 CLI 类型。';
    }
  } finally {
    runtimeLoading.value = false;
  }
}

function scheduleModelRefresh() {
  if (modelRefreshTimer || modelRefreshAttempts >= 8) return;
  modelRefreshAttempts += 1;
  modelRefreshTimer = setTimeout(async () => {
    modelRefreshTimer = null;
    await loadRuntimes(false);
  }, 1_500);
}

async function loadProviders() {
  providersLoading.value = true;
  try {
    providers.value = await getProviders();
  } catch {
    providers.value = [];
  } finally {
    providersLoaded.value = true;
    providersLoading.value = false;
  }
}

async function refreshRuntimes() {
  actionNotice.value = '';
  modelRefreshAttempts = 0;
  if (modelRefreshTimer) {
    clearTimeout(modelRefreshTimer);
    modelRefreshTimer = null;
  }
  await loadRuntimes();
  actionNotice.value = '已重新扫描本机 CLI。模型列表来自对应 CLI，未修改任何 API 供应商配置。';
}

async function testRuntime(runtime: AgentRuntimeView) {
  if (testStatus.value === 'testing') return;
  testStatus.value = 'testing';
  actionNotice.value = `正在检查 ${cliDisplayName(runtime.name)}。`;
  await loadRuntimes(false);
  const current = runtimeRows.value.find((item) => item.name === runtime.name);
  if (current?.available) {
    testStatus.value = 'success';
    actionNotice.value = `${cliDisplayName(runtime.name)} 可用，运行时会继续使用 CLI 自己的供应商和默认模型。`;
  } else {
    testStatus.value = 'error';
    actionNotice.value = `${cliDisplayName(runtime.name)} 当前不可用，请先在本机安装或修复 CLI。`;
  }
}

async function manageProviders() {
  if (!providersLoaded.value) await loadProviders();
  router.push({ name: 'settings', query: { section: 'models' } });
}

onMounted(async () => {
  readDefault();
  await loadAgents();
  loading.value = false;
  if (!agents.value.length) {
    await loadRuntimes();
  }
});

onBeforeUnmount(() => {
  if (modelRefreshTimer) {
    clearTimeout(modelRefreshTimer);
    modelRefreshTimer = null;
  }
});
</script>

<template>
  <div class="agent-page">
    <div v-if="showCreateView && agents.length" class="create-back">
      <GButton variant="ghost" @click="leaveCreate">
        返回已保存配置
      </GButton>
    </div>

    <p v-if="actionNotice" class="action-notice" role="status">{{ actionNotice }}</p>
    <p v-if="dataError" class="data-notice" role="status">{{ dataError }}</p>

    <main v-if="activeTab === 'cli'" class="settings-surface" :class="{ 'settings-surface--profiles': !showCreateView }">
      <section v-if="showCreateView" class="cli-section" aria-labelledby="cli-heading">
        <header class="section-head">
          <div>
            <p class="section-label">本机执行器</p>
            <h2 id="cli-heading">你的 CLI</h2>
            <p>只显示当前 Gate 服务进程能找到的 CLI。未指定模型时不会覆盖 CLI 配置。</p>
          </div>
          <GButton size="sm" variant="secondary" :loading="runtimeLoading" @click="refreshRuntimes">
            <GIcon name="refresh" :size="14" />
            重新扫描
          </GButton>
        </header>

        <div v-if="loading || runtimeLoading" class="runtime-list runtime-list--loading" aria-label="正在扫描本机 CLI">
          <div v-for="item in 2" :key="item" class="runtime-skeleton"><span /><div><i /><i /><i /></div></div>
        </div>

        <div v-else class="runtime-list">
          <article
            v-for="runtime in runtimeRows"
            :key="runtime.name"
            class="runtime-card"
            :class="{ 'runtime-card--selected': selectedRuntime?.name === runtime.name }"
          >
            <button type="button" class="runtime-card__button" @click="selectRuntime(runtime.name)">
              <span class="cli-mark" :class="'cli-mark--' + runtime.name" aria-hidden="true">{{ cliMark(runtime.name) }}</span>
              <span class="runtime-card__copy">
                <span class="runtime-title">
                  <strong>{{ cliDisplayName(runtime.name) }}</strong>
                  <span class="runtime-subtitle">{{ cliDescription(runtime.name) }}</span>
                  <GBadge :tone="runtimeTone(runtime)" :dot="runtime.available">{{ runtimeStatus(runtime) }}</GBadge>
                </span>
                <span class="runtime-version">
                  {{ runtime.version || runtime.note || '未读取到版本' }}
                </span>
                <span class="runtime-model">
                  <span>模型</span>
                  <strong>{{ modelSummary(runtime) }}</strong>
                  <span v-if="runtime.available && runtime.models.length" class="model-count">{{ runtime.models.length }} 个可选项</span>
                </span>
              </span>
              <GIcon name="chevron-right" :size="17" />
            </button>

            <div v-if="selectedRuntime?.name === runtime.name" class="runtime-card__detail">
              <div class="runtime-detail__head">
                <div>
                  <strong>{{ cliDisplayName(runtime.name) }} 的运行时设置</strong>
                  <p>{{ runtime.available ? '会话会直接启动这个 CLI。' : '安装完成后重新扫描即可使用。' }}</p>
                </div>
                <GBadge :tone="runtimeTone(runtime)">{{ runtime.available ? '本机已发现' : '等待安装' }}</GBadge>
              </div>
              <div class="runtime-detail__facts">
                <div><span>命令</span><strong class="mono">{{ runtime.name }}</strong></div>
                <div><span>供应商</span><strong>由 CLI 配置决定</strong></div>
                <div><span>默认模型</span><strong>由 CLI 配置决定</strong></div>
              </div>
              <div class="runtime-detail__actions">
                <GButton variant="primary" size="sm" :disabled="!runtime.available" @click="openCreateForm(runtime)">添加为配置</GButton>
                <GButton variant="secondary" size="sm" :disabled="!runtime.available || testStatus === 'testing'" :loading="testStatus === 'testing'" @click="testRuntime(runtime)">测试 CLI</GButton>
              </div>
            </div>
          </article>
        </div>
      </section>

      <section v-else class="profiles-section" aria-labelledby="profiles-heading">
        <div class="profiles-main">
          <header class="profiles-head">
            <div>
              <p class="section-label">已保存配置</p>
              <h2 id="profiles-heading">配置档案</h2>
              <p>创建后可在工单和会话中直接选择。</p>
            </div>
            <div class="profiles-head__actions">
              <span class="section-count">{{ agents.length }} 个配置</span>
              <GButton variant="primary" @click="startCreate">
                <GIcon name="plus" :size="15" />
                新增配置
              </GButton>
            </div>
          </header>

          <div v-if="agents.length" class="profile-grid">
            <article v-for="agent in agents" :key="agent.id" class="profile-card" :class="{ 'profile-card--active': selectedAgent?.id === agent.id }">
              <div class="profile-card__top">
                <button type="button" class="profile-card__identity" :aria-label="`选择 ${agent.name}`" :aria-pressed="selectedAgent?.id === agent.id" @click="selectAgent(agent)">
                  <span class="cli-mark cli-mark--card" :class="'cli-mark--' + agent.cli.toLowerCase()" aria-hidden="true">{{ agent.cli === 'OPENCODE' ? 'OC' : 'CC' }}</span>
                  <span class="profile-card__heading">
                    <strong>{{ agent.name }}</strong>
                    <small class="mono">{{ agent.id }}</small>
                  </span>
                </button>
                <GBadge v-if="isDefault(agent)" tone="accent">默认配置</GBadge>
              </div>

              <div class="profile-card__body">
                <div class="profile-card__facts">
                  <div class="profile-card__fact">
                    <span>执行 CLI</span>
                    <strong>{{ agent.cli === 'OPENCODE' ? 'OpenCode' : 'Claude Code' }}</strong>
                  </div>
                  <div class="profile-card__fact">
                    <span>模型</span>
                    <strong class="mono">{{ modelForAgent(agent) }}</strong>
                  </div>
                </div>

                <p class="profile-card__description">
                  {{ agent.description || '使用 CLI 自己的供应商、登录态和默认模型设置。' }}
                </p>
              </div>

              <div class="profile-card__actions">
                <GButton v-if="!isDefault(agent)" size="sm" variant="secondary" @click="setDefault(agent)">设为默认</GButton>
                <span v-else class="profile-card__default-note">未指定智能体时使用</span>
                <div class="profile-card__action-group">
                  <GButton size="sm" variant="ghost" @click="openEdit(agent)">编辑</GButton>
                  <GButton size="sm" variant="ghost" @click="copyAgent(agent)">复制</GButton>
                  <GButton size="sm" variant="ghost" @click="removeAgent(agent)">删除</GButton>
                </div>
              </div>
            </article>
          </div>
          <div v-else class="empty-state">
            <GIcon name="bot" :size="19" />
            <strong>还没有保存配置</strong>
            <span>从上面的本机 CLI 选择“添加为配置”，再把它分配给工单。</span>
          </div>
        </div>

      </section>
    </main>

    <main v-else class="settings-surface api-surface">
      <section aria-labelledby="api-heading">
        <header class="section-head">
          <div>
            <p class="section-label">预留能力</p>
            <h2 id="api-heading">API 提供商</h2>
            <p>这里保留未来直接调用 LLM 的本地供应商配置。它不会改变 Claude Code 或 OpenCode 的运行时。</p>
          </div>
          <GButton size="sm" variant="secondary" @click="manageProviders">管理供应商</GButton>
        </header>

        <div v-if="providersLoading" class="api-loading"><span /><span /><span /></div>
        <div v-else-if="providers.length" class="provider-grid">
          <article v-for="provider in providers" :key="provider.id" class="provider-card">
            <div class="provider-card__top">
              <span class="provider-mark">{{ provider.name.slice(0, 1).toUpperCase() }}</span>
              <GBadge :tone="provider.credential_configured ? 'success' : 'warning'">{{ provider.credential_configured ? '已配置凭据' : '待配置凭据' }}</GBadge>
            </div>
            <strong>{{ provider.name }}</strong>
            <span class="mono">{{ provider.id }}</span>
            <div class="provider-card__meta"><span>{{ provider.type }}</span><span>{{ provider.models.length }} 个模型</span></div>
          </article>
        </div>
        <div v-else class="empty-state api-empty">
          <GIcon name="bot" :size="19" />
          <strong>暂未配置 API 提供商</strong>
          <span>后续接入直接 LLM 功能时，可在系统设置中添加 OpenAI 兼容服务。</span>
          <GButton size="sm" variant="secondary" @click="manageProviders">打开系统设置</GButton>
        </div>

        <div class="api-boundary">
          <GIcon name="shield" :size="17" />
          <div><strong>运行边界</strong><p>本机 CLI 的账号、环境变量和默认模型仍由用户自己的 CLI 管理。Gate 只负责选择可执行文件并注入工单上下文。</p></div>
        </div>
      </section>
    </main>

    <GModal :show="showForm" :title="editingId ? '编辑本机 CLI 配置' : '添加本机 CLI 配置'" width="620px" @close="closeForm">
      <form class="config-form" @submit.prevent="saveForm">
        <p class="config-form__intro">供应商和登录态不会写入配置。模型留空时，启动命令不会携带模型参数。</p>
        <div class="form-grid">
          <GField label="配置名称" for-id="agent-config-name" hint="用于工单和会话选择器。" :error="formSubmitted ? (formErrors.name ?? '') : ''" required>
            <GInput id="agent-config-name" v-model="form.name" name="agent-name" autocomplete="off" placeholder="例如：日常修复" required />
          </GField>
          <GField label="执行 CLI" for-id="agent-config-cli" hint="会话只使用本机已安装的执行器。">
            <GSelect id="agent-config-cli" :model-value="form.cli" :options="[{ label: 'Claude Code', value: 'CLAUDE' }, { label: 'OpenCode', value: 'OPENCODE' }]" @update:model-value="changeFormCli" />
          </GField>
        </div>
        <GField label="模型覆盖" for-id="agent-config-model" hint="列表由 CLI 提供；选择“CLI 默认设置”即可继续使用 CLI 自己的供应商和模型配置。">
          <GCombobox
            id="agent-config-model"
            v-model="form.model"
            :options="modelOptions"
            placeholder="选择模型"
            search-placeholder="搜索模型"
            aria-label="选择模型覆盖"
          />
        </GField>
        <details class="config-advanced">
          <summary>高级上下文与启动参数</summary>
          <div class="config-advanced__body">
            <GField label="系统提示" for-id="agent-config-prompt" hint="Gate 仍会注入工单、克隆目录和闸门约束。">
              <GInput id="agent-config-prompt" v-model="form.systemPrompt" type="textarea" :rows="4" placeholder="可选的额外上下文，会随工单一起传给执行侧。" />
            </GField>
            <GField label="额外启动参数" for-id="agent-config-flags" hint="会原样传给 CLI。不要在这里填写供应商密钥。">
              <GInput id="agent-config-flags" v-model="form.extraFlags" placeholder="用逗号或换行分隔，例如：--verbose" />
            </GField>
            <GField label="描述" for-id="agent-config-description" hint="可选，用一句话说明这个配置适合什么任务。">
              <GInput id="agent-config-description" v-model="form.description" type="textarea" :rows="2" placeholder="例如：适合日常缺陷修复和小范围重构。" />
            </GField>
          </div>
        </details>
        <p v-if="formNotice" class="form-notice" role="alert">{{ formNotice }}</p>
      </form>
      <template #footer>
        <GButton variant="ghost" @click="closeForm">取消</GButton>
        <GButton variant="primary" :loading="saving" @click="saveForm">保存配置</GButton>
      </template>
    </GModal>
  </div>
</template>

<style scoped>
.agent-page {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 16px;
  min-height: 0;
  width: 100%;
  max-width: none;
  margin: 0;
  padding: clamp(22px, 3vw, 40px) clamp(16px, 3.5vw, 52px) 32px;
  overflow: auto;
}

.section-head,
.runtime-title,
.runtime-detail__head,
.profile-card__top,
.profile-card__identity,
.profile-card__actions,
.provider-card__top,
.api-boundary,
.section-head,
.runtime-detail__head {
  justify-content: space-between;
  gap: 18px;
}

.profiles-head h2 {
  margin: 0;
  color: var(--text);
  font-size: clamp(22px, 2.8vw, 30px);
  font-weight: 800;
  letter-spacing: -0.045em;
}

.section-label {
  margin: 0 0 7px;
  color: var(--accent);
  font-size: 10px;
  font-weight: 800;
  letter-spacing: 0.12em;
  text-transform: uppercase;
}

.section-head p,
.runtime-detail__head p,
.api-boundary p,
.config-form__intro {
  margin: 6px 0 0;
  color: var(--text-muted);
  font-size: 12px;
  line-height: 1.55;
}

.agent-count,
.section-count {
  color: var(--text-faint);
  font-size: 11px;
  white-space: nowrap;
}

.agent-count {
  padding-right: 12px;
  border-right: 1px solid var(--border);
}

.create-back {
  display: flex;
  justify-content: flex-start;
  min-height: 34px;
}

.create-back :deep(.g-btn) {
  padding-inline: 0;
}

.profiles-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 18px;
  padding: 6px 2px 0;
}

.profiles-head__actions {
  display: flex;
  align-items: center;
  flex: none;
  gap: 12px;
}

.profiles-head p:not(.section-label) {
  margin: 6px 0 0;
  color: var(--text-muted);
  font-size: 12px;
  line-height: 1.55;
}

.profiles-main {
  min-width: 0;
}

.mode-switch {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  min-height: 42px;
  padding: 3px;
  border: 1px solid var(--border);
  border-radius: 999px;
  background: var(--panel-2);
}

.mode-switch button {
  min-height: 34px;
  border: 0;
  border-radius: 999px;
  color: var(--text-muted);
  background: transparent;
  font-size: 12px;
  font-weight: 750;
  cursor: pointer;
  transition: background-color 0.15s ease, color 0.15s ease, box-shadow 0.15s ease;
}

.mode-switch button:hover {
  color: var(--text);
}

.mode-switch button.active {
  color: var(--text);
  background: var(--panel);
  box-shadow: var(--shadow-panel);
}

.mode-switch button:focus-visible,
.runtime-card__button:focus-visible,
.profile-card__identity:focus-visible {
  outline: 2px solid var(--accent);
  outline-offset: -2px;
}

.action-notice,
.data-notice {
  margin: -2px 0 0;
  padding: 9px 12px;
  border: 1px solid var(--accent-border);
  border-radius: var(--radius-sm);
  color: var(--accent-hover);
  background: var(--accent-soft);
  font-size: 12px;
  font-weight: 650;
}

.data-notice {
  border-color: var(--warning);
  color: var(--warning);
  background: var(--warning-soft);
}

.settings-surface {
  display: grid;
  gap: 30px;
  padding: 22px;
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  background: var(--panel);
  box-shadow: var(--shadow-panel);
}

.settings-surface--profiles {
  display: block;
  padding: 0;
  border: 0;
  background: transparent;
  box-shadow: none;
}

.section-head {
  align-items: flex-end;
  padding-bottom: 14px;
  border-bottom: 1px solid var(--border);
}

.section-head--compact {
  align-items: center;
}

.section-head h2 {
  margin: 0;
  color: var(--text);
  font-size: 18px;
  font-weight: 780;
  letter-spacing: -0.035em;
}

.section-head p {
  max-width: 660px;
}

.runtime-list {
  display: grid;
  gap: 8px;
  padding-top: 14px;
}

.runtime-card {
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--canvas);
  transition: border-color 0.15s ease, background-color 0.15s ease;
}

.runtime-card:hover,
.runtime-card--selected {
  border-color: var(--accent-border);
}

.runtime-card--selected {
  background: var(--panel);
}

.runtime-card__button {
  display: grid;
  grid-template-columns: 42px minmax(0, 1fr) auto;
  gap: 13px;
  align-items: center;
  width: 100%;
  padding: 15px 17px;
  border: 0;
  color: inherit;
  background: transparent;
  text-align: left;
  cursor: pointer;
}

.cli-mark,
.provider-mark {
  display: grid;
  width: 38px;
  height: 38px;
  place-items: center;
  flex: none;
  border-radius: var(--radius-sm);
  color: var(--ink-text);
  background: var(--ink);
  font-family: var(--font-mono);
  font-size: 11px;
  font-weight: 800;
  letter-spacing: -0.04em;
}

.cli-mark--opencode {
  color: var(--text);
  background: var(--panel-2);
  border: 1px solid var(--border-strong);
}

.cli-mark--small {
  width: 28px;
  height: 28px;
  border-radius: 7px;
  font-size: 9px;
}

.runtime-card__copy {
  display: grid;
  min-width: 0;
  gap: 4px;
}

.runtime-title {
  flex-wrap: wrap;
  gap: 7px;
}

.runtime-title strong,
.profile-card__heading strong,
.provider-card > strong {
  color: var(--text);
  font-size: 13px;
  font-weight: 760;
}

.runtime-subtitle {
  color: var(--text-muted);
  font-size: 11px;
}

.runtime-title :deep(.g-badge) {
  padding-block: 2px;
  font-size: 9px;
}

.runtime-version,
.profile-card__heading small,
.provider-card > span,
.provider-card__meta {
  overflow: hidden;
  color: var(--text-faint);
  font-size: 10px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.runtime-model {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 7px;
  margin-top: 2px;
  font-size: 10px;
}

.runtime-model > span:first-child {
  color: var(--text-faint);
}

.runtime-model strong {
  overflow: hidden;
  color: var(--text-secondary);
  font-size: 11px;
  font-weight: 650;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.model-count {
  padding: 2px 6px;
  border-radius: 999px;
  color: var(--success);
  background: var(--success-soft);
  font-size: 9px;
}

.runtime-card__detail {
  display: grid;
  gap: 16px;
  padding: 16px 17px 17px 72px;
  border-top: 1px solid var(--border);
  background: var(--panel-2);
}

.runtime-detail__head {
  align-items: flex-start;
}

.runtime-detail__head strong {
  color: var(--text);
  font-size: 12px;
}

.runtime-detail__head p {
  margin-top: 4px;
  font-size: 11px;
}

.runtime-detail__facts {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
}

.runtime-detail__facts div {
  display: grid;
  gap: 4px;
  min-width: 0;
}

.runtime-detail__facts span {
  color: var(--text-faint);
  font-size: 10px;
}

.runtime-detail__facts strong {
  overflow: hidden;
  color: var(--text-secondary);
  font-size: 11px;
  font-weight: 650;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.runtime-detail__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 7px;
}

.runtime-skeleton {
  display: grid;
  grid-template-columns: 42px minmax(0, 1fr);
  gap: 13px;
  align-items: center;
  min-height: 82px;
  padding: 15px 17px;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--canvas);
}

.runtime-skeleton > span,
.runtime-skeleton i,
.api-loading span {
  display: block;
  border-radius: var(--radius-sm);
  background: var(--skeleton-base);
  animation: skeleton-pulse 1.1s ease-in-out infinite alternate;
}

.runtime-skeleton > span {
  width: 38px;
  height: 38px;
}

.runtime-skeleton div {
  display: grid;
  gap: 7px;
}

.runtime-skeleton i {
  width: 52%;
  height: 9px;
}

.runtime-skeleton i:nth-child(2) { width: 34%; }
.runtime-skeleton i:nth-child(3) { width: 68%; }

@keyframes skeleton-pulse {
  from { opacity: 0.55; }
  to { opacity: 1; }
}

.profile-grid {
  --agent-card-size: 304px;
  display: grid;
  grid-template-columns: repeat(auto-fill, var(--agent-card-size));
  gap: 20px;
  align-items: start;
  padding-top: 20px;
}

.profile-card {
  display: grid;
  grid-template-rows: auto minmax(0, 1fr) auto;
  width: var(--agent-card-size);
  height: var(--agent-card-size);
  min-width: 0;
  overflow: hidden;
  position: relative;
  padding: 20px;
  border: 1px solid var(--border);
  border-radius: var(--radius-xl);
  background: linear-gradient(145deg, var(--panel), var(--canvas));
  box-shadow: inset 0 1px 0 color-mix(in srgb, var(--panel) 55%, var(--hover));
  transition: border-color 0.18s ease, box-shadow 0.18s ease, transform 0.18s ease;
}

.profile-card::before {
  position: absolute;
  inset: 0 22px auto;
  height: 2px;
  border-radius: 0 0 999px 999px;
  background: var(--accent);
  content: '';
  opacity: 0;
  transition: opacity 0.18s ease;
}

.profile-card:hover {
  border-color: var(--accent-border);
  box-shadow: var(--shadow-panel);
  transform: translateY(-2px);
}

.profile-card--active {
  border-color: var(--accent-border);
  background: var(--panel);
  box-shadow: 0 0 0 3px var(--accent-soft), var(--shadow-panel);
}

.profile-card--active::before {
  opacity: 1;
}

.profile-card__top {
  align-items: flex-start;
  justify-content: space-between;
  gap: 14px;
}

.profile-card__identity {
  min-width: 0;
  gap: 13px;
  padding: 0;
  border: 0;
  color: inherit;
  background: transparent;
  text-align: left;
  cursor: pointer;
}

.profile-card__identity:focus-visible {
  outline: 2px solid var(--accent);
  outline-offset: 5px;
  border-radius: var(--radius-sm);
}

.cli-mark--card {
  width: 48px;
  height: 48px;
  border-radius: var(--radius-md);
  font-size: 12px;
  box-shadow: 0 0 0 4px var(--accent-soft);
}

.profile-card__heading {
  display: grid;
  min-width: 0;
  gap: 6px;
}

.profile-card__heading strong {
  overflow: hidden;
  font-size: 16px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.profile-card__heading small {
  overflow: hidden;
  color: var(--text-faint);
  font-size: 10px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.profile-card__body {
  display: grid;
  align-content: start;
  gap: 16px;
  min-width: 0;
  padding-top: 20px;
}

.profile-card__facts {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  min-width: 0;
  padding: 14px 0;
  border-top: 1px solid var(--border);
  border-bottom: 1px solid var(--border);
}

.profile-card__fact {
  display: grid;
  gap: 6px;
  min-width: 0;
  align-content: start;
  padding-right: 12px;
}

.profile-card__fact + .profile-card__fact {
  padding-right: 0;
  padding-left: 12px;
  border-left: 1px solid var(--border);
}

.profile-card__fact span {
  color: var(--text-faint);
  font-size: 10px;
}

.profile-card__fact strong {
  overflow: hidden;
  color: var(--text-secondary);
  font-size: 12px;
  font-weight: 700;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.profile-card__description {
  display: -webkit-box;
  min-height: 34px;
  margin: 0;
  overflow: hidden;
  color: var(--text-muted);
  font-size: 11px;
  line-height: 1.55;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.profile-card__actions {
  justify-content: space-between;
  gap: 10px;
  align-items: center;
  min-width: 0;
  padding-top: 14px;
  border-top: 1px solid var(--border);
}

.profile-card__action-group {
  display: flex;
  flex-wrap: nowrap;
  justify-content: flex-end;
  gap: 3px;
}

.profile-card__action-group :deep(.g-btn) {
  padding-inline: 7px;
  color: var(--text-muted);
  font-size: 10px;
  white-space: nowrap;
}

.profile-card__default-note {
  color: var(--accent-hover);
  font-size: 10px;
  font-weight: 700;
}

.default-foot {
  display: flex;
  justify-content: flex-end;
  gap: 6px;
  padding-top: 13px;
  color: var(--text-faint);
  font-size: 10px;
}

.default-foot strong {
  color: var(--text-secondary);
  font-weight: 700;
}

.empty-state {
  display: grid;
  justify-items: center;
  gap: 8px;
  min-height: 150px;
  place-content: center;
  padding: 24px;
  color: var(--text-muted);
  text-align: center;
}

.empty-state strong {
  color: var(--text-secondary);
  font-size: 13px;
}

.empty-state span {
  max-width: 360px;
  color: var(--text-faint);
  font-size: 11px;
  line-height: 1.5;
}

.api-surface {
  min-height: 380px;
}

.provider-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
  padding-top: 16px;
}

.provider-card {
  display: grid;
  gap: 7px;
  min-width: 0;
  padding: 15px;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--canvas);
}

.provider-card__top {
  justify-content: space-between;
  gap: 8px;
}

.provider-mark {
  width: 28px;
  height: 28px;
  border-radius: 7px;
  color: var(--accent-contrast);
  background: var(--accent);
  font-size: 12px;
}

.provider-card__meta {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  padding-top: 5px;
  border-top: 1px solid var(--border);
}

.api-empty {
  min-height: 220px;
}

.api-boundary {
  align-items: flex-start;
  gap: 10px;
  margin-top: 22px;
  padding: 13px 15px;
  border: 1px solid var(--accent-border);
  border-radius: var(--radius-md);
  color: var(--accent-hover);
  background: var(--accent-soft);
}

.api-boundary strong {
  color: var(--text-secondary);
  font-size: 11px;
}

.api-boundary p {
  margin-top: 3px;
  color: var(--text-muted);
  font-size: 11px;
}

.api-loading {
  display: flex;
  gap: 8px;
  padding-top: 18px;
}

.api-loading span {
  width: 33%;
  height: 90px;
  border: 1px solid var(--border);
}

.config-form {
  display: grid;
  gap: 15px;
}

.config-advanced {
  border-top: 1px solid var(--border);
}

.config-advanced summary {
  padding: 13px 0 1px;
  color: var(--text-secondary);
  font-size: 12px;
  font-weight: 750;
  cursor: pointer;
  list-style: none;
}

.config-advanced summary::-webkit-details-marker {
  display: none;
}

.config-advanced summary::after {
  float: right;
  color: var(--text-faint);
  content: '+';
  font-family: var(--font-mono);
  font-size: 15px;
}

.config-advanced[open] summary::after {
  content: '-';
}

.config-advanced__body {
  display: grid;
  gap: 15px;
  padding-top: 14px;
}

.config-form__intro {
  margin: 0;
  padding: 10px 12px;
  border-left: 3px solid var(--accent);
  background: var(--accent-soft);
}

.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}

.field {
  display: grid;
  gap: 6px;
  min-width: 0;
}

.field > span {
  color: var(--text-secondary);
  font-size: 12px;
  font-weight: 700;
}

.field small,
.field em {
  color: var(--text-muted);
  font-size: 10px;
  line-height: 1.45;
}

.field em,
.form-notice {
  color: var(--danger);
  font-style: normal;
  font-weight: 650;
}

.form-notice {
  margin: 0;
  padding: 8px 10px;
  border-left: 3px solid var(--danger);
  background: var(--danger-soft);
}

@media (max-width: 860px) {
  .agent-page { padding-inline: 20px; }
  .provider-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}

@media (max-width: 560px) {
  .agent-page { padding: 16px 14px 26px; }
  .settings-surface { padding: 16px; }
  .section-head { align-items: flex-start; flex-direction: column; }
  .section-head :deep(.g-btn) { align-self: flex-end; }
  .profiles-head { align-items: flex-start; flex-direction: column; }
  .profiles-head__actions { width: 100%; justify-content: space-between; }
  .runtime-card__detail { padding-left: 17px; }
  .runtime-detail__facts { grid-template-columns: 1fr; }
  .profile-grid { grid-template-columns: minmax(0, 1fr); }
  .profile-card { width: min(100%, var(--agent-card-size)); height: auto; aspect-ratio: 1 / 1; padding: 16px; }
  .profile-card__facts { grid-template-columns: 1fr; }
  .profile-card__fact,
  .profile-card__fact + .profile-card__fact { padding-inline: 0; }
  .profile-card__fact + .profile-card__fact { padding-top: 12px; border-top: 1px solid var(--border); border-left: 0; }
  .profile-card__actions { align-items: flex-start; flex-direction: column; }
  .profile-card__action-group { width: 100%; justify-content: flex-start; }
  .provider-grid { grid-template-columns: 1fr; }
  .form-grid { grid-template-columns: 1fr; }
  .default-foot { justify-content: flex-start; flex-wrap: wrap; }
}

@media (prefers-reduced-motion: reduce) {
  .mode-switch button,
  .runtime-card,
  .profile-card,
  .runtime-skeleton > span,
  .runtime-skeleton i,
  .api-loading span { transition: none; animation: none; }
}
</style>
