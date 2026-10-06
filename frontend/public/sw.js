/* eslint-disable no-restricted-globals */
/**
 * 移动端离线能力（Service Worker）
 *
 * 为什么需要它：
 *   APK 走 Capacitor 的 server.url 指向线上站点，断网时 WebView 连 HTML 都拿不到，
 *   表现为白屏 —— 「离线可用」就无从谈起。这里用 App Shell 策略把外壳与静态资源
 *   缓存下来，断网冷启动/刷新仍能打开界面，写文档则落到 IndexedDB 队列。
 *
 * 策略：
 *   1) 导航请求（HTML）：network-first，失败 → 缓存的 mobile.html / index.html
 *   2) /assets/*（带内容 hash）：cache-first，命中即返回，永不失效
 *   3) /api/*：**不缓存**，直接走网络（离线时由 offlineStore 队列兜底）
 *   4) 其它同源 GET：stale-while-revalidate
 *
 * 注意：不缓存 /api，避免出现「离线读到过期登录态/文档」这类更难排查的问题。
 */

// VERSION 由构建后脚本 build-inject-sw.cjs 按 package.json 版本注入（发版即变，旧哈希缓存随激活清理）
const VERSION = 'kb-mobile-v1'
const SHELL_CACHE = `${VERSION}-shell`
const ASSET_CACHE = `${VERSION}-assets`

// 预缓存的外壳入口（相对根路径，两个入口都存）
const SHELL_URLS = ['/mobile.html', '/index.html', '/manifest.webmanifest']

// 该常量由构建后脚本 build-inject-sw.cjs 注入（取自 Vite 的 __vite__mapDeps，
// 覆盖移动端入口的完整 chunk 依赖图）。开发/未注入时为空数组，不影响功能。
const PRECACHE_ASSETS = [];

self.addEventListener('install', (event) => {
  event.waitUntil((async () => {
    const cache = await caches.open(SHELL_CACHE)
    const assetCache = await caches.open(ASSET_CACHE)

    // 1) 先缓存外壳 HTML
    await Promise.all(SHELL_URLS.map(async (u) => {
      try { await cache.add(new Request(u, { cache: 'reload' })) } catch { /* 忽略 */ }
    }))

    // 2) 预缓存构建期注入的完整依赖清单 —— 保证断网冷启动不缺文件
    if (PRECACHE_ASSETS.length) {
      await Promise.all(PRECACHE_ASSETS.map(async (u) => {
        try { await assetCache.add(new Request(u, { cache: 'reload' })) } catch { /* 单个失败不阻塞 */ }
      }))
    } else {
      // 兜底：未注入时，至少把 HTML 里直接引用的资源缓存下来
      for (const shell of ['/mobile.html', '/index.html']) {
        try {
          const res = await cache.match(shell)
          if (!res) continue
          const html = await res.text()
          const urls = new Set()
          const re = /(?:src|href)="([^"]+)"/g
          let m
          while ((m = re.exec(html))) {
            const u = m[1]
            if (u.startsWith('/assets/') || u.startsWith('/icons/') || u.endsWith('.png')) urls.add(u)
          }
          await Promise.all([...urls].map(async (u) => {
            try { await assetCache.add(new Request(u, { cache: 'reload' })) } catch { /* 忽略 */ }
          }))
        } catch { /* 忽略 */ }
      }
    }

    await self.skipWaiting()
  })())
})

self.addEventListener('activate', (event) => {
  event.waitUntil((async () => {
    // 清掉旧版本缓存
    const keys = await caches.keys()
    await Promise.all(keys.filter((k) => k.startsWith('kb-mobile-') && !k.startsWith(VERSION)).map((k) => caches.delete(k)))
    await self.clients.claim()
  })())
})

/** 判断是否为静态资源（带 hash 的构建产物） */
function isAsset(url) {
  return url.pathname.startsWith('/assets/') ||
    /\.(?:js|css|woff2?|ttf|png|jpe?g|gif|svg|webp|ico)$/.test(url.pathname)
}

self.addEventListener('fetch', (event) => {
  const req = event.request
  if (req.method !== 'GET') return

  const url = new URL(req.url)
  if (url.origin !== self.location.origin) return  // 跨域（CDN 等）不接管

  // API 一律走网络，不缓存
  if (url.pathname.startsWith('/api/') || url.pathname.startsWith('/collab')) return

  // 导航请求：network-first → 回退外壳
  if (req.mode === 'navigate') {
    event.respondWith((async () => {
      try {
        const fresh = await fetch(req)
        // 只把正常响应写进外壳缓存：一次 502 不能把坏页面缓存成"离线外壳"（2026-10-06 外部安全审查）
        if (fresh && fresh.ok) {
          const cache = await caches.open(SHELL_CACHE)
          cache.put(url.pathname.startsWith('/m/') || url.pathname === '/mobile.html' ? '/mobile.html' : '/index.html', fresh.clone()).catch(() => {})
        }
        return fresh
      } catch {
        const cache = await caches.open(SHELL_CACHE)
        const isMobile = url.pathname === '/m' || url.pathname.startsWith('/m/') || url.pathname === '/mobile.html'
        const fallback = await cache.match(isMobile ? '/mobile.html' : '/index.html')
        if (fallback) return fallback
        // 兜底：移动端优先
        return (await cache.match('/mobile.html')) || (await cache.match('/index.html')) || Response.error()
      }
    })())
    return
  }

  // 静态资源：cache-first
  if (isAsset(url)) {
    event.respondWith((async () => {
      const cache = await caches.open(ASSET_CACHE)
      const hit = await cache.match(req)
      if (hit) return hit
      try {
        const fresh = await fetch(req)
        if (fresh && fresh.ok && fresh.type === 'basic') cache.put(req, fresh.clone()).catch(() => {})
        return fresh
      } catch (e) {
        return hit || Response.error()
      }
    })())
    return
  }

  // 其它同源 GET：stale-while-revalidate
  event.respondWith((async () => {
    const cache = await caches.open(ASSET_CACHE)
    const hit = await cache.match(req)
    const fetching = fetch(req).then((fresh) => {
      if (fresh && fresh.ok && fresh.type === 'basic') cache.put(req, fresh.clone()).catch(() => {})
      return fresh
    }).catch(() => null)
    return hit || (await fetching) || Response.error()
  })())
})

// 允许页面主动要求激活新版本
self.addEventListener('message', (event) => {
  if (event.data === 'SKIP_WAITING') self.skipWaiting()
})
