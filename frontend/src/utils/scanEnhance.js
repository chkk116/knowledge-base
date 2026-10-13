/**
 * 扫描增强核心（零依赖，纯 Canvas，离线可用）
 *
 * 借鉴「全能扫描王」的成像能力：
 *   ① 旋转 / 自由裁剪
 *   ② 滤镜：原图 / 增强 / 灰度 / 黑白（自适应二值化）/ 去阴影
 *   ③ 四点透视矫正（拍歪的文档拉正）
 *   ④ 文档四角自动检测（简化版：沿对角线找强边缘）
 *
 * 约定：所有处理函数「输入 canvas → 输出新 canvas」，不改原图，便于撤销。
 * 坐标系：裁剪框 / 角点均用**归一化坐标**（0~1），与画布像素尺寸解耦。
 */

/** 从 File / Blob / URL / Image / Canvas 拿到 HTMLImageElement */
export function loadImage(src) {
  return new Promise((resolve, reject) => {
    if (src instanceof HTMLImageElement) {
      if (src.complete && src.naturalWidth) { resolve(src); return }
      src.onload = () => resolve(src)
      src.onerror = () => reject(new Error('图片加载失败'))
      return
    }
    let url = src
    let revoke = false
    if (src instanceof Blob) { url = URL.createObjectURL(src); revoke = true }
    const img = new Image()
    img.onload = () => { if (revoke) URL.revokeObjectURL(url); resolve(img) }
    img.onerror = () => { if (revoke) URL.revokeObjectURL(url); reject(new Error('图片解码失败')) }
    img.src = url
  })
}

/** 新建一个同尺寸（或指定尺寸）的 canvas 并铺白底 */
export function makeCanvas(w, h) {
  const cv = document.createElement('canvas')
  cv.width = Math.max(1, Math.round(w))
  cv.height = Math.max(1, Math.round(h))
  const ctx = cv.getContext('2d')
  ctx.fillStyle = '#fff'
  ctx.fillRect(0, 0, cv.width, cv.height)
  return cv
}

/** 把图片按最长边缩放后画进新 canvas（扫描统一入口，控制内存） */
export async function toCanvas(src, maxSide = 2000) {
  const img = await loadImage(src)
  const scale = Math.min(1, maxSide / Math.max(img.naturalWidth || img.width, img.naturalHeight || img.height))
  const w = Math.round((img.naturalWidth || img.width) * scale)
  const h = Math.round((img.naturalHeight || img.height) * scale)
  const cv = makeCanvas(w, h)
  cv.getContext('2d').drawImage(img, 0, 0, w, h)
  return cv
}

/** 深拷贝 canvas（撤销栈用） */
export function cloneCanvas(src) {
  const cv = makeCanvas(src.width, src.height)
  cv.getContext('2d').drawImage(src, 0, 0)
  return cv
}

/** 旋转：deg 为 90 的倍数时无插值损失；非 90 倍数走双线性 */
export function rotateCanvas(src, deg) {
  const rad = (deg * Math.PI) / 180
  const cos = Math.abs(Math.cos(rad))
  const sin = Math.abs(Math.sin(rad))
  const w = Math.round(src.width * cos + src.height * sin)
  const h = Math.round(src.width * sin + src.height * cos)
  const cv = makeCanvas(w, h)
  const ctx = cv.getContext('2d')
  ctx.translate(w / 2, h / 2)
  ctx.rotate(rad)
  ctx.drawImage(src, -src.width / 2, -src.height / 2)
  return cv
}

/** 按归一化矩形裁剪（x/y/w/h ∈ 0~1） */
export function cropCanvas(src, rect) {
  const x = Math.max(0, Math.min(1, rect.x)) * src.width
  const y = Math.max(0, Math.min(1, rect.y)) * src.height
  const w = Math.max(1, Math.min(1 - rect.x, rect.w) * src.width)
  const h = Math.max(1, Math.min(1 - rect.y, rect.h) * src.height)
  const cv = makeCanvas(w, h)
  cv.getContext('2d').drawImage(src, x, y, w, h, 0, 0, cv.width, cv.height)
  return cv
}

/* ============================ 滤镜 ============================ */

export const FILTERS = [
  { key: 'original', label: '原图' },
  { key: 'enhance', label: '增强' },
  { key: 'gray', label: '灰度' },
  { key: 'bw', label: '黑白' },
  { key: 'shadow', label: '去阴影' },
]

/** 灰度值数组（0~255） */
function toGrayArray(data, w, h) {
  const g = new Float32Array(w * h)
  for (let i = 0, p = 0; i < g.length; i++, p += 4) {
    g[i] = 0.299 * data[p] + 0.587 * data[p + 1] + 0.114 * data[p + 2]
  }
  return g
}

/** 对比度/亮度拉伸（按分位数截断，避免被少量极值拖累） */
function stretch(g) {
  const hist = new Uint32Array(256)
  for (let i = 0; i < g.length; i++) hist[Math.max(0, Math.min(255, g[i] | 0))]++
  const total = g.length
  const cut = total * 0.005
  let lo = 0, hi = 255, acc = 0
  for (let v = 0; v < 256; v++) { acc += hist[v]; if (acc > cut) { lo = v; break } }
  acc = 0
  for (let v = 255; v >= 0; v--) { acc += hist[v]; if (acc > cut) { hi = v; break } }
  if (hi <= lo) return g
  const k = 255 / (hi - lo)
  for (let i = 0; i < g.length; i++) g[i] = (g[i] - lo) * k
  return g
}

/** 盒式模糊（分离式，用于自适应阈值与背景估计） */
function boxBlur(g, w, h, radius) {
  const r = Math.max(1, radius | 0)
  const tmp = new Float32Array(w * h)
  const out = new Float32Array(w * h)
  const win = r * 2 + 1
  // 横向
  for (let y = 0; y < h; y++) {
    let sum = 0
    const row = y * w
    for (let x = -r; x <= r; x++) sum += g[row + Math.max(0, Math.min(w - 1, x))]
    for (let x = 0; x < w; x++) {
      tmp[row + x] = sum / win
      const add = g[row + Math.min(w - 1, x + r + 1)]
      const sub = g[row + Math.max(0, x - r)]
      sum += add - sub
    }
  }
  // 纵向
  for (let x = 0; x < w; x++) {
    let sum = 0
    for (let y = -r; y <= r; y++) sum += tmp[Math.max(0, Math.min(h - 1, y)) * w + x]
    for (let y = 0; y < h; y++) {
      out[y * w + x] = sum / win
      const add = tmp[Math.min(h - 1, y + r + 1) * w + x]
      const sub = tmp[Math.max(0, y - r) * w + x]
      sum += add - sub
    }
  }
  return out
}

/**
 * 应用滤镜，返回新 canvas。
 * preset: original | enhance | gray | bw | shadow
 */
export function applyFilter(src, preset) {
  if (!preset || preset === 'original') return cloneCanvas(src)
  const w = src.width
  const h = src.height
  const cv = makeCanvas(w, h)
  const ctx = cv.getContext('2d')
  const imgData = src.getContext('2d').getImageData(0, 0, w, h)
  const d = imgData.data
  const g = toGrayArray(d, w, h)

  if (preset === 'enhance') {
    // 亮度/对比度提升 + 轻微饱和：文档字迹更实、底色更白
    const s = stretch(g)
    const radius = Math.max(8, Math.round(Math.max(w, h) / 40))
    const bg = boxBlur(s, w, h, radius)
    for (let i = 0, p = 0; i < g.length; i++, p += 4) {
      // 以背景为参考做局部归一化（压掉光照不均），再回到原色相
      const norm = Math.max(0, Math.min(255, (s[i] / Math.max(1, bg[i])) * 235))
      const ratio = norm / Math.max(1, s[i])
      d[p] = Math.max(0, Math.min(255, d[p] * ratio))
      d[p + 1] = Math.max(0, Math.min(255, d[p + 1] * ratio))
      d[p + 2] = Math.max(0, Math.min(255, d[p + 2] * ratio))
    }
    ctx.putImageData(imgData, 0, 0)
    return cv
  }

  if (preset === 'gray') {
    const s = stretch(g)
    for (let i = 0, p = 0; i < g.length; i++, p += 4) {
      const v = Math.max(0, Math.min(255, s[i]))
      d[p] = d[p + 1] = d[p + 2] = v
    }
    ctx.putImageData(imgData, 0, 0)
    return cv
  }

  if (preset === 'bw') {
    // Bradley 自适应二值化：局部均值 × (1 - t)，对阴影/渐变鲁棒
    const s = stretch(g)
    const radius = Math.max(6, Math.round(Math.max(w, h) / 60))
    const mean = boxBlur(s, w, h, radius)
    const t = 0.15
    for (let i = 0, p = 0; i < g.length; i++, p += 4) {
      const v = s[i] > mean[i] * (1 - t) ? 255 : 0
      d[p] = d[p + 1] = d[p + 2] = v
    }
    ctx.putImageData(imgData, 0, 0)
    return cv
  }

  if (preset === 'shadow') {
    // 去阴影：大半径背景估计 → 除法归一化 → 轻度拉伸
    const radius = Math.max(12, Math.round(Math.max(w, h) / 12))
    const bg = boxBlur(g, w, h, radius)
    const out = new Float32Array(g.length)
    for (let i = 0; i < g.length; i++) out[i] = Math.max(0, Math.min(255, (g[i] / Math.max(1, bg[i])) * 245))
    const s = stretch(out)
    for (let i = 0, p = 0; i < g.length; i++, p += 4) {
      const v = Math.max(0, Math.min(255, s[i]))
      d[p] = d[p + 1] = d[p + 2] = v
    }
    ctx.putImageData(imgData, 0, 0)
    return cv
  }

  return cloneCanvas(src)
}

/* ======================= 四点透视矫正 ======================= */

/**
 * 解 8 元线性方程组（高斯消元，带部分主元），求透视变换矩阵。
 * 由 4 组对应点 (x,y) → (u,v) 建立：
 *   u = (h0·x + h1·y + h2) / (h6·x + h7·y + 1)
 *   v = (h3·x + h4·y + h5) / (h6·x + h7·y + 1)
 * 返回 [h0..h7]，无解返回 null。
 */
function solveHomography(src, dst) {
  const A = []
  const b = []
  for (let i = 0; i < 4; i++) {
    const { x, y } = src[i]
    const { x: u, y: v } = dst[i]
    A.push([x, y, 1, 0, 0, 0, -u * x, -u * y]); b.push(u)
    A.push([0, 0, 0, x, y, 1, -v * x, -v * y]); b.push(v)
  }
  const n = 8
  for (let col = 0; col < n; col++) {
    let piv = col
    for (let r = col + 1; r < n; r++) if (Math.abs(A[r][col]) > Math.abs(A[piv][col])) piv = r
    if (Math.abs(A[piv][col]) < 1e-10) return null
    if (piv !== col) { const t = A[piv]; A[piv] = A[col]; A[col] = t; const tb = b[piv]; b[piv] = b[col]; b[col] = tb }
    const p = A[col][col]
    for (let r = 0; r < n; r++) {
      if (r === col) continue
      const f = A[r][col] / p
      if (!f) continue
      for (let c = col; c < n; c++) A[r][c] -= f * A[col][c]
      b[r] -= f * b[col]
    }
  }
  const h = new Array(n)
  for (let i = 0; i < n; i++) h[i] = b[i] / A[i][i]
  return h
}

/** 双线性采样（越界取白） */
function sampleBilinear(data, w, h, x, y, out) {
  if (x < 0 || y < 0 || x > w - 1 || y > h - 1) {
    out[0] = out[1] = out[2] = 255
    return
  }
  const x0 = x | 0, y0 = y | 0
  const x1 = Math.min(w - 1, x0 + 1), y1 = Math.min(h - 1, y0 + 1)
  const fx = x - x0, fy = y - y0
  for (let c = 0; c < 3; c++) {
    const p00 = data[(y0 * w + x0) * 4 + c], p10 = data[(y0 * w + x1) * 4 + c]
    const p01 = data[(y1 * w + x0) * 4 + c], p11 = data[(y1 * w + x1) * 4 + c]
    out[c] = (p00 * (1 - fx) + p10 * fx) * (1 - fy) + (p01 * (1 - fx) + p11 * fx) * fy
  }
}

/**
 * 四点透视矫正：把 src 上由 corners 围成的四边形拉正成矩形。
 * corners = [{x,y}×4] 归一化坐标，顺序 TL → TR → BR → BL。
 * 输出尺寸按四边形最长边估算（保持纵横比）。
 */
export function perspectiveWarp(src, corners) {
  const w = src.width
  const h = src.height
  const pts = corners.map((p) => ({ x: p.x * w, y: p.y * h }))
  const [tl, tr, br, bl] = pts

  const dist = (a, b) => Math.hypot(a.x - b.x, a.y - b.y)
  const outW = Math.max(16, Math.round(Math.max(dist(tl, tr), dist(bl, br))))
  const outH = Math.max(16, Math.round(Math.max(dist(tl, bl), dist(tr, br))))

  const dstPts = [{ x: 0, y: 0 }, { x: outW - 1, y: 0 }, { x: outW - 1, y: outH - 1 }, { x: 0, y: outH - 1 }]
  // 需要「目标 → 源」的映射，所以把 dst 当输入、pts 当输出
  const H = solveHomography(dstPts, pts)
  if (!H) return cloneCanvas(src)

  const srcData = src.getContext('2d').getImageData(0, 0, w, h).data
  const cv = makeCanvas(outW, outH)
  const ctx = cv.getContext('2d')
  const out = ctx.createImageData(outW, outH)
  const od = out.data
  const px = [0, 0, 0]

  for (let y = 0; y < outH; y++) {
    for (let x = 0; x < outW; x++) {
      const den = H[6] * x + H[7] * y + 1
      const sx = (H[0] * x + H[1] * y + H[2]) / den
      const sy = (H[3] * x + H[4] * y + H[5]) / den
      sampleBilinear(srcData, w, h, sx, sy, px)
      const p = (y * outW + x) * 4
      od[p] = px[0]; od[p + 1] = px[1]; od[p + 2] = px[2]; od[p + 3] = 255
    }
  }
  ctx.putImageData(out, 0, 0)
  return cv
}

/* ======================= 文档四角自动检测 ======================= */

/**
 * 简化版文档边缘检测：从画面四角沿对角线向内找「强梯度」像素作为近似角点。
 * 适用场景：文档大致居中、占据画面主体（扫描的典型用法）。
 * 检测不理想时用户可在编辑器里手动拖四角。
 * 返回 [{x,y}×4] 归一化坐标（TL/TR/BR/BL）。
 */
/** Otsu 阈值：在灰度直方图上最大化类间方差（自动区分纸面与背景） */
function otsu(gray) {
  const hist = new Uint32Array(256)
  for (let i = 0; i < gray.length; i++) hist[Math.max(0, Math.min(255, gray[i] | 0))]++
  const total = gray.length
  let sumAll = 0
  for (let v = 0; v < 256; v++) sumAll += v * hist[v]
  let sumB = 0, wB = 0, best = -1, thr = 128
  for (let v = 0; v < 256; v++) {
    wB += hist[v]
    if (!wB) continue
    const wF = total - wB
    if (!wF) break
    sumB += v * hist[v]
    const mB = sumB / wB
    const mF = (sumAll - sumB) / wF
    const between = wB * wF * (mB - mF) * (mB - mF)
    if (between > best) { best = between; thr = v }
  }
  return thr
}

export function detectCorners(src) {
  const MAX = 420
  const scale = Math.min(1, MAX / Math.max(src.width, src.height))
  const w = Math.max(32, Math.round(src.width * scale))
  const h = Math.max(32, Math.round(src.height * scale))
  const small = makeCanvas(w, h)
  small.getContext('2d').drawImage(src, 0, 0, w, h)
  const d = small.getContext('2d').getImageData(0, 0, w, h).data

  const gray = new Float32Array(w * h)
  for (let i = 0, p = 0; i < gray.length; i++, p += 4) gray[i] = 0.299 * d[p] + 0.587 * d[p + 1] + 0.114 * d[p + 2]

  const margin = 0.02
  const fallback = [
    { x: margin, y: margin }, { x: 1 - margin, y: margin },
    { x: 1 - margin, y: 1 - margin }, { x: margin, y: 1 - margin },
  ]

  // Otsu 分割亮色纸面（比「沿对角线找强边缘」稳：后者会被文字/阴影带偏）
  const thr = otsu(gray)
  // 5×5 邻域至少 17 个亮点才算「纸面」，滤掉噪点与细笔画
  const ok = new Uint8Array(w * h)
  const R = 2
  for (let y = R; y < h - R; y++) {
    for (let x = R; x < w - R; x++) {
      let c = 0
      for (let dy = -R; dy <= R; dy++) {
        for (let dx = -R; dx <= R; dx++) if (gray[(y + dy) * w + (x + dx)] > thr) c++
      }
      if (c >= 17) ok[y * w + x] = 1
    }
  }
  let count = 0
  for (let i = 0; i < ok.length; i++) count += ok[i]
  if (count < w * h * 0.05) return fallback     // 亮点太少 → 判为检测失败

  // 四方向极值点（凸四边形的极值点即角点）
  let minSum = Infinity, maxSum = -Infinity, minDiff = Infinity, maxDiff = -Infinity
  let pTL = null, pBR = null, pBL = null, pTR = null
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      if (!ok[y * w + x]) continue
      const s = x + y
      const df = x - y
      if (s < minSum) { minSum = s; pTL = { x, y } }
      if (s > maxSum) { maxSum = s; pBR = { x, y } }
      if (df < minDiff) { minDiff = df; pBL = { x, y } }
      if (df > maxDiff) { maxDiff = df; pTR = { x, y } }
    }
  }
  if (!pTL || !pTR || !pBR || !pBL) return fallback

  const pts = [pTL, pTR, pBR, pBL].map((p) => ({ x: p.x / w, y: p.y / h }))
  // 面积校验：过小说明只框到一小块（如文字块），整体回退
  const area = Math.abs(
    (pts[0].x * pts[1].y - pts[1].x * pts[0].y) +
    (pts[1].x * pts[2].y - pts[2].x * pts[1].y) +
    (pts[2].x * pts[3].y - pts[3].x * pts[2].y) +
    (pts[3].x * pts[0].y - pts[0].x * pts[3].y)
  ) / 2
  if (area < 0.2) return fallback
  return pts
}

/* ======================= 输出 ======================= */

/** canvas → JPEG 字节 + 预览 URL（结构对齐 scanPdf.prepareImage 的产物） */
export function toJpegPage(cv, quality = 0.85) {
  return new Promise((resolve, reject) => {
    cv.toBlob(async (blob) => {
      if (!blob) { reject(new Error('图片编码失败')); return }
      const bytes = new Uint8Array(await blob.arrayBuffer())
      resolve({ bytes, w: cv.width, h: cv.height, url: URL.createObjectURL(blob), size: blob.size })
    }, 'image/jpeg', quality)
  })
}

/** 多页纵向拼接成长图（页间距 gap 像素，白底） */
export function buildLongImage(canvases, gap = 16) {
  if (!canvases.length) throw new Error('没有可导出的页面')
  const width = Math.max(...canvases.map((c) => c.width))
  const height = canvases.reduce((s, c) => s + c.height, 0) + gap * (canvases.length - 1)
  const cv = makeCanvas(width, height)
  const ctx = cv.getContext('2d')
  let y = 0
  for (const c of canvases) {
    ctx.drawImage(c, Math.round((width - c.width) / 2), y)
    y += c.height + gap
  }
  return cv
}

/** 叠加文字水印（平铺，带轻微旋转，透明度可调） */
export function applyWatermark(cv, text, opts = {}) {
  const { opacity = 0.18, fontSize = 0, color = '#8a8f99' } = opts
  const out = cloneCanvas(cv)
  const ctx = out.getContext('2d')
  const size = fontSize || Math.max(14, Math.round(Math.min(out.width, out.height) / 18))
  ctx.save()
  ctx.globalAlpha = Math.max(0.02, Math.min(1, opacity))
  ctx.fillStyle = color
  ctx.font = `${size}px -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif`
  ctx.textAlign = 'center'
  ctx.textBaseline = 'middle'
  ctx.rotate(-Math.PI / 9)
  const stepX = size * (text.length * 1.1 + 6)
  const stepY = size * 4
  const span = Math.hypot(out.width, out.height)
  for (let y = -span; y < span; y += stepY) {
    for (let x = -span; x < span; x += stepX) ctx.fillText(text, x, y)
  }
  ctx.restore()
  return out
}
