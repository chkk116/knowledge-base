const { chromium } = require('playwright-core');
const BASE = process.env.KB_BASE || 'http://localhost:8080'   // 站点地址不入库，用环境变量传入

(async () => {
  const b = await chromium.launch({ headless: true, channel: 'chrome' });
  for (const [label, url, w] of [['Web', '' + BASE + '/disclaimer', 1280], ['App(网页)', '' + BASE + '/m/disclaimer', 390]]) {
    const ctx = await b.newContext({ viewport: { width: w, height: 844 } });
    const page = await ctx.newPage();
    const errs = [];
    page.on('pageerror', e => errs.push(e.message.slice(0, 120)));
    await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 40000 });
    await page.waitForTimeout(6000);
    const t = (await page.evaluate(() => document.body.innerText)).replace(/\s+/g, ' ');
    console.log(`[${label}] ${page.url()}`);
    console.log('  标题:', /免责声明/.test(t) ? '✓ 有' : '✗ 无');
    console.log('  含 AI 免责:', /AI 生成的内容/.test(t) ? '✓' : '✗');
    console.log('  含邮箱:', /zxc1275981466@qq.com/.test(t) ? '✓' : '✗');
    console.log('  含运营主体:', /本站运营者/.test(t) ? '✓' : '✗');
    console.log('  段落数(含 h3):', (t.match(/(一|二|三|四|五|六|七|八|九|十)、/g) || []).length);
    if (errs.length) console.log('  ⚠️', errs.slice(0, 2).join(' | '));
    await ctx.close();
  }
  await b.close();
})().catch(e => { console.error('FAIL', e && e.message); process.exit(1) });
