const { chromium } = require('playwright-core');
const BASE = process.env.KB_BASE || 'http://localhost:8080'   // 站点地址不入库，用环境变量传入

const token = process.env.KB_TOKEN;
(async () => {
  const b = await chromium.launch({ headless: true, channel: 'chrome' });
  const ctx = await b.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await ctx.newPage();
  if (!token) { console.error('缺少 KB_TOKEN 环境变量，脚本无法以登录态运行'); process.exit(1) }
  await page.addInitScript((t) => { try { localStorage.setItem('kb_token', t) } catch (e) {} }, token);
  await page.goto('' + BASE + '/doc-shares/2?doc=8', { waitUntil: 'domcontentloaded', timeout: 40000 });
  await page.waitForTimeout(16000);
  const info = await page.evaluate(() => ({
    fcWrap: !!document.querySelector('.kb-fc-wrap'),
    nodes: document.querySelectorAll('.vue-flow__node').length,
    edges: document.querySelectorAll('.vue-flow__edge').length,
    labels: [...document.querySelectorAll('.vue-flow__node')].slice(0, 12).map((n) => n.textContent.trim()),
    connText: (document.body.innerText.match(/连接中|已连接|已断开/g) || []).join(',')
  }));
  console.log(JSON.stringify(info, null, 1));
  await b.close();
})().catch((e) => { console.error('FAIL', e && e.message); process.exit(1) });
