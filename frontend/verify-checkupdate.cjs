/**
const BASE = process.env.KB_BASE || 'http://localhost:8080'   // 站点地址不入库，用环境变量传入

 * 验证「应用内升级」完整链路（打桩原生环境）：
 *   ⓪ 关于页版本自检区
 *   ① 启动自动检查 → 弹 .upd-mask
 *   ② 点「立即更新」→ 必须走 ApkInstaller 插件（不是浏览器）+ 进度条出现
 *   ③ 点「稍后」→ 关闭
 *   ④ 手动「关于 → 检查更新」→ 弹「发现新版本」
 *   ⑤ 网页版（无原生插件）→ 提示「读不到本机版本号」，不谎报「已是最新」
 */
const { chromium } = require('playwright-core')
const token = process.env.KB_TOKEN || ''

async function run(mode) {
  const b = await chromium.launch({ headless: true, channel: 'chrome' })
  const ctx = await b.newContext({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true })
  const page = await ctx.newPage()
  const errs = []
  page.on('pageerror', (e) => errs.push(e.message.slice(0, 160)))
  await page.addInitScript((t) => { try { localStorage.setItem('kb_token', t) } catch (e) {} }, token)

  if (mode === 'native') {
    await page.addInitScript(() => {
      window.Capacitor = {
        isNativePlatform: () => true,
        getPlatform: () => 'android',
        Plugins: {
          App: {
            getInfo: async () => ({ version: '1.3', build: '4' }),   // 本机 versionCode=4
            addListener: async () => ({ remove: async () => {} }),
            minimizeApp: async () => {}
          },
          Browser: { open: async ({ url }) => { window.__openedUrl = url } },
          ApkInstaller: {
            addListener: async (evt, cb) => {
              window.__apkL = window.__apkL || {}
              window.__apkL[evt] = cb
              return { remove: async () => {} }
            },
            downloadAndInstall: async ({ url }) => {
              window.__apkUrl = url
              // 模拟真实插件持续上报的 progress 事件
              setTimeout(() => window.__apkL?.progress?.({ percent: 42 }), 700)
              await new Promise((r) => setTimeout(r, 3000))
              return { filePath: '/tmp/x.apk', size: 1, launched: true }
            }
          }
        }
      }
      try { history.replaceState(null, '', '/about') } catch (e) {}
    })
  }

  await page.goto('' + BASE + '/m/about', { waitUntil: 'domcontentloaded', timeout: 40000 })
  await page.waitForTimeout(10000)

  console.log(`[${mode}]`)
  console.log('  ⓪ 版本自检区 :', await page.evaluate(() => (document.querySelector('.vdiag')?.innerText || '(无)').replace(/\s+/g, ' ')))

  // ① 启动自动弹层
  const auto = await page.evaluate(() => {
    const m = document.querySelector('.upd-mask')
    return m ? {
      title: m.querySelector('.upd-title')?.textContent?.trim(),
      btns: [...m.querySelectorAll('.upd-btn')].map((e) => e.textContent.trim())
    } : null
  })
  console.log('  ① 启动自动弹层 :', auto ? JSON.stringify(auto) : '（无）')

  if (auto) {
    // ② 点「立即更新」→ 必须在弹层还开着的时候点
    const go = await page.$('.upd-btn.primary')
    if (go) { await go.click({ force: true }); await page.waitForTimeout(1500) }
    console.log('  ② 点「立即更新」→ 进度条 :', await page.evaluate(() => document.querySelector('.upd-prog-txt')?.textContent?.trim() || '(无)'))
    await page.waitForTimeout(2600)
    console.log('     ApkInstaller 收到 URL :', await page.evaluate(() => window.__apkUrl || '(未触发 → 走了浏览器)'))
    console.log('     Browser.open 被调用 :', await page.evaluate(() => window.__openedUrl || '(否 ✓)'))
    // ③ 关闭
    const later = await page.$('.upd-btn.ghost')
    if (later) { await later.click({ force: true }); await page.waitForTimeout(1000) }
    console.log('  ③ 点「稍后」→ 弹层还在吗 :', await page.evaluate(() => !!document.querySelector('.upd-mask')))
  }

  // ④ 手动检查更新
  const btn = await page.$('text="检查更新"')
  if (btn) { await btn.click({ force: true }); await page.waitForTimeout(2500) }
  console.log('  ④ 检查更新 → 弹窗标题 :', await page.evaluate(() => document.querySelector('.el-message-box__title')?.textContent?.trim() || '(无)'),
    '| 轻提示 :', await page.evaluate(() => (document.querySelector('.el-message')?.innerText || '').replace(/\s+/g, ' ').slice(0, 60) || '(无)'))

  if (errs.length) console.log('  ⚠️ 错误:', errs.slice(0, 2).join(' | '))
  console.log('')
  await b.close()
}

;(async () => { await run('native'); await run('web') })()
  .catch((e) => { console.error('FAIL', e && e.message); process.exit(1) })
