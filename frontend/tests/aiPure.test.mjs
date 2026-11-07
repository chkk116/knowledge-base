/**
 * aiPure.js 纯函数单测（零依赖）：node --test frontend/tests/aiPure.test.mjs
 *
 * 覆盖 AiChat / MobileAiChat 拆分第一步抽出的 6 个函数，重点守住安全相关行为
 * （mdToHtml 的 XSS 转义与图片源白名单）和两端共用后的参数契约。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  mkey, sanitizeAnswer, sanitizeInput, cleanTitle, mdToHtml, buildQuestionWithAttachments,
} from '../src/utils/aiPure.js'

test('mkey: 用户模型与系统模型带不同前缀，避免 id 冲突', () => {
  assert.equal(mkey({ scope: 'user', id: 7 }), 'u:7')
  assert.equal(mkey({ scope: 'system', id: 7 }), 's:7')
  assert.equal(mkey({ id: 3 }), 's:3')
})

test('sanitizeInput: 剥离 Agent 内部标记与角色前缀，压缩空行', () => {
  // 注意：剥角色前缀的 ^\s* 会连前面的空行一起吃掉（现有行为，抽取阶段不改）
  assert.equal(sanitizeInput('【任务】写文档\n\n\n助手：好的'), '写文档\n好的')
  assert.equal(sanitizeInput('  【最近的对话】\n用户：你好  '), '你好')
  assert.equal(sanitizeInput(null), '')
})

test('sanitizeAnswer: 只拦截「长文本 + 提示词 + TOOL:」的泄漏，不误伤正常回答', () => {
  const normal = '这是一段正常回答，提到工具列表也没关系，只要不出现 TOOL 指令。'
  assert.equal(sanitizeAnswer(normal), normal)
  const leaked = '工作方式要求\n可用工具（需要时输出）\n'.repeat(30) + '\nTOOL: search_kb'
  assert.equal(sanitizeAnswer(leaked), '本次没有生成有效回答，请重试一次。')
})

test('cleanTitle: 取首行有效文字、剥包装、限 20 字', () => {
  assert.equal(cleanTitle('【任务】<think>思考过程</think>\n第二行'), '新对话')
  assert.equal(cleanTitle('【任务】季度复盘报告'), '季度复盘报告')
  assert.equal(cleanTitle(''), '新对话')
  assert.equal(cleanTitle('a'.repeat(30)).length, 20)
})

test('buildQuestionWithAttachments: 纯函数，attachments 由调用方传入', () => {
  assert.equal(buildQuestionWithAttachments('问题', []), '问题')
  assert.equal(buildQuestionWithAttachments('问题'), '问题')          // 默认空数组
  const atts = [
    { name: 'a.txt', text: 'AAA', kind: 'file' },
    { name: 'b.png', text: 'BBB', kind: 'image' },
  ]
  const r = buildQuestionWithAttachments('问题', atts)
  assert.match(r, /【附件 1：a\.txt】\nAAA/)
  assert.match(r, /【附件 2：b\.png · 本地识别文字】\nBBB/)
  assert.match(r, /【提问】问题$/)
})

test('mdToHtml: 标题/加粗/列表/代码块', () => {
  const h = mdToHtml('# 标题\n**粗体**\n- 一\n- 二\n```\ncode\n```')
  assert.match(h, /<h1>标题<\/h1>/)
  assert.match(h, /<strong>粗体<\/strong>/)
  assert.match(h, /<ul><li>一<\/li><li>二<\/li><\/ul>/)
  assert.match(h, /<pre>code<\/pre>/)
})

test('mdToHtml: 不再有 HTML 直通分支，事件属性一律转义', () => {
  const a = mdToHtml('<div onmouseover="alert(1)">x</div>')
  assert.ok(!a.includes('<div onmouseover'))   // 标签+属性结构必须被转义，不能成为可执行 HTML
  assert.ok(!a.includes('<div'))
  assert.match(a, /&lt;div/)
  const b = mdToHtml('<p onclick="alert(1)">点我</p>')
  assert.ok(!b.includes('<p onclick'))
  assert.match(b, /&lt;p/)
})

test('mdToHtml: 提到 HTML 标签的普通回答按文本处理，不误判成 HTML', () => {
  const h = mdToHtml('HTML 里 <p> 表示段落，<div> 表示块')
  assert.match(h, /&lt;p&gt;/)
  assert.match(h, /表示段落/)
  assert.ok(!h.includes('<p> 表示段落'))   // 输入标签已转义，输出里的 <p> 只是正文包裹
})

test('mdToHtml: XSS 转义（尖括号与引号）', () => {
  const h = mdToHtml('<script>alert(1)</script>')
  assert.ok(!h.includes('<script>'))
  assert.match(h, /&lt;script&gt;/)
})

test('mdToHtml: 图片源白名单 —— 外站 URL 不渲染 img，本站 /uploads 与 data:image 放行', () => {
  // 单行纯 HTML 会命中「正文已是 HTML」直通分支（安全性由后端 HtmlSanitizer 兜底）；
  // 混合 Markdown 内容才走逐行解析与图片源白名单，这里验证白名单确实拦下外站 URL。
  const evil = mdToHtml('# 标题\n<img src="https://evil.example/x" onerror="alert(1)">')
  assert.ok(!evil.includes('<img'))
  const site = mdToHtml('# 标题\n<img src="/uploads/a.png" alt="图">')
  assert.match(site, /class="ai-html-img"/)
  assert.match(site, /src="\/uploads\/a\.png"/)
  const data = mdToHtml('# 标题\n<img src="data:image/png;base64,AAAA" alt="x">')
  assert.match(data, /class="ai-html-img"/)
})

test('mdToHtml: Markdown 图片语法的 alt 含引号也必须转义', () => {
  const h = mdToHtml('![" onerror="alert(1)](https://evil/x.png)')
  // 该语法会生成 img（源不做白名单，由渲染层处理），但属性引号必须转义，不能出现未转义的 onerror 属性
  assert.ok(!h.includes('alt="" onerror=""') || h.includes('&quot;'))
  assert.match(h, /&quot;/)
})

test('mdToHtml: 视频/音频语法', () => {
  assert.match(mdToHtml('@[video](/uploads/a.mp4)'), /<video src="\/uploads\/a\.mp4" controls>/)
  assert.match(mdToHtml('@[audio](/uploads/a.mp3)'), /<audio src="\/uploads\/a\.mp3" controls>/)
})
