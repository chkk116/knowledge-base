/**
 * 前端热更包打包脚本（配合 @capgo/capacitor-updater）
 *
 * 用法：node outputs/build-app-bundle.cjs v190
 *       （v190 = frontend/dist-v190，即前端构建产物目录后缀）
 *
 * 产物：outputs/app-bundles/kb-app-<yyyyMMddHHmm>.zip
 *   注意 zip 的**根目录**必须直接是 web 根（index.html 在根上），
 *   Capgo 解压后就是这么当 webDir 用的，多套一层目录会白屏。
 *
 * 打包完把最后打印的 JSON 写进后台 SysSetting 的 `app_bundle` 即可。
 * 上线后：用户下次打开 App 会自动下载并切换，**不用重新装 APK**。
 *
 * ⚠️⚠️ 血泪铁律：zip 里的 `index.html` 必须是**移动端入口**（mobile.html），
 *   绝不能是 dist 根上那个桌面入口 index.html。
 *
 *   事故（2026-10-11）：本脚本早期版本直接把 dist-vNN 整个打包，zip 根上的
 *   index.html 是桌面版入口（引 assets/main-*.js）。APK 那边有 apk-sync.cjs 做
 *   「mobile.html → index.html」替换所以没事，热更包这边漏了 → App 装上热更包后
 *   冷启动加载的是**桌面版 SPA**，整个 App 变网页版；Capgo 不回滚，只能装新 APK 恢复。
 *
 *   现在：① 打包时把 mobile.html 写成 zip 里的 index.html
 *         ② 剔除 .gz/.br（与 apk-sync 一致；也避免残留旧的 index.html.br）
 *         ③ **打包后强制复检** zip 内 index.html 引用的是 mobile-*.js 而非 main-*.js
 *            —— 复检不过直接删包 + 退出 1，绝不产出坏包。
 *
 * 恢复被坏包锁死的设备：发一个 versionCode 更高的 APK，用户覆盖安装即可
 *   （capacitor.config.json 的 resetWhenUpdate=true → 原生版本号变化时
 *    Capgo 的 autoReset 会清掉已下载 bundle，回落 APK 内置资源）。
 */
const fs = require('fs')
const path = require('path')
const crypto = require('crypto')
const { spawnSync } = require('child_process')

const ver = String(process.argv[2] || '').replace(/^dist-/, '')
if (!ver) {
  console.error('用法: node outputs/build-app-bundle.cjs <dist后缀>   例：node outputs/build-app-bundle.cjs v190')
  process.exit(1)
}

const distDir = path.resolve(__dirname, '../frontend/dist-' + ver)
const outDir = path.resolve(__dirname, 'app-bundles')

if (!fs.existsSync(distDir)) {
  console.error('找不到构建目录：' + distDir + '\n（先跑前端构建生成 dist-' + ver + '）')
  process.exit(1)
}

// ---- 1. 入口校验（fail-closed）----
const mobileHtmlPath = path.join(distDir, 'mobile.html')
if (!fs.existsSync(mobileHtmlPath)) {
  console.error('[FAIL] 构建目录里没有 mobile.html（移动端入口），打包出来 App 会白屏：' + distDir)
  process.exit(1)
}
if (!fs.existsSync(path.join(distDir, 'index.html'))) {
  console.error('[FAIL] 构建目录里没有 index.html —— 构建产物不完整，已中止：' + distDir)
  process.exit(1)
}

const mobileHtml = fs.readFileSync(mobileHtmlPath, 'utf8')
const entryChunk = (mobileHtml.match(/<script[^>]+src="[^"]*assets\/([A-Za-z0-9_.-]+\.js)"/) || [])[1] || ''
if (!/^mobile-/.test(entryChunk)) {
  console.error('[FAIL] mobile.html 的入口不是 mobile-*.js（实际：' + (entryChunk || '未找到') + '）—— 构建产物异常，已中止')
  process.exit(1)
}

// ---- 1b. 接口域名校验（fail-closed）----
// ⚠️⚠️ 血泪铁律：热更包内置的接口根地址必须是**线上绝对域名**，绝不能是 localhost。
//   事故（2026-10-12）：v212 构建时缺 VITE_NATIVE_ORIGIN → runtimeBase 回退 http://localhost:8080，
//   打成热更包下发后 App 全站接口 ERR_CONNECTION_REFUSED（表现为「发现」空白、管理页「连不上服务器」），
//   而且因为连不上后端 → 拿不到后续修好的热更包 → **无法自愈，只能装新 APK 覆盖**。
//   这里在打包前拦截，杜绝再产出这种坏包。
const NATIVE_HOST = process.env.KB_NATIVE_HOST || '你的域名'
{
  const assetsDir = path.join(distDir, 'assets')
  const rbFiles = fs.existsSync(assetsDir)
    ? fs.readdirSync(assetsDir).filter((f) => /^runtimeBase-.*\.js$/.test(f))
    : []
  if (!rbFiles.length) {
    console.error('[FAIL] dist 里找不到 assets/runtimeBase-*.js —— 构建产物异常，已中止：' + distDir)
    process.exit(1)
  }
  let originBad = false
  for (const f of rbFiles) {
    const src = fs.readFileSync(path.join(assetsDir, f), 'utf8')
    if (/localhost|127\.0\.0\.1/.test(src) || !src.includes(NATIVE_HOST)) {
      originBad = true
      console.error('[FAIL] ' + f + ' 内置接口域名异常（未含 ' + NATIVE_HOST + '，或含 localhost/127.0.0.1）')
    }
  }
  if (originBad) {
    console.error('       多半是构建时缺 VITE_NATIVE_ORIGIN（应写在 frontend/.env.production.local）。')
    console.error('       这种包下发会让 App 全站连不上服务器且无法自愈，已中止出包。')
    process.exit(1)
  }
  console.log('接口域名  ' + NATIVE_HOST + '（runtimeBase ✓）')
}

fs.mkdirSync(outDir, { recursive: true })

// 版本号用时间戳（精确到秒）：单调递增、肉眼可读，同分钟两次构建也不会重名
// （审查 r10-10：分钟精度下第二次构建会撞同一个 zipPath）
const d = new Date()
const p2 = (n) => String(n).padStart(2, '0')
const version = `${d.getFullYear()}${p2(d.getMonth() + 1)}${p2(d.getDate())}${p2(d.getHours())}${p2(d.getMinutes())}${p2(d.getSeconds())}`
const zipName = `kb-app-${version}.zip`
const zipPath = path.join(outDir, zipName)
if (fs.existsSync(zipPath)) fs.unlinkSync(zipPath)

// 用 archiver 打标准 zip。
// ⚠️ 不能用 PowerShell 的 Compress-Archive：它写出来的条目名是**反斜杠**
// （assets\xxx.js），Android 解压时会把它当成文件名的一部分，整包错乱。
const archiver = require('archiver')

// 过滤：①剔除预压缩副本（.gz/.br）②剔除桌面入口 index.html
// （zip 根上的 index.html 由下面 append(mobile.html) 提供）
const SKIP = /\.(gz|br)$/
function filterEntry(entry) {
  if (SKIP.test(entry.name)) return false
  if (entry.name === 'index.html') return false
  return entry
}

function buildZip() {
  return new Promise((resolve, reject) => {
    const output = fs.createWriteStream(zipPath)
    const archive = archiver('zip', { zlib: { level: 9 } })
    output.on('close', () => resolve(archive.pointer()))
    archive.on('warning', (e) => { if (e.code !== 'ENOENT') reject(e) })
    archive.on('error', reject)
    archive.pipe(output)
    // 第二个参数 false = 不套顶层目录，直接把内容放在 zip 根（Capgo 要求）
    archive.directory(distDir, false, filterEntry)
    // 移动端入口 → zip 根的 index.html（App 冷启动读的就是它）
    archive.append(fs.readFileSync(mobileHtmlPath), { name: 'index.html' })
    archive.finalize()
  })
}

/** 从 zip 里取某个条目的内容（系统 unzip；命令不存在时返回 {missing:true}，其余错误 fail-closed） */
function readZipEntry(name) {
  const r = spawnSync('unzip', ['-p', zipPath, name], { maxBuffer: 8 * 1024 * 1024 })
  // 2026-10-21：只有「unzip 没装」才允许跳过复检；其他 spawn 错误（权限/资源）必须失败，
  // 否则校验被静默跳过，坏包照样出（审查 r10-10 High）
  if (r.error) {
    if (r.error.code === 'ENOENT') return { toolMissing: true }
    throw new Error('zip 复检失败（unzip 无法执行）：' + r.error.message)
  }
  return { content: r.status === 0 && r.stdout ? r.stdout.toString('utf8') : '' }
}

buildZip().then(() => {
  const buf = fs.readFileSync(zipPath)
  const sha = crypto.createHash('sha256').update(buf).digest('hex')

  // ---- 2. 产物复检：zip 内 index.html 必须是移动端入口 ----
  const got = readZipEntry('index.html')
  if (got.toolMissing) {
    console.warn('⚠️  未找到 unzip 命令，跳过 zip 内 index.html 复检（源 mobile.html 已校验通过）')
  } else {
    const html = got.content || ''
    if (!html) {
      fs.unlinkSync(zipPath)
      console.error('[FAIL] zip 里没有 index.html —— 这个包装上去会白屏，已删除坏包')
      process.exit(1)
    }
    const entry = (html.match(/<script[^>]+src="[^"]*assets\/([A-Za-z0-9_.-]+\.js)"/) || [])[1] || ''
    if (!/^mobile-/.test(entry)) {
      fs.unlinkSync(zipPath)
      console.error('[FAIL] zip 内 index.html 的入口是 ' + (entry || '未知') + '（应为 mobile-*.js）')
      console.error('       这就是「App 变网页版」的那个坏包，已删除：' + zipPath)
      process.exit(1)
    }
  }

  console.log('')
  console.log('打包完成  ' + zipPath)
  console.log('入口      index.html → ' + entryChunk + '（移动端 ✓）')
  console.log('体积      ' + (buf.length / 1024 / 1024).toFixed(2) + ' MB')
  console.log('SHA-256   ' + sha)
  console.log('')
  console.log('上传：把 zip 传到服务器 /opt/kb/app-bundles/ （nginx 暴露为 /app-bundles/）')
  console.log('然后把下面这段写进后台 SysSetting 的 app_bundle：')
  console.log('')
  const base = process.env.KB_BASE || 'https://www.你的域名'
  console.log(JSON.stringify({
    version,
    url: `${base}/app-bundles/${zipName}`,
    checksum: sha,
    notes: '',
  }))
}).catch((e) => {
  console.error('打包失败：', e && e.message)
  process.exit(1)
})
