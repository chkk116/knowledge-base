const { chromium } = require('playwright-core');
const BASE = process.env.KB_BASE || 'http://localhost:8080'   // 站点地址不入库，用环境变量传入

const token = process.env.KB_TOKEN;
(async () => {
  const b = await chromium.launch({ headless: true, channel: 'chrome' });
  const ctx = await b.newContext({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true });
  const page = await ctx.newPage();
  await page.addInitScript((t) => { try { localStorage.setItem('kb_token', t) } catch (e) {} }, token);
  // 共享文档里的 OCR 表格文档（含「座位号 主机名 IP MAC地址」）
  await page.goto('' + BASE + '/m/doc/2/6', { waitUntil: 'domcontentloaded', timeout: 30000 });
  await page.waitForTimeout(9000);
  const info = await page.evaluate(() => {
    const t = document.querySelector('.m-prose table');
    if (!t) return { hasTable: false, proseHtml: (document.querySelector('.m-prose') || {}).innerHTML?.slice(0, 200) || '(无正文)' };
    const cs = getComputedStyle(t);
    return {
      hasTable: true,
      display: cs.display,
      width: cs.width,
      maxWidth: cs.maxWidth,
      overflowX: cs.overflowX,
      scrollW: t.scrollWidth,
      clientW: t.clientWidth,
      canScroll: t.scrollWidth > t.clientWidth,
      cols: t.querySelectorAll('th,td').length,
      firstRow: [...(t.querySelectorAll('tr')[0]?.querySelectorAll('th,td') || [])].map((c) => c.textContent.trim()),
      pageOverflow: document.documentElement.scrollWidth > window.innerWidth
    };
  });
  console.log(JSON.stringify(info, null, 1));
  await b.close();
})().catch((e) => { console.error('FAIL', e && e.message); process.exit(1) });
