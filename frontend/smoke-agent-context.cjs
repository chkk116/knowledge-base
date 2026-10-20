// 验证 Agent 是否带上文：先说一件事，再追问，看它记不记得
const { chromium } = require('playwright-core')
const TOKEN = process.argv[2]
const BASE = 'https://www.你的域名'

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

  await p.goto(BASE + '/ai/chat', { waitUntil: 'domcontentloaded' })
  await p.waitForTimeout(4500)
  await p.locator('.ai-mode-btn').nth(2).click()
  await p.waitForTimeout(600)

  const ta = p.locator('textarea.ai-text')
  const lastAi = async () => (await p.locator('.ai-msg.ai .ai-flat').last().innerText({ timeout: 800 }).catch(() => '')).replace(/\s+/g, ' ')

  async function ask(text, waitMs = 45000) {
    await ta.fill(text)
    await ta.press('Enter')
    const t0 = Date.now()
    let prev = ''
    while (Date.now() - t0 < waitMs) {
      await p.waitForTimeout(1500)
      const typing = await p.locator('.ai-typing').count()
      const cur = await lastAi()
      if (typing === 0 && cur && cur === prev && cur.length > 2) return cur
      prev = cur
    }
    return await lastAi()
  }

  const a1 = await ask('请记住：我的工号是 A123，稍后我会问你')
  console.log('第 1 轮回答:', a1.slice(0, 120))
  await p.waitForTimeout(1200)
  const a2 = await ask('我的工号是多少？')
  console.log('第 2 轮回答:', a2.slice(0, 200))
  console.log('是否记得 A123:', /A123/i.test(a2))
  console.log('页面错误:', errs.length ? errs.slice(0, 3) : '无')
  await b.close()
})()
