/**
 * AI 对话两端共用的纯函数（桌面 AiChat.vue / 移动端 MobileAiChat.vue）。
 *
 * 约束：无副作用、不依赖 Vue 响应式状态 —— attachments 由调用方以数组传入。
 * 单测：frontend/tests/aiPure.test.mjs（node --test，零依赖）。
 * 拆分背景：AiChat.vue 2433 行，两端 35 个同名函数重复；第一步先抽纯函数。
 */

/** 模型 key：系统模型 s:<id>、自己的模型 u:<id>（两类 id 各自自增可能重复，必须带前缀区分） */
export function mkey(m) {
  return (m.scope === 'user' ? 'u:' : 's:') + m.id
}

/** 回答里若混入 Agent 内部指令（模型复述 prompt），整段替换成提示，避免一屏手册 */
export function sanitizeAnswer(t) {
  const x = String(t || '')
  const looksLeaked = x.length > 400
    && /(工作方式要求|工具列表|可用工具（需要时输出|禁止在回答中复述)/.test(x)
    && x.includes('TOOL:')
  if (!looksLeaked) return x
  return '本次没有生成有效回答，请重试一次。'
}

/** 剥离 Agent 内部标记（防「重试/回填」把【任务】【最近的对话】当用户输入存库） */
export function sanitizeInput(t) {
  let x = String(t || '')
  x = x.replace(/【(任务|最近的对话|本次任务|已执行的步骤与结果|要求|工具返回)】/g, '')
  x = x.replace(/^\s*(用户|助手)：/gm, '')
  return x.replace(/\n{3,}/g, '\n\n').trim()
}

/** 会话标题：取第一行有效文字，剥掉【】包装与思考残留，控制在 20 字内 */
export function cleanTitle(raw) {
  let t = String(raw || '').split('\n').find((l) => l.trim()) || '新对话'
  t = t.replace(/【[^】]*】/g, ' ').replace(/<[^>]+>/g, ' ')
       .replace(/\bthink\b|思考过程|reasoning/gi, ' ').replace(/\s+/g, ' ').trim()
  return (t || '新对话').slice(0, 20)
}

/**
 * Agent 写文档用的极简 Markdown → HTML。
 * 安全约定：不做「正文已是 HTML 就原样返回」的直通——该分支会把 onmouseover/onclick 等事件属性
 * 原样提交给服务端，也会把「提到了 <p> 标签」的普通回答误判成 HTML 导致格式全丢。
 * 一律按 Markdown 解析：引号必须转义（alt/src 属性位置可被注入 onerror）；
 * 图片 / 音视频只放行站内资源与内联 data:，外站地址一律降级成纯文本，
 * 避免读者打开文档时向第三方发请求（与 /uploads 站内资源策略一致）。
 * 后端 HtmlSanitizer 另有一层兜底。
 */
// 资源白名单：图片 /uploads、/emojis 或 data:image；音视频 /uploads 或 data:(video|audio)。
// 复用给 Markdown 语法与单行 HTML <img>，两条路径口径必须一致。
const ALLOWED_IMG = (src) => /^\/(uploads|emojis)\//i.test(src) || /^data:image\/(png|jpe?g|gif|webp|bmp);base64,/i.test(src)
const ALLOWED_MEDIA = (src) => /^\/uploads\//i.test(src) || /^data:(video|audio)\//i.test(src)

export function mdToHtml(md) {
  const esc = (s) => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')
  const out = []
  let inCode = false
  let inList = false
  const closeList = () => { if (inList) { out.push('</ul>'); inList = false } }
  for (const raw of String(md || '').split('\n')) {
    const line = raw.replace(/\s+$/, '')
    if (/^```/.test(line)) {
      closeList()
      out.push(inCode ? '</pre>' : '<pre>')
      inCode = !inCode
      continue
    }
    if (inCode) { out.push(esc(line)); continue }
    const img = line.match(/^!\[([^\]]*)\]\(([^)\s]+)\)\s*$/)
    if (img) {
      closeList()
      // 外站图片不渲染：否则文档打开时会向第三方发请求（可能带 Referer / 追踪像素）
      if (ALLOWED_IMG(img[2])) out.push(`<img src="${esc(img[2])}" alt="${esc(img[1])}"/>`)
      else out.push(`<p>${esc(line)}</p>`)
      continue
    }
    const htmlImg = line.match(/^(?:<p[^>]*>\s*)?<img\b([^>]*)>(?:\s*<\/p>)?\s*$/i)
    if (htmlImg) {
      const attrs = htmlImg[1]
      const srcM = attrs.match(/src\s*=\s*["']([^"']+)["']/i)
      const src = srcM ? srcM[1] : ''
      if (ALLOWED_IMG(src)) {
        const altM = attrs.match(/alt\s*=\s*["']([^"']*)["']/i)
        const wM = attrs.match(/width\s*=\s*["']?(\d{1,3}%|\d{2,4})["']?/i)
        closeList()
        out.push(`<img class="ai-html-img" src="${esc(src)}" alt="${esc(altM ? altM[1] : '')}"`
          + (wM ? ` style="max-width:${wM[1].endsWith('%') ? wM[1] : Math.min(Number(wM[1]), 900) + 'px'}"` : '')
          + ' />')
        continue
      }
    }
    const media = line.match(/^@\[(video|audio)\]\(([^)\s]+)\)\s*$/)
    if (media) {
      closeList()
      if (ALLOWED_MEDIA(media[2])) out.push(`<${media[1]} src="${esc(media[2])}" controls></${media[1]}>`)
      else out.push(`<p>${esc(line)}</p>`)
      continue
    }
    const h = line.match(/^(#{1,4})\s+(.*)$/)
    if (h) { closeList(); out.push(`<h${h[1].length}>${esc(h[2])}</h${h[1].length}>`); continue }
    const li = line.match(/^\s*[-*]\s+(.*)$/)
    if (li) { if (!inList) { out.push('<ul>'); inList = true } out.push(`<li>${esc(li[1])}</li>`); continue }
    closeList()
    if (!line.trim()) continue
    out.push(`<p>${esc(line).replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')}</p>`)
  }
  closeList()
  if (inCode) out.push('</pre>')
  return out.join('')
}

/**
 * 富文本直通内容的「前端纵深净化」（第 4 项）：后端 HtmlSanitizer 是权威防线，
 * 这里再做一次保守收敛，防止把 script / 事件属性 / 外站资源直接提交给服务端。
 *
 * 刻意不做成「标签白名单」：导图/流程图/表格是 TipTap 自定义节点，图形数据全在
 * data='{"nodes":...}' 属性里，按白名单重建标签会把图全部毁掉。
 * 所以只剔除危险位，其余（含 data-*、class、style）原样保留；
 * 干净 HTML 经过本函数字符串完全不变（单测断言了这点）。
 */
const DANGEROUS_TAG = /^(script|iframe|object|embed|link|meta|base|form|input|button|svg|math)$/i
const EVENT_ATTR = /\s+on[a-z0-9_-]+\s*=\s*(?:"[^"]*"|'[^']*'|[^\s>]+)/gi
const JS_PROTO = /(?:javascript|vbscript|data:text\/html)\s*:/gi
const SRC_ATTR = /(\s(?:src|poster|srcset)\s*=\s*)(?:"([^"]*)"|'([^']*)'|([^\s>]+))/gi
const TAG_RE = /<(\/?)([a-z][a-z0-9-]*)\b([^>]*)>/gi

export function sanitizeDocHtml(html) {
  const s = String(html || '').replace(/<script\b[^>]*>[\s\S]*?<\/script\s*>/gi, '')
  return s.replace(TAG_RE, (whole, slash, tag, attrs) => {
    if (DANGEROUS_TAG.test(tag)) return ''
    if (!attrs) return whole
    let a = attrs.replace(EVENT_ATTR, '').replace(JS_PROTO, '')
    // style 里的 url(...) 会向第三方发请求，去掉 url() 但保留其余内联样式
    a = a.replace(/(\sstyle\s*=\s*")([^"]*)(")/gi, (m, p, v, q) => p + v.replace(/url\s*\([^)]*\)/gi, '') + q)
    a = a.replace(/(\sstyle\s*=\s*')([^']*)(')/gi, (m, p, v, q) => p + v.replace(/url\s*\([^)]*\)/gi, '') + q)
    // src/poster/srcset：只保留站内资源与内联 data:，外站地址整条属性丢弃
    a = a.replace(SRC_ATTR, (m, prefix, d, s1, u) => {
      const attrName = prefix.trim().split(/\s*=/)[0].toLowerCase()
      const raw = String(d ?? s1 ?? u ?? '')
      // srcset 是「URL + 描述符」列表，逐条校验；src/poster 整值校验
      // （data:image/...;base64,AAAA 自带逗号，不能按逗号切）
      const values = attrName === 'srcset'
        ? raw.split(',').map((x) => x.trim().split(/\s+/)[0])
        : [raw]
      const ok = values.length > 0 && values.every((v) =>
        /^\/(uploads|emojis)\//i.test(v)
        || /^data:(image\/(png|jpe?g|gif|webp|bmp);base64,|video\/|audio\/)/i.test(v))
      return ok ? m : ''
    })
    return a === attrs ? whole : `<${slash}${tag}${a}>`
  })
}

/**
 * 「保存文档」专用：正文本来就是富文本 HTML 就交给后端（先过一道前端净化），否则按 Markdown 解析。
 *
 * 为什么不能一律 mdToHtml：Agent 的 read_doc → update_doc 链路读回来的是库里的 HTML，
 * 模型会把它整段写回；一律按 Markdown 转义的话，正文会变成满屏 `&lt;p&gt;` 字面量，
 * 原文档的排版、图片全丢。
 *
 * 安全性：这里的直通**只作用于提交给后端的正文**，聊天里的回答渲染仍走 mdToHtml（一律转义），
 * 所以不存在「模型输出被当 HTML 直接 v-html」的面；提交前先由 sanitizeDocHtml 剔除
 * script / 事件属性 / 外站资源，入库前服务端 DocumentController / DocShareService
 * 还会用 HtmlSanitizer 白名单再洗一遍（两层纵深）。
 */
export function toDocHtml(md) {
  const s = String(md || '')
  // 判定要严：必须「以块级标签开头」且「至少两个闭合标签」才当 HTML，
  // 避免「正文里提到了 <p> 标签」的普通 Markdown 被误判成 HTML（老直通分支就是这么翻车的）
  const startsBlock = /^\s*<(p|div|h[1-6]|ul|ol|table|blockquote|pre|figure|img|section|article)\b/i.test(s)
  const closes = (s.match(/<\/[a-z][a-z0-9]*\s*>/gi) || []).length
  return startsBlock && closes >= 2 ? sanitizeDocHtml(s) : mdToHtml(s)
}

/** 把带本地识别文字的附件汇总进提问（纯函数版：attachments 由调用方传入数组） */
export function buildQuestionWithAttachments(q, attachments = []) {
  const texts = (attachments || []).filter((a) => a.text)
  if (!texts.length) return q
  const parts = texts.map((a, i) =>
    '【附件 ' + (i + 1) + '：' + a.name + (a.kind === 'image' ? ' · 本地识别文字】' : '】') + '\n' + a.text)
  return parts.join('\n\n') + '\n\n【提问】' + q
}
