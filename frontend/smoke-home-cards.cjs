/**
 * 冒烟测试：首页复习提醒 + 金刚区角标。
 * 用法（frontend 目录下）：node smoke-home-cards.cjs <token>
 *
 * 先用接口造 2 张到期卡片 → 打开首页看提醒卡与角标 → 点进 /cards → 清理。
 */
const { chromium } = require('playwright-core')

const TOKEN = process.argv[2]
if (!TOKEN) { console.error('缺少 token'); process.exit(1) }
const BASE = 'https://www.chkk116.cloud'
const API = BASE + '/api'

;(async () => {
  const browser = await chromium.launch({ channel: 'chrome', headless: true })
  const ctx = await browser.newContext({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true })
  const headers = { Authorization: 'Bearer ' + TOKEN }
  await ctx.addInitScript(([t]) => {
    localStorage.setItem('kb_token', t)
    localStorage.setItem('kb_user_id', '1')
    localStorage.setItem('kb_user_cache', JSON.stringify({ id: 1, username: 'admin', role: 'ADMIN' }))
  }, [TOKEN])

  // 1) 造 2 张到期卡片
  const ids = []
  for (const n of [1, 2]) {
    const r = await ctx.request.post(`${API}/flashcards`, {
      headers, data: { front: `冒烟测试卡片 ${n}：这条会被清理`, back: '答案' },
    })
    const j = await r.json()
    if (j?.id) ids.push(j.id)
  }
  console.log('创建卡片:', ids)

  const page = await ctx.newPage()
  const errors = []
  page.on('pageerror', (e) => errors.push('pageerror: ' + e.message))
  page.on('console', (m) => { if (m.type() === 'error') errors.push('console: ' + m.text()) })

  // 2) 首页
  await page.goto(BASE + '/m/home', { waitUntil: 'domcontentloaded' })
  await page.waitForTimeout(4000)

  const card = page.locator('.plan-card', { hasText: '张卡片待复习' })
  const hasCard = (await card.count()) > 0
  console.log('复习提醒卡出现:', hasCard, hasCard ? '| ' + (await card.first().innerText()).replace(/\s+/g, ' ').trim() : '')

  const badge = page.locator('.q-badge').first()
  console.log('金刚区角标:', (await badge.count()) ? (await badge.innerText()).trim() : '（无）')

  // 3) 点提醒卡进复习页
  if (hasCard) {
    await card.first().click()
    await page.waitForTimeout(1800)
    console.log('跳转后路径:', new URL(page.url()).pathname)
    console.log('待复习数:', (await page.locator('.stat-num').innerText().catch(() => '?')).trim())
  }

  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)
  console.log('横向溢出(px):', overflow)
  console.log('页面错误:', errors.length ? errors.slice(0, 5) : '无')

  // 4) 清理
  for (const id of ids) await ctx.request.delete(`${API}/flashcards/${id}`, { headers })
  console.log('已清理测试卡片')
  await browser.close()

  const ok = hasCard && new URL(page.url()).pathname === '/m/cards' && overflow <= 0 && errors.length === 0
  console.log(ok ? '✅ 通过' : '❌ 未通过')
  process.exit(ok ? 0 : 1)
})().catch((e) => { console.error('测试异常:', e.message); process.exit(1) })
