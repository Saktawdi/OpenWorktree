/**
 * 插件系统（app/plugins）：快捷 chip 图标白名单。
 * 插件只能引用这里登记的 Phosphor 图标名（icon 字符串）——chip 由宿主渲染，
 * 不把宿主的组件模块直接交给插件代码。
 */
import {
  Brain,
  Bug,
  ChatText,
  CheckCircle,
  Clock,
  Eye,
  GearSix,
  Lightning,
  LockKey,
  MagnifyingGlass,
  PaperPlaneRight,
  PlugsConnected,
  Rocket,
  ShieldCheck,
  Sparkle,
  TerminalWindow,
  Wrench,
} from "@phosphor-icons/react";
import type { Icon } from "@phosphor-icons/react";

export const PLUGIN_ICONS: Record<string, Icon> = {
  Brain,
  Bug,
  ChatText,
  CheckCircle,
  Clock,
  Eye,
  GearSix,
  Lightning,
  LockKey,
  MagnifyingGlass,
  PaperPlaneRight,
  PlugsConnected,
  Rocket,
  ShieldCheck,
  Sparkle,
  TerminalWindow,
  Wrench,
};

/** 白名单内按名取图标；未知或缺省回落到对话图标。 */
export function pluginIcon(name?: string): Icon {
  return (name ? PLUGIN_ICONS[name] : undefined) ?? ChatText;
}
