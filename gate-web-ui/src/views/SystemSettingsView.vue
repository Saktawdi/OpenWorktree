<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { GBadge, GButton, GIcon, GInput, GModal } from '@/components/ui';
import {
  createProvider,
  deleteProvider,
  getProviders,
  getSystemConfig,
  replaceProviderModels,
  updateProvider,
} from '@/api/config';
import type { ProviderPayload, ProviderView, SystemConfigView } from '@/api/config';
import { useConsolePreferences } from '@/composables/useConsolePreferences';
import { useAuthStore } from '@/stores/authStore';

type SettingsSection = 'models' | 'console' | 'runtime' | 'security';
type DataSource = 'server' | 'local';

interface ProviderForm {
  id: string;
  name: string;
  base_url: string;
  type: string;
  api_key_ref: string;
}

const providerStorageKey = 'gate.provider-config';
const defaultModelStorageKey = 'gate.default-models';

const fallbackConfig: SystemConfigView = {
  project: 'gate',
  auth_repo: '--',
  clones_root: '--',
  target_ref_whitelist: ['refs/heads/main'],
  gate_home: '--',
  engine_configured: false,
  web: {
    bind: '127.0.0.1',
    port: 4097,
    allowed_origins: ['127.0.0.1', 'localhost'],
  },
};

const fallbackProviders: ProviderView[] = [
  {
    id: 'newapi',
    name: 'New API Gateway',
    base_url: 'http://127.0.0.1:3000/v1',
    type: 'openai-compatible',
    credential_configured: true,
    model_count: 2,
    models: ['gpt-5.2-codex', 'claude-3-5-sonnet-20241022'],
  },
  {
    id: 'manual',
    name: '人工审核',
    base_url: 'local://manual',
    type: 'manual',
    credential_configured: true,
    model_count: 1,
    models: ['human'],
  },
];

const sectionItems: Array<{
  id: SettingsSection;
  label: string;
  description: string;
  icon: 'bot' | 'grid' | 'folder' | 'shield';
}> = [
  { id: 'models', label: '模型与供应商', description: '供应商、模型和默认选择', icon: 'bot' },
  { id: 'console', label: '控制台', description: '界面与工单默认行为', icon: 'grid' },
  { id: 'runtime', label: '运行环境', description: '仓库与执行引擎状态', icon: 'folder' },
  { id: 'security', label: '访问安全', description: '本地绑定与人工凭据', icon: 'shield' },
];

const router = useRouter();
const route = useRoute();
const auth = useAuthStore();
const validSections: SettingsSection[] = ['models', 'console', 'runtime', 'security'];
const routeSection = computed<SettingsSection>(() => {
  const value = String(route.query.section ?? 'models') as SettingsSection;
  return validSections.includes(value) ? value : 'models';
});
const activeSection = ref<SettingsSection>(routeSection.value);
const loading = ref(false);
const saving = ref(false);
const configSource = ref<DataSource>('local');
const notice = ref('');
const systemConfig = ref<SystemConfigView>(fallbackConfig);
const providers = ref<ProviderView[]>([]);
const activeProviderId = ref('');
const modelDraft = ref('');
const editingModel = ref<string | null>(null);
const editingModelValue = ref('');
const showProviderForm = ref(false);
const showDeleteConfirm = ref(false);
const editingProviderId = ref<string | null>(null);
const providerSubmitted = ref(false);
const providerForm = reactive<ProviderForm>(emptyProviderForm());
const defaultModels = reactive<Record<string, string>>({});

const {
  preferences,
  saveConsolePreferences,
  resetConsolePreferences,
} = useConsolePreferences();

const consoleDraft = reactive({
  density: preferences.density,
  defaultTicketView: preferences.defaultTicketView,
  reduceMotion: preferences.reduceMotion,
  theme: preferences.theme,
});

const activeProvider = computed(() =>
  providers.value.find((provider) => provider.id === activeProviderId.value) ?? providers.value[0] ?? null,
);
const configSourceLabel = computed(() => (configSource.value === 'server' ? '已连接后端' : '浏览器本地缓存'));
const webEndpoint = computed(() => {
  const web = systemConfig.value.web;
  return web ? `${web.bind}:${web.port}` : '--';
});
const providerFormError = computed(() => {
  if (!providerForm.id.trim()) return '请填写 Provider ID。';
  if (!/^[a-z0-9][a-z0-9._-]*$/i.test(providerForm.id.trim())) return 'ID 只能包含字母、数字、点、下划线和连字符。';
  if (!providerForm.name.trim()) return '请填写供应商名称。';
  if (!providerForm.base_url.trim()) return '请填写 Base URL。';
  if (providers.value.some((provider) => provider.id === providerForm.id.trim() && provider.id !== editingProviderId.value)) {
    return '这个 Provider ID 已经存在。';
  }
  return '';
});

function emptyProviderForm(): ProviderForm {
  return { id: '', name: '', base_url: '', type: 'openai-compatible', api_key_ref: '' };
}

function readLocalProviders(): ProviderView[] {
  try {
    const raw = localStorage.getItem(providerStorageKey);
    if (raw) {
      const parsed = JSON.parse(raw) as ProviderView[];
      if (Array.isArray(parsed) && parsed.length) return parsed;
    }
  } catch {
    // Keep the safe seed when browser storage is unavailable.
  }
  return fallbackProviders.map((provider) => ({ ...provider, models: [...provider.models] }));
}

function persistLocalProviders() {
  try {
    localStorage.setItem(providerStorageKey, JSON.stringify(providers.value));
  } catch {
    // Persistence is best effort. The active page state remains usable.
  }
}

function readDefaultModels() {
  try {
    const raw = localStorage.getItem(defaultModelStorageKey);
    if (raw) Object.assign(defaultModels, JSON.parse(raw));
  } catch {
    // Ignore malformed local preference and use the first model.
  }
}

function persistDefaultModels() {
  try {
    localStorage.setItem(defaultModelStorageKey, JSON.stringify(defaultModels));
  } catch {
    // Best effort only.
  }
}

function ensureDefaultModel(provider: ProviderView | null) {
  if (!provider) return '';
  const current = defaultModels[provider.id];
  if (current && provider.models.includes(current)) return current;
  const fallback = provider.models[0] ?? '';
  if (fallback) defaultModels[provider.id] = fallback;
  return fallback;
}

function selectProvider(id: string) {
  activeProviderId.value = id;
  editingModel.value = null;
  modelDraft.value = '';
  ensureDefaultModel(providers.value.find((provider) => provider.id === id) ?? null);
}

function providerPayload(form: ProviderForm): ProviderPayload {
  const payload: ProviderPayload = {
    id: form.id.trim(),
    name: form.name.trim(),
    base_url: form.base_url.trim(),
    type: form.type.trim() || 'openai-compatible',
  };
  if (form.api_key_ref.trim()) payload.api_key_ref = form.api_key_ref.trim();
  return payload;
}

function openCreateProvider() {
  Object.assign(providerForm, emptyProviderForm());
  editingProviderId.value = null;
  providerSubmitted.value = false;
  showProviderForm.value = true;
}

function openEditProvider(provider: ProviderView) {
  Object.assign(providerForm, {
    id: provider.id,
    name: provider.name,
    base_url: provider.base_url,
    type: provider.type,
    api_key_ref: '',
  });
  editingProviderId.value = provider.id;
  providerSubmitted.value = false;
  showProviderForm.value = true;
}

function closeProviderForm() {
  showProviderForm.value = false;
  providerSubmitted.value = false;
}

function replaceProviderInState(next: ProviderView) {
  const index = providers.value.findIndex((provider) => provider.id === next.id);
  if (index >= 0) providers.value[index] = { ...next, models: [...next.models], model_count: next.models.length };
  else providers.value.push({ ...next, models: [...next.models], model_count: next.models.length });
  activeProviderId.value = next.id;
  ensureDefaultModel(next);
  persistLocalProviders();
}

async function saveProvider() {
  providerSubmitted.value = true;
  if (providerFormError.value) return;
  saving.value = true;
  notice.value = '';
  const payload = providerPayload(providerForm);
  try {
    const next = editingProviderId.value
      ? await updateProvider(editingProviderId.value, payload)
      : await createProvider(payload);
    replaceProviderInState(next);
    configSource.value = 'server';
    notice.value = `已保存 ${next.name}。`;
  } catch {
    const next: ProviderView = {
      id: payload.id!,
      name: payload.name,
      base_url: payload.base_url,
      type: payload.type,
      credential_configured: Boolean(payload.api_key_ref) || Boolean(activeProvider.value?.credential_configured),
      models: editingProviderId.value ? [...(activeProvider.value?.models ?? [])] : [],
      model_count: editingProviderId.value ? activeProvider.value?.models.length ?? 0 : 0,
    };
    if (editingProviderId.value && editingProviderId.value !== next.id) {
      providers.value = providers.value.filter((provider) => provider.id !== editingProviderId.value);
      delete defaultModels[editingProviderId.value];
    }
    replaceProviderInState(next);
    notice.value = '后端暂不可用，已保存到浏览器本地缓存。';
  } finally {
    saving.value = false;
    closeProviderForm();
  }
}

async function removeProvider() {
  const provider = activeProvider.value;
  if (!provider || provider.id === 'manual') return;
  saving.value = true;
  try {
    await deleteProvider(provider.id);
    configSource.value = 'server';
  } catch {
    notice.value = '后端暂不可用，已在当前浏览器移除该供应商。';
  }
  providers.value = providers.value.filter((item) => item.id !== provider.id);
  delete defaultModels[provider.id];
  persistLocalProviders();
  persistDefaultModels();
  activeProviderId.value = providers.value[0]?.id ?? '';
  showDeleteConfirm.value = false;
  saving.value = false;
  notice.value = notice.value || `已移除 ${provider.name}。`;
}

function normalizeModel(value: string) {
  return value.trim().replace(/\s+/g, ' ');
}

async function saveModels(nextModels: string[], message: string) {
  const provider = activeProvider.value;
  if (!provider) return;
  const models = [...new Set(nextModels.map(normalizeModel).filter(Boolean))];
  saving.value = true;
  try {
    const next = await replaceProviderModels(provider.id, models);
    replaceProviderInState(next);
    configSource.value = 'server';
  } catch {
    provider.models = models;
    provider.model_count = models.length;
    persistLocalProviders();
    notice.value = '后端暂不可用，模型列表已保存到浏览器本地缓存。';
  } finally {
    saving.value = false;
  }
  ensureDefaultModel(provider);
  persistDefaultModels();
  notice.value = notice.value || message;
}

async function addModel() {
  const model = normalizeModel(modelDraft.value);
  if (!model || !activeProvider.value) return;
  if (activeProvider.value.models.includes(model)) {
    notice.value = '模型已经在当前供应商下。';
    return;
  }
  modelDraft.value = '';
  await saveModels([...activeProvider.value.models, model], `已添加模型 ${model}。`);
}

function startEditModel(model: string) {
  editingModel.value = model;
  editingModelValue.value = model;
}

function cancelEditModel() {
  editingModel.value = null;
  editingModelValue.value = '';
}

async function saveModelEdit() {
  const provider = activeProvider.value;
  const oldModel = editingModel.value;
  const nextModel = normalizeModel(editingModelValue.value);
  if (!provider || !oldModel || !nextModel) return;
  if (nextModel !== oldModel && provider.models.includes(nextModel)) {
    notice.value = '模型名称不能重复。';
    return;
  }
  const models = provider.models.map((model) => (model === oldModel ? nextModel : model));
  if (defaultModels[provider.id] === oldModel) defaultModels[provider.id] = nextModel;
  cancelEditModel();
  await saveModels(models, `已更新模型 ${nextModel}。`);
}

async function removeModel(model: string) {
  const provider = activeProvider.value;
  if (!provider) return;
  const models = provider.models.filter((item) => item !== model);
  await saveModels(models, `已移除模型 ${model}。`);
}

function setDefaultModel(model: string) {
  if (!activeProvider.value) return;
  defaultModels[activeProvider.value.id] = model;
  persistDefaultModels();
  notice.value = `${model} 已设为默认模型。`;
}

async function loadProviders() {
  const localProviders = readLocalProviders();
  providers.value = localProviders;
  activeProviderId.value = localProviders[0]?.id ?? '';
  try {
    const remoteProviders = await getProviders();
    if (remoteProviders.length) {
      providers.value = remoteProviders;
      configSource.value = 'server';
      persistLocalProviders();
    }
  } catch {
    configSource.value = 'local';
  }
  ensureDefaultModel(activeProvider.value);
}

async function loadRuntimeConfig() {
  loading.value = true;
  try {
    systemConfig.value = await getSystemConfig();
    configSource.value = 'server';
  } catch {
    systemConfig.value = fallbackConfig;
    notice.value = '后端配置暂不可用，当前显示安全默认值。';
  } finally {
    loading.value = false;
  }
}

function saveConsole() {
  saveConsolePreferences({
    density: consoleDraft.density,
    defaultTicketView: consoleDraft.defaultTicketView,
    reduceMotion: consoleDraft.reduceMotion,
    theme: consoleDraft.theme,
  });
  notice.value = '控制台偏好已保存。';
}

function resetConsole() {
  Object.assign(consoleDraft, resetConsolePreferences());
  notice.value = '已恢复默认控制台偏好。';
}

function logout() {
  auth.logout();
  router.push({ name: 'login' });
}

function goSettingsSection(section: SettingsSection) {
  activeSection.value = section;
  router.replace({ name: 'settings', query: { section } });
}

onMounted(async () => {
  readDefaultModels();
  await Promise.all([loadProviders(), loadRuntimeConfig()]);
});

watch(routeSection, (section) => {
  activeSection.value = section;
});
</script>

<template>
  <div class="settings-page">
    <header class="settings-header">
      <div>
        <p class="settings-kicker"><GIcon name="settings" :size="13" /> System settings</p>
        <h2>系统设置</h2>
        <p>把模型连接、控制台偏好和本地边界放在同一个可验证的工作区。</p>
      </div>
      <span class="source-state" :class="{ 'source-state--online': configSource === 'server' }">
        <i aria-hidden="true" />
        {{ configSourceLabel }}
      </span>
    </header>

    <p v-if="notice" class="settings-notice" role="status">{{ notice }}</p>

    <div class="settings-layout">
      <nav class="settings-nav" aria-label="设置分类">
        <button
          v-for="item in sectionItems"
          :key="item.id"
          type="button"
          :class="{ active: activeSection === item.id }"
          :aria-current="activeSection === item.id ? 'page' : undefined"
          @click="goSettingsSection(item.id)"
        >
          <GIcon :name="item.icon" :size="15" />
          <span><strong>{{ item.label }}</strong><small>{{ item.description }}</small></span>
        </button>
      </nav>

      <main class="settings-content">
        <section v-if="activeSection === 'models'" class="settings-section models-section" aria-labelledby="models-heading">
          <header class="settings-section__head">
            <div>
              <p class="section-label">连接目录</p>
              <h2 id="models-heading">模型与供应商</h2>
              <p>先选供应商，再维护它旗下能被 Agent 和审核引擎使用的模型。</p>
            </div>
            <GButton size="sm" variant="primary" @click="openCreateProvider">
              <GIcon name="plus" :size="14" /> 添加供应商
            </GButton>
          </header>

          <div class="provider-workspace">
            <aside class="provider-catalog" aria-label="供应商列表">
              <div class="provider-catalog__head"><span>供应商</span><b>{{ providers.length }}</b></div>
              <div v-if="providers.length" class="provider-list">
                <button
                  v-for="provider in providers"
                  :key="provider.id"
                  type="button"
                  class="provider-row"
                  :class="{ active: activeProvider?.id === provider.id }"
                  :aria-pressed="activeProvider?.id === provider.id"
                  @click="selectProvider(provider.id)"
                >
                  <span class="provider-row__mark"><GIcon name="bot" :size="15" /></span>
                  <span class="provider-row__copy"><strong>{{ provider.name }}</strong><small class="mono">{{ provider.id }}</small></span>
                  <span class="provider-row__count">{{ provider.models.length }}</span>
                </button>
              </div>
              <div v-else class="empty-provider"><GIcon name="bot" :size="18" /><span>还没有供应商</span></div>
              <button type="button" class="catalog-add" @click="openCreateProvider"><GIcon name="plus" :size="14" /> 新建供应商</button>
            </aside>

            <div v-if="activeProvider" class="provider-detail">
              <header class="provider-detail__head">
                <div>
                  <div class="provider-title"><span class="provider-title__mark"><GIcon name="bot" :size="17" /></span><h3>{{ activeProvider.name }}</h3><GBadge tone="neutral">{{ activeProvider.type }}</GBadge></div>
                  <p class="mono provider-id">{{ activeProvider.id }}</p>
                </div>
                <div class="detail-actions">
                  <GButton size="sm" variant="secondary" @click="openEditProvider(activeProvider)">编辑</GButton>
                  <GButton v-if="activeProvider.id !== 'manual'" size="sm" variant="ghost" @click="showDeleteConfirm = true">删除</GButton>
                </div>
              </header>

              <dl class="provider-facts">
                <div><dt>Base URL</dt><dd class="mono">{{ activeProvider.base_url }}</dd></div>
                <div><dt>凭据引用</dt><dd><span class="credential-state"><i aria-hidden="true" />{{ activeProvider.credential_configured ? '已配置' : '未配置' }}</span></dd></div>
                <div><dt>模型数量</dt><dd>{{ activeProvider.models.length }} 个模型</dd></div>
                <div><dt>默认模型</dt><dd class="mono">{{ ensureDefaultModel(activeProvider) || '未设置' }}</dd></div>
              </dl>

              <section class="models-panel" aria-labelledby="provider-models-heading">
                <header class="models-panel__head"><div><h3 id="provider-models-heading">可用模型</h3><p>默认模型会用于未明确指定模型的新会话。</p></div><span class="mono">{{ activeProvider.models.length }} items</span></header>
                <form class="model-add" @submit.prevent="addModel"><GInput v-model="modelDraft" aria-label="模型名称" placeholder="输入模型 ID，例如 gpt-5.2-codex" /><GButton type="submit" size="sm" variant="secondary" :disabled="!modelDraft.trim() || saving"><GIcon name="plus" :size="14" /> 添加模型</GButton></form>
                <div v-if="activeProvider.models.length" class="model-list">
                  <div v-for="model in activeProvider.models" :key="model" class="model-row">
                    <span class="model-row__icon"><GIcon name="check" :size="14" /></span>
                    <template v-if="editingModel === model">
                      <GInput v-model="editingModelValue" aria-label="编辑模型名称" @keydown.enter.prevent="saveModelEdit" />
                      <div class="model-row__actions"><button type="button" aria-label="保存模型" @click="saveModelEdit"><GIcon name="check" :size="14" /></button><button type="button" aria-label="取消编辑" @click="cancelEditModel"><GIcon name="x" :size="14" /></button></div>
                    </template>
                    <template v-else>
                      <span class="model-row__name mono">{{ model }}</span>
                      <span v-if="defaultModels[activeProvider.id] === model" class="model-default">默认</span>
                      <div class="model-row__actions"><button type="button" :aria-label="defaultModels[activeProvider.id] === model ? '当前默认模型' : '设为默认模型'" :class="{ active: defaultModels[activeProvider.id] === model }" @click="setDefaultModel(model)"><GIcon name="check" :size="14" /></button><button type="button" aria-label="编辑模型" @click="startEditModel(model)">编辑</button><button type="button" aria-label="移除模型" @click="removeModel(model)"><GIcon name="x" :size="14" /></button></div>
                    </template>
                  </div>
                </div>
                <div v-else class="models-empty"><GIcon name="bot" :size="18" /><strong>还没有模型</strong><span>添加一个模型 ID 后，Agent 配置就能直接选择它。</span></div>
              </section>
            </div>
            <div v-else class="provider-empty-detail"><GIcon name="bot" :size="20" /><strong>选择一个供应商</strong><span>或者先创建新的模型供应商。</span><GButton size="sm" variant="secondary" @click="openCreateProvider">添加供应商</GButton></div>
          </div>
        </section>

        <section v-else-if="activeSection === 'console'" class="settings-section" aria-labelledby="console-heading">
          <header class="settings-section__head"><div><p class="section-label">本地偏好</p><h2 id="console-heading">控制台</h2><p>这些偏好只保存在当前浏览器，不会改动 gate.toml。</p></div></header>
          <div class="setting-rows">
            <div class="setting-row"><div><strong>外观主题</strong><span>在日间和夜间工作区之间快速切换。</span></div><div class="segmented-control" role="group" aria-label="外观主题"><button type="button" :aria-pressed="consoleDraft.theme === 'light'" :class="{ active: consoleDraft.theme === 'light' }" @click="consoleDraft.theme = 'light'">日间</button><button type="button" :aria-pressed="consoleDraft.theme === 'dark'" :class="{ active: consoleDraft.theme === 'dark' }" @click="consoleDraft.theme = 'dark'">夜间</button></div></div>
            <div class="setting-row"><div><strong>界面密度</strong><span>控制看板卡片、列表间距和页面留白。</span></div><div class="segmented-control" role="group" aria-label="界面密度"><button type="button" :aria-pressed="consoleDraft.density === 'comfortable'" :class="{ active: consoleDraft.density === 'comfortable' }" @click="consoleDraft.density = 'comfortable'">舒适</button><button type="button" :aria-pressed="consoleDraft.density === 'compact'" :class="{ active: consoleDraft.density === 'compact' }" @click="consoleDraft.density = 'compact'">紧凑</button></div></div>
            <div class="setting-row"><div><strong>工单默认视图</strong><span>进入工单工作区时首先展示的视图。</span></div><div class="segmented-control" role="group" aria-label="工单默认视图"><button type="button" :aria-pressed="consoleDraft.defaultTicketView === 'board'" :class="{ active: consoleDraft.defaultTicketView === 'board' }" @click="consoleDraft.defaultTicketView = 'board'">看板</button><button type="button" :aria-pressed="consoleDraft.defaultTicketView === 'records'" :class="{ active: consoleDraft.defaultTicketView === 'records' }" @click="consoleDraft.defaultTicketView = 'records'">记录</button></div></div>
            <label class="setting-row setting-row--toggle"><div><strong>减少界面动效</strong><span>关闭抽屉、悬停和状态切换中的非必要动画。</span></div><span class="switch-control"><input v-model="consoleDraft.reduceMotion" type="checkbox" /><i aria-hidden="true" /></span></label>
          </div>
          <footer class="settings-actions"><GButton variant="ghost" @click="resetConsole">恢复默认</GButton><GButton variant="primary" @click="saveConsole"><GIcon name="check" :size="14" /> 保存偏好</GButton></footer>
        </section>

        <section v-else-if="activeSection === 'runtime'" class="settings-section" aria-labelledby="runtime-heading">
          <header class="settings-section__head"><div><p class="section-label">服务状态</p><h2 id="runtime-heading">运行环境</h2><p>来自 `/api/config` 的只读运行信息，敏感字段不会返回前端。</p></div><GButton size="sm" variant="secondary" :loading="loading" @click="loadRuntimeConfig"><GIcon name="refresh" :size="14" /> 重新读取</GButton></header>
          <dl class="system-facts"><div><dt>项目</dt><dd>{{ systemConfig.project }}</dd></div><div><dt>Gate Home</dt><dd class="mono">{{ systemConfig.gate_home }}</dd></div><div><dt>权威仓库</dt><dd class="mono">{{ systemConfig.auth_repo }}</dd></div><div><dt>Clone 根目录</dt><dd class="mono">{{ systemConfig.clones_root }}</dd></div><div><dt>目标引用白名单</dt><dd class="value-list"><span v-for="refName in systemConfig.target_ref_whitelist" :key="refName" class="mono">{{ refName }}</span></dd></div><div><dt>审核引擎</dt><dd><span class="engine-state" :class="{ 'engine-state--ready': systemConfig.engine_configured }">{{ systemConfig.engine_configured ? '已配置' : '未读取' }}</span></dd></div></dl>
        </section>

        <section v-else class="settings-section" aria-labelledby="security-heading">
          <header class="settings-section__head"><div><p class="section-label">本机边界</p><h2 id="security-heading">访问安全</h2><p>控制台只允许本机访问，服务端配置仍在 gate.toml 中维护。</p></div></header>
          <dl class="security-facts"><div><dt>监听地址</dt><dd class="mono">{{ webEndpoint }}</dd><span>必须使用 loopback 地址，禁止绑定 0.0.0.0。</span></div><div><dt>允许来源</dt><dd class="value-list"><span v-for="origin in systemConfig.web?.allowed_origins ?? []" :key="origin" class="mono">{{ origin }}</span></dd><span>Host 与 Origin 均受白名单校验。</span></div><div><dt>HUMAN token</dt><dd class="credential-state"><i aria-hidden="true" />{{ auth.tokenDigest || '未登录' }}</dd><span>浏览器只保存明文 token，配置接口不会回传敏感值。</span></div></dl>
          <footer class="danger-zone"><div><strong>清除本机登录凭据</strong><span>删除当前浏览器中的 token 并返回登录页。</span></div><GButton variant="danger" @click="logout">退出并清除</GButton></footer>
        </section>
      </main>
    </div>

    <GModal :show="showProviderForm" :title="editingProviderId ? '编辑供应商' : '添加供应商'" width="560px" @close="closeProviderForm">
      <form class="provider-form" @submit.prevent="saveProvider">
        <p>保存的是连接元数据和凭据引用，不会把 API key 明文写进前端。</p>
        <div class="form-grid"><label class="field"><span>Provider ID</span><GInput v-model="providerForm.id" :disabled="Boolean(editingProviderId)" placeholder="例如 newapi" /><em v-if="providerSubmitted && providerFormError">{{ providerFormError }}</em></label><label class="field"><span>名称</span><GInput v-model="providerForm.name" placeholder="例如 New API Gateway" /></label></div>
        <label class="field"><span>Base URL</span><GInput v-model="providerForm.base_url" placeholder="https://api.example.com/v1" /></label>
        <div class="form-grid"><label class="field"><span>类型</span><GInput v-model="providerForm.type" placeholder="openai-compatible" /></label><label class="field"><span>凭据引用</span><GInput v-model="providerForm.api_key_ref" placeholder="例如 env:NEWAPI_KEY" /><small>留空表示保留已有引用。不要填写 API key 明文。</small></label></div>
        <p v-if="providerSubmitted && providerFormError" class="form-notice" role="alert">{{ providerFormError }}</p>
      </form>
      <template #footer><GButton variant="ghost" @click="closeProviderForm">取消</GButton><GButton variant="primary" :loading="saving" @click="saveProvider">保存供应商</GButton></template>
    </GModal>

    <GModal :show="showDeleteConfirm" title="删除供应商" width="430px" @close="showDeleteConfirm = false">
      <p class="confirm-copy">将删除 {{ activeProvider?.name }} 以及它缓存的模型列表。已经创建的 Agent 配置不会被自动改写。</p>
      <template #footer><GButton variant="ghost" @click="showDeleteConfirm = false">取消</GButton><GButton variant="danger" :loading="saving" @click="removeProvider">确认删除</GButton></template>
    </GModal>
  </div>
</template>

<style scoped>
.settings-page { display: flex; height: 100%; min-width: 0; min-height: 0; flex-direction: column; padding: 20px 28px 0; overflow: hidden; }
.settings-header { display: flex; align-items: flex-end; justify-content: space-between; gap: 24px; flex: none; padding-bottom: 18px; }
.settings-header h2 { margin: 0; color: var(--text); font-size: 24px; font-weight: 760; letter-spacing: -0.03em; }
.settings-header > div > p:last-child { margin: 6px 0 0; color: var(--text-muted); font-size: 12px; }
.settings-kicker, .section-label { display: inline-flex; align-items: center; gap: 6px; margin: 0 0 7px; color: var(--accent-hover); font-size: 10px; font-weight: 800; letter-spacing: .1em; text-transform: uppercase; }
.source-state { display: inline-flex; align-items: center; gap: 7px; padding: 5px 8px; border: 1px solid var(--border); border-radius: 6px; color: var(--text-muted); background: var(--panel); font-size: 10px; font-weight: 700; }
.source-state i, .credential-state i { width: 7px; height: 7px; flex: none; border-radius: 50%; background: var(--warning); }
.source-state--online i, .credential-state i { background: var(--success); box-shadow: 0 0 0 3px var(--success-soft); }
.settings-notice { flex: none; margin: 0 0 12px; padding: 8px 10px; border-left: 3px solid var(--accent); color: var(--text-secondary); background: var(--accent-soft); font-size: 11px; }
.settings-layout { display: grid; grid-template-columns: 205px minmax(0, 1fr); min-height: 0; flex: 1; border-top: 1px solid var(--border); }
.settings-nav { display: grid; align-content: start; gap: 3px; padding: 14px 14px 24px 0; border-right: 1px solid var(--border); }
.settings-nav button { display: grid; grid-template-columns: 24px minmax(0, 1fr); gap: 8px; align-items: start; width: 100%; padding: 9px; border: 1px solid transparent; border-radius: 6px; color: var(--text-muted); background: transparent; text-align: left; cursor: pointer; }
.settings-nav button:hover { color: var(--text); background: var(--hover); }
.settings-nav button.active { border-color: rgba(9, 105, 218, .22); color: var(--accent-hover); background: var(--accent-soft); }
.settings-nav button > span { display: grid; gap: 2px; min-width: 0; }
.settings-nav strong { color: inherit; font-size: 12px; }
.settings-nav small { overflow: hidden; color: var(--text-faint); font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
.settings-content { min-width: 0; overflow: auto; padding: 0 0 34px 26px; }
.settings-section { width: 100%; max-width: 1120px; }
.settings-section__head { display: flex; align-items: center; justify-content: space-between; gap: 18px; min-height: 86px; border-bottom: 1px solid var(--border); }
.settings-section__head h2 { margin: 0; color: var(--text); font-size: 18px; font-weight: 750; letter-spacing: -.02em; }
.settings-section__head p:last-child { margin: 5px 0 0; color: var(--text-muted); font-size: 11px; }
.provider-workspace { display: grid; grid-template-columns: 240px minmax(0, 1fr); min-height: 500px; }
.provider-catalog { display: flex; flex-direction: column; min-width: 0; padding: 17px 14px 0 0; border-right: 1px solid var(--border); }
.provider-catalog__head { display: flex; align-items: center; justify-content: space-between; padding: 0 9px 9px; color: var(--text-muted); font-size: 11px; font-weight: 750; }
.provider-catalog__head b { color: var(--text-faint); font-family: var(--font-mono); font-size: 10px; }
.provider-list { display: grid; gap: 2px; }
.provider-row { display: grid; grid-template-columns: 28px minmax(0, 1fr) auto; gap: 8px; align-items: center; min-width: 0; width: 100%; padding: 9px; border: 1px solid transparent; border-radius: 6px; color: var(--text-secondary); background: transparent; text-align: left; cursor: pointer; }
.provider-row:hover { background: var(--hover); }
.provider-row.active { border-color: rgba(9, 105, 218, .24); color: var(--text); background: var(--accent-soft); }
.provider-row__mark, .provider-title__mark { display: grid; place-items: center; width: 26px; height: 26px; border: 1px solid var(--border); border-radius: 6px; color: var(--accent); background: var(--panel); }
.provider-row__copy { display: grid; gap: 2px; min-width: 0; }
.provider-row__copy strong { overflow: hidden; color: inherit; font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.provider-row__copy small { overflow: hidden; color: var(--text-faint); font-size: 9px; text-overflow: ellipsis; white-space: nowrap; }
.provider-row__count { min-width: 18px; color: var(--text-faint); font-family: var(--font-mono); font-size: 10px; text-align: right; }
.catalog-add { display: inline-flex; align-items: center; gap: 6px; margin: auto 8px 0; padding: 11px 0; border: 0; border-top: 1px solid var(--border); color: var(--accent-hover); background: transparent; font-size: 11px; font-weight: 700; cursor: pointer; }
.empty-provider { display: grid; justify-items: center; gap: 7px; padding: 28px 10px; color: var(--text-muted); font-size: 11px; text-align: center; }
.provider-detail { min-width: 0; padding-left: 26px; }
.provider-detail__head { display: flex; align-items: center; justify-content: space-between; gap: 16px; min-height: 86px; border-bottom: 1px solid var(--border); }
.provider-title { display: flex; align-items: center; gap: 9px; }
.provider-title h3 { margin: 0; color: var(--text); font-size: 16px; font-weight: 750; }
.provider-id { margin: 5px 0 0 35px; color: var(--text-faint); font-size: 10px; }
.detail-actions { display: flex; gap: 6px; }
.provider-facts { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); margin: 0; border-bottom: 1px solid var(--border); }
.provider-facts > div { min-width: 0; padding: 13px 12px 13px 0; }
.provider-facts > div:not(:first-child) { padding-left: 12px; border-left: 1px solid var(--border); }
.provider-facts dt { color: var(--text-muted); font-size: 10px; font-weight: 700; }
.provider-facts dd { margin: 5px 0 0; overflow: hidden; color: var(--text-secondary); font-size: 11px; font-weight: 650; text-overflow: ellipsis; white-space: nowrap; }
.credential-state { display: inline-flex; align-items: center; gap: 7px; }
.models-panel { padding-top: 22px; }
.models-panel__head { display: flex; align-items: flex-end; justify-content: space-between; gap: 16px; padding-bottom: 10px; }
.models-panel__head h3 { margin: 0; color: var(--text); font-size: 14px; font-weight: 750; }
.models-panel__head p { margin: 4px 0 0; color: var(--text-muted); font-size: 11px; }
.models-panel__head > span { color: var(--text-faint); font-size: 10px; }
.model-add { display: flex; gap: 7px; padding: 11px 0; border-top: 1px solid var(--border); border-bottom: 1px solid var(--border); }
.model-add :deep(.g-input) { min-height: 34px; }
.model-list { display: grid; }
.model-row { display: grid; grid-template-columns: 22px minmax(0, 1fr) auto auto; gap: 8px; align-items: center; min-height: 48px; border-bottom: 1px solid var(--border); }
.model-row__icon { display: grid; place-items: center; width: 20px; height: 20px; border-radius: 4px; color: var(--text-faint); background: var(--panel-2); }
.model-row__name { min-width: 0; overflow: hidden; color: var(--text-secondary); font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }
.model-default { padding: 2px 5px; border-radius: 4px; color: var(--success); background: var(--success-soft); font-size: 9px; font-weight: 750; }
.model-row__actions { display: inline-flex; align-items: center; gap: 2px; }
.model-row__actions button { min-height: 25px; padding: 0 6px; border: 0; border-radius: 4px; color: var(--text-muted); background: transparent; font-size: 10px; cursor: pointer; }
.model-row__actions button:hover, .model-row__actions button.active { color: var(--accent-hover); background: var(--accent-soft); }
.models-empty, .provider-empty-detail { display: grid; justify-items: center; gap: 8px; min-height: 180px; place-content: center; color: var(--text-muted); text-align: center; }
.models-empty strong, .provider-empty-detail strong { color: var(--text-secondary); font-size: 13px; }
.models-empty span, .provider-empty-detail span { color: var(--text-faint); font-size: 11px; }
.provider-empty-detail { min-height: 360px; padding-left: 26px; }
.setting-rows { display: grid; }
.setting-row { display: grid; grid-template-columns: minmax(240px, 1fr) minmax(220px, 320px); gap: 24px; align-items: center; min-height: 78px; padding: 15px 0; border-bottom: 1px solid var(--border); }
.setting-row > div:first-child { display: grid; gap: 4px; }
.setting-row strong, .danger-zone strong { color: var(--text); font-size: 13px; font-weight: 720; }
.setting-row > div:first-child span, .danger-zone span { color: var(--text-muted); font-size: 11px; }
.segmented-control { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 2px; padding: 2px; border: 1px solid var(--border); border-radius: 6px; background: var(--panel-2); }
.segmented-control button { min-height: 30px; border: 0; border-radius: 4px; color: var(--text-muted); background: transparent; font-size: 11px; font-weight: 700; cursor: pointer; }
.segmented-control button.active { color: var(--text); background: var(--panel); box-shadow: 0 1px 2px rgba(31, 35, 40, .1); }
.switch-control { position: relative; justify-self: end; width: 40px; height: 22px; }
.switch-control input { position: absolute; inset: 0; z-index: 1; width: 100%; height: 100%; margin: 0; opacity: 0; cursor: pointer; }
.switch-control i { position: absolute; inset: 0; border: 1px solid var(--border-strong); border-radius: 999px; background: var(--panel-2); }
.switch-control i::after { position: absolute; top: 3px; left: 3px; width: 14px; height: 14px; border-radius: 50%; background: var(--panel); box-shadow: 0 1px 2px rgba(31,35,40,.2); content: ''; transition: transform .16s ease; }
.switch-control input:checked + i { border-color: var(--accent); background: var(--accent); }
.switch-control input:checked + i::after { transform: translateX(18px); }
.settings-actions { display: flex; justify-content: flex-end; gap: 7px; padding-top: 16px; }
.system-facts, .security-facts { margin: 0; }
.system-facts > div { display: grid; grid-template-columns: 180px minmax(0,1fr); gap: 16px; align-items: center; min-height: 56px; border-bottom: 1px solid var(--border); }
.system-facts dt, .security-facts dt { color: var(--text-muted); font-size: 11px; font-weight: 700; }
.system-facts dd, .security-facts dd { min-width: 0; margin: 0; overflow: hidden; color: var(--text-secondary); font-size: 12px; font-weight: 650; text-overflow: ellipsis; white-space: nowrap; }
.value-list { display: flex; flex-wrap: wrap; gap: 5px; }
.value-list span, .engine-state { padding: 3px 6px; border-radius: 4px; color: var(--text-secondary); background: var(--panel-2); font-size: 10px; }
.engine-state--ready { color: var(--success); background: var(--success-soft); }
.security-facts > div { display: grid; grid-template-columns: 180px minmax(0,1fr); gap: 8px 16px; padding: 15px 0; border-bottom: 1px solid var(--border); }
.security-facts > div > span { grid-column: 2; color: var(--text-muted); font-size: 10px; }
.danger-zone { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding-top: 20px; }
.danger-zone > div { display: grid; gap: 4px; }
.provider-form { display: grid; gap: 15px; }
.provider-form > p, .confirm-copy { margin: 0; color: var(--text-muted); font-size: 12px; line-height: 1.55; }
.form-grid { display: grid; grid-template-columns: repeat(2, minmax(0,1fr)); gap: 12px; }
.field { display: grid; gap: 6px; }
.field > span { color: var(--text-secondary); font-size: 12px; font-weight: 700; }
.field small, .field em { color: var(--text-muted); font-size: 10px; line-height: 1.4; }
.field em, .form-notice { margin: 0; color: var(--danger); font-style: normal; font-weight: 650; }
.form-notice { padding: 8px 10px; border-left: 3px solid var(--danger); background: var(--danger-soft); }
@media (min-width: 681px) { .settings-layout { display: block; } .settings-nav { display: none; } .settings-content { padding-left: 0; } }
@media (max-width: 900px) { .settings-page { padding-inline: 18px; } .provider-workspace { grid-template-columns: 190px minmax(0,1fr); } .provider-facts { grid-template-columns: repeat(2,minmax(0,1fr)); } .provider-facts > div:nth-child(3) { border-left: 0; padding-left: 0; border-top: 1px solid var(--border); } .provider-facts > div:nth-child(4) { border-top: 1px solid var(--border); } }
@media (max-width: 680px) { .settings-page { padding: 16px 14px 0; } .settings-header { align-items: flex-start; flex-direction: column; gap: 12px; } .settings-layout { grid-template-columns: 1fr; border-top: 0; } .settings-nav { grid-template-columns: repeat(2,minmax(0,1fr)); padding: 10px 0; border-right: 0; border-bottom: 1px solid var(--border); } .settings-nav button { min-height: 48px; } .settings-content { padding: 0 0 28px; } .settings-section__head { min-height: 76px; } .provider-workspace { grid-template-columns: 1fr; } .provider-catalog { padding: 12px 0 0; border-right: 0; border-bottom: 1px solid var(--border); } .provider-list { grid-template-columns: repeat(2,minmax(0,1fr)); } .catalog-add { margin: 6px 8px 0; } .provider-detail { padding: 0; } .provider-detail__head { min-height: 76px; } .provider-facts { grid-template-columns: repeat(2,minmax(0,1fr)); } .provider-facts > div, .provider-facts > div:not(:first-child) { padding: 11px 8px 11px 0; } .provider-facts > div:nth-child(odd) { border-left: 0; padding-left: 0; } .provider-facts > div:nth-child(n+3) { border-top: 1px solid var(--border); } .model-add { align-items: stretch; flex-direction: column; } .setting-row { grid-template-columns: 1fr; gap: 10px; } .switch-control { justify-self: start; } .form-grid { grid-template-columns: 1fr; } }
</style>
