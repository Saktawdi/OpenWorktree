/**
 * 宿主契约的类型镜像 —— 与 gate-web-ui/src/app/plugins/types.ts 手工保持一致。
 * 改宿主契约时需同步这里（纯类型，构建后被擦除，不进产物）。
 */
import type { ReactNode } from "react";

export type Disposable = () => void;

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

export interface ChatActionApi {
  insertText(text: string): void;
  sendPrompt(text: string): void;
  presubmit(): void;
  returnWithFindings(): void;
  toast(text: string): void;
}

export interface ChatInputActionContribution {
  id: string;
  label: string;
  /** 宿主图标白名单内的 Phosphor 名：Brain/Bug/ChatText/CheckCircle/Clock/Eye/GearSix/
   *  Lightning/LockKey/MagnifyingGlass/PaperPlaneRight/PlugsConnected/Rocket/ShieldCheck/
   *  Sparkle/TerminalWindow/Wrench；缺省 ChatText。 */
  icon?: string;
  when?(state: ChatInputState): boolean;
  run(api: ChatActionApi, state: ChatInputState): void;
}

export interface PanelWidgetContribution {
  id: string;
  title?: string;
  render(): ReactNode;
}

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
  readonly manifest: { id: string; name: string; version: string; description: string | null; permissions: string[] };
  registerChatInputAction(contribution: ChatInputActionContribution): Disposable;
  registerPanelWidget(widget: PanelWidgetContribution): Disposable;
  readonly kv: PluginKv | null;
  hostFetch<T>(path: string, init?: HostFetchRequest): Promise<T>;
  onDeactivate(fn: Disposable): void;
  log(...args: unknown[]): void;
}

export interface PluginEntry {
  activate(ctx: PluginContext): Disposable | void;
}
