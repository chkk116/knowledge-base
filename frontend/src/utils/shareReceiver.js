/**
 * 系统分享接收（Android 原生插件 ShareReceiver）
 *
 * 冷启动：App 加载后调 getPending() 取清单。
 * 热启动：监听原生事件 'shareReceived'。
 * 每个文件项 { name, mime, size, path, text? }；path 相对 cacheDir。
 *
 * 读取：用 @capacitor/filesystem 的 readFile(Directory.Cache) 拿 base64 → 转 Blob → 上传。
 */

const P = () => window.Capacitor?.Plugins?.ShareReceiver
const FS = () => window.Capacitor?.Plugins?.Filesystem

export function isShareReceiverAvailable() {
  return !!P()
}

/** 取待导入清单（冷启动） */
export async function getPendingShares() {
  const p = P()
  if (!p) return []
  try {
    const r = await p.getPending()
    return (r && r.files) || []
  } catch {
    return []
  }
}

/** 清空待导入清单 */
export async function clearPendingShares() {
  const p = P()
  if (!p) return
  try { await p.clear() } catch { /* ignore */ }
}

/** 监听运行中收到的分享；返回取消函数 */
export function onShareReceived(cb) {
  const p = P()
  if (!p?.addListener) return () => {}
  const h = p.addListener('shareReceived', (data) => {
    try { cb((data && data.files) || []) } catch { /* ignore */ }
  })
  return () => { try { h.then((x) => x?.remove?.()).catch(() => {}) } catch { /* ignore */ } }
}

function b64ToBlob(b64, mime) {
  const bin = atob(b64)
  const len = bin.length
  const arr = new Uint8Array(len)
  for (let i = 0; i < len; i++) arr[i] = bin.charCodeAt(i)
  return new Blob([arr], { type: mime || 'application/octet-stream' })
}

/**
 * 把一条分享项读成浏览器 File（用于上传）。
 * 图片/PDF 走 Filesystem 读缓存文件；纯文本项直接用 item.text。
 */
export async function shareItemToFile(item) {
  // 纯文本：直接造一个 .txt
  if (item.text != null && (!item.mime || item.mime.startsWith('text/'))) {
    return new File([item.text], item.name || '分享的文本.txt', { type: 'text/plain' })
  }
  const fs = FS()
  if (!fs) throw new Error('本机不支持读取分享文件')
  const r = await fs.readFile({ path: item.path, directory: 'CACHE' })
  const blob = b64ToBlob(r.data, item.mime)
  return new File([blob], item.name || 'shared', { type: item.mime || 'application/octet-stream' })
}

/** 判断是否图片（用于决定导入后正文用 <img> 还是链接） */
export function isImage(mime, name) {
  if (mime && mime.startsWith('image/')) return true
  return /\.(png|jpe?g|gif|webp|bmp|heic)$/i.test(name || '')
}
