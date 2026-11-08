/**
 * aiPure.js 纯函数单测（零依赖）：node --test frontend/tests/aiPure.test.mjs
 *
 * 覆盖 AiChat / MobileAiChat 拆分第一步抽出的 6 个函数，重点守住安全相关行为
 * （mdToHtml 的 XSS 转义与图片源白名单）和两端共用后的参数契约。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  mkey, sanitizeAnswer, sanitizeInput, cleanTitle, mdToHtml, toDocHtml, sanitizeDocHtml, buildQuestionWithAttachments,
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
  // 统一走逐行解析 + 站内图片白名单（HTML 直通分支已移除）。
  const evil = mdToHtml('# 标题\n<img src="https://evil.example/x" onerror="alert(1)">')
  assert.ok(!evil.includes('<img'))
  const site = mdToHtml('# 标题\n<img src="/uploads/a.png" alt="图">')
  assert.match(site, /class="ai-html-img"/)
  assert.match(site, /src="\/uploads\/a\.png"/)
  const data = mdToHtml('# 标题\n<img src="data:image/png;base64,AAAA" alt="x">')
  assert.match(data, /class="ai-html-img"/)
})

test('mdToHtml: Markdown 图片语法的 alt 含引号也必须转义', () => {
  const h = mdToHtml('![" onerror="alert(1)](/uploads/x.png)')
  // alt 属性位置可被注入 onerror，引号必须转义；站内源才会生成 img
  assert.match(h, /<img src="\/uploads\/x\.png"/)
  assert.match(h, /&quot;/)
  assert.ok(!/alt="[^"]*"\s+onerror=/.test(h))
})

test('mdToHtml: 外站 Markdown 图片 / 视频 / 音频降级为纯文本，不发第三方请求', () => {
  for (const line of [
    '![a](https://evil.example/x.png)',
    '![a](http://evil.example/x.png)',
    '@[video](https://evil.example/v.mp4)',
    '@[audio](https://evil.example/a.mp3)'
  ]) {
    const h = mdToHtml(line)
    assert.ok(!/<(img|video|audio)\b/i.test(h), '外部资源被渲染: ' + line)
    // 原文以转义纯文本保留，用户仍能看到写了什么
    assert.match(h, /<p>/)
  }
  // 站内与内联资源照常渲染
  assert.match(mdToHtml('![a](/uploads/a.png)'), /<img src="\/uploads\/a\.png"/)
  assert.match(mdToHtml('![a](/emojis/happy.png)'), /<img src="\/emojis\/happy\.png"/)
  assert.match(mdToHtml('![a](data:image/png;base64,AAAA)'), /<img src="data:image\/png;base64,AAAA"/)
  assert.match(mdToHtml('@[video](/uploads/a.mp4)'), /<video src="\/uploads\/a\.mp4" controls>/)
  assert.match(mdToHtml('@[audio](data:audio/mp3;base64,AAAA)'), /<audio src="data:audio\/mp3;base64,AAAA" controls>/)
})

test('mdToHtml: 视频/音频语法', () => {
  assert.match(mdToHtml('@[video](/uploads/a.mp4)'), /<video src="\/uploads\/a\.mp4" controls>/)
  assert.match(mdToHtml('@[audio](/uploads/a.mp3)'), /<audio src="\/uploads\/a\.mp3" controls>/)
})

test('toDocHtml: 已是富文本 HTML 的正文原样保留（Agent read_doc → update_doc 回写）', () => {
  const html = '<p>第一段 <strong>加粗</strong></p><ul><li>要点</li></ul>'
  assert.equal(toDocHtml(html), html)
})

test('toDocHtml: 普通 Markdown 仍按 Markdown 解析', () => {
  assert.match(toDocHtml('# 标题\n\n正文'), /<p>/)
})

test('toDocHtml: 只是提到标签的普通文本不会被当成 HTML 直通', () => {
  const text = '可以用 <p> 标签分段，也可以用 <div> 包一层'
  const out = toDocHtml(text)
  assert.ok(out.includes('&lt;p&gt;'), '标签文本必须被转义')
  assert.ok(out.includes('&lt;div&gt;'), '提到的标签都要转义')
  assert.ok(!/<p>标签/.test(out), '不能把提到的标签还原成真结构')
})

test('toDocHtml: 单个闭合标签的伪 HTML 不算直通', () => {
  const t = '<p>只有一段</p>'
  assert.equal(toDocHtml(t), '<p>&lt;p&gt;只有一段&lt;/p&gt;</p>')
})

test('sanitizeDocHtml: 干净富文本字符串完全不变（不破坏 TipTap 节点）', () => {
  const html = '<p>第一段 <strong>加粗</strong></p><ul><li>要点</li></ul>'
  assert.equal(sanitizeDocHtml(html), html)
  const graph = '<div data-type="flowchart" data=\'{"nodes":[{"id":"1"}],"edges":[]}\'></div><p>说明</p>'
  assert.equal(sanitizeDocHtml(graph), graph)
})

test('sanitizeDocHtml: script / 事件属性 / javascript: 全部剔除', () => {
  const dirty = '<div onmouseover="alert(1)"><p onclick="x">正文</p><a href="javascript:alert(1)">链接</a><script>alert(2)</script></div><p>尾</p>'
  const out = sanitizeDocHtml(dirty)
  assert.ok(!/<script/i.test(out))
  assert.ok(!/\son[a-z]+\s*=/i.test(out))
  assert.ok(!/javascript:/i.test(out))
  assert.match(out, /正文/)
  assert.match(out, /尾/)
})

test('sanitizeDocHtml: 外站 src/poster 丢弃，站内与 data: 保留', () => {
  const dirty = '<p><img src="https://evil.example/x.png" alt="外图"><video src="https://evil.example/v.mp4" poster="https://evil.example/p.png" controls></video></p><p>结束</p>'
  const out = sanitizeDocHtml(dirty)
  assert.ok(!/evil\.example/.test(out), '外站地址不得进入正文')
  assert.ok(!/src=/.test(out), '外站 src/poster 属性须整条丢弃')
  const clean = '<p><img src="/uploads/a.png" alt="站图"><video src="/uploads/a.mp4" controls></video></p><p>结束</p>'
  assert.match(sanitizeDocHtml(clean), /src="\/uploads\/a\.png"/)
  assert.match(sanitizeDocHtml(clean), /src="\/uploads\/a\.mp4"/)
  assert.match(sanitizeDocHtml('<p><img src="data:image/png;base64,AAAA"></p><p>结束</p>'), /src="data:image\/png;base64,AAAA"/)
})

test('sanitizeDocHtml: style 里的 url() 外部资源被剔除，其余样式保留', () => {
  const out = sanitizeDocHtml('<div style="background:url(https://evil/x.png);color:red"></div><p>正文</p>')
  assert.ok(!/evil\.example|evil\/x/.test(out))
  assert.match(out, /color:red/)
})

test('toDocHtml: 富文本直通走前端净化后再提交', () => {
  const dirty = '<div onmouseover="alert(1)"><p>正文</p><img src="https://evil.example/x.png"></div><p>尾</p>'
  const out = toDocHtml(dirty)
  assert.ok(!/\son[a-z]+\s*=/i.test(out))
  assert.ok(!/evil\.example/.test(out))
  assert.match(out, /正文/)
})

test('sanitizeDocHtml: 没闭合的 <script>/<style> 连内容一起丢掉，不能留成可见文字', () => {
  assert.equal(sanitizeDocHtml('<p>a</p><script>alert(1)'), '<p>a</p>')
  assert.equal(sanitizeDocHtml('<p>a</p><style>body{display:none}'), '<p>a</p>')
})

test('sanitizeDocHtml: svg / iframe 整棵子树删掉，不留裸子节点', () => {
  assert.equal(sanitizeDocHtml('<svg onload="alert(1)"><circle/></svg>'), '')
  assert.equal(sanitizeDocHtml('<iframe src="https://evil">fallback</iframe>ok'), 'ok')
})
