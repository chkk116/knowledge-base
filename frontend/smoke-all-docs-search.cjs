/**
 * 冒烟测试：桌面端「全部文档」的正文检索。
 * 用法（在 frontend 目录下）：node smoke-all-docs-search.cjs <token>
 */
const { chromium } = require('playwright-core')

const TOKEN = process.argv[2]
if (!TOKEN) { console.error('缺少 token'); process.exit(1) }

const BASE = 'https://www.chkk116.cloud'

;(async () => {
  const browser = await chromium.launch({ channel: 'chrome', headless: true })
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } })
  // 注入登录态，避免走滑动验证码
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

  const rows = () => page.locator('.el-table__row').count()
  const initial = await rows()
  console.log('初始行数:', initial)

  const input = page.locator('input[placeholder="搜索标题、标签或正文"]')
  await input.waitFor({ timeout: 10000 })

  // 1) 只在正文里出现的词（示例文档「终端密码规则合规性配置」的正文）
  await input.fill('静态密码')
  await page.waitForTimeout(600)
  const bodyHits = await rows()
  const hitText = bodyHits ? (await page.locator('.t-hit').first().innerText().catch(() => '')) : ''
  console.log('正文关键词「静态密码」→ 行数:', bodyHits, '| 片段:', JSON.stringify(hitText.slice(0, 50)))

  // 2) 不存在的词
  await input.fill('zzz绝对不存在zzz')
  await page.waitForTimeout(600)
  console.log('无关键词 → 行数:', await rows())

  // 3) 标题关键词（回归：不能因为改正文检索而丢掉标题匹配）
  await input.fill('终端密码')
  await page.waitForTimeout(600)
  console.log('标题关键词「终端密码」→ 行数:', await rows())

  await page.screenshot({ path: 'smoke-all-docs.png', fullPage: false })
  console.log('页面错误:', errors.length ? errors.slice(0, 5) : '无')

  await browser.close()

  const ok = initial > 0 && bodyHits > 0 && hitText.length > 0 && errors.length === 0
  console.log(ok ? '✅ 通过' : '❌ 未通过')
  process.exit(ok ? 0 : 1)
})().catch((e) => { console.error('测试异常:', e.message); process.exit(1) })
