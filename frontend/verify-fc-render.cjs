const { chromium } = require('playwright-core');
const token = process.env.KB_TOKEN;
const url = process.env.FC_URL;
if (!url) { console.error('缺少 FC_URL 环境变量（渲染结果页地址）'); process.exit(1) }
(async () => {
  const b = await chromium.launch({ headless: true, channel: 'chrome' });
  const ctx = await b.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await ctx.newPage();
  const logs = [];
  page.on('pageerror', (e) => logs.push('pageerror: ' + e.message));
  if (!token) { console.error('缺少 KB_TOKEN 环境变量，脚本无法以登录态运行'); process.exit(1) }
  await page.addInitScript((t) => { try { localStorage.setItem('kb_token', t) } catch (e) {} }, token);
  await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 40000 });
  await page.waitForTimeout(6000);
  // 点进那篇流程图文档
  const link = await page.$('text=员工请假审批流程');
  if (link) { await link.click(); await page.waitForTimeout(9000); console.log('已点开文档'); }
  else { console.log('未找到文档条目'); }
  const info = await page.evaluate(() => ({
    fcWrap: !!document.querySelector('.kb-fc-wrap'),
    fcNodes: document.querySelectorAll('.vue-flow__node').length,
    fcEdges: document.querySelectorAll('.vue-flow__edge').length,
    labels: [...document.querySelectorAll('.vue-flow__node')].slice(0, 6).map((n) => n.textContent.trim()),
    text: document.body.innerText.replace(/\s+/g, ' ').slice(0, 160)
  }));
  console.log('渲染检查:', JSON.stringify(info));
  if (logs.length) console.log('错误:', logs.slice(0, 3));
  await b.close();
})().catch((e) => { console.error('FAIL', e && e.message); process.exit(1) });
