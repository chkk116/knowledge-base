// 验证 Agent 的 ask_user：写文档时是否会先问用户风格（并验证弹层可交互）
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
  const agentBtn = await page.$('text=Agent');
  if (agentBtn) await agentBtn.click();
  await page.waitForTimeout(800);

  const ta = await page.$('textarea');
  await ta.fill('帮我写一篇关于团队周会的文档，存进知识库');
  await page.waitForTimeout(400);
  const sendBtn = await page.$('.aic-send') || await page.$('text=发送');
  if (sendBtn) await sendBtn.click(); else await ta.press('Enter');
  console.log('已发送，观察是否弹出询问…');

  let asked = false;
  for (let i = 0; i < 10; i++) {
    await page.waitForTimeout(4000);
    const ask = await page.$('.aic-ask');
    const txt = (await page.evaluate(() => document.body.innerText)).replace(/\s+/g, ' ');
    if (ask) {
      asked = true;
      const q = await page.evaluate(() => { const e = document.querySelector('.aic-ask-q'); return e ? e.textContent : '' });
      const opts = await page.evaluate(() => [...document.querySelectorAll('.aic-ask-opt')].map((b) => b.textContent));
      console.log('★ 弹出询问:', q);
      console.log('  选项:', JSON.stringify(opts));
      if (opts.length) { await page.click('.aic-ask-opt'); console.log('  已点选第一个选项'); }
      break;
    }
    console.log('[' + (i + 1) * 4 + 's] ' + txt.slice(0, 150));
    if (/已完成|已提交审核|已存为草稿/.test(txt)) break;
  }
  if (!asked) console.log('（本次未触发询问，属正常——模型可能自行决定了）');

  // 再看步骤是否累积展示
  await page.waitForTimeout(20000);
  const steps = await page.evaluate(() => [...document.querySelectorAll('.aic-step-row')].map((e) => e.textContent.trim()));
  console.log('步骤列表条数:', steps.length, JSON.stringify(steps.slice(0, 6)));
  if (logs.length) console.log('错误:', logs.slice(0, 4));
  await browser.close();
})().catch((e) => { console.error('FAIL', e && e.message); process.exit(1); });
