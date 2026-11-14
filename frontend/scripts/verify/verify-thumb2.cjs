const { chromium } = require('playwright-core');
const BASE = process.env.KB_BASE || 'http://localhost:8080'   // 站点地址不入库，用环境变量传入

const token = process.env.KB_TOKEN;
(async () => {
  const b = await chromium.launch({ headless: true, channel: 'chrome' });
  const ctx = await b.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await ctx.newPage();
  const errs = [];
  page.on('pageerror', (e) => errs.push('pageerror: ' + e.message));
  page.on('console', (m) => { if (m.type() === 'error') errs.push('console: ' + m.text().slice(0, 160)) });
  if (!token) { console.error('缺少 KB_TOKEN 环境变量，脚本无法以登录态运行'); process.exit(1) }
  await page.addInitScript((t) => { try { localStorage.setItem('kb_token', t) } catch (e) {} }, token);
  await page.goto('' + BASE + '/ai/chat', { waitUntil: 'domcontentloaded', timeout: 30000 });
  await page.waitForTimeout(10000);
  console.log('URL:', page.url());
  const txt = (await page.evaluate(() => document.body.innerText)).replace(/\s+/g, ' ');
  console.log('页面文本:', txt.slice(0, 300));
  console.log('是否有 textarea:', !!(await page.$('textarea')));
  console.log('ai-input-bar 存在:', !!(await page.$('.ai-input-bar')));
  console.log('ai-mode 存在:', !!(await page.$('.ai-mode')));
  if (errs.length) console.log('错误:', errs.slice(0, 5));
  await b.close();
})().catch((e) => { console.error('FAIL', e && e.message); process.exit(1) });
