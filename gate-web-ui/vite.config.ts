import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";
import { fileURLToPath } from "node:url";

const backend = process.env.VITE_BACKEND_URL || "http://127.0.0.1:19090";

export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: {
      "@": fileURLToPath(new URL("./src", import.meta.url)),
    },
  },
  server: {
    host: "127.0.0.1",
    port: 5173,
    proxy: {
      "/api": {
        target: backend,
        changeOrigin: true,
      },
      // 插件资产（ES import 无法带 Authorization 头，走免鉴权资产端点）
      "/plugins": {
        target: backend,
        changeOrigin: true,
      },
      "/ws": {
        target: backend,
        ws: true,
      },
    },
  },
});
