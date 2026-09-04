// 同步 desktop/web -> web-app，排除 .gz/.br 预压缩文件
// （AGP 会把 a.js 与 a.js.gz 判为 Duplicate resources）
const fs = require('fs')
const path = require('path')
const SRC = path.join(__dirname, '..', 'desktop', 'web')
const DST = path.join(__dirname, 'web-app')
function rmrf(p) { if (fs.existsSync(p)) fs.rmSync(p, { recursive: true, force: true }) }
function copy(src, dst) {
  for (const name of fs.readdirSync(src)) {
    if (name.endsWith('.gz') || name.endsWith('.br')) continue
    const s = path.join(src, name), d = path.join(dst, name)
    if (fs.statSync(s).isDirectory()) { fs.mkdirSync(d, { recursive: true }); copy(s, d) }
    else fs.copyFileSync(s, d)
  }
}
rmrf(DST); fs.mkdirSync(DST, { recursive: true })
copy(SRC, DST)
let n = 0
function count(p) { for (const x of fs.readdirSync(p)) { const f = path.join(p, x); fs.statSync(f).isDirectory() ? count(f) : n++ } }
count(DST)
console.log('web-app synced, files: ' + n)
