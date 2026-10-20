/**
 * 冒烟测试：手机端「速记」。
 * 用法（frontend 目录下）：node smoke-quick-note.cjs <token>
 */
const { chromium } = require('playwright-core')

const TOKEN = process.argv[2]
if (!TOKEN) { console.error('缺少 token'); process.exit(1) }
const BASE = 'https://www.你的域名'

;(async () => {
  const browser = await chromium.launch({ channel: 'chrome', headless: true })
  const ctx = await browser.newContext({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true })
  await ctx.addInitScript(([t]) => {
    localStorage.setItem('kb_token', t)
    localStorage.setItem('kb_user_id', '1')
    localStorage.setItem('kb_user_cache', JSON.stringify({ id: 1, username: 'admin', role: 'ADMIN' }))
  }, [TOKEN])

  const page = await ctx.newPage()
  const errors = []
  page.on('pageerror', (e) => errors.push('pageerror: ' + e.message))
  page.on('console', (m) => { if (m.type() === 'error') errors.push('console: ' + m.text()) })

  await page.goto(BASE + '/m/quick', { waitUntil: 'domcontentloaded' })
  await page.waitForSelector('.qn-input', { timeout: 20000 })
  console.log('输入框可见:', await page.locator('.qn-input').isVisible())
  console.log('保存按钮初始禁用:', await page.locator('.qn-save').isDisabled())

  const text = '冒烟测试速记：明天把请假流程文档更新一下 #流程 #待办'
  await page.locator('.qn-input').fill(text)
  await page.waitForTimeout(400)
  const pills = await page.locator('.tag-pill').allInnerTexts()
  console.log('识别到标签:', pills.map((s) => s.trim()))

  await page.locator('.qn-save').click()
  await page.waitForTimeout(2200)
  const msg = await page.locator('.el-message').first().innerText().catch(() => '')
  console.log('提示:', msg.trim())
  console.log('输入框已清空:', (await page.locator('.qn-input').inputValue()) === '')
  console.log('最近速记条数:', await page.locator('.recent-row').count())

  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)
  console.log('横向溢出(px):', overflow)
  await page.screenshot({ path: 'smoke-quick.png' })
  console.log('页面错误:', errors.length ? errors.slice(0, 5) : '无')
  await browser.close()

  const ok = pills.length === 2 && /待整理箱/.test(msg) && overflow <= 0 && errors.length === 0
  console.log(ok ? '✅ 通过' : '❌ 未通过')
  process.exit(ok ? 0 : 1)
})().catch((e) => { console.error('测试异常:', e.message); process.exit(1) })
