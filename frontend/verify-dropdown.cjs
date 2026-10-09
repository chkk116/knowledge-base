const { chromium } = require('playwright-core');
const BASE = process.env.KB_BASE || 'http://localhost:8080'   // 站点地址不入库，用环境变量传入

const token = process.env.KB_TOKEN;
(async () => {
  const b = await chromium.launch({ headless: true, channel: 'chrome' });
  const ctx = await b.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await ctx.newPage();
  await page.addInitScript((t) => { try { localStorage.setItem('kb_token', t) } catch (e) {} }, token);
  await page.goto('' + BASE + '/', { waitUntil: 'domcontentloaded', timeout: 30000 });
  await page.waitForTimeout(6000);
  const av = await page.$('.avatar');
  if (!av) { console.log('未找到头像，可能未登录'); await b.close(); return; }
  await av.click();
  await page.waitForTimeout(2500);
  const info = await page.evaluate(() => {
    const m = document.querySelector('.el-dropdown-menu');
    if (!m) return { open: false };
    const r = m.getBoundingClientRect();
    return {
      open: true,
      width: Math.round(r.width),
      viewportW: window.innerWidth,
      pct: (r.width / window.innerWidth * 100).toFixed(1) + '%',
      items: [...m.querySelectorAll('.el-dropdown-menu__item')].map((e) => e.textContent.trim()).slice(0, 6)
    };
  });
  console.log(JSON.stringify(info, null, 1));
  await b.close();
})().catch((e) => { console.error('FAIL', e && e.message); process.exit(1) });
