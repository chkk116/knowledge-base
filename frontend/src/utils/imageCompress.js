/**
 * 图片压缩为 dataURL —— 供多模态模型直接「看图」。
 *
 * 为什么还要压缩：
 *   base64 会让体积膨胀约 33%，手机拍的原图动辄数 MB，多张一起会撑爆请求体
 *   （nginx 上限 50MB，但几 MB 的 JSON 体已足以让请求变慢甚至超时）。
 *   视觉模型对分辨率并不敏感：最长边 1280 + JPEG 0.82 足以识别文字、图表与版式。
 *
 * 为什么不再 OCR：
 *   OCR 只保留文字，图案/排版/表格结构全部丢失；现在模型原生多模态，
 *   直接把图喂给模型即可，识别质量与「理解图片」能力都更好（2026-10-07）。
 */
export async function compressImage(file, maxEdge = 1280, quality = 0.82) {
  const url = URL.createObjectURL(file)
  try {
    const img = await new Promise((resolve, reject) => {
      const i = new Image()
      i.onload = () => resolve(i)
      i.onerror = () => reject(new Error('图片读取失败'))
      i.src = url
    })
    let { width: w, height: h } = img
    const scale = Math.min(1, maxEdge / Math.max(w, h))
    w = Math.max(1, Math.round(w * scale))
    h = Math.max(1, Math.round(h * scale))
    const c = document.createElement('canvas')
    c.width = w
    c.height = h
    const ctx = c.getContext('2d')
    // 白底填充：PNG 透明区域转 JPEG 会变黑
    ctx.fillStyle = '#fff'
    ctx.fillRect(0, 0, w, h)
    ctx.drawImage(img, 0, 0, w, h)
    return c.toDataURL('image/jpeg', quality)
  } finally {
    URL.revokeObjectURL(url)
  }
}
