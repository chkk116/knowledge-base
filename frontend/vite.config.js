import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { gzipSync, brotliCompressSync, constants } from 'node:zlib'
import { readdirSync, readFileSync, writeFileSync, statSync } from 'node:fs'
import { join, extname, relative, isAbsolute, resolve } from 'node:path'

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
  plugins: [vue(), precompressPlugin()],
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
    // 分包：把体积大且变动少的第三方库拆成独立长缓存 chunk，主包因此显著变小
    chunkSizeWarningLimit: 1500,
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (!id.includes('node_modules')) return
          if (id.includes('@wangeditor')) return 'wangeditor'
          if (id.includes('element-plus') || id.includes('@element-plus')) return 'element-plus'
          if (
            id.includes('/vue/') || id.includes('vue-router') ||
            id.includes('pinia') || id.includes('@vue/')
          ) return 'vue-vendor'
          if (
            id.includes('marked') || id.includes('markdown') ||
            id.includes('dompurify') || id.includes('highlight')
          ) return 'markdown'
          return 'vendor'
        }
      }
    }
  }
})
