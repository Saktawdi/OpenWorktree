/**
 * 宿主共享 React 桥接 shim —— 构建期由 vite alias 把 "react" 指向本文件。
 * 运行时从全局 __GATE_PLUGIN_SHARED__ 取宿主的 React 实例：
 * 插件组件与宿主共用同一个 React，hooks 才能安全工作（双 React 实例会 Invalid hook call）。
 *
 * !! 本文件属于模板 SDK，正常开发不需要改动 !!
 */
const shared = (
  globalThis as unknown as {
    __GATE_PLUGIN_SHARED__?: { React: typeof import("react") };
  }
).__GATE_PLUGIN_SHARED__;

const R = shared?.React;
if (!R) {
  throw new Error(
    "宿主未注入 __GATE_PLUGIN_SHARED__.React：插件必须由工作台宿主加载（复制到 gate-home/plugins/ 并在前端加载），不能独立打开",
  );
}

export default R;

export const useState = R.useState;
export const useEffect = R.useEffect;
export const useLayoutEffect = R.useLayoutEffect;
export const useMemo = R.useMemo;
export const useRef = R.useRef;
export const useCallback = R.useCallback;
export const useReducer = R.useReducer;
export const useContext = R.useContext;
export const createContext = R.createContext;
export const createElement = R.createElement;
export const cloneElement = R.cloneElement;
export const Fragment = R.Fragment;
export const memo = R.memo;
export const forwardRef = R.forwardRef;
export const useId = R.useId;
export const useImperativeHandle = R.useImperativeHandle;
export const useSyncExternalStore = R.useSyncExternalStore;
export const useTransition = R.useTransition;
export const useDeferredValue = R.useDeferredValue;
export const startTransition = R.startTransition;
export const isValidElement = R.isValidElement;
export const Children = R.Children;
export const StrictMode = R.StrictMode;
