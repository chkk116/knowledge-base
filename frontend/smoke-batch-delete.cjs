// 考试管理：题目/试卷批量删除 —— 浏览器验收（只删自己造的测试数据，不动真题）
// 用法: node smoke-batch-delete.cjs <token>
const { chromium } = require('playwright-core')
const TOKEN = process.argv[2]
const API = 'https://www.你的域名/api'
const BASE = 'https://www.你的域名/admin/exams'

;(async () => {
  const b = await chromium.launch({
    channel: 'chrome', headless: true,
    args: ['--no-proxy-server'],
  })
  const ctx = await b.newContext({ viewport: { width: 1440, height: 900 } })
  await ctx.addInitScript(([t]) => {
    localStorage.setItem('kb_token', t)
    localStorage.setItem('kb_user_id', '1')
    localStorage.setItem('kb_user_cache', JSON.stringify({ id: 1, username: 'admin', role: 'ADMIN' }))
  }, [TOKEN])
  const p = await ctx.newPage()
  const errs = []
  p.on('pageerror', (e) => errs.push('PAGEERROR ' + e.message))
  p.on('console', (m) => { if (m.type() === 'error') errs.push('ERR ' + m.text().slice(0, 100)) })

  const batchReq = []
  p.on('request', (r) => { if (r.url().includes('batch-delete')) batchReq.push(r.method() + ' ' + r.url().slice(-40)) })

  // ---- 先打开站点（about:blank 下 fetch 会 Failed to fetch）----
  await p.goto(BASE, { waitUntil: 'domcontentloaded', timeout: 60000 })
  await p.waitForTimeout(1500)

  // ---- 再造 2 道测试题 ----
  const make = await p.evaluate(async ([api, tok]) => {
    const ids = []
    for (let i = 0; i < 2; i++) {
      const r = await fetch(api + '/admin/exams/questions', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + tok },
        body: JSON.stringify({
          question: { type: 'JUDGE', content: '[批量删除测试] 临时题目 ' + i, difficulty: 1, category: '批量删除测试' },
          options: [
            { label: 'A', content: '对', isCorrect: true },
            { label: 'B', content: '错', isCorrect: false },
          ],
        }),
      })
      if (r.ok) ids.push((await r.json()).id)
    }
    return ids
  }, [API, TOKEN])
  console.log('造的测试题目:', make)

  await p.waitForSelector('.el-table', { timeout: 30000 })
  await p.waitForTimeout(2000)

  // ---- 筛选到测试分类，表格里只剩这 2 道 ----
  await p.locator('.cat-sel .el-select__wrapper').first().click({ force: true })
  await p.waitForTimeout(600)
  const opt = p.locator('.el-select-dropdown__item').filter({ hasText: '批量删除测试' }).first()
  if (await opt.count()) { await opt.click() } else { await p.keyboard.press('Escape') }
  await p.waitForTimeout(2200)

  const rows = p.locator('.el-tab-pane').first().locator('.el-table__row')
  const before = await rows.count()
  console.log('筛选后题库行数:', before)

  // ---- 勾选前两行 → 批量删除 ----
  await rows.nth(0).locator('.el-checkbox').first().click()
  await rows.nth(1).locator('.el-checkbox').first().click()
  await p.waitForTimeout(800)

  const batchBtn = p.getByRole('button', { name: /批量删除（2）/ })
  console.log('批量删除按钮出现:', await batchBtn.count() > 0)

  if (await batchBtn.count()) {
    await batchBtn.first().click()
    await p.waitForTimeout(600)
    const confirm = p.locator('.el-message-box').filter({ hasText: '批量删除' })
    console.log('确认框文案:', (await confirm.innerText().catch(() => '')).replace(/\s+/g, ' ').slice(0, 120))
    const ok = confirm.getByRole('button', { name: '删除' })
    if (await ok.count()) {
      await ok.first().click()
      await p.waitForTimeout(4500)
      console.log('成功提示:', (await p.locator('.el-message--success').allInnerTexts().catch(() => [])).join('|'))
    }
  }

  await p.waitForTimeout(1200)
  const after = await p.locator('.el-tab-pane').first().locator('.el-table__row').count()
  console.log('删除后行数:', after, after === 0 ? '✓（2 道测试题全删）' : '✗')

  // ---- 试卷 tab：按钮显隐 + 取消 ----
  await p.locator('.cat-sel .el-select__wrapper').first().click({ force: true })
  await p.waitForTimeout(500)
  const clr = p.locator('.cat-sel .el-select__clear').first()
  if (await clr.count()) await clr.click({ force: true })
  else await p.keyboard.press('Escape')
  await p.waitForTimeout(1800)

  await p.getByRole('tab', { name: '试卷' }).click()
  await p.waitForTimeout(2000)
  const pRows = p.locator('.el-tab-pane').nth(1).locator('.el-table__row')
  const pBefore = await pRows.count()
  console.log('试卷行数:', pBefore)
  console.log('未选中时批量按钮隐藏:', (await p.getByRole('button', { name: /批量删除（/ }).count()) === 0 ? '✓' : '✗')

  await pRows.nth(0).locator('.el-checkbox').first().click()
  await p.waitForTimeout(700)
  const pBtn = p.getByRole('button', { name: /批量删除（1）/ })
  console.log('试卷批量按钮出现:', await pBtn.count() > 0 ? '✓' : '✗')
  if (await pBtn.count()) {
    await pBtn.first().click()
    await p.waitForTimeout(600)
    const confirm = p.locator('.el-message-box').filter({ hasText: '批量删除' })
    console.log('试卷确认框:', (await confirm.innerText().catch(() => '')).replace(/\s+/g, ' ').slice(0, 140))
    await confirm.getByRole('button', { name: '取消' }).first().click()
    await p.waitForTimeout(900)
    console.log('取消后试卷数:', await p.locator('.el-tab-pane').nth(1).locator('.el-table__row').count(), '（应仍为 ' + pBefore + '）')
  }

  console.log('batch-delete 请求:', batchReq.length ? batchReq.join(' , ') : '（未捕获）')
  console.log('页面错误:', errs.length ? errs.slice(0, 3) : '无')
  await b.close()
})()
