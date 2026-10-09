/**
 * 应用内升级：下载 APK → 直接拉起系统安装器（不再跳浏览器）。
 *
 * 原生实现：`mobile/android/app/src/main/java/com/kb/kbplatform/ApkInstallerPlugin.java`
 *   （已在 `assets/capacitor.plugins.json` 登记，无需 gradle 模块）
 * 兜底：Web 端 / 没带该插件的老包 → 退回「用系统浏览器打开下载地址」。
 */
import { openExternal } from './openExternal'

/** 当前环境是否支持应用内下载安装 */
export function hasInAppInstaller() {
  return !!window.Capacitor?.Plugins?.ApkInstaller?.downloadAndInstall
}

/**
 * 下载并安装。
 * @param {string} url APK 下载地址
 * @param {(percent:number)=>void} [onProgress] 进度回调；percent=-1 表示总大小未知
 * @returns {Promise<{mode:'in-app'|'external'}>}
 */
/**
 * 更新包地址必须通过校验才交给原生安装器：这个 URL 最终以「安装未知应用」权限落成 APK，
 * 若被改成 http://（中间人替换包体）或指向非自有域名，等同于远程代码执行（外部复审 2026-10-09）。
 * 允许的 host：当前站点（网页端）+ App 部署站点（内置资源模式下的 NATIVE_ORIGIN）。
 */
const ALLOWED_HOSTS = (() => {
  const hosts = new Set([location.host])
  try {
    // App 内置资源模式下 location.host 是 https://localhost，真实站点从 NATIVE_ORIGIN 取
    import('./runtimeBase').then((m) => {
      try { hosts.add(new URL(m.NATIVE_ORIGIN, location.href).host) } catch { /* 忽略 */ }
    }).catch(() => {})
  } catch { /* ignore */ }
  return hosts
})()

function assertSafeUpdateUrl(url) {
  const u = new URL(url, location.href)
  if (u.protocol !== 'https:' && u.protocol !== 'http:') throw new Error('非法的更新地址协议')
  if (!ALLOWED_HOSTS.has(u.host)) throw new Error('非法的更新地址（host 不在允许列表）')
}

export async function downloadAndInstall(url, onProgress, checksum) {
  assertSafeUpdateUrl(url)
  const P = window.Capacitor?.Plugins?.ApkInstaller
  if (!P?.downloadAndInstall) {
    await openExternal(url)
    return { mode: 'external' }
  }

  const handles = []
  try {
    if (P.addListener) {
      handles.push(await P.addListener('progress', (d) => {
        onProgress?.(typeof d?.percent === 'number' ? d.percent : -1)
      }))
      handles.push(await P.addListener('needInstallPermission', () => {
        window.__kbMsg?.warning('请先允许本应用「安装未知应用」，再回来重新点更新')
      }))
      handles.push(await P.addListener('downloadError', (d) => {
        if (d?.message) window.__kbMsg?.error('下载出错：' + d.message)
      }))
    }
  } catch { /* 监听注册失败不影响主流程 */ }

  try {
    await P.downloadAndInstall({ url, checksum: checksum || '' })
    return { mode: 'in-app' }
  } finally {
    handles.forEach((h) => { try { h?.remove?.() } catch { /* ignore */ } })
  }
}
