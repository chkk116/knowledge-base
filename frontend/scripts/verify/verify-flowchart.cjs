// 验证 Agent 生成流程图（共享文档画布）
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
  if (!token) { console.error('缺少 KB_TOKEN 环境变量，脚本无法以登录态运行'); process.exit(1) }
  await page.addInitScript((t) => { try { localStorage.setItem('kb_token', t) } catch (e) {} }, token);

  await page.goto('' + BASE + '/m/ai', { waitUntil: 'domcontentloaded', timeout: 30000 });
  await page.waitForTimeout(3500);
  const agentBtn = await page.$('text=Agent');
  if (agentBtn) await agentBtn.click();
  await page.waitForTimeout(800);

  const ta = await page.$('textarea');
  await ta.fill('帮我画一个「员工请假审批」的流程图，新建一个共享文档放进去');
  await page.waitForTimeout(400);
  const sendBtn = await page.$('.aic-send') || await page.$('text=发送');
  if (sendBtn) await sendBtn.click(); else await ta.press('Enter');
  console.log('已发送，等待 Agent 生成流程图…');

  for (let i = 0; i < 18; i++) {
    await page.waitForTimeout(5000);
    const txt = (await page.evaluate(() => document.body.innerText)).replace(/\s+/g, ' ');
    if (/流程图|生成失败|任务未在/.test(txt)) { console.log('[' + (i + 1) * 5 + 's] ' + txt.slice(0, 320)); }
    if (/已在共享文档|已新建共享文档|生成流程图失败|任务未在/.test(txt)) break;
  }
  if (logs.length) console.log('错误:', logs.slice(0, 3));
  await browser.close();
})().catch((e) => { console.error('FAIL', e && e.message); process.exit(1); });
