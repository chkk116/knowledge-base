// 实测移动端 Agent：能否「自己写文档并存进知识库」
const BASE = process.env.KB_BASE || 'http://localhost:8080'   // 站点地址不入库，用环境变量传入

const { chromium } = require('playwright-core');
const token = process.env.KB_TOKEN;
if (!token) { console.error('缺 KB_TOKEN'); process.exit(1); }

(async () => {
  const browser = await chromium.launch({ headless: true, channel: 'chrome' });
  const ctx = await browser.newContext({ viewport: { width: 390, height: 844 } });
  const page = await ctx.newPage();
  const logs = [];
  page.on('pageerror', (e) => logs.push('pageerror: ' + e.message));
  await page.addInitScript((t) => { try { localStorage.setItem('kb_token', t) } catch (e) {} }, token);

  await page.goto('' + BASE + '/m/ai', { waitUntil: 'domcontentloaded', timeout: 30000 });
  await page.waitForTimeout(3500);
  console.log('URL:', page.url());

  // 切到 Agent 模式
  const agentBtn = await page.$('text=Agent');
  if (!agentBtn) { console.log('未找到 Agent 按钮'); console.log((await page.evaluate(() => document.body.innerText)).replace(/\s+/g, ' ').slice(0, 200)); await browser.close(); return; }
  await agentBtn.click();
  await page.waitForTimeout(800);

  // 输入任务
  const ta = await page.$('textarea');
  if (!ta) { console.log('未找到输入框'); await browser.close(); return; }
  await ta.fill('帮我写一篇关于「消防演练注意事项」的短文档，保存进知识库');
  await page.waitForTimeout(500);
  const sendBtn = await page.$('.aic-send') || await page.$('text=发送');
  if (sendBtn) await sendBtn.click();
  else await ta.press('Enter');
  console.log('已发送任务，等待 Agent 执行…');

  for (let i = 0; i < 20; i++) {
    await page.waitForTimeout(5000);
    const txt = (await page.evaluate(() => document.body.innerText)).replace(/\s+/g, ' ');
    const hit = /新建文档|已新建|保存文档|update_doc|create_doc|list_my_docs/.test(txt);
    console.log('[' + (i + 1) * 5 + 's]' + (hit ? ' ★' : '') + ' ' + txt.slice(0, 220));
    if (/已提交审核|已存为草稿|新建失败|任务未在/.test(txt)) break;   // 对齐 createDoc hook 实际返回文案
  }
  if (logs.length) console.log('错误:', logs.slice(0, 4));
  await browser.close();
})().catch((e) => { console.error('FAIL', e && e.message); process.exit(1); });
