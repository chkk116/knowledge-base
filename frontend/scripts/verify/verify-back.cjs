/**
const BASE = process.env.KB_BASE || 'http://localhost:8080'   // 站点地址不入库，用环境变量传入

 * 实测「返回上一级」：把 Capacitor 打桩成原生环境，抓住 backButton 回调，
 * 手动触发，验证：
 *   - 二级页触发 → router.back()（回到上一级）
 *   - 根 Tab 触发 → minimizeApp()（最小化，不退出）
 * 注意：原生下路由 base 是 '/'（不是 /m/），所以进页面后把地址改成 '/' 再让路由跑。
 */
const { chromium } = require('playwright-core')
const token = process.env.KB_TOKEN

;(async () => {
  const b = await chromium.launch({ headless: true, channel: 'chrome' })
  const ctx = await b.newContext({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true })
  const page = await ctx.newPage()
  const errs = []
  page.on('pageerror', (e) => errs.push('pageerror: ' + e.message.slice(0, 200)))

  await page.addInitScript((t) => {
    try { localStorage.setItem('kb_token', t) } catch (e) {}
    window.__backCb = null
    window.__minimized = 0
    window.Capacitor = {
      isNativePlatform: () => true,
      getPlatform: () => 'android',
      Plugins: {
        App: {
          // build=99 → 不触发升级弹层，避免挡住点击
          getInfo: async () => ({ version: '1.2', build: '99' }),
          addListener: async (evt, cb) => {
            if (evt === 'backButton') window.__backCb = cb
            return { remove: async () => {} }
          },
          minimizeApp: async () => { window.__minimized++ }
        },
        Browser: { open: async ({ url }) => { window.__openedUrl = url } }
      }
    }
    try { history.replaceState(null, '', '/') } catch (e) {}
  }, token)

  await page.goto('' + BASE + '/m/home', { waitUntil: 'domcontentloaded', timeout: 40000 })
  await page.waitForTimeout(9000)

  console.log('1) 注册了 backButton 监听:', await page.evaluate(() => typeof window.__backCb === 'function'))
  console.log('   起始路由:', await page.evaluate(() => location.pathname))

  // 进二级页：点底部 Tab「我的」(第4个) → 点「关于」
  const tab = await page.$$('.tabbar .tab')
  console.log('   Tab 数:', tab.length)
  if (tab[4]) { await tab[4].click(); await page.waitForTimeout(1800) }
  console.log('2) 点「我的」Tab →', await page.evaluate(() => location.pathname), '(期望 /profile)')
  const about = await page.$('text="关于"')
  if (about) { await about.click(); await page.waitForTimeout(1800) }
  console.log('3) 点「关于」→', await page.evaluate(() => location.pathname), '(期望 /about)')

  const before = await page.evaluate(() => ({ path: location.pathname, back: window.history.state && window.history.state.back }))
  console.log('   二级页:', JSON.stringify(before))

  // 触发返回
  await page.evaluate(() => window.__backCb && window.__backCb())
  await page.waitForTimeout(1800)
  console.log('4) 触发返回 →', await page.evaluate(() => location.pathname), '(期望 /profile)')

  await page.evaluate(() => window.__backCb && window.__backCb())
  await page.waitForTimeout(1800)
  console.log('5) 再触发返回 →', await page.evaluate(() => location.pathname), '(期望 /home)')

  // 根 Tab 上触发 → 应最小化，不退出
  await page.evaluate(() => window.__backCb && window.__backCb())
  await page.waitForTimeout(1500)
  const root = await page.evaluate(() => ({ path: location.pathname, minimized: window.__minimized }))
  console.log('6) 根 Tab 触发 →', JSON.stringify(root), '(期望 path=/home, minimized=1)')

  console.log(errs.length ? '⚠️ ' + errs.slice(0, 2).join(' | ') : '✓ 无 JS 错误')
  await b.close()
})().catch((e) => { console.error('FAIL', e && e.message); process.exit(1) })
