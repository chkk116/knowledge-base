const { chromium } = require('playwright-core');
const BASE = process.env.KB_BASE || 'http://localhost:8080'   // 站点地址不入库，用环境变量传入

const token = process.env.KB_TOKEN;
(async () => {
  const b = await chromium.launch({ headless: true, channel: 'chrome' });
  const ctx = await b.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await ctx.newPage();
  if (!token) { console.error('缺少 KB_TOKEN 环境变量，脚本无法以登录态运行'); process.exit(1) }
  await page.addInitScript((t) => { try { localStorage.setItem('kb_token', t) } catch (e) {} }, token);
  await page.goto('' + BASE + '/doc-shares/2', { waitUntil: 'domcontentloaded', timeout: 30000 });
  await page.waitForTimeout(8000);
  const info = await page.evaluate(() => {
    const r = (sel) => { const e = document.querySelector(sel); if (!e) return null; const b = e.getBoundingClientRect(); return { top: Math.round(b.top), bottom: Math.round(b.bottom), h: Math.round(b.height) } };
    const wrap = document.querySelector('.wrap');
    const ws = document.querySelector('.workspace');
    const foot = document.querySelector('.site-footer');
    return {
      viewportH: window.innerHeight,
      wrap: r('.wrap'), footer: r('.site-footer'),
      workspaceBorder: ws ? getComputedStyle(ws).borderWidth : null,
      footerGap: (foot && wrap) ? Math.round(wrap.getBoundingClientRect().bottom - foot.getBoundingClientRect().bottom) : null,
      pageScrollH: document.documentElement.scrollHeight
    };
  });
  console.log(JSON.stringify(info, null, 1));
  console.log('页脚下方空白(px):', info.viewportH - info.footer.bottom);
  console.log('workspace 边框宽:', info.workspaceBorder, '(0 = 已去)');
  console.log('页面总高>视口?', info.pageScrollH > info.viewportH ? '是' : '否');
  await b.close();
})().catch((e) => { console.error('FAIL', e && e.message); process.exit(1) });
