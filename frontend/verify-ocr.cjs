// 实测移动端「拍照建文档」的本地 OCR（Tesseract）能否走完
const BASE = process.env.KB_BASE || 'http://localhost:8080'   // 站点地址不入库，用环境变量传入

const { chromium } = require('playwright-core');
const token = process.env.KB_TOKEN;
if (!token) { console.error('缺 KB_TOKEN'); process.exit(1); }

(async () => {
  const browser = await chromium.launch({ headless: true, channel: 'chrome' });
  const ctx = await browser.newContext({ viewport: { width: 390, height: 844 } });
  const page = await ctx.newPage();
  const logs = [];
  page.on('console', (m) => logs.push(m.type() + ': ' + m.text()));
  page.on('pageerror', (e) => logs.push('pageerror: ' + e.message));
  page.on('requestfailed', (r) => { if (r.url().includes('tesseract')) logs.push('reqfail: ' + r.url() + ' ' + (r.failure() || {}).errorText) });
  await page.addInitScript((t) => { try { localStorage.setItem('kb_token', t) } catch (e) {} }, token);

  await page.goto('' + BASE + '/m/capture', { waitUntil: 'domcontentloaded', timeout: 30000 });
  await page.waitForTimeout(3000);
  console.log('URL:', page.url());

  const input = await page.$('input[type=file]');
  console.log('file input:', !!input);
  if (!input) { console.log('页面文本:', (await page.evaluate(() => document.body.innerText)).replace(/\s+/g, ' ').slice(0, 200)); await browser.close(); return; }

  await input.setInputFiles('C:/chkk/知识库/knowledge-base/frontend/public/favicon.png');
  await page.waitForTimeout(2500);
  console.log('上传后文本:', (await page.evaluate(() => document.body.innerText)).replace(/\s+/g, ' ').slice(0, 240));

  const btn = await page.$('text=开始识别');
  console.log('识别按钮存在:', !!btn);
  if (btn) {
    await btn.click();
    for (let i = 0; i < 16; i++) {
      await page.waitForTimeout(5000);
      const txt = (await page.evaluate(() => document.body.innerText)).replace(/\s+/g, ' ');
      console.log(`[${(i + 1) * 5}s] ${txt.slice(0, 130)}`);
      if (/识别完成|确认内容|识别失败|错误/.test(txt)) break;
    }
  }
  const errs = logs.filter((l) => l.startsWith('error') || l.startsWith('pageerror') || l.startsWith('reqfail')).slice(0, 8);
  if (errs.length) console.log('错误日志:', errs);
  await browser.close();
})().catch((e) => { console.error('FAIL', e && e.message); process.exit(1); });
