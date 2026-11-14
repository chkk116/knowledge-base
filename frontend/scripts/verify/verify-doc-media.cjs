/**
 * 验证 App 文档页（MobileDocView）能渲染正文里的图片 / 视频
 *
 * 背景：App 文档页用 Tiptap 渲染正文，而 StarterKit 不含 Image/Video 扩展 →
 * <img> / <video> 被 ProseMirror schema 丢弃（网页端走 v-html 所以正常）。
 * 修法：extensions/ImageNode.js + VideoNode.js 接进 MobileDocView 的 extensions。
 *
 * 做法：mock 单篇文档接口返回带 img/video 的正文，再**从首页点最近文档卡片**进详情页
 * （真实路由导航，别手改 URL —— 打桩 Capacitor 后路由 base 是 '/'）。
 */
const { chromium } = require('playwright-core')

const BASE = 'https://www.你的域名'
const CONTENT = [
  '<p>这里是正文开头。</p>',
  '<p><img src="/uploads/test-pic.jpg" alt="测试图" data-full="/uploads/test-pic.jpg" /></p>',
  '<p><video src="/uploads/test-clip.mp4" poster="/uploads/test-poster.jpg"></video></p>',
  '<p>这里是正文结尾。</p>',
].join('')

;(async () => {
  const b = await chromium.launch({ headless: true, channel: 'chrome' })
  const ctx = await b.newContext({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true })
  const page = await ctx.newPage()
  const errs = []
  page.on('pageerror', (e) => errs.push(String(e.message).slice(0, 160)))

  await page.addInitScript((t) => {
    let isTop = false
    try { isTop = window.top === window.self } catch (e) { isTop = false }
    if (!isTop) return
    try { localStorage.setItem('kb_token', t) } catch (e) {}
    window.Capacitor = { isNativePlatform: () => true, Plugins: {} }
  }, process.env.KB_TOKEN || '')

  // 单篇文档详情：返回带图片 + 视频的正文
  await page.route('**/api/doc-shares/*/docs/*', (r) => {
    const u = r.request().url()
    if (/docs\/\d+$/.test(u)) {
      r.fulfill({
        status: 200,
        json: { id: 999, title: '媒体渲染测试', content: CONTENT, updatedAt: new Date().toISOString() },
      })
    } else {
      r.continue()
    }
  })

  await page.goto(BASE + '/m/', { waitUntil: 'domcontentloaded', timeout: 45000 })
  await page.waitForTimeout(6000)

  // 从首页点「最近文档」的第一张卡片进详情
  const clicked = await page.evaluate(() => {
    const c = document.querySelector('.hcard')
    if (c) { c.click(); return true }
    return false
  })
  console.log('点到最近文档卡片:', clicked ? '✓' : '✗')
  await page.waitForTimeout(5000)

  const st = await page.evaluate(() => {
    const root = document.querySelector('.m-prose') || document.body
    const imgs = Array.from(root.querySelectorAll('img'))
    const vids = Array.from(root.querySelectorAll('video'))
    return {
      url: location.pathname,
      imgs: imgs.length,
      imgSrc: imgs.map((i) => i.getAttribute('src')).slice(0, 2),
      imgFull: imgs.map((i) => i.getAttribute('data-full')).slice(0, 1),
      vids: vids.length,
      vidSrc: vids.map((v) => v.getAttribute('src')).slice(0, 2),
      bodyText: (root.innerText || '').replace(/\s+/g, ' ').slice(0, 80),
    }
  })
  console.log('当前路由:', st.url)
  console.log('图片数(应 1):', st.imgs, '| src:', st.imgSrc.join(' , '))
  console.log('  → 绝对化:', st.imgSrc.filter((x) => x && x.startsWith('https://')).length ? '✓ 已是绝对地址' : '✗ 仍是相对地址')
  console.log('  原图 data-full:', st.imgFull.join(' , '))
  console.log('视频数(应 1):', st.vids, '| src:', st.vidSrc.join(' , '))
  console.log('正文文本:', st.bodyText)
  console.log('JS 错误:', errs.length ? errs.slice(0, 2) : '无 ✓')
  await b.close()
})().catch((e) => { console.error('FAIL', e && e.message); process.exit(1) })
