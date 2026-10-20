/**
 * 冒烟测试：章节「自动完成」（长文滑到底 + 最短停留时间）。
 * 用法：node smoke-chapter-auto.cjs <token> [chapterId]
 */
const { chromium } = require('playwright-core')
const TOKEN = process.argv[2]
const CH = process.argv[3] || '2'
const COURSE = process.argv[4] || '1'
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

  await page.goto(`${BASE}/m/course/${COURSE}/chapter/${CH}`, { waitUntil: 'domcontentloaded' })
  // 长章节正文很高，按钮可能在视口外；这里等「挂载」而不是「可见」
  await page.waitForSelector('.m-btn', { state: 'attached', timeout: 30000 })
  await page.waitForTimeout(1500)

  console.log('按钮初始:', (await page.locator('.m-btn').first().innerText()).trim())
  console.log('门槛提示:', (await page.locator('.gate-tip').innerText().catch(() => '')).trim())
  console.log('按钮可点(应为 false):', await page.locator('.m-btn').first().isEnabled())

  // 模拟用户滑到底部
  const scrolled = await page.evaluate(() => {
    const el = document.querySelector('.m-view') || document.scrollingElement
    if (!el) return 'no-scroller'
    el.scrollTop = el.scrollHeight
    el.dispatchEvent(new Event('scroll'))
    return `${el.scrollTop}/${el.scrollHeight - el.clientHeight}`
  })
  console.log('滚动到底:', scrolled)
  await page.waitForTimeout(600)
  console.log('滑到底后提示:', (await page.locator('.gate-tip').innerText().catch(() => '')).trim())

  // 等自动完成（最多 ~100 秒）
  let finalText = ''
  for (let i = 0; i < 34; i++) {
    await page.waitForTimeout(3000)
    finalText = (await page.locator('.m-btn').first().innerText()).trim()
    if (finalText.includes('下一章') || finalText.includes('已学完')) break
  }
  console.log('最终按钮:', finalText)
  console.log('完成提示:', (await page.locator('.el-message').first().innerText().catch(() => '')).trim())

  console.log('页面错误:', errors.length ? errors.slice(0, 5) : '无')
  await browser.close()

  const ok = finalText.includes('下一章') || finalText.includes('已学完')
  console.log(ok ? '✅ 通过（已自动完成）' : '❌ 未自动完成')
  process.exit(ok ? 0 : 1)
})().catch((e) => { console.error('测试异常:', e.message); process.exit(1) })
