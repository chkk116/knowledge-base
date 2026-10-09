/**
 * 用系统浏览器打开外部链接。
 *
 * 为什么不能用 window.open：
 *   Capacitor 的 WebView 不支持多窗口，window.open(url, '_blank') 会把**当前 WebView 自己导航走**，
 *   于是 App 界面被替换成下载页/二进制页 → 白屏且回不来。
 *   所以 App 内必须走 @capacitor/browser（打开系统浏览器 / Custom Tab）。
 *
 * 依赖：@capacitor/browser 原生插件（已在 capacitor.settings.gradle / capacitor.build.gradle 手工登记）。
 * Web 端没有该插件，退回 window.open。
 */
export async function openExternal(url) {
  if (!url) return
  // 只放行 http/https：url 来自站点配置，javascript:/intent:/file: 等协议在 WebView/系统浏览器
  // 中行为不可控（外部复审 2026-10-09）
  if (!/^https?:\/\//i.test(String(url).trim())) return
  const Browser = window.Capacitor?.Plugins?.Browser
  if (Browser?.open) {
    try {
      await Browser.open({ url })
      return
    } catch { /* 插件异常则退回下面的方式 */ }
  }
  window.open(url, '_blank', 'noopener,noreferrer')
}
