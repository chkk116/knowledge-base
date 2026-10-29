import { listAllShareDocs } from '../api/docShare'

/**
 * 共享文档的页面路径。
 *
 * 移动端是 `/doc/<spaceId>/<docId>`（见 router/mobile.js），桌面是 `/doc-shares/<spaceId>?doc=<docId>`。
 * **没有** `/m/docs/<id>` 这个路由 —— 2026-10-27 线上就是这样 404 的：
 * Agent 生成流程图后按提示词里的「文档页为 /m/docs/<id>」跳转，路由不存在，直接掉进 404 页。
 */
export function shareDocPath(spaceId, docId, mobile = false) {
  return mobile ? `/doc/${spaceId}/${docId}` : `/doc-shares/${spaceId}?doc=${docId}`
}

/**
 * 规范化 Agent 给的跳转路径。
 *
 * 模型（以及旧提示词）经常写 `/m/docs/21`、`/docs/21` 这种「文档 id 直连」，
 * 但共享文档必须有 spaceId 才能定位。这里按 docId 反查一次空间 id 再改写；
 * 查不到就退到列表页，别让用户看到一个 404。
 */
export async function normalizeNavPath(path, { mobile = false } = {}) {
  const p = String(path || '')
  const m = p.match(/^\/(?:m\/)?docs\/(\d+)\/?$/)
  if (!m) return p
  const docId = Number(m[1])
  try {
    const { data } = await listAllShareDocs()
    const hit = (data || []).find((d) => Number(d.docId ?? d.id) === docId)
    if (hit && (hit.spaceId ?? hit.shareId)) return shareDocPath(hit.spaceId ?? hit.shareId, docId, mobile)
  } catch {
    // 查不到就落到列表页
  }
  return mobile ? '/all-docs' : '/doc-shares/all'
}
