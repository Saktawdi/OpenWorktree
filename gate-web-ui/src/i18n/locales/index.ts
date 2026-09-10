/**
 * 语言注册表：受支持语言、默认语言与字典装载。
 * 新增语言三步：新建 locales/<id>/ 域字典 → 在 LOCALES 登记 → PrefsBlock 的语言列表自动扩展。
 */
import type { MsgKey } from "./zh-CN";
import { zhCN } from "./zh-CN";
import { en } from "./en";

/** 受支持的语言 id（与 localStorage 落盘值一致）。 */
export type LocaleId = "zh-CN" | "en";

export interface LocaleMeta {
  /** 语言 id（BCP-47）。 */
  id: LocaleId;
  /** 语言自称（语言分区里展示给用户的名字，始终用该语言书写）。 */
  label: string;
}

/** 全部受支持语言；首项为默认。 */
export const LOCALES: LocaleMeta[] = [
  { id: "zh-CN", label: "简体中文" },
  { id: "en", label: "English" },
];

export const DEFAULT_LOCALE: LocaleId = "zh-CN";

/** 字典形状：允许缺键（回退链兜底），禁止多余键。 */
export type Dict = Partial<Record<MsgKey, string>>;

const DICTS: Record<LocaleId, Dict> = {
  "zh-CN": zhCN,
  en,
};

/** 取某语言字典；未知语言返回默认语言（防 localStorage 手改脏值）。 */
export function dictFor(locale: LocaleId): { dict: Dict } {
  return { dict: DICTS[locale] ?? DICTS[DEFAULT_LOCALE] };
}

/** 语言 id 是否受支持（恢复持久化值时校验）。 */
export function isLocaleId(v: unknown): v is LocaleId {
  return typeof v === "string" && LOCALES.some((l) => l.id === v);
}
