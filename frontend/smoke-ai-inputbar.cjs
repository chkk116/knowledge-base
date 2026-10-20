// 验证「模型选择 + 上下文环」已挪到输入栏底部，并截图
const { chromium } = require('playwright-core')
const TOKEN = process.argv[2]
const BASE = 'https://www.chkk116.cloud'

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

  await p.goto(BASE + '/ai/chat', { waitUntil: 'commit', timeout: 90000 })
  await p.waitForTimeout(12000)

  console.log('顶部残留 .ai-head-right:', await p.locator('.ai-head-right').count())
  console.log('输入栏里的环:', await p.locator('.ai-input-tools .ai-ctx').count())
  console.log('输入栏里的模型按钮:', await p.locator('.ai-input-tools .aimp-trigger').count())
  console.log('模型按钮文字:', (await p.locator('.ai-input-tools .aimp-label').innerText({ timeout: 1000 }).catch(() => '')).trim())

  // 环和模型是否在同一行（纵向中心接近）
  const ringBox = await p.locator('.ai-input-tools .ai-ctx').boundingBox()
  const modelBox = await p.locator('.ai-input-tools .aimp-trigger').boundingBox()
  const sendBox = await p.locator('.ai-send').boundingBox()
  console.log('环 y=', Math.round(ringBox?.y || 0), '| 模型 y=', Math.round(modelBox?.y || 0), '| 发送 y=', Math.round(sendBox?.y || 0))

  await p.screenshot({ path: 'shot-ai-input.png', clip: { x: 240, y: 640, width: 1200, height: 260 } })

  // 点开环，弹层应该往上弹（在环上方）
  await p.locator('.ai-input-tools .ai-ctx').click()
  await p.waitForTimeout(700)
  const pop = p.locator('.ai-ctx-pop')
  const popBox = await pop.boundingBox()
  console.log('弹层可见:', await pop.isVisible().catch(() => false), '| 弹层底部 y=', Math.round((popBox?.y || 0) + (popBox?.height || 0)), '（应小于环 y=' + Math.round(ringBox?.y || 0) + '）')
  await p.screenshot({ path: 'shot-ai-ctxpop.png' })

  // 点开模型弹层
  await p.keyboard.press('Escape')
  await p.waitForTimeout(300)
  await p.locator('.ai-input-tools .aimp-trigger').click()
  await p.waitForTimeout(700)
  console.log('模型弹层可见:', await p.locator('.aimp-popover').isVisible().catch(() => false))
  await p.screenshot({ path: 'shot-ai-modelpop.png' })

  console.log('页面错误:', errs.length ? errs.slice(0, 3) : '无')
  await b.close()
})()
