import { defineStore } from 'pinia'
import { ref, watch } from 'vue'
import { useAuthStore } from './auth'

// 浏览历史：纯前端、按浏览器 localStorage 存储（无需后端改动）。
// 仅记录当前登录用户在本机浏览过的文档，最多保留 50 条，最新浏览排在最前。
// Key 按用户隔离（2026-10-13 审查 M14）：共用终端时不得跨账号泄露浏览记录。
function storageKey() {
  try {
    const uid = useAuthStore().user?.id
    return uid ? `kb_history_${uid}` : 'kb_history_guest'
  } catch {
    return 'kb_history_guest'
  }
}

function read() {
  try {
    const arr = JSON.parse(localStorage.getItem(storageKey()) || '[]')
    return Array.isArray(arr) ? arr : []
  } catch {
    return []
  }
}

function write(arr) {
  try { localStorage.setItem(storageKey(), JSON.stringify(arr)) } catch {}
}

export const useHistoryStore = defineStore('history', () => {
  const items = ref(read())

  // 账号切换时重载对应的历史（登录/登出/换号）
  const auth = useAuthStore()
  watch(() => auth.user?.id, () => { items.value = read() })

  // 记录一次浏览；同一文档会去重并提到最前
  function add(doc) {
    if (!doc || !doc.id) return
    items.value = items.value.filter((x) => x.id !== doc.id)
    items.value.unshift({
      id: doc.id,
      title: doc.title || '未命名文档',
      category: doc.category || '',
      author: doc.author || '',
      views: doc.views || 0,
      time: Date.now(),
    })
    if (items.value.length > 50) items.value = items.value.slice(0, 50)
    write(items.value)
  }

  // 删除单条
  function remove(id) {
    items.value = items.value.filter((x) => x.id !== id)
    write(items.value)
  }

  // 清空全部
  function clear() {
    items.value = []
    try { localStorage.removeItem(storageKey()) } catch {}
  }

  return { items, add, remove, clear }
})
