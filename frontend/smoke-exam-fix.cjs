/**
 * 冒烟测试：手机端考试页修复 + 章节完成。
 * 用法：node smoke-exam-fix.cjs <token>
 */
const { chromium } = require('playwright-core')
const TOKEN = process.argv[2]
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

  // 1) 我的考试：标题应是试卷名，不是「考试 #id」
  await page.goto(BASE + '/m/exam/mine', { waitUntil: 'domcontentloaded' })
  await page.waitForTimeout(3000)
  const titles = await page.locator('.r-title').allInnerTexts()
  console.log('成绩列表标题:', titles)
  const badTitle = titles.some((t) => /^考试 #\d+$/.test(t.trim()))

  // 2) 答题页：TabBar 应隐藏；交卷流程可用
  await page.goto(BASE + '/m/exam/2', { waitUntil: 'domcontentloaded' })
  await page.waitForSelector('.topbar', { timeout: 25000 })
  await page.waitForTimeout(1200)
  console.log('TabBar 数量(应为 0):', await page.locator('.tabbar').count())
  console.log('未答提示:', (await page.locator('.unanswered-tip').innerText().catch(() => '')).trim())

  // 全部选 A 直到最后一题
  const total = await page.locator('.dot').count()
  for (let i = 0; i < total; i++) {
    await page.locator('.dot').nth(i).click()
    await page.waitForTimeout(150)
    const opt = page.locator('.opt').first()
    if (await opt.count()) await opt.click()
  }
  await page.waitForTimeout(300)
  console.log('作答后未答提示:', await page.locator('.unanswered-tip').count(), '(应为 0)')

  // 交卷 → 确认框 → 结果
  await page.locator('.fb.primary').click()
  await page.waitForTimeout(700)
  const confirmVisible = await page.locator('.el-message-box').isVisible().catch(() => false)
  console.log('交卷确认框:', confirmVisible)
  if (confirmVisible) await page.locator('.el-message-box__btns .el-button--primary').click()
  await page.waitForTimeout(3000)
  const resultScore = await page.locator('.r-score').innerText().catch(() => '')
  console.log('结果分数:', resultScore.trim())
  console.log('结果文案:', (await page.locator('.r-state').innerText().catch(() => '')).trim())

  // 3) 章节完成
  await page.goto(BASE + '/m/course/1/chapter/1', { waitUntil: 'domcontentloaded' })
  await page.waitForTimeout(2500)
  const btn = page.locator('.m-btn').first()
  console.log('章节按钮文案:', (await btn.innerText()).trim())
  if ((await btn.innerText()).includes('标记完成')) {
    await btn.click()
    await page.waitForTimeout(1500)
    console.log('点击后提示:', (await page.locator('.el-message').first().innerText().catch(() => '')).trim())
    // 重新进入同一章，应显示「已完成」
    await page.goto(BASE + '/m/course/1/chapter/1', { waitUntil: 'domcontentloaded' })
    await page.waitForTimeout(2500)
    console.log('重进后按钮文案:', (await page.locator('.m-btn').first().innerText()).trim())
  }

  console.log('页面错误:', errors.length ? errors.slice(0, 5) : '无')
  await browser.close()

  const ok = !badTitle && errors.length === 0
  console.log(ok ? '✅ 通过' : '❌ 未通过')
  process.exit(ok ? 0 : 1)
})().catch((e) => { console.error('测试异常:', e.message); process.exit(1) })
