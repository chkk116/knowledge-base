const { chromium } = require('playwright-core')
const TOKEN = process.argv[2]
;(async () => {
  const b = await chromium.launch({ channel: 'chrome', headless: true })
  const ctx = await b.newContext({ viewport: { width: 1440, height: 900 } })
  await ctx.addInitScript(([t]) => {
    localStorage.setItem('kb_token', t)
    localStorage.setItem('kb_user_id', '1')
    localStorage.setItem('kb_user_cache', JSON.stringify({ id: 1, username: 'admin', role: 'ADMIN' }))
  }, [TOKEN])
  const p = await ctx.newPage()
  const errs = []
  p.on('pageerror', (e) => errs.push('PAGEERROR ' + e.message))
  await p.goto('https://www.你的域名/admin/exams', { waitUntil: 'domcontentloaded' })
  await p.waitForTimeout(4000)
  await p.locator('.el-table__row').first().getByRole('button', { name: '编辑' }).click()
  await p.waitForTimeout(2000)
  const dlg = p.locator('.el-dialog').filter({ hasText: '编辑题目' }).first()
  console.log('弹窗:', await dlg.isVisible())
  console.log('题干:', (await dlg.locator('textarea').first().inputValue().catch(() => '')).slice(0, 40))
  const rows = dlg.locator('.orow')
  console.log('选项行数:', await rows.count())
  for (let i = 0; i < await rows.count(); i++) {
    const t = await rows.nth(i).locator('input').first().inputValue().catch(() => '')
    const checked = await rows.nth(i).locator('.el-checkbox.is-checked').count()
    console.log(`  ${String.fromCharCode(65 + i)}. ${t.slice(0, 26)} ${checked ? '✓' : ''}`)
  }
  console.log('页面错误:', errs.length ? errs.slice(0, 3) : '无')
  await b.close()
})()
