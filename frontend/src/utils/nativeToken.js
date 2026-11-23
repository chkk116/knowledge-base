/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

/**
 * 原生壳 / 桌面 WebView 的登录态接收端（Web 入口与移动入口共用）。
 *
 * 三个来源，优先级从上到下：
 *  1) window.__KB_NATIVE_TOKEN —— 壳在 HTML 文档请求阶段注入（App 端 WebScreen.kt、桌面 initScript）
 *  2) #__kb_token=<token>      —— 桌面 WebView 把令牌拼进目标地址（allowUrl 时才认）
 *     **放 hash 不放查询串**：查询串会随 HTML 请求进服务器 access log、也会出现在子资源 Referer 里；
 *     hash 既不发给服务端也不进 Referer。
 *  3) ?__kb_token=<token>      —— 兼容早期桌面构建（曾用查询串），新版本不再发这种地址
 *
 * 必须在本入口后续任何 useAuthStore() / router 首次导航之前调用：store/auth.js 在
 * defineStore 时同步读 localStorage.kb_token，晚一步就会带着空令牌渲染并跳登录页。
 * 落盘后立刻抹掉全局变量与地址栏参数，避免令牌留在历史记录 / 截图里。
 *
 * 语义：`__KB_NATIVE_TOKEN` 为 undefined 表示「不在壳里」，完全不碰 localStorage；
 * 为显式空值（null / 空串）表示壳已登出，清掉本地会话。
 */
export function adoptNativeToken({ allowUrl = false } = {}) {
  let incoming
  try { incoming = window.__KB_NATIVE_TOKEN } catch (e) { incoming = undefined }
  let fromUrl = ''
  if (allowUrl) {
    try {
      const hash = String(location.hash || '').replace(/^#/, '')
      if (hash) fromUrl = new URLSearchParams(hash).get('__kb_token') || ''
      if (!fromUrl) fromUrl = new URL(location.href).searchParams.get('__kb_token') || ''
    } catch (e) { /* 隐私模式 / 非法 URL：交给登录页 */ }
  }
  const inShell = incoming !== undefined
  const token = (inShell ? incoming : '') || fromUrl
  if (!inShell && !fromUrl) return

  try {
    if (token) {
      localStorage.setItem('kb_token', String(token))
    } else {
      // 壳已登出：清干净，避免 WebView 里残留旧会话（含用户缓存与 id）
      localStorage.removeItem('kb_token')
      localStorage.removeItem('kb_user_cache')
      localStorage.removeItem('kb_user_id')
    }
  } catch (e) { /* 无痕模式 localStorage 不可写，交给后续手动登录 */ }

  // 落盘后立刻抹掉全局变量：否则后续任意脚本都能读到
  try { delete window.__KB_NATIVE_TOKEN } catch (e) { window.__KB_NATIVE_TOKEN = undefined }

  if (fromUrl) {
    try {
      const u = new URL(location.href)
      u.searchParams.delete('__kb_token')
      const hp = new URLSearchParams(String(u.hash || '').replace(/^#/, ''))
      hp.delete('__kb_token')
      const rest = hp.toString()
      history.replaceState(null, '', u.pathname + (u.search || '') + (rest ? '#' + rest : ''))
    } catch (e) { /* 忽略：参数没清掉也只影响地址栏显示 */ }
  }
}
