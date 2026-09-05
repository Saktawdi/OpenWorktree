import { defineConfig } from "vite";
import { fileURLToPath } from "node:url";

const r = (p: string) => fileURLToPath(new URL(p, import.meta.url));

/**
 * 插件构建：ESM 单入口产物（dist/index.js + dist/style.css）。
 * react 不打进产物——经 alias 桥接到 src/host/react.ts shim，
 * 运行时从宿主注入的全局取同一个 React 实例（详见 README「共享 React」）。
 */
export default defineConfig({
  resolve: {
    alias: [
      // 顺序敏感："react" 前缀规则会一并命中 "react/jsx-runtime"，jsx-runtime 必须在前
      { find: "react/jsx-runtime", replacement: r("./src/host/jsx-runtime.ts") },
      { find: "react", replacement: r("./src/host/react.ts") },
    ],
  },
  build: {
    lib: {
      entry: r("./src/index.tsx"),
      name: "gate-plugin",
      formats: ["es"],
      fileName: () => "index.js",
    },
    cssCodeSplit: false,
    outDir: "dist",
    rollupOptions: {
      output: {
        // 单一 css 产物：manifest.json 的 css 字段指向它，宿主按插件粒度注入 <link>
        assetFileNames: () => "style.css",
      },
    },
  },
});
