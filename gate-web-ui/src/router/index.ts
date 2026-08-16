/**
 * 路由表 — 对齐前端文档 §3.1 / x.md §5.
 *
 * 页面通过 API 边界读取后端数据, 需要本地 mock 时由测试层单独注入.
 * 守卫: 未登录跳 /login; /login 已登录则跳 /.
 */
import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router';
import { useAuthStore } from '@/stores/authStore';

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/LoginView.vue'),
    meta: { requiresAuth: false, public: true },
  },
  {
    path: '/',
    component: () => import('@/views/AppLayout.vue'),
    meta: { requiresAuth: true },
    children: [
      {
        path: '',
        name: 'home',
        component: () => import('@/views/HomeView.vue'),
        meta: { closedLoop: 'status' },
      },
      {
        path: 'styleguide',
        name: 'styleguide',
        component: () => import('@/views/StyleGuideView.vue'),
        meta: { closedLoop: 'layout' },
      },
      {
        path: 'projects/gate/tickets',
        name: 'kanban',
        component: () => import('@/views/TicketKanbanView.vue'),
        meta: { closedLoop: 'status' },
      },
      {
        path: 'projects/gate/tickets/:no',
        name: 'ticket-detail',
        component: () => import('@/views/TicketDetailView.vue'),
        meta: { closedLoop: 'presubmit+review' },
      },
      {
        path: 'projects/gate/tickets/:no/review',
        name: 'review',
        component: () => import('@/views/ReviewConsoleView.vue'),
        meta: { closedLoop: 'review' },
      },
      {
        path: 'projects/gate/tickets/:no/session',
        name: 'session',
        component: () => import('@/views/SessionView.vue'),
        meta: { closedLoop: 'session' },
      },
      {
        path: 'projects/gate/agents',
        name: 'agents',
        component: () => import('@/views/AgentConfigView.vue'),
        meta: { closedLoop: 'session' },
      },
      {
        path: 'projects/gate/cost',
        name: 'cost',
        component: () => import('@/views/CostPanelView.vue'),
        meta: { closedLoop: 'metrics' },
      },
      {
        path: 'settings',
        name: 'settings',
        component: () => import('@/views/SystemSettingsView.vue'),
        meta: { closedLoop: 'config' },
      },
    ],
  },
  { path: '/:pathMatch(.*)*', redirect: '/' },
];

export const router = createRouter({
  history: createWebHistory(),
  routes,
});

router.beforeEach((to) => {
  const auth = useAuthStore();
  if (to.meta.requiresAuth !== false && !auth.isAuthenticated) {
    return { name: 'login', query: { redirect: to.fullPath } };
  }
  if (to.name === 'login' && auth.isAuthenticated) {
    return { name: 'home' };
  }
  return true;
});
