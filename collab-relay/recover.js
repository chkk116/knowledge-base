// 共享文档协同操作日志恢复工具
// 用途：当 yjs_state 快照损坏/丢失，或需要重建某个时刻的文档状态时，
//       按 kb_doc_op_log 的时间顺序重放 update，生成完整 state 并写回 kb_shared_document。
//
// 用法（在项目根目录或 collab-relay 目录）：
//   node collab-relay/recover.js <docId> [yyyy-mm-dd HH:MM:SS]
// 示例：
//   node collab-relay/recover.js 7
//   node collab-relay/recover.js 7 "2026-08-24 12:00:00"
//
// 环境变量（与 index.js 对齐，缺省走本地开发配置）：
//   COLLAB_DB_HOST / COLLAB_DB_PORT / COLLAB_DB_USER / COLLAB_DB_PASS / COLLAB_DB_NAME

import * as Y from 'yjs'
import mysql from 'mysql2/promise'

const docId = Number(process.argv[2])
const untilArg = process.argv[3]

if (!docId) {
  console.error('[recover] 用法：node recover.js <docId> ["yyyy-mm-dd HH:MM:SS"]')
  process.exit(1)
}

const until = untilArg ? new Date(untilArg.replace(' ', 'T') + '+08:00') : null
if (untilArg && Number.isNaN(until.getTime())) {
  console.error('[recover] 时间格式错误，请用 "yyyy-mm-dd HH:MM:SS"')
  process.exit(1)
}

const db = mysql.createPool({
  host: process.env.COLLAB_DB_HOST || 'localhost',
  port: Number(process.env.COLLAB_DB_PORT || 3306),
  user: process.env.COLLAB_DB_USER || 'kb',
  password: process.env.COLLAB_DB_PASS || 'kb',
  database: process.env.COLLAB_DB_NAME || 'kb',
  connectionLimit: 2,
})

async function recover() {
  let sql = 'SELECT update_blob, created_at FROM kb_doc_op_log WHERE doc_id = ? ORDER BY id ASC'
  const params = [docId]
  if (until) {
    sql = 'SELECT update_blob, created_at FROM kb_doc_op_log WHERE doc_id = ? AND created_at <= ? ORDER BY id ASC'
    params.push(until)
  }

  const [rows] = await db.query(sql, params)
  if (!rows.length) {
    console.error(`[recover] docId=${docId} 在操作日志中没有记录`)
    await db.end()
    process.exit(1)
  }

  console.log(`[recover] 找到 ${rows.length} 条操作日志，开始重放…`)
  const doc = new Y.Doc()
  for (const row of rows) {
    const update = row.update_blob
    if (update && update.length) {
      Y.applyUpdate(doc, update)
    }
  }

  const state = Y.encodeStateAsUpdate(doc)
  await db.query('UPDATE kb_shared_document SET yjs_state = ? WHERE id = ?', [state, docId])
  console.log(`[recover] 完成：docId=${docId}，重建后状态 ${state.length} 字节（截至 ${until ? until.toISOString() : '最新'}）`)
  await db.end()
}

recover().catch((e) => {
  console.error('[recover] 失败：', e.message)
  process.exit(1)
})
