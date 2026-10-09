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
 */
const { execSync } = require('child_process')
const fs = require('fs')
const path = require('path')
const crypto = require('crypto')

const ver = process.argv[2]
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
if (!fs.existsSync(path.join(distDir, 'index.html'))) {
  console.error('构建目录里没有 index.html —— 打包出来会是坏包，已中止：' + distDir)
  process.exit(1)
}

fs.mkdirSync(outDir, { recursive: true })

// 版本号用时间戳：单调递增、肉眼可读，且天然避免与上一次重名
const d = new Date()
const p2 = (n) => String(n).padStart(2, '0')
const version = `${d.getFullYear()}${p2(d.getMonth() + 1)}${p2(d.getDate())}${p2(d.getHours())}${p2(d.getMinutes())}`
const zipName = `kb-app-${version}.zip`
const zipPath = path.join(outDir, zipName)
if (fs.existsSync(zipPath)) fs.unlinkSync(zipPath)

// 用 archiver 打标准 zip。
// ⚠️ 不能用 PowerShell 的 Compress-Archive：它写出来的条目名是**反斜杠**
// （assets\xxx.js），Android 解压时会把它当成文件名的一部分，整包错乱。
const archiver = require('archiver')

function zipDir(srcDir, outPath) {
  return new Promise((resolve, reject) => {
    const output = fs.createWriteStream(outPath)
    const archive = archiver('zip', { zlib: { level: 9 } })
    output.on('close', () => resolve(archive.pointer()))
    archive.on('warning', (e) => { if (e.code !== 'ENOENT') reject(e) })
    archive.on('error', reject)
    archive.pipe(output)
    // 第二个参数 false = 不套顶层目录，直接把内容放在 zip 根（Capgo 要求）
    archive.directory(srcDir, false)
    archive.finalize()
  })
}

zipDir(distDir, zipPath).then((bytes) => {
  const buf = fs.readFileSync(zipPath)
  const sha = crypto.createHash('sha256').update(buf).digest('hex')

  console.log('')
  console.log('打包完成  ' + zipPath)
  console.log('体积      ' + (buf.length / 1024 / 1024).toFixed(2) + ' MB')
  console.log('SHA-256   ' + sha)
  console.log('')
  console.log('上传：把 zip 传到服务器 /opt/kb/app-bundles/ （nginx 暴露为 /app-bundles/）')
  console.log('然后把下面这段写进后台 SysSetting 的 app_bundle：')
  console.log('')
  if (!process.env.KB_BASE) { console.error('缺少 KB_BASE 环境变量（App 更新检查的基地址）——示例域名不能写入生产配置'); process.exit(1) }
  console.log(JSON.stringify({
    version,
    url: `${process.env.KB_BASE}/app-bundles/${zipName}`,
    checksum: sha,
    notes: '',
  }))
}).catch((e) => {
  console.error('打包失败：', e && e.message)
  process.exit(1)
})
