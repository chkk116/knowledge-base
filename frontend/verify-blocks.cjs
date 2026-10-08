const { chromium } = require('playwright-core');
const BASE = process.env.KB_BASE || 'http://localhost:8080'   // 站点地址不入库，用环境变量传入

const token = process.env.KB_TOKEN;
const DOC = process.env.DOC_ID;
const KIND = process.env.KIND;
(async () => {
  const b = await chromium.launch({ headless: true, channel: 'chrome' });
  const ctx = await b.newContext({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true });
  const page = await ctx.newPage();
  const errs = [];
  page.on('pageerror', (e) => errs.push('pageerror: ' + e.message.slice(0, 200)));
  page.on('console', (m) => { if (m.type() === 'error') errs.push('console: ' + m.text().slice(0, 200)) });
  await page.addInitScript((t) => { try { localStorage.setItem('kb_token', t) } catch (e) {} }, token);
  await page.goto(`' + BASE + '/m/doc/3/${DOC}`, { waitUntil: 'domcontentloaded', timeout: 30000 });
  await page.waitForTimeout(11000);
  const info = await page.evaluate(() => ({
    title: document.querySelector('.dv-h1')?.textContent?.trim() || '(无标题)',
    hasEditor: !!document.querySelector('.ProseMirror'),
    hasFcWrap: !!document.querySelector('.kb-fc-wrap'),
    fcNodes: document.querySelectorAll('.vue-flow__node').length,
    hasSheet: !!document.querySelector('.kb-sheet-wrap, .x-spreadsheet, [class*=sheet]'),
    hasMindmap: !!document.querySelector('.kb-mindmap-wrap, [class*=mindmap]'),
    blockType: [...document.querySelectorAll('[data-type$="-block"]')].map((e) => e.getAttribute('data-type')),
    bodyText: (document.body.innerText || '').replace(/\s+/g, ' ').slice(0, 120)
  }));
  console.log(`[${KIND} doc=${DOC}] ` + JSON.stringify(info));
  console.log(errs.length ? '  ⚠️ ' + errs.slice(0, 2).join(' | ') : '  ✓ 无 JS 错误');
  await b.close();
})().catch((e) => { console.error('FAIL', e && e.message); process.exit(1) });
