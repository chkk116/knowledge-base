import { defineStore } from 'pinia'
import { ref } from 'vue'

// 浏览历史：纯前端、按浏览器 localStorage 存储（无需后端改动）。
// 仅记录当前登录用户在本机浏览过的文档，最多保留 50 条，最新浏览排在最前。
const KEY = 'kb_history'

function read() {
  try {
    const arr = JSON.parse(localStorage.getItem(KEY) || '[]')
    return Array.isArray(arr) ? arr : []
  } catch {
    return []
  }
}

export const useHistoryStore = defineStore('history', () => {
  const items = ref(read())

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
    try { localStorage.setItem(KEY, JSON.stringify(items.value)) } catch {}
  }

  // 删除单条
  function remove(id) {
    items.value = items.value.filter((x) => x.id !== id)
    try { localStorage.setItem(KEY, JSON.stringify(items.value)) } catch {}
  }

  // 清空全部
  function clear() {
    items.value = []
    try { localStorage.removeItem(KEY) } catch {}
  }

  return { items, add, remove, clear }
})
