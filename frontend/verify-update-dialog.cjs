/**
const BASE = process.env.KB_BASE || 'http://localhost:8080'   // 站点地址不入库，用环境变量传入

 * 实测「应用内升级」弹层：把 window.Capacitor 打桩成原生环境（本机 versionCode=1），
 * 线上 app_version 是 2 → 应弹出更新弹层；点「立即更新」应走 Browser 插件（而非 window.open）。
 */
const { chromium } = require('playwright-core')
const token = process.env.KB_TOKEN

;(async () => {
  const b = await chromium.launch({ headless: true, channel: 'chrome' })
  const ctx = await b.newContext({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true })
  const page = await ctx.newPage()
  const errs = []
  page.on('pageerror', (e) => errs.push('pageerror: ' + e.message.slice(0, 200)))
  page.on('console', (m) => { if (m.type() === 'error') errs.push('console: ' + m.text().slice(0, 200)) })

  await page.addInitScript((t) => {
    try { localStorage.setItem('kb_token', t) } catch (e) {}
    // 打桩 Capacitor：伪装原生，本机版本号 = 1（低于线上的 2）
    window.Capacitor = {
      isNativePlatform: () => true,
      getPlatform: () => 'android',
      Plugins: {
        App: {
          getInfo: async () => ({ version: '1.0', build: '1' }),
          addListener: async () => ({ remove: async () => {} }),
          minimizeApp: async () => {}
        },
        Browser: {
          open: async ({ url }) => { window.__openedUrl = url }
        }
      }
    }
    // 原生下路由 base 是 '/'，把地址从 /m/home 改成 /（会 redirect 到 /home）
    try { history.replaceState(null, '', '/') } catch (e) {}
  }, token)

  await page.goto('' + BASE + '/m/home', { waitUntil: 'domcontentloaded', timeout: 40000 })
  await page.waitForTimeout(10000)

  const info = await page.evaluate(() => ({
    url: location.href,
    mask: !!document.querySelector('.upd-mask'),
    title: document.querySelector('.upd-title')?.textContent?.trim() || null,
    notes: (document.querySelector('.upd-notes')?.textContent || '').trim().slice(0, 60) || null,
    btns: [...document.querySelectorAll('.upd-btn')].map((e) => e.textContent.trim()),
    bodyText: (document.body.innerText || '').replace(/\s+/g, ' ').slice(0, 100)
  }))
  console.log('弹层:', JSON.stringify(info, null, 1))

  const primary = await page.$('.upd-btn.primary')
  if (primary) { await primary.click(); await page.waitForTimeout(1500) }
  const opened = await page.evaluate(() => window.__openedUrl || null)
  console.log('点「立即更新」→ Browser.open 收到:', opened)

  // 点「稍后」应关闭弹层
  const ghost = await page.$('.upd-btn.ghost')
  if (ghost) { await ghost.click(); await page.waitForTimeout(800) }
  console.log('点「稍后」后弹层还在吗:', await page.evaluate(() => !!document.querySelector('.upd-mask')))

  console.log(errs.length ? '⚠️ 错误: ' + errs.slice(0, 3).join(' | ') : '✓ 无 JS 错误')
  await b.close()
})().catch((e) => { console.error('FAIL', e && e.message); process.exit(1) })
