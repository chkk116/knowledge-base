/**
 * 文档解析（纯前端、零后端）：PDF / Word(.docx) / PPT(.pptx) / Excel(.xlsx) → 文本
 *
 * 设计原则（2026-10-07 与用户确认）：
 *   文件预处理（提取 / 转换）是**确定性任务**，一律在本地做 —— 零 token、不上传原文件（隐私）、
 *   不受网络影响；只有「理解 / 生成」才交给模型。所以这里**不**用 AI 调工具。
 *
 * PDF 采用 A+B 组合：
 *   · 文字版页面 → pdf.js 提取纯文本（快、免费）
 *   · 扫描件页面（提取到的文字过少）→ 渲染成图片，交给多模态模型「看」
 * 依赖全部动态 import：不进主包，只有真正上传这类文件时才加载。
 */

const PDF_MAX_PAGES = 30      // 单次最多解析页数（防超大 PDF 卡死 / 爆 token）
const PDF_MIN_TEXT = 20       // 一页文字少于此值，视为扫描件 → 转图片
const IMG_MAX_EDGE = 1280     // 扫描件页转图片时的最长边

let pdfjsPromise = null
function loadPdfjs() {
  if (!pdfjsPromise) {
    pdfjsPromise = (async () => {
      const pdfjs = await import('pdfjs-dist')
      // worker 固定用 public/vendor/pdf.worker.min.js：.mjs 会被 nginx / Capacitor 当作
      // application/octet-stream → 浏览器以「模块脚本」严格校验 MIME 后拒绝加载
      // （实测报 "Expected a JavaScript-or-Wasm module script"）。pdfjs 以 type:'module' 加载，扩展名无关。
      pdfjs.GlobalWorkerOptions.workerSrc = `${import.meta.env.BASE_URL}vendor/pdf.worker.min.js`
      return pdfjs
    })()
  }
  return pdfjsPromise
}

async function pageToImage(page) {
  const base = page.getViewport({ scale: 2 })
  const scale = Math.min(1, IMG_MAX_EDGE / Math.max(base.width, base.height))
  const viewport = page.getViewport({ scale: 2 * scale })
  const canvas = document.createElement('canvas')
  canvas.width = Math.max(1, Math.round(viewport.width))
  canvas.height = Math.max(1, Math.round(viewport.height))
  const ctx = canvas.getContext('2d')
  ctx.fillStyle = '#fff'
  ctx.fillRect(0, 0, canvas.width, canvas.height)
  await page.render({ canvasContext: ctx, viewport }).promise
  return canvas.toDataURL('image/jpeg', 0.82)
}

/** PDF → { text, images, pages } */
export async function parsePdf(file, onProgress) {
  const pdfjs = await loadPdfjs()
  const data = new Uint8Array(await file.arrayBuffer())
  const doc = await pdfjs.getDocument({ data }).promise
  const total = doc.numPages
  const pages = Math.min(total, PDF_MAX_PAGES)
  const texts = []
  const images = []
  for (let i = 1; i <= pages; i++) {
    if (onProgress) onProgress(i / pages, `解析 PDF 第 ${i}/${pages} 页…`)
    const page = await doc.getPage(i)
    const tc = await page.getTextContent()
    const t = tc.items.map((it) => it.str).join(' ').replace(/\s+/g, ' ').trim()
    if (t.length >= PDF_MIN_TEXT) texts.push(`【第 ${i} 页】\n${t}`)
    else images.push(await pageToImage(page)) // 扫描件页 → 交给多模态
  }
  const tail = total > pages ? `\n\n（PDF 共 ${total} 页，已解析前 ${pages} 页）` : ''
  return { text: texts.join('\n\n') + tail, images, pages: total }
}

/** Word(.docx) → { text, images } */
export async function parseDocx(file) {
  const mod = await import('mammoth/mammoth.browser')
  const mammoth = mod.default || mod
  const buf = await file.arrayBuffer()
  const { value } = await mammoth.extractRawText({ arrayBuffer: buf })
  return { text: (value || '').trim(), images: [] }
}

/** 从 OOXML(zip) 的 XML 里抽 <a:t> / <w:t> / <t> 文本 */
async function xmlTexts(zip, path) {
  const f = zip.file(path)
  if (!f) return ''
  const xml = await f.async('string')
  const out = []
  // 负向断言精确匹配 <a:t>/<w:t>/<t>：<a:tbl>、<w:tab/> 等会被旧正则误配并跨元素抓取内容
  const re = /<(?:a:|w:)?t(?![A-Za-z])[^>]*>([\s\S]*?)<\/(?:a:|w:)?t>/g
  let m
  while ((m = re.exec(xml))) out.push(m[1])
  return out.join(' ').replace(/\s+/g, ' ').trim()
}

/** PPT(.pptx) → { text, images } */
export async function parsePptx(file) {
  const JSZip = (await import('jszip')).default
  const zip = await JSZip.loadAsync(await file.arrayBuffer())
  const slides = Object.keys(zip.files)
    .filter((n) => /^ppt\/slides\/slide\d+\.xml$/.test(n))
    .sort((a, b) => parseInt(a.match(/(\d+)/)[1], 10) - parseInt(b.match(/(\d+)/)[1], 10))
  const parts = []
  for (let i = 0; i < slides.length; i++) {
    const t = await xmlTexts(zip, slides[i])
    if (t) parts.push(`【第 ${i + 1} 页】\n${t}`)
  }
  return { text: parts.join('\n\n'), images: [] }
}

/** Excel(.xlsx) → { text, images } */
export async function parseXlsx(file) {
  const JSZip = (await import('jszip')).default
  const zip = await JSZip.loadAsync(await file.arrayBuffer())
  const shared = []
  const sst = zip.file('xl/sharedStrings.xml')
  if (sst) {
    const xml = await sst.async('string')
    const re = /<si>([\s\S]*?)<\/si>/g
    let m
    while ((m = re.exec(xml))) {
      const ts = [...m[1].matchAll(/<t[^>]*>([\s\S]*?)<\/t>/g)].map((x) => x[1])
      shared.push(ts.join(''))
    }
  }
  const sheets = Object.keys(zip.files)
    .filter((n) => /^xl\/worksheets\/sheet\d+\.xml$/.test(n))
    .sort((a, b) => (parseInt(a.match(/(\d+)/)?.[1] || '0', 10)) - (parseInt(b.match(/(\d+)/)?.[1] || '0', 10)))
  const parts = []
  for (let s = 0; s < sheets.length; s++) {
    const xml = await zip.file(sheets[s]).async('string')
    const rows = [...xml.matchAll(/<row[^>]*>([\s\S]*?)<\/row>/g)]
      .map((r) =>
        [...r[1].matchAll(/<c[^>]*?(?: t="(\w+)")?[^>]*>(?:<v>([\s\S]*?)<\/v>)?<\/c>/g)]
          .map((c) => (c[1] === 's' ? shared[Number(c[2])] || '' : c[2] || ''))
          .filter(Boolean)
          .join('\t')
      )
      .filter((r) => r.trim())
    if (rows.length) parts.push(`【工作表 ${s + 1}】\n${rows.join('\n')}`)
  }
  return { text: parts.join('\n\n'), images: [] }
}

/** 是否为可解析的文档类型（旧格式 .doc/.ppt/.xls 不支持，需先另存为新格式） */
export const DOC_EXTS = /\.(pdf|docx|pptx|xlsx)$/i

/** 统一入口：按扩展名分派；不支持返回 null */
/** OOXML 实体解码（解码后按纯文本处理，不回灌 HTML） */
function decodeXmlEntities(s) {
  return String(s || '')
    .replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'").replace(/&#x([0-9a-fA-F]+);/g, (_, h) => String.fromCodePoint(parseInt(h, 16)))
    .replace(/&#(\d+);/g, (_, d) => String.fromCodePoint(parseInt(d, 10)))
    .replace(/&amp;/g, '&')
}
const PARSE_TEXT_MAX = 50000   // 单文档解析文本上限：防压缩炸弹/超大文档把几十万字符喂给模型

export async function parseDocument(file, onProgress) {
  const n = (file.name || '').toLowerCase()
  try {
    if (n.endsWith('.pdf')) return clipText(await parsePdf(file, onProgress))
    if (n.endsWith('.docx')) return clipText(await parseDocx(file))
    if (n.endsWith('.pptx')) return clipText(await parsePptx(file))
    if (n.endsWith('.xlsx')) return clipText(await parseXlsx(file))
    return null
  } catch (e) {
    console.warn('[docParse] 解析失败：', (e && e.message) || e)
    return null
  }
  function clipText(t) { return t && t.length > PARSE_TEXT_MAX ? t.slice(0, PARSE_TEXT_MAX) + '\n…（内容过长已截断）' : t }
  if (false) return parsePdf(file, onProgress)
  if (n.endsWith('.docx')) return parseDocx(file)
  if (n.endsWith('.pptx')) return parsePptx(file)
  if (n.endsWith('.xlsx')) return parseXlsx(file)
  return null
}
