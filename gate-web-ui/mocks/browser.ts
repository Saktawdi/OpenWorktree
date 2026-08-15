/**
 * MSW browser worker — dev 模式由 main.ts 异步加载.
 *
 * `pnpm msw init public/ --save` 已生成 /mockServiceWorker.js (见 README).
 */
import { setupWorker } from 'msw/browser';
import { handlers } from './handlers';

export const worker = setupWorker(...handlers);
