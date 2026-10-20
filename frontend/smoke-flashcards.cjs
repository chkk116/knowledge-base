/**
 * 冒烟测试：手机端「复习卡片」。
 * 用法（frontend 目录下）：node smoke-flashcards.cjs <token>
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

  await page.goto(BASE + '/m/cards', { waitUntil: 'domcontentloaded' })
  await page.waitForSelector('.stat-card', { timeout: 20000 })
  console.log('待复习数:', (await page.locator('.stat-num').innerText()).trim())

  // 新建一张卡
  await page.locator('.m-header-act').last().click()
  await page.waitForSelector('.sheet', { timeout: 8000 })
  const tas = page.locator('.field textarea')
  await tas.nth(0).fill('冒烟测试：SM-2 的 ease factor 下限是多少？')
  await tas.nth(1).fill('1.3')
  await page.locator('.sheet-actions .primary-btn').click()
  await page.waitForTimeout(2000)
  console.log('列表卡片数:', await page.locator('.card-row').count())
  console.log('待复习数(刷新后):', (await page.locator('.stat-num').innerText()).trim())

  // 开始复习
  await page.locator('.primary-btn', { hasText: '开始复习' }).click()
  await page.waitForSelector('.flash', { timeout: 10000 })
  console.log('问题:', (await page.locator('.flash-text').innerText()).trim().slice(0, 30))
  console.log('评分按钮初始可见:', await page.locator('.grade').count())

  await page.locator('.primary-btn', { hasText: '显示答案' }).click()
  await page.waitForTimeout(400)
  console.log('答案:', (await page.locator('.flash-text').innerText()).trim())
  console.log('评分按钮数:', await page.locator('.grade').count())

  await page.locator('.grade.g-good').click()
  await page.waitForTimeout(1800)
  const finished = await page.locator('.finish-title').count()
  console.log('复习完成页出现:', finished > 0, finished ? '| ' + (await page.locator('.finish-title').innerText()).trim() : '')

  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)
  console.log('横向溢出(px):', overflow)
  await page.screenshot({ path: 'smoke-cards.png' })
  console.log('页面错误:', errors.length ? errors.slice(0, 5) : '无')
  await browser.close()

  const ok = finished > 0 && overflow <= 0 && errors.length === 0
  console.log(ok ? '✅ 通过' : '❌ 未通过')
  process.exit(ok ? 0 : 1)
})().catch((e) => { console.error('测试异常:', e.message); process.exit(1) })
