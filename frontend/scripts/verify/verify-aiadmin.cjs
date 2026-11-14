const { chromium } = require('playwright-core');
const BASE = process.env.KB_BASE || 'http://localhost:8080'   // 站点地址不入库，用环境变量传入

const token = process.env.KB_TOKEN;
(async () => {
  const b = await chromium.launch({ headless: true, channel: 'chrome' });
  const ctx = await b.newContext({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true });
  const page = await ctx.newPage();
  const errs = [];
  page.on('pageerror', (e) => errs.push('pageerror: ' + e.message.slice(0, 200)));
  page.on('console', (m) => { if (m.type() === 'error') errs.push('console: ' + m.text().slice(0, 200)) });
  await page.addInitScript((t) => { try { localStorage.setItem('kb_token', t) } catch (e) {} }, token);
  await page.goto('' + BASE + '/m/manage/ai', { waitUntil: 'domcontentloaded', timeout: 30000 });
  await page.waitForTimeout(10000);

  const info = await page.evaluate(() => ({
    url: location.href,
    title: document.querySelector('.h-title')?.textContent?.trim(),
    tabs: [...document.querySelectorAll('.seg-btn')].map((e) => e.textContent.trim()),
    modelRows: document.querySelectorAll('.m-row').length,
    modelNames: [...document.querySelectorAll('.m-row-title')].map((e) => e.textContent.trim()).slice(0, 6),
    bodyText: (document.body.innerText || '').replace(/\s+/g, ' ').slice(0, 200)
  }));
  console.log(JSON.stringify(info, null, 1));

  // 切到用量 / 设置
  const tabs = await page.$$('.seg-btn');
  if (tabs[1]) { await tabs[1].click(); await page.waitForTimeout(2500); }
  const usage = await page.evaluate(() => ({
    stats: [...document.querySelectorAll('.stat-n')].map((e) => e.textContent.trim()),
    usageRows: document.querySelectorAll('.u-row').length
  }));
  console.log('用量页:', JSON.stringify(usage));
  if (tabs[2]) { await tabs[2].click(); await page.waitForTimeout(1500); }
  const setInfo = await page.evaluate(() => ({
    setRows: document.querySelectorAll('.set-row').length,
    hasSwitch: !!document.querySelector('.el-switch')
  }));
  console.log('设置页:', JSON.stringify(setInfo));
  console.log(errs.length ? '⚠️ ' + errs.slice(0, 3).join(' | ') : '✓ 无 JS 错误');
  await b.close();
})().catch((e) => { console.error('FAIL', e && e.message); process.exit(1) });
