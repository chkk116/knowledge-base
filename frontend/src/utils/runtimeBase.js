// 运行时基地址解析。
//
// 背景：同一份前端产物要同时跑在两种环境
//   1) 网页端 —— 浏览器直接访问部署站点，相对路径 '/api'、'/uploads' 由 nginx 同源反代，
//      无跨域、无绝对地址，换域名不用重新打包。
//   2) App 端 —— Capacitor 套壳，页面从 WebView 本地文件加载（源 = server.hostname）。
//      此时相对路径会被 Capacitor 判定为「本地资源」直接 404，必须改走绝对地址。
//
// Capacitor 会在 WebView 里注入 window.Capacitor，且 isNativePlatform() 返回 true，
// 据此在运行时切换，无需为 App 单独构建一份产物。

/**
 * App 端使用的后端根地址（不含 /api）。
 *
 * 仓库里**不存任何公网地址**，默认指向本机占位；打包自己的 App 时用环境变量覆盖：
 *
 *   VITE_NATIVE_ORIGIN=https://kb.example.com npm run build:prod
 *
 * 真机联调本机后端的常用值：
 *   - Android 模拟器：http://10.0.2.2:8080（10.0.2.2 = 宿主机回环）
 *   - 真机 + 局域网： http://<电脑局域网IP>:8080
 */
if (!import.meta.env.VITE_NATIVE_ORIGIN) {
  console.info('[runtimeBase] 未设置 VITE_NATIVE_ORIGIN，App 端默认连 http://localhost:8080（打包时可用环境变量覆盖）')
}
// 尾部斜杠归一：env 写成 https://kb.example.com/ 时，避免拼接出 //api
export const NATIVE_ORIGIN =
  (import.meta.env.VITE_NATIVE_ORIGIN || 'http://localhost:8080').trim().replace(/\/+$/, '')

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
