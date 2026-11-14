const { chromium } = require('playwright-core');
const BASE = process.env.KB_BASE || 'http://localhost:8080'   // 站点地址不入库，用环境变量传入

const token = process.env.KB_TOKEN;
(async () => {
  const b = await chromium.launch({ headless: true, channel: 'chrome' });
  const ctx = await b.newContext({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true });
  const page = await ctx.newPage();
  const errs = [];
  page.on('pageerror', (e) => errs.push('pageerror: ' + e.message.slice(0, 180)));
  await page.addInitScript((t) => { try { localStorage.setItem('kb_token', t) } catch (e) {} }, token);
  await page.goto('' + BASE + '/m/doc/3/8/edit', { waitUntil: 'domcontentloaded', timeout: 30000 });
  await page.waitForTimeout(12000);

  // 点「表格」按钮 → 应弹出选择器
  const btns = await page.$$('.de-bar .tb');
  let tableBtn = null;
  for (const btn of btns) { const t = (await btn.textContent()).trim(); if (t === '表格') tableBtn = btn; }
  if (tableBtn) { await tableBtn.click(); await page.waitForTimeout(900); }
  const picker = await page.evaluate(() => ({
    mask: !!document.querySelector('.tp-mask'),
    cells: document.querySelectorAll('.tp-cell').length,
    sizeText: document.querySelector('.tp-size')?.textContent?.trim()
  }));
  console.log('表格选择器:', JSON.stringify(picker));

  // 点 4×4 那格（index 22 = r4,c4）
  const cells = await page.$$('.tp-cell');
  if (cells[21]) { await cells[21].click(); await page.waitForTimeout(1200); }
  const after = await page.evaluate(() => ({
    pickerClosed: !document.querySelector('.tp-mask'),
    tables: document.querySelectorAll('.ProseMirror table').length,
    rows: document.querySelectorAll('.ProseMirror table tr').length,
    cols: document.querySelectorAll('.ProseMirror table tr:first-child th, .ProseMirror table tr:first-child td').length
  }));
  console.log('插入后:', JSON.stringify(after));
  console.log(errs.length ? '⚠️ ' + errs.slice(0, 2).join(' | ') : '✓ 无 JS 错误');
  await b.close();
})().catch((e) => { console.error('FAIL', e && e.message); process.exit(1) });
