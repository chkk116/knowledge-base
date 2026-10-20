// 上下文用量环 / 思考强度 / 压缩 的 UI 冒烟
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

  const ring = p.locator('.ai-ctx')
  console.log('用量环存在:', await ring.count())
  console.log('环上数字:', (await ring.innerText({ timeout: 1000 }).catch(() => '')).trim())
  console.log('环标题:', await ring.getAttribute('title').catch(() => ''))

  await ring.click()
  await p.waitForTimeout(700)
  const pop = p.locator('.ai-ctx-pop')
  const shown = await pop.isVisible().catch(() => false)
  console.log('弹层可见:', shown)
  if (shown) {
    const txt = (await pop.innerText({ timeout: 1000 }).catch(() => '')).replace(/\s+/g, ' ')
    console.log('弹层内容:', txt.slice(0, 200))
    const btns = await pop.locator('.ctxp-tbtn').allTextContents().catch(() => [])
    console.log('思考强度档位:', btns)
    console.log('压缩按钮:', await pop.locator('.ctxp-act').count())

    // 切到「深入」看是否生效
    await pop.getByRole('button', { name: '深入' }).click()
    await p.waitForTimeout(800)
    const on = await pop.locator('.ctxp-tbtn.on').innerText().catch(() => '')
    const saved = await p.evaluate(() => localStorage.getItem('kb_ai_think'))
    console.log('选中档位:', on.trim(), '| localStorage:', saved)
    const msg = await p.locator('.el-message').last().innerText().catch(() => '')
    console.log('提示:', msg.trim())
    // 还原成标准，别把测试状态留给你
    await pop.getByRole('button', { name: '标准' }).click()
    await p.waitForTimeout(500)
  }

  await p.screenshot({ path: 'smoke-ctx.png' })
  console.log('页面错误:', errs.length ? errs.slice(0, 3) : '无')
  await b.close()
})()
