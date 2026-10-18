/**
 * 冒烟测试：移动端首页「每日回顾」+ AI 问答页加载。
 * 用法（frontend 目录下）：node smoke-daily-recall.cjs <token>
 */
const { chromium } = require('playwright-core')

const TOKEN = process.argv[2]
if (!TOKEN) { console.error('缺少 token'); process.exit(1) }
const BASE = 'https://www.chkk116.cloud'
const MOBILE = { width: 390, height: 844 }

;(async () => {
  const browser = await chromium.launch({ channel: 'chrome', headless: true })
  const ctx = await browser.newContext({ viewport: MOBILE, isMobile: true, hasTouch: true })
  await ctx.addInitScript(([t]) => {
    localStorage.setItem('kb_token', t)
    localStorage.setItem('kb_user_id', '1')
    localStorage.setItem('kb_user_cache', JSON.stringify({ id: 1, username: 'admin', role: 'ADMIN' }))
  }, [TOKEN])

  const page = await ctx.newPage()
  const errors = []
  page.on('pageerror', (e) => errors.push('pageerror: ' + e.message))
  page.on('console', (m) => { if (m.type() === 'error') errors.push('console: ' + m.text()) })

  // 1) 首页每日回顾
  await page.goto(BASE + '/m/home', { waitUntil: 'domcontentloaded' })
  await page.waitForTimeout(3500)
  const card = page.locator('.recall')
  const hasCard = (await card.count()) > 0
  console.log('每日回顾卡片出现:', hasCard)
  if (hasCard) {
    console.log('标题:', (await page.locator('.rc-title').innerText()).trim())
    console.log('提示:', (await page.locator('.rc-tip').innerText()).trim())
    const t1 = await page.locator('.rc-title').innerText()
    // 换一篇：标题应当变化（候选池 > 1 时）
    await page.locator('.m-sec-title .more', { hasText: '换一篇' }).click()
    await page.waitForTimeout(400)
    const t2 = await page.locator('.rc-title').innerText()
    console.log('换一篇后标题变化:', t1.trim() !== t2.trim(), `(${t1.trim()} → ${t2.trim()})`)
    // 打开这篇 → 跳文档页
    await page.locator('.rc-btn.primary').click()
    await page.waitForTimeout(1500)
    console.log('打开后路径:', new URL(page.url()).pathname)
    await page.goBack()
    await page.waitForTimeout(1500)
  } else {
    console.log('（本机没有可回顾的文档，跳过卡片断言）')
  }

  // 2) AI 问答页（用户刚改过 UI）能否正常打开
  await page.goto(BASE + '/m/ai', { waitUntil: 'domcontentloaded' })
  await page.waitForTimeout(2500)
  console.log('AI 问答路径:', new URL(page.url()).pathname, '| 可交互元素:', (await page.locator('textarea, input, button').count()))

  // 3) 横向溢出检查（移动端老规矩）
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)
  console.log('横向溢出(px):', overflow)

  await page.screenshot({ path: 'smoke-recall.png', fullPage: false })
  console.log('页面错误:', errors.length ? errors.slice(0, 5) : '无')
  await browser.close()

  const ok = errors.length === 0 && overflow <= 0
  console.log(ok ? '✅ 通过' : '❌ 未通过')
  process.exit(ok ? 0 : 1)
})().catch((e) => { console.error('测试异常:', e.message); process.exit(1) })
