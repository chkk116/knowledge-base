import test from 'node:test'
import assert from 'node:assert/strict'
import { cleanSnippetMarkdown } from '../src/utils/text.js'

test('标题、粗体、斜体与行内代码标记被清除', () => {
  assert.equal(cleanSnippetMarkdown('## 报销制度\n**重点**说明与 *补充*，见 `手册`'),
    '报销制度 重点说明与 补充，见 手册')
})

test('链接与图片只保留可读文字', () => {
  assert.equal(cleanSnippetMarkdown('请看[流程说明](https://example.com/a)和![架构图](/uploads/x.png)'),
    '请看流程说明和架构图')
})

test('引用、列表标记与多余空白收敛', () => {
  assert.equal(cleanSnippetMarkdown('> 引言\n- 第一条\n* 第二条'), '引言 第一条 第二条')
})

test('普通文本不受影响，空值安全', () => {
  assert.equal(cleanSnippetMarkdown('这是一段普通摘要，含 3 个数字与 a_b 下划线。'),
    '这是一段普通摘要，含 3 个数字与 a_b 下划线。')
  assert.equal(cleanSnippetMarkdown(null), '')
  assert.equal(cleanSnippetMarkdown(undefined), '')
})
