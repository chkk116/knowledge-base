// 验证 Web 端：① 侧边栏「我的文件」图标 ② AI 页的 Agent 模式
const BASE = process.env.KB_BASE || 'http://localhost:8080'   // 站点地址不入库，用环境变量传入

const { chromium } = require('playwright-core');
const token = process.env.KB_TOKEN;
if (!token) { console.error('缺 KB_TOKEN'); process.exit(1); }

(async () => {
  const browser = await chromium.launch({ headless: true, channel: 'chrome' });
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await ctx.newPage();
  const logs = [];
  page.on('pageerror', (e) => logs.push('pageerror: ' + e.message));
  await page.addInitScript((t) => { try { localStorage.setItem('kb_token', t) } catch (e) {} }, token);

  // ① 我的文件页面 + 导航图标
  await page.goto('' + BASE + '/files', { waitUntil: 'domcontentloaded', timeout: 30000 });
  await page.waitForTimeout(4000);
  const fileInfo = await page.evaluate(() => {
    // 找菜单里指向 /files 的项，看它内部有没有 svg 图标
    const items = [...document.querySelectorAll('.el-menu-item, [class*=menu] a, a[href="/files"]')];
    let hit = null;
    for (const it of items) {
      const txt = (it.innerText || '').trim();
      if (txt.includes('我的文件')) { hit = it; break }
    }
    return {
      hasMenuSvg: hit ? !!hit.querySelector('svg') : null,
      bodyHasUpload: !!document.querySelector('.fc-drop'),
      pageText: document.body.innerText.replace(/\s+/g, ' ').slice(0, 120)
    };
  });
  console.log('① 我的文件页:', JSON.stringify(fileInfo));

  // ② AI 页 Agent 模式
  await page.goto('' + BASE + '/ai/chat', { waitUntil: 'domcontentloaded', timeout: 30000 });
  await page.waitForTimeout(4000);
  const agentBtn = await page.$('text=Agent');
  console.log('② Agent 按钮存在:', !!agentBtn);
  if (agentBtn) {
    await agentBtn.click();
    await page.waitForTimeout(1200);
    const after = (await page.evaluate(() => document.body.innerText)).replace(/\s+/g, ' ');
    console.log('   切换后页面文案:', after.slice(0, 220));
    console.log('   出现「自主检索/写文档」引导:', /自主检索|写文档|我是 Agent/.test(after) ? '是 ✓' : '否');
  }
  if (logs.length) console.log('错误:', logs.slice(0, 4));
  await browser.close();
})().catch((e) => { console.error('FAIL', e && e.message); process.exit(1); });
