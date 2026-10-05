// 运行时基地址解析。
//
// 背景：同一份前端产物要同时跑在两种环境
//   1) 网页端 —— 浏览器直接访问 https://www.chkk116.cloud，
//      相对路径 '/api'、'/uploads' 由 nginx 同源反代，无跨域、无绝对地址，换域名不用重新打包。
//   2) App 端 —— Capacitor 套壳，页面从 WebView 本地文件加载（源 = server.hostname）。
//      此时相对路径会被 Capacitor 判定为「本地资源」直接 404，必须改走绝对地址。
//
// Capacitor 会在 WebView 里注入 window.Capacitor，且 isNativePlatform() 返回 true，
// 据此在运行时切换，无需为 App 单独构建一份产物。

/** App 端使用的后端根地址（不含 /api）。换域名时只改这一处。 */
export const NATIVE_ORIGIN = 'https://www.chkk116.cloud'

/** 当前是否运行在 Capacitor 原生容器内。 */
export function isNative() {
  try {
    return !!(
      typeof window !== 'undefined' &&
      window.Capacitor &&
      typeof window.Capacitor.isNativePlatform === 'function' &&
      window.Capacitor.isNativePlatform()
    )
  } catch (e) {
    return false
  }
}

/**
 * 把以 '/' 开头的站内相对路径解析为当前环境可用的绝对地址。
 * 网页端原样返回（保持同源反代的简洁）；App 端补上 NATIVE_ORIGIN。
 * 已是 http(s)/data/blob 等绝对地址的直接返回。
 */
export function resolveUrl(path) {
  if (!path) return path
  if (/^(https?:|data:|blob:|capacitor:|file:)/i.test(path)) return path
  if (!isNative()) return path
  return NATIVE_ORIGIN.replace(/\/$/, '') + (path.startsWith('/') ? path : '/' + path)
}

/** API 基地址：网页端 '/api'，App 端绝对地址。 */
export const apiBase = () => resolveUrl('/api')
