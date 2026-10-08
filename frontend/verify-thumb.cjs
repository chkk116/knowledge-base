const { chromium } = require('playwright-core');
const BASE = process.env.KB_BASE || 'http://localhost:8080'   // 站点地址不入库，用环境变量传入

const token = process.env.KB_TOKEN;
(async () => {
  const b = await chromium.launch({ headless: true, channel: 'chrome' });
  const ctx = await b.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await ctx.newPage();
  if (!token) { console.error('缺少 KB_TOKEN 环境变量，脚本无法以登录态运行'); process.exit(1) }
  await page.addInitScript((t) => { try { localStorage.setItem('kb_token', t) } catch (e) {} }, token);
  await page.goto('' + BASE + '/ai/chat', { waitUntil: 'domcontentloaded', timeout: 30000 });
  await page.waitForTimeout(9000); console.log("URL:", page.url());
  const read = () => page.evaluate(() => {
    const th = document.querySelector('.ai-mode-thumb');
    const seg = document.querySelector('.ai-mode');
    if (!th || !seg) return null;
    const tr = th.getBoundingClientRect();
    const sr = seg.getBoundingClientRect();
    return { thumbLeft: Math.round(tr.left - sr.left), thumbW: Math.round(tr.width), segW: Math.round(sr.width), transform: getComputedStyle(th).transform };
  });
  console.log('默认(kb):', JSON.stringify(await read()));
  const btns = await page.$$('.ai-mode-btn');
  console.log('按钮个数:', btns.length);
  if (btns[2]) { await btns[2].click(); await page.waitForTimeout(700); console.log('切到 Agent:', JSON.stringify(await read())); }
  if (btns[1]) { await btns[1].click(); await page.waitForTimeout(700); console.log('切到 通用:', JSON.stringify(await read())); }
  await b.close();
})().catch((e) => { console.error('FAIL', e && e.message); process.exit(1) });
