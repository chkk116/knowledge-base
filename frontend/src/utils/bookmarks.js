/**
 * 本地收藏（稍后读 / 书签）
 *
 * 为什么不用后端的 /api/favorites：那是针对**公开文章（Document）**的，
 * 而 App 里读的是**空间文档（SharedDocument）**，两套实体不通；
 * 且本地收藏在离线时也能用（App 场景更实在）。
 *
 * 键：kb_bookmarks_v1:<userId>
 */
const KEY = 'kb_bookmarks_v1'

function uid() {
  try {
    const u = JSON.parse(localStorage.getItem('kb_user_cache') || 'null')
    return u && u.id != null ? String(u.id) : 'anon'
  } catch { return 'anon' }
}

function storeKey() { return KEY + ':' + uid() }

export function listBookmarks() {
  try {
    const a = JSON.parse(localStorage.getItem(storeKey()) || '[]')
    return Array.isArray(a) ? a : []
  } catch { return [] }
}

function save(list) {
  try { localStorage.setItem(storeKey(), JSON.stringify(list)) } catch { /* ignore */ }
  window.dispatchEvent(new CustomEvent('kb:bookmarks-changed'))
}

export function isBookmarked(spaceId, docId) {
  return listBookmarks().some((b) => String(b.spaceId) === String(spaceId) && String(b.docId) === String(docId))
}

/** 切换收藏，返回切换后的状态（true=已收藏） */
export function toggleBookmark({ spaceId, docId, title = '', spaceName = '' }) {
  const list = listBookmarks()
  const i = list.findIndex((b) => String(b.spaceId) === String(spaceId) && String(b.docId) === String(docId))
  if (i >= 0) {
    list.splice(i, 1)
    save(list)
    return false
  }
  list.unshift({ spaceId: Number(spaceId), docId: Number(docId), title: title || '未命名文档', spaceName, at: Date.now() })
  save(list)
  return true
}

export function removeBookmark(spaceId, docId) {
  save(listBookmarks().filter((b) => !(String(b.spaceId) === String(spaceId) && String(b.docId) === String(docId))))
}

export function clearBookmarks() { save([]) }

export function bookmarkCount() { return listBookmarks().length }
