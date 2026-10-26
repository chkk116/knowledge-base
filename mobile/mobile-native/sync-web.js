// 同步前端构建产物 → app/src/main/assets/web（WebViewAssetLoader 本地承载）。
// 用法：先在 frontend 执行 npm run build（含 SW 预缓存注入），再运行本脚本。
// 排除 .gz/.br 预压缩文件（与 mobile/sync-web.js 同规则，避免资源重复）。
const fs = require('fs')
const path = require('path')

const SRC = path.join(__dirname, '..', 'frontend', 'dist')
const DST = path.join(__dirname, 'app', 'src', 'main', 'assets', 'web')

if (!fs.existsSync(SRC)) {
  console.error('未找到前端构建产物：' + SRC)
  console.error('请先执行：cd ../frontend && npm run build')
  process.exit(1)
}

function rmrf(p) { if (fs.existsSync(p)) fs.rmSync(p, { recursive: true, force: true }) }
let n = 0
function copy(src, dst) {
  for (const name of fs.readdirSync(src)) {
    if (name.endsWith('.gz') || name.endsWith('.br')) continue
    const s = path.join(src, name), d = path.join(dst, name)
    if (fs.statSync(s).isDirectory()) { fs.mkdirSync(d, { recursive: true }); copy(s, d) }
    else { fs.copyFileSync(s, d); n++ }
  }
}

rmrf(DST); fs.mkdirSync(DST, { recursive: true })
copy(SRC, DST)
console.log('web assets synced, files: ' + n + ' → ' + DST)
