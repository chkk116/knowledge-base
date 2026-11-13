// 题库分类筛选 + 按分类一键组卷
const { chromium } = require('playwright-core')
const TOKEN = process.argv[2]
// 冒烟目标由环境变量给出，仓库不写死任何真实域名/服务器地址
const HOST = process.env.KB_SMOKE_HOST || 'your-domain.example'
const IP = process.env.KB_SMOKE_IP || '127.0.0.1'

;(async () => {
  const b = await chromium.launch({ channel: 'chrome', headless: true, args: [`--host-resolver-rules=MAP ${HOST} ${IP}`] })
  const ctx = await b.newContext({ viewport: { width: 1440, height: 900 } })
  await ctx.addInitScript(([t]) => {
    localStorage.setItem('kb_token', t)
    localStorage.setItem('kb_user_id', '1')
    localStorage.setItem('kb_user_cache', JSON.stringify({ id: 1, username: 'admin', role: 'ADMIN' }))
  }, [TOKEN])
  const p = await ctx.newPage()
  const errs = []
  p.on('pageerror', (e) => errs.push('PAGEERROR ' + e.message))
  p.on('response', async (r) => {
    const u = r.url()
    if (u.includes('/admin/exams/papers') && r.request().method() === 'POST') {
      const t = await r.text().catch(() => '')
      console.log('创建试卷:', r.status(), t.slice(0, 120))
    }
    if (u.includes('/assemble')) console.log('组卷:', r.status())
  })

  await p.goto('https://www.你的域名/admin/exams', { waitUntil: 'commit', timeout: 90000 })
  await p.waitForTimeout(9000)

  console.log('分类下拉:', await p.locator('.cat-sel').count())
  console.log('总数文案:', (await p.locator('.count-hint').innerText({ timeout: 2000 }).catch(() => '')).trim())

  await p.locator('.cat-sel').click()
  await p.waitForTimeout(800)
  const opts = await p.locator('.el-select-dropdown__item').allInnerTexts().catch(() => [])
  console.log('可选分类:', opts.map((s) => s.trim()).filter(Boolean))

  // 选「软考网络工程师」
  const target = p.locator('.el-select-dropdown__item').filter({ hasText: '软考' }).first()
  if (await target.count()) {
    await target.click()
    await p.waitForTimeout(2000)
    console.log('筛选后:', (await p.locator('.count-hint').innerText({ timeout: 2000 }).catch(() => '')).trim())
    console.log('表格行数:', await p.locator('.el-table__row').count())
    console.log('组卷按钮:', await p.getByRole('button', { name: '用该分类组卷' }).count())

    await p.getByRole('button', { name: '用该分类组卷' }).click()
    await p.waitForTimeout(900)
    const box = p.locator('.el-message-box')
    console.log('确认框:', (await box.innerText({ timeout: 1500 }).catch(() => '')).replace(/\s+/g, ' ').slice(0, 100))
    await box.getByRole('button', { name: '生成试卷' }).click()
    await p.waitForTimeout(3500)
    console.log('提示:', (await p.locator('.el-message').last().innerText({ timeout: 1500 }).catch(() => '')).trim())
    console.log('当前 tab:', (await p.locator('.el-tabs__item.is-active').innerText({ timeout: 1500 }).catch(() => '')).trim())
    console.log('试卷行数:', await p.locator('.el-table__row').count())
  } else {
    console.log('没找到「软考」分类')
  }

  await p.screenshot({ path: 'shot-exam-cat.png' })
  console.log('页面错误:', errs.length ? errs.slice(0, 3) : '无')
  await b.close()
})()
