/**
 * 复现/验证：App 内 iframe 管理页被弹到登录页（跨域登录态桥接）
 *
 * 做法：用 Playwright 把本地产物「挂」在 https://localhost 下（App 的真实父页 origin），
 * 打桩 window.Capacitor 让 resolveUrl 走原生绝对地址，再打开 /embed/<kind>。
 * 子页是真实部署站 —— 跨域，和 App 里完全一致。
 * 判定：iframe 最终停在 /login 还是目标管理页。
 *
 * 用法：KB_TOKEN=<jwt> NODE_OPTIONS='' node verify-embed-bridge.cjs [kind...]
 */
const fs = require('fs')
const path = require('path')
const { chromium } = require('playwright-core')

const DIST = path.resolve(__dirname, 'dist-' + (process.env.DIST_VER || 'v203'))
const TOKEN = process.env.KB_TOKEN || ''
const KINDS = process.argv.slice(2).length ? process.argv.slice(2) : ['adminfooter', 'adminmaintain', 'adminstats']

const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8', '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.jpg': 'image/jpeg', '.webp': 'image/webp',
  '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
}

;(async () => {
  const b = await chromium.launch({ headless: true, channel: 'chrome' })
  const ctx = await b.newContext({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true })
  const page = await ctx.newPage()

  // 把 https://localhost/** 映射到本地构建产物（模拟 Capacitor 本地包）
  await page.route('https://localhost/**', async (route) => {
    const u = new URL(route.request().url())
    let p = decodeURIComponent(u.pathname)
    // SPA 回退：无扩展名 → 移动端入口
    if (p === '/' || !path.extname(p)) p = '/mobile.html'
    const file = path.join(DIST, p.replace(/^\//, ''))
    if (!fs.existsSync(file)) return route.fulfill({ status: 404, contentType: 'text/plain', body: 'not found' })
    return route.fulfill({
      status: 200,
      contentType: MIME[path.extname(file).toLowerCase()] || 'application/octet-stream',
      body: fs.readFileSync(file),
    })
  })

  // 原生环境打桩（必须在页面脚本前）
  // ⚠️ addInitScript 会注入到**所有 frame**（含跨域 iframe）——那样等于把 token 直接
  // 塞给子页，测试恒过、测不出桥接坏没坏。必须用 window.top === window.self 限定只作用于父页。
  await page.addInitScript((t) => {
    let isTop = false
    try { isTop = window.top === window.self } catch (e) { isTop = false }
    if (!isTop) return
    window.Capacitor = { isNativePlatform: () => true, Plugins: {} }
    try { localStorage.setItem('kb_token', t) } catch (e) {}
  }, TOKEN)

  let fail = 0
  for (const kind of KINDS) {
    await page.goto('https://localhost/embed/' + kind, { waitUntil: 'domcontentloaded', timeout: 45000 })
    await page.waitForTimeout(6500)

    const frames = page.frames().filter((f) => f.url().includes('你的域名'))
    const target = frames[0] ? frames[0].url() : '(没有 iframe)'
    // ⚠️ 别用 /\/login/ —— /admin/login-qr 也含 "/login"，会误判成登录页
    const onLogin = /\/login(\/|\?|$)/.test(target)
    console.log(`  ${kind.padEnd(16)} → ${target.replace('https://www.你的域名', '')}  ${onLogin ? '✗ 被弹到登录页' : '✓ 进入管理页'}`)
    if (onLogin) fail++
  }

  await b.close()
  console.log(fail === 0 ? '\n✅ 桥接正常' : `\n❌ ${fail}/${KINDS.length} 个仍被弹到登录页`)
  process.exit(fail === 0 ? 0 : 1)
})().catch((e) => { console.error('FAIL', e && e.message); process.exit(2) })
