const { chromium } = require('playwright-core');
const BASE = process.env.KB_BASE || 'http://localhost:8080'   // 站点地址不入库，用环境变量传入

const token = process.env.KB_TOKEN;
(async () => {
  const b = await chromium.launch({ headless: true, channel: 'chrome' });
  const ctx = await b.newContext({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true });
  const page = await ctx.newPage();
  const errs = [];
  page.on('pageerror', (e) => errs.push('pageerror: ' + e.message.slice(0, 220)));
  page.on('console', (m) => { if (m.type() === 'error') errs.push('console: ' + m.text().slice(0, 220)) });
  await page.addInitScript((t) => { try { localStorage.setItem('kb_token', t) } catch (e) {} }, token);
  await page.goto('' + BASE + '/m/doc/2/8', { waitUntil: 'domcontentloaded', timeout: 30000 });
  await page.waitForTimeout(10000);
  const info = await page.evaluate(() => ({
    url: location.href,
    hasProseMirror: !!document.querySelector('.ProseMirror'),
    proseClass: document.querySelector('.ProseMirror')?.className || null,
    bodyText: (document.body.innerText || '').replace(/\s+/g, ' ').slice(0, 160)
  }));
  console.log(JSON.stringify(info, null, 1));
  console.log(errs.length ? '⚠️ 错误:\n' + errs.slice(0, 4).join('\n') : '✓ 无 JS 错误');
  await b.close();
})().catch((e) => { console.error('FAIL', e && e.message); process.exit(1) });
