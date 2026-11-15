/** AI 回答反馈的纯规则（无 Vue / DOM / 网络依赖）。 */

export const FB_REASONS = ['答非所问', '没有依据', '资料过时', '回答不完整']

/**
 * 点击赞 / 踩按钮后该做什么：
 * - 无 qaId 或已在处理中由调用方拦截，这里只处理业务规则
 * - 踩：已踩则取消（rating 0），否则展开原因面板
 * - 赞：已赞则取消，否则提交赞
 * 返回 null（忽略）/ { action: 'toggle' } / { action: 'submit', rating, reason }
 */
export function resolveRatingClick(m, rating) {
  if (!m || !m.qaId) return null
  if (rating === -1) {
    if (m.fbRating === -1) return { action: 'submit', rating: 0, reason: '' }
    return { action: 'toggle' }
  }
  return { action: 'submit', rating: m.fbRating === 1 ? 0 : 1, reason: '' }
}
