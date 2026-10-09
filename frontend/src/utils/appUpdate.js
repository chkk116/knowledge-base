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
export async function downloadAndInstall(url, onProgress) {
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
    await P.downloadAndInstall({ url })
    return { mode: 'in-app' }
  } finally {
    handles.forEach((h) => { try { h?.remove?.() } catch { /* ignore */ } })
  }
}
