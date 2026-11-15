import test from 'node:test'
import assert from 'node:assert/strict'
import { openSource } from '../src/features/ai/useMarkdownView.js'

function withWindow(run) {
  const calls = []
  const previous = globalThis.window
  globalThis.window = { open: (...args) => calls.push(args) }
  try { run(calls) } finally {
    if (previous === undefined) delete globalThis.window
    else globalThis.window = previous
  }
}

test('公开文档来源对象取 id，不能拼出 [object Object]', () => {
  withWindow((calls) => {
    openSource({ id: 42, title: '报销制度' })
    assert.deepEqual(calls[0], ['/reader/42', '_blank'])
  })
})

test('共享文档来源走带空间鉴权的路径', () => {
  withWindow((calls) => {
    openSource({ shareId: 7, docId: 9, title: '团队文档' })
    assert.deepEqual(calls[0], ['/doc-shares/7?doc=9', '_blank'])
  })
})

test('数字 id 与无 id 对象', () => {
  withWindow((calls) => {
    openSource(123)
    assert.deepEqual(calls[0], ['/reader/123', '_blank'])
    openSource({ title: '没有 id' })
    openSource(null)
    assert.equal(calls.length, 1)
  })
})
