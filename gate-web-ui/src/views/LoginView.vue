<script setup lang="ts">
import { ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { verifyToken } from '@/api/auth';
import { useAuthStore } from '@/stores/authStore';
import { GButton, GCard, GField, GIcon, GInput } from '@/components/ui';

const auth = useAuthStore();
const router = useRouter();
const route = useRoute();
const token = ref('');
const error = ref('');
const submitting = ref(false);

async function submit() {
  const value = token.value.trim();
  if (!value) {
    error.value = '请输入登录令牌';
    return;
  }
  if (value.length < 6) {
    error.value = '登录令牌无效或已撤销';
    return;
  }
  if (submitting.value) return;

  submitting.value = true;
  error.value = '';
  try {
    const result = await verifyToken(value);
    if (!result.ok) {
      error.value = '登录令牌未通过后端校验';
      return;
    }
    auth.setToken(value);
    const redirect = route.query.redirect;
    router.push(typeof redirect === 'string' && redirect ? redirect : { name: 'home' });
  } catch {
    error.value = '登录令牌无效、已撤销或后端未启动';
  } finally {
    submitting.value = false;
  }
}
</script>

<template>
  <div class="login">
    <div class="login__grid" aria-hidden="true" />

    <GCard class="login__card" title="本地操作台">
      <template #head>
        <div class="login__brand">
          <span class="login__mark" aria-hidden="true">G</span>
          <div class="login__brand-text">
            <strong>GATE</strong>
            <small>本地 Git 闸门</small>
          </div>
        </div>
      </template>

      <p class="login__sub">输入启动日志打印的 GATE_WEB_TOKEN</p>
      <form class="login__form" @submit.prevent="submit">
        <GField label="人工令牌" for-id="login-token" hint="令牌只保存在当前浏览器，用于本地 API 鉴权。" required>
          <GInput
            id="login-token"
            v-model="token"
            name="gate-token"
            autocomplete="current-password"
            aria-label="GATE_WEB_TOKEN"
            type="password"
            placeholder="粘贴人工令牌"
            :aria-invalid="Boolean(error) || undefined"
            required
          />
        </GField>
        <p v-if="error" class="login__error" role="alert" aria-live="assertive">
          <GIcon name="shield" :size="13" />
          {{ error }}
        </p>
        <GButton variant="primary" type="submit" block class="login__btn" :loading="submitting">进入操作台</GButton>
      </form>
    </GCard>

    <p class="login__foot"><i aria-hidden="true" />本地实例 · 端到端受控</p>
  </div>
</template>

<style scoped>
.login {
  position: relative;
  min-height: 100vh;
  min-height: 100dvh;
  display: grid;
  place-items: center;
  padding: 32px 20px;
  overflow: hidden;
  color: var(--text);
  background: var(--background);
}

.login__grid {
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(to right, var(--border-subtle) 1px, transparent 1px),
    linear-gradient(to bottom, var(--border-subtle) 1px, transparent 1px);
  background-size: 32px 32px;
  mask-image: radial-gradient(circle at center, black 35%, transparent 75%);
  -webkit-mask-image: radial-gradient(circle at center, black 35%, transparent 75%);
  opacity: 0.65;
  pointer-events: none;
}

.login__card {
  position: relative;
  width: min(100%, 400px);
  border-radius: var(--radius-xl);
  box-shadow: var(--card-highlight), var(--shadow-popover);
}

.login__card :deep(.g-card__head) {
  padding-top: 16px;
  background: linear-gradient(180deg, var(--hover) 0%, transparent 100%);
}

.login__card :deep(.g-card__body) {
  padding: 22px;
}

.login__brand {
  display: flex;
  align-items: center;
  gap: 10px;
}

.login__mark {
  display: grid;
  width: 34px;
  height: 34px;
  place-items: center;
  flex: none;
  border: 1px solid var(--accent-border);
  border-radius: var(--radius-md);
  color: var(--accent-contrast);
  background: linear-gradient(145deg, var(--accent-hover), var(--accent));
  box-shadow: var(--shadow-brand);
  font-size: 16px;
  font-weight: 800;
  letter-spacing: -0.04em;
}

.login__brand-text {
  display: flex;
  flex-direction: column;
  gap: 2px;
  line-height: 1.1;
}

.login__brand-text strong {
  color: var(--text);
  font-size: 14.5px;
  font-weight: 750;
  letter-spacing: 0.05em;
}

.login__brand-text small {
  color: var(--text-muted);
  font-size: 9.5px;
  font-weight: 600;
  letter-spacing: 0.1em;
}

.login__sub {
  margin: 0 0 16px;
  color: var(--text-secondary);
  font-size: 12.5px;
}

.login__form {
  display: grid;
  gap: 4px;
}

.login__card :deep(.g-input) {
  min-height: 40px;
}

.login__error {
  display: flex;
  align-items: center;
  gap: 7px;
  margin: 10px 0 0;
  padding: 8px 10px;
  border: 1px solid var(--danger-border);
  border-radius: var(--radius-sm);
  color: var(--danger);
  background: var(--danger-soft);
  font-size: 12px;
  font-weight: 600;
}

.login__btn {
  margin-top: 16px;
  min-height: 40px;
  font-size: 13.5px;
}

.login__card :deep(.g-field__hint) {
  font-size: 11px;
}

.login__card :deep(.g-card__foot) {
  display: none;
}

.login__foot {
  position: fixed;
  bottom: 22px;
  display: flex;
  align-items: center;
  gap: 7px;
  color: var(--text-muted);
  font-size: 11.5px;
  letter-spacing: 0.02em;
}

.login__foot i {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--success);
  box-shadow: 0 0 0 3px var(--success-soft);
}

@media (max-width: 520px) {
  .login {
    padding: 20px 14px;
  }
  .login__card :deep(.g-card__body) {
    padding: 18px;
  }
}
</style>
