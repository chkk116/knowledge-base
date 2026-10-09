const { chromium } = require('playwright-core');
const BASE = process.env.KB_BASE || 'http://localhost:8080'   // 站点地址不入库，用环境变量传入

(async () => {
  const b = await chromium.launch({ headless: true, channel: 'chrome' });
  const ctx = await b.newContext({ viewport: { width: 390, height: 844 } });
  const page = await ctx.newPage();
  // 全新上下文 = 没有 token，模拟「App 里跨域 iframe」的处境
  await page.goto('' + BASE + '/admin/users', { waitUntil: 'domcontentloaded', timeout: 40000 });
  await page.waitForTimeout(6000);
  console.log('无 token 打开 /admin/users →', page.url());
  const t = (await page.evaluate(() => document.body.innerText)).replace(/\s+/g, ' ').slice(0, 80);
  console.log('页面内容:', t);
  console.log('是否被弹到登录页:', /login/i.test(page.url()) ? '❌ 是（复现了 bug）' : '否');
  await b.close();
})().catch(e => { console.error('FAIL', e && e.message); process.exit(1) });
