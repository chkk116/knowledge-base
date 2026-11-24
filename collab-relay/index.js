// 共享文档实时协同中继
// 技术：Hocuspocus（Yjs 官方推荐的服务端）+ pg 直连（PostgreSQL）
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
import pg from 'pg'
import crypto from 'node:crypto'

const PORT = Number(process.env.COLLAB_PORT || 1234)

// 令牌签名密钥：必须与后端同值。**缺失或仍是历史上随仓库外泄的弱默认串 → 直接退出**，
// 不再静默兜底 —— 兜底的话中继会用公开可见的密钥校验令牌，任何人都能伪造任意 userId
// 连入所有共享文档（Java 侧 TokenSecretProvider 已是同样的 fail-fast 语义，这里对齐）。
const LEAKED_DEFAULT_SECRET = 'kb-dev-secret-please-change-this-long-string-2026'
const SECRET = (process.env.KB_TOKEN_SECRET || '').trim()
if (!SECRET) {
  console.error('[relay][致命] 未配置 KB_TOKEN_SECRET，拒绝启动。'
    + '内嵌模式由后端自动注入；独立部署请显式设置与后端同值的 KB_TOKEN_SECRET。')
  process.exit(1)
}
if (SECRET === LEAKED_DEFAULT_SECRET) {
  console.error('[relay][致命] KB_TOKEN_SECRET 是已随仓库外泄的默认值，拒绝启动。'
    + '请更换为不少于 32 位的随机串（与后端 KB_TOKEN_SECRET 保持一致）。')
  process.exit(1)
}
// C: 缩小丢失窗口，默认从 15s 降到 5s；可通过环境变量覆盖
const SAVE_INTERVAL_MS = Number(process.env.COLLAB_SAVE_INTERVAL || 5000)
// A: 操作日志开关；默认开启
const OP_LOG_ENABLED = process.env.COLLAB_OP_LOG_ENABLED !== 'false'

const db = new pg.Pool({
  host: process.env.COLLAB_DB_HOST || 'localhost',
  port: Number(process.env.COLLAB_DB_PORT || 5432),
  user: process.env.COLLAB_DB_USER || 'kb',
  password: process.env.COLLAB_DB_PASS || 'kb',
  database: process.env.COLLAB_DB_NAME || 'kb',
  max: 5,
  connectionTimeoutMillis: 8000,
})

// 包装一次查询并打日志，便于定位"卡连接中"到底是 DB 问题还是鉴权问题
async function q(sql, params) {
  // pg 风格：$1/$2 占位符；返回 rows 数组（不再像 mysql2 返回 [rows, fields]）
  try {
    const r = await db.query(sql, params)
    return r.rows
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
  if (!Number.isSafeInteger(userId) || userId <= 0 || !Number.isSafeInteger(tokenVersion)
      || tokenVersion < 0 || !Number.isSafeInteger(exp)) throw new Error('bad claims')
  return { userId, role: f[1] || '', username: f[0] || '', tokenVersion, exp }
}

// 初始代次沿用 shareId:docId；回滚后必须使用 shareId:docId:revision。
// 禁止别名（前导零、:0、额外片段），避免同一文档被建成两个互相覆盖的房间。
function parseName(name) {
  const match = /^([1-9]\d*):([1-9]\d*)(?::([1-9]\d*))?$/.exec(String(name || ''))
  if (!match) throw new Error('invalid document')
  const [shareId, docId, revision] = [Number(match[1]), Number(match[2]), Number(match[3] || 0)]
  if (![shareId, docId, revision].every(Number.isSafeInteger)) throw new Error('invalid document')
  return { shareId, docId, revision }
}

const WRITE_PERMISSIONS = new Set(['OWNER', 'ADMIN', 'WRITE'])
const READ_PERMISSIONS = new Set([...WRITE_PERMISSIONS, 'READ'])

// 与后端 Perm.WHITELIST 去掉 DEMO_MODE 一一对应：权限码覆盖这一整组才算「完全管理员」。
// 漂移的风险是**单向**的，别写成「漏更新只是收窄」：
//   后端删码 → 这里仍要求它 → 该账号退回普通用户（收窄，安全）；
//   后端加码 → 这里清单偏短 → 这里放行、REST 不放行（放行面变宽，越权）。
// 所以下面在启动时对一遍后端源码，对不上就关掉「全权限码 = 管理员」这条旁路，宁可不给。
const ADMIN_PERM_SET = [
  'USER_MANAGE', 'DOC_REVIEW', 'POST_MANAGE', 'STATS_VIEW', 'BLACKLIST_MANAGE', 'TAXONOMY_MANAGE',
  'ANNOUNCEMENT_MANAGE', 'COURSE_MANAGE', 'EXAM_MANAGE', 'AI_MANAGE',
  'SHARE_CREATE', 'SHARE_VIEW', 'SHARE_DELETE', 'SHARE_CAPACITY', 'CORE_MAINTAIN',
  'SITE_FEATURE_MANAGE', 'SITE_SETTINGS_MANAGE', 'SSO_MANAGE', 'SYSTEM_STATUS_VIEW',
  'DOC_EXPORT', 'BANNED_WORDS_MANAGE', 'ASSET_MANAGE', 'AVATAR_REVIEW',
  'AUDIT_VIEW', 'LOGIN_LOG_VIEW', 'ADMIN_VIEW',
]
// 只有和后端清单核对通过（或后端源码不在本机、无从核对）时才启用这条旁路
let ADMIN_PERM_TRUSTED = true

/** 启动自检：读后端 Perm.java 的 WHITELIST，与本文件的清单比对 */
function verifyAdminPermSet() {
  const path = require('path').join(__dirname, '..', 'backend', 'src', 'main', 'java',
    'com', 'kb', 'kbplatform', 'security', 'Perm.java')
  let src
  try {
    src = require('fs').readFileSync(path, 'utf8')
  } catch {
    console.log('[relay] 未找到后端 Perm.java（独立部署？），跳过权限码清单自检')
    return
  }
  const block = src.match(/WHITELIST\s*=\s*Set\.of\(([\s\S]*?)\)\s*;/)
  if (!block) {
    console.error('[relay][警告] 解析不出 Perm.WHITELIST，关闭「全权限码视为管理员」的旁路')
    ADMIN_PERM_TRUSTED = false
    return
  }
  const backend = new Set((block[1].match(/"([A-Z_]+)"/g) || []).map((x) => x.replace(/"/g, '')))
  backend.delete('DEMO_MODE')
  const missing = [...backend].filter((c) => !ADMIN_PERM_SET.includes(c))   // 后端有、这里没跟上
  const extra = ADMIN_PERM_SET.filter((c) => !backend.has(c))              // 这里多要了（收窄）
  if (missing.length || extra.length) {
    console.error('[relay][警告] 权限码清单与后端不一致：后端新增但本文件缺失 ' + JSON.stringify(missing)
      + '，本文件多余 ' + JSON.stringify(extra) + '；已临时关闭「全权限码视为管理员」的旁路，'
      + '请同步 collab-relay/index.js 的 ADMIN_PERM_SET')
    ADMIN_PERM_TRUSTED = false
  }
}
verifyAdminPermSet()

// 与 REST 的 docPermission 保持一致：真实管理员/空间所有者/文档所有者优先，
// 文档单独授权优先于空间授权。使用下划线别名，避免 PG 把 ownerId 折叠成 ownerid。
async function authorizeDocument(documentName, claims) {
  const { shareId, docId, revision } = parseName(documentName)
  if (!claims || !Number.isSafeInteger(claims.userId) || !Number.isSafeInteger(claims.exp)
      || claims.exp <= Date.now()) throw new Error('token expired')
  const rows = await q(
    `SELECT u.enabled, u.token_version, u.role, u.avatar, u.display_name,
            d.owner_id AS doc_owner_id, s.owner_id AS space_owner_id, u.permissions AS user_permissions,
            COALESCE(d.yjs_rev, 0) AS yjs_rev,
            dp.permission AS doc_permission, sp.permission AS space_permission,
            sp.user_id AS space_member_id
       FROM kb_shared_document d
       JOIN kb_doc_share s ON s.id = d.share_id
       JOIN kb_user u ON u.id = $1
       LEFT JOIN kb_shared_doc_permission dp ON dp.doc_id = d.id AND dp.share_id = s.id AND dp.user_id = u.id
       LEFT JOIN kb_doc_share_permission sp ON sp.share_id = s.id AND sp.user_id = u.id
      WHERE d.id = $2 AND d.share_id = $3`,
    [claims.userId, docId, shareId]
  )
  const row = rows[0]
  if (!row || !(row.enabled === true || row.enabled === 1)) throw new Error('forbidden')
  if (Number(row.token_version || 0) !== claims.tokenVersion) throw new Error('token revoked')
  const perms = String(row.user_permissions || '')
  // 与 REST 的 isAdmin() 对齐：role=ADMIN，或权限码覆盖管理员全集（顺带认通配 *）。
  // 此前只认 role 和 *，于是「权限码全勾、系统角色仍是 USER」的账号在后台算管理员、
  // 到协同这儿却被当普通用户（S-M6）。清单与自检见文件头部的 ADMIN_PERM_SET。
  const permSet = new Set(perms.split(',').map((x) => x.trim().toUpperCase()).filter(Boolean))
  const isDemo = permSet.has('DEMO_MODE')   // 演示号只读，绝不算管理员
  const coverageIsAdmin = ADMIN_PERM_TRUSTED && ADMIN_PERM_SET.every((code) => permSet.has(code))
  const isAdminLike = !isDemo && (row.role === 'ADMIN' || permSet.has('*') || coverageIsAdmin)
  const isSpaceOwner = Number(row.space_owner_id) === claims.userId
  const isDocOwner = Number(row.doc_owner_id) === claims.userId
  const isSpaceMember = Number(row.space_member_id) === claims.userId
  // 空间成员是网关（N36）：被移出空间后，残留的文档级授权行不能继续放行。
  // 此前只比对 doc_permission/space_permission，文档级授权可以绕过「已不是空间成员」这件事，
  // 表现为 REST 拒绝、中继却还能连上并写协同状态。
  // 文档所有者同样**不单独放行**：removeMember 只删授权行、不清 kb_shared_document.owner_id，
  // 被移出空间的文档创建者若只凭 owner_id 就能连，分脑只是换了个载体（REST 403 / 中继 200）。
  // 文档所有者只在下面的权限档位上仍算 OWNER。
  if (!isAdminLike && !isSpaceOwner && !isSpaceMember) {
    console.warn('[collab] deny(not space member)', JSON.stringify({
      shareId, docId, userId: claims.userId, docOwner: row.doc_owner_id,
      docPerm: row.doc_permission, spacePerm: row.space_permission,
    }))
    throw new Error('forbidden')
  }
  let permission
  if (isAdminLike || isSpaceOwner || isDocOwner) permission = 'OWNER'
  else permission = row.doc_permission ?? row.space_permission
  if (!READ_PERMISSIONS.has(permission)) {
    // 拒绝原因写清楚：所有者被拒基本只有一种可能——doc_owner_id 为空（历史数据），
    // 此时 Number(null)=0 永远对不上 userId，需要补数据而不是改代码。
    console.warn('[collab] deny', JSON.stringify({
      shareId, docId, userId: claims.userId, role: row.role,
      docOwner: row.doc_owner_id, spaceOwner: row.space_owner_id,
      docPerm: row.doc_permission, spacePerm: row.space_permission,
      perms,
    }))
    throw new Error('forbidden')
  }
  if (Number(row.yjs_rev) !== revision) throw new Error('document revision changed')
  return { permission, avatar: row.avatar || '', name: row.display_name || claims.username, role: row.role }
}

function closeConnection(connection, reason = 'permission changed') {
  connection.readOnly = true
  connection.context ||= {}
  connection.context.revoked = true
  // Connection.close() 只移除房间关联；同时关闭底层 socket，阻止消息继续到达旧实例。
  try { connection.close({ code: 4403, reason }) } catch (e) { /* already closed */ }
  try { connection.webSocket?.close(4403, reason) } catch (e) { /* already closed */ }
}

async function refreshConnection(documentName, connection) {
  const context = connection.context || {}
  if (context.revoked) throw new Error('connection revoked')
  const access = await authorizeDocument(documentName, context.claims)
  if (context.revoked) throw new Error('connection revoked')
  const readOnly = !WRITE_PERMISSIONS.has(access.permission)
  const changed = connection.readOnly !== readOnly
  connection.readOnly = readOnly
  if (changed) {
    try { connection.sendStateless?.(JSON.stringify({ type: 'permission-changed', canWrite: !readOnly })) } catch (e) { /* disconnecting */ }
  }
  if (context.user) context.user.permission = access.permission
  context.permission = access.permission
  return access
}

const CURSOR_COLORS = ['#f43f5e', '#f59e0b', '#10b981', '#3b82f6', '#8b5cf6', '#ec4899', '#14b8a6', '#ef4444']
function colorFor(seed) {
  let h = 0
  for (const c of String(seed)) h = (h * 31 + c.charCodeAt(0)) >>> 0
  return CURSOR_COLORS[h % CURSOR_COLORS.length]
}

// 已加载文档缓存（documentName -> Y.Doc），用于定时落库与崩溃保护
const loadedDocs = new Map()

// 代次绑定 Y.Doc 实例，而非连接。任意成员断开不得清空房间代次，
// 未加载/已失效的实例绝不能用数据库当前代次作为兜底继续保存。
const documentRevisions = new WeakMap()
const invalidDocuments = new WeakSet()
const saveQueues = new WeakMap()

async function invalidateDocument(docName, doc) {
  if (invalidDocuments.has(doc)) return
  invalidDocuments.add(doc)
  if (loadedDocs.get(docName) === doc) loadedDocs.delete(docName)
  for (const connection of doc.getConnections?.() || []) {
    try { connection.sendStateless(JSON.stringify({ type: 'document-reset' })) } catch (e) { /* closing */ }
    closeConnection(connection, 'document revision changed')
  }
  // 当前版本的 API 接收 Document 对象，不是房间名字符串。
  if (server.documents.get(docName) === doc) await server.unloadDocument(doc)
}

async function persistCurrent(docName, doc) {
  if (invalidDocuments.has(doc) || !documentRevisions.has(doc) || loadedDocs.get(docName) !== doc) return
  const { docId, shareId } = parseName(docName)
  const revision = documentRevisions.get(doc)
  const state = Y.encodeStateAsUpdate(doc)
  // 代次与归属必须在同一条 UPDATE 内判断；先 SELECT 再无条件 UPDATE 有竞态窗口。
  // 这里只写 yjs_state：updated_at 交给编辑器保存快照时推进。中继定时落库是无差别的
  // （空闲房间也会每几秒写一次），如果在这里改 updated_at，编辑器手里的 baseUpdatedAt
  // 基线会立刻过期，下一次保存就 409。
  const result = await db.query(
    'UPDATE kb_shared_document SET yjs_state = $1 WHERE id = $2 AND share_id = $3 AND COALESCE(yjs_rev, 0) = $4',
    [state, docId, shareId, revision]
  )
  if (result.rowCount === 0) await invalidateDocument(docName, doc)
}

function persist(docName, doc) {
  // 定时、断线与框架保存可能重叠，按实例串行快照，防止旧请求晚完成覆盖新快照。
  const previous = saveQueues.get(doc) || Promise.resolve()
  const current = previous.catch(() => {}).then(() => persistCurrent(docName, doc))
  saveQueues.set(doc, current)
  return current
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
         (doc_id, user_id, op_type, update_blob, state_vector, client_id, content_length, created_at)
       VALUES ($1, $2, $3, $4, $5, $6, $7, now())`,
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

/**
 * 立即落库指定文档房间（后端存版本快照前调用）。
 *
 * 背景：定时落库有间隔，后端读 kb_shared_document.yjs_state 存快照时可能是几秒前的旧状态，
 * 版本里于是存的是「上一版协同态」——回滚后就出现「正文列已恢复、协同内容还是旧的」。
 * 返回是否真的存在在线房间：没有在线连接时数据库本来就是最新的，后端直接快照即可。
 */
async function flushDocument(shareId, docId) {
  const prefix = shareId + ':' + docId
  let flushed = false
  for (const [name, doc] of [...loadedDocs]) {
    if (name !== prefix && !name.startsWith(prefix + ':')) continue
    try { await persist(name, doc); flushed = true } catch (e) { console.error('[flush]', name, e.message) }
  }
  return flushed
}

const server = Server.configure({
  port: PORT,
  // 监听地址（Hocuspocus 的配置项名是 address，**不是** listen() 的 host；后者会被忽略）。
  // 默认 0.0.0.0 只收 IPv4：Windows 上浏览器访问 http://localhost:5173 常把 localhost 解析成 ::1，
  // 此时前端 ws://localhost:1234 会连不上、表现为协同「一直连接中」。
  // 绑 '::' 走双栈同时接受 IPv6 与 IPv4。可用 COLLAB_HOST 覆盖（如仅内网某网卡填具体 IP）。
  address: process.env.COLLAB_HOST || '::',
  name: 'collab',
  extensions: [{
    afterUnloadDocument({ documentName, instance }) {
      if (!instance.documents.has(documentName)) loadedDocs.delete(documentName)
    },
  }],

  async onAuthenticate({ token, documentName, connection }) {
    const claims = verifyToken(token)
    const access = await authorizeDocument(documentName, claims)
    connection.readOnly = !WRITE_PERMISSIONS.has(access.permission)
    return {
      claims,
      user: {
        id: claims.userId,
        name: access.name,
        role: access.role,
        permission: access.permission,
        color: colorFor(claims.userId),
        avatar: access.avatar,
      },
      // Hocuspocus 将返回值合入 context，不会直接挂在 connection 上。
      permission: access.permission,
    }
  },

  // 通知只是复检信号，不把 payload.perm 当授权事实（通知乱序/重放不能恢复旧权限）。
  async onRequest({ request, response }) {
    const url = new URL(request.url, 'http://localhost')
    // /revoke：权限变更后复检；/flush：后端保存版本前，要求把内存里的真实状态先落库
    if (url.pathname !== '/revoke' && url.pathname !== '/flush') return
    if (request.method !== 'POST') return
    const reply = (status, result) => {
      response.writeHead(status, { 'Content-Type': 'application/json' })
      response.end(JSON.stringify(result))
    }
    const chunks = []
    let size = 0
    for await (const chunk of request) {
      size += Buffer.byteLength(chunk)
      if (size > 8192) {
        reply(413, { error: 'request too large' })
        throw null // Hocuspocus 已响应请求须拒绝空值，终止默认响应，避免二次 writeHead。
      }
      chunks.push(Buffer.from(chunk))
    }
    const body = Buffer.concat(chunks).toString('utf8')
    const sign = String(request.headers['x-kb-relay-sign'] || '')
    const expected = crypto.createHmac('sha256', SECRET).update(body).digest('hex')
    if (!/^[0-9a-f]{64}$/.test(sign) || !crypto.timingSafeEqual(Buffer.from(sign), Buffer.from(expected))) {
      reply(403, { error: 'bad signature' })
      throw null
    }
    let payload
    try { payload = JSON.parse(body) } catch (e) { payload = null }
    const shareId = Number(payload?.shareId)
    const docId = payload?.docId == null ? null : Number(payload.docId)
    if (url.pathname === '/flush') {
      // 只需要文档身份：把该房间当前内存状态按代次 CAS 落库，供后端随后的版本快照使用
      if (!Number.isSafeInteger(shareId) || shareId <= 0
          || !Number.isSafeInteger(docId) || docId <= 0) {
        reply(400, { error: 'invalid shareId/docId' })
        throw null
      }
      const flushed = await flushDocument(shareId, docId)
      reply(200, { ok: true, flushed })
      throw null
    }
    const userId = Number(payload?.userId)
    if (![shareId, userId].every(n => Number.isSafeInteger(n) && n > 0)
        || (docId != null && (!Number.isSafeInteger(docId) || docId <= 0))) {
      reply(400, { error: 'invalid shareId/userId/docId' })
      throw null
    }
    const counts = await recheckPermissions({ shareId, userId, docId })
    reply(200, { ok: true, ...counts })
    throw null
  },

  async onLoadDocument({ documentName, document }) {
    const { docId, shareId, revision } = parseName(documentName)
    const rows = await q('SELECT yjs_state, yjs_rev, share_id FROM kb_shared_document WHERE id = $1 AND share_id = $2', [docId, shareId])
    const row = rows[0]
    if (!row) throw new Error('document not found')
    if (Number(row.yjs_rev || 0) !== revision) throw new Error('document revision changed')
    if (row.yjs_state?.length) Y.applyUpdate(document, row.yjs_state)
    documentRevisions.set(document, revision)
    loadedDocs.set(documentName, document)
    return document
  },

  // update 是完整协议帧，不能据其真值区分正文/光标；return false 也不会中止框架。
  // 每条消息复检权限，再由原生 readOnly 区分同步读取与正文写入。失效必须抛错。
  async beforeHandleMessage({ connection, documentName, document }) {
    try {
      if (invalidDocuments.has(document)) throw new Error('document revision changed')
      await refreshConnection(documentName, connection)
      if (invalidDocuments.has(document)) throw new Error('document revision changed')
    } catch (error) {
      if (error.message === 'document revision changed') await invalidateDocument(documentName, document)
      closeConnection(connection, error.message === 'document revision changed' ? error.message : 'authorization failed')
      throw error
    }
  },

  // A: 每次文档变更（任意客户端合法写入）追加操作日志
  async onChange({ documentName, document, update, context }) {
    await persistOpLog(documentName, document, update, context)
  },

  async onStoreDocument({ documentName, document }) {
    await persist(documentName, document)
  },

  async onDisconnect({ documentName, document }) {
    // 保留加载代次；保存完成后由框架卸载最后一个连接离开的房间。
    await persist(documentName, document).catch(e => console.error('[save][disconnect]', documentName, e.message))
  },
})

// 定时落库，避免进程崩溃丢失未断开时的增量
const timer = setInterval(() => {
  persistAll().catch((e) => console.error('[persistAll]', e.message))
}, SAVE_INTERVAL_MS)
timer.unref?.()

/**
 * F04：权限变更只改数据库、不会让既有长连接失效。
 * 定期复检每个在线连接在所属空间的真实权限：
 *  - 已无权限 → 直接关闭连接（否则能继续收广播、继续按旧资格写入）；
 *  - 由可写降为只读 → 切到原生 readOnly，后续写入在协议层被拒；
 *  - 只读恢复为可写 → 解除只读标记。
 * 握手时也校验，这里是「在线期间被撤权」的兜底。
 */
const PERM_RECHECK_MS = Number(process.env.COLLAB_PERM_RECHECK_MS || 15000)
async function recheckPermissions(filter = {}) {
  const counts = { checked: 0, closed: 0, downgraded: 0, restored: 0 }
  for (const [docName, document] of server.documents) {
    const { shareId, docId } = parseName(docName)
    if (filter.shareId != null && filter.shareId !== shareId) continue
    if (filter.docId != null && filter.docId !== docId) continue
    for (const connection of document.getConnections?.() || []) {
      const userId = connection.context?.claims?.userId
      if (filter.userId != null && filter.userId !== userId) continue
      counts.checked++
      const wasReadOnly = connection.readOnly
      try {
        await refreshConnection(docName, connection)
        if (!wasReadOnly && connection.readOnly) counts.downgraded++
        if (wasReadOnly && !connection.readOnly) counts.restored++
      } catch (error) {
        if (error.message === 'document revision changed') await invalidateDocument(docName, document)
        // 账号删除/禁用、令牌过期/吊销和查询失败均关闭连接，不能只查空间成员表。
        closeConnection(connection, 'authorization changed')
        counts.closed++
      }
    }
  }
  return counts
}
let permissionCheckRunning = false
const permTimer = setInterval(() => {
  if (permissionCheckRunning) return
  permissionCheckRunning = true
  recheckPermissions()
    .catch(e => console.error('[relay][权限复检失败]', e.message))
    .finally(() => { permissionCheckRunning = false })
}, PERM_RECHECK_MS)
permTimer.unref?.()

async function shutdown() {
  console.log('\n[relay] 正在落库并关闭…')
  clearInterval(timer)
  clearInterval(permTimer)
  await persistAll().catch(() => {})
  await db.end().catch(() => {})
  process.exit(0)
}
process.on('SIGINT', shutdown)
process.on('SIGTERM', shutdown)

// 监听地址由上方 Server.configure 的 address 决定（Hocuspocus 只认 address，不认 listen 的 host）。
server.listen({ port: PORT })
  .then(() => {
    console.log(`[relay] 共享文档协同中继已启动：ws://${process.env.COLLAB_HOST || '::'}:${PORT}（双栈）`)
    console.log(`[relay] 令牌密钥 = ${SECRET === 'kb-dev-secret-please-change-this-long-string-2026' ? '默认值(与后端默认一致)' : '已通过环境变量注入'}`)
    console.log(`[relay] 数据库 = ${process.env.COLLAB_DB_HOST || 'localhost'}:${process.env.COLLAB_DB_PORT || 5432}/${process.env.COLLAB_DB_NAME || 'kb'}`)
  })
  .catch((e) => {
    console.error('[relay][启动失败]', e.message)
    process.exit(1)
  })
