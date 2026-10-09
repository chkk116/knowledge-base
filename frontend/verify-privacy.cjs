const { chromium } = require('playwright-core');
const BASE = process.env.KB_BASE || 'http://localhost:8080'   // 站点地址不入库，用环境变量传入

(async () => {
  const b = await chromium.launch({ headless: true, channel: 'chrome' });
  const ctx = await b.newContext({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true });
  const page = await ctx.newPage();
  await page.goto('' + BASE + '/m/privacy', { waitUntil: 'domcontentloaded', timeout: 40000 });
  await page.waitForTimeout(8000);
  const t = (await page.evaluate(() => document.body.innerText)).replace(/\s+/g, ' ');
  console.log('运营主体行  :', (t.match(/运营主体：\S+/) || ['(未找到)'])[0]);
  console.log('联系邮箱行  :', (t.match(/联系邮箱：\S+/) || ['(未找到)'])[0]);
  console.log('App 端备案号:', /备案号|湘ICP|湘 ICP/.test(t) ? '❌ 仍有' : '✓ 已移除');
  console.log('真名周顺    :', /周顺/.test(t) ? '❌ 仍有' : '✓ 已移除');
  await b.close();
})().catch(e => { console.error('FAIL', e && e.message); process.exit(1) });
