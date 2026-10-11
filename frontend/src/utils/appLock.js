/**
 * 应用锁（PIN 码）
 *
 * 大厂 App 标配：打开 App / 从后台切回需验证，防他人翻看。
 * - 只存 **加盐哈希**，不存明文 PIN
 * - SHA-256 全上下文统一：crypto.subtle 可用时用原生，否则用内置纯 JS 实现——
 *   两者结果完全一致，http / https 切换不会导致哈希漂移（外部审查 2026-10-11：
 *   旧版 http 下退化 FNV，跨上下文 PIN 永久校验失败且强度不足）
 * - 连续输错 5 次锁定 15 分钟（防应用内快速穷举）
 * - disableLock 必须携带 PIN 并校验通过（防桥接滥用直接关锁）
 * - 锁定策略：冷启动必锁；后台超过 N 秒切回也锁
 *
 * 键：kb_app_lock_v1（锁配置）、kb_app_lock_fail_v1（失败计数）
 */
const KEY = 'kb_app_lock_v1'
const FAIL_KEY = 'kb_app_lock_fail_v1'
const MAX_FAILS = 5
const LOCK_MS = 15 * 60 * 1000

const cfg = { enabled: false, salt: '', hash: '', algo: 'sha256', timeout: 60, len: 4 }

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

// ---------- 纯 JS SHA-256（FIPS 180-4，供非安全上下文使用；与 subtle 输出一致） ----------
function rotr(x, n) { return (x >>> n) | (x << (32 - n)) }

function jsSha256Hex(ascii) {
  const K = [
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
  ]
  const H = [0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19]
  const l = ascii.length
  const words = []
  for (let i = 0; i < l; i++) words[i >> 2] = (words[i >> 2] || 0) | (ascii.charCodeAt(i) << ((3 - (i & 3)) * 8))
  words[l >> 2] = (words[l >> 2] || 0) | (0x80 << ((3 - (l & 3)) * 8))
  const wl = (((l + 8) >> 6) + 1) * 16
  for (let i = (l >> 2) + 1; i < wl; i++) words[i] = 0
  words[wl - 1] = (l * 8) >>> 0 // 本场景输入为 salt(32hex)+'::'+pin(≤6位) ≈ 40 字节，32bit 长度足够
  words[wl - 2] = 0
  for (let j = 0; j < wl; j += 16) {
    const w = []
    for (let i = 0; i < 16; i++) w[i] = words[j + i] | 0
    for (let i = 16; i < 64; i++) {
      const s0 = rotr(w[i - 15], 7) ^ rotr(w[i - 15], 18) ^ (w[i - 15] >>> 3)
      const s1 = rotr(w[i - 2], 17) ^ rotr(w[i - 2], 19) ^ (w[i - 2] >>> 10)
      w[i] = (w[i - 16] + s0 + w[i - 7] + s1) | 0
    }
    let [a, b, c, d, e, f, g, h] = H
    for (let i = 0; i < 64; i++) {
      const S1 = rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25)
      const ch = (e & f) ^ (~e & g)
      const t1 = (h + S1 + ch + K[i] + w[i]) | 0
      const S0 = rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22)
      const mj = (a & b) ^ (a & c) ^ (b & c)
      const t2 = (S0 + mj) | 0
      h = g; g = f; f = e; e = (d + t1) | 0; d = c; c = b; b = a; a = (t1 + t2) | 0
    }
    H[0] = (H[0] + a) | 0; H[1] = (H[1] + b) | 0; H[2] = (H[2] + c) | 0; H[3] = (H[3] + d) | 0
    H[4] = (H[4] + e) | 0; H[5] = (H[5] + f) | 0; H[6] = (H[6] + g) | 0; H[7] = (H[7] + h) | 0
  }
  return H.map((x) => (x >>> 0).toString(16).padStart(8, '0')).join('')
}

async function hashPin(pin, salt) {
  const data = salt + '::' + pin
  try {
    if (window.crypto?.subtle) {
      const buf = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(data))
      return [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, '0')).join('')
    }
  } catch { /* 落下面 */ }
  // 纯 JS SHA-256：与 subtle 输出完全一致（不再是弱 FNV，也不再因上下文漂移）
  return jsSha256Hex(data)
}

// ---------- 失败次数限制 ----------
export function pinFailState() {
  try {
    const o = JSON.parse(localStorage.getItem(FAIL_KEY) || 'null')
    if (o && typeof o === 'object') return { n: o.n || 0, lockUntil: o.lockUntil || 0 }
  } catch { /* ignore */ }
  return { n: 0, lockUntil: 0 }
}

function noteFail() {
  const s = pinFailState()
  s.n++
  if (s.n >= MAX_FAILS) { s.lockUntil = Date.now() + LOCK_MS; s.n = 0 }
  try { localStorage.setItem(FAIL_KEY, JSON.stringify(s)) } catch { /* ignore */ }
  return s
}

function noteOk() {
  try { localStorage.removeItem(FAIL_KEY) } catch { /* ignore */ }
}

/** 旧版 FNV 哈希（算法升级前的存量配置） */
export function isLegacyLock() {
  const c = getLockConfig()
  return !!c.hash && String(c.hash).startsWith('f')
}

/** 清除旧算法遗留配置（仅限 legacy 哈希；UI 侧需用户确认） */
export function clearLegacyLock() {
  if (isLegacyLock()) { save({ ...cfg }); noteOk() }
}

/** 开启/修改 PIN */
export async function setPin(pin) {
  if (!isValidPin(pin)) throw new Error('请输入 4~6 位数字')
  const salt = randSalt()
  const hash = await hashPin(pin, salt)
  const c = getLockConfig()
  save({ ...c, enabled: true, salt, hash, algo: 'sha256', len: pin.length })
  noteOk()
}

/**
 * 校验 PIN。
 * @throws {Error} 锁定中 / 旧算法存量配置 —— 调用方应把 message 展示给用户
 */
export async function verifyPin(pin) {
  const s = pinFailState()
  if (s.lockUntil > Date.now()) {
    const m = Math.max(1, Math.ceil((s.lockUntil - Date.now()) / 60000))
    throw new Error(`失败次数过多，请 ${m} 分钟后再试`)
  }
  const c = getLockConfig()
  if (!c.hash) return false
  if (String(c.hash).startsWith('f')) throw new Error('PIN 算法已升级，请重新设置应用锁')
  const h = await hashPin(String(pin || ''), c.salt)
  const ok = h === c.hash
  if (ok) noteOk(); else noteFail()
  return ok
}

/** 关闭应用锁：必须携带 PIN 且校验通过（外部审查 2026-10-11：原实现无任何校验） */
export async function disableLock(pin) {
  if (!(await verifyPin(pin))) throw new Error('PIN 校验失败')
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
