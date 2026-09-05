import { defineConfig } from "vite";
import { fileURLToPath } from "node:url";
import { pluginReactAliases } from "@gate/plugin-sdk/vite";

const r = (p: string) => fileURLToPath(new URL(p, import.meta.url));

/**
 * 插件构建：ESM 单入口产物（dist/index.js + dist/style.css）。
 * react 不打进产物——经 alias 桥接到 @gate/plugin-sdk 的宿主共享 shim，
 * 运行时从宿主注入的全局取同一个 React 实例（详见 README「共享 React」）。
 */
export default defineConfig({
  resolve: {
    alias: pluginReactAliases(),
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
