import { createApp } from 'vue';
import { createPinia } from 'pinia';
import App from './App.vue';
import { router } from './router';
import { setRouterGetter } from './api/client';
import './styles/global.css';
import { applyConsolePreferences } from './composables/useConsolePreferences';

async function bootstrap() {
  // 真实后端为默认；仅 UI 原型开发时显式设置 VITE_ENABLE_MSW=true 开启 mock。
  if (import.meta.env.DEV && import.meta.env.VITE_ENABLE_MSW === 'true') {
    const { worker } = await import('../mocks/browser');
    await worker.start({ onUnhandledRequest: 'bypass' });
  }

  setRouterGetter(() => router);
  applyConsolePreferences();
  createApp(App).use(createPinia()).use(router).mount('#app');
}

void bootstrap();
