<script setup lang="ts">
import { ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useAuthStore } from '@/stores/authStore';
import { GButton, GInput, GCard } from '@/components/ui';

const auth = useAuthStore();
const router = useRouter();
const route = useRoute();
const token = ref('');
const error = ref('');

function submit() {
  const value = token.value.trim();
  if (!value) {
    error.value = '请输入 token';
    return;
  }
  if (value.length < 6) {
    error.value = 'token 无效或已撤销';
    return;
  }
  auth.setToken(value);
  const redirect = route.query.redirect;
  router.push(typeof redirect === 'string' && redirect ? redirect : { name: 'home' });
}
</script>

<template>
  <div class="login">
    <GCard class="login__card" title="GATE 操作台">
      <p class="login__sub">输入启动日志打印的 GATE_WEB_TOKEN</p>
      <GInput
        v-model="token"
        aria-label="GATE_WEB_TOKEN"
        type="password"
        placeholder="粘贴 HUMAN token"
        @keydown="(e: KeyboardEvent) => e.key === 'Enter' && submit()"
      />
      <p v-if="error" class="login__error" role="alert" aria-live="assertive">{{ error }}</p>
      <GButton variant="primary" block class="login__btn" @click="submit">进入操作台</GButton>
    </GCard>
  </div>
</template>

<style scoped>
.login {
  min-height: 100vh;
  min-height: 100dvh;
  display: grid;
  place-items: center;
  padding: 32px 20px;
  color: var(--text, #172a46);
  background:
    radial-gradient(560px 340px at 78% 8%, rgba(176, 75, 28, 0.12), transparent 66%),
    radial-gradient(500px 300px at 12% 92%, rgba(231, 124, 67, 0.1), transparent 70%),
    var(--background, #f3efe8);
}
.login__card {
  width: min(100%, 420px);
  border-radius: 18px;
  box-shadow: 0 18px 50px rgba(35, 53, 76, 0.11);
}
.login__card::before {
  content: 'GATE  /  LOCAL GIT GATE';
  display: block;
  padding: 18px 22px 0;
  color: var(--text-muted, #718092);
  font-family: var(--font-mono, ui-monospace, monospace);
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.1em;
}
.login__card :deep(.g-card__head) {
  padding-top: 14px;
}
.login__card :deep(.g-card__body) {
  padding: 22px;
}
.login__sub {
  color: var(--text-secondary, #405064);
  font-size: 13px;
  margin: 0 0 18px;
}
.login__card :deep(.g-input) {
  min-height: 42px;
  border-radius: 10px;
}
.login__error {
  color: var(--danger, #c2534a);
  font-size: 13px;
  margin: 9px 0 0;
  padding: 8px 10px;
  background: var(--danger-soft, rgba(194, 83, 74, 0.1));
  border-radius: 8px;
}
.login__btn {
  margin-top: 18px;
  min-height: 42px;
  border-radius: 10px;
}
.login__card :deep(.g-card__body)::after {
  content: '令牌只保存在当前浏览器，用于本地 API 鉴权。';
  display: block;
  margin-top: 14px;
  color: var(--text-muted, #718092);
  font-size: 11px;
  line-height: 1.55;
  text-align: center;
}
.login__card :deep(.g-card__foot) {
  display: none;
}
.login::after {
  content: '●  本地连接 · 端到端受控';
  position: fixed;
  bottom: 22px;
  color: var(--text-muted, #718092);
  font-size: 11px;
  letter-spacing: 0.02em;
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
