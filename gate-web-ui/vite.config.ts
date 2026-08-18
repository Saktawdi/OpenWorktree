import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import { fileURLToPath, URL } from 'node:url';

// Dev proxy: /api → 真后端 127.0.0.1:4097; /api/**/events (SSE) → SSE target.
// Real-backend-first: MSW 默认关闭 (设 VITE_ENABLE_MSW=true 才启用浏览器侧 mock);
// SSE 默认指向 mock SSE server 127.0.0.1:4098, 真后端联调时设 VITE_SSE_BACKEND_URL 指真后端.
const backendTarget = process.env.VITE_BACKEND_URL ?? 'http://127.0.0.1:4097';
const sseTarget = process.env.VITE_SSE_BACKEND_URL ?? 'http://127.0.0.1:4098';

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    host: '127.0.0.1',
    port: 5173,
    strictPort: true,
    proxy: {
      // SSE 端点 (含 /events 后缀) 转发到 SSE target (默认 mock SSE server; 真后端联调时指 4097).
      // useSSE 会把 token 拼成 ?token=... 挂到 URL 上; Vite 用 RegExp 匹配含 query 的 req.url,
      // 故正则必须容忍尾部 query, 否则 SSE 请求会落到 /api 规则 (实证: ECONNREFUSED 127.0.0.1:4097).
      '^/api/.*/events(\\?.*)?$': {
        target: sseTarget,
        changeOrigin: false,
        ws: false,
      },
      // 普通 /api → 真后端 (MSW 在浏览器侧拦截).
      '/api': {
        target: backendTarget,
        changeOrigin: false,
      },
    },
  },
  build: {
    // 产物供后端 gate-web 静态托管 (gate-web/src/main/resources/static).
    outDir: '../gate-web/src/main/resources/static',
    emptyOutDir: true,
    sourcemap: false,
    // Monaco 拆 chunk (S2 才引入, 这里预留).
    chunkSizeWarningLimit: 1500,
    rollupOptions: {
      output: {
        manualChunks: {
          // 预留: 'monaco': ['monaco-editor'],
        },
      },
    },
  },
});
