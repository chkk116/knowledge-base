/**
 * 分享 / 保存文件的统一出口（原生优先，Web 兜底）
 *
 * 为什么需要它：Capacitor 的 Android WebView **不支持** a[download] 下载，
 * `window.open` 又会把 WebView 自己导航走（白屏）。所以：
 *   原生：@capacitor/filesystem 写到缓存目录 → @capacitor/share 拉起系统分享面板
 *        （用户可存到「文件」、发微信、用 WPS 打开——这就是大厂 App 的导出体验）
 *   Web ：退回 Blob + a[download] / navigator.share / 复制到剪贴板
 *
 * 插件用运行时方式访问（window.Capacitor.Plugins.*），与项目里其它地方一致，
 * 没装插件的老包/Web 端会自动走兜底，不会报错。
 */
const capPlugin = (name) => {
  try { return window.Capacitor?.Plugins?.[name] } catch { return null }
}

export const isNativeEnv = () => {
  try { return !!window.Capacitor?.isNativePlatform?.() } catch { return false }
}

/** UTF-8 字符串 → base64（Filesystem.writeFile 需要 base64 或 data URL） */
export function utf8ToBase64(str) {
  try {
    const bytes = new TextEncoder().encode(str)
    let bin = ''
    const CHUNK = 0x8000
    for (let i = 0; i < bytes.length; i += CHUNK) {
      bin += String.fromCharCode.apply(null, bytes.subarray(i, i + CHUNK))
    }
    return btoa(bin)
  } catch {
    return ''
  }
}

/** 复制到剪贴板（返回是否成功） */
export async function copyText(text) {
  const s = String(text ?? '')
  if (!s) return false
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(s)
      return true
    }
  } catch { /* 落下面 */ }
  try {
    const ta = document.createElement('textarea')
    ta.value = s
    ta.style.position = 'fixed'
    ta.style.opacity = '0'
    document.body.appendChild(ta)
    ta.select()
    const ok = document.execCommand('copy')
    document.body.removeChild(ta)
    return ok
  } catch { return false }
}

/**
 * 分享一段文字 / 链接。
 * 原生优先系统分享面板，其次 Web Share，最后复制到剪贴板。
 * 返回 'share' | 'copy' | false
 */
export async function shareText({ title = '', text = '', url = '' }) {
  const Share = capPlugin('Share')
  if (Share?.share) {
    try {
      await Share.share({ title, text, url, dialogTitle: title || '分享' })
      return 'share'
    } catch (e) {
      // 用户取消不算失败：Capacitor 取消时抛 "Share canceled"
      if (/cancel/i.test(String(e?.message || e))) return 'share'
    }
  }
  if (navigator.share) {
    try {
      await navigator.share({ title, text, url })
      return 'share'
    } catch (e) {
      if (/abort|cancel/i.test(String(e?.name || e?.message || e))) return 'share'
    }
  }
  return (await copyText([text, url].filter(Boolean).join('\n'))) ? 'copy' : false
}

/** Web 端兜底：Blob 下载 */
function downloadBlob(filename, content, mime) {
  try {
    const blob = content instanceof Blob ? content : new Blob([content], { type: mime })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = filename
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    setTimeout(() => URL.revokeObjectURL(url), 4000)
    return true
  } catch { return false }
}

/**
 * 保存（或分享）一个文本文件。
 * 原生：写入缓存目录 → 拉起系统分享（用户自行存到「文件」/发给自己/用 WPS 打开）
 * Web ：直接下载
 * 返回 'share' | 'download' | false
 */
export async function saveTextFile({ filename, content, mime = 'text/plain' }) {
  const Filesystem = capPlugin('Filesystem')
  const Share = capPlugin('Share')
  if (Filesystem?.writeFile) {
    const b64 = utf8ToBase64(typeof content === 'string' ? content : await content.text())
    if (b64) {
      try {
        const { uri } = await Filesystem.writeFile({
          path: filename,
          data: b64,
          directory: 'CACHE',
          encoding: 'base64',
        })
        if (Share?.share) {
          try {
            await Share.share({ title: filename, files: [uri], dialogTitle: '保存 / 分享' })
            return 'share'
          } catch (e) {
            // 用户主动取消 → 视为已交给用户处理；非 cancel（权限/网络等）→ 落回浏览器下载，
            // 不再穿透到下一行把「分享失败」误报成成功（外部审查 2026-10-11）
            if (/cancel/i.test(String(e?.message || e))) return 'share'
            return downloadBlob(filename, content, mime) ? 'download' : false
          }
        }
        return 'share'
      } catch { /* 落下面 */ }
    }
  }
  return downloadBlob(filename, content, mime) ? 'download' : false
}

/** 文件名安全化 */
export function safeName(title, ext) {
  const base = String(title || 'document').replace(/[\\/:*?"<>|\s]+/g, '_').slice(0, 60) || 'document'
  return `${base}.${ext}`
}
