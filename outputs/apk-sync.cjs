// APK 资产同步（通用）：dist-vNNN -> android/app/src/main/assets/public
// 用法：NODE_OPTIONS='' node outputs/apk-sync.cjs vNNN
// 铁律：①mobile.html 复制为 index.html（App 离线入口）②剔除 .gz/.br（mergeReleaseAssets 报重复）
//      ③删后 existsSync 校验 ④md5 校验（源 dist vs 目标 assets，跳过 .gz/.br 与 index.html 预期差异）
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const ROOT = path.resolve(__dirname, '..');
const ver = String(process.argv[2] || '').replace(/^dist-/, '');
if (!ver) { console.error('用法: node outputs/apk-sync.cjs <vNNN>'); process.exit(1); }

const SRC = path.join(ROOT, 'frontend', 'dist-' + ver);
const DST = path.join(ROOT, 'mobile', 'android', 'app', 'src', 'main', 'assets', 'public');

const md5 = (f) => crypto.createHash('md5').update(fs.readFileSync(f)).digest('hex');

function rmrf(p) {
  if (!fs.existsSync(p)) return;
  fs.readdirSync(p, { withFileTypes: true }).forEach((e) => {
    const full = path.join(p, e.name);
    if (e.isDirectory()) rmrf(full);
    else fs.rmSync(full); // 不用 force，删失败会抛错 fail-closed
  });
  fs.rmdirSync(p);
}

if (!fs.existsSync(path.join(SRC, 'index.html'))) { console.error('[FAIL] 源 ' + SRC + ' 缺 index.html'); process.exit(1); }

console.log('== 1. 清空目标 ==');
rmrf(DST);
if (fs.existsSync(DST)) { console.error('[FAIL] 目标未清空'); process.exit(1); }

console.log('== 2. 复制 ==');
fs.mkdirSync(DST, { recursive: true });
let n = 0;
(function cpDir(s, d) {
  fs.readdirSync(s, { withFileTypes: true }).forEach((e) => {
    const sf = path.join(s, e.name), df = path.join(d, e.name);
    if (e.isDirectory()) { fs.mkdirSync(df); cpDir(sf, df); }
    else { fs.copyFileSync(sf, df); n++; }
  });
})(SRC, DST);

console.log('== 3. mobile.html -> index.html ==');
fs.copyFileSync(path.join(DST, 'mobile.html'), path.join(DST, 'index.html'));

console.log('== 4. 剔除 .gz/.br ==');
let strip = 0;
(function stripGz(p) {
  fs.readdirSync(p, { withFileTypes: true }).forEach((e) => {
    const f = path.join(p, e.name);
    if (e.isDirectory()) return stripGz(f);
    if (/\.(gz|br)$/.test(e.name)) { fs.rmSync(f); strip++; }
  });
})(DST);

console.log('== 5. 校验 ==');
let bad = 0, checked = 0;
(function verify(s, d) {
  fs.readdirSync(s, { withFileTypes: true }).forEach((e) => {
    const sf = path.join(s, e.name), df = path.join(d, e.name);
    if (e.isDirectory()) return verify(sf, df);
    if (/\.(gz|br)$/.test(e.name)) return;      // 源里的压缩文件已剔除
    if (e.name === 'index.html') {
      // 目标 index.html 已被源 mobile.html 覆盖（App 入口 = 移动版），
      // 因此拿它跟**源 mobile.html** 比对；源 index.html（桌面入口）不参与校验。
      if (md5(path.join(d, 'index.html')) !== md5(path.join(s, 'mobile.html'))) { bad++; console.error('index.html 覆盖失败'); }
      checked++; return;
    }
    if (!fs.existsSync(df) || md5(sf) !== md5(df)) { bad++; console.error('MISMATCH: ' + e.name); }
    checked++;
  });
})(SRC, DST);

console.log(`复制 ${n} 个；剔除 ${strip} 个压缩文件；md5 校验 ${checked} 个；不一致 ${bad}`);
if (bad > 0) { console.error('[FAIL] md5 校验失败'); process.exit(1); }

// == 6. 接口域名校验（fail-closed）==
// ⚠️ 血泪铁律：APK 内置前端的接口根地址必须是线上绝对域名，不能是 localhost。
//   事故（2026-10-12）：构建缺 VITE_NATIVE_ORIGIN → runtimeBase=localhost:8080 → App 全站连不上服务器。
//   这里在同步后拦截，避免打出「装上也连不上」的 APK。
const NATIVE_HOST = process.env.KB_NATIVE_HOST || 'chkk116.cloud';
{
  const assetsDir = path.join(DST, 'assets');
  const rbFiles = fs.existsSync(assetsDir)
    ? fs.readdirSync(assetsDir).filter((f) => /^runtimeBase-.*\.js$/.test(f))
    : [];
  if (!rbFiles.length) { console.error('[FAIL] 目标 assets 里找不到 runtimeBase-*.js'); process.exit(1); }
  let originBad = false;
  for (const f of rbFiles) {
    const src = fs.readFileSync(path.join(assetsDir, f), 'utf8');
    if (/localhost|127\.0\.0\.1/.test(src) || !src.includes(NATIVE_HOST)) {
      originBad = true;
      console.error('[FAIL] ' + f + ' 内置接口域名异常（未含 ' + NATIVE_HOST + '，或含 localhost/127.0.0.1）');
    }
  }
  if (originBad) {
    console.error('       多半是构建时缺 VITE_NATIVE_ORIGIN（frontend/.env.production.local）。已中止。');
    process.exit(1);
  }
  console.log('== 6. 接口域名 ' + NATIVE_HOST + ' (runtimeBase ✓) ==');
}
console.log('[OK] 同步完成: ' + DST);
