/**
 * 本地 OCR（Tesseract.js）
 *
 * 为什么自己封装而不用后端 OCR：
 *   用户要求「离线也能用」—— 无网时后端 OCR 完全不可用。Tesseract.js 是 WASM，
 *   中文语言包首次约 4~10MB，交给浏览器 HTTP 缓存（Cache Storage）后，
 *   之后即使离线也能用（Service Worker 未启用时也能命中 HTTP 磁盘缓存）。
 *
 * 关键取舍：
 *  - tesseract.js 走 CDN 动态 import（不进主包），避免给首屏加 ~1MB 的 WASM 加载器。
 *    如果内网/离线场景连 CDN 都拿不到，会走「识别失败」分支并提示改用云端识别。
 *  - 语言包优先用 jsDelivr（国内可达性一般），失败自动切 unpkg。
 *  - 图片先按最长边 1600px 缩放：实测超过后精度提升极小、耗时翻倍。
 *  - 中文用 'chi_sim'，同时带 'eng' 以正确识别数字/英文（纯 chi_sim 会把英文识别成乱码）。
 */


let modPromise = null
let worker = null
let workerLang = ''
let warmupPromise = null

/** 进度回调用的当前状态（供 UI 订阅） */
export const ocrProgress = { value: 0, text: '' }

function loadScript(url) {
  return new Promise((resolve, reject) => {
    const old = document.querySelector(`script[data-kb-ocr="${url}"]`)
    if (old && old.dataset.loaded === '1') { resolve(); return }
    const s = old || document.createElement('script')
    s.src = url
    s.async = true
    s.dataset.kbOcr = url
    s.onload = () => { s.dataset.loaded = '1'; resolve() }
    s.onerror = () => reject(new Error('脚本加载失败: ' + url))
    if (!old) document.head.appendChild(s)
  })
}


/** 动态加载 tesseract.js（用 UMD 版更稳，ESM 在部分 WebView 里 import 报错） */
async function loadTesseract() {
  if (modPromise) return modPromise
  modPromise = (async () => {
    if (window.Tesseract) return window.Tesseract
    // 本地资源（frontend/public/tesseract/，随构建打包）：消除 CDN 依赖——
    // ① 持令牌页面上加载无 SRI 的第三方脚本是供应链风险；②「离线 OCR」此前实际必须联网；
    // ③ APK 白多 9MB 资源却没用上（外部复审 2026-10-07）
    const umd = [`${import.meta.env.BASE_URL}tesseract/tesseract.min.js`]
    let lastErr = null
    for (const url of umd) {
      try {
        await loadScript(url)
        if (window.Tesseract) return window.Tesseract
      } catch (e) { lastErr = e }
    }
    throw lastErr || new Error('OCR 引擎加载失败')
  })()
  return modPromise
}

/** 把 File/Blob 按最长边缩放，返回 canvas 的 dataURL（降低识别耗时） */
function normalizeImage(file, maxEdge = 1600, removeLines = false) {
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(file)
    const img = new Image()
    img.onload = () => {
      URL.revokeObjectURL(url)
      let { width: w, height: h } = img
      const scale = Math.min(1, maxEdge / Math.max(w, h))
      w = Math.max(1, Math.round(w * scale))
      h = Math.max(1, Math.round(h * scale))
      const c = document.createElement('canvas')
      c.width = w; c.height = h
      const ctx = c.getContext('2d')
      // 白底填充：PNG 透明区域在 Tesseract 里会被当作噪声
      ctx.fillStyle = '#fff'
      ctx.fillRect(0, 0, w, h)
      ctx.drawImage(img, 0, 0, w, h)
      if (removeLines) eraseTableLines(ctx, w, h)
      resolve(c.toDataURL('image/jpeg', 0.92))
    }
    img.onerror = () => { URL.revokeObjectURL(url); reject(new Error('图片读取失败')) }
    img.src = url
  })
}

/**
 * 擦掉表格线：Tesseract 会把横竖表格线认成 `[` `|` `-` 之类的字符，
 * 这是识别表格时最主要的噪声来源（实测「IP/MAC 表格」里满屏的 `[` 和 `|`）。
 * 做法：把几乎横贯 / 纵贯全图的暗线涂成白色。
 */
function eraseTableLines(ctx, w, h) {
  try {
    const img = ctx.getImageData(0, 0, w, h)
    const d = img.data
    const step = w > 1600 ? 2 : 1 // 大图隔点采样，避免双重循环过慢
    const dark = (x, y) => {
      const i = (y * w + x) * 4
      return (d[i] + d[i + 1] + d[i + 2]) / 3 < 128
    }
    // 水平线：某行暗像素占比过高 → 视为表格线
    const rowDark = new Uint32Array(h)
    for (let y = 0; y < h; y++) {
      let n = 0
      for (let x = 0; x < w; x += step) if (dark(x, y)) n++
      rowDark[y] = n * step
    }
    for (let y = 0; y < h; y++) {
      if (rowDark[y] > w * 0.7) {
        for (let x = 0; x < w; x++) { const i = (y * w + x) * 4; d[i] = 255; d[i + 1] = 255; d[i + 2] = 255 }
      }
    }
    // 垂直线（在水平线已擦除的基础上重新统计）
    const colDark = new Uint32Array(w)
    for (let x = 0; x < w; x++) {
      let n = 0
      for (let y = 0; y < h; y += step) if (dark(x, y)) n++
      colDark[x] = n * step
    }
    for (let x = 0; x < w; x++) {
      if (colDark[x] > h * 0.7) {
        for (let y = 0; y < h; y++) { const i = (y * w + x) * 4; d[i] = 255; d[i + 1] = 255; d[i + 2] = 255 }
      }
    }
    ctx.putImageData(img, 0, 0)
  } catch (e) { /* 取像素失败（跨域等）就直接跳过，不阻塞识别 */ }
}

/** 取（或创建）worker；语言变化时重建 */
async function ensureWorker(langs, onProgress) {
  const T = await loadTesseract()
  if (worker && workerLang === langs) return worker
  if (worker) {
    try { await worker.terminate() } catch { /* 忽略 */ }
    worker = null
  }
  // 语言包按需下载；logger 把「loading language traineddata」进度转出来
  // worker / core / 语言包全部指向本地内置资源；traineddata 未压缩，gzip:false 告知 worker 不追加 .gz
  // ⚠️ workerBlobURL:false 必须保留：tesseract.js 默认把 worker 脚本包成 Blob URL，
  //    Blob worker 内 scriptDirectory 为空 → core 用裸名 fetch `tesseract-core-simd-lstm.wasm`
  //    → 「Failed to parse URL」→ 永久卡在「正在加载识别引擎」（2026-10-07 实测复现）。
  //    用真实 URL（同源）后 core 才能正确拼出 wasm 的绝对路径。
  const localBase = `${import.meta.env.BASE_URL}tesseract/`
  worker = await T.createWorker(langs, 1, {
    workerPath: localBase + 'worker.min.js',
    corePath: localBase + 'tesseract-core-simd-lstm.js',
    langPath: localBase,
    workerBlobURL: false,
    gzip: false,
    logger: (m) => {
      if (!onProgress) return
      if (m.status === 'loading tesseract core') onProgress(0.08, '正在加载识别引擎…')
      else if (m.status === 'loading language traineddata') {
        onProgress(0.1 + (m.progress || 0) * 0.35, '首次使用，正在下载中文语言包…')
      } else if (m.status === 'initializing api') onProgress(0.5, '正在初始化…')
      else if (m.status === 'recognizing text') {
        onProgress(0.5 + (m.progress || 0) * 0.5, `识别中 ${Math.round((m.progress || 0) * 100)}%`)
      }
    }
  })
  workerLang = langs
  return worker
}

/**
 * 本地识别
 * @param {File|Blob} file 图片
 * @param {(p:number, msg:string)=>void} onProgress 进度回调（0~1）
 * @param {{table?:boolean}} opts table=true 时按词坐标重建表格结构（输出 Markdown）
 * @returns {Promise<string>} 识别出的文本
 */
export async function recognizeLocal(file, onProgress, opts = {}) {
  const step = (p, t) => { ocrProgress.value = p; ocrProgress.text = t; onProgress && onProgress(p, t) }
  step(0.02, '正在处理图片…')
  // 表格模式：分辨率给足（2000）+ 擦掉表格线，否则 Tesseract 会满屏认出 `[` `|` 这类噪声
  const dataUrl = await normalizeImage(file, opts.table ? 2000 : 1600, !!opts.table)

  // 中文 + 英文：chi_sim 单独会把英文/数字识别得很糟
  const langs = 'chi_sim+eng'
  step(0.05, '正在准备识别引擎…')
  const w = await ensureWorker(langs, step)

  const { data } = await w.recognize(dataUrl)
  step(1, '识别完成')
  if (opts.table) {
    const md = buildTableFromWords(data)
    if (md) return md
  }
  return cleanText(data && data.text)
}

/**
 * 用 Tesseract 给出的**词坐标**重建表格 → Markdown。
 *
 * 为什么需要：Tesseract 只认字、不理解表格结构，直接输出会把行列的对应关系打乱
 * （用户实测「IP/MAC 表格」识别出来是一坨乱码）。这里按 y 坐标聚成行、行内按 x 排序，
 * 至少把行列结构还原出来；不像表格时返回 null，调用方回退为纯文本。
 */
function buildTableFromWords(data) {
  // tesseract.js v5 把词级结果移到 blocks[].paragraphs[].lines[].words，data.words 可能为 undefined
  let words = (data && data.words) || []
  if (!words.length && data && Array.isArray(data.blocks)) {
    for (const b of data.blocks) for (const p of (b.paragraphs || [])) for (const l of (p.lines || [])) {
      if (Array.isArray(l.words)) words = words.concat(l.words)
    }
  }
  words = words
    .filter((w) => w && w.text && String(w.text).trim())
    .map((w) => ({
      text: String(w.text).trim(),
      x: w.bbox.x0,
      y: w.bbox.y0,
      h: (w.bbox.y1 - w.bbox.y0) || 10
    }))
  if (words.length < 4) return null

  // 行聚类：按 y 排序，容差取词高的 60%
  words.sort((a, b) => a.y - b.y)
  const rows = []
  for (const w of words) {
    const tol = Math.max(6, w.h * 0.6)
    let row = rows.find((r) => Math.abs(r.y - w.y) <= tol)
    if (!row) {
      row = { y: w.y, words: [] }
      rows.push(row)
    }
    row.words.push(w)
    row.y = row.words.reduce((s, x) => s + x.y, 0) / row.words.length // 用行内均值，聚类更稳
  }
  if (rows.length < 2) return null

  const table = rows.map((r) => r.words.slice().sort((a, b) => a.x - b.x).map((w) => w.text))
  const maxCol = Math.max(...table.map((r) => r.length))
  if (maxCol < 2) return null // 每行都只有 1 个词 → 不是表格

  // 关键：各行词数必须「基本一致」才算表格。普通段落每行词数差异很大，
  // 少了这一步会把正常文章也拆成假表格，反而更难读。
  const freq = {}
  for (const r of table) freq[r.length] = (freq[r.length] || 0) + 1
  const topFreq = Math.max(...Object.values(freq))
  if (topFreq < Math.ceil(table.length * 0.6)) return null

  const pad = (r) => {
    const c = r.slice()
    while (c.length < maxCol) c.push('')
    return c
  }
  const line = (r) => '| ' + pad(r).join(' | ') + ' |'
  return [
    line(table[0]),
    '| ' + Array.from({ length: maxCol }, () => '---').join(' | ') + ' |',
    ...table.slice(1).map(line)
  ].join('\n')
}

/** 预热：进入拍照页就后台把引擎与语言包准备好，用户点识别时无需等待 */
export function warmupLocalOcr() {
  if (warmupPromise) return warmupPromise
  warmupPromise = (async () => {
    try {
      await ensureWorker('chi_sim+eng', () => {})
      return true
    } catch (e) {
      warmupPromise = null
      throw e
    }
  })()
  return warmupPromise
}

/**
 * 清洗 Tesseract 输出：
 *  - 去掉中文字符之间的多余空格（Tesseract 中文识别的典型产物，如「产品 需求 评审」）
 *  - 去掉纯噪声行（中文识别常见单字符乱码行）
 *  - 合并被硬折行的中文段落（中文不应在行中间断句）
 *  - 压缩连续空白
 */
function cleanText(raw) {
  let t = String(raw || '').replace(/\r/g, '')

  // 关键：先去掉「中文 中文」之间的空格。Tesseract 的 chi_sim 会把多数汉字之间插入空格，
  // 直接展示会非常影响可读性（用户看到的是一篇被空格打散的文章）。
  // 只处理「汉字/中文标点 空格 汉字/中文标点」的情形，英文单词之间的空格必须保留。
  const CJK = '\\u4e00-\\u9fa5\\u3000-\\u303f\\uff00-\\uffef'
  t = t.replace(new RegExp(`([${CJK}])[ \\t]+([${CJK}])`, 'g'), '$1$2')
  // 上面单次替换对「甲 乙 丙」这类连续空格需要多轮（每次只吃掉一个空格）
  for (let i = 0; i < 4; i++) t = t.replace(new RegExp(`([${CJK}])[ \\t]+([${CJK}])`, 'g'), '$1$2')
  // 注意：汉字与「数字/英文」之间的空格**不能删**，否则「2026年10月5日 14:00 ... 记录人」
  // 会粘成一坨。这里只把连续多空格收敛为一个，保持边界清晰。
  t = t.replace(/[ \t]{2,}/g, ' ')
  // 标点前后的空格去掉：中文标点（「时间 ：」）与紧跟在汉字后的 ASCII 冒号（「时间 :」）
  t = t.replace(new RegExp(`[ \\t]+([，。、；：？！（）《》【】])`, 'g'), '$1')
  t = t.replace(new RegExp(`([，。、；：？！（）《》【】])[ \\t]+`, 'g'), '$1')
  t = t.replace(new RegExp(`([${CJK}])[ \\t]+([:;,.!?])`, 'g'), '$1$2')
  t = t.replace(new RegExp(`([:;,])[ \\t]+([${CJK}])`, 'g'), '$1$2')

  const lines = t.split('\n').map((l) => l.replace(/[ \t]+$/g, ''))

  const out = []
  for (const line of lines) {
    const s = line.trim()
    if (!s) { out.push(''); continue }
    // 噪声行：长度 <= 2 且不含中文/字母数字
    if (s.length <= 2 && !/[\u4e00-\u9fa5A-Za-z0-9]/.test(s)) continue
    out.push(s)
  }

  // 中文换行合并：上一行以中文（或中文标点）结尾、下一行以中文开头 → 接起来
  const merged = []
  for (const line of out) {
    const prev = merged[merged.length - 1]
    if (prev && /[\u4e00-\u9fa5，、；：]$/.test(prev) && /^[\u4e00-\u9fa5]/.test(line)) {
      merged[merged.length - 1] = prev + line
    } else {
      merged.push(line)
    }
  }

  return merged.join('\n').replace(/\n{3,}/g, '\n\n').trim()
}

/** 结束 worker（页面卸载时调用，释放 WASM 内存） */
export async function disposeLocalOcr() {
  if (!worker) return
  try { await worker.terminate() } catch { /* 忽略 */ }
  worker = null
  workerLang = ''
}
