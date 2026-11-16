import test from 'node:test'
import assert from 'node:assert/strict'
import { isNearBlack, pseudoLevel, buildTocTree, flattenToc } from '../src/features/post/postDoc.js'

test('近黑颜色识别覆盖 black / hex3 / hex6 / rgb', () => {
  assert.equal(isNearBlack('black'), true)
  assert.equal(isNearBlack('#000'), true)
  assert.equal(isNearBlack('#1a1a1a'), true)
  assert.equal(isNearBlack('rgb(1, 2, 3)'), true)
  assert.equal(isNearBlack('#ffffff'), false)
  assert.equal(isNearBlack('rgba(255, 0, 0, .5)'), false)
  assert.equal(isNearBlack(''), false)
  assert.equal(isNearBlack(null), false)
})

test('编号段落按中文数字与小数点推层级，正文不误判', () => {
  assert.equal(pseudoLevel('一、项目概述'), 1)
  assert.equal(pseudoLevel('1.1 方案优势'), 2)
  assert.equal(pseudoLevel('1.1.1：细节'), 3)
  assert.equal(pseudoLevel('1.1GB 流量说明'), null)
  assert.equal(pseudoLevel('这是一段普通正文'), null)
})

test('目录树按层级嵌套，超层级回退到父级', () => {
  const nodes = [
    { id: 'a', level: 1, children: [] },
    { id: 'b', level: 2, children: [] },
    { id: 'c', level: 3, children: [] },
    { id: 'd', level: 2, children: [] },
    { id: 'e', level: 1, children: [] },
  ]
  const tree = buildTocTree(nodes)
  assert.equal(tree.length, 2)
  assert.equal(tree[0].id, 'a')
  assert.equal(tree[0].children[0].id, 'b')
  assert.equal(tree[0].children[0].children[0].id, 'c')
  assert.equal(tree[0].children[1].id, 'd')
  assert.equal(tree[1].id, 'e')
})

test('目录压平保持文档顺序', () => {
  const tree = buildTocTree([
    { id: 'a', level: 1, children: [] },
    { id: 'b', level: 2, children: [] },
    { id: 'c', level: 1, children: [] },
  ])
  assert.deepEqual(flattenToc(tree).map((n) => n.id), ['a', 'b', 'c'])
})
