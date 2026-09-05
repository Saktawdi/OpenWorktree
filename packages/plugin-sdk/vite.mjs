/**
 * @gate/plugin-sdk/vite —— 插件 vite.config 用的共享 alias 助手。
 * shim 路径以本模块自身位置（import.meta.url）解析：无论 file: 依赖被 npm
 * 以 symlink 还是 copy 方式落进 node_modules，都指向正确的 SDK 内文件。
 * 插件工程不得在配置里手写相对路径。
 */
import { fileURLToPath } from "node:url";

const shim = (name) => fileURLToPath(new URL(`./src/${name}`, import.meta.url));

/**
 * 返回把 "react" / "react/jsx-runtime" 桥接到宿主共享 React 的 alias 数组。
 * 顺序敏感：jsx-runtime 必须在前（"react" 前缀规则会一并命中 "react/jsx-runtime"）。
 */
export function pluginReactAliases() {
  return [
    { find: "react/jsx-runtime", replacement: shim("jsx-runtime.ts") },
    { find: "react", replacement: shim("react.ts") },
  ];
}
