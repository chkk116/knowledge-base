import { ref } from 'vue'
import { dmUnread } from '../api/dm'

/** 私信未读总数（侧栏红点用）：模块级单例，SSE 事件与页面操作共同维护 */
export const dmUnreadCount = ref(0)

export async function refreshDmUnread() {
  try {
    const { data } = await dmUnread()
    dmUnreadCount.value = Number(data?.unread) || 0
  } catch (e) {
    // 未登录 / 网络异常：保持原值，不打扰
  }
}

export function clearDmUnread() {
  dmUnreadCount.value = 0
}
