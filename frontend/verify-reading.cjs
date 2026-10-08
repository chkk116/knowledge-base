const { chromium } = require('playwright-core');
const BASE = process.env.KB_BASE || 'http://localhost:8080'   // 站点地址不入库，用环境变量传入

const token = process.env.KB_TOKEN;
(async () => {
  const b = await chromium.launch({ headless: true, channel: 'chrome' });
  const ctx = await b.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await ctx.newPage();
  const logs = [];
  page.on('pageerror', (e) => logs.push('pageerror: ' + e.message));
  await page.addInitScript((t) => { try { localStorage.setItem('kb_token', t) } catch (e) {} }, token);
  await page.goto('' + BASE + '/doc-shares/2?doc=8', { waitUntil: 'domcontentloaded', timeout: 40000 });
  await page.waitForTimeout(30000);

  const rect = (sel) => page.evaluate((s) => { const e = document.querySelector(s); if (!e) return null; const b = e.getBoundingClientRect(); return { top: Math.round(b.top), bottom: Math.round(b.bottom), h: Math.round(b.height) } }, sel);

  console.log('全屏按钮存在:', !!(await page.$('.reading-btn')));
  console.log('页脚(常规):', JSON.stringify(await rect('.site-footer')));
  console.log('视口高:', await page.evaluate(() => window.innerHeight));

  const btn = await page.$('.reading-btn');
  if (btn) { await btn.click(); await page.waitForTimeout(1500); }
  const reading = await page.evaluate(() => ({
    hasClass: !!document.querySelector('.wrap.reading'),
    topbarHidden: !document.querySelector('.wrap.reading .topbar') || getComputedStyle(document.querySelector('.wrap.reading .topbar')).display === 'none',
    listHidden: !document.querySelector('.wrap.reading .doc-list') || getComputedStyle(document.querySelector('.wrap.reading .doc-list')).display === 'none',
    wrap: (() => { const e = document.querySelector('.wrap.reading'); if (!e) return null; const b = e.getBoundingClientRect(); return { top: Math.round(b.top), bottom: Math.round(b.bottom), w: Math.round(b.width), h: Math.round(b.height) } })()
  }));
  console.log('点全屏后:', JSON.stringify(reading));
  // Esc 退出
  await page.keyboard.press('Escape');
  await page.waitForTimeout(1200);
  console.log('Esc 后是否退出:', !(await page.$('.wrap.reading')) ? '✓ 已退出' : '❌ 仍在全屏');
  if (logs.length) console.log('错误:', logs.slice(0, 3));
  await b.close();
})().catch((e) => { console.error('FAIL', e && e.message); process.exit(1) });
