/**
 * 冒烟测试：桌面端命令面板（Ctrl/⌘ + K）。
 * 用法（frontend 目录下）：node smoke-command-palette.cjs <token>
 */
const { chromium } = require('playwright-core')

const TOKEN = process.argv[2]
if (!TOKEN) { console.error('缺少 token'); process.exit(1) }
const BASE = 'https://www.chkk116.cloud'

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

  await page.goto(BASE + '/doc-shares/all', { waitUntil: 'domcontentloaded' })
  await page.waitForSelector('.el-table__row', { timeout: 20000 })

  // 1) 顶栏入口
  const btn = page.locator('.cmd-btn')
  await btn.waitFor({ timeout: 10000 })
  console.log('顶栏入口可见:', await btn.isVisible(), '| 文案:', (await btn.innerText()).replace(/\s+/g, ' ').trim())

  // 2) 点开 → 面板出现
  await btn.click()
  await page.waitForSelector('.cp-panel', { timeout: 8000 })
  console.log('面板已打开:', await page.locator('.cp-panel').isVisible())

  // 3) 输关键词 → 命中「部门管理」
  const input = page.locator('.cp-input input')
  await input.fill('部门')
  await page.waitForTimeout(800)
  const rows = page.locator('.cp-row')
  const n = await rows.count()
  const first = n ? (await rows.first().locator('.cp-label').innerText()) : ''
  console.log('关键词「部门」结果数:', n, '| 首行:', first)

  // 4) 回车 → 跳到部门管理页
  await input.press('Enter')
  await page.waitForTimeout(1200)
  console.log('回车后 URL:', new URL(page.url()).pathname)

  // 5) Ctrl+K 再用键盘唤起 → Esc 关闭
  await page.keyboard.press('Control+k')
  await page.waitForSelector('.cp-panel', { timeout: 8000 })
  console.log('Ctrl+K 唤起:', await page.locator('.cp-panel').isVisible())
  await page.keyboard.press('Escape')
  await page.waitForTimeout(400)
  console.log('Esc 后面板已隐藏:', (await page.locator('.cp-panel').count()) === 0)

  await page.screenshot({ path: 'smoke-palette.png' })
  console.log('页面错误:', errors.length ? errors.slice(0, 5) : '无')

  await browser.close()

  const urlOk = new URL(page.url()).pathname === '/admin/departments'
  const ok = n > 0 && /部门管理/.test(first) && urlOk && errors.length === 0
  console.log(ok ? '✅ 通过' : '❌ 未通过')
  process.exit(ok ? 0 : 1)
})().catch((e) => { console.error('测试异常:', e.message); process.exit(1) })
