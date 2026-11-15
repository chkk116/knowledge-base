import { onBeforeRouteLeave } from 'vue-router'
import { onMounted, onBeforeUnmount } from 'vue'
import { ElMessageBox } from 'element-plus'

/**
 * 未保存离开守卫：路由跳转二次确认 + 关闭/刷新标签页提示。
 *
 * @param {() => boolean} isDirty 函数形式读取响应式状态，避免把值拷贝成快照
 * @param {string} message 路由离开确认文案
 *
 * 页面在正常保存 / 交卷成功后把自己的 dirty 状态置回 false，守卫即自动放行，
 * 不会在成功提交后还弹一次确认。
 */
export function useUnsavedGuard(isDirty, message = '有未保存的内容，确定离开吗？') {
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
      return true
    } catch {
      return false
    }
  })
}
