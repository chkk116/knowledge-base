<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import QRCode from 'qrcode'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { User, Lock, ChatDotRound, ChatLineRound, Promotion } from '@element-plus/icons-vue'
import { useAuthStore } from '../store/auth'
import SlideCaptcha from '../components/SlideCaptcha.vue'
import SiteFooter from '../components/SiteFooter.vue'
import { resolveUrl } from '../utils/runtimeBase'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

const username = ref('')
const password = ref('')
const captchaToken = ref('')
const slidePassed = ref(false)
const loading = ref(false)
const captchaOpen = ref(false)
const captchaNonce = ref(0)

function onSlideSuccess(tk) {
  captchaToken.value = tk
  slidePassed.value = true
  doLogin()
}
function openCaptcha() {
  if (slidePassed.value) return
  captchaOpen.value = true
}
function onCaptchaClose() {
  captchaOpen.value = false
}

async function doLogin() {
  loading.value = true
  try {
    await auth.login(username.value.trim(), password.value, captchaToken.value, '')
    captchaOpen.value = false
    ElMessage.success('登录成功')
    let redirect = route.query.redirect || (auth.isAdmin ? '/admin/docs' : '/')
    // App 内（main-mobile.js 会设 window.__KB_MOBILE__）：桌面路由不存在，一律回移动端首页；
    // 仅放行 /m 前缀（外层如需带回某移动端页时会带全路径）
    if (window.__KB_MOBILE__) {
      const r = String(redirect)
      redirect = r.startsWith('/m') ? r : '/home'
    }
    router.push(redirect)
  } catch (e) {
    const msg = e.response?.data?.error || e.response?.data?.message || (e.biz && e.message) || '登录失败'
    slidePassed.value = false
    const isCredential = msg.includes('用户名或密码') || msg.includes('密码错误') || msg.includes('禁用')
    if (isCredential) {
      captchaOpen.value = false
      ElMessage.error(msg)
    } else {
      captchaNonce.value++
      captchaOpen.value = true
      ElMessage.error(msg)
    }
  } finally {
    loading.value = false
  }
}

async function submit() {
  if (!username.value || !password.value) {
    ElMessage.warning('请输入账号和密码')
    return
  }
  if (slidePassed.value) {
    doLogin()
    return
  }
  captchaOpen.value = true
}

function onForgot() {
  ElMessageBox.alert(
    '请联系系统管理员重置密码。',
    { confirmButtonText: '知道了', type: 'info' }
  )
}

// 3D 插画的微妙视差（鼠标移动驱动 transform 微调）
const sceneRef = ref(null)
const onSceneMove = (e) => {
  const el = sceneRef.value
  if (!el) return
  const r = el.getBoundingClientRect()
  const dx = ((e.clientX - r.left) / r.width - 0.5) * 6
  const dy = ((e.clientY - r.top) / r.height - 0.5) * 4
  el.style.setProperty('--rx', dy.toFixed(2) + 'deg')
  el.style.setProperty('--ry', (-dx).toFixed(2) + 'deg')
  el.style.setProperty('--tx', (dx * 1.5).toFixed(1) + 'px')
  el.style.setProperty('--ty', (dy * 1.5).toFixed(1) + 'px')
}
const onSceneLeave = () => {
  const el = sceneRef.value
  if (!el) return
  el.style.setProperty('--rx', '0deg'); el.style.setProperty('--ry', '0deg')
  el.style.setProperty('--tx', '0px');  el.style.setProperty('--ty', '0px')
}

// 二维码：前端用 qrcode 包生成 SVG；内容地址由管理后台「登录页二维码」配置（未配置用内置默认）
// 若后台直接上传了二维码图片（wxImage/supportImage），优先显示图片（适合微信群截图类现成码）
// errorCorrectionLevel 'H'：中心有徽章遮挡，需要 30% 纠错才能稳定扫出
const wxImage = ref('')
const supportImage = ref('')
// 个性化二维码列表（2026-10-06）：后台「登录页二维码」可配任意数量，
// 每项 {label 下方文字, content 扫码内容(链接/任意文本), image 可选现成图片}
const qrItems = ref([])
onMounted(async () => {
  startParticles()
  let wxUrl = 'https://www.example.com/wx-miniapp'
  let supportUrl = 'https://www.example.com/support'
  let items = null
  try {
    const { data } = await fetch(resolveUrl('/api/public/login-config')).then(r => r.json()).then(d => ({ data: d }))
    if (data?.wxUrl) wxUrl = data.wxUrl
    if (data?.supportUrl) supportUrl = data.supportUrl
    if (data?.wxImage) wxImage.value = data.wxImage
    if (data?.supportImage) supportImage.value = data.supportImage
    if (data?.items) { try { items = JSON.parse(data.items) } catch (e) { items = null } }
  } catch (e) { /* 拉不到配置就用默认地址，不阻塞登录 */ }
  const opt = { type: 'svg', margin: 1, width: 96, errorCorrectionLevel: 'H', color: { dark: '#1e2a3f', light: '#ffffff' } }


  // 组装渲染列表：items 优先；无 items 时从旧字段迁移，保证向后兼容
  const list = Array.isArray(items) && items.length
    ? items.map((it) => ({ label: String(it.label ?? '').trim(), content: String(it.content ?? '').trim(), image: String(it.image ?? '').trim() }))
    : [
        { label: '微信扫码体验移动版', content: wxUrl, image: wxImage.value },
        { label: '技术支持', content: supportUrl, image: supportImage.value },
      ]
  for (const it of list) {
    if (!it.label && !it.content && !it.image) continue
    if (!it.image && it.content) {
      try { it.svg = await QRCode.toString(it.content, opt) } catch (e2) { it.svg = '' }
    }
    qrItems.value.push(it)
  }
})

// 语言切换（当前仅简体中文，预留 UI）
const langOpen = ref(false)
function toggleLang() { langOpen.value = !langOpen.value }
function pickLang(v) { langOpen.value = false; ElMessage.info('暂仅提供简体中文') }

// 离开登录页时停掉动画并解绑监听，避免后台空转
onUnmounted(() => { if (particleCleanup) particleCleanup() })

// ---------- 粒子连线动画（登录页左栏装饰） ----------
const particleCanvas = ref(null)
let particleRaf = 0
let particleCleanup = null

function startParticles() {
  const canvas = particleCanvas.value
  if (!canvas) return
  const ctx = canvas.getContext('2d')
  if (!ctx) return
  // 尊重系统「减少动态效果」偏好：只画一帧静态图，不做动画
  const reduced = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches

  let w = 0, h = 0
  const resize = () => {
    const rect = canvas.getBoundingClientRect()
    const dpr = Math.min(window.devicePixelRatio || 1, 2)
    w = Math.max(1, rect.width); h = Math.max(1, rect.height)
    canvas.width = Math.round(w * dpr)
    canvas.height = Math.round(h * dpr)
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
  }
  resize()

  // 数量按整页面积缩放，避免小屏过密
  const count = Math.max(30, Math.min(120, Math.round(w * h / 15000)))
  const rnd = (a, b) => a + Math.random() * (b - a)
  const pts = Array.from({ length: count }, () => {
    const bvx = rnd(-0.58, 0.58), bvy = rnd(-0.58, 0.58)   // 基础漂移速度（调大=更快）
    return { x: rnd(0, w), y: rnd(0, h), vx: bvx, vy: bvy, bvx, bvy, r: rnd(1.1, 2.2) }
  })

  const LINK = 110        // 粒子间连线距离阈值
  const ATTRACT_R = 190   // 鼠标吸引半径
  const CURSOR_LINK = 150 // 光标与粒子的连线距离
  const mouse = { x: 0, y: 0, on: false }
  const onMouse = (e) => {
    const rect = canvas.getBoundingClientRect()
    const x = e.clientX - rect.left, y = e.clientY - rect.top
    mouse.on = x >= 0 && y >= 0 && x <= rect.width && y <= rect.height
    mouse.x = x; mouse.y = y
  }
  const onMouseLeave = () => { mouse.on = false }
  window.addEventListener('mousemove', onMouse)
  document.documentElement.addEventListener('mouseleave', onMouseLeave)

  const isDark = () => document.documentElement.classList.contains('dark')

  function drawFrame() {
    const dark = isDark()
    const dotBase = dark ? '147, 180, 255' : '59, 110, 230'
    const lineBase = dark ? '129, 150, 255' : '79, 70, 229'
    ctx.clearRect(0, 0, w, h)
    for (const p of pts) {
      if (!reduced) {
        // 鼠标吸引：半径内柔和拉向光标，越近吸力越小（不会糊成一团）
        if (mouse.on) {
          const dx = mouse.x - p.x, dy = mouse.y - p.y
          const d = Math.sqrt(dx * dx + dy * dy) || 1
          if (d < ATTRACT_R && d > 26) {
            const f = (1 - d / ATTRACT_R) * 0.045
            p.vx += (dx / d) * f
            p.vy += (dy / d) * f
          }
        }
        // 限速 + 缓慢回归初始漂移，防止速度失控或粒子集体停摆
        p.vx *= 0.985; p.vy *= 0.985
        const sp = Math.hypot(p.vx, p.vy)
        if (sp > 2.6) { p.vx = p.vx / sp * 2.6; p.vy = p.vy / sp * 2.6 }
        if (sp < 0.12) { p.vx += (p.bvx - p.vx) * 0.03; p.vy += (p.bvy - p.vy) * 0.03 }
        p.x += p.vx; p.y += p.vy
        if (p.x < -10) p.x = w + 10; else if (p.x > w + 10) p.x = -10
        if (p.y < -10) p.y = h + 10; else if (p.y > h + 10) p.y = -10
      }
      ctx.beginPath()
      // 暗色模式下点缩小、透明度减半：避免在深色底上呈现刺眼的白点
      ctx.arc(p.x, p.y, dark ? p.r * 0.7 : p.r, 0, Math.PI * 2)
      ctx.fillStyle = `rgba(${dotBase}, ${dark ? 0.32 : 0.5})`
      ctx.fill()
    }
    // 光标连线：吸引半径内的粒子向鼠标拉出更亮的线，突出「吸」的体感
    if (mouse.on && !reduced) {
      for (const p of pts) {
        const dx = mouse.x - p.x, dy = mouse.y - p.y
        const d2 = dx * dx + dy * dy
        if (d2 > CURSOR_LINK * CURSOR_LINK) continue
        const a = (1 - Math.sqrt(d2) / CURSOR_LINK) * (dark ? 0.3 : 0.45)
        ctx.beginPath()
        ctx.moveTo(mouse.x, mouse.y)
        ctx.lineTo(p.x, p.y)
        ctx.strokeStyle = `rgba(${lineBase}, ${a.toFixed(3)})`
        ctx.lineWidth = 1
        ctx.stroke()
      }
    }
    for (let i = 0; i < pts.length; i++) {
      for (let j = i + 1; j < pts.length; j++) {
        const dx = pts[i].x - pts[j].x, dy = pts[i].y - pts[j].y
        const d2 = dx * dx + dy * dy
        if (d2 > LINK * LINK) continue
        const a = (1 - Math.sqrt(d2) / LINK) * (dark ? 0.18 : 0.32)
        ctx.beginPath()
        ctx.moveTo(pts[i].x, pts[i].y)
        ctx.lineTo(pts[j].x, pts[j].y)
        ctx.strokeStyle = `rgba(${lineBase}, ${a.toFixed(3)})`
        ctx.lineWidth = 1
        ctx.stroke()
      }
    }
  }

  const onResize = () => { resize(); drawFrame() }
  window.addEventListener('resize', onResize)

  if (reduced) {
    drawFrame()   // 静态一帧
  } else {
    const loop = () => { drawFrame(); particleRaf = requestAnimationFrame(loop) }
    particleRaf = requestAnimationFrame(loop)
  }

  particleCleanup = () => {
    window.removeEventListener('resize', onResize)
    window.removeEventListener('mousemove', onMouse)
    document.documentElement.removeEventListener('mouseleave', onMouseLeave)
    if (particleRaf) cancelAnimationFrame(particleRaf)
    particleRaf = 0
  }
}
</script>

<template>
  <div class="login-page">
    <!-- 粒子连线动画：铺满整页，带鼠标吸引；纯 Canvas，无第三方依赖 -->
    <canvas ref="particleCanvas" class="particles-canvas" aria-hidden="true"></canvas>
    <!-- 左侧：浅蓝渐变 + 3D 插画 + 底部二维码区 -->
    <aside class="login-hero">
      <div class="hero-bg"></div>
      <div ref="sceneRef" class="hero-scene" @mousemove="onSceneMove" @mouseleave="onSceneLeave">
        <!-- 等距 3D 插画：笔记本 + 平板 + 服务器堆叠（纯 SVG） -->
        <svg viewBox="0 0 520 360" class="hero-3d" preserveAspectRatio="xMidYMid meet">
          <defs>
            <linearGradient id="screenG" x1="0" y1="0" x2="0" y2="1">
              <stop offset="0" stop-color="#cfe1ff"/><stop offset="1" stop-color="#9bbcff"/>
            </linearGradient>
            <linearGradient id="laptopBodyG" x1="0" y1="0" x2="0" y2="1">
              <stop offset="0" stop-color="#eef4ff"/><stop offset="1" stop-color="#c8d6f4"/>
            </linearGradient>
            <linearGradient id="cardG" x1="0" y1="0" x2="1" y2="1">
              <stop offset="0" stop-color="#ffffff"/><stop offset="1" stop-color="#e0eaff"/>
            </linearGradient>
            <linearGradient id="serverG" x1="0" y1="0" x2="0" y2="1">
              <stop offset="0" stop-color="#5fa1ff"/><stop offset="1" stop-color="#2f6fe6"/>
            </linearGradient>
            <linearGradient id="tabletG" x1="0" y1="0" x2="1" y2="1">
              <stop offset="0" stop-color="#7cb0ff"/><stop offset="1" stop-color="#4f86e6"/>
            </linearGradient>
            <radialGradient id="glowG" cx="50%" cy="55%" r="55%">
              <stop offset="0" stop-color="rgba(255,255,255,.95)"/>
              <stop offset="1" stop-color="rgba(255,255,255,0)"/>
            </radialGradient>
            <filter id="softS" x="-20%" y="-20%" width="140%" height="140%">
              <feGaussianBlur stdDeviation="2"/>
            </filter>
          </defs>

          <!-- 桌面投光（柔和地面光晕） -->
          <ellipse cx="260" cy="295" rx="200" ry="22" fill="#bcd3ff" opacity=".55" filter="url(#softS)"/>

          <!-- 笔记本电脑 -->
          <g transform="translate(60 110)">
            <!-- 屏幕 -->
            <path d="M0 110 L180 90 L180 0 L0 20 Z" fill="url(#laptopBodyG)" stroke="#a8c0e8" stroke-width="1"/>
            <path d="M14 102 L166 84 L166 14 L14 32 Z" fill="url(#screenG)"/>
            <!-- 屏幕上的 UI 抽象（卡片堆叠） -->
            <g transform="translate(28 36)">
              <rect width="48" height="36" rx="3" fill="url(#cardG)" stroke="#bcd2f0" stroke-width=".6"/>
              <rect x="56" width="62" height="36" rx="3" fill="url(#cardG)" stroke="#bcd2f0" stroke-width=".6"/>
              <circle cx="68" cy="20" r="6" fill="#3b82f6"/>
              <rect x="80" y="14" width="32" height="3" rx="1" fill="#7da3e6"/>
              <rect x="80" y="22" width="22" height="2.5" rx="1" fill="#a8c0e8"/>
              <rect x="14" y="6" width="22" height="3" rx="1" fill="#7da3e6"/>
              <rect x="14" y="14" width="34" height="2.5" rx="1" fill="#a8c0e8"/>
              <rect x="14" y="22" width="18" height="2.5" rx="1" fill="#a8c0e8"/>
              <circle cx="86" cy="58" r="8" fill="#3b82f6"/>
              <rect x="60" y="50" width="20" height="3" rx="1" fill="#7da3e6"/>
              <rect x="60" y="58" width="28" height="2.5" rx="1" fill="#a8c0e8"/>
            </g>
            <!-- 底座 -->
            <path d="M-10 110 L190 90 L210 110 L10 132 Z" fill="#dee8f7" stroke="#a8c0e8" stroke-width="1"/>
            <path d="M70 108 L130 105 L134 116 L74 119 Z" fill="#bcccf0" opacity=".7"/>
          </g>

          <!-- 平板（叠在笔记本右侧） -->
          <g transform="translate(248 138)">
            <rect width="92" height="118" rx="6" fill="url(#tabletG)" stroke="#3978e6" stroke-width="1"/>
            <rect x="6" y="6" width="80" height="106" rx="3" fill="#eef4ff"/>
            <!-- 平板上的列表 -->
            <g transform="translate(14 14)">
              <circle cx="6" cy="6" r="6" fill="#3b82f6"/>
              <rect x="18" y="3" width="48" height="3" rx="1" fill="#5a86cc"/>
              <rect x="18" y="10" width="32" height="2.5" rx="1" fill="#9bb8e8"/>
              <circle cx="6" cy="28" r="6" fill="#7cb0ff"/>
              <rect x="18" y="25" width="44" height="3" rx="1" fill="#5a86cc"/>
              <rect x="18" y="32" width="36" height="2.5" rx="1" fill="#9bb8e8"/>
              <circle cx="6" cy="50" r="6" fill="#a8c8ff"/>
              <rect x="18" y="47" width="40" height="3" rx="1" fill="#5a86cc"/>
              <rect x="18" y="54" width="28" height="2.5" rx="1" fill="#9bb8e8"/>
            </g>
          </g>

          <!-- 服务器（叠在左下角，等距多层） -->
          <g transform="translate(140 220)">
            <!-- 顶面 -->
            <path d="M0 24 L72 0 L144 24 L72 48 Z" fill="url(#serverG)" stroke="#2a5fcc" stroke-width=".8"/>
            <!-- 正面 -->
            <path d="M0 24 L0 84 L72 108 L72 48 Z" fill="#4f86e6" stroke="#2a5fcc" stroke-width=".8"/>
            <!-- 右侧面 -->
            <path d="M72 48 L72 108 L144 84 L144 24 Z" fill="#2f6fe6" stroke="#2a5fcc" stroke-width=".8"/>
            <!-- 顶面细节（散热口） -->
            <g transform="translate(36 12)">
              <rect width="72" height="4" rx="1" fill="#1d4fa6" opacity=".4"/>
              <rect y="6" width="72" height="4" rx="1" fill="#1d4fa6" opacity=".4"/>
              <rect y="12" width="72" height="4" rx="1" fill="#1d4fa6" opacity=".4"/>
            </g>
            <!-- 正面小灯 -->
            <g transform="translate(8 50)">
              <circle cx="0" cy="0" r="2.5" fill="#7fffd4"/>
              <circle cx="10" cy="2" r="2.5" fill="#ffd166"/>
              <circle cx="20" cy="4" r="2.5" fill="#7fffd4"/>
            </g>
          </g>

          <!-- 高光圆斑（暗色模式见 .glow-orb / #glowG 覆盖，避免变成刺眼白斑） -->
          <circle class="glow-orb" cx="360" cy="120" r="40" fill="url(#glowG)" opacity=".6"/>
          <circle class="glow-orb" cx="170" cy="190" r="60" fill="url(#glowG)" opacity=".4"/>
        </svg>
      </div>

      <!-- 底部二维码区：后台「登录页二维码」可个性化配置（数量/文案/内容/图片） -->
      <div class="hero-qr">
        <div v-for="(it, i) in qrItems" :key="i" class="qr-item">
          <div class="qr-box">
            <img v-if="it.image" :src="resolveUrl(it.image)" class="qr-img" alt="" />
            <div v-else-if="it.svg" class="qr-real" v-html="it.svg"></div>
            <div v-else class="qr-empty">未配置</div>
            <div class="qr-badge" :class="i % 2 ? 'sup' : 'wx'">{{ (it.label || '?').charAt(0) }}</div>
          </div>
          <div class="qr-cap">{{ it.label || '扫码' }}</div>
        </div>
      </div>
    </aside>

    <!-- 右侧：登录区 -->
    <main class="login-main">
      <div class="lang-switch">
        <el-dropdown trigger="click" @command="pickLang">
          <span class="lang-trigger">简体中文<i class="el-icon--right"><el-icon><Promotion /></el-icon></i></span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="zh">简体中文</el-dropdown-item>
              <el-dropdown-item command="en" disabled>English（暂未提供）</el-dropdown-item>
              <el-dropdown-item command="tw" disabled>繁體中文（暂未提供）</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>

      <div class="login-card-wrap">
        <h1 class="welcome-title">欢迎登录</h1>

        <div class="tabs">
          <span class="tab active">密码登录</span>
        </div>

        <el-form @submit.prevent="submit" class="login-form">
          <el-input v-model="username" placeholder="请输入账号" :prefix-icon="User" size="large" class="login-input" />
          <el-input v-model="password" type="password" show-password placeholder="请输入密码"
                    :prefix-icon="Lock" size="large" class="login-input" @keyup.enter="submit" />
          <div v-if="slidePassed" class="captcha-state passed">✓ 已通过安全验证</div>

          <el-button type="primary" size="large" class="login-submit" :loading="loading" @click="submit">
            立即登录
          </el-button>
        </el-form>

        <div class="other-login">
          <div class="divider"><span>其他登录方式</span></div>
          <div class="other-icons">
            <a class="oa oa-wechat" title="微信登录"><el-icon><ChatDotRound /></el-icon></a>
            <a class="oa oa-qq" title="QQ 登录"><el-icon><ChatLineRound /></el-icon></a>
            <a class="oa oa-wecom" title="企业微信登录">
              <svg viewBox="0 0 24 24" width="20" height="20"><path fill="#fff" d="M5 7h14a1 1 0 0 1 1 1v8a1 1 0 0 1-1 1h-5l-3 3-3-3H5a1 1 0 0 1-1-1V8a1 1 0 0 1 1-1zm2 2v2h4V9H7zm6 0v2h4V9h-4zM7 13v2h4v-2H7zm6 0v2h4v-2h-4z"/></svg>
            </a>
          </div>
        </div>

        <div class="footer-tip">
          <span @click="onForgot">忘记密码？</span>
          <i class="ft-sep">·</i>
          <span @click="router.push('/privacy')">隐私政策</span>
        </div>
      </div>

      <!-- 登录页也需展示备案信息（合规要求：网站底部可见） -->
      <SiteFooter plain class="login-footer" />
    </main>

    <!-- 点登录后弹出的滑块验证码 -->
    <div v-if="captchaOpen" class="captcha-mask" @click.self="onCaptchaClose">
      <div class="captcha-pop">
        <SlideCaptcha :key="captchaNonce" @success="onSlideSuccess" @close="onCaptchaClose" />
      </div>
    </div>
  </div>
</template>

<style scoped>
/* ============== 双栏布局 ============== */
.login-page {
  position: relative;            /* 粒子画布的定位基准 */
  height: 100vh; display: flex;
  background: #fff;
  overflow: hidden;
}

/* ---- 左栏：浅蓝渐变 + 3D 插画 ---- */
.login-hero {
  position: relative; flex: 0 0 45%; max-width: 45%;
  /* 刻意不设 z-index：粒子画布(z-index:1)覆盖全页、在左栏上方飘动是预期视觉（2026-09-21 确认，
     勿按「画布在内容层之下」的旧注释抬高本栏，否则左栏粒子被背景挡没） */
  overflow: hidden;
  background: linear-gradient(160deg, #d6e4ff 0%, #eaf1ff 35%, #f4f8ff 70%, #f9fbff 100%);
}
.hero-bg {
  position: absolute; inset: 0; pointer-events: none;
  background:
    radial-gradient(60% 50% at 20% 30%, rgba(255,255,255,.85), rgba(255,255,255,0) 60%),
    radial-gradient(50% 40% at 85% 80%, rgba(191,210,255,.55), rgba(191,210,255,0) 70%);
}
/* 粒子连线画布：铺满整页，z-index:1 介于背景与内容之间——左栏 hero 刻意不设 z-index 让粒子在其上飘动（预期视觉），
   仅右侧 .login-main 抬到 z-index:2 保持正文输入区在粒子之下不被遮挡 */
.particles-canvas {
  position: absolute; inset: 0; width: 100%; height: 100%;
  z-index: 1; pointer-events: none;
}
.hero-scene {
  position: absolute; inset: 0;
  display: grid; place-items: center;
  --rx: 0deg; --ry: 0deg; --tx: 0px; --ty: 0px;
  transform: perspective(1200px) rotateX(var(--rx)) rotateY(var(--ry));
  transition: transform .4s cubic-bezier(.2,.8,.2,1);
}
.hero-3d {
  width: min(92%, 540px); height: auto;
  transform: translate(var(--tx), var(--ty));
  transition: transform .25s ease;
  filter: drop-shadow(0 30px 40px rgba(64,102,196,.25));
}

/* ---- 左栏底部二维码 ---- */
.hero-qr {
  position: absolute; left: 36px; bottom: 32px;
  display: flex; gap: 22px;
  z-index: 2;
}
.qr-item { display: flex; flex-direction: column; align-items: center; gap: 8px; }
.qr-box {
  position: relative;
  width: 96px; height: 96px; padding: 6px;
  background: #fff; border-radius: 8px;
  box-shadow: 0 8px 18px -8px rgba(28,52,108,.25), 0 0 0 1px rgba(28,52,108,.04);
}
.qr-real { width: 100%; height: 100%; display: block; }
.qr-real :deep(svg) { width: 100%; height: 100%; display: block; }
.qr-img { width: 100%; height: 100%; object-fit: contain; display: block; border-radius: 4px; }
.qr-badge {
  position: absolute; left: 50%; top: 50%;
  transform: translate(-50%, -50%);
  width: 22px; height: 22px; border-radius: 5px;
  display: grid; place-items: center;
  color: #fff; font-size: 13px; font-weight: 700;
  box-shadow: 0 0 0 2px #fff;
}
.qr-badge.wx  { background: #22c55e; }
.qr-badge.sup { background: #3b82f6; }
.qr-empty { height: 100%; display: grid; place-items: center; color: #94a3b8; font-size: 12px; background: #f8fafc; border-radius: 4px; }
.qr-cap { font-size: 12px; color: #6b7a99; }

/* ---- 右栏：登录卡 ---- */
.login-main {
  flex: 1; min-width: 0; position: relative; z-index: 2;
  /* 纵向排列：登录卡片在上，备案页脚在下（原为横向 flex，页脚会被挤到卡片右侧） */
  display: flex; flex-direction: column; align-items: center; justify-content: center;
  /* 右侧不再纯白：两团极淡的冷色光斑 + 微渐变，与左栏呼应（F：登录页右侧太白） */
  background:
    radial-gradient(46% 38% at 86% 12%, rgba(210,226,255,.5), rgba(210,226,255,0) 70%),
    radial-gradient(42% 36% at 12% 92%, rgba(214,230,255,.45), rgba(214,230,255,0) 70%),
    linear-gradient(180deg, #fdfeff 0%, #f6f9ff 100%);
  padding: 40px;
}
/* 登录页页脚：与卡片同宽、视觉弱化 */
.login-footer { width: 360px; max-width: 100%; margin-top: 18px; border-top: none; padding: 0; }
.lang-switch { position: absolute; top: 22px; right: 26px; }
.lang-trigger {
  display: inline-flex; align-items: center; gap: 6px;
  padding: 5px 12px; border-radius: 6px;
  background: #fff; border: 1px solid #e5e9f2;
  font-size: 13px; color: #5b6478; cursor: pointer;
  transition: all .2s;
}
.lang-trigger:hover { border-color: #c7d0e3; background: #f7f9fd; }

.login-card-wrap {
  /* 上下 auto 外边距把剩余空间均分：卡片视觉居中，
     卡片下方的 auto 空间同时把页脚顶到栏底（内容不足一屏时页脚贴底） */
  margin-top: auto; margin-bottom: auto;
  width: 360px; max-width: 100%; min-width: 0;
  padding: 32px 32px 28px;
  background: #fff; border-radius: 12px;
  box-shadow: 0 14px 38px -16px rgba(28,52,108,.18), 0 0 0 1px rgba(28,52,108,.04);
}
.welcome-title {
  margin: 0 0 18px; font-size: 18px; font-weight: 600;
  color: #1e2a3f; text-align: center;
}

/* ---- Tab ---- */
.tabs { display: flex; gap: 24px; margin: 0 0 22px; padding: 0 4px; border-bottom: 1px solid #eef1f6; }
.tab {
  position: relative; padding: 0 2px 12px;
  font-size: 14px; color: #8b95ac; cursor: default;
  transition: color .2s;
}
.tab.active { color: #3b6fe0; font-weight: 600; }
.tab.active::after {
  content: ''; position: absolute; left: 0; right: 0; bottom: -1px; height: 2px;
  background: #3b6fe0; border-radius: 2px;
}

/* ---- 表单 ---- */
.login-form { display: flex; flex-direction: column; gap: 14px; }
.login-input :deep(.el-input__wrapper) {
  border-radius: 6px !important; background: #f5f7fb !important;
  box-shadow: 0 0 0 1px #e1e6f0 inset !important;
  padding: 4px 12px;
  transition: all .2s;
}
.login-input :deep(.el-input__wrapper:hover) { box-shadow: 0 0 0 1px #c7d0e3 inset !important; }
.login-input :deep(.el-input__wrapper.is-focus) {
  box-shadow: 0 0 0 1px #3b6fe0 inset, 0 0 0 3px rgba(59,111,224,.12) !important;
  background: #fff !important;
}
.login-input :deep(.el-input__inner) { color: #1e2a3f; }

.captcha-state { font-size: 12px; color: #94a3b8; text-align: left; }
.captcha-state.passed { color: #22c55e; font-weight: 600; }

.login-submit {
  margin-top: 4px; height: 42px; font-size: 14px; letter-spacing: 2px;
  background: #3b6fe0 !important; border-color: #3b6fe0 !important;
  border-radius: 6px;
}
.login-submit:hover { background: #2e5fc8 !important; border-color: #2e5fc8 !important; }

/* ---- 其他登录方式 ---- */
.other-login { margin-top: 18px; }
.divider {
  display: flex; align-items: center; gap: 12px;
  font-size: 12px; color: #9aa5bd;
  margin-bottom: 14px;
}
.divider::before, .divider::after {
  content: ''; flex: 1; height: 1px; background: #eef1f6;
}
.other-icons { display: flex; justify-content: center; gap: 14px; }
.oa {
  width: 34px; height: 34px; border-radius: 8px;
  display: grid; place-items: center; cursor: pointer;
  color: #fff; font-size: 18px;
  transition: transform .2s, box-shadow .2s;
}
.oa:hover { transform: translateY(-2px); }
.oa-wechat { background: #07c160; box-shadow: 0 6px 14px -4px rgba(7,193,96,.45); }
.oa-qq     { background: #12B7F5; box-shadow: 0 6px 14px -4px rgba(18,183,245,.45); }
.oa-wecom  { background: #2f87f5; box-shadow: 0 6px 14px -4px rgba(47,135,245,.45); }

.footer-tip { margin-top: 18px; text-align: center; font-size: 12px; color: #9aa5bd; }
.footer-tip span { cursor: pointer; color: #6b7a99; }
.footer-tip span:hover { color: #3b6fe0; text-decoration: underline; }
.footer-tip .ft-sep { margin: 0 6px; color: #c3ccdd; font-style: normal; }

/* ---- 滑块验证码弹窗 ---- */
.captcha-mask {
  position: fixed; inset: 0; z-index: 1000;
  background: rgba(15,23,42,0.45);
  display: flex; align-items: center; justify-content: center;
}
.captcha-pop { width: 348px; }

/* ============== 暗色 ============== */
html.dark .login-hero {
  background: linear-gradient(160deg, #0f1a3a 0%, #15224a 50%, #0c1530 100%);
}
html.dark .hero-bg {
  background:
    radial-gradient(60% 50% at 20% 30%, rgba(80,120,220,.22), rgba(80,120,220,0) 60%),
    radial-gradient(50% 40% at 85% 80%, rgba(40,80,180,.25), rgba(40,80,180,0) 70%);
}
html.dark .hero-3d { filter: drop-shadow(0 30px 40px rgba(0,0,0,.55)) brightness(1.05); }
/* 高光圆斑在暗色下会把白斑映得很突兀：换成淡蓝微光并大幅压暗 */
html.dark .glow-orb { opacity: .16; }
html.dark #glowG stop { stop-color: rgba(126,170,255,.6); }
html.dark #glowG stop:last-child { stop-color: rgba(126,170,255,0); }
html.dark .qr-box { background: #f4f6fb; }
html.dark .qr-cap { color: #8ea0c5; }

html.dark .login-main {
  background:
    radial-gradient(46% 38% at 86% 12%, rgba(44,68,120,.35), rgba(44,68,120,0) 70%),
    radial-gradient(42% 36% at 12% 92%, rgba(36,54,96,.3), rgba(36,54,96,0) 70%),
    #0c1428;
}
html.dark .lang-trigger { background: #15203a; border-color: #2a3858; color: #b9c4dc; }
html.dark .lang-trigger:hover { background: #1c2a48; }
html.dark .login-card-wrap {
  background: #14213b; border: 1px solid #1f2d4d;
  box-shadow: 0 14px 38px -16px rgba(0,0,0,.7), 0 0 0 1px rgba(255,255,255,.03);
}
html.dark .welcome-title { color: #e6ecf7; }
html.dark .tabs { border-bottom-color: #1f2d4d; }
html.dark .tab { color: #6e7a96; }
html.dark .tab.active { color: #6ea0ff; }
html.dark .tab.active::after { background: #6ea0ff; }
html.dark .login-input :deep(.el-input__wrapper) {
  background: #0e1830 !important; box-shadow: 0 0 0 1px #25344f inset !important;
}
html.dark .login-input :deep(.el-input__wrapper:hover) { box-shadow: 0 0 0 1px #324367 inset !important; }
html.dark .login-input :deep(.el-input__wrapper.is-focus) {
  box-shadow: 0 0 0 1px #4a78c8 inset, 0 0 0 3px rgba(74,120,200,.18) !important;
  background: #12203c !important;
}
html.dark .login-input :deep(.el-input__inner) { color: #e6ecf7; }
html.dark .login-submit { background: #2f6fd6 !important; border-color: #2f6fd6 !important; }
html.dark .login-submit:hover { background: #4a85e6 !important; border-color: #4a85e6 !important; }
html.dark .divider { color: #6e7a96; }
html.dark .divider::before, html.dark .divider::after { background: #1f2d4d; }
html.dark .footer-tip span { color: #8ea0c5; }

/* ============== 响应式 ============== */
@media (max-width: 1024px) {
  .login-hero { flex-basis: 42%; max-width: 42%; }
  .hero-qr { left: 22px; bottom: 22px; gap: 16px; }
  .qr-box { width: 80px; height: 80px; }
  .login-main { padding: 32px 24px; box-sizing: border-box; }
  .login-card-wrap { max-width: 360px; min-width: 0; }
}
@media (max-width: 768px) {
  .login-page { flex-direction: column; height: auto; min-height: 100vh; min-height: 100dvh; overflow-x: hidden; }
  .login-hero { flex-basis: auto; max-width: 100%; height: 200px; }
  .hero-3d { width: min(62%, 300px); }
  /* 手机端隐藏二维码：避免叠在插画上，也符合移动端登录页惯例 */
  .hero-qr { display: none; }
  .login-main { padding: 28px 16px 40px; width: 100%; box-sizing: border-box; }
  /* App 全面屏底部安全区（页脚不被手势条压住） */
  .login-main { padding-bottom: calc(env(safe-area-inset-bottom) + 40px); }
  .login-card-wrap { width: 100%; max-width: 380px; min-width: 0; padding: 22px 20px; }
  .login-form { min-width: 0; }
  .lang-switch { top: 14px; right: 14px; }
  /* 16px 起：低于该值 iOS Safari/WebView 聚焦会强制放大页面 */
  .login-input :deep(.el-input__inner) { font-size: 16px !important; }
  .login-submit { height: 44px; }
  .login-footer { margin-top: 14px; }
}
@media (max-width: 640px) {
  .captcha-pop { width: calc(100vw - 24px); max-width: 348px; }
  .captcha-pop :deep(.slide-captcha) { width: 100%; max-width: 100%; padding: 12px 12px 10px; }
  .captcha-pop :deep(.sc-stage) { width: 100%; max-width: 320px; height: 160px; }
  .captcha-pop :deep(.sc-bg) { width: 100%; height: 100%; }
  .captcha-pop :deep(.sc-bar) { width: 100%; max-width: 320px; }
}
</style>
