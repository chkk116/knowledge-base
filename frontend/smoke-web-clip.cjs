/**
 * 冒烟测试：手机端「网页剪藏」。
 * 用法（frontend 目录下）：node smoke-web-clip.cjs <token>
 */
const { chromium } = require('playwright-core')

const TOKEN = process.argv[2]
if (!TOKEN) { console.error('缺少 token'); process.exit(1) }
const BASE = 'https://www.chkk116.cloud'

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

  await page.goto(BASE + '/m/clip', { waitUntil: 'domcontentloaded' })
  await page.waitForSelector('.url-input', { timeout: 20000 })
  console.log('链接输入框可见:', await page.locator('.url-input').isVisible())
  console.log('抓取按钮初始禁用:', await page.locator('.fetch-btn').isDisabled())

  await page.locator('.url-input').fill('https://example.com')
  await page.locator('.fetch-btn').click()
  await page.waitForSelector('.title-input', { timeout: 40000 })
  console.log('标题:', await page.locator('.title-input').inputValue())
  console.log('站点/字数:', (await page.locator('.meta-line').innerText()).replace(/\s+/g, ' ').trim())

  await page.locator('.save-btn').click()
  await page.waitForTimeout(2500)
  const msg = await page.locator('.el-message').first().innerText().catch(() => '')
  console.log('保存提示:', msg.trim())
  console.log('出现「查看」按钮:', (await page.locator('.ghost-btn').count()) > 0)

  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)
  console.log('横向溢出(px):', overflow)
  await page.screenshot({ path: 'smoke-clip.png' })
  console.log('页面错误:', errors.length ? errors.slice(0, 5) : '无')
  await browser.close()

  const ok = /已/.test(msg) && overflow <= 0 && errors.length === 0
  console.log(ok ? '✅ 通过' : '❌ 未通过')
  process.exit(ok ? 0 : 1)
})().catch((e) => { console.error('测试异常:', e.message); process.exit(1) })
