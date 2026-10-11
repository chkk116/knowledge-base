import { resolveUrl } from './runtimeBase'

/**
 * 文档正文媒体增强：供 Web 端 PostDetail 与移动端 MobileDocView 共用。
 *
 * 【App 内置资源模式（P0 离线化）关键函数】absolutizeMedia：
 *   内置模式下页面源是 https://localhost，正文里的 /uploads/xxx、/api/thumb?src=...
 *   这类**站内相对地址会被 Capacitor 本地服务器当成自己的本地文件**（404），
 *   必须补成线上绝对地址。网页端 resolveUrl 原样返回，调用无副作用。
 *
 * 1) <video>：
 *    - 强制 16:9 占位（加载元数据前就撑好高度，避免「点开瞬间页面跳大」）
 *    - 补齐 playsinline / controls，样式统一
 *    - 在视频下方插入一条轻量控制条：倍速循环 / 全屏 / 画中画（原生 controls 没有或端上不一致）
 * 2) 样式类 .kb-video-bar 的定义放在全局 responsive.css（两端入口都引入，且 v-html 动态
 *    插入的节点吃不到 scoped 样式）。
 */
export function enhanceVideos(container) {
  if (!container) return
  container.querySelectorAll('video').forEach((v) => {
    if (v.dataset.kbEnhanced) return
    v.dataset.kbEnhanced = '1'
    v.setAttribute('playsinline', '')
    v.controls = true
    v.style.width = '100%'
    v.style.display = 'block'
    v.style.background = '#000'
    v.style.borderRadius = '10px'
    if (!v.style.aspectRatio) v.style.aspectRatio = '16 / 9'
    v.style.objectFit = 'contain'

    const bar = document.createElement('div')
    bar.className = 'kb-video-bar'

    const mk = (label, title, onClick) => {
      const b = document.createElement('button')
      b.type = 'button'
      b.textContent = label
      b.title = title
      b.addEventListener('click', (e) => { e.preventDefault(); e.stopPropagation(); onClick(b) })
      return b
    }

    const rates = [1, 1.25, 1.5, 2, 0.75]
    let ri = 0
    bar.appendChild(mk('倍速 1x', '切换播放倍速', (b) => {
      ri = (ri + 1) % rates.length
      v.playbackRate = rates[ri]
      b.textContent = '倍速 ' + rates[ri] + 'x'
    }))

    bar.appendChild(mk('全屏', '全屏播放', () => {
      if (v.requestFullscreen) v.requestFullscreen().catch(() => {})
      else if (v.webkitEnterFullscreen) v.webkitEnterFullscreen()
    }))

    if (document.pictureInPictureEnabled) {
      bar.appendChild(mk('画中画', '小窗播放', () => {
        if (document.pictureInPictureElement) document.exitPictureInPicture().catch(() => {})
        else v.requestPictureInPicture?.().catch(() => {})
      }))
    }

    v.after(bar)
  })
}

/** 把正文里以 '/' 开头的媒体地址补成当前环境可用的绝对地址（App 端必需） */
/**
 * HTML **字符串**级的媒体地址绝对化（App 端专用）。
 *
 * 为什么需要它（和 absolutizeMedia 的区别）：
 *  `absolutizeMedia()` 是「渲染完后改 DOM 属性」，在 Tiptap/ProseMirror 里**留不住** ——
 *  ProseMirror 会把 DOM 同步回节点属性，改过的 src 又被还原成相对路径。
 *  所以凡是用 Tiptap 渲染正文的地方（MobileDocView），必须在**灌入前**先把 HTML 里的地址改好。
 *
 * 网页端：resolveUrl 原样返回，本函数直接返回原串（无副作用）。
 */
export function absolutizeMediaHtml(html) {
  if (!html || typeof html !== 'string') return html
  return html.replace(
    /(src|poster|data-full)="\/([^"]*)"/g,
    (_full, attr, path) => attr + '="' + resolveUrl('/' + path) + '"'
  )
}

export function absolutizeMedia(container) {
  if (!container) return
  const fix = (el, attr) => {
    const v = el.getAttribute(attr)
    if (!v || v.startsWith('//') || !v.startsWith('/')) return
    const abs = resolveUrl(v)
    if (abs !== v) el.setAttribute(attr, abs)
  }
  container.querySelectorAll('img').forEach((el) => {
    fix(el, 'src')
    if (el.dataset.full) el.dataset.full = resolveUrl(el.dataset.full)
  })
  container.querySelectorAll('video').forEach((el) => { fix(el, 'src'); fix(el, 'poster') })
  container.querySelectorAll('source').forEach((el) => fix(el, 'src'))
  container.querySelectorAll('audio').forEach((el) => fix(el, 'src'))
}

/** 点击正文图片放大：调用方传 onOpen(src)，由调用方自己渲染灯箱 */
export function bindImgLightbox(container, onOpen) {
  if (!container || typeof onOpen !== 'function') return
  container.querySelectorAll('img').forEach((img) => {
    if (img.classList.contains('kb-emoji') || img.dataset.kbLightbox) return
    img.dataset.kbLightbox = '1'
    img.style.cursor = 'zoom-in'
    img.addEventListener('click', (e) => {
      e.preventDefault()
      e.stopPropagation()
      onOpen(resolveUrl(img.dataset.full || img.getAttribute('src') || ''))
    })
  })
}
