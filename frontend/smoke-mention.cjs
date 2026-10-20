/**
 * 冒烟测试：文档 @提及。
 * 用法（frontend 目录下）：node smoke-mention.cjs <token> [shareId] [docId]
 */
const { chromium } = require('playwright-core')

const TOKEN = process.argv[2]
if (!TOKEN) { console.error('缺少 token'); process.exit(1) }
const SHARE = process.argv[3] || '3'
const DOC = process.argv[4] || '8'
const BASE = 'https://www.你的域名'

;(async () => {
  const browser = await chromium.launch({ channel: 'chrome', headless: true })
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } })
  await ctx.addInitScript(([t]) => {
    localStorage.setItem('kb_token', t)
    localStorage.setItem('kb_user_id', '1')
    localStorage.setItem('kb_user_cache', JSON.stringify({ id: 1, username: 'admin', role: 'ADMIN' }))
  }, [TOKEN])

  const page = await ctx.newPage()
  const errors = []
  page.on('pageerror', (e) => errors.push('pageerror: ' + e.message))
  page.on('console', (m) => { if (m.type() === 'error') errors.push('console: ' + m.text()) })

  await page.goto(`${BASE}/doc-shares/${SHARE}?doc=${DOC}`, { waitUntil: 'domcontentloaded' })
  await page.waitForSelector('.mention-btn', { timeout: 25000 })
  console.log('提及按钮可见:', await page.locator('.mention-btn').isVisible())

  await page.locator('.mention-btn').click()
  await page.waitForTimeout(700)
  const dlg = page.locator('.el-dialog:has-text("提及成员")')
  console.log('弹窗打开:', await dlg.isVisible())

  const rows = dlg.locator('.mention-row')
  const n = await rows.count()
  console.log('可选成员数:', n)
  if (n) {
    console.log('第一位:', (await rows.first().innerText()).replace(/\s+/g, ' ').trim())
    await rows.first().click()
    await page.waitForTimeout(1800)
    const msg = await page.locator('.el-message').first().innerText().catch(() => '')
    console.log('提示:', msg.trim())
    console.log('弹窗已关闭:', !(await dlg.isVisible()))
  } else {
    console.log('（该空间没有其他成员，跳过点击）')
  }

  await page.screenshot({ path: 'smoke-mention.png' })
  console.log('页面错误:', errors.length ? errors.slice(0, 5) : '无')
  await browser.close()

  const ok = n > 0 && errors.length === 0
  console.log(ok ? '✅ 通过' : '❌ 未通过')
  process.exit(ok ? 0 : 1)
})().catch((e) => { console.error('测试异常:', e.message); process.exit(1) })
