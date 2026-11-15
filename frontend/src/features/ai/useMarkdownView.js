/**
 * AI 对话的 Markdown 渲染与展示格式化（桌面端第二步拆分；移动端后续接同一模块）。
 *
 * 设计：零响应式状态、零浏览器全局依赖 —— Prism 高亮由调用方以 highlight(code, lang)
 * 注入（桌面端用 utils/prism 包装，Node 单测用默认转义），因此本模块可直接在 node --test 里跑。
 * 组件侧用 const { renderMarkdown, ... } = useMarkdownView(...) 解构，模板无需改动。
 *
 * 安全约定（外部复审 r9/r10）：
 *  - html:false，AI 输出按纯文本解析，不解析内嵌 HTML；
 *  - 外部图片不自动加载（防止注入内容借 ![](url) 触发外链请求泄露），只放行本站 /uploads /emojis 与 data:image；
 *  - fence 的语言标识进 class 前做字符白名单，防 ```x" onmouseover=... 闭合属性注入事件。
 */
import MarkdownIt from 'markdown-it'

export function fmtMs(ms) {
  if (!ms) return ''
  return ms >= 1000 ? (ms / 1000).toFixed(2) + 's' : ms + 'ms'
}

export function fmtTokens(u) {
  if (!u) return ''
  // OpenAI 兼容格式：prompt_tokens / completion_tokens / total_tokens
  const inp = u.prompt_tokens ?? u.promptTokens ?? u.input_tokens
  const out = u.completion_tokens ?? u.completionTokens ?? u.output_tokens
  const total = u.total_tokens ?? u.totalTokens
  if (total == null && inp == null && out == null) return ''
  const t = total != null ? total : ((inp || 0) + (out || 0))
  let s = t + ' tokens'
  if (inp != null || out != null) s += `（输入 ${inp || 0} / 输出 ${out || 0}）`
  return s
}

export function openSource(idOrSource) {
  if (idOrSource == null || typeof window === 'undefined') return
  // 共享文档来源带 shareId/docId，必须走带空间鉴权的共享文档页，不能跳公开文章阅读页
  if (typeof idOrSource === 'object' && idOrSource.shareId != null) {
    window.open('/doc-shares/' + idOrSource.shareId + '?doc=' + idOrSource.docId, '_blank')
    return
  }
  // 公开文档来源可能是完整来源对象（引用卡片传的就是对象），不能把对象直接拼进 URL
  const docId = typeof idOrSource === 'object' ? (idOrSource.id ?? idOrSource.docId) : idOrSource
  if (docId == null || String(docId) === '[object Object]') return
  window.open('/reader/' + docId, '_blank')
}

export function useMarkdownView({ highlight } = {}) {
  const md = new MarkdownIt({ html: false, linkify: true, breaks: true })
  const escapeHtml = (s) => md.utils.escapeHtml(String(s))
  const doHighlight = typeof highlight === 'function' ? highlight : escapeHtml

  const LOCAL_IMG_RE = /^\/(uploads|emojis)\//i
  const DATA_IMG_RE = /^(data:image\/(png|jpe?g|gif|webp|bmp);base64,)/i
  const defaultRenderImage = md.renderer.rules.image
  md.renderer.rules.image = (tokens, idx, opts, env, self) => {
    const src = tokens[idx].attrGet('src') || ''
    if (LOCAL_IMG_RE.test(src) || DATA_IMG_RE.test(src)) {
      return defaultRenderImage(tokens, idx, opts, env, self)
    }
    return '<a href="' + escapeHtml(src) + '" target="_blank" rel="noopener noreferrer">[外部图片，点击查看]</a>'
  }

  // 代码块保留 .md-code-block / .md-copy-btn / .md-code 结构，复用既有复制逻辑与样式
  md.renderer.rules.fence = (tokens, idx) => {
    const info = (tokens[idx].info || '').trim()
    const lang = info ? info.toLowerCase().split(/[\s,;:]/)[0].replace(/[^a-z0-9_+#.-]/g, '') : ''
    const cls = lang ? ' language-' + lang : ''
    const code = doHighlight(tokens[idx].content, lang)
    return '<div class="md-code-block"><button type="button" class="md-copy-btn">复制</button>'
      + '<pre class="md-code' + cls + '"><code' + cls + '>' + code + '</code></pre></div>'
  }

  const renderMarkdown = (src) => src ? md.render(src) : ''
  return { renderMarkdown, fmtMs, fmtTokens, openSource, escapeHtml }
}
