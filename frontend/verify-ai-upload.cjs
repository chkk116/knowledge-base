// 实测 web 端 AI 页（/ai/chat）的附件上传
// 用法：KB_BASE=https://your-site node verify-ai-upload.cjs  （站点地址不入库，用环境变量传入）
const { chromium } = require('playwright-core');
const fs = require('fs');
const token = process.env.KB_TOKEN;
if (!token) { console.error('缺 KB_TOKEN'); process.exit(1); }

(async () => {
  const browser = await chromium.launch({ headless: true, channel: 'chrome' });
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await ctx.newPage();
  const logs = [];
  page.on('console', (m) => { if (m.type() === 'error') logs.push(m.text()) });
  page.on('pageerror', (e) => logs.push('pageerror: ' + e.message));
  await page.addInitScript((t) => { try { localStorage.setItem('kb_token', t) } catch (e) {} }, token);

  const BASE = process.env.KB_BASE || 'http://localhost:8080'
  await page.goto(BASE + '/ai/chat', { waitUntil: 'domcontentloaded', timeout: 30000 });
  await page.waitForTimeout(4500);
  console.log('URL:', page.url());
  const bodyHead = (await page.evaluate(() => document.body.innerText)).replace(/\s+/g, ' ').slice(0, 160);
  console.log('页面文本:', bodyHead);

  const input = await page.$('input[type=file]');
  console.log('file input 存在:', !!input);
  if (input) {
    const p = 'C:/chkk/知识库/knowledge-base/outputs/kbtest.txt';
    fs.writeFileSync(p, '这是一段测试文本，用于验证 AI 页附件上传。\n第二行：报销流程说明与注意事项。');
    await input.setInputFiles(p);
    await page.waitForTimeout(3000);
    const chipText = await page.evaluate(() => { const el = document.querySelector('.ai-atts'); return el ? el.innerText : '' });
    console.log('附件 chip:', chipText ? '出现 ✓ → ' + chipText.replace(/\s+/g, ' ') : '未出现 ✗');
  }
  if (logs.length) console.log('错误:', logs.slice(0, 5));
  await browser.close();
})().catch((e) => { console.error('FAIL', e && e.message); process.exit(1); });
