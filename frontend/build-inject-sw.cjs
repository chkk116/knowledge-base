/**
 * 构建后处理：把移动端入口的完整 chunk 依赖图写入 Service Worker 的预缓存清单。
 *
 * 背景：
 *   Vite 产物里入口 JS 自带 __vite__mapDeps，列出了它所有（含懒加载）的资源。
 *   断网冷启动时 SW 必须已缓存这些文件，否则 HTML 打开了、JS 却 404 → 仍然白屏。
 *   这里把清单注入 sw.js 的 PRECACHE_ASSETS 常量，install 时一次性拉全。
 *
 * 用法：node build-inject-sw.cjs <distDir>
 */
const fs = require('fs')
const path = require('path')

const dist = process.argv[2] || 'dist'
const pkgVersion = require('./package.json').version || '0.0.0'
const root = path.resolve(dist)

if (!fs.existsSync(root)) { console.error('dist 不存在: ' + root); process.exit(1) }

// 1) 找移动端入口 JS
const mobileHtml = fs.readFileSync(path.join(root, 'mobile.html'), 'utf8')
const entryMatch = mobileHtml.match(/assets\/mobile-[A-Za-z0-9_-]+\.js/)
if (!entryMatch) { console.error('未找到移动端入口 JS'); process.exit(1) }
const entryJs = entryMatch[0]

// 2) 从 mobile.html 直接引用的资源
const fromHtml = new Set()
{
  const re = /(?:src|href)="(\/assets\/[^"]+|\/icons\/[^"]+|\/[^"]+\.png)"/g
  let m
  while ((m = re.exec(mobileHtml))) fromHtml.add(m[1])
}

// 3) 从入口 JS 的 __vite__mapDeps 抽全部依赖
const entrySrc = fs.readFileSync(path.join(root, entryJs), 'utf8')
const fromDeps = new Set()
{
  // 形如 d=(m.f||(m.f=["assets/a.js","assets/b.css",...]))
  const re = /m\.f\|\|\(m\.f=\[([^\]]+)\]/g
  let m
  while ((m = re.exec(entrySrc))) {
    for (const raw of m[1].split(',')) {
      const s = raw.trim().replace(/^["']|["']$/g, '')
      if (s && s.startsWith('assets/')) fromDeps.add('/' + s)
    }
  }
}

// 4) 汇总（只保留构建产物里真实存在的）
const all = new Set()
for (const u of [...fromHtml, ...fromDeps]) {
  const rel = u.replace(/^\//, '')
  if (fs.existsSync(path.join(root, rel))) all.add(u)
}
// 外壳 HTML 与 manifest
for (const u of ['/mobile.html', '/index.html', '/manifest.webmanifest']) all.add(u)

const list = [...all].sort()
console.log('预缓存清单：' + list.length + ' 个文件')
console.log('  （来自 html: ' + fromHtml.size + '，来自 mapDeps: ' + fromDeps.size + '）')

// 5) 注入 sw.js
const swPath = path.join(root, 'sw.js')
let sw = fs.readFileSync(swPath, 'utf8')
const marker = /const PRECACHE_ASSETS = \[[\s\S]*?\];/
if (!marker.test(sw)) { console.error('sw.js 中未找到 PRECACHE_ASSETS 占位'); process.exit(1) }
sw = sw.replace(marker, 'const PRECACHE_ASSETS = ' + JSON.stringify(list, null, 2) + ';')
  .replace("const VERSION = 'kb-mobile-v1'", "const VERSION = 'kb-mobile-v" + pkgVersion + "'")
fs.writeFileSync(swPath, sw, 'utf8')
console.log('已注入 ' + swPath + ' (' + fs.statSync(swPath).size + ' 字节)')
