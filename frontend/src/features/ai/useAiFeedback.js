import { reactive } from 'vue'
import { ElMessage } from 'element-plus'
import { aiFeedback } from '../../api/ai'
import { FB_REASONS, resolveRatingClick } from './feedbackRules'

/**
 * AI 回答反馈（赞 / 踩）：数据进 kb_ai_qa_log，管理端「AI 反馈」看板汇总。
 * 点击规则抽到 feedbackRules 便于单测，这里只保留请求状态与消息对象上的交互字段。
 */
export function useAiFeedback() {
  const fbBusy = reactive({})

  /** 赞/踩可随时切换或取消：再点一次同项 = 取消（rating 0），点另一项 = 改评；踩先展开原因面板 */
  function rateMsg(m, rating) {
    if (!m.qaId || fbBusy[m.qaId]) return
    const r = resolveRatingClick(m, rating)
    if (!r) return
    if (r.action === 'toggle') { m.fbOpen = !m.fbOpen; return }
    submitRate(m, r.rating, r.reason)
  }

  async function submitRate(m, rating, reason) {
    if (!m.qaId || fbBusy[m.qaId]) return
    fbBusy[m.qaId] = true
    try {
      await aiFeedback(m.qaId, rating, reason)
      m.fbRating = rating
      m.fbOpen = false
      ElMessage.success(rating === 1 ? '感谢反馈' : rating === 0 ? '已取消反馈' : '已记录，我们会据此补充文档')
    } catch (e) {
      ElMessage.error('反馈失败，请稍后重试')
    } finally {
      fbBusy[m.qaId] = false
    }
  }

  return { FB_REASONS, fbBusy, rateMsg, submitRate }
}
