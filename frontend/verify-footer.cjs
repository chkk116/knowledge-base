const { chromium } = require('playwright-core');
const BASE = process.env.KB_BASE || 'http://localhost:8080'   // 站点地址不入库，用环境变量传入

const token = process.env.KB_TOKEN;
(async () => {
  const b = await chromium.launch({ headless: true, channel: 'chrome' });
  const ctx = await b.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await ctx.newPage();
  if (!token) { console.error('缺少 KB_TOKEN 环境变量，脚本无法以登录态运行'); process.exit(1) }
  await page.addInitScript((t) => { try { localStorage.setItem('kb_token', t) } catch (e) {} }, token);
  await page.goto('' + BASE + '/doc-shares/2?doc=8', { waitUntil: 'domcontentloaded', timeout: 40000 });
  await page.waitForTimeout(16000);
  const info = await page.evaluate(() => {
    const wrap = document.querySelector('.wrap');
    const foot = document.querySelector('.site-footer');
    const ed = document.querySelector('.ProseMirror, .tiptap');
    const r = (el) => el ? { top: Math.round(el.getBoundingClientRect().top), bottom: Math.round(el.getBoundingClientRect().bottom), h: Math.round(el.getBoundingClientRect().height) } : null;
    return {
      viewportH: window.innerHeight,
      wrap: r(wrap), footer: r(foot), editor: r(ed),
      overlap: (wrap && foot) ? Math.round(wrap.getBoundingClientRect().bottom - foot.getBoundingClientRect().top) : null,
      docScrollH: document.documentElement.scrollHeight
    };
  });
  console.log(JSON.stringify(info, null, 1));
  console.log('wrap 底边 vs 页脚顶边重叠像素:', info.overlap, info.overlap > 0 ? '❌ 仍重叠' : '✓ 不重叠');
  await b.close();
})().catch((e) => { console.error('FAIL', e && e.message); process.exit(1) });
