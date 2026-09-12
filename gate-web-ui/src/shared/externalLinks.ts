/** 外链统一出口：桌面壳里 WebView 的新窗口请求（window.open / target=_blank）默认
 *  被拦，SPA 在远端 origin 又拿不到 Tauri API——经既有 postMessage 桥把 URL 交给
 *  壳页（tauri:// 域）以 opener 插件拉起系统默认浏览器；浏览器直开仍走新标签。 */
export function openExternal(url: string) {
  if (!/^https?:\/\//i.test(url)) return;
  try {
    if (window.parent !== window) {
      window.parent.postMessage({ __ow: true, action: "open-external", url }, "*");
      return;
    }
  } catch {
    // parent 不可达（异常嵌入环境）时退回 window.open
  }
  window.open(url, "_blank", "noopener,noreferrer");
}
