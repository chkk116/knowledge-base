// AI 出题 UI 冒烟：入口 → 弹窗 → 三档依据切换的确认框 → 取消回退
const { chromium } = require('playwright-core')
const TOKEN = process.argv[2]
const BASE = 'https://www.chkk116.cloud'

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
  p.on('console', (m) => { if (m.type() === 'error') errs.push('ERR ' + m.text().slice(0, 120)) })

  await p.goto(BASE + '/admin/exams', { waitUntil: 'domcontentloaded' })
  await p.waitForTimeout(4000)
  console.log('进入考试管理:', await p.getByRole('button', { name: 'AI 出题' }).count() > 0)

  await p.getByRole('button', { name: 'AI 出题' }).click()
  await p.waitForTimeout(800)
  const dlg = p.locator('.el-dialog').filter({ hasText: 'AI 出题' }).first()
  console.log('弹窗可见:', await dlg.isVisible())
  console.log('默认依据:', (await dlg.locator('.el-radio.is-checked').first().innerText().catch(() => '?')).trim())
  console.log('空间下拉数量:', await dlg.locator('.el-select').count())

  // 切「AI 通用知识」：必须弹确认
  await dlg.getByText('AI 通用知识', { exact: true }).click()
  await p.waitForTimeout(800)
  const box = p.locator('.el-message-box')
  const boxShown = await box.isVisible().catch(() => false)
  console.log('通用知识确认框:', boxShown, '|', boxShown ? (await box.locator('.el-message-box__title').innerText()).trim() : '')
  if (boxShown) await box.getByRole('button', { name: '取消' }).click()
  await p.waitForTimeout(600)
  console.log('取消后依据回到:', (await dlg.locator('.el-radio.is-checked').first().innerText().catch(() => '?')).trim())

  // 切「指定网页」：必须弹确认，确认后出现链接框
  await dlg.getByText('指定网页', { exact: true }).click()
  await p.waitForTimeout(800)
  const box2 = p.locator('.el-message-box')
  const shown2 = await box2.isVisible().catch(() => false)
  console.log('联网确认框:', shown2, '|', shown2 ? (await box2.locator('.el-message-box__title').innerText()).trim() : '')
  if (shown2) await box2.getByRole('button', { name: '继续' }).click()
  await p.waitForTimeout(600)
  console.log('链接输入框:', await dlg.locator('textarea').count())
  console.log('当前依据:', (await dlg.locator('.el-radio.is-checked').first().innerText().catch(() => '?')).trim())

  // 填主题并生成（模型可能失败，重点看是否给出可读提示）
  await dlg.locator('input[placeholder*="员工请假"]').fill('员工请假审批流程')
  await dlg.locator('textarea').first().fill('https://example.com')
  const genBtn = dlg.getByRole('button', { name: /开始生成/ })
  console.log('生成按钮可用:', await genBtn.isEnabled())
  await genBtn.click()
  await p.waitForTimeout(1000)
  console.log('生成中文案:', (await genBtn.innerText().catch(() => '')).trim())

  await p.waitForTimeout(75000)
  const preview = await dlg.locator('.qcard').count()
  const msg = await p.locator('.el-message').last().innerText().catch(() => '')
  console.log('预览题卡数:', preview)
  if (preview) {
    console.log('依据标签:', await dlg.locator('.mat-tag').allInnerTexts())
    console.log('已选文案:', (await dlg.locator('.pick-bar .muted').innerText().catch(() => '')).trim())
    console.log('首题:', (await dlg.locator('.qtext').first().innerText().catch(() => '')).slice(0, 60))
  } else {
    console.log('提示:', msg.slice(0, 160))
  }

  await p.screenshot({ path: 'smoke-ai-questions.png' })
  console.log('页面错误:', errs.length ? errs.slice(0, 4) : '无')
  await b.close()
})()
