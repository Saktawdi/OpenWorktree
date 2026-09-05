/**
 * 插件系统（app/plugins）：与插件共享的宿主运行时单例。
 *
 * 插件工程构建时由 vite alias 把 "react"/"react/jsx-runtime" 指向模板内的桥接 shim，
 * shim 运行时从全局 __GATE_PLUGIN_SHARED__ 取这里的实例——插件组件与宿主共用同一个
 * React，才能安全挂进宿主树（hooks 可用、不会出现双 React 实例的 Invalid hook call）。
 * host.ts import 本文件（副作用先于任何插件加载执行）。
 */
import * as React from "react";
import * as ReactJsxRuntime from "react/jsx-runtime";

(globalThis as unknown as Record<string, unknown>).__GATE_PLUGIN_SHARED__ = {
  React,
  jsxRuntime: ReactJsxRuntime,
};
