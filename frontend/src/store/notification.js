import { defineStore } from 'pinia'
import { ref } from 'vue'
import { notificationUnread } from '../api/kb'

// 实时通知：用 EventSource 长连后端 SSE 流，替代「路由切换轮询 unread」。
// 同时暴露 version（每次收到推送自增），供通知页监听以刷新列表。
export const useNotificationStore = defineStore('notification', () => {
  const unread = ref(0)
  const version = ref(0)
  let es = null
  let retryTimer = null
  let retryDelay = 1000          // 指数退避：1s → 2s → 4s … 上限 30s，避免后端异常时疯狂重连

  function connect(t) {
    const token = t || localStorage.getItem('kb_token') || ''
    disconnect()
    if (!token) return
    // 先拉一次准确未读数（避免重连期间遗漏）
    notificationUnread().then(({ data }) => { unread.value = data.count || 0 }).catch(() => {})
    const url = `/api/notifications/stream?token=${encodeURIComponent(token)}`
    es = new EventSource(url)
    es.onopen = () => {
      // 连接成功：重置退避（后端若重启，恢复正常节奏）
      retryDelay = 1000
    }
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
    // 连接出错：关闭当前实例并自行按指数退避重连。
    // 不能依赖 EventSource 的原生自动重连——它在服务端 4xx/5xx 时会以极短间隔反复重试
    // （nginx/后端异常时表现为每 2-3 秒一次的长连接风暴，拖慢页面并刷爆日志）。
    es.onerror = () => {
      if (es) { es.close(); es = null }
      if (retryTimer) return
      retryTimer = setTimeout(() => {
        retryTimer = null
        retryDelay = Math.min(retryDelay * 2, 30000)
        if (localStorage.getItem('kb_token')) connect()
      }, retryDelay)
    }
  }

  function disconnect() {
    if (retryTimer) { clearTimeout(retryTimer); retryTimer = null }
    if (es) { es.close(); es = null }
  }

  function setUnread(n) { unread.value = n }

  return { unread, version, connect, disconnect, setUnread }
})
