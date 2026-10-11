/**
 * 验证：应用内更新「下载横幅」+ 刷新后恢复 + 消息页条目 + checksum 修复
 *
 * 用法：KB_TOKEN=<jwt> NODE_OPTIONS='' node verify-update-banner.cjs
 *
 * 关键点（上一版踩过的坑）：addInitScript 会注入**所有 frame** → 必须用 window.top===window.self
 * 限定只作用于父页，否则等于把 token/插件直接喂给 iframe，测试恒过。
 */
const { chromium } = require('playwright-core')

const BASE = 'https://www.chkk116.cloud'
const TOKEN = process.env.KB_TOKEN || ''
const APK = BASE + '/app-release/kb-app-1.14.apk'

;(async () => {
  const b = await chromium.launch({ headless: true, channel: 'chrome' })
  const ctx = await b.newContext({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true })
  const page = await ctx.newPage()

  const errs = []
  page.on('pageerror', (e) => errs.push(String(e.message).slice(0, 160)))

  // 原生环境打桩（仅父页）
  await page.addInitScript(({ t, apk }) => {
    let isTop = false
    try { isTop = window.top === window.self } catch (e) { isTop = false }
    if (!isTop) return
    try { localStorage.setItem('kb_token', t) } catch (e) {}
    window.Capacitor = {
      isNativePlatform: () => true,
      Plugins: {
        App: { getInfo: async () => ({ version: '1.0', build: '1' }) },
        CapacitorUpdater: { notifyAppReady: async () => {}, current: async () => ({ bundle: { version: 'x' } }) },
        ApkInstaller: {
          _cbs: {},
          addListener: async (evt, cb) => {
            window.Capacitor.Plugins.ApkInstaller._cbs[evt] = cb
            return { remove: async () => {} }
          },
          downloadAndInstall: async ({ url, checksum }) => {
            window.__dl = { url, checksum }
            const cbs = window.Capacitor.Plugins.ApkInstaller._cbs
            // 模拟进度（真实插件边下边报）
            setTimeout(() => cbs.progress && cbs.progress({ percent: 37 }), 500)
            await new Promise((r) => setTimeout(r, 9000))   // 长一点，方便测刷新
            return { filePath: '/tmp/x.apk', size: 1, launched: true }
          },
        },
      },
    }
  }, { t: TOKEN, apk: APK })

  // 让版本检查认为有新版（含 checksum）
  await page.route('**/api/public/app-version', (r) => r.fulfill({
    status: 200,
    json: { versionCode: 999, versionName: '9.9', url: APK, checksum: 'deadbeef', notes: '测试更新', force: false },
  }))

  // 打桩 Capacitor 后 isNative()=true → 移动端 router base 是 '/'，
  // 而站点上移动端入口是 /m/ → 打开 /m/ 会被 SPA 自己重定向到 /home（原生等价）
  async function loadApp() {
    await page.goto(BASE + '/m/', { waitUntil: 'domcontentloaded', timeout: 45000 })
    await page.waitForTimeout(4000)
  }
  /** 点 TabBar 切页（真实导航，避免手改 URL 与 router 不同步） */
  async function tapTab(i) {
    await page.evaluate((idx) => {
      const t = document.querySelectorAll('.tabbar .tab')[idx]
      if (t) t.click()
    }, i)
    await page.waitForTimeout(3000)
  }

  console.log('== 1. 启动自动检查 → 弹层 ==')
  await loadApp()
  const hasDialog = await page.evaluate(() => !!document.querySelector('.upd-panel'))
  console.log('   弹层出现:', hasDialog ? '✓' : '✗')

  console.log('== 2. 点「立即更新」→ 横幅应出现 ==')
  const go = await page.$('.upd-btn.primary')
  if (go) await go.click({ force: true })
  await page.waitForTimeout(2500)
  let bar = await page.evaluate(() => {
    const el = document.querySelector('.upd-bar')
    return el ? { t: el.querySelector('.ub-t')?.textContent?.trim(), d: el.querySelector('.ub-d')?.textContent?.trim() } : null
  })
  console.log('   横幅:', bar ? `✓ 「${bar.t}」/「${bar.d}」` : '✗ 没出现')
  console.log('   插件收到的 checksum:', await page.evaluate(() => window.__dl?.checksum || '(空 → 会被 fail-closed 拒绝)'))

  console.log('== 3. 切到「消息」页 → 横幅仍在 + 置顶条目 ==')
  await tapTab(3)   // 消息 Tab
  const msg = await page.evaluate(() => {
    const bar = document.querySelector('.upd-bar')
    const rows = Array.from(document.querySelectorAll('.m-row-title')).map((e) => e.textContent.trim())
    const first = document.querySelector('.m-row-desc')?.textContent?.trim()
    return { bar: !!bar, titles: rows.slice(0, 3), firstDesc: first }
  })
  console.log('   横幅仍在:', msg.bar ? '✓' : '✗', '| 列表前 3 条:', msg.titles.join(' / '))
  console.log('   首条描述:', msg.firstDesc)

  console.log('== 4. 刷新页面（模拟"刷新后栏不见了"）→ 横幅应恢复 ==')
  await loadApp()
  bar = await page.evaluate(() => {
    const el = document.querySelector('.upd-bar')
    return el ? { t: el.querySelector('.ub-t')?.textContent?.trim(), d: el.querySelector('.ub-d')?.textContent?.trim() } : null
  })
  console.log('   刷新后横幅:', bar ? `✓ 「${bar.t}」/「${bar.d}」` : '✗ 丢了')

  console.log('== 5. 手动关掉横幅 ==')
  await page.evaluate(() => { const el = document.querySelector('.ub-x'); if (el) el.click() })
  await page.waitForTimeout(800)
  const gone = await page.evaluate(() => !document.querySelector('.upd-bar'))
  console.log('   关闭后消失:', gone ? '✓' : '✗')

  console.log('\nJS 错误:', errs.length ? errs.slice(0, 2) : '无 ✓')
  await b.close()
})().catch((e) => { console.error('FAIL', e && e.message); process.exit(1) })
