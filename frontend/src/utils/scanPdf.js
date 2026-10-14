/**
 * 本地 PDF 生成（零依赖，离线可用）——用于「多页扫描导出 PDF」。
 *
 * 为什么不用 jsPDF：App 是内置资源 + 离线优先，多引一个几百 KB 的库不值当；
 * 这里只做「一页一张 JPEG」这一件事，手写最小 PDF 结构即可（DCTDecode 直接内嵌 JPEG）。
 *
 * 用法：
 *   const pages = await Promise.all(files.map(prepareImage))
 *   const blob = buildPdfBlob(pages)
 */

import { createEncryptor, toHexString } from './pdfCrypto'

/** 把图片文件压成适合放进 PDF 的 JPEG（限制最长边，控制体积） */
export function prepareImage(file, maxSide = 1600, quality = 0.82) {
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(file)
    const img = new Image()
    img.onload = () => {
      try {
        const scale = Math.min(1, maxSide / Math.max(img.width, img.height))
        const w = Math.max(1, Math.round(img.width * scale))
        const h = Math.max(1, Math.round(img.height * scale))
        const cv = document.createElement('canvas')
        cv.width = w; cv.height = h
        const ctx = cv.getContext('2d')
        ctx.fillStyle = '#fff'          // 透明 PNG 转 JPEG 会变黑，先铺白底
        ctx.fillRect(0, 0, w, h)
        ctx.drawImage(img, 0, 0, w, h)
        cv.toBlob((blob) => {
          URL.revokeObjectURL(url)
          if (!blob) { reject(new Error('图片编码失败')); return }
          // 回调不能是 async：其返回的 Promise 无人链接，arrayBuffer 拒绝会变成
          // unhandled rejection 且外层 Promise 永挂（第四轮审查 H7）
          blob.arrayBuffer()
            .then((buf) => resolve({ bytes: new Uint8Array(buf), w, h, url: URL.createObjectURL(blob), size: blob.size }))
            .catch((e) => reject(e instanceof Error ? e : new Error('图片编码失败')))
        }, 'image/jpeg', quality)
      } catch (e) {
        URL.revokeObjectURL(url)
        reject(e)
      }
    }
    img.onerror = () => { URL.revokeObjectURL(url); reject(new Error('图片解码失败')) }
    img.src = url
  })
}

const A4_W = 595.28
const A4_H = 841.89

const enc = (s) => new TextEncoder().encode(s)

/** 默认权限位（ISO 32000-1 Table 22）：-60 = 仅允许打印+复制内容，禁改禁注（第四轮审查：魔数提常量） */
const DEFAULT_PERMISSIONS = -60

/** %PDF 头 + 高位二进制标记（按字节直写，避免 TextEncoder 把 \xE2… 转成 UTF-8 双字节） */
const PDF_HEADER_BYTES = new Uint8Array([
  0x25, 0x50, 0x44, 0x46, 0x2d, 0x31, 0x2e, 0x34, 0x0a,   // "%PDF-1.4\n"
  0x25, 0xE2, 0xE3, 0xCF, 0xD3, 0x0a,                     // "%âãÏÓ\n"
])

/** 用「一页一张 JPEG」组装最小可用的 PDF Blob */
export function buildPdfBlob(pages, opts = {}) {
  if (!pages || !pages.length) throw new Error('没有可导出的页面')
  // 页数据先验（第四轮审查）：NaN/0 几何或缺字节会产出结构损坏的 PDF 而不是报错
  pages.forEach((p, i) => {
    if (!p || !p.bytes || !(p.w > 0) || !(p.h > 0)) {
      throw new Error(`第 ${i + 1} 页数据无效，无法导出`)
    }
  })
  const chunks = []
  let len = 0
  const push = (u8) => { chunks.push(u8); len += u8.length }
  const offsets = []                       // 每个对象的字节偏移（xref 用）

  const startObj = (num, body) => {
    offsets[num] = len
    push(enc(`${num} 0 obj\n${body}\n`))
  }

  // 口令非空则启用标准加密（RC4 40-bit）
  const crypt = createEncryptor(opts.password, opts.ownerPassword, opts.permissions ?? DEFAULT_PERMISSIONS)
  const encStream = (num, gen, bytes) => (crypt ? crypt.encryptObject(num, gen, bytes) : bytes)

  push(PDF_HEADER_BYTES)

  // 对象编号规划：1=Catalog 2=Pages，之后每页 3 个对象（Page / Contents / Image）
  const pageObjNums = pages.map((_, i) => 3 + i * 3)
  const kids = pageObjNums.map((n) => `${n} 0 R`).join(' ')

  startObj(1, `<< /Type /Catalog /Pages 2 0 R >>`)
  startObj(2, `<< /Type /Pages /Kids [${kids}] /Count ${pages.length} >>`)

  pages.forEach((p, i) => {
    const pageNum = 3 + i * 3
    const contentNum = pageNum + 1
    const imgNum = pageNum + 2

    // 图片按「contain」缩放到 A4，居中
    const s = Math.min(A4_W / p.w, A4_H / p.h)
    const dw = p.w * s
    const dh = p.h * s
    const dx = (A4_W - dw) / 2
    const dy = (A4_H - dh) / 2
    const content = `q ${dw.toFixed(2)} 0 0 ${dh.toFixed(2)} ${dx.toFixed(2)} ${dy.toFixed(2)} cm /Im0 Do Q`

    startObj(pageNum, `<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${A4_W} ${A4_H}] ` +
      `/Resources << /XObject << /Im0 ${imgNum} 0 R >> >> /Contents ${contentNum} 0 R >>`)

    const cBytes = encStream(contentNum, 0, enc(content))
    offsets[contentNum] = len
    push(enc(`${contentNum} 0 obj\n<< /Length ${cBytes.length} >>\nstream\n`))
    push(cBytes)
    push(enc('\nendstream\nendobj\n'))

    const iBytes = encStream(imgNum, 0, p.bytes)
    offsets[imgNum] = len
    push(enc(`${imgNum} 0 obj\n<< /Type /XObject /Subtype /Image /Width ${p.w} /Height ${p.h} ` +
      `/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ${iBytes.length} >>\nstream\n`))
    push(iBytes)
    push(enc('\nendstream\nendobj\n'))
  })

  // 加密字典（若有）
  let encryptNum = 0
  if (crypt) {
    encryptNum = 3 + pages.length * 3
    startObj(encryptNum, crypt.dict)
  }

  // xref
  const total = 3 + pages.length * 3 + (crypt ? 1 : 0)
  const xrefStart = len
  let xref = `xref\n0 ${total}\n0000000000 65535 f \n`
  for (let n = 1; n < total; n++) {
    const off = offsets[n]
    // 严格校验（第四轮审查）：静默写 0 会产出坏 xref，宁可报内部错误
    if (typeof off !== 'number') throw new Error(`内部错误：对象 ${n} 缺少字节偏移`)
    xref += String(off).padStart(10, '0') + ' 00000 n \n'
  }
  push(enc(xref))

  const idHex = crypt ? toHexString(crypt.idBytes) : ''
  const idPart = crypt ? ` /ID [<${idHex}> <${idHex}>]` : ''
  const encPart = crypt ? ` /Encrypt ${encryptNum} 0 R` : ''
  push(enc(`trailer\n<< /Size ${total} /Root 1 0 R${encPart}${idPart} >>\nstartxref\n${xrefStart}\n%%EOF\n`))

  return new Blob(chunks, { type: 'application/pdf' })
}

/** 释放 prepareImage 产生的临时 URL */
export function revokePages(pages) {
  for (const p of pages || []) {
    if (p && p.url && String(p.url).startsWith('blob:')) URL.revokeObjectURL(p.url)
  }
}
