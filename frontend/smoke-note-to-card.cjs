/**
 * 冒烟测试：摘录 → 一键生成复习卡片。
 * 用法（frontend 目录下）：node smoke-note-to-card.cjs <token>
 *
 * 流程：先用接口造一条摘录 → 打开「我的摘录」→ 点「做卡片」→ 填正面 → 生成
 * → 校验卡片确实建出来。测完把摘录和卡片都删掉。
 */
const { chromium } = require('playwright-core')

const TOKEN = process.argv[2]
if (!TOKEN) { console.error('缺少 token'); process.exit(1) }
const BASE = 'https://www.chkk116.cloud'
const API = BASE + '/api'
const SHARE = 1
const DOC = 11
const QUOTE = '冒烟测试摘录：这条内容用来验证一键生成卡片'

;(async () => {
  const browser = await chromium.launch({ channel: 'chrome', headless: true })
  const ctx = await browser.newContext({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true })
  const headers = { Authorization: 'Bearer ' + TOKEN }
  await ctx.addInitScript(([t]) => {
    localStorage.setItem('kb_token', t)
    localStorage.setItem('kb_user_id', '1')
    localStorage.setItem('kb_user_cache', JSON.stringify({ id: 1, username: 'admin', role: 'ADMIN' }))
  }, [TOKEN])

  // 1) 造一条摘录
  const annoRes = await ctx.request.post(`${API}/doc-shares/${SHARE}/docs/${DOC}/annotations`,
    { headers, data: { quote: QUOTE, note: '测试笔记', prefix: '', suffix: '' } })
  const anno = await annoRes.json()
  console.log('创建摘录:', annoRes.status(), anno?.id)

  const page = await ctx.newPage()
  const errors = []
  page.on('pageerror', (e) => errors.push('pageerror: ' + e.message))
  page.on('console', (m) => { if (m.type() === 'error') errors.push('console: ' + m.text()) })

  await page.goto(BASE + '/m/notes', { waitUntil: 'domcontentloaded' })
  await page.waitForSelector('.note-item', { timeout: 20000 })
  console.log('摘录条数:', await page.locator('.note-item').count())

  // 2) 找到那条摘录，点「做卡片」
  const item = page.locator('.note-item').filter({ hasText: '冒烟测试摘录' }).first()
  await item.locator('.card-op').click()
  await page.waitForSelector('.sheet', { timeout: 8000 })
  const tas = page.locator('.field textarea')
  console.log('背面预填:', (await tas.nth(1).inputValue()).slice(0, 30))
  await tas.nth(0).fill('冒烟测试：这条摘录讲的是什么？')
  await page.locator('.sheet-actions .primary-btn').click()
  await page.waitForTimeout(2200)
  const msg = await page.locator('.el-message').first().innerText().catch(() => '')
  console.log('提示:', msg.trim())

  // 3) 校验卡片确实建出来了
  const listRes = await ctx.request.get(`${API}/flashcards`, { headers })
  const cards = await listRes.json()
  const hit = (Array.isArray(cards) ? cards : []).filter((c) => String(c.front).includes('冒烟测试：这条摘录'))
  console.log('生成卡片数:', hit.length, hit.length ? '| source=' + String(hit[0].source).slice(0, 20) + ' | docId=' + hit[0].docId : '')

  // 4) 清理
  for (const c of hit) await ctx.request.delete(`${API}/flashcards/${c.id}`, { headers })
  if (anno?.id) await ctx.request.delete(`${API}/doc-shares/${SHARE}/docs/${DOC}/annotations/${anno.id}`, { headers })
  console.log('已清理测试数据')

  console.log('页面错误:', errors.length ? errors.slice(0, 5) : '无')
  await browser.close()

  const ok = hit.length === 1 && /已生成卡片/.test(msg) && errors.length === 0
  console.log(ok ? '✅ 通过' : '❌ 未通过')
  process.exit(ok ? 0 : 1)
})().catch((e) => { console.error('测试异常:', e.message); process.exit(1) })
