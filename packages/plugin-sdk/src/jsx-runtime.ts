/**
 * 宿主共享 jsx-runtime 桥接 shim —— 插件构建期由 vite alias 把 "react/jsx-runtime" 指向本文件
 * （jsx: "react-jsx" 编译出的 JSX 调用走这里）。
 *
 * !! 本文件属于 SDK，正常开发不需要改动 !!
 */
const shared = (
  globalThis as unknown as {
    __GATE_PLUGIN_SHARED__?: { jsxRuntime: typeof import("react/jsx-runtime") };
  }
).__GATE_PLUGIN_SHARED__;

const j = shared?.jsxRuntime;
if (!j) {
  throw new Error("宿主未注入 __GATE_PLUGIN_SHARED__.jsxRuntime：插件必须由工作台宿主加载");
}

export const jsx = j.jsx;
export const jsxs = j.jsxs;
export const Fragment = j.Fragment;
