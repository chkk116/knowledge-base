// 文本处理小工具（原先在多个页面各写一份，行为还不一致，这里统一）

/**
 * 拆分标签串。
 * 统一规则：按中英文逗号/分号拆分，**不按空格拆**（标签本身可能含空格，如「Vue 3 组合式 API」）。
 * 原先 CourseManage 会按空格拆，导致含空格标签被切成多个；其余三处只认英文逗号，
 * 于是「好文，推荐」这种中文逗号写法在不同页面渲染结果不一样——现在统一。
 */
export function splitTags(s) {
  if (!s) return []
  return String(s)
    .split(/[,，;；]+/)
    .map((x) => x.trim())
    .filter(Boolean)
}

/**
 * 摘要里的轻量 Markdown 清洗：正文可能是导入或直接保存的 Markdown，
 * 只剥 HTML 标签会把 #、**、[文字](url) 这些符号原样显示在首页卡片上。
 * 只处理展示用摘要，不改动正文，也不做完整 Markdown 解析。
 */
export function cleanSnippetMarkdown(s) {
  return String(s ?? '')
    .replace(/!\[([^\]]*)\]\([^)]*\)/g, '$1')   // 图片 -> alt 文本
    .replace(/\[([^\]]+)\]\([^)]*\)/g, '$1')    // 链接 -> 文字
    .replace(/^\s{0,3}#{1,6}\s+/gm, '')          // 标题
    .replace(/^\s{0,3}>\s?/gm, '')               // 引用
    .replace(/^\s{0,3}[-*+]\s+/gm, '')           // 列表符号
    .replace(/(\*\*|__)(.*?)\1/g, '$2')          // 粗体
    .replace(/(\*|_)(.*?)\1/g, '$2')             // 斜体
    .replace(/`([^`]+)`/g, '$1')                 // 行内代码
    .replace(/\s+/g, ' ')
    .trim()
}

/** HTML 转义（& < > " '），用于把纯文本安全地放进 innerHTML/v-html */
export function escapeHtml(s) {
  return String(s ?? '').replace(/[&<>"']/g, (c) => ({
    '&': '&amp;',
    '<': '&lt;',
    '>': '&gt;',
    '"': '&quot;',
    "'": '&#39;'
  }[c]))
}
