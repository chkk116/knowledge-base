const path = require('path');
const { chromium } = require('playwright-core');
(async () => {
  const b = await chromium.launch({ headless: true, channel: 'chrome' });
  const ctx = await b.newContext({ viewport: { width: 900, height: 700 }, deviceScaleFactor: 2 });
  const p = await ctx.newPage();
  await p.setContent(`<body style="font-family:Consolas,monospace;padding:24px;background:#fff">
  <h3>设备清单（IP / MAC）</h3>
  <table border="1" cellspacing="0" cellpadding="8" style="border-collapse:collapse;font-size:16px">
    <tr style="background:#eee"><th>序号</th><th>设备名</th><th>IP 地址</th><th>MAC 地址</th></tr>
    <tr><td>1</td><td>core-sw-01</td><td>198.51.100.1</td><td>00:1A:2B:3C:4D:5E</td></tr>
    <tr><td>2</td><td>core-sw-02</td><td>198.51.100.2</td><td>00:1A:2B:3C:4D:5F</td></tr>
    <tr><td>3</td><td>ap-floor1</td><td>198.51.100.11</td><td>A4:5E:60:12:34:56</td></tr>
    <tr><td>4</td><td>srv-db-01</td><td>203.0.113.21</td><td>B8:27:EB:AA:BB:CC</td></tr>
  </table></body>`);
  await p.screenshot({ path: 'path.join(__dirname, '../outputs/ipmac-test.png')', fullPage: true });
  await b.close();
  console.log('已生成 ipmac-test.png');
})();
