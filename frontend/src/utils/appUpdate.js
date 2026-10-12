/**
 * 应用内升级：下载 APK → 直接拉起系统安装器（不再跳浏览器）。
 *
 * 原生实现：`mobile/android/app/src/main/java/com/kb/kbplatform/ApkInstallerPlugin.java`
 *   （已在 `assets/capacitor.plugins.json` 登记，无需 gradle 模块）
 * 兜底：Web 端 / 没带该插件的老包 → 退回「用系统浏览器打开下载地址」。
 */
import { openExternal } from './openExternal'
import { beginUpdate, setUpdateProgress, finishUpdate, failUpdate } from './updateProgress'
// 静态 import：ALLOWED_HOSTS 必须同步就绪。原用动态 import().then() 异步加域名，
// App 内置资源模式下 location.host 是 localhost，真实站点还没加进集合就被校验，
// 会把合法更新地址判成「非法」→ 更新直接失败（2026-10-09 复查发现）。
import { NATIVE_ORIGIN } from './runtimeBase'

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
  const hosts = new Set()
  try { hosts.add(location.host) } catch (e) { console.error('[更新] 无法获取当前站点 host：', e) }
  try {
    // App 内置资源模式下 location.host 是 localhost，真实站点从 NATIVE_ORIGIN 取
    hosts.add(new URL(NATIVE_ORIGIN, location.href).host)
  } catch (e) {
    // 外部审查 2026-10-12：解析失败不能再静默——否则白名单只剩 localhost，
    // 所有合法更新被拒且没有任何排查线索
    console.error('[更新] NATIVE_ORIGIN 解析失败，更新地址白名单可能不完整：', e)
  }
  return hosts
})()

function assertSafeUpdateUrl(url) {
  const u = new URL(url, location.href)
  // 只放行 https：http 会被中间人替换包体，原生层同样强制 https，两层口径保持一致
  // （外部审查 2026-10-11：原实现放行 http，插件缺失走 openExternal 时绕过原生防线）
  if (u.protocol !== 'https:') throw new Error('非法的更新地址协议（仅允许 https）')
  if (!ALLOWED_HOSTS.has(u.host)) throw new Error('非法的更新地址（host 不在允许列表）')
}

/** 更新包/热更包等自托管资源地址的统一校验（更新链路各调用点共用，防未来新增调用点漏检） */
export function assertSafeAssetUrl(url) {
  assertSafeUpdateUrl(url)
}

export async function downloadAndInstall(url, onProgress, checksum) {
  assertSafeUpdateUrl(url)
  // fail-closed：缺 checksum 一律拒绝安装，不再降级为「跳过校验」
  // （外部审查 2026-10-11：checksum || '' 会让原生层把空串当成「免校验」直装）
  if (!checksum) throw new Error('更新包缺少完整性校验值（checksum），已拒绝安装')
  const P = window.Capacitor?.Plugins?.ApkInstaller
  if (!P?.downloadAndInstall) {
    await openExternal(url)
    return { mode: 'external' }
  }

  // 进度同时写进全局状态：App 壳的常驻横幅 + 「消息」页都能看到，
  // 切页/刷新也不会丢（下载在原生后台线程，不受 WebView 影响）
  const ver = String(url).split('/').pop() || ''
  beginUpdate(ver)

  const handles = []
  try {
    if (P.addListener) {
      handles.push(await P.addListener('progress', (d) => {
        const p = typeof d?.percent === 'number' ? d.percent : -1
        const sp = typeof d?.speed === 'number' ? d.speed : 0
        setUpdateProgress(p, sp)
        onProgress?.(p, sp)
      }))
      handles.push(await P.addListener('needInstallPermission', () => {
        window.__kbMsg?.warning('请先允许本应用「安装未知应用」，再回来重新点更新')
      }))
      handles.push(await P.addListener('downloadError', (d) => {
        failUpdate()
        if (d?.message) window.__kbMsg?.error('下载出错：' + d.message)
      }))
    }
  } catch { /* 监听注册失败不影响主流程 */ }

  try {
    await P.downloadAndInstall({ url, checksum })
    finishUpdate()
    return { mode: 'in-app' }
  } catch (e) {
    failUpdate()
    throw e
  } finally {
    handles.forEach((h) => { try { h?.remove?.() } catch { /* ignore */ } })
  }
}
