const { chromium } = require('playwright-core');
const BASE = process.env.KB_BASE || 'http://localhost:8080'   // 站点地址不入库，用环境变量传入

const token = process.env.KB_TOKEN;
(async () => {
  const b = await chromium.launch({ headless: true, channel: 'chrome' });
  const ctx = await b.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await ctx.newPage();
  const logs = [];
  page.on('pageerror', (e) => logs.push('pageerror: ' + e.message));
  page.on('console', (m) => { if (m.type() === 'error') logs.push('console: ' + m.text().slice(0, 200)) });
  if (!token) { console.error('缺少 KB_TOKEN 环境变量，脚本无法以登录态运行'); process.exit(1) }
  await page.addInitScript((t) => { try { localStorage.setItem('kb_token', t) } catch (e) {} }, token);
  await page.goto('' + BASE + '/doc-shares/2?doc=8', { waitUntil: 'domcontentloaded', timeout: 40000 });
  await page.waitForTimeout(18000);
  const info = await page.evaluate(() => {
    const pm = document.querySelector('.ProseMirror, .tiptap');
    return {
      editorHtmlOk: !!pm,
      hasFcBlock: !!document.querySelector('[data-type="flowchart-block"]'),
      anyWrap: [...document.querySelectorAll('[class*=kb-fc],[class*=fc-]')].slice(0, 5).map((e) => e.className),
      bodyHint: document.body.innerText.replace(/\s+/g, ' ').slice(0, 120)
    };
  });
  console.log(JSON.stringify(info, null, 1));
  if (logs.length) console.log('错误:', logs.slice(0, 4));
  await b.close();
})().catch((e) => { console.error('FAIL', e && e.message); process.exit(1) });
