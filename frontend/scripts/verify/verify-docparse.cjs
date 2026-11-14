// 端到端验证：AI 页上传 PDF → 本地解析 → 附件 chip 出现文本
const BASE = process.env.KB_BASE || 'http://localhost:8080'   // 站点地址不入库，用环境变量传入

const { chromium } = require('playwright-core');
const fs = require('fs');
const token = process.env.KB_TOKEN;
if (!token) { console.error('缺 KB_TOKEN'); process.exit(1); }

(async () => {
  const browser = await chromium.launch({ headless: true, channel: 'chrome' });
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });

  // 1. 生成一个有效 PDF（含中文文本）
  const pdfPath = 'C:/chkk/知识库/knowledge-base/outputs/test-parse.pdf';
  const p2 = await ctx.newPage();
  await p2.setContent('<h1>报销流程说明</h1><p>1. 填写报销单；2. 附上发票；3. 提交主管审批。这是用于验证 PDF 解析的测试文本。</p>');
  await p2.pdf({ path: pdfPath, format: 'A4' });
  await p2.close();
  console.log('PDF 已生成:', fs.statSync(pdfPath).size, 'bytes');

  // 2. 打开 AI 页并上传
  const page = await ctx.newPage();
  const logs = [];
  page.on('console', (m) => logs.push(m.type() + ': ' + m.text()));
  page.on('pageerror', (e) => logs.push('pageerror: ' + e.message));
  if (!token) { console.error('缺少 KB_TOKEN 环境变量，脚本无法以登录态运行'); process.exit(1) }
  await page.addInitScript((t) => { try { localStorage.setItem('kb_token', t) } catch (e) {} }, token);
  await page.goto('' + BASE + '/ai/chat', { waitUntil: 'domcontentloaded', timeout: 30000 });
  await page.waitForTimeout(4000);

  const input = await page.$('input[type=file]');
  console.log('file input:', !!input);
  if (!input) { console.log('页面文本:', (await page.evaluate(() => document.body.innerText)).replace(/\s+/g, ' ').slice(0, 160)); await browser.close(); return; }

  await input.setInputFiles(pdfPath);
  await page.waitForTimeout(28000);

  const chip = await page.evaluate(() => { const e = document.querySelector('.ai-atts'); return e ? e.innerText.replace(/\s+/g, ' ') : '' });
  console.log('附件 chip:', chip || '(无)');
  const errs = logs.filter((l) => l.startsWith('error') || l.startsWith('pageerror')).slice(0, 6);
  if (errs.length) console.log('错误:', errs);
  await browser.close();
})().catch((e) => { console.error('FAIL', e && e.message); process.exit(1); });
