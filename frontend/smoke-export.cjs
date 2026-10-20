/**
 * 冒烟测试：桌面端「全部文档 → 导出全部」按钮。
 * 用法（frontend 目录下）：node smoke-export.cjs <token>
 */
const { chromium } = require('playwright-core')

const TOKEN = process.argv[2]
if (!TOKEN) { console.error('缺少 token'); process.exit(1) }
const BASE = 'https://www.你的域名'

;(async () => {
  const browser = await chromium.launch({ channel: 'chrome', headless: true })
  const ctx = await browser.newContext({
    viewport: { width: 1440, height: 900 },
    acceptDownloads: true,
  })
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

  const btn = page.getByRole('button', { name: /导出全部/ })
  await btn.waitFor({ timeout: 10000 })
  console.log('按钮可见:', await btn.isVisible(), '| 文案:', (await btn.innerText()).trim())

  const [dl] = await Promise.all([
    page.waitForEvent('download', { timeout: 60000 }),
    btn.click(),
  ])
  const p = await dl.path()
  const fs = require('fs')
  const size = fs.statSync(p).size
  console.log('下载文件名:', dl.suggestedFilename(), '| 大小:', size, 'bytes')

  // 校验下载下来的确实是可用 zip
  const buf = fs.readFileSync(p)
  const isZip = buf.slice(0, 2).toString('latin1') === 'PK'
  console.log('魔数 PK:', isZip)

  console.log('页面错误:', errors.length ? errors.slice(0, 5) : '无')
  await browser.close()

  const ok = size > 1000 && isZip && errors.length === 0
  console.log(ok ? '✅ 通过' : '❌ 未通过')
  process.exit(ok ? 0 : 1)
})().catch((e) => { console.error('测试异常:', e.message); process.exit(1) })
