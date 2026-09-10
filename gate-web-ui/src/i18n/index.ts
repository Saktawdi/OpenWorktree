/**
 * i18n 对外 API：语言状态（zustand）、翻译函数与持久化。
 * - React 组件用 useT()（订阅语言变化，切换即时重渲染）；
 * - 非 React 上下文（store 动作、net 层提示等）用 t()（按当前语言即时取值）；
 * - 语言选择落盘 localStorage（gate-locale），启动时恢复。
 *
 * 存储键在此自持（而非 import store/prefs）：shared/format 依赖 i18n，
 * 而 prefs 依赖 shared/format——若 i18n 反向依赖 prefs 会构成运行时循环。
 */
import { useMemo } from "react";
import { create } from "zustand";
import { makeT, type TParams } from "./core";
import { DEFAULT_LOCALE, isLocaleId, LOCALES, type LocaleId } from "./locales";
import type { MsgKey } from "./locales/zh-CN";

/** 界面语言的 localStorage 键（键名统一 gate-* 前缀；prefs.ts 有同名说明）。 */
export const LOCALE_KEY = "gate-locale";

interface I18nState {
  locale: LocaleId;
}

/** 启动恢复：读持久化语言；脏值/缺失回退默认语言。 */
function loadLocale(): LocaleId {
  try {
    const raw = localStorage.getItem(LOCALE_KEY);
    return isLocaleId(raw) ? raw : DEFAULT_LOCALE;
  } catch {
    return DEFAULT_LOCALE;
  }
}

const i18nStore = create<I18nState>(() => ({ locale: loadLocale() }));

/** 文档级联动：<html lang> 与标签页标题随语言刷新（切换即时生效的一部分）。 */
function applyLocaleDom(locale: LocaleId) {
  document.documentElement.lang = locale;
  document.title = makeT(locale)("app.docTitle");
}

/** 主题同款启动钩子：App 挂载时调用，恢复语言并同步文档属性。 */
export function applyLocaleFromStorage() {
  const locale = loadLocale();
  i18nStore.setState({ locale });
  applyLocaleDom(locale);
}

/** 切换语言：持久化 + 更新状态（订阅方即时重渲染）+ 文档属性联动。 */
export function setLocale(locale: LocaleId) {
  try {
    localStorage.setItem(LOCALE_KEY, locale);
  } catch {
    /* 隐私模式等存储不可用场景：本次会话内仍然生效 */
  }
  i18nStore.setState({ locale });
  applyLocaleDom(locale);
}

/** 当前语言（非响应式；非 React 上下文使用）。 */
export function getLocale(): LocaleId {
  return i18nStore.getState().locale;
}

/** 全部受支持语言（语言分区选项数据源）。 */
export { LOCALES };
export type { LocaleId };
export type { MsgKey };

/** 面向 UI 的翻译函数签名：键约束到 MsgKey，拼错编译期报错。 */
export type Translate = (key: MsgKey, params?: TParams) => string;

/** 非 React 上下文的翻译：按调用时刻的当前语言取值。 */
export const t: Translate = (key, params) => makeT(getLocale())(key, params);

/** React 组件用翻译：订阅语言 store，切换语言即时重渲染。 */
export function useT(): Translate {
  const locale = i18nStore((s) => s.locale);
  return useMemo(() => makeT(locale), [locale]);
}

/** React 组件读当前语言（语言分区选中态等场景）。 */
export function useLocale(): LocaleId {
  return i18nStore((s) => s.locale);
}
