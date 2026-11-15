/**
 * AI 问答附件的纯规则（无 Vue / DOM / 网络依赖，可直接单测）。
 * 从 AiChat.vue 抽离，便于独立演进与回归。
 */

export const MAX_ATTACHMENTS = 4
export const ATT_TEXT_MAX = 200 * 1024   // 文本文件上限 200KB
export const ATT_SNIPPET = 6000          // 单附件注入提问的内容截断长度
export const DOC_SIZE_LIMIT = 20 * 1024 * 1024
export const IMAGE_SIZE_LIMIT = 8 * 1024 * 1024

const PLAIN_TEXT_RE = /\.(txt|md|markdown|json|csv|log|xml|yml|yaml|sql|ts|js|mjs|py|java|html?|css|vue|ini|conf|properties)$/i

/** 文档类附件（PDF / Word / PPT / Excel）走本地解析，其余按图片或纯文本处理 */
export function attachmentSizeLimit(name, docPattern) {
  return docPattern.test(String(name || '')) ? DOC_SIZE_LIMIT : IMAGE_SIZE_LIMIT
}

export function isPlainTextFile(name, type) {
  return PLAIN_TEXT_RE.test(String(name || '')) || /^text\//i.test(String(type || ''))
}

/** 注入提问前截断长文本，并明确标注「已截断」，避免模型上下文被单附件吃满 */
export function truncateAttachmentText(text, max = ATT_SNIPPET) {
  const s = String(text || '')
  return s.slice(0, max) + (s.length > max ? '\n…（内容过长已截断）' : '')
}

export function makeAttachmentId(prefix = '') {
  return Date.now() + (prefix ? '-' + prefix : '') + '-' + Math.random().toString(36).slice(2, 7)
}
