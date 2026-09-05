/**
 * @gate/plugin-sdk 公共入口：契约类型 + API 代次常量。
 * React 桥接 shim（src/react.ts、src/jsx-runtime.ts）不由插件直接 import——
 * 由 vite alias（"@gate/plugin-sdk/vite" 助手）把 "react" 指到 shim。
 */
export * from "./types";

/**
 * 宿主当前实现的插件 API 代次。
 * 与后端 gate.web.plugin.PluginManifest#SUPPORTED_API_VERSION 保持一致
 * （跨语言各持一份，升级代次时两处 + CHANGELOG 必须同步）。
 */
export const SUPPORTED_API_VERSION = "1";
