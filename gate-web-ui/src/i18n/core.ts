/**
 * i18n 核心（i18n core）：翻译函数与回退链，不含任何 React/状态依赖。
 *
 * 设计要点：
 * - 键的唯一真源是默认语言字典（locales/zh-CN.ts，`as const` 导出 MsgKey 类型），
 *   其余语言字典以 `Record<MsgKey, string>` 收口，缺键在编译期报错；
 * - 回退链：当前语言 → 默认语言（zh-CN）→ 键名本身。运行期缺失的键
 *   （如后端下发的动态文案）回退到默认语言而不是显示键名；
 * - 插值占位符用 {name} 形式，t("key", { name: value }) 填充。
 */
import type { Dict, LocaleId } from "./locales";
import { DEFAULT_LOCALE, dictFor } from "./locales";

/** 占位符：{name}，名称限字母/数字/下划线。 */
const PARAM_RE = /\{(\w+)\}/g;

export type TParams = Record<string, string | number>;

/** 翻译函数签名：key 为 MsgKey（由 zh-CN 字典推导），params 可选。 */
export type TFunc = (key: string, params?: TParams) => string;

/** 用占位符填充模板；未知占位符原样保留，便于发现文案问题。 */
function interpolate(template: string, params: TParams | undefined): string {
  if (!params) return template;
  return template.replace(PARAM_RE, (raw, name: string) => {
    const v = params[name];
    return v === undefined ? raw : String(v);
  });
}

/**
 * 构造绑定某个语言的翻译函数。
 * - dict 缺键 → 默认语言兜底；
 * - 默认语言也缺 → 返回键名（调用方可感知，避免渲染空白）。
 */
export function makeT(locale: LocaleId): TFunc {
  const dict = dictFor(locale).dict as Record<string, string | undefined>;
  const fallback = dictFor(DEFAULT_LOCALE).dict as Record<string, string | undefined>;
  return (key: string, params?: TParams) => {
    const template = dict[key] ?? fallback[key];
    if (template === undefined) return key;
    return interpolate(template, params);
  };
}
