import test from 'node:test'
import assert from 'node:assert/strict'
import { toggleSelection, toggleAllSelection, removeConversations } from '../src/features/ai/conversationRules.js'

test('单个会话选中状态来回切换且返回新数组', () => {
  const a = []
  const b = toggleSelection(a, 3)
  assert.deepEqual(b, [3])
  assert.deepEqual(a, [], '不修改原数组')
  assert.deepEqual(toggleSelection(b, 3), [])
  assert.deepEqual(toggleSelection(b, 4), [3, 4])
})

test('全选与清空互切', () => {
  const ids = [1, 2, 3]
  assert.deepEqual(toggleAllSelection([], ids), [1, 2, 3])
  assert.deepEqual(toggleAllSelection([1, 2, 3], ids), [])
  assert.deepEqual(toggleAllSelection([1], ids), [1, 2, 3])
})

test('长度相等但含陈旧 id 时应重新全选，而不是清空', () => {
  const ids = [1, 2]
  assert.deepEqual(toggleAllSelection([1, 999], ids), [1, 2])
  assert.deepEqual(toggleAllSelection([1, 2], ids), [])
})

test('选中状态按字符串归一，数字与字符串 id 都能切掉', () => {
  assert.deepEqual(toggleSelection([1], '1'), [])
  assert.deepEqual(toggleSelection([], '1'), ['1'])
})

test('删除会话时数字与字符串 id 都能匹配', () => {
  const list = [{ id: 1 }, { id: '2' }, { id: 3 }]
  assert.deepEqual(removeConversations(list, [1, '2']), [{ id: 3 }])
  assert.deepEqual(removeConversations(list, []), list)
  assert.equal(list.length, 3, '不修改原数组')
})
