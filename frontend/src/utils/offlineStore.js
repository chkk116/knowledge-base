/**
 * 移动端离线存储 + 自动同步
 *
 * 场景：手机上没网时也能建文档（拍照识别 / 手写），内容先存在本机 IndexedDB，
 * 恢复网络后自动上传到服务端「我的文档」空间，上传成功即从本地队列移除。
 *
 * 设计取舍：
 *  - 用原生 IndexedDB（不引 idb 库），只有两张表：queue（待同步）/ cache（已同步文档的阅读缓存）。
 *  - 队列项 = { localId, spaceId, title, content, createdAt, tries, lastError }
 *    · spaceId 为 null 表示「还没选空间」，同步时落到默认空间
 *    · tries 用于退避：失败 3 次后不再自动重试，等用户手动点「立即同步」
 *  - 同步是「乐观上传」：不比对服务端版本，因为移动端创建的都是新文档（不存在冲突合并）。
 *    编辑已有文档的离线版本走另一条路（见 saveDocOffline），带 docId 时用 PUT 覆盖。
 *  - 所有写操作都派发 window 事件 'kb:offline-pending-changed'，供 TabBar 上的角标刷新。
 */

const DB_NAME = 'kb-mobile'
const DB_VER = 1
const STORE_QUEUE = 'queue'   // 待同步
const STORE_CACHE = 'cache'   // 服务端文档的本地阅读缓存

let dbPromise = null

function openDB() {
  if (dbPromise) return dbPromise
  dbPromise = new Promise((resolve, reject) => {
    if (!('indexedDB' in window)) { reject(new Error('浏览器不支持 IndexedDB')); return }
    const req = indexedDB.open(DB_NAME, DB_VER)
    req.onupgradeneeded = () => {
      const db = req.result
      if (!db.objectStoreNames.contains(STORE_QUEUE)) {
        const s = db.createObjectStore(STORE_QUEUE, { keyPath: 'localId' })
        s.createIndex('createdAt', 'createdAt')
        s.createIndex('spaceId', 'spaceId')
      }
      if (!db.objectStoreNames.contains(STORE_CACHE)) {
        const s = db.createObjectStore(STORE_CACHE, { keyPath: 'key' })   // key = spaceId:docId
        s.createIndex('cachedAt', 'cachedAt')
      }
    }
    req.onsuccess = () => resolve(req.result)
    req.onerror = () => { dbPromise = null; reject(req.error) }
  })
  return dbPromise
}

function tx(store, mode, fn) {
  return openDB().then((db) => new Promise((resolve, reject) => {
    const t = db.transaction(store, mode)
    const s = t.objectStore(store)
    let result
    try { result = fn(s) } catch (e) { reject(e); return }
    t.oncomplete = () => resolve(result && result.__req ? result.__req.result : result)
    t.onerror = () => reject(t.error)
    t.onabort = () => reject(t.error)
  }))
}

/** 统一封装 IDBRequest → Promise */
function wrap(req) { return { __req: req } }

function notify() {
  window.dispatchEvent(new CustomEvent('kb:offline-pending-changed'))
}

function genId() {
  return 'd' + Date.now().toString(36) + Math.random().toString(36).slice(2, 7)
}

// ---------------------------------------------------------------- 队列

/** 当前登录用户 id（离线数据按用户隔离：换号登录后旧草稿/缓存不得跟随新账号，2026-10-06 外部安全审查） */
function currentUserId() {
  try {
    const u = JSON.parse(localStorage.getItem('kb_user_cache') || 'null')
    return u && u.id != null ? String(u.id) : null
  } catch { return null }
}

/** 新增一条待同步文档，返回 localId */
export async function enqueueDoc({ spaceId = null, title = '', content = '', images = [] }) {
  const item = {
    localId: genId(),
    userId: currentUserId(),          // 串号防护：同步阶段只提交与当前登录账号一致的条目
    spaceId,
    title: title || '未命名文档',
    content: content || '',
    images,                       // 可选：拍照的原图 dataURL（仅本地预览用，不参与同步）
    createdAt: Date.now(),
    tries: 0,
    lastError: ''
  }
  await tx(STORE_QUEUE, 'readwrite', (s) => wrap(s.add(item)))
  notify()
  return item.localId
}

/** 更新队列项（离线编辑场景） */
export async function updateQueuedDoc(localId, patch) {
  const cur = await getQueuedDoc(localId)
  if (!cur) throw new Error('草稿不存在')
  const next = { ...cur, ...patch }
  await tx(STORE_QUEUE, 'readwrite', (s) => wrap(s.put(next)))
  notify()
  return next
}

/** 只返回属于当前登录账号的条目：换号/退出后不把上一位用户的数据露出来。
 *  无 userId 的老条目（早期版本遗留）视为「归属不明」，任何账号都不返回。 */
function mine(list) {
  const uid = currentUserId()
  return (list || []).filter((it) => it && it.userId && it.userId === uid)
}

export async function getQueuedDoc(localId) {
  const item = await tx(STORE_QUEUE, 'readonly', (s) => wrap(s.get(localId)))
  // 隔离缺口修复（外部复审）：详情页也要按归属校验，否则换号后可直接打开他人草稿
  if (item && item.userId !== currentUserId()) return null
  return item || null
}

/**
 * 登出 / 令牌失效时调用：**只清文档缓存，保留待同步草稿**。
 * 离线时点退出不该丢内容（外部复审修正）；草稿本身带 userId，同步阶段只提交本账号条目，
 * 无主草稿（老版本遗留、没有 userId）不自动同步，等于既不丢也不串号。
 */
export async function clearDocCacheForLogout() {
  try {
    await tx(STORE_CACHE, 'readwrite', (s) => wrap(s.clear()))
  } catch { /* 忽略 */ }
}

/**
 * 注销场景专用：队列与缓存全部清空。
 * 与 clearDocCacheForLogout（登出，保留草稿）不同——注销后账号不复存在，草稿无法再同步。
 */
export async function purgeAllOffline() {
  try {
    const items = await listQueuedDocs()
    for (const it of items) await removeQueuedDoc(it.localId)
    await tx(STORE_CACHE, 'readwrite', (s) => wrap(s.clear()))
    notify()
  } catch { /* 忽略 */ }
}

export async function removeQueuedDoc(localId) {
  await tx(STORE_QUEUE, 'readwrite', (s) => wrap(s.delete(localId)))
  notify()
}

/** 全部待同步项，按创建时间倒序 */
export async function listQueuedDocs() {
  const all = await tx(STORE_QUEUE, 'readonly', (s) => wrap(s.getAll()))
  // 隔离缺口修复（外部复审）：草稿列表此前不过滤归属
  return mine(all).sort((a, b) => b.createdAt - a.createdAt)
}

export async function pendingCount() {
  try {
    const all = await tx(STORE_QUEUE, 'readonly', (s) => wrap(s.getAll()))
    // 隔离缺口修复（外部复审）：此前用 count() 统计全库，会把他人的待同步数算进来
    return mine(all).length
  } catch { return 0 }
}

// ---------------------------------------------------------------- 缓存

export async function cacheDoc(spaceId, doc, html) {
  const key = spaceId + ':' + doc.id
  const item = {
    key, spaceId, docId: doc.id,
    userId: currentUserId(),        // 缓存归属：换号后不给他人复用（外部复审修正）
    title: doc.title,
    html: html || doc.content || '',
    updatedAt: doc.updatedAt || doc.createdAt || '',
    cachedAt: Date.now()
  }
  await tx(STORE_CACHE, 'readwrite', (s) => wrap(s.put(item)))
  return item
}

export async function getCachedDoc(spaceId, docId) {
  const item = await tx(STORE_CACHE, 'readonly', (s) => wrap(s.get(spaceId + ':' + docId)))
  // 归属校验：缓存属于别的账号（或老版本无归属）时视为未缓存——换号/令牌失效后不得复用他人缓存
  if (item && (item.userId || null) !== currentUserId()) return null
  return item
}

/** 离线时的文档列表兜底：返回本空间已缓存的文档，按缓存时间倒序 */
export async function listCachedDocs(spaceId) {
  const all = await tx(STORE_CACHE, 'readonly', (s) => wrap(s.getAll()))
  return mine(all)
    .filter((d) => spaceId == null || d.spaceId === spaceId)
    .sort((a, b) => b.cachedAt - a.cachedAt)
}

export async function clearCache() {
  await tx(STORE_CACHE, 'readwrite', (s) => wrap(s.clear()))
}

/** 删除单篇文档的离线缓存（「离线内容」页按条移除用） */
export async function removeCachedDoc(spaceId, docId) {
  await tx(STORE_CACHE, 'readwrite', (s) => wrap(s.delete(spaceId + ':' + docId)))
  notify()
  return true
}

export async function cachedCount() {
  try {
    const all = await tx(STORE_CACHE, 'readonly', (s) => wrap(s.getAll()))
    return mine(all).length   // 按当前账号统计，避免显示他人缓存数
  } catch { return 0 }
}

// ---------------------------------------------------------------- 同步

const MAX_TRIES = 3
let syncing = false

/**
 * 把队列里所有「未超次数」的文档上传到服务端。
 * @returns {Promise<number>} 成功同步的条数
 */
export async function syncOnReconnect() {
  if (syncing) return 0
  if (!navigator.onLine) return 0
  syncing = true
  let ok = 0
  try {
    // 动态 import，避免离线包在无网时都去加载 axios 封装
    const { createShareDoc } = await import('../api/docShare')
    const { resolveDefaultSpaceId } = await import('./spaceResolver')
    const items = await listQueuedDocs()
    for (const it of items) {
      // 串号防护：换号登录后不同步前任账号的草稿（署名错误 / 写入无权空间）；
      // 无 userId 的老条目同样跳过——它属于谁不确定，任何账号自动提交都可能错署名
      if (!it.userId || it.userId !== currentUserId()) continue
      if (it.tries >= MAX_TRIES) continue
      try {
        const spaceId = it.spaceId || await resolveDefaultSpaceId()
        if (!spaceId) throw new Error('没有可写入的文档空间')
        await createShareDoc(spaceId, { title: it.title, content: it.content })
        await removeQueuedDoc(it.localId)
        ok++
      } catch (e) {
        const msg = (e && (e.response?.data?.error || e.message)) || '同步失败'
        await updateQueuedDoc(it.localId, { tries: (it.tries || 0) + 1, lastError: msg })
      }
    }
    if (ok > 0) window.dispatchEvent(new CustomEvent('kb:offline-synced', { detail: { count: ok } }))
  } finally {
    syncing = false
  }
  return ok
}

/** 手动重试单条（用户点「重试」按钮）：清零次数后再同步 */
export async function retryOne(localId) {
  // 外部审查 2026-10-11：原实现先清零 tries 再调 syncOnReconnect——若已有同步在跑会
  // 立即 return 0，tries 白清、用户重试静默丢失。改为等本轮同步结束后再重试（上限 30s）。
  for (let i = 0; i < 60 && syncing; i++) await new Promise((r) => setTimeout(r, 500))
  await updateQueuedDoc(localId, { tries: 0, lastError: '' })
  return syncOnReconnect()
}
