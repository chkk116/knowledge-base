/**
 * 文章短码：把自增 id 映射成 8 位看不出序号的字符串。
 *
 * 目的：分享出去的 /post/38 会变成 /post/7K3Q9XMB 这类地址，
 *      链接里不再暴露「这是第几篇」，随手改数字也猜不到相邻文章。
 *
 * 边界（别当加密用）：算法在前端包里，属于**混淆**不是加密；
 *      刻意逆向前端代码仍能还原。真正不可枚举需要服务端密钥或随机短码 + 数据库字段。
 *
 * 实现：35 bit 空间上的仿射置换（乘奇数 + 加常量，奇数与 2^35 互质 ⇒ 双射、可逆），
 *      再拼 5 bit 校验位，共 40 bit，用 Crockford base32（去掉 I/L/O/U）编成 8 字符。
 *      校验位让随手编的短码 31/32 概率直接判无效；双射保证 id ↔ 短码一一对应、不用存表。
 */
const PAYLOAD_BITS = 35n
const MOD = 1n << PAYLOAD_BITS
const MASK = MOD - 1n
const CHECK_MASK = 31n
const ALPHABET = '0123456789ABCDEFGHJKMNPQRSTVWXYZ'
// 奇数 ⇒ 与 2^35 互质 ⇒ 存在模逆，置换可逆
const MULT = 0x9E3779B97F4A7C15n & MASK
const ADD = 0x2545F4914F6CDD1Dn & MASK

function modInverse(a, m) {
  let oldR = a % m, r = m
  let oldS = 1n, s = 0n
  while (r !== 0n) {
    const q = oldR / r
    ;[oldR, r] = [r, oldR - q * r]
    ;[oldS, s] = [s, oldS - q * s]
  }
  return ((oldS % m) + m) % m
}
const INV = modInverse(MULT, MOD)

function checksum(p) {
  // 按 5 bit 分段折叠异或：载荷的**每一位**都要参与，否则改高位能绕过校验
  let x = p
  let acc = 0x1Bn
  while (x > 0n) {
    acc ^= x & CHECK_MASK
    x >>= 5n
  }
  return acc & CHECK_MASK
}

/** 数字 id → 8 位短码；非法 id 返回 null */
export function encodeDocId(id) {
  const n = Number(id)
  if (!Number.isSafeInteger(n) || n <= 0 || BigInt(n) >= MOD) return null
  const p = (BigInt(n) * MULT + ADD) & MASK
  const v = (p << 5n) | checksum(p)
  let out = ''
  for (let i = 7; i >= 0; i--) out += ALPHABET[Number((v >> BigInt(i * 5)) & CHECK_MASK)]
  return out
}

/** 短码 → 数字 id；长度/字符/校验位任一不对都返回 null */
export function decodeDocId(code) {
  if (typeof code !== 'string' || code.length !== 8) return null
  let v = 0n
  for (const ch of code.toUpperCase()) {
    const i = ALPHABET.indexOf(ch)
    if (i < 0) return null
    v = (v << 5n) | BigInt(i)
  }
  const p = v >> 5n
  if ((v & CHECK_MASK) !== checksum(p)) return null
  let n = ((p - ADD) * INV) % MOD
  if (n < 0n) n += MOD
  if (n <= 0n || n >= MOD) return null
  return Number(n)
}

/**
 * 路由参数 → 真实文档 id。
 * 纯数字一律按老链接处理（8 位数字 id 不能被误当成短码解码）；
 * 其余按短码解码，因此 upgradeDocPath 会剔除「恰好全为数字」的短码。
 */
export function docIdFromParam(v) {
  if (v == null) return null
  const s = String(v)
  if (/^\d+$/.test(s)) {
    const n = Number(s)
    return Number.isSafeInteger(n) && n > 0 ? n : null
  }
  return decodeDocId(s)
}

/**
 * 老式数字路径 → 短码路径；已经是短码或不是文章路径时返回 null。
 * 供 router.afterEach 用 replaceState 改地址栏（不产生额外历史记录）。
 */
export function upgradeDocPath(path) {
  const m = /^\/(post|reader)\/(\d+)(\/versions)?\/?$/.exec(String(path || ''))
  if (!m) return null
  const code = encodeDocId(Number(m[2]))
  // 全数字短码既容易被猜，又会和 8 位数字 id 撞车；这种极少数的文章保持原链接
  if (!code || /^\d+$/.test(code)) return null
  return '/' + m[1] + '/' + code + (m[3] || '')
}
