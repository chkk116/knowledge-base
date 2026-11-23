/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

import test from 'node:test'
import assert from 'node:assert/strict'
import { mergeTagOptions } from '../src/utils/tagOptions.js'

test('两个来源按名称去重，计数取较大者', () => {
  const out = mergeTagOptions(
    [{ tag: '测试', count: 3 }, { tag: '导图', count: 1 }],
    [{ id: 7, name: '测试', count: 1 }, { id: 8, name: '空标签', count: 0 }],
  )
  assert.deepEqual(out.map((t) => t.name), ['测试', '导图', '空标签'])
  assert.equal(out[0].count, 3)
  assert.equal(out[1].count, 1)
})

test('规范标签 id 优先，没有规范行时用 doc- 前缀做稳定 key', () => {
  const out = mergeTagOptions([{ tag: '测试', count: 2 }], [{ id: 7, name: '测试', count: 0 }])
  assert.equal(out[0].id, 7)
  const onlyDoc = mergeTagOptions([{ tag: '测试', count: 2 }], [])
  assert.equal(onlyDoc[0].id, 'doc-测试')
})

test('空白名称被丢弃，来源为空时返回空数组', () => {
  assert.deepEqual(mergeTagOptions(null, undefined), [])
  assert.deepEqual(mergeTagOptions([{ tag: '   ', count: 5 }, { tag: '', count: 1 }], []), [])
})

test('按使用频次降序，同频次按名称稳定排序', () => {
  const out = mergeTagOptions(
    [{ tag: 'B', count: 1 }, { tag: 'A', count: 1 }, { tag: 'C', count: 9 }],
    [],
  )
  assert.deepEqual(out.map((t) => t.name), ['C', 'A', 'B'])
})
