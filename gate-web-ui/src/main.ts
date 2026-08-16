import { createApp } from 'vue';
import { createPinia } from 'pinia';
import App from './App.vue';
import { router } from './router';
import './styles/global.css';
import { applyConsolePreferences } from './composables/useConsolePreferences';

applyConsolePreferences();
createApp(App).use(createPinia()).use(router).mount('#app');
