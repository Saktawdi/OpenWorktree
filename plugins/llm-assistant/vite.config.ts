import { defineConfig } from "vite";
import { fileURLToPath } from "node:url";
import { pluginReactAliases } from "@gate/plugin-sdk/vite";

const r = (p: string) => fileURLToPath(new URL(p, import.meta.url));

export default defineConfig({
  resolve: {
    alias: pluginReactAliases(),
  },
  define: {
    "process.env.NODE_ENV": JSON.stringify(process.env.NODE_ENV ?? "production"),
  },
  build: {
    lib: {
      entry: r("./src/index.tsx"),
      name: "gate-plugin-llm-assistant",
      formats: ["es"],
      fileName: () => "index.js",
    },
    cssCodeSplit: false,
    outDir: "dist",
    rollupOptions: {
      output: {
        assetFileNames: () => "style.css",
      },
    },
  },
});
