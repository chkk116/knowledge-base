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
  await page.goto('' + BASE + '/m/doc/3/8/edit', { waitUntil: 'domcontentloaded', timeout: 30000 });
  await page.waitForTimeout(12000);
  const info = await page.evaluate(() => ({
    titleVal: document.querySelector('.t-input')?.value || '',
    hasProseMirror: !!document.querySelector('.ProseMirror'),
    editable: document.querySelector('.ProseMirror')?.getAttribute('contenteditable'),
    hasFcWrap: !!document.querySelector('.kb-fc-wrap'),
    fcNodes: document.querySelectorAll('.vue-flow__node').length,
    toolbarBtns: [...document.querySelectorAll('.de-bar .tb')].map((b) => b.textContent.trim()),
    charCount: document.querySelector('.de-count')?.textContent?.trim()
  }));
  console.log(JSON.stringify(info, null, 1));
  console.log(errs.length ? '⚠️ ' + errs.slice(0, 3).join(' | ') : '✓ 无 JS 错误');
  // 试一下工具栏：加粗
  const before = await page.evaluate(() => document.querySelector('.ProseMirror')?.innerHTML?.length || 0);
  const boldBtn = await page.$('.de-bar .tb');
  if (boldBtn) { await boldBtn.click(); await page.waitForTimeout(500); }
  console.log('工具栏可点:', !!boldBtn);
  const hasStrong = await page.evaluate(() => !!document.querySelector('.de-prose strong'))
  if (!hasStrong) { console.error('❌ 加粗未生效（页面无 strong 元素）'); process.exit(1) }
  console.log('加粗生效: true')
  await b.close();
})().catch((e) => { console.error('FAIL', e && e.message); process.exit(1) });
