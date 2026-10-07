// 实测 web 端「文件中心」上传（注入真实 token，走真实 UI）
// 用法：KB_BASE=https://your-site node verify-upload.cjs  （站点地址不入库，用环境变量传入）
const { chromium } = require('playwright-core');
const token = process.env.KB_TOKEN;
if (!token) { console.error('缺 KB_TOKEN'); process.exit(1); }

(async () => {
  const browser = await chromium.launch({ headless: true, channel: 'chrome' });
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await ctx.newPage();
  const logs = [];
  page.on('console', (m) => logs.push(m.type() + ': ' + m.text()));
  page.on('pageerror', (e) => logs.push('pageerror: ' + e.message));
  const apiHits = [];
  page.on('response', (r) => { if (r.url().includes('/api/files')) apiHits.push(r.status() + ' ' + r.request().method() + ' ' + r.url().replace('process.env.KB_BASE || 'http://localhost:8080'', '')); });

  await page.addInitScript((t) => { try { localStorage.setItem('kb_token', t) } catch (e) {} }, token);

  await page.goto('process.env.KB_BASE || 'http://localhost:8080'/files', { waitUntil: 'domcontentloaded', timeout: 30000 });
  await page.waitForTimeout(3500);
  console.log('URL:', page.url());
  console.log('页面文本:', (await page.evaluate(() => document.body.innerText)).replace(/\s+/g, ' ').slice(0, 260));

  const input = await page.$('input[type=file]');
  console.log('文件输入框存在:', !!input);
  if (input) {
    await input.setInputFiles('C:/chkk/知识库/knowledge-base/frontend/public/favicon.png');
    await page.waitForTimeout(6000);
    console.log('上传后文本:', (await page.evaluate(() => document.body.innerText)).replace(/\s+/g, ' ').slice(0, 400));
  }
  console.log('files 接口调用:', apiHits);
  const errs = logs.filter((l) => l.startsWith('error') || l.startsWith('pageerror')).slice(0, 8);
  if (errs.length) console.log('错误日志:', errs);
  await browser.close();
})().catch((e) => { console.error('FAIL', e && e.message); process.exit(1); });
