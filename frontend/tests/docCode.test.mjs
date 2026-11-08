/**
 * docCode.js 纯函数单测（零依赖）：node --test frontend/tests/docCode.test.mjs
 * 重点守双射（id ↔ 短码一一对应）、校验位、老数字链接兼容、路径升级。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  encodeDocId, decodeDocId, docIdFromParam, upgradeDocPath,
} from '../src/utils/docCode.js'

test('往返：1..2000 每个 id 都能还原，且短码定长 8 位', () => {
  const seen = new Set()
  for (let i = 1; i <= 2000; i++) {
    const code = encodeDocId(i)
    assert.equal(code.length, 8, 'id=' + i)
    assert.match(code, /^[0-9A-HJKMNP-TV-Z]{8}$/)
    assert.equal(decodeDocId(code), i, 'id=' + i + ' code=' + code)
    seen.add(code)
  }
  assert.equal(seen.size, 2000, '短码不能撞车')
})

test('边界 id 与非法 id', () => {
  assert.equal(decodeDocId(encodeDocId(46)), 46)
  assert.equal(decodeDocId(encodeDocId(34359738367)), 34359738367)
  assert.equal(encodeDocId(0), null)
  assert.equal(encodeDocId(-1), null)
  assert.equal(encodeDocId(1.5), null)
  assert.equal(encodeDocId('abc'), null)
  assert.equal(encodeDocId(null), null)
  assert.equal(encodeDocId(34359738368), null)   // 超出 35 bit 载荷
})

test('相邻 id 的短码不呈连续/递增规律', () => {
  const codes = Array.from({ length: 12 }, (_, i) => encodeDocId(38 + i))
  const diffs = codes.slice(1).map((c, i) => c === codes[i])
  assert.equal(diffs.some(Boolean), false)
  // 38 与 39 不应该只有末位不同（校验位单独变化也算太弱）
  const a = encodeDocId(38), b = encodeDocId(39)
  let same = 0
  for (let i = 0; i < 8; i++) if (a[i] === b[i]) same++
  assert.ok(same < 6, '相邻 id 的短码相似度过高：' + a + ' / ' + b)
})

test('校验位：改校验字符必失败；改载荷位约 31/32 被拦下；大小写不敏感', () => {
  const code = encodeDocId(38)
  assert.equal(decodeDocId(code.toLowerCase()), 38)
  // 最后一位就是 5 bit 校验位，改它必然对不上
  const last = code[7] === 'A' ? 'B' : 'A'
  assert.equal(decodeDocId(code.slice(0, 7) + last), null)
  // 载荷位：checksum 只有 5 bit，理论漏放 1/32，这里按统计口径守「绝大多数被拦」
  let blocked = 0
  for (const id of [1, 2, 38, 46, 999, 123456]) {
    const c = encodeDocId(id)
    for (let at = 0; at < 7; at++) {
      const ch = c[at] === 'A' ? 'B' : 'A'
      if (decodeDocId(c.slice(0, at) + ch + c.slice(at + 1)) === null) blocked++
    }
  }
  assert.ok(blocked >= 38, '载荷篡改拦截数偏低：' + blocked + '/42')
})

test('非法短码一律 null', () => {
  assert.equal(decodeDocId(''), null)
  assert.equal(decodeDocId('ABC'), null)
  assert.equal(decodeDocId('IIIIIIII'), null)   // 字母表里没有 I
  assert.equal(decodeDocId('!!!!!!!!'), null)
  assert.equal(decodeDocId(null), null)
  assert.equal(decodeDocId(38), null)
})

test('docIdFromParam：纯数字一律按老链接，短码逐个还原', () => {
  const code = encodeDocId(38)
  assert.equal(docIdFromParam(code), 38)
  assert.equal(docIdFromParam(code.toLowerCase()), 38)
  assert.equal(docIdFromParam('38'), 38)       // 守卫只改地址栏，params 仍是数字
  // 8 位数字 id 不能被当成短码解码（短码与老链接的歧义必须消除）
  assert.equal(docIdFromParam('12345678'), 12345678)
  for (let i = 1; i <= 2000; i++) assert.equal(docIdFromParam(encodeDocId(i)), i)
  assert.equal(docIdFromParam('0'), null)
  assert.equal(docIdFromParam('garbage!'), null)
  assert.equal(docIdFromParam(undefined), null)
})

test('upgradeDocPath 产出的短码不含纯数字形式，避免与数字 id 撞车', () => {
  for (let i = 1; i <= 2000; i++) {
    const p = upgradeDocPath('/post/' + i)
    assert.ok(p, 'id=' + i + ' 应升级')
    const code = p.split('/')[2]
    assert.equal(/^\d+$/.test(code), false, 'id=' + i + ' 得到可猜的纯数字短码 ' + code)
  }
})

test('upgradeDocPath：只升级数字文章路径，短码和其它路径不动', () => {
  const code = encodeDocId(38)
  assert.equal(upgradeDocPath('/post/38'), '/post/' + code)
  assert.equal(upgradeDocPath('/post/38/versions'), '/post/' + code + '/versions')
  assert.equal(upgradeDocPath('/reader/38'), '/reader/' + code)
  assert.equal(upgradeDocPath('/post/' + code), null)
  assert.equal(upgradeDocPath('/post/' + code + '/versions'), null)
  assert.equal(upgradeDocPath('/write/edit/38'), null)
  assert.equal(upgradeDocPath('/doc-shares/12/docs/34'), null)
  assert.equal(upgradeDocPath('/'), null)
})
