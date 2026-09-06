/**
 * Composer 输入桥（features/session 域内的小型总线）：
 * SelectionQuoteLayer / 插件动作需要往「当前工单」的输入框插入文本或聚焦，
 * 但它们不持有 Composer 的 ref——Composer 挂载时把两个能力注册进来，
 * 卸载时注销。域外只 import 本模块，不感知 Composer 实例。
 */

type InsertFn = (text: string) => void;
type FocusFn = () => void;

let insert: InsertFn | null = null;
let focus: FocusFn | null = null;

/** Composer 挂载时注册能力；返回注销函数。 */
export function registerComposerBridge(fn: { insert: InsertFn; focus: FocusFn }): () => void {
  insert = fn.insert;
  focus = fn.focus;
  return () => {
    if (insert === fn.insert) insert = null;
    if (focus === fn.focus) focus = null;
  };
}

/** 在当前工单输入框光标处插入文本；无输入框（未选工单/页面不在工作台）时静默忽略。 */
export function insertIntoComposer(text: string) {
  insert?.(text);
}

/** 聚焦当前工单输入框（添加引用胶囊后调用，引导用户继续输入）。 */
export function focusComposer() {
  focus?.();
}
