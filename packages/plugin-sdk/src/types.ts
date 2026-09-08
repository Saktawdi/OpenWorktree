/**
 * @gate/plugin-sdk：宿主与插件之间的类型契约（唯一事实来源）。
 *
 * 宿主（gate-web-ui/src/app/plugins/types.ts）re-export 本文件；插件工程直接
 * `import type { ... } from "@gate/plugin-sdk"`，不再各自维护 host-types.ts 镜像。
 *
 * 冻结语义：已发布字段（含 apiVersion="1" 下的全部形状）不可改名/删除/收窄；
 * 演进只允许增量（新增可选字段、新增 ctx 方法）。apiVersion 由 SDK 的
 * SUPPORTED_API_VERSION 常量与后端 PluginManifest.SUPPORTED_API_VERSION 各持一份，
 * 升级代次时两处 + CHANGELOG 必须同步。
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

/** 对话输入区上方的快捷 chip 贡献点。 */
export interface ChatInputActionContribution {
  id: string;
  label: string;
  /** 宿主图标白名单内的 Phosphor 图标名（宿主 icons.tsx）；缺省 ChatTextDots。 */
  icon?: string;
  /** 依据工单上下文决定显隐；缺省恒显示。抛异常按隐藏处理（坏插件不污染输入区）。 */
  when?(state: ChatInputState): boolean;
  run(api: ChatActionApi, state: ChatInputState): void;
}

/** 宿主开放给划选菜单动作的能力面（apiVersion=1 增量能力）。 */
export interface SelectionActionApi {
  /** 把文本作为引用胶囊加进当前工单对话框（与内置「添加到对话框」同一落点）。 */
  addToComposer(text: string): void;
  /** 在当前工单输入框光标处插入文本（无输入框时静默忽略）。 */
  insertText(text: string): void;
  toast(text: string): void;
}

/** 划选文字弹出菜单的动作贡献点（selection.menu 区域，如「添加到 xxx」）。 */
export interface SelectionActionContribution {
  id: string;
  label: string;
  /** 宿主图标白名单内的 Phosphor 图标名（宿主 icons.tsx）；缺省 PlusCircle。 */
  icon?: string;
  /**
   * 依据划选文本决定显隐；缺省恒显示。抛异常按隐藏处理（坏插件不污染菜单）。
   * state.text 为划选的原始文本（首尾空白已保留原样，仅用于判断，入参即全文）。
   */
  when?(state: { text: string }): boolean;
  /** text 为划选的原始文本。 */
  run(api: SelectionActionApi, text: string): void;
}

/** 渲染进宿主 React 树的面板挂件（设置中心「插件」分区展示）。 */
export interface PanelWidgetContribution {
  id: string;
  title?: string;
  /** 与宿主共享同一 React 实例（SDK shim 桥接），可用 hooks。 */
  render(): ReactNode;
}

/**
 * 插件贡献的整页视图（顶栏导航注册表，nav.pages 区域）。
 * 导航原生项在前，插件页按 order 升序追加（缺省 100）；页面体惰性挂载，
 * 插件被禁用/重载时宿主关闭打开中的该页（优雅降级，不崩主应用）。
 */
export interface PageContribution {
  /** 插件内唯一 id（导航高亮与打开态的键）。 */
  id: string;
  title: string;
  /** 宿主图标白名单内的 Phosphor 图标名；缺省宿主默认图标。 */
  icon?: string;
  /** 排序权重，小者在前；缺省 100。 */
  order?: number;
  /** 页面体（与宿主共享 React 实例，可用 hooks；宿主全局样式类可直接用）。 */
  render(): ReactNode;
}

/** 顶栏右上角胶囊动作贡献点（header.actions 区域）。 */
export interface HeaderActionContribution {
  id: string;
  order?: number;
  /** 渲染胶囊按钮等挂件内容（与宿主共享同一 React 实例）。 */
  render(): ReactNode;
}

/** 全局悬浮挂件贡献点（floating.widgets 区域，如悬浮可拖拽小助手面板）。 */
export interface FloatingWidgetContribution {
  id: string;
  /** 渲染悬浮挂件内容（全视口自由浮动挂载，支持 hooks）。 */
  render(): ReactNode;
}

/** LLM 单次/流式对话请求参数。 */
export interface LlmChatMessage {
  role: "system" | "user" | "assistant";
  content: string;
}

export interface LlmChatOptions {
  messages: LlmChatMessage[];
  providerId?: string;
  model?: string;
  temperature?: number;
  maxTokens?: number;
}

export interface LlmChatResponse {
  id?: string;
  choices: Array<{
    message: {
      role: string;
      content: string;
    };
    finish_reason?: string;
  }>;
}

export interface PluginLlmApi {
  /** 单次等待完整回复（manifest 声明 llm 权限时可用）。 */
  chat(options: LlmChatOptions): Promise<LlmChatResponse>;
  /** 流式对话（manifest 声明 llm 权限时可用；每收到增量内容触发 onChunk 回调，最终返回合并后的全文）。 */
  chatStream(options: LlmChatOptions, onChunk: (chunk: string) => void): Promise<string>;
}

/** 插件前端 Storage 存储（manifest 声明 storage 权限时宿主才注入；基于 localStorage 并在 key 前拼接插件前缀隔离）。 */
export interface PluginStorage {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
  removeItem(key: string): void;
  clear(): void;
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

/**
 * 插件事件总线的事件形状（apiVersion=1 增量能力）。
 * 语义：no replay——事件在语义写入点显式 emit，订阅晚于 emit 即错过，宿主不重放、
 * 重连不去重；插件要做"补拉现状"自行用 hostFetch。
 */
export interface PluginEventMap {
  /** 工单阶段迁移（from→to 语义由写入点保证，不做快照 diff 还原）。 */
  "ticket.stage-changed": { ticketNo: string; from: string; to: string };
  /** 会话创建（demo 与 live 路径各在其创建成功点 emit）。 */
  "session.created": { ticketNo: string; sessionId: string; agentConfigId: string | null };
  /** 会话回合结束（done=正常完成；failed=出错/中止）。 */
  "session.ended": { ticketNo: string; sessionId: string | null; kind: "done" | "failed" };
  /** 插件生命周期（宿主自身 emit，供插件生态感知）。 */
  "plugin.activated": { pluginId: string };
  "plugin.deactivated": { pluginId: string };
  "plugin.error": { pluginId: string; error: string };
}

/** "*" 通配订阅收到的信封。 */
export type PluginEventEnvelope = {
  [K in keyof PluginEventMap]: { type: K; payload: PluginEventMap[K] };
}[keyof PluginEventMap];

export interface PluginContext {
  readonly pluginId: string;
  readonly manifest: PluginManifest;
  registerChatInputAction(contribution: ChatInputActionContribution): Disposable;
  registerPanelWidget(widget: PanelWidgetContribution): Disposable;
  /** 注册整页视图：顶栏导航追加入口 + 全页渲染（apiVersion=1 增量能力）。 */
  registerPage(page: PageContribution): Disposable;
  /** 注册划选文字弹出菜单的动作（selection.menu 区域，如「添加到 xxx」；apiVersion=1 增量能力）。 */
  registerSelectionAction(action: SelectionActionContribution): Disposable;
  /** 注册顶栏右上角胶囊动作（header.actions 区域）。 */
  registerHeaderAction(action: HeaderActionContribution): Disposable;
  /** 注册全局悬浮挂件（floating.widgets 区域）。 */
  registerFloatingWidget(widget: FloatingWidgetContribution): Disposable;
  readonly kv: PluginKv | null;
  /** 前端 Storage 存储（manifest 声明 storage 权限时宿主才注入；按插件 ID 隔离）。 */
  readonly storage: PluginStorage | null;
  /** LLM 对话能力（manifest 声明 llm 权限时宿主才注入）。 */
  readonly llm: PluginLlmApi | null;
  /** 注入 Web Token 的同源 /api/ 请求（manifest 声明 net 权限时可用）。 */
  hostFetch<T>(path: string, init?: HostFetchRequest): Promise<T>;
  /** 登记停用回调：宿主 disable/reload 时与注册返回的 Disposable 一起逆序执行。 */
  onDeactivate(fn: Disposable): void;
  /**
   * 订阅宿主事件（fan-out 总线；no replay，见 PluginEventMap 注释）。
   * 返回的 Disposable 忘记调用也没关系——宿主在停用插件时会强制注销。
   */
  on(event: "*", handler: (envelope: PluginEventEnvelope) => void): Disposable;
  on<K extends keyof PluginEventMap>(event: K, handler: (payload: PluginEventMap[K]) => void): Disposable;
  log(...args: unknown[]): void;
}

/** 插件入口模块：导出 activate（命名或 default 均可），返回的清理函数会被宿主调用。 */
export interface PluginEntryModule {
  activate(ctx: PluginContext): Disposable | void;
}
