/**
 * 插件系统（app/plugins）：区域插槽定义（插槽总线的锚点）。
 *
 * 宿主每声明一个命名渲染区，只需：
 *   1. 在 SlotContributionMap 里登记「区域名 → 贡献物契约」；
 *   2. 在对应 feature 组件焊一行挂载点（渲染型用 <PluginSlot name>，动作型用其区域 wrapper）。
 * 之后新增该区域的插件贡献零改 feature 组件。
 *
 * 两类区域形态：
 *   - 渲染型：contribution.render() 返回 ReactNode，由通用 PluginSlot 按注册顺序渲染并逐个包
 *     PluginBoundary（如 settings.plugins、nav.pages 的页面体）；
 *   - 动作型：贡献物不渲染，由宿主区域 wrapper 读取注册表后执行（when/run，如 composer.chips
 *     的 ChatActionChips——折叠/整行隐藏等 region 行为留在 wrapper，不进 PluginSlot）。
 *
 * 红线（可 grep 验证）：本目录（app/plugins/**）不得 import 任何 @/features/*——
 * 插件层对业务域只暴露快照与能力 api，不反向依赖 feature 实现。
 */
import type {
  ChatInputActionContribution,
  PageContribution,
  PanelWidgetContribution,
} from "./types";

/** 区域名 → 该区域贡献物的契约类型。新增区域在此登记。 */
export interface SlotContributionMap {
  /** 对话输入区上方的插件快捷 chip（原生 chip 不在此列，归 Composer 域自管）。 */
  "composer.chips": ChatInputActionContribution;
  /** 设置中心「插件」分区的面板挂件。 */
  "settings.plugins": PanelWidgetContribution;
  /** 顶栏导航的插件整页（页面体惰性挂载，禁用/重载时优雅关闭）。 */
  "nav.pages": PageContribution;
}

export type SlotName = keyof SlotContributionMap & string;

/** 渲染型区域子集（贡献物带 render()）——通用 PluginSlot 只服务这些区域。 */
export type RenderSlotName = {
  [S in SlotName]: SlotContributionMap[S] extends { render(): unknown } ? S : never;
}[SlotName];

export const SLOT_COMPOSER_CHIPS = "composer.chips" as const;
export const SLOT_SETTINGS_PLUGINS = "settings.plugins" as const;
export const SLOT_NAV_PAGES = "nav.pages" as const;

/** nav.pages 的排序：order 升序，缺省 100；同 order 按注册顺序稳定。 */
export const PAGE_ORDER_DEFAULT = 100;
