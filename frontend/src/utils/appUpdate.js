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
 * 是否已有「下载完成且校验通过」的安装包（同一个 checksum 视为同一个包）。
 *
 * 背景：更新包下完、系统安装界面弹出后用户点了「取消」，本地那个十几 MB 的包其实还在，
 * 但旧实现每次都无条件重下 —— 表现为「取消后再点更新又要重新下载」。
 * 这里先问原生一句，有货就复用，按钮也能相应写成「立即安装」。
 *
 * 原生不可用时（网页端 / 老包没带 checkCached）返回 null，
 * 调用方按「没缓存」处理即可，行为与改动前一致。
 */
export async function getCachedApk(checksum) {
  const P = window.Capacitor?.Plugins?.ApkInstaller
  if (!checksum || !P?.checkCached) return null
  try {
    const r = await P.checkCached({ checksum })
    if (r?.cached && r?.filePath) return { filePath: r.filePath, size: Number(r.size) || 0 }
  } catch {
    // 老包没有该方法 / 插件异常：按「无缓存」处理，走正常下载
  }
  return null
}

/**
 * 直接拉起已下载包的安装界面（不重新下载）。缓存被系统清掉时会抛错，调用方应回落到下载。
 *
 * 2026-10-18：缺「安装未知应用」授权时原生只跳设置页不装——这里补挂 needInstallPermission 监听
 * （原生事件本来就会发，只是复用路径没注册没人接），并把 launched 透传给调用方，
 * 调用方据此给「去授权」提示，而不是弹假成功。
 * @returns {Promise<{launched:boolean}>} 老包没有该字段时按成功处理
 */
export async function installCachedApk(filePath) {
  const P = window.Capacitor?.Plugins?.ApkInstaller
  if (!filePath || !P?.install) throw new Error('当前版本不支持直接安装，将重新下载')
  let handle = null
  try {
    if (P.addListener) {
      handle = await P.addListener('needInstallPermission', () => {
        window.__kbMsg?.warning('请先允许本应用「安装未知应用」，授权后返回再点「立即安装」')
      })
    }
  } catch { /* 监听注册失败不影响主流程 */ }
  try {
    const r = await P.install({ filePath })
    return { launched: r?.launched !== false }
  } finally {
    try { handle?.remove?.() } catch { /* ignore */ }
  }
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
