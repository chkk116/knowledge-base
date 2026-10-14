/**
 * 最小 PDF 加密实现（标准安全处理器，RC4 40-bit，R=2 / V=1）
 *
 * 为什么自己写：App 离线优先、不引依赖。PDF 标准加密（ISO 32000-1 §7.6.3）
 * 只需 MD5 + RC4 两个原语，手写约百余行即可，所有主流阅读器都支持。
 *
 * 安全说明：RC4 40-bit 是 PDF 1.x 的经典方案，强度有限（防「随手转发」够用，
 * 不抗专业破解）。若要更强需 AES-128（R=4），代码量翻倍，此处从简。
 */

/* ------------------------------ MD5 ------------------------------ */

const MD5_S = [
  7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
  5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
  4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
  6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
]
const MD5_K = new Uint32Array(64)
for (let i = 0; i < 64; i++) MD5_K[i] = Math.floor(Math.abs(Math.sin(i + 1)) * 4294967296)

function rotl(x, c) { return (x << c) | (x >>> (32 - c)) }

/** MD5：输入 Uint8Array / number[]，返回 16 字节 Uint8Array */
export function md5(input) {
  const msg = input instanceof Uint8Array ? input : Uint8Array.from(input)
  const len = msg.length
  const withPad = new Uint8Array((((len + 8) >> 6) + 1) << 6)
  withPad.set(msg)
  withPad[len] = 0x80
  const bitLen = len * 8
  const dv = new DataView(withPad.buffer)
  dv.setUint32(withPad.length - 8, bitLen >>> 0, true)
  dv.setUint32(withPad.length - 4, Math.floor(bitLen / 4294967296), true)

  let a0 = 0x67452301, b0 = 0xefcdab89, c0 = 0x98badcfe, d0 = 0x10325476
  const M = new Uint32Array(16)
  for (let off = 0; off < withPad.length; off += 64) {
    for (let i = 0; i < 16; i++) M[i] = dv.getUint32(off + i * 4, true)
    let A = a0, B = b0, C = c0, D = d0
    for (let i = 0; i < 64; i++) {
      let F, g
      if (i < 16) { F = (B & C) | (~B & D); g = i }
      else if (i < 32) { F = (D & B) | (~D & C); g = (5 * i + 1) % 16 }
      else if (i < 48) { F = B ^ C ^ D; g = (3 * i + 5) % 16 }
      else { F = C ^ (B | ~D); g = (7 * i) % 16 }
      F = (F + A + MD5_K[i] + M[g]) >>> 0
      A = D; D = C; C = B
      B = (B + rotl(F, MD5_S[i])) >>> 0
    }
    a0 = (a0 + A) >>> 0; b0 = (b0 + B) >>> 0; c0 = (c0 + C) >>> 0; d0 = (d0 + D) >>> 0
  }
  const out = new Uint8Array(16)
  const odv = new DataView(out.buffer)
  odv.setUint32(0, a0, true); odv.setUint32(4, b0, true)
  odv.setUint32(8, c0, true); odv.setUint32(12, d0, true)
  return out
}

/* ------------------------------ RC4 ------------------------------ */

/** RC4 流加密（对称，加解密同一函数） */
export function rc4(key, data) {
  const S = new Uint8Array(256)
  for (let i = 0; i < 256; i++) S[i] = i
  let j = 0
  for (let i = 0; i < 256; i++) {
    j = (j + S[i] + key[i % key.length]) & 0xff
    const t = S[i]; S[i] = S[j]; S[j] = t
  }
  const out = new Uint8Array(data.length)
  let i = 0; j = 0
  for (let k = 0; k < data.length; k++) {
    i = (i + 1) & 0xff
    j = (j + S[i]) & 0xff
    const t = S[i]; S[i] = S[j]; S[j] = t
    out[k] = data[k] ^ S[(S[i] + S[j]) & 0xff]
  }
  return out
}

/* --------------------- PDF 标准安全处理器 --------------------- */

/** 标准 32 字节填充串（ISO 32000-1 Algorithm 2 步骤 a） */
const PAD = new Uint8Array([
  0x28, 0xBF, 0x4E, 0x5E, 0x4E, 0x75, 0x8A, 0x41, 0x64, 0x00, 0x4E, 0x56, 0xFF, 0xFA, 0x01, 0x08,
  0x2E, 0x2E, 0x00, 0xB6, 0xD0, 0x68, 0x3E, 0x80, 0x2F, 0x0C, 0xA9, 0xFE, 0x64, 0x53, 0x69, 0x7A,
])

/** 把口令截断/补足到 32 字节。
 *  PDF R2 标准安全的口令按 PDFDocEncoding/Latin-1 取字节（ISO 32000-1 §7.6.3.3）：
 *  TextEncoder 的 UTF-8 会让非 ASCII 口令在其他阅读器里派生出不同文件密钥打不开
 *  （第四轮审查）。按 code unit & 0xff 映射，ASCII 口令行为不变。 */
function padPassword(pw) {
  const b = new Uint8Array(32)
  const s = String(pw || '')
  const n = Math.min(32, s.length)
  for (let i = 0; i < n; i++) b[i] = s.charCodeAt(i) & 0xff
  b.set(PAD.subarray(0, 32 - n), n)
  return b
}

function concat(...arrs) {
  const total = arrs.reduce((s, a) => s + a.length, 0)
  const out = new Uint8Array(total)
  let o = 0
  for (const a of arrs) { out.set(a, o); o += a.length }
  return out
}

/** Algorithm 3：由 owner/user 口令算出 /O 值（R=2，n=5） */
function computeO(userPw, ownerPw) {
  const key = md5(padPassword(ownerPw || userPw))
  const upad = padPassword(userPw)
  return rc4(key.subarray(0, 5), upad)
}

/** Algorithm 2：算加密密钥（R=2，n=5，长度 5 字节） */
function computeKey(userPw, O, P, idBytes) {
  const pArr = new Uint8Array(4)
  new DataView(pArr.buffer).setInt32(0, P, true)
  const digest = md5(concat(padPassword(userPw), O, pArr, idBytes))
  return digest.subarray(0, 5)
}

/** Algorithm 4（R=2）：算 /U 值 */
function computeU(key) {
  return rc4(key, PAD)
}

/**
 * 创建加密器。
 * @param {string} userPw   打开文档所需口令（空串 = 不加密）
 * @param {string} ownerPw  权限口令（留空则同 userPw）
 * @param {number} P        权限位（有符号 32 位，见 ISO 表 22）
 * @returns {{ dict: string, encryptObject: (num:number, gen:number, bytes:Uint8Array)=>Uint8Array } | null}
 */
export function createEncryptor(userPw, ownerPw, P = -60) {
  if (!userPw) return null
  const idBytes = crypto.getRandomValues(new Uint8Array(16))
  const O = computeO(userPw, ownerPw || userPw)
  const key = computeKey(userPw, O, P, idBytes)
  const U = computeU(key)

  const hex = toHexString   // 复用导出实现，避免两份逻辑漂移（第四轮审查）
  const dict = `<< /Filter /Standard /V 1 /R 2 /Length 40 /P ${P} ` +
    `/O <${hex(O)}> /U <${hex(U)}> >>`

  /** 每个对象用「密钥 + 对象号 + 生成号」派生独立 RC4 密钥
   *  尾部固定 5 字节：对象号低 3 字节 + 生成号 2 字节（均小端，ISO 32000-1 §7.6.3.3） */
  function objectKey(num, gen) {
    const tail = new Uint8Array(5)
    tail[0] = num & 0xff
    tail[1] = (num >> 8) & 0xff
    tail[2] = (num >> 16) & 0xff
    tail[3] = gen & 0xff
    tail[4] = (gen >> 8) & 0xff
    return md5(concat(key, tail)).subarray(0, 10)
  }

  return {
    dict,
    idBytes,
    encryptObject: (num, gen, bytes) => rc4(objectKey(num, gen), bytes),
  }
}

/** PDF 十六进制字符串（加密后写入 /ID 等） */
export function toHexString(u8) {
  return Array.from(u8).map((b) => b.toString(16).padStart(2, '0')).join('')
}
