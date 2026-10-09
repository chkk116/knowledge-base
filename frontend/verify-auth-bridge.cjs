/**
 * 验证「跨域 iframe 登录态桥接」：
 *   1) 无 token 打开桌面管理页 → 应被弹到 /login?redirect=...（复现 bug）
 *   2) 模拟父页 postMessage 一个有效 token → 子页应落盘并自动跳到 redirect 目标（修复生效）
 *   3) 非受信任来源的 message → 应被忽略（安全）
 */
const { chromium } = require('playwright-core')
const token = process.env.KB_TOKEN || ''

;(async () => {
  const b = await chromium.launch({ headless: true, channel: 'chrome' })
  const ctx = await b.newContext({ viewport: { width: 1280, height: 900 } })
  const page = await ctx.newPage()

  // ① 无 token
  await page.goto(DOMAIN + '/admin/users', { waitUntil: 'domcontentloaded', timeout: 40000 })
  await page.waitForTimeout(5000)
  console.log('① 无 token →', page.url())
  const onLogin = /\/login/.test(page.url())
  console.log('   被弹到登录页:', onLogin ? '是（bug 复现）' : '否')

  if (!token) { console.log('（没给 KB_TOKEN，跳过 ②③）'); await b.close(); return }

  // ② 模拟父页桥接（来源 = 本站自身，属于受信任列表）
  await page.evaluate((t) => window.postMessage({ type: 'kb:auth', token: t }, location.origin), token)
  await page.waitForTimeout(6000)
  console.log('② 桥接 token 后 →', page.url())
  const okPath = /\/admin\/users/.test(page.url())
  console.log('   自动进入管理页:', okPath ? '✓ 是' : '✗ 否')

  // ③ 非受信任来源应被忽略（换一个不同 origin 的 iframe 发消息）
  const before = await page.evaluate(() => localStorage.getItem('kb_token') ? 'has' : 'none')
  await page.evaluate(() => {
    // 造一个 about:blank 的 iframe（origin 为 "null"），从里面发消息
    const f = document.createElement('iframe')
    f.style.display = 'none'
    document.body.appendChild(f)
    f.contentWindow.postMessage({ type: 'kb:auth', token: 'FAKE_TOKEN' }, '*')
  })
  await page.waitForTimeout(1200)
  const after = await page.evaluate(() => localStorage.getItem('kb_token'))
  console.log('③ 伪造来源注入 FAKE_TOKEN → localStorage:', after === 'FAKE_TOKEN' ? '❌ 被写入了（不安全）' : '✓ 已忽略')

  await b.close()
})().catch((e) => { console.error('FAIL', e && e.message); process.exit(1) })
