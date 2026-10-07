// 验证：连不上服务器时，移动端是否还会弹「服务器异常」/「页面出现问题」
// 用法：KB_BASE=https://your-site node verify-offline.cjs  （站点地址不入库，用环境变量传入）
// 手法：拦截所有 /api 请求并 abort（等价于网络故障：无 HTTP 状态，navigator.onLine 仍为 true）
const { chromium } = require('playwright-core');

(async () => {
  const browser = await chromium.launch({ headless: true, channel: 'chrome' });
  const ctx = await browser.newContext({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2 });
  const page = await ctx.newPage();
  const logs = [];
  page.on('console', (m) => logs.push(m.type() + ': ' + m.text()));

  // 模拟「连不上服务器」：所有 API 请求直接失败（无响应）
  await page.route('**/api/**', (route) => route.abort('failed'));

  await page.goto('(process.env.KB_BASE || 'http://localhost:8080') + '/m/home'', { waitUntil: 'domcontentloaded', timeout: 30000 });
  await page.waitForTimeout(9000);

  const text = await page.evaluate(() => document.body.innerText || '');
  const flat = text.replace(/\s+/g, ' ').trim();
  console.log('URL:', page.url());
  console.log('含「服务器异常」:', text.includes('服务器异常'));
  console.log('含「服务暂时不可用」:', text.includes('服务暂时不可用'));
  console.log('含「页面出现问题」:', text.includes('页面出现问题'));
  console.log('BODY前 240 字:', flat.slice(0, 240));
  const errLogs = logs.filter((l) => l.startsWith('error')).slice(0, 5);
  if (errLogs.length) console.log('控制台错误:', errLogs);

  await browser.close();
})().catch((e) => { console.error('FAIL', e && e.message); process.exit(1); });
