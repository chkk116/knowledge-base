/**
 * 移动端新功能 + 红点居中 验证
 *
 * 用法：KB_TOKEN=<jwt> NODE_OPTIONS='' node verify-mobile-batch.cjs
 * 覆盖：
 *   1) 首页改造：金刚区 8 项 / 数据概览 / 公告 / 精选文章
 *   2) 新增页面逐个渲染（无 JS 报错）
 *   3) TabBar 未读红点：数字是否真正居中（像素级测量）
 *   4) 关于页「本版本更新说明」
 */
const { chromium } = require('playwright-core')

const BASE = 'https://www.chkk116.cloud'
const TOKEN = process.env.KB_TOKEN || ''

const PAGES = [
  ['首页', '/m/home'],
  ['发现', '/m/discover'],
  ['我的数据', '/m/stats'],
  ['我的收藏', '/m/favorites'],
  ['离线内容', '/m/offline'],
  ['外观', '/m/appearance'],
  ['应用锁', '/m/lock'],
  ['关于', '/m/about'],
  ['我的', '/m/profile'],
]

;(async () => {
  const b = await chromium.launch({ headless: true, channel: 'chrome' })
  const ctx = await b.newContext({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true })
  const page = await ctx.newPage()

  const errs = []
  page.on('pageerror', (e) => errs.push(String(e.message).slice(0, 160)))
  page.on('console', (m) => { if (m.type() === 'error') errs.push('[console] ' + m.text().slice(0, 160)) })

  await page.addInitScript((t) => {
    try { localStorage.setItem('kb_token', t) } catch (e) {}
  }, TOKEN)
  // 让红点有数字可测
  await page.route('**/api/notifications/unread', (r) => r.fulfill({ status: 200, json: { count: 8 } }))

  // ---------- 1) 逐页渲染 ----------
  console.log('== 1. 页面渲染 ==')
  for (const [name, path] of PAGES) {
    errs.length = 0
    await page.goto(BASE + path, { waitUntil: 'domcontentloaded', timeout: 45000 })
    await page.waitForTimeout(4200)
    const txt = (await page.evaluate(() => document.body.innerText)).replace(/\s+/g, ' ')
    const url = page.url().replace(BASE, '')
    const okUrl = url.startsWith(path)
    console.log(`  ${name.padEnd(6)} ${okUrl ? '✓' : '✗'} ${url}  len=${txt.length}${errs.length ? '  ⚠️ ' + errs[0] : ''}`)
  }

  // ---------- 2) 首页区块 ----------
  console.log('== 2. 首页区块 ==')
  await page.goto(BASE + '/m/home', { waitUntil: 'domcontentloaded', timeout: 45000 })
  await page.waitForTimeout(5000)
  const home = await page.evaluate(() => ({
    quicks: document.querySelectorAll('.quick').length,
    quickLabels: Array.from(document.querySelectorAll('.quick .q-txt')).map((e) => e.textContent.trim()),
    overview: document.querySelectorAll('.ovw-item').length,
    overviewNums: Array.from(document.querySelectorAll('.ovw-num')).map((e) => e.textContent.trim()),
    hasAnn: !!document.querySelector('.ann'),
    picks: document.querySelectorAll('.m-row-title').length,
    text: document.body.innerText.replace(/\s+/g, ' ').slice(0, 200),
  }))
  console.log('  金刚区入口数 :', home.quicks, home.quickLabels.join(' / '))
  console.log('  数据概览格数 :', home.overview, '→', home.overviewNums.join(' | '))
  console.log('  公告卡片     :', home.hasAnn ? '有' : '（无公告）')

  // ---------- 3) 红点数字居中（像素级） ----------
  console.log('== 3. 未读红点数字居中 ==')
  const dot = await page.evaluate(() => {
    const el = document.querySelector('.dot')
    if (!el) return null
    const box = el.getBoundingClientRect()
    const rg = document.createRange()
    rg.selectNodeContents(el)
    const t = rg.getBoundingClientRect()
    return {
      text: el.textContent,
      boxW: +box.width.toFixed(2), boxH: +box.height.toFixed(2),
      dx: +(t.x + t.width / 2 - (box.x + box.width / 2)).toFixed(2),
      dy: +(t.y + t.height / 2 - (box.y + box.height / 2)).toFixed(2),
      style: getComputedStyle(el).display + '/' + getComputedStyle(el).lineHeight,
    }
  })
  if (!dot) console.log('  ✗ 没找到红点（未读数没生效）')
  else {
    const ok = Math.abs(dot.dx) <= 0.75 && Math.abs(dot.dy) <= 0.75
    console.log(`  内容="${dot.text}" 尺寸=${dot.boxW}×${dot.boxH} display/line-height=${dot.style}`)
    console.log(`  水平偏移=${dot.dx}px  垂直偏移=${dot.dy}px  → ${ok ? '✓ 居中' : '✗ 仍偏心'}`)
  }

  // ---------- 4) 关于页更新说明 ----------
  console.log('== 4. 关于页更新说明 ==')
  await page.goto(BASE + '/m/about', { waitUntil: 'domcontentloaded', timeout: 45000 })
  await page.waitForTimeout(4500)
  const about = await page.evaluate(() => {
    const t = document.body.innerText.replace(/\s+/g, ' ')
    return {
      hasLog: !!document.querySelector('.log-list'),
      items: document.querySelectorAll('.log-list li').length,
      ver: (document.querySelector('.log-v') || {}).textContent || '',
      more: (document.querySelector('.log-more') || {}).textContent || '',
      head: t.slice(0, 120),
    }
  })
  console.log('  更新说明区块 :', about.hasLog ? `有（${about.items} 条）` : '✗ 无')
  console.log('  版本徽标     :', about.ver.trim())
  console.log('  历史版本入口 :', about.more.trim().slice(0, 30))

  await b.close()
  console.log('\n完成')
})().catch((e) => { console.error('FAIL', e && e.message); process.exit(1) })
