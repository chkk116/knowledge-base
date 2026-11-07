/**
 * useMarkdownView 单测（零依赖）：node --test frontend/tests/useMarkdownView.test.mjs
 * 重点守住搬进共用模块后的安全行为与格式化输出，确保「只搬函数不改行为」。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { useMarkdownView, fmtMs, fmtTokens, openSource } from '../src/features/ai/useMarkdownView.js'

test('renderMarkdown: 基础语法与 XSS（html:false）', () => {
  const { renderMarkdown } = useMarkdownView()
  assert.match(renderMarkdown('**粗体**'), /<strong>粗体<\/strong>/)
  assert.match(renderMarkdown('# 标题'), /<h1>标题<\/h1>/)
  const xss = renderMarkdown('<script>alert(1)</script>')
  assert.ok(!xss.includes('<script>'))
  assert.equal(renderMarkdown(''), '')
})

test('renderMarkdown: 外部图片降级为链接，本站 /uploads 与 data:image 正常渲染', () => {
  const { renderMarkdown } = useMarkdownView()
  const ext = renderMarkdown('![](https://attacker.example/log?d=1)')
  assert.match(ext, /\[外部图片，点击查看\]/)
  assert.ok(!ext.includes('<img'))
  const local = renderMarkdown('![说明](/uploads/a.png)')
  assert.match(local, /<img/)
  assert.match(local, /src="\/uploads\/a\.png"/)
  const data = renderMarkdown('![](data:image/png;base64,AAAA)')
  assert.match(data, /<img/)
})

test('fence: 语言标识白名单，防属性注入；默认高亮转义 HTML', () => {
  const { renderMarkdown } = useMarkdownView()
  const h = renderMarkdown('```js" onmouseover="alert(1)\n<script>bad</script>\n```')
  assert.match(h, /class="md-code-block"/)
  assert.match(h, /language-js/)
  assert.ok(!h.includes('onmouseover'))
  assert.ok(!h.includes('<script>'))
  assert.match(h, /&lt;script&gt;/)
})

test('fence: 注入的 highlight 生效（桌面端接 Prism 的契约）', () => {
  const { renderMarkdown } = useMarkdownView({ highlight: (code, lang) => `[${lang}]${code.trim()}` })
  const h = renderMarkdown('```python\nx=1\n```')
  assert.match(h, /\[python\]x=1/)
})

test('fmtMs / fmtTokens: 各格式兼容', () => {
  assert.equal(fmtMs(0), '')
  assert.equal(fmtMs(850), '850ms')
  assert.equal(fmtMs(1500), '1.50s')
  assert.equal(fmtTokens(null), '')
  assert.equal(fmtTokens({ total_tokens: 100, prompt_tokens: 30, completion_tokens: 70 }), '100 tokens（输入 30 / 输出 70）')
  assert.equal(fmtTokens({ input_tokens: 10, output_tokens: 5 }), '15 tokens（输入 10 / 输出 5）')
})

test('openSource: 无 window 环境（Node）不抛错', () => {
  assert.doesNotThrow(() => openSource(12))
  assert.doesNotThrow(() => openSource(null))
})

test('useMarkdownView 返回的函数集合完整（组件解构契约）', () => {
  const api = useMarkdownView()
  for (const k of ['renderMarkdown', 'fmtMs', 'fmtTokens', 'openSource', 'escapeHtml']) {
    assert.equal(typeof api[k], 'function', k)
  }
  assert.equal(api.escapeHtml('<b>'), '&lt;b&gt;')
})
