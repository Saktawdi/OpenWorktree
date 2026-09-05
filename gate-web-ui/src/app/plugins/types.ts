/**
 * 插件系统（app/plugins）：宿主与插件之间的类型契约。
 * plugin-template 的 src/host-types.ts 是本文件的手工镜像——修改这里必须同步模板（文件头有注明）。
 */
import type { ReactNode } from "react";

/** 卸载回调：宿主在停用插件时按后注册先执行的顺序调用。 */
export type Disposable = () => void;

/** 后端 manifest（/api/plugins 下发，字段与 gate-web PluginManifest 一一对应）。 */
export interface PluginManifest {
  id: string;
  name: string;
  version: string;
  apiVersion: string;
  entry: string;
  css: string | null;
  description: string | null;
  permissions: string[];
}

/** 目录同步得到的一条插件（含启停状态与资产指纹）。 */
export interface PluginListItem extends PluginManifest {
  enabled: boolean;
  cacheTag: string;
}

/** composer 快捷动作可见性判定的工单上下文快照。 */
export interface ChatInputState {
  ticketNo: string;
  mode: "demo" | "live";
  busy: boolean;
  terminal: boolean;
  stage: string;
  diffs: number;
  findingsCount: number;
  restartCount: number;
}

/** 宿主开放给快捷动作的能力面（与 app/actions 对齐的最小集，不暴露整只 facade）。 */
export interface ChatActionApi {
  insertText(text: string): void;
  sendPrompt(text: string): void;
  presubmit(): void;
  returnWithFindings(): void;
  toast(text: string): void;
}

/** 对话输入区上方的快捷 chip（原 Composer 硬编码 quick 语录的插件化形态）。 */
export interface ChatInputActionContribution {
  id: string;
  label: string;
  /** 宿主图标白名单内的 Phosphor 图标名（app/plugins/icons.tsx）；缺省 ChatTextDots。 */
  icon?: string;
  /** 依据工单上下文决定显隐；缺省恒显示。抛异常按隐藏处理（坏插件不污染输入区）。 */
  when?(state: ChatInputState): boolean;
  run(api: ChatActionApi, state: ChatInputState): void;
}

/** 渲染进宿主 React 树的面板挂件（设置中心「插件」分区展示）。 */
export interface PanelWidgetContribution {
  id: string;
  title?: string;
  /** 与宿主共享同一 React 实例（shared.ts 全局注入 + 模板 shim 桥接），可用 hooks。 */
  render(): ReactNode;
}

/** 插件 KV 存储（manifest 声明 kv 权限时宿主才注入；数据落 <gateHome>/plugins-data/<id>/）。 */
export interface PluginKv {
  get<T>(key: string): Promise<T | null>;
  set(key: string, value: unknown): Promise<void>;
  del(key: string): Promise<void>;
}

export interface HostFetchRequest {
  method?: "GET" | "POST" | "PUT" | "DELETE";
  body?: unknown;
}

export interface PluginContext {
  readonly pluginId: string;
  readonly manifest: PluginManifest;
  registerChatInputAction(contribution: ChatInputActionContribution): Disposable;
  registerPanelWidget(widget: PanelWidgetContribution): Disposable;
  readonly kv: PluginKv | null;
  /** 注入 Web Token 的同源 /api/ 请求（manifest 声明 net 权限时可用）。 */
  hostFetch<T>(path: string, init?: HostFetchRequest): Promise<T>;
  /** 登记停用回调：宿主 disable/reload 时与注册返回的 Disposable 一起逆序执行。 */
  onDeactivate(fn: Disposable): void;
  log(...args: unknown[]): void;
}

/** 插件入口模块：导出 activate（命名或 default 均可），返回的清理函数会被宿主调用。 */
export interface PluginEntryModule {
  activate(ctx: PluginContext): Disposable | void;
}
