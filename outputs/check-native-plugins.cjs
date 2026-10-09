#!/usr/bin/env node
/**
 * 原生插件配置自检 —— 打包前/后跑一遍，防止「配置漏了但构建照样成功」。
 *
 * 背景（2026-10-09 踩坑）：Capacitor 靠 `android/app/src/main/assets/capacitor.plugins.json`
 * 里的 classpath 用 Class.forName 实例化插件（见 @capacitor/android 的 PluginManager.java）。
 * 该文件是 `[]` 时**一个插件都不加载**，但 gradle 构建完全成功、dex 里类也在 ——
 * 表现为：`window.Capacitor.Plugins.App` 为 undefined，返回键 / 热更 / 外链全部静默失效。
 * **必须显式检查。**
 *
 * 用法：
 *   node outputs/check-native-plugins.cjs                 # 查工程配置
 *   node outputs/check-native-plugins.cjs <apk路径>        # 顺便查打出来的 APK
 *
 * 纯 Node 实现，不依赖 shell（Windows 上 execSync 走 cmd.exe 会偶发 EBUSY）。
 */
const fs = require('fs')
const path = require('path')
const zlib = require('zlib')

const ROOT = path.resolve(__dirname, '..')
const MOBILE = path.join(ROOT, 'mobile')
const JSON_FILE = path.join(MOBILE, 'android/app/src/main/assets/capacitor.plugins.json')

let fail = 0
const ok = (m) => console.log('  ✓ ' + m)
const bad = (m) => { console.log('  ✗ ' + m); fail++ }

/** 在目录里递归找文件（纯 Node，不用 shell） */
function findFile(dir, name) {
  const stack = [dir]
  while (stack.length) {
    const d = stack.pop()
    let entries
    try { entries = fs.readdirSync(d, { withFileTypes: true }) } catch { continue }
    for (const e of entries) {
      const p = path.join(d, e.name)
      if (e.isDirectory()) stack.push(p)
      else if (e.name === name) return p
    }
  }
  return null
}

/** 读 zip 的中央目录，返回 [{name, method, compSize, size, offset}] */
function readCentralDirectory(zipPath) {
  const buf = fs.readFileSync(zipPath)
  // 从尾部找 EOCD (PK\x05\x06)
  let eocd = -1
  for (let i = buf.length - 22; i >= 0 && i > buf.length - 66000; i--) {
    if (buf.readUInt32LE(i) === 0x06054b50) { eocd = i; break }
  }
  if (eocd < 0) throw new Error('找不到 EOCD，不是合法 zip')
  const count = buf.readUInt16LE(eocd + 10)
  let p = buf.readUInt32LE(eocd + 16)
  const out = []
  for (let k = 0; k < count; k++) {
    if (buf.readUInt32LE(p) !== 0x02014b50) break
    const method = buf.readUInt16LE(p + 10)
    const compSize = buf.readUInt32LE(p + 20)
    const size = buf.readUInt32LE(p + 24)
    const nameLen = buf.readUInt16LE(p + 28)
    const extraLen = buf.readUInt16LE(p + 30)
    const cmtLen = buf.readUInt16LE(p + 32)
    const offset = buf.readUInt32LE(p + 42)
    const name = buf.slice(p + 46, p + 46 + nameLen).toString('utf8')
    out.push({ name, method, compSize, size, offset })
    p += 46 + nameLen + extraLen + cmtLen
  }
  return { buf, entries: out }
}

/** 取 zip 条目内容 */
function entryData(buf, e) {
  const nameLen = buf.readUInt16LE(e.offset + 26)
  const extraLen = buf.readUInt16LE(e.offset + 28)
  const start = e.offset + 30 + nameLen + extraLen
  const raw = buf.slice(start, start + e.compSize)
  return e.method === 0 ? raw : zlib.inflateRawSync(raw)
}

console.log('== 1. capacitor.plugins.json ==')
let list = []
try {
  list = JSON.parse(fs.readFileSync(JSON_FILE, 'utf8'))
  if (Array.isArray(list) && list.length > 0) {
    ok(`${list.length} 个插件：` + list.map((p) => String(p.classpath).split('.').pop()).join(', '))
  } else {
    bad('清单为空 [] —— 所有原生插件都不会加载！（返回键/热更/外链都会静默失效）')
  }
} catch (e) {
  bad('读不到或不是合法 JSON: ' + e.message)
}

console.log('== 2. gradle 登记（include + implementation） ==')
const settings = fs.readFileSync(path.join(MOBILE, 'android/capacitor.settings.gradle'), 'utf8')
const buildGradle = fs.readFileSync(path.join(MOBILE, 'android/app/capacitor.build.gradle'), 'utf8')
for (const pkg of [...new Set(list.map((p) => p.pkg).filter(Boolean))]) {
  const mod = ':' + pkg.replace('@', '').replace('/', '-')   // @capacitor/app -> :capacitor-app
  const inc = settings.includes(`include '${mod}'`) && settings.includes(pkg)
  const dep = buildGradle.includes(`project('${mod}')`)
  inc && dep ? ok(`${mod} 已登记`) : bad(`${mod} 登记不全（include=${inc} implementation=${dep}）`)
}
// 本地插件（写在 app 源码里，无 pkg / 无 gradle 模块）不需要登记 gradle
const locals = list.filter((p) => !p.pkg)
if (locals.length) ok(`${locals.length} 个本地插件（在 app 源码内，无需 gradle 模块）：` + locals.map((p) => String(p.classpath).split('.').pop()).join(', '))

console.log('== 3. 插件类源文件是否存在 ==')
const nm = path.join(MOBILE, 'node_modules')
const appJava = path.join(MOBILE, 'android/app/src/main/java')
for (const p of list) {
  const cls = String(p.classpath).split('.').pop() + '.java'
  const base = p.pkg ? nm : appJava
  findFile(base, cls)
    ? ok(`${p.classpath} 源文件在（${p.pkg ? 'node_modules' : 'app 源码'}）`)
    : bad(`${p.classpath} 找不到 ${cls}`)
}

const apk = process.argv[2]
if (apk && fs.existsSync(apk)) {
  console.log('== 4. APK 内的清单 ==')
  let buf, entries
  try {
    ({ buf, entries } = readCentralDirectory(apk))
    ok(`zip 条目 ${entries.length} 个`)
  } catch (e) { bad('解析 APK 失败: ' + e.message) }

  if (buf) {
    const e = entries.find((x) => x.name === 'assets/capacitor.plugins.json')
    if (!e) {
      bad('APK 里没有 assets/capacitor.plugins.json')
    } else {
      const txt = entryData(buf, e).toString('utf8').trim()
      try {
        const arr = JSON.parse(txt)
        arr.length > 0
          ? ok(`APK 内 ${arr.length} 个插件：` + arr.map((p) => String(p.classpath).split('.').pop()).join(', '))
          : bad(`APK 内清单为空 ${txt} —— 插件不会加载！`)
      } catch { bad('APK 内清单不是合法 JSON: ' + txt.slice(0, 60)) }
    }

    console.log('== 5. 插件类是否在 dex ==')
    const dexes = entries.filter((x) => /^classes\d*\.dex$/.test(x.name))
    if (!dexes.length) bad('APK 里没有 classes*.dex')
    else {
      const dex = Buffer.concat(dexes.map((x) => entryData(buf, x)))
      ok(`合并 ${dexes.length} 个 dex（${(dex.length / 1024 / 1024).toFixed(1)} MB）`)
      for (const p of list) {
        // dex 里存的是斜杠形式（com/capacitorjs/.../AppPlugin），不是点号
        const jvmName = String(p.classpath).replace(/\./g, '/')
        dex.includes(Buffer.from(jvmName))
          ? ok(`${p.classpath} 在 dex 内`)
          : bad(`${p.classpath} 不在 dex 内`)
      }
    }
  }
}

console.log('')
console.log(fail === 0 ? '✅ 原生插件配置检查通过' : `❌ 有 ${fail} 项不通过`)
process.exit(fail === 0 ? 0 : 1)
