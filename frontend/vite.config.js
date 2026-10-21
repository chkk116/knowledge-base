import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { gzipSync, brotliCompressSync, constants } from 'node:zlib'
import { readdirSync, readFileSync, writeFileSync, statSync } from 'node:fs'
import { join, extname, relative, isAbsolute, resolve } from 'node:path'
import { tmpdir } from 'node:os'
import { fileURLToPath } from 'node:url'

const __dirname = fileURLToPath(new URL('.', import.meta.url))

// 版本号单一来源：package.json 的 version，构建期注入 __APP_VERSION__，
// 页脚直接引用，避免手写版本号与发布脱节。
// ⚠️ 发版时必须同步改三处：package.json（App「关于」/页脚显示）、
//    mobile/android/app/build.gradle（versionName/versionCode，升级判断用它）、
//    src/changelog.js（更新日志，升级弹层按 versionName 匹配兜底）。
const pkg = JSON.parse(readFileSync(new URL('./package.json', import.meta.url), 'utf8'))

// 零依赖预压缩插件：构建结束后用 Node 内置 zlib 为每个文本资源生成 .gz / .br
// （沙箱无法 npm install 第三方压缩插件，故用原生 zlib；.gz/.br 可被 nginx / 静态托管直接命中）
const COMPRESSIBLE = new Set([
  '.js', '.mjs', '.cjs', '.css', '.html', '.htm',
  '.json', '.svg', '.xml', '.txt', '.map'
])
const BROTLLI_PARAMS = {
  [constants.BROTLI_PARAM_MODE]: constants.BROTLI_MODE_TEXT,
  [constants.BROTLI_PARAM_QUALITY]: 11
}

function walk(dir, out = []) {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const full = join(dir, entry.name)
    if (entry.isDirectory()) walk(full, out)
    else out.push(full)
  }
  return out
}

function precompressPlugin() {
  let outDirAbs = 'dist'
  return {
    name: 'vite-precompress',
    apply: 'build',
    configResolved(config) {
      const o = config.build.outDir
      outDirAbs = isAbsolute(o) ? o : resolve(config.root, o)
    },
      closeBundle() {
        let total = 0
      let saved = 0
      for (const file of walk(outDirAbs)) {
        if (!COMPRESSIBLE.has(extname(file))) continue
        const buf = readFileSync(file)
        if (buf.length < 1024) continue // 小于 1KB 不值得压
        const gz = gzipSync(buf, { level: 9 })
        const br = brotliCompressSync(buf, BROTLLI_PARAMS)
        writeFileSync(file + '.gz', gz)
        writeFileSync(file + '.br', br)
        total += buf.length
        saved += buf.length - br.length
      }
      if (total) {
        const ratio = ((saved / total) * 100).toFixed(1)
        this.warn(`[precompress] 已生成 .gz/.br，${saved} 字节经 brotli 可省（约 ${ratio}%）`)
      }
    }
  }
}

export default defineConfig({
  // 缓存目录挪到系统临时目录：node_modules/.vite 里的 deps_temp_* 批量清理
  // 会触发 safe-delete 钩子（>50 文件要求确认，fail-closed 直接崩进程）。
  // ⚠️ 必须用 os.tmpdir()，不能写死 C:/Users/<某个人>——换机器/换用户名时该路径不存在，
  //    Vite 建不了缓存目录，依赖预构建会卡在 scanning dependencies（2026-10-21 实锤）。
  cacheDir: join(tmpdir(), 'kb-vite-cache'),
  // 构建期常量：版本号来自 package.json（页脚展示用）
  define: {
    __APP_VERSION__: JSON.stringify(pkg.version)
  },
  plugins: [
    vue(),
    precompressPlugin(),
    // App 离线包：Capacitor 本地服务器会把 <script type="module" crossorigin> 按 CORS 规则拒掉
    // （历史上「内置资源 → 白屏」的根因）。构建时统一摘掉 crossorigin：
    // 网页端是同源加载，有没有该属性行为一致；App 内置包则必需。
    {
      name: 'strip-crossorigin',
      transformIndexHtml(html) {
        return html.replace(/\s+crossorigin(?:="[^"]*")?/g, '')
      }
    },
    // ⚠️ prismjs 语言组件（components/prism-*.js）在模块顶层直接读写全局 Prism，
    // 而 Rolldown 会把 prismjs 主包（CJS）惰性化成 `var fr = t(...)` 工厂 ——
    // 结果：语言组件先执行，核心后执行 → `ReferenceError: Prism is not defined`，
    // 且抛在模块求值阶段，会把整个懒加载 chunk（如 AiChat）干掉 → 页面空白。
    // 在组件文件里声明局部绑定 Prism 并强制核心先求值，彻底摆脱执行顺序依赖。
    // （曾试过在业务模块里 `window.Prism = Prism`：它所在的 chunk 评估晚于语言组件，无效。）
    {
      name: 'prism-global-shim',
      enforce: 'pre',
      transform(code, id) {
        const norm = id.replace(/\\/g, '/')
        if (!norm.includes('/node_modules/prismjs/components/')) return null
        // 只注入语言组件（prism-*.js）：index.js 是 CJS 辅助文件（内部 require components.json），
        // prism-core 是本体本体无需注入——混入 ESM import 会导致 require is not defined
        if (!/\/prismjs\/components\/prism-[^/]+\.js$/.test(norm)) return null
        if (/(^|[\s;])(?:var|let|const)\s+Prism\b/.test(code)) return null
        return {
          code:
            "import __prismCore from 'prismjs';\n" +
            "var Prism = typeof window !== 'undefined' ? (window.Prism = __prismCore) : __prismCore;\n" +
            code,
          map: null
        }
      }
    }
  ],
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://localhost:8080',
      '/uploads': 'http://localhost:8080'
    }
  },
  // 预打包 wangEditor 核心（ESM/CJS 互操作，避免 dev/build 报错）
  optimizeDeps: {
    include: ['@wangeditor/editor', '@wangeditor/plugin-md']
  },
  build: {
    target: 'es2020',
    outDir: 'dist',
    // 分包：把体积大且变动少的第三方库拆成独立长缓存 chunk，主包因此显著变小
    chunkSizeWarningLimit: 1500,
    rollupOptions: {
      // 多页入口：桌面端 index.html（/）+ 移动端 mobile.html（/m/）。
      // 两者共用同一份依赖与样式变量，但各自注册的 Element Plus 组件不同，
      // 移动端入口因此不会被桌面端的菜单/表格等重组件拖大。
      input: {
        main: resolve(__dirname, 'index.html'),
        mobile: resolve(__dirname, 'mobile.html')
      },
      output: {
        // Vite 8 底层是 Rolldown（产物里有 rolldown-runtime）。
        // manualChunks 只是兼容层，Rolldown 会按自己的共享模块算法重新归并，
        // 曾把 Vue 运行时合并进 collab chunk 导致协同编辑器全家桶被首屏静态加载。
        // 必须用 Rolldown 原生 advancedChunks：组内模块强制留在本组，按声明顺序匹配。
        // 注意 test 必须限定包根（node_modules/包名/），裸写 'yjs' 会误匹配 dayjs
        // （"dayjs" 含 "yjs" 子串）；裸写 '/vue/' 会误匹配 element-plus/es/utils/vue/*。
        advancedChunks: {
          groups: [
            { name: 'wangeditor', test: /node_modules[\\/]@?wangeditor[\\/]/ },
            { name: 'element-plus', test: /node_modules[\\/](@element-plus|element-plus)[\\/]/ },
            { name: 'collab', test: /node_modules[\\/](@tiptap|prosemirror|y-prosemirror|y-protocols|yjs|lib0|@hocuspocus)[\\/]/ },
            { name: 'vue-vendor', test: /node_modules[\\/](@vue|vue|vue-router|vue-demi|pinia)[\\/]/ },
            { name: 'ep-deps', test: /node_modules[\\/](dayjs|lodash-es|async-validator|@vueuse)[\\/]/ },
            { name: 'markdown', test: /node_modules[\\/](marked|markdown-it|dompurify|prismjs|highlight\.js)[\\/]/ },
            { name: 'vendor', test: /node_modules[\\/]/ }
          ]
        }
      }
    }
  }
})
