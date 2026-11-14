const { chromium } = require('playwright-core');
const BASE = process.env.KB_BASE || 'http://localhost:8080'   // 站点地址不入库，用环境变量传入

const token = process.env.KB_TOKEN;
(async () => {
  const b = await chromium.launch({ headless: true, channel: 'chrome' });
  const ctx = await b.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await ctx.newPage();
  await page.addInitScript((t) => { try { localStorage.setItem('kb_token', t) } catch (e) {} }, token);
  await page.goto('' + BASE + '/doc-shares/2?doc=8', { waitUntil: 'domcontentloaded', timeout: 40000 });
  await page.waitForTimeout(28000);
  const info = await page.evaluate(() => {
    const r = (sel) => { const e = document.querySelector(sel); if (!e) return null; const b = e.getBoundingClientRect(); return { top: Math.round(b.top), bottom: Math.round(b.bottom), h: Math.round(b.height) } };
    const canvas = document.querySelector('.collab-canvas');
    return {
      viewportH: window.innerHeight,
      pageScrollH: document.documentElement.scrollHeight,
      wrap: r('.wrap'), workspace: r('.workspace'), editor: r('.editor'), collab: r('.collab'), canvas: r('.collab-canvas'), footer: r('.site-footer'),
      canvasScrollable: canvas ? (canvas.scrollHeight > canvas.clientHeight) : null,
      canvasScrollH: canvas ? canvas.scrollHeight : null,
      canvasClientH: canvas ? canvas.clientHeight : null
    };
  });
  console.log(JSON.stringify(info, null, 1));
  console.log('---');
  console.log('页面是否溢出(总高>视口):', info.pageScrollH > info.viewportH ? '❌ 是' : '✓ 否');
  console.log('页脚是否被内容覆盖:', (info.canvas && info.footer) ? (info.canvas.bottom > info.footer.top ? '❌ 是' : '✓ 否') : 'n/a');
  console.log('编辑器内部可滚动:', info.canvasScrollable ? '✓ 是' : '（内容不够长，正常）');
  await b.close();
})().catch((e) => { console.error('FAIL', e && e.message); process.exit(1) });
