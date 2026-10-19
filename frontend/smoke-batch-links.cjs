/**
 * 冒烟测试：批量打标签 + 双向链接面板。
 * 用法（frontend 目录下）：node smoke-batch-links.cjs <token>
 *
 * 只验证前端接线（勾选 → 批量条 → 对话框；文档页关联面板渲染），
 * 写入路径已由后端接口测试覆盖，这里不动真实数据。
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

  // 1) 批量操作
  await page.goto(BASE + '/doc-shares/all', { waitUntil: 'domcontentloaded' })
  await page.waitForSelector('.el-table__row', { timeout: 20000 })

  // Element Plus 把原生 input 藏起来了（opacity:0），要点可见的 .el-checkbox 外壳
  const boxes = page.locator('.el-table__row .el-checkbox')
  console.log('勾选框数量:', await boxes.count())
  await boxes.nth(0).click()
  await boxes.nth(1).click()
  await page.waitForTimeout(400)

  const bar = page.locator('.batch-bar')
  console.log('批量条出现:', await bar.isVisible(), '|', (await bar.innerText()).replace(/\s+/g, ' ').trim())

  await page.getByRole('button', { name: '批量打标签' }).click()
  await page.waitForTimeout(500)
  const dlg = page.locator('.el-dialog:has-text("批量打标签")')
  console.log('对话框打开:', await dlg.isVisible())
  await page.screenshot({ path: 'smoke-batch.png' })
  await dlg.getByRole('button', { name: '取消' }).click()
  await page.waitForTimeout(500)
  // el-dialog 关闭后 DOM 仍在（display:none），所以判可见性而不是 count
  console.log('取消后对话框已隐藏:', !(await page.locator('.el-dialog:has-text("批量打标签")').isVisible()))

  // 2) 文档页的关联面板
  await page.goto(BASE + '/doc-shares/1?doc=11', { waitUntil: 'domcontentloaded' })
  await page.waitForTimeout(3500)
  const panel = page.locator('.doc-links')
  const hasPanel = (await panel.count()) > 0
  console.log('关联文档面板出现:', hasPanel)
  if (hasPanel) console.log('面板内容:', (await panel.innerText()).replace(/\s+/g, ' ').trim().slice(0, 90))

  console.log('页面错误:', errors.length ? errors.slice(0, 5) : '无')
  await browser.close()

  const ok = hasPanel && errors.length === 0
  console.log(ok ? '✅ 通过' : '❌ 未通过')
  process.exit(ok ? 0 : 1)
})().catch((e) => { console.error('测试异常:', e.message); process.exit(1) })
