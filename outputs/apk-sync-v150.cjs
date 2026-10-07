// APK 资产同步：dist-v150 -> android/app/src/main/assets/public
// 铁律：①mobile.html 复制为 index.html（App 离线入口）②剔除 .gz/.br（mergeReleaseAssets 报重复）
// ③删后 existsSync 校验 ④三方 md5 校验（源 dist vs 目标 assets）
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const ROOT = path.resolve(__dirname, '..');
const SRC = path.join(ROOT, 'frontend', 'dist-v150');
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

if (!fs.existsSync(path.join(SRC, 'index.html'))) {
  console.error('[FAIL] 源 dist-v150 缺 index.html'); process.exit(1);
}

console.log('== 1. 清空目标 ==');
rmrf(DST);
if (fs.existsSync(DST)) { console.error('[FAIL] 目标未清空'); process.exit(1); }

console.log('== 2. 复制 ==');
fs.mkdirSync(DST, { recursive: true });
let n = 0;
function cpDir(s, d) {
  fs.readdirSync(s, { withFileTypes: true }).forEach((e) => {
    const sf = path.join(s, e.name), df = path.join(d, e.name);
    if (e.isDirectory()) { fs.mkdirSync(df); cpDir(sf, df); }
    else { fs.copyFileSync(sf, df); n++; }
  });
}
cpDir(SRC, DST);

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
if (strip > 0) console.log(`已剔除 ${strip} 个 .gz/.br`);
// 抽样全量校验 md5
let bad = 0, checked = 0;
(function verify(s, d) {
  fs.readdirSync(s, { withFileTypes: true }).forEach((e) => {
    const sf = path.join(s, e.name), df = path.join(d, e.name);
    if (e.isDirectory()) return verify(sf, df);
    if (/\.(gz|br)$/.test(e.name)) return;      // 源里的压缩文件已剔除，跳过
    if (e.name === 'index.html') {              // 已被 mobile.html 覆盖，单独比对
      if (md5(sf) !== md5(path.join(d, 'mobile.html'))) { bad++; console.error('index.html 覆盖失败'); }
      checked++;
      return;
    }
    if (!fs.existsSync(df) || md5(sf) !== md5(df)) { bad++; console.error('MISMATCH: ' + e.name); }
    checked++;
  });
})(SRC, DST);
console.log(`文件数: ${n}, md5 校验: ${checked}, 不一致: ${bad}`);
if (bad > 0) { console.error('[FAIL] md5 校验失败'); process.exit(1); }
console.log('[OK] 同步完成');
