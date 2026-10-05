/**
 * 移动端「落到哪个空间」的解析逻辑
 *
 * 用户需求：拍照识别出的文档默认存进「个人共享文档库」。
 * 后端里个人空间就是 type === 'PERSONAL' 的 DocShare（每个用户一个，自动创建）。
 * 本模块负责：
 *  1. 缓存空间列表（避免每次拍照都请求）
 *  2. 找出 PERSONAL 空间；没有则调用接口创建一个
 *  3. 记住用户上次手动选的空间（localStorage），优先用它
 */
import { listMyShares, createShare } from '../api/docShare'

const LS_LAST_SPACE = 'kb_m_last_space'

let cache = null
let cacheAt = 0
const CACHE_MS = 60 * 1000

/** 我的空间列表（60s 内存缓存；离线时返回 []，由调用方走本地兜底） */
export async function mySpaces(force = false) {
  const now = Date.now()
  if (!force && cache && now - cacheAt < CACHE_MS) return cache
  try {
    const { data } = await listMyShares()
    cache = Array.isArray(data) ? data : []
    cacheAt = now
    return cache
  } catch (e) {
    // 离线：返回旧缓存（可能是空），让上层决定兜底策略
    return cache || []
  }
}

export function clearSpaceCache() { cache = null; cacheAt = 0 }

/** 个人空间（type=PERSONAL）；不存在则创建 */
export async function personalSpace(force = false) {
  const list = await mySpaces(force)
  let p = list.find((s) => s.type === 'PERSONAL')
  if (p) return p
  // 没找到：可能是缓存过期或后端尚未自动创建，主动建一个
  try {
    const { data } = await createShare({
      name: '我的文档',
      description: '个人文档（移动端拍照识别的默认存放位置）',
      type: 'PERSONAL'
    })
    clearSpaceCache()
    return data
  } catch (e) {
    // 并发创建冲突：重新拉一次列表
    const again = await mySpaces(true)
    p = again.find((s) => s.type === 'PERSONAL')
    if (p) return p
    throw e
  }
}

export function lastSpaceId() {
  const v = localStorage.getItem(LS_LAST_SPACE)
  return v ? Number(v) : null
}

export function rememberSpace(id) {
  if (id) localStorage.setItem(LS_LAST_SPACE, String(id))
  else localStorage.removeItem(LS_LAST_SPACE)
}

/**
 * 默认写入目标：上次选过的空间（若仍在我的列表里） > 个人空间。
 * 离线时如果列表拿不到，返回 lastSpaceId()（可能为 null，同步阶段再解析）。
 */
export async function resolveDefaultSpaceId() {
  const last = lastSpaceId()
  const list = await mySpaces()
  if (last && list.some((s) => Number(s.id) === last)) return last
  if (list.length === 0) return last   // 离线：退回上次记住的
  const p = list.find((s) => s.type === 'PERSONAL')
  return p ? p.id : list[0].id
}
