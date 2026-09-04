// 共享文档实时协同中继
// 技术：Hocuspocus（Yjs 官方推荐的服务端）+ mysql2 直连
// 职责：
//   1) 连接鉴权：复刻后端 TokenUtil 的 HMAC-SHA256 校验，解析 userId，再查 kb_user 校验
//      账号状态（enabled）与令牌版本（token_version，改密/重置后旧 token 立即失效，
//      与 HTTP 侧 AuthFilter 同标准），最后查 kb_doc_share_permission 判权限
//   2) 房间广播：同 documentName（= shareId:docId）的客户端互相同步 Yjs 更新与 Awareness（光标）
//   3) 持久化：把 Yjs 文档二进制状态落库到 kb_shared_document.yjs_state（防崩定时 + 断开时落库）
//   4) 操作日志：每次合法 update 追加写入 kb_doc_op_log（可靠投递、审计、回放）
//
// 该服务为纯内网自托管 Node 进程（非外部 SaaS），与 Spring Boot 共享同一 MySQL 与 KB_TOKEN_SECRET。

import { Server } from '@hocuspocus/server'
import * as Y from 'yjs'
import mysql from 'mysql2/promise'
import crypto from 'node:crypto'

const PORT = Number(process.env.COLLAB_PORT || 1234)
const SECRET = process.env.KB_TOKEN_SECRET || 'kb-dev-secret-please-change-this-long-string-2026'
// C: 缩小丢失窗口，默认从 15s 降到 5s；可通过环境变量覆盖
const SAVE_INTERVAL_MS = Number(process.env.COLLAB_SAVE_INTERVAL || 5000)
// A: 操作日志开关；默认开启
const OP_LOG_ENABLED = process.env.COLLAB_OP_LOG_ENABLED !== 'false'

const db = mysql.createPool({
  host: process.env.COLLAB_DB_HOST || 'localhost',
  port: Number(process.env.COLLAB_DB_PORT || 3306),
  user: process.env.COLLAB_DB_USER || 'kb',
  password: process.env.COLLAB_DB_PASS || 'kb',
  database: process.env.COLLAB_DB_NAME || 'kb',
  waitForConnections: true,
  connectionLimit: 5,
  connectTimeout: 8000,
})

// 包装一次查询并打日志，便于定位"卡连接中"到底是 DB 问题还是鉴权问题
async function q(sql, params) {
  try {
    return await db.query(sql, params)
  } catch (e) {
    console.error('[relay][DB错误]', e.message)
    throw e
  }
}

// ---------- 复刻后端 TokenUtil 的令牌校验 ----------
function b64urlDecode(s) {
  return Buffer.from(s, 'base64url')
}

function verifyToken(token) {
  if (!token || token.indexOf('.') < 0) throw new Error('invalid token')
  const dot = token.indexOf('.')
  const payload = token.slice(0, dot)
  const sig = token.slice(dot + 1)
  const expected = crypto.createHmac('sha256', SECRET).update(payload).digest('base64url')
  const a = Buffer.from(expected)
  const b = Buffer.from(sig)
  if (a.length !== b.length || !crypto.timingSafeEqual(a, b)) throw new Error('bad signature')
  const f = b64urlDecode(payload).toString('utf8').split('|')
  if (f.length < 3) throw new Error('bad payload')
  const exp = Number(f[2])
  if (Number.isNaN(exp) || exp < Date.now()) throw new Error('token expired')
  const userId = f.length > 3 && f[3] ? Number(f[3]) : 0
  // 第 6 段 tokenVersion：旧版 5 段 token（无版本）回退为 0，与后端 TokenUtil.parse 同兼容策略
  const tokenVersion = f.length > 5 && f[5] ? Number(f[5]) : 0
  return { userId, role: f[1] || '', username: f[0] || '', tokenVersion }
}

// documentName 约定为 `${shareId}:${docId}`
function parseName(name) {
  const [shareId, docId] = String(name || '').split(':')
  return { shareId: Number(shareId), docId: Number(docId) }
}

// 复刻 DocShareService.myPermission：OWNER / ADMIN / WRITE / READ / null
async function myPermission(shareId, userId, role) {
  if (role === 'ADMIN') return 'OWNER'
  const [rows] = await q(
    `SELECT s.owner_id AS ownerId, p.permission AS perm
       FROM kb_doc_share s
       LEFT JOIN kb_doc_share_permission p ON p.share_id = s.id AND p.user_id = ?
      WHERE s.id = ?`,
    [userId, shareId]
  )
  if (!rows.length) return null
  const r = rows[0]
  if (r.ownerId === userId) return 'OWNER'
  return r.perm || null
}

const CURSOR_COLORS = ['#f43f5e', '#f59e0b', '#10b981', '#3b82f6', '#8b5cf6', '#ec4899', '#14b8a6', '#ef4444']
function colorFor(seed) {
  let h = 0
  for (const c of String(seed)) h = (h * 31 + c.charCodeAt(0)) >>> 0
  return CURSOR_COLORS[h % CURSOR_COLORS.length]
}

// 已加载文档缓存（documentName -> Y.Doc），用于定时落库与崩溃保护
const loadedDocs = new Map()

async function persist(docName, doc) {
  const { docId } = parseName(docName)
  if (!docId) return
  const state = Y.encodeStateAsUpdate(doc)
  await db.query('UPDATE kb_shared_document SET yjs_state = ? WHERE id = ?', [state, docId])
}

// A: 追加写入操作日志（update + 状态向量），用于可靠恢复、审计、回放
async function persistOpLog(docName, doc, update, context) {
  if (!OP_LOG_ENABLED) return
  const { docId } = parseName(docName)
  if (!docId || !update || !update.length) return
  const userId = context?.user?.id || null
  const stateVector = Y.encodeStateVector(doc)
  const clientId = doc.clientID || null
  try {
    await db.query(
      `INSERT INTO kb_doc_op_log
         (doc_id, user_id, op_type, update_blob, state_vector, client_id, content_length)
       VALUES (?, ?, ?, ?, ?, ?, ?)`,
      [docId, userId, 'UPDATE', Buffer.from(update), Buffer.from(stateVector), clientId, update.length]
    )
  } catch (e) {
    // 日志失败不阻断协同与快照；但要告警，便于排查
    console.error('[relay][op-log 失败]', docName, e.message)
  }
}

async function persistAll() {
  const tasks = []
  for (const [name, doc] of loadedDocs) tasks.push(persist(name, doc).catch((e) => console.error('[save]', name, e.message)))
  if (tasks.length) await Promise.allSettled(tasks)
}

const server = Server.configure({
  port: PORT,
  name: 'collab',

  async onAuthenticate({ token, documentName }) {
    try {
      const claims = verifyToken(token)
      const { shareId, docId } = parseName(documentName)
      if (!shareId || !docId) throw new Error('invalid document')
      // 账号状态与令牌版本校验（与 HTTP 侧 AuthFilter 同标准）：
      // 用户被禁用、或改密/重置密码导致 token_version 自增后，旧 token 在协同侧同样立即失效
      const [urows] = await q(
        'SELECT avatar, display_name, enabled, token_version FROM kb_user WHERE id = ?',
        [claims.userId]
      )
      if (!urows.length) throw new Error('user not found')
      const u = urows[0]
      // BIT(1) 列 mysql2 返回 Buffer，需转为数值再判（Buffer 对象恒为 truthy，直接 !u.enabled 会失效）
      const enabled = Buffer.isBuffer(u.enabled) ? u.enabled[0] === 1 : Boolean(u.enabled)
      if (!enabled) throw new Error('account disabled')
      if (Number(u.token_version || 0) !== (claims.tokenVersion || 0)) {
        throw new Error('token revoked (password changed)')
      }
      const perm = await myPermission(shareId, claims.userId, claims.role)
      if (!perm) {
        console.warn(`[relay][鉴权拒绝] doc=${documentName} user=${claims.username}(${claims.userId}) 无权限`)
        throw new Error('forbidden')
      }
      // 同步数据库头像与昵称，供前端协作光标/在线列表展示（修复头像与 kb_user 不一致）
      const avatar = u.avatar || ''
      const displayName = u.display_name || claims.username
      console.log(`[relay][鉴权通过] doc=${documentName} user=${displayName}(${claims.userId}) perm=${perm}`)
      return {
        user: {
          id: claims.userId,
          name: displayName,
          role: claims.role,
          permission: perm,
          color: colorFor(claims.userId),
          avatar,
        },
        // 挂在 connection 上供 beforeHandleMessage 做写入级校验
        permission: perm,
      }
    } catch (e) {
      console.error(`[relay][鉴权失败] doc=${documentName} -> ${e.message}`)
      throw e
    }
  },

  async onLoadDocument({ documentName, document }) {
    const { docId } = parseName(documentName)
    console.log(`[relay][加载文档] doc=${documentName}`)
    const [rows] = await q('SELECT yjs_state FROM kb_shared_document WHERE id = ?', [docId])
    const state = rows[0] && rows[0].yjs_state
    if (state && state.length) Y.applyUpdate(document, state)
    loadedDocs.set(documentName, document)
    console.log(`[relay][文档就绪] doc=${documentName} 已有状态=${!!(state && state.length)}`)
    return document
  },

  // 写入级校验：只读（READ）客户端只能拉取文档与广播在线状态，
  // 不得向文档推送任何 Yjs 更新（否则会绕过前端 editable 锁、把只读端的编辑写进正文）。
  // Hocuspocus 在调用本钩子时会把「客户端推送的 Yjs 文档更新」放在 payload.update 字段
  // （awareness 光标/在线消息不带 update，自然放行）。返回 false = 丢弃该消息；true = 放行。
  beforeHandleMessage({ connection, documentName, update }) {
    const perm = connection.permission
    const canWrite = perm === 'OWNER' || perm === 'ADMIN' || perm === 'WRITE'
    if (canWrite) return true

    // update 为 truthy 表示这是一条文档内容更新（含 sync step2 / 增量 update）；
    // 只读用户不得写入，直接丢弃，保护文档不被篡改。awareness 消息 update 为 undefined，放行。
    if (update) {
      console.warn(`[relay][拒绝写入] doc=${documentName} 只读用户试图推送文档更新，已丢弃`)
      return false
    }
    return true
  },

  // A: 每次文档变更（任意客户端合法写入）追加操作日志
  async onChange({ documentName, document, update, context }) {
    await persistOpLog(documentName, document, update, context)
  },

  async onStoreDocument({ documentName, document }) {
    await persist(documentName, document)
  },

  onDisconnect({ documentName, document }) {
    // C: 用户断开时立即 flush 一次快照，缩小崩溃丢失窗口
    console.log(`[relay][断开] doc=${documentName}`)
    void persist(documentName, document).catch((e) => console.error('[save][disconnect]', documentName, e.message))

    // 房间空了且超过宽限期再移除缓存；期间仍可被定时落库覆盖
    setTimeout(() => {
      const stillOpen = document.getConnectionsCount ? document.getConnectionsCount() : 0
      if (stillOpen === 0 && loadedDocs.get(documentName) === document) {
        loadedDocs.delete(documentName)
      }
    }, 5 * 60 * 1000).unref?.()
  },
})

// 定时落库，避免进程崩溃丢失未断开时的增量
const timer = setInterval(() => {
  persistAll().catch((e) => console.error('[persistAll]', e.message))
}, SAVE_INTERVAL_MS)
timer.unref?.()

async function shutdown() {
  console.log('\n[relay] 正在落库并关闭…')
  clearInterval(timer)
  await persistAll().catch(() => {})
  await db.end().catch(() => {})
  process.exit(0)
}
process.on('SIGINT', shutdown)
process.on('SIGTERM', shutdown)

server.listen({ port: PORT, host: '0.0.0.0' })
  .then(() => {
    console.log(`[relay] 共享文档协同中继已启动：ws://0.0.0.0:${PORT}`)
    console.log(`[relay] 令牌密钥 = ${SECRET === 'kb-dev-secret-please-change-this-long-string-2026' ? '默认值(与后端默认一致)' : '已通过环境变量注入'}`)
    console.log(`[relay] 数据库 = ${process.env.COLLAB_DB_HOST || 'localhost'}:${process.env.COLLAB_DB_PORT || 3306}/${process.env.COLLAB_DB_NAME || 'kb'}`)
  })
  .catch((e) => {
    console.error('[relay][启动失败]', e.message)
    process.exit(1)
  })
