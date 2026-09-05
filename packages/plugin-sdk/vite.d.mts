export interface ViteAliasObject {
  find: string | RegExp;
  replacement: string;
  customResolver?: unknown;
}

/** 把 "react" / "react/jsx-runtime" 桥接到宿主共享 React shim 的 alias 数组（jsx-runtime 在前）。 */
export declare function pluginReactAliases(): ViteAliasObject[];
