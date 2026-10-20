// AI 问答（agent 模式）出题冒烟：对话 → agent 调工具 → 确认入库 → 最终回答
const { chromium } = require('playwright-core')
const TOKEN = process.argv[2]
const TASK = process.argv[3] || '帮我出 3 道关于请假流程的单选题，加进题库'
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
  p.on('console', (m) => { if (m.type() === 'error') errs.push('ERR ' + m.text().slice(0, 100)) })

  // 关键：入库请求到底发了什么、回了什么
  p.on('response', async (r) => {
    const u = r.url()
    if (u.includes('/admin/exams/questions/batch')) {
      const req = r.request().postData() || ''
      console.log('>> batch 请求体:', req.slice(0, 400))
      const body = await r.text().catch(() => '')
      console.log('<< batch 响应:', r.status(), body.slice(0, 300))
    }
    if (u.includes('/ai/ask') && r.status() >= 400) {
      console.log('!! /ai/ask', r.status())
    }
  })

  await p.goto(BASE + '/ai/chat', { waitUntil: 'domcontentloaded' })
  await p.waitForTimeout(4500)

  await p.locator('.ai-mode-btn').nth(2).click()
  await p.waitForTimeout(600)
  console.log('模式:', (await p.locator('.ai-mode-btn.active').innerText({ timeout: 800 }).catch(() => '')).trim())

  const ta = p.locator('textarea.ai-text')
  await ta.fill(TASK)
  await ta.press('Enter')
  console.log('已发送:', TASK)

  let answer = ''
  let asks = 0
  for (let i = 0; i < 70; i++) {
    await p.waitForTimeout(1500)
    const mask = p.locator('.ai-ask-mask')
    if (await mask.isVisible().catch(() => false)) {
      asks++
      const txt = (await mask.innerText({ timeout: 800 }).catch(() => '')).replace(/\s+/g, ' ')
      console.log(`[${i}] 弹层${asks}:`, txt.slice(0, 150))
      let clicked = false
      for (const label of ['加入题库', '继续']) {
        const btn = mask.getByRole('button', { name: label })
        if (await btn.count()) { await btn.first().click(); clicked = true; break }
      }
      if (!clicked) await mask.locator('.ai-ask-opt').first().click()
      await p.waitForTimeout(1200)
      continue
    }
    const typing = await p.locator('.ai-typing').count()
    const last = (await p.locator('.ai-msg.ai .ai-flat').last().innerText({ timeout: 800 }).catch(() => '')).replace(/\s+/g, ' ')
    if (i % 4 === 0) console.log(`[${i}] 生成中=${typing} 末条=${last.slice(0, 50)}`)
    if (typing === 0 && last.length > 30) { answer = last; break }
  }

  console.log('步骤:', (await p.locator('.ai-agent-step').allTextContents().catch(() => [])).slice(0, 8))
  console.log('最终回答:', answer.slice(0, 500))
  console.log('页面错误:', errs.length ? errs.slice(0, 4) : '无')
  await b.close()
})()
