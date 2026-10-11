/**
 * 应用锁（PIN 码）
 *
 * 大厂 App 标配：打开 App / 从后台切回需验证，防他人翻看。
 * - 只存 **加盐哈希**，不存明文 PIN
 * - 优先用 crypto.subtle(SHA-256)；非安全上下文（http）退化为内置哈希（本地校验够用）
 * - 锁定策略：冷启动必锁；后台超过 N 秒切回也锁
 *
 * 键：kb_app_lock_v1
 */
const KEY = 'kb_app_lock_v1'

const cfg = { enabled: false, salt: '', hash: '', timeout: 60, len: 4 }

export function getLockConfig() {
  try {
    const o = JSON.parse(localStorage.getItem(KEY) || 'null')
    if (o && typeof o === 'object') return { ...cfg, ...o }
  } catch { /* ignore */ }
  return { ...cfg }
}

function save(o) {
  try { localStorage.setItem(KEY, JSON.stringify(o)) } catch { /* ignore */ }
}

export function isLockEnabled() {
  const c = getLockConfig()
  return !!c.enabled && !!c.hash
}

/** PIN 是否合法（4~6 位数字） */
export function isValidPin(pin) {
  return /^\d{4,6}$/.test(String(pin || ''))
}

function randSalt() {
  try {
    const a = new Uint8Array(16)
    crypto.getRandomValues(a)
    return [...a].map((b) => b.toString(16).padStart(2, '0')).join('')
  } catch {
    return String(Date.now()) + Math.random().toString(36).slice(2)
  }
}

async function hashPin(pin, salt) {
  const data = salt + '::' + pin
  try {
    if (window.crypto?.subtle) {
      const buf = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(data))
      return [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, '0')).join('')
    }
  } catch { /* 落下面 */ }
  // 退化实现：FNV-1a 变体，仅用于本地防窥（非加密强度）
  let h = 0x811c9dc5
  for (let i = 0; i < data.length; i++) {
    h ^= data.charCodeAt(i)
    h = (h + ((h << 1) + (h << 4) + (h << 7) + (h << 8) + (h << 24))) >>> 0
  }
  return 'f' + h.toString(16)
}

/** 开启/修改 PIN */
export async function setPin(pin) {
  if (!isValidPin(pin)) throw new Error('请输入 4~6 位数字')
  const salt = randSalt()
  const hash = await hashPin(pin, salt)
  const c = getLockConfig()
  save({ ...c, enabled: true, salt, hash, len: pin.length })
}

/** 校验 PIN */
export async function verifyPin(pin) {
  const c = getLockConfig()
  if (!c.hash) return false
  const h = await hashPin(String(pin || ''), c.salt)
  return h === c.hash
}

/** 关闭应用锁（需已通过校验） */
export function disableLock() {
  save({ ...cfg })
}

export function setLockTimeout(sec) {
  const c = getLockConfig()
  save({ ...c, timeout: Math.max(0, Number(sec) || 0) })
}

/** 是否该锁定（有登录态 + 已开启） */
export function shouldLock() {
  if (!isLockEnabled()) return false
  try { return !!localStorage.getItem('kb_token') } catch { return false }
}
