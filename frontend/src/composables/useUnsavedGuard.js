import { onBeforeRouteLeave } from 'vue-router'
import { onMounted, onBeforeUnmount } from 'vue'
import { ElMessageBox } from 'element-plus'

/**
 * 未保存离开守卫：路由跳转二次确认 + 关闭/刷新标签页提示。
 *
 * @param {() => boolean} isDirty 函数形式读取响应式状态，避免把值拷贝成快照
 * @param {string} message 路由离开确认文案
 * @param {{ onConfirmLeave?: () => any }} [opts] 用户确认离开后、真正跳转前执行的收尾动作
 *        （考试页用它作废「一题未答就离开」的进行中记录，避免白占一次考试机会）
 *
 * 页面在正常保存 / 交卷成功后把自己的 dirty 状态置回 false，守卫即自动放行，
 * 不会在成功提交后还弹一次确认。
 */
export function useUnsavedGuard(isDirty, message = '有未保存的内容，确定离开吗？', opts = {}) {
  const beforeUnload = (e) => {
    if (!isDirty()) return
    e.preventDefault()
    e.returnValue = ''
  }

  onMounted(() => window.addEventListener('beforeunload', beforeUnload))
  onBeforeUnmount(() => window.removeEventListener('beforeunload', beforeUnload))

  onBeforeRouteLeave(async () => {
    if (!isDirty()) return true
    try {
      await ElMessageBox.confirm(message, '未保存的内容', {
        type: 'warning',
        confirmButtonText: '离开',
        cancelButtonText: '继续编辑',
      })
    } catch {
      return false
    }
    if (typeof opts.onConfirmLeave === 'function') {
      try { await opts.onConfirmLeave() } catch { /* 收尾失败不拦用户离开 */ }
    }
    return true
  })
}
