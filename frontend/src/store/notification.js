import { defineStore } from 'pinia'
import { ref } from 'vue'
import { notificationUnread } from '../api/kb'

// 实时通知：用 EventSource 长连后端 SSE 流，替代「路由切换轮询 unread」。
// 同时暴露 version（每次收到推送自增），供通知页监听以刷新列表。
export const useNotificationStore = defineStore('notification', () => {
  const unread = ref(0)
  const version = ref(0)
  let es = null

  function connect(t) {
    const token = t || localStorage.getItem('kb_token') || ''
    disconnect()
    if (!token) return
    // 先拉一次准确未读数（避免重连期间遗漏）
    notificationUnread().then(({ data }) => { unread.value = data.count || 0 }).catch(() => {})
    const url = `/api/notifications/stream?token=${encodeURIComponent(token)}`
    es = new EventSource(url)
    es.onmessage = (ev) => {
      try {
        const d = JSON.parse(ev.data)
        if (d && typeof d.unread === 'number') unread.value = d.unread
        else unread.value = unread.value + 1
      } catch (e) {
        unread.value = unread.value + 1
      }
      version.value++
    }
    // EventSource 在连接断开后会自动重连，无需手动处理
  }

  function disconnect() {
    if (es) { es.close(); es = null }
  }

  function setUnread(n) { unread.value = n }

  return { unread, version, connect, disconnect, setUnread }
})
