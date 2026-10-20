// 验证：出题不再 15s 超时；确认弹窗只问一次（模型重试时不再弹）
const { chromium } = require('playwright-core')
const TOKEN = process.argv[2]
const TASK = process.argv[3] || '帮我出 3 道关于 TCP/IP 模型的单选题，加进题库'

;(async () => {
  const b = await chromium.launch({ channel: 'chrome', headless: true, args: ['--no-proxy-server'] })
  const ctx = await b.newContext({ viewport: { width: 1440, height: 900 } })
  await ctx.addInitScript(([t]) => {
    localStorage.setItem('kb_token', t)
    localStorage.setItem('kb_user_id', '1')
    localStorage.setItem('kb_user_cache', JSON.stringify({ id: 1, username: 'admin', role: 'ADMIN' }))
  }, [TOKEN])
  const p = await ctx.newPage()
  const errs = []
  p.on('pageerror', (e) => errs.push('PAGEERROR ' + e.message))

  const batch = []
  p.on('response', async (r) => {
    if (r.url().includes('/admin/exams/ai/generate')) {
      const body = await r.text().catch(() => '')
      batch.push({ kind: 'generate', status: r.status(), body: body.slice(0, 120) })
    }
    if (r.url().includes('/admin/exams/questions/batch')) {
      batch.push({ kind: 'save', status: r.status() })
    }
  })

  await p.goto('https://www.你的域名/ai/chat', { waitUntil: 'commit', timeout: 90000 })
  await p.waitForTimeout(11000)
  await p.locator('.ai-mode-btn').nth(2).click()
  await p.waitForTimeout(600)

  const ta = p.locator('textarea.ai-text')
  await ta.fill(TASK)
  await ta.press('Enter')

  const asks = []
  let answer = ''
  for (let i = 0; i < 150; i++) {
    await p.waitForTimeout(1500)
    const mask = p.locator('.ai-ask-mask')
    if (await mask.isVisible().catch(() => false)) {
      const txt = (await mask.innerText({ timeout: 800 }).catch(() => '')).replace(/\s+/g, ' ')
      asks.push(txt.slice(0, 60))
      console.log(`弹层 ${asks.length}:`, txt.slice(0, 80))
      // 按选项文本精确点，避免 getByRole 匹配不到时误点遮罩（那会当成「取消」）
      let clicked = ''
      const opts = mask.locator('.ai-ask-opt')
      const n = await opts.count()
      for (let k = 0; k < n; k++) {
        const t = (await opts.nth(k).innerText().catch(() => '')).trim()
        if (t.includes('加入') || t.includes('继续')) { await opts.nth(k).click(); clicked = t; break }
      }
      console.log('   → 点了:', clicked || '(无匹配)')
      await p.waitForTimeout(4000)
      continue
    }
    const typing = await p.locator('.ai-typing').count()
    const last = (await p.locator('.ai-msg.ai .ai-flat').last().innerText({ timeout: 800 }).catch(() => '')).replace(/\s+/g, ' ')
    if (typing === 0 && last.length > 25 && asks.length > 0) { answer = last; break }
  }

  console.log('--- 结果 ---')
  console.log('弹层总次数:', asks.length, '（预期 2：一次依据确认 + 一次入库确认）')
  console.log('出题接口调用:', batch.filter((x) => x.kind === 'generate').map((x) => x.status).join(','))
  console.log('入库接口调用:', batch.filter((x) => x.kind === 'save').map((x) => x.status).join(','))
  console.log('最终回答:', answer.slice(0, 320))
  console.log('页面错误:', errs.length ? errs.slice(0, 3) : '无')
  await b.close()
})()
