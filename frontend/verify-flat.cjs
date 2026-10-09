const { chromium } = require('playwright-core');
const token = process.env.KB_TOKEN;
const URL = process.env.PAGE_URL;
(async () => {
  const b = await chromium.launch({ headless: true, channel: 'chrome' });
  const ctx = await b.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await ctx.newPage();
  const errs = [];
  page.on('pageerror', (e) => errs.push('pageerror: ' + e.message.slice(0, 160)));
  await page.addInitScript((t) => { try { localStorage.setItem('kb_token', t) } catch (e) {} }, token);
  await page.goto(URL, { waitUntil: 'domcontentloaded', timeout: 30000 });
  await page.waitForTimeout(6000);
  const info = await page.evaluate(() => {
    const pick = (el) => { if (!el) return null; const cs = getComputedStyle(el); return { bg: cs.backgroundImage === 'none' ? cs.backgroundColor : 'GRADIENT!', color: cs.color, shadow: cs.boxShadow === 'none' ? 'none' : 'HAS-SHADOW' }; };
    return {
      catBadge: pick(document.querySelector('.cat-badge')),
      headIcon: pick(document.querySelector('.head-icon')),
      hasShadowAnywhere: [...document.querySelectorAll('.cat-badge, .head-icon')].some((e) => getComputedStyle(e).boxShadow !== 'none')
    };
  });
  console.log(JSON.stringify(info));
  console.log(errs.length ? '⚠️ ' + errs.slice(0, 2).join(' | ') : '✓ 无 JS 错误');
  await b.close();
})().catch((e) => { console.error('FAIL', e && e.message); process.exit(1) });
