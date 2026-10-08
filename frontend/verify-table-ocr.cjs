// 实测拍照建文档的「本地识别」：IP/MAC 表格图 → 是否还原出 Markdown 表格
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

  await page.goto('' + BASE + '/m/capture', { waitUntil: 'domcontentloaded', timeout: 30000 });
  await page.waitForTimeout(3000);

  const input = await page.$('input[type=file]');
  if (!input) { console.log('未找到上传入口'); await browser.close(); return; }
  await input.setInputFiles('C:/chkk/知识库/knowledge-base/outputs/ipmac-test.png');
  await page.waitForTimeout(2500);

  const btn = await page.$('text=开始识别');
  if (!btn) { console.log('未找到识别按钮'); await browser.close(); return; }
  console.log('已点击「开始识别」…');
  await btn.click();

  let done = false;
  for (let i = 0; i < 16; i++) {
    await page.waitForTimeout(5000);
    const txt = (await page.evaluate(() => document.body.innerText)).replace(/\s+/g, ' ');
    if (/确认内容|没识别到|识别失败/.test(txt)) {
      console.log('[' + (i + 1) * 5 + 's] 完成：' + txt.slice(0, 200));
      done = true;
      break;
    }
  }
  if (!done) console.log('未在 80s 内完成');

  const area = await page.evaluate(() => {
    const t = document.querySelector('textarea');
    return t ? t.value : '';
  });
  if (area) {
    console.log('---- 识别结果（textarea）----');
    console.log(area.slice(0, 600));
    console.log('是否 Markdown 表格:', /^\|.*\|$/m.test(area) ? '是 ✓' : '否');
  } else {
    console.log('未取到识别结果文本');
  }
  if (logs.length) console.log('错误:', logs.slice(0, 4));
  await browser.close();
})().catch((e) => { console.error('FAIL', e && e.message); process.exit(1); });
