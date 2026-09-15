/**
 * 统一的接口错误提示取值。
 * 后端业务失败返回 { ok:false, error }，框架级异常可能只有 message，
 * 原先 100+ 处各写一遍 `e?.response?.data?.error || e?.response?.data?.message || '兜底'`，
 * 这里收敛成一处，避免各处漏写导致「报错没提示」。
 */
export function errMsg(e, fallback = '操作失败') {
  return (
    (e && e.response && e.response.data && (e.response.data.error || e.response.data.message)) ||
    (e && e.message) ||
    fallback
  )
}
