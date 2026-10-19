import api from './request'

/**
 * 网页剪藏：服务端抓取网页并提取正文。
 * 返回 { url, title, site, excerpt, content, length }
 *
 * 只做抓取预览，保存仍走 createShareDoc——权限/配额/空间归属由那条路径统一处理。
 */
export const clipPreview = (url) => api.post('/clip/preview', { url })
