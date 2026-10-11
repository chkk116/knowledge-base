/**
 * 文档导出与分享（纯前端实现，不依赖后端导出接口）
 *
 * App 里读的是**空间文档（SharedDocument）**，后端只有针对 Document 的
 * /docs/{id}/export，所以这里自己做：
 *   - 导出 Word(.doc)：套一层 Word 能识别的 HTML 信封（与后端 exportWord 同思路）
 *   - 导出 Markdown(.md)：HTML → Markdown 简易转换
 *   - 复制纯文本 / 复制 Markdown / 复制链接
 *   - 分享二维码（qrcode 库生成 dataURL，页面内直接展示）
 *
 * 文件落地走 utils/shareOut.js（原生用 Filesystem+Share，Web 退回浏览器下载）。
 */
import { NATIVE_ORIGIN } from './runtimeBase'

/** 把相对资源路径补成绝对（导出/分享后图片还能显示） */
export function absolutize(html) {
  if (!html) return ''
  return String(html)
    .replace(/(src|href)="\/(?!\/)/g, `$1="${NATIVE_ORIGIN}/`)
    .replace(/(src|href)='\/(?!\/)/g, `$1='${NATIVE_ORIGIN}/`)
}

/** 文档的可分享链接（原生里 location 是 https://localhost，不能直接拿去分享） */
export function shareLink(spaceId, docId) {
  return `${NATIVE_ORIGIN}/m/doc/${spaceId}/${docId}`
}

const esc = (s) => String(s || '').replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]))

/** 包成 Word/WPS 可直接打开的 .doc（HTML 信封） */
export function toWordHtml(title, html) {
  return `<html xmlns:o="urn:schemas-microsoft-com:office:office" xmlns:w="urn:schemas-microsoft-com:office:word" xmlns="http://www.w3.org/TR/REC-html40">
<head><meta charset="utf-8"><title>${esc(title)}</title>
<!--[if gte mso 9]><xml><w:WordDocument><w:View>Print</w:View></w:WordDocument></xml><![endif]-->
<style>
body{font-family:"Microsoft YaHei","PingFang SC",sans-serif;font-size:11pt;line-height:1.75;color:#1d2129}
h1{font-size:19pt;margin:18pt 0 10pt}h2{font-size:15pt;margin:16pt 0 8pt}h3{font-size:13pt;margin:14pt 0 6pt}
table{border-collapse:collapse;width:100%}td,th{border:1px solid #999;padding:4pt 8pt;text-align:left}
img{max-width:100%}blockquote{border-left:3pt solid #ddd;margin:8pt 0;padding-left:10pt;color:#666}
pre{background:#f5f6f7;padding:8pt;border-radius:4pt;font-family:Consolas,monospace}
</style></head>
<body><h1>${esc(title)}</h1>${absolutize(html)}</body></html>`
}

/** 纯文本（去标签） */
export function toPlainText(html) {
  if (!html) return ''
  return String(html)
    .replace(/<(br|\/p|\/div|\/h[1-6]|\/li|\/tr)\s*\/?>/gi, '\n')
    .replace(/<[^>]*>/g, '')
    .replace(/&nbsp;/g, ' ')
    .replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"')
    .replace(/\n{3,}/g, '\n\n')
    .trim()
}

/**
 * HTML → Markdown（覆盖标题/段落/加粗/斜体/链接/图片/列表/引用/代码/表格/分割线）。
 * 只做常见节点，复杂图块（流程图等）退化为占位文字。
 */
export function toMarkdown(html, title = '') {
  if (!html) return title ? `# ${title}\n` : ''
  let doc
  try { doc = new DOMParser().parseFromString(`<div>${html}</div>`, 'text/html') } catch { return toPlainText(html) }
  const out = []
  if (title) out.push(`# ${title}`, '')

  const inline = (node) => {
    let s = ''
    node.childNodes.forEach((n) => {
      if (n.nodeType === 3) { s += n.nodeValue.replace(/\s+/g, ' '); return }
      if (n.nodeType !== 1) return
      const tag = n.tagName.toLowerCase()
      const inner = inline(n)
      if (tag === 'strong' || tag === 'b') s += `**${inner}**`
      else if (tag === 'em' || tag === 'i') s += `*${inner}*`
      else if (tag === 'code') s += '`' + inner + '`'
      else if (tag === 'a') s += `[${inner}](${n.getAttribute('href') || ''})`
      else if (tag === 'img') s += `![${n.getAttribute('alt') || ''}](${absolutize(n.getAttribute('src') || '')})`
      else if (tag === 'br') s += '  \n'
      else s += inner
    })
    return s
  }

  const walk = (parent) => {
    parent.childNodes.forEach((n) => {
      if (n.nodeType === 3) {
        const t = n.nodeValue.trim()
        if (t) out.push(t)
        return
      }
      if (n.nodeType !== 1) return
      const tag = n.tagName.toLowerCase()
      if (/^h[1-6]$/.test(tag)) {
        out.push('', '#'.repeat(Number(tag[1])) + ' ' + inline(n), '')
      } else if (tag === 'p') {
        const s = inline(n).trim()
        if (s) out.push(s, '')
      } else if (tag === 'ul' || tag === 'ol') {
        out.push('')
        Array.from(n.children).forEach((li, i) => {
          out.push((tag === 'ol' ? `${i + 1}. ` : '- ') + inline(li).trim())
        })
        out.push('')
      } else if (tag === 'blockquote') {
        out.push('', '> ' + inline(n).trim(), '')
      } else if (tag === 'pre') {
        out.push('', '```', n.textContent.replace(/\n$/, ''), '```', '')
      } else if (tag === 'table') {
        out.push('')
        Array.from(n.querySelectorAll('tr')).forEach((tr, ri) => {
          const cells = Array.from(tr.children).map((td) => inline(td).trim() || ' ')
          out.push('| ' + cells.join(' | ') + ' |')
          if (ri === 0) out.push('| ' + cells.map(() => '---').join(' | ') + ' |')
        })
        out.push('')
      } else if (tag === 'hr') {
        out.push('', '---', '')
      } else if (n.hasAttribute && n.hasAttribute('data-type')) {
        out.push('', `> [${n.getAttribute('data-type')} 图块，请在 App 内查看]`, '')
      } else {
        walk(n)
      }
    })
  }

  walk(doc.body.firstChild || doc.body)
  return out.join('\n').replace(/\n{3,}/g, '\n\n').trim() + '\n'
}

/** 生成二维码 dataURL（用于页面内展示） */
export async function qrDataUrl(text, size = 220) {
  const QR = await import('qrcode')
  return (QR.default || QR).toDataURL(text, {
    width: size,
    margin: 1,
    errorCorrectionLevel: 'M',
    color: { dark: '#1d2129', light: '#ffffff' },
  })
}
