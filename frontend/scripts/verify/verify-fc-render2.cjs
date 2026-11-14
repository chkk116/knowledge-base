const { chromium } = require('playwright-core');
const BASE = process.env.KB_BASE || 'http://localhost:8080'   // 站点地址不入库，用环境变量传入

const token = process.env.KB_TOKEN;
(async () => {
  const b = await chromium.launch({ headless: true, channel: 'chrome' });
  const ctx = await b.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await ctx.newPage();
  const logs = [];
  page.on('pageerror', (e) => logs.push('pageerror: ' + e.message));
  if (!token) { console.error('缺少 KB_TOKEN 环境变量，脚本无法以登录态运行'); process.exit(1) }
  await page.addInitScript((t) => { try { localStorage.setItem('kb_token', t) } catch (e) {} }, token);
  await page.goto('' + BASE + '/doc-shares/2?doc=8', { waitUntil: 'domcontentloaded', timeout: 40000 });
  await page.waitForTimeout(14000);
  // 找文档条目并点击（优先点带 href/role 的可点元素）
  const clicked = await page.evaluate(() => {
    const all = [...document.querySelectorAll('*')];
    const t = all.find((e) => e.children.length === 0 && (e.textContent || '').trim() === '员工请假审批流程');
    if (!t) return 'no-title-el';
    let el = t;
    for (let i = 0; i < 6 && el; i++) {
      if (el.tagName === 'A' || el.getAttribute('role') === 'button' || String(el.className).includes('item') || el.className.includes('card') || el.className.includes('row')) break;
      el = el.parentElement;
    }
    (el || t).click();
    return 'clicked:' + (el ? el.tagName + '.' + String(el.className).slice(0, 40) : 'title');
  });
  console.log(clicked);
  await page.waitForTimeout(12000);
  const info = await page.evaluate(() => ({
    url: location.href,
    fcWrap: !!document.querySelector('.kb-fc-wrap'),
    fcNodes: document.querySelectorAll('.vue-flow__node').length,
    labels: [...document.querySelectorAll('.vue-flow__node')].slice(0, 8).map((n) => n.textContent.trim()),
    editor: !!document.querySelector('.ProseMirror, .tiptap')
  }));
  console.log('渲染:', JSON.stringify(info));
  if (logs.length) console.log('错误:', logs.slice(0, 3));
  await b.close();
})().catch((e) => { console.error('FAIL', e && e.message); process.exit(1) });
