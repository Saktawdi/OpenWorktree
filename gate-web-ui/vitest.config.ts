import { defineConfig, mergeConfig } from 'vitest/config';
import viteConfig from './vite.config';

export default mergeConfig(
  viteConfig,
  defineConfig({
    test: {
      globals: true,
      environment: 'jsdom',
      include: ['tests/**/*.{test,spec}.{ts,tsx}'],
      // naive-ui 全量 ESM 在 vitest 冷缓存下首次 transform 需 ~11s (实测),
      // 默认 5s 会误杀涉及 lazy import (HomeView → naive-ui) 的用例, 故放宽.
      testTimeout: 30_000,
      hookTimeout: 30_000,
      // Mock-first: MSW 在测试里不起作用 (msw 走 worker), 这里直接用 vitest 拦截 axios.
      // 真集成在浏览器 dev 时才用 MSW.
    },
  }),
);
