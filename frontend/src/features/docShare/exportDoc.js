/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
/** DocShareEditor 的快照与导出能力（Markdown / HTML / Excel），纯函数为主、可单测；从视图抽离。 */
import { ElMessage } from 'element-plus'
import { toSvg } from 'html-to-image'

const DIAGRAM_SEL = '[data-type="mindmap-block"],[data-type="flowchart-block"],[data-type="sheet-block"]'

export async function snapshotDiagramImages(editor) {
  const root = editor?.view?.dom
  const map = new Map()
  if (!root) return map
  const blocks = [...root.querySelectorAll(DIAGRAM_SEL)]
  const rendered = await Promise.all(blocks.map(async (el, i) => {
    try {
      return await toSvg(el, { backgroundColor: '#ffffff', cacheBust: true })
    } catch (e) {
      // 单块失败不影响其它块，保留原 div；但失败要可诊断
      console.warn('图块转图失败 #' + i + '：', e)
      return null
    }
  }))
  rendered.forEach((dataUrl, i) => { if (dataUrl) map.set(i, dataUrl) })
  return map
}

export async function buildSnapshotHtml(editor) {
  const ed = editor
  if (!ed) return ''
  const images = await snapshotDiagramImages(ed)
  const html = ed.getHTML()
  if (!images.size) return html
  const doc = new DOMParser().parseFromString(html, 'text/html')
  const targets = [...doc.querySelectorAll(DIAGRAM_SEL)]
  targets.forEach((el, i) => {
    const src = images.get(i)
    if (!src) return
    const img = doc.createElement('img')
    img.setAttribute('src', src)
    img.setAttribute('alt', el.getAttribute('title') || '图表')
    img.setAttribute('class', 'kb-diagram-snapshot')
    el.replaceWith(img)
  })
  return doc.body.innerHTML
}


// ---------- 导出：Markdown / HTML ----------
function safeName(s) {
  return (s || 'document').replace(/[\\/:*?"<>|]+/g, '_').slice(0, 80) || 'document'
}
// （图块专属文档的「转为普通文档」已移除：专属文档整篇就是一个图块，转完内容会丢；
//   需要文字混排时直接新建普通文档插入图块即可）

function downloadBlob(filename, blob) {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  a.remove()
  // 立刻 revoke 在部分浏览器会中断下载，延后一拍
  setTimeout(() => URL.revokeObjectURL(url), 0)
}

function download(filename, content, mime) {
  downloadBlob(filename, new Blob([content], { type: mime + ';charset=utf-8' }))
}

/** 纯前端 HTML -> Markdown（覆盖本编辑器所用元素，零依赖，避免沙箱装包） */
function htmlToMarkdown(html) {
  // DOMParser 游离文档不加载资源，detached <img onerror> 不会触发（2026-10-13 审查 H9）
  const el = new DOMParser().parseFromString(html || '', 'text/html').body
  const block = (node) => {
    let out = ''
    node.childNodes.forEach((n) => { out += inline(n) })
    return out
  }
  const inline = (n) => {
    if (n.nodeType === 3) return (n.textContent || '').replace(/[ \t]+\n/g, '\n')
    if (n.nodeType !== 1) return ''
    const tag = n.tagName.toLowerCase()
    const txt = () => Array.from(n.childNodes).map(inline).join('')
    switch (tag) {
      case 'br': return '\n'
      case 'hr': return '\n---\n'
      case 'strong': case 'b': return '**' + txt() + '**'
      case 'em': case 'i': return '*' + txt() + '*'
      case 's': case 'del': return '~~' + txt() + '~~'
      case 'u': return txt() // Markdown 无下划线语义，保留文本
      case 'code': return '`' + txt() + '`'
      case 'a': return '[' + txt() + '](' + safeUrl(n.getAttribute('href')) + ')'
      case 'img': return '![' + (n.getAttribute('alt') || '') + '](' + safeUrl(n.getAttribute('src')) + ')'
      case 'span': case 'font': return txt() // 行内样式包裹，直接取文本
      default: return txt()
    }
  }
  const walk = (node, depth) => {
    let md = ''
    node.childNodes.forEach((n) => {
      if (n.nodeType === 3) {
        const t = (n.textContent || '').replace(/^[ \t]+|[ \t]+$/g, '')
        if (t) md += t
        return
      }
      if (n.nodeType !== 1) return
      const tag = n.tagName.toLowerCase()
      if (tag === 'h1') md += '\n# ' + block(n) + '\n'
      else if (tag === 'h2') md += '\n## ' + block(n) + '\n'
      else if (tag === 'h3') md += '\n### ' + block(n) + '\n'
      else if (tag === 'h4') md += '\n#### ' + block(n) + '\n'
      else if (tag === 'h5') md += '\n##### ' + block(n) + '\n'
      else if (tag === 'h6') md += '\n###### ' + block(n) + '\n'
      else if (tag === 'p') md += '\n' + block(n) + '\n'
      else if (tag === 'blockquote') md += '\n> ' + block(n).replace(/\n/g, '\n> ') + '\n'
      else if (tag === 'pre') md += '\n```\n' + (n.textContent || '').replace(/\n$/, '') + '\n```\n'
      else if (tag === 'ul' || tag === 'ol') {
        md += '\n'
        Array.from(n.children).forEach((li, i) => {
          const prefix = tag === 'ol' ? (i + 1) + '. ' : '- '
          md += prefix + (li.textContent || '').trim() + '\n'
        })
      }
      else if (tag === 'table') {
        const rows = Array.from(n.querySelectorAll('tr'))
        if (rows.length) {
          md += '\n'
          rows.forEach((r, ri) => {
            const cells = Array.from(r.children).map((c) => (c.textContent || '').trim().replace(/\|/g, '\\|'))
            md += '| ' + cells.join(' | ') + ' |\n'
            if (ri === 0) md += '| ' + cells.map(() => '---').join(' | ') + ' |\n'
          })
        }
      }
      else if (tag === 'br') md += '\n'
      else if (tag === 'hr') md += '\n---\n'
      else md += block(n)
    })
    return md
  }
  let result = walk(el, 0).replace(/\n{3,}/g, '\n\n').replace(/^\n+/, '').replace(/\n+$/, '\n')
  return result
}

// ---------- 导出：Excel (.xlsx) 零依赖 ----------
// 手写最小 xlsx：5 个 XML 部件 + store 模式（无压缩）zip，浏览器原生生成，
// 不需要 xlsx/JSZip 等任何 npm 包（沙箱装不了包，本机也免装依赖）。
const XLSX_CONTENT_TYPES = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' +
  '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">' +
  '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>' +
  '<Default Extension="xml" ContentType="application/xml"/>' +
  '<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>' +
  '<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>' +
  '</Types>'
const XLSX_ROOT_RELS = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' +
  '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">' +
  '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>' +
  '</Relationships>'
const XLSX_WORKBOOK = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' +
  '<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" ' +
  'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">' +
  '<sheets><sheet name="文档内容" sheetId="1" r:id="rId1"/></sheets></workbook>'
const XLSX_WORKBOOK_RELS = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' +
  '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">' +
  '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>' +
  '</Relationships>'

function xmlEscape(s) {
  return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&apos;')
}
// 1-based 列号 → Excel 列字母（A、B、…、Z、AA…）
function colLetter(n) {
  let s = ''
  while (n > 0) { const m = (n - 1) % 26; s = String.fromCharCode(65 + m) + s; n = Math.floor((n - 1) / 26) }
  return s
}

// 编辑器 HTML → 二维数组（每行一个数组；标题/段落/列表/代码为单列行，表格按原结构展开成单元格矩阵）
function htmlToAoa(html) {
  // 同 htmlToMarkdown：DOMParser 不加载资源
  const el = new DOMParser().parseFromString(html || '', 'text/html').body
  const rows = []
  const inlineText = (n) => {
    let out = ''
    n.childNodes.forEach((c) => {
      if (c.nodeType === 3) out += c.textContent || ''
      else if (c.nodeType === 1) {
        const t = c.tagName.toLowerCase()
        if (t === 'br') out += '\n'
        else if (t === 'img') out += c.getAttribute('alt') || ''
        else out += inlineText(c)
      }
    })
    return out
  }
  const push = (v) => rows.push([(v === null || v === undefined) ? '' : String(v)])
  const walk = (node) => {
    node.childNodes.forEach((n) => {
      if (n.nodeType === 3) {
        const t = (n.textContent || '').replace(/^[ \t]+|[ \t]+$/g, '')
        if (t) push(t)
        return
      }
      if (n.nodeType !== 1) return
      const tag = n.tagName.toLowerCase()
      const txt = () => (inlineText(n) || '').replace(/\s+/g, ' ').trim()
      if (tag === 'h1') push('# ' + txt())
      else if (tag === 'h2') push('## ' + txt())
      else if (tag === 'h3') push('### ' + txt())
      else if (tag === 'h4') push('#### ' + txt())
      else if (tag === 'h5') push('##### ' + txt())
      else if (tag === 'h6') push('###### ' + txt())
      else if (tag === 'p') push(txt())
      else if (tag === 'blockquote') push('> ' + txt())
      else if (tag === 'pre') (n.textContent || '').replace(/\n$/, '').split('\n').forEach((l) => push(l))
      else if (tag === 'ul' || tag === 'ol') {
        Array.from(n.children).forEach((li, i) => {
          const box = li.querySelector('input[type="checkbox"]')
          const body = (li.textContent || '').trim()
          if (box) push((box.checked ? '[x] ' : '[ ] ') + body)
          else push((tag === 'ol' ? (i + 1) + '. ' : '- ') + body)
        })
      }
      else if (tag === 'table') {
        // 表格：每个 tr 一行、每个单元格一列（Excel 原生结构，导出后可直接继续编辑）
        Array.from(n.querySelectorAll('tr')).forEach((tr) => {
          rows.push(Array.from(tr.children).map((c) => (c.textContent || '').trim()))
        })
        rows.push([])
      }
      else if (tag === 'br') push('')
      else if (tag === 'hr') push('---')
      else push(txt())
    })
  }
  walk(el)
  while (rows.length && rows[0].length === 1 && !rows[0][0]) rows.shift()
  while (rows.length && rows[rows.length - 1].length === 1 && !rows[rows.length - 1][0]) rows.pop()
  return rows
}

function aoaToSheetXml(aoa) {
  let maxCols = 1
  aoa.forEach((r) => { if (r.length > maxCols) maxCols = r.length })
  // 列宽：正文（单列）给宽列；含表格（多列）按列数均匀分配
  let cols
  if (maxCols === 1) cols = '<cols><col min="1" max="1" width="72" customWidth="1"/></cols>'
  else {
    const w = Math.max(12, Math.min(40, Math.floor(96 / maxCols)))
    cols = '<cols>' + Array.from({ length: maxCols }, (_, i) =>
      `<col min="${i + 1}" max="${i + 1}" width="${w}" customWidth="1"/>`).join('') + '</cols>'
  }
  let body = ''
  aoa.forEach((r, i) => {
    const rowNum = i + 1
    let cells = ''
    r.forEach((v, j) => {
      const s = xmlEscape((v === null || v === undefined) ? '' : String(v))
      cells += `<c r="${colLetter(j + 1)}${rowNum}" t="inlineStr"><is><t xml:space="preserve">${s}</t></is></c>`
    })
    body += `<row r="${rowNum}">${cells}</row>`
  })
  return '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' +
    '<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">' +
    cols + '<sheetData>' + body + '</sheetData></worksheet>'
}

// CRC32（zip store 模式校验用），标准查表实现
let crcTable = null
function crc32(buf) {
  if (!crcTable) {
    crcTable = new Int32Array(256)
    for (let i = 0; i < 256; i++) {
      let c = i
      for (let k = 0; k < 8; k++) c = (c & 1) ? (0xEDB88320 ^ (c >>> 1)) : (c >>> 1)
      crcTable[i] = c
    }
  }
  let crc = -1
  for (let i = 0; i < buf.length; i++) crc = (crc >>> 8) ^ crcTable[(crc ^ buf[i]) & 0xFF]
  return (crc ^ -1) >>> 0
}

// 把 {name, data(Uint8Array)} 列表打成 zip（store 无压缩，避免 deflate，Excel 兼容）
function zipStore(files) {
  const enc = new TextEncoder()
  const localParts = []
  const centralParts = []
  let offset = 0
  files.forEach((f) => {
    const nameB = enc.encode(f.name)
    const dataB = f.data
    const crc = crc32(dataB)
    const local = new Uint8Array(30 + nameB.length + dataB.length)
    const dv = new DataView(local.buffer)
    dv.setUint32(0, 0x04034b50, true)
    dv.setUint16(4, 20, true)
    dv.setUint16(6, 0, true)
    dv.setUint16(8, 0, true)  // method 0 = store
    dv.setUint16(10, 0, true)
    dv.setUint16(12, 0x21, true) // 1980-01-01
    dv.setUint32(14, crc, true)
    dv.setUint32(18, dataB.length, true)
    dv.setUint32(22, dataB.length, true)
    dv.setUint16(26, nameB.length, true)
    dv.setUint16(28, 0, true)
    local.set(nameB, 30)
    local.set(dataB, 30 + nameB.length)
    localParts.push(local)
    const cen = new Uint8Array(46 + nameB.length)
    const cdv = new DataView(cen.buffer)
    cdv.setUint32(0, 0x02014b50, true)
    cdv.setUint16(4, 20, true)
    cdv.setUint16(6, 20, true)
    cdv.setUint16(8, 0, true)
    cdv.setUint16(10, 0, true)
    cdv.setUint16(12, 0, true)
    cdv.setUint16(14, 0x21, true)
    cdv.setUint32(16, crc, true)
    cdv.setUint32(20, dataB.length, true)
    cdv.setUint32(24, dataB.length, true)
    cdv.setUint16(28, nameB.length, true)
    cdv.setUint16(30, 0, true)
    cdv.setUint16(32, 0, true)
    cdv.setUint16(34, 0, true)
    cdv.setUint16(36, 0, true)
    cdv.setUint32(38, 0, true)
    cdv.setUint32(42, offset, true)
    cen.set(nameB, 46)
    centralParts.push(cen)
    offset += local.length
  })
  const cdSize = centralParts.reduce((s, c) => s + c.length, 0)
  const eocd = new Uint8Array(22)
  const edv = new DataView(eocd.buffer)
  edv.setUint32(0, 0x06054b50, true)
  edv.setUint16(4, 0, true)
  edv.setUint16(6, 0, true)
  edv.setUint16(8, files.length, true)
  edv.setUint16(10, files.length, true)
  edv.setUint32(12, cdSize, true)
  edv.setUint32(16, offset, true)
  edv.setUint16(20, 0, true)
  const out = new Uint8Array(offset + cdSize + 22)
  let pos = 0
  localParts.forEach((p) => { out.set(p, pos); pos += p.length })
  centralParts.forEach((c) => { out.set(c, pos); pos += c.length })
  out.set(eocd, pos)
  return out
}

function exportXlsx(editor, docId) {
  const aoa = htmlToAoa(editor.getHTML())
  if (!aoa.length) { ElMessage.warning('文档为空，无可导出内容'); return }
  const enc = new TextEncoder()
  const zip = zipStore([
    { name: '[Content_Types].xml', data: enc.encode(XLSX_CONTENT_TYPES) },
    { name: '_rels/.rels', data: enc.encode(XLSX_ROOT_RELS) },
    { name: 'xl/workbook.xml', data: enc.encode(XLSX_WORKBOOK) },
    { name: 'xl/_rels/workbook.xml.rels', data: enc.encode(XLSX_WORKBOOK_RELS) },
    { name: 'xl/worksheets/sheet1.xml', data: enc.encode(aoaToSheetXml(aoa)) },
  ])
  downloadBlob(safeName(docId ? ('doc-' + docId) : 'shared-doc') + '.xlsx',
    new Blob([zip], { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' }))
  ElMessage.success('已导出 Excel')
}

/** 导出 URL 清洗：拒绝 javascript: 协议，转义会破坏 Markdown 链接语法的括号与空白 */
function safeUrl(u) {
  const s = String(u || '')
  if (/^\s*javascript:/i.test(s)) return ''
  return s.replace(/[()\s]/g, (c) => encodeURIComponent(c))
}

export function exportDocument(command, editor, docId) {
  if (!editor) { ElMessage.warning('编辑器尚未就绪，稍后再试'); return }
  try {
    const html = editor.getHTML()
    const base = safeName(docId ? ('doc-' + docId) : 'shared-doc')
    if (command === 'xlsx') { exportXlsx(editor, docId); return }
    if (command === 'html') {
      const full = '<!DOCTYPE html>\n<html>\n<head><meta charset="utf-8"><title>' + xmlEscape(base) +
        '</title></head>\n<body>\n' + html + '\n</body>\n</html>'
      download(base + '.html', full, 'text/html')
      ElMessage.success('已导出 HTML')
    } else {
      download(base + '.md', htmlToMarkdown(html), 'text/markdown')
      ElMessage.success('已导出 Markdown')
    }
  } catch (e) {
    console.warn('导出失败：', e)
    ElMessage.error('导出失败：' + ((e && e.message) || '未知错误'))
  }
}


export {
  DIAGRAM_SEL, safeName, download,
  htmlToMarkdown, xmlEscape, colLetter, htmlToAoa, aoaToSheetXml, crc32, zipStore
}
