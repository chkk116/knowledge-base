import { defineStore } from 'pinia'
import { resolveUrl } from '../utils/runtimeBase'
import { ref } from 'vue'
import { notificationUnread } from '../api/kb'

// 实时通知：fetch 流式读取后端 SSE 流（外部审查 2026-10-13 M11）。
// 原实现用 EventSource + ?token= URL 传令牌——令牌会进 nginx/浏览器访问日志与历史记录；
// EventSource 又无法自定义请求头，故改用 fetch + ReadableStream：Authorization 头带令牌，
// 手动按 SSE 协议解析帧，重连仍由本 store 指数退避控制。
// 同时暴露 version（每次收到推送自增），供通知页监听以刷新列表。
export const useNotificationStore = defineStore('notification', () => {
  const unread = ref(0)
  const version = ref(0)
  let controller = null     // AbortController：断开时中止 fetch
  let retryTimer = null
  let retryDelay = 1000          // 指数退避：1s → 2s → 4s … 上限 30s，避免后端异常时疯狂重连
  let generation = 0             // 连接代次：防止旧连接的回调覆盖新连接的状态

  // 离线时不要连 SSE：否则会按退避节奏反复失败（1s→30s），徒增噪音与耗电
  function bindOnlineHooks() {
    if (bindOnlineHooks.done) return
    bindOnlineHooks.done = true
    window.addEventListener('offline', () => { disconnect() })
    window.addEventListener('online', () => {
      const tk = localStorage.getItem('kb_token') || ''
      if (tk) connect(tk)
    })
  }

  // 未读数只信服务端：推送帧里没有准确数字时回服务端取，杜绝「+1 猜」导致的虚增
  function refreshUnread() {
    notificationUnread().then(({ data }) => { unread.value = data.count || 0 }).catch(() => {})
  }

  async function connect(t) {
    const token = t || localStorage.getItem('kb_token') || ''
    disconnect()
    bindOnlineHooks()
    if (!token) return
    if (typeof navigator !== 'undefined' && navigator.onLine === false) return   // 离线直接不连
    // 先拉一次准确未读数（避免重连期间遗漏）
    refreshUnread()
    const myGen = ++generation
    controller = new AbortController()
    try {
      // App 内置资源模式下 resolveUrl 给绝对地址（相对路径会被 Capacitor 当本地资源 → 404）
      const resp = await fetch(resolveUrl('/api/notifications/stream'), {
        headers: { Authorization: 'Bearer ' + token },
        signal: controller.signal,
        cache: 'no-store'
      })
      if (myGen !== generation) return   // 已被更新的连接取代
      if (!resp.ok || !resp.body) throw new Error('SSE HTTP ' + resp.status)
      retryDelay = 1000                  // 连接成功：重置退避（后端若重启，恢复正常节奏）
      await readStream(resp.body, myGen)
    } catch (e) {
      if (myGen !== generation) return
      if (e && e.name === 'AbortError') return   // 主动断开，不重连
      scheduleRetry()
    }
  }

  // 按 SSE 协议读流：帧以空行分隔；注释行（心跳 ": ping"）规范上应忽略
  async function readStream(body, myGen) {
    const reader = body.getReader()
    const decoder = new TextDecoder('utf-8')
    let buf = ''
    try {
      while (true) {
        const { done, value } = await reader.read()
        if (done) break
        if (myGen !== generation) return   // 连接已作废：停止读取
        buf += decoder.decode(value, { stream: true }).replace(/\r\n/g, '\n')
        let idx
        while ((idx = buf.indexOf('\n\n')) >= 0) {
          const frame = buf.slice(0, idx)
          buf = buf.slice(idx + 2)
          handleFrame(frame)
        }
      }
    } catch (e) {
      if (myGen !== generation) return
      scheduleRetry()
      return
    }
    // 流正常结束（后端 30 分钟超时或重启）：按退避重连
    if (myGen === generation) scheduleRetry()
  }

  function handleFrame(frame) {
    const dataLines = []
    let eventName = ''
    for (const line of frame.split('\n')) {
      if (!line || line.startsWith(':')) continue        // 空行 / 注释（心跳）忽略
      if (line.startsWith('event:')) { eventName = line.slice(6).trim(); continue }
      if (line.startsWith('data:')) dataLines.push(line.slice(5).replace(/^ /, '').replace(/\r$/, ''))
    }
    if (!dataLines.length) return
    let d = null
    try { d = JSON.parse(dataLines.join('\n')) } catch (e) { d = null }
    // 私信事件：交给具体页面处理（聊天页实时追加、其它页面刷新未读），不走通知未读那一套
    if (eventName === 'dm' && d) {
      window.dispatchEvent(new CustomEvent('kb:dm', { detail: d }))
      return
    }
    if (eventName === 'dm-recall' && d) {
      window.dispatchEvent(new CustomEvent('kb:dm-recall', { detail: d }))
      return
    }
    if (d && typeof d.unread === 'number') {
      unread.value = d.unread
    } else {
      refreshUnread()   // 非标准帧：不猜未读数，回服务端取准确值（原 EventSource 版在此处盲 +1，导致虚增）
    }
    version.value++
  }

  // 连接出错 / 流结束：关闭当前连接并按指数退避重连。
  // 不能无间隔重连——nginx/后端异常时表现为每 2-3 秒一次的长连接风暴，拖慢页面并刷爆日志。
  function scheduleRetry() {
    if (retryTimer) return
    retryTimer = setTimeout(() => {
      retryTimer = null
      retryDelay = Math.min(retryDelay * 2, 30000)
      if (localStorage.getItem('kb_token')) connect()
    }, retryDelay)
  }

  function disconnect() {
    generation++          // 作废在途连接的所有回调
    if (retryTimer) { clearTimeout(retryTimer); retryTimer = null }
    if (controller) { try { controller.abort() } catch (e) { /* ignore */ } ; controller = null }
  }

  function setUnread(n) { unread.value = n }

  return { unread, version, connect, disconnect, setUnread }
})
