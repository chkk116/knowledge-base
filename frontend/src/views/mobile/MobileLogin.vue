<script setup>
/**
 * 移动端登录页（v2：完整对齐 Web 登录页设计语言，2026-10-05 用户确认「Web 版就行，再优化」）
 *
 * 与 Web 版（Login.vue）一致的部分：
 *   - 粒子连线动画铺满整页（移动端无鼠标：去掉吸引/光标连线，纯漂移；prefers-reduced-motion 只画静态一帧）
 *   - 顶部浅蓝渐变 hero + 缩小版 3D 插画（笔记本/平板/服务器）
 *   - 白卡 + 「欢迎登录」+ Tab「密码登录」底线样式
 *   - 输入框浅灰底 + 细边框，聚焦 #3b6fe0 描边 + 3px 光晕
 *   - 主按钮 #3b6fe0、字距 2px；「其他登录方式」分隔线 + 三图标；「忘记密码？」弹窗
 *   - 暗色模式完整适配（#0c1428 系）
 *
 * 移动端专属优化：
 *   - 输入框 font-size 16px（防 iOS 聚焦自动放大）
 *   - 高度用 100dvh + 安全区 padding；hero 高度 clamp 自适应小屏
 *   - 触屏无 hover 态，交互反馈改为 active 按压
 * 功能不变：滑动验证码复用同一组件（底部弹层）、无注册入口。
 */
import { ref, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../../store/auth'
import SlideCaptcha from '../../components/SlideCaptcha.vue'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const username = ref('')
const password = ref('')
const captchaToken = ref('')
const showCaptcha = ref(false)
const busy = ref(false)
const errMsg = ref('')
const showPwd = ref(false)

function startLogin() {
  errMsg.value = ''
  if (!username.value.trim()) { errMsg.value = '请输入用户名'; return }
  if (!password.value) { errMsg.value = '请输入密码'; return }
  if (!captchaToken.value) { showCaptcha.value = true; return }
  doLogin()
}

async function doLogin() {
  busy.value = true
  errMsg.value = ''
  try {
    await auth.login(username.value.trim(), password.value, captchaToken.value)
    const to = route.query.redirect && String(route.query.redirect).startsWith('/m')
      ? String(route.query.redirect) : '/home'
    router.replace(to)
  } catch (e) {
    errMsg.value = (e && (e.biz ? e.message : (e.response?.data?.error || e.message))) || '登录失败'
    // 票据一次性：失败后要重新验证
    captchaToken.value = ''
    showCaptcha.value = false
  } finally {
    busy.value = false
  }
}

function onCaptchaOk(token) {
  captchaToken.value = token
  showCaptcha.value = false
  if (username.value.trim() && password.value) doLogin()
}

function onForgot() {
  window.__kbMsgBox?.alert('请联系系统管理员重置密码。', '忘记密码', { confirmButtonText: '知道了' })
}

// ---------- 粒子连线动画（Web 同款，移动端纯漂移版） ----------
const particleCanvas = ref(null)
let particleRaf = 0
let particleCleanup = null

function startParticles() {
  const canvas = particleCanvas.value
  if (!canvas) return
  const ctx = canvas.getContext('2d')
  if (!ctx) return
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

  const count = Math.max(26, Math.min(70, Math.round(w * h / 12000)))
  const rnd = (a, b) => a + Math.random() * (b - a)
  const pts = Array.from({ length: count }, () => {
    const bvx = rnd(-0.5, 0.5), bvy = rnd(-0.5, 0.5)
    return { x: rnd(0, w), y: rnd(0, h), vx: bvx, vy: bvy, bvx, bvy, r: rnd(1.1, 2.1) }
  })

  const LINK = 110
  const isDark = () => document.documentElement.classList.contains('dark')

  function drawFrame() {
    const dark = isDark()
    const dotBase = dark ? '147, 180, 255' : '59, 110, 230'
    const lineBase = dark ? '129, 150, 255' : '79, 70, 229'
    ctx.clearRect(0, 0, w, h)
    for (const p of pts) {
      if (!reduced) {
        p.vx *= 0.985; p.vy *= 0.985
        const sp = Math.hypot(p.vx, p.vy)
        if (sp > 2.2) { p.vx = p.vx / sp * 2.2; p.vy = p.vy / sp * 2.2 }
        if (sp < 0.12) { p.vx += (p.bvx - p.vx) * 0.03; p.vy += (p.bvy - p.vy) * 0.03 }
        p.x += p.vx; p.y += p.vy
        if (p.x < -10) p.x = w + 10; else if (p.x > w + 10) p.x = -10
        if (p.y < -10) p.y = h + 10; else if (p.y > h + 10) p.y = -10
      }
      ctx.beginPath()
      ctx.arc(p.x, p.y, dark ? p.r * 0.7 : p.r, 0, Math.PI * 2)
      ctx.fillStyle = `rgba(${dotBase}, ${dark ? 0.32 : 0.5})`
      ctx.fill()
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

  if (reduced) drawFrame()
  else {
    const loop = () => { drawFrame(); particleRaf = requestAnimationFrame(loop) }
    particleRaf = requestAnimationFrame(loop)
  }

  particleCleanup = () => {
    window.removeEventListener('resize', onResize)
    if (particleRaf) cancelAnimationFrame(particleRaf)
    particleRaf = 0
  }
}
onMounted(() => { startParticles() })
onUnmounted(() => { if (particleCleanup) particleCleanup() })
</script>

<template>
  <div class="login">
    <!-- 粒子连线动画：铺满整页（卡片抬到 z-index:2 之上） -->
    <canvas ref="particleCanvas" class="particles" aria-hidden="true"></canvas>

    <!-- 顶部：浅蓝渐变 hero + 缩小版 3D 插画（Web 同款） -->
    <div class="hero">
      <div class="hero-bg"></div>
      <svg viewBox="0 0 520 360" class="hero-3d" preserveAspectRatio="xMidYMid meet" aria-hidden="true">
        <defs>
          <linearGradient id="mScreenG" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0" stop-color="#cfe1ff"/><stop offset="1" stop-color="#9bbcff"/>
          </linearGradient>
          <linearGradient id="mLaptopG" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0" stop-color="#eef4ff"/><stop offset="1" stop-color="#c8d6f4"/>
          </linearGradient>
          <linearGradient id="mCardG" x1="0" y1="0" x2="1" y2="1">
            <stop offset="0" stop-color="#ffffff"/><stop offset="1" stop-color="#e0eaff"/>
          </linearGradient>
          <linearGradient id="mServerG" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0" stop-color="#5fa1ff"/><stop offset="1" stop-color="#2f6fe6"/>
          </linearGradient>
          <linearGradient id="mTabletG" x1="0" y1="0" x2="1" y2="1">
            <stop offset="0" stop-color="#7cb0ff"/><stop offset="1" stop-color="#4f86e6"/>
          </linearGradient>
        </defs>
        <ellipse cx="260" cy="295" rx="200" ry="22" fill="#bcd3ff" opacity=".55"/>
        <g transform="translate(60 110)">
          <path d="M0 110 L180 90 L180 0 L0 20 Z" fill="url(#mLaptopG)" stroke="#a8c0e8" stroke-width="1"/>
          <path d="M14 102 L166 84 L166 14 L14 32 Z" fill="url(#mScreenG)"/>
          <g transform="translate(28 36)">
            <rect width="48" height="36" rx="3" fill="url(#mCardG)" stroke="#bcd2f0" stroke-width=".6"/>
            <rect x="56" width="62" height="36" rx="3" fill="url(#mCardG)" stroke="#bcd2f0" stroke-width=".6"/>
            <circle cx="68" cy="20" r="6" fill="#3b82f6"/>
            <rect x="80" y="14" width="32" height="3" rx="1" fill="#7da3e6"/>
            <rect x="14" y="6" width="22" height="3" rx="1" fill="#7da3e6"/>
            <rect x="14" y="14" width="34" height="2.5" rx="1" fill="#a8c0e8"/>
            <circle cx="86" cy="58" r="8" fill="#3b82f6"/>
            <rect x="60" y="50" width="20" height="3" rx="1" fill="#7da3e6"/>
            <rect x="60" y="58" width="28" height="2.5" rx="1" fill="#a8c0e8"/>
          </g>
          <path d="M-10 110 L190 90 L210 110 L10 132 Z" fill="#dee8f7" stroke="#a8c0e8" stroke-width="1"/>
        </g>
        <g transform="translate(248 138)">
          <rect width="92" height="118" rx="6" fill="url(#mTabletG)" stroke="#3978e6" stroke-width="1"/>
          <rect x="6" y="6" width="80" height="106" rx="3" fill="#eef4ff"/>
          <g transform="translate(14 14)">
            <circle cx="6" cy="6" r="6" fill="#3b82f6"/>
            <rect x="18" y="3" width="48" height="3" rx="1" fill="#5a86cc"/>
            <rect x="18" y="10" width="32" height="2.5" rx="1" fill="#9bb8e8"/>
            <circle cx="6" cy="28" r="6" fill="#7cb0ff"/>
            <rect x="18" y="25" width="44" height="3" rx="1" fill="#5a86cc"/>
            <rect x="18" y="32" width="36" height="2.5" rx="1" fill="#9bb8e8"/>
          </g>
        </g>
        <g transform="translate(140 220)">
          <path d="M0 24 L72 0 L144 24 L72 48 Z" fill="url(#mServerG)" stroke="#2a5fcc" stroke-width=".8"/>
          <path d="M0 24 L0 84 L72 108 L72 48 Z" fill="#4f86e6" stroke="#2a5fcc" stroke-width=".8"/>
          <path d="M72 48 L72 108 L144 84 L144 24 Z" fill="#2f6fe6" stroke="#2a5fcc" stroke-width=".8"/>
          <g transform="translate(36 12)">
            <rect width="72" height="4" rx="1" fill="#1d4fa6" opacity=".4"/>
            <rect y="6" width="72" height="4" rx="1" fill="#1d4fa6" opacity=".4"/>
            <rect y="12" width="72" height="4" rx="1" fill="#1d4fa6" opacity=".4"/>
          </g>
          <g transform="translate(8 50)">
            <circle cx="0" cy="0" r="2.5" fill="#7fffd4"/>
            <circle cx="10" cy="2" r="2.5" fill="#ffd166"/>
            <circle cx="20" cy="4" r="2.5" fill="#7fffd4"/>
          </g>
        </g>
      </svg>
    </div>

    <!-- 登录卡：Web 同款白卡 + Tab + 表单 + 其他登录方式 -->
    <div class="login-main">
      <div class="card">
        <h1 class="welcome">欢迎登录</h1>
        <div class="tabs"><span class="tab active">密码登录</span></div>

        <div class="form">
          <label class="fld">
            <el-icon class="fld-ico"><User /></el-icon>
            <input v-model="username" type="text" placeholder="请输入账号" autocomplete="username"
                   @keyup.enter="startLogin" />
          </label>
          <label class="fld">
            <el-icon class="fld-ico"><Lock /></el-icon>
            <input v-model="password" :type="showPwd ? 'text' : 'password'" placeholder="请输入密码"
                   autocomplete="current-password" @keyup.enter="startLogin" />
            <button class="fld-eye" type="button" @click="showPwd = !showPwd">
              <el-icon><component :is="showPwd ? 'View' : 'Close'" /></el-icon>
            </button>
          </label>

          <div v-if="errMsg" class="err">
            <el-icon><WarningFilled /></el-icon>{{ errMsg }}
          </div>

          <button class="m-btn login-btn" :disabled="busy" @click="startLogin">
            <el-icon v-if="busy" class="rot"><Loading /></el-icon>
            {{ busy ? '登录中…' : '立即登录' }}
          </button>
        </div>

        <div class="divider"><span>其他登录方式</span></div>
        <div class="other-icons">
          <a class="oa oa-wechat" title="微信登录"><el-icon><ChatDotRound /></el-icon></a>
          <a class="oa oa-qq" title="QQ 登录"><el-icon><ChatLineRound /></el-icon></a>
          <a class="oa oa-wecom" title="企业微信登录">
            <svg viewBox="0 0 24 24" width="20" height="20"><path fill="#fff" d="M5 7h14a1 1 0 0 1 1 1v8a1 1 0 0 1-1 1h-5l-3 3-3-3H5a1 1 0 0 1-1-1V8a1 1 0 0 1 1-1zm2 2v2h4V9H7zm6 0v2h4V9h-4zM7 13v2h4v-2H7zm6 0v2h4v-2h-4z"/></svg>
          </a>
        </div>

        <div class="footer-tip"><span @click="onForgot">忘记密码？</span></div>
      </div>

      <div class="page-tip">账号由管理员开通，忘记密码请联系管理员</div>
    </div>

    <!-- 滑动验证码弹层 -->
    <transition name="sheet-up">
      <div v-if="showCaptcha" class="m-sheet-mask" @click.self="showCaptcha = false">
        <div class="m-sheet captcha-sheet">
          <div class="m-sheet-grip"></div>
          <div class="m-sheet-title">完成安全验证</div>
          <SlideCaptcha @success="onCaptchaOk" @close="showCaptcha = false" />
        </div>
      </div>
    </transition>
  </div>
</template>

<style scoped>
/* ===== 页面基底：粒子画布铺满，hero + 卡片区抬到其上 ===== */
.login {
  position: relative;
  min-height: 100vh; min-height: 100dvh;
  background: linear-gradient(180deg, #fdfeff 0%, #f6f9ff 100%);
  padding-bottom: calc(env(safe-area-inset-bottom) + 26px);
  box-sizing: border-box;
  overflow: hidden;
}
.particles {
  position: absolute; inset: 0; width: 100%; height: 100%;
  z-index: 1; pointer-events: none;
}

/* ===== 顶部 hero：浅蓝渐变 + 3D 插画（Web 同款缩放） ===== */
.hero {
  position: relative; z-index: 2;
  height: clamp(150px, 24vh, 230px);
  overflow: hidden;
  background: linear-gradient(160deg, #d6e4ff 0%, #eaf1ff 40%, #f4f8ff 100%);
}
.hero-bg {
  position: absolute; inset: 0; pointer-events: none;
  background:
    radial-gradient(60% 60% at 20% 35%, rgba(255,255,255,.85), rgba(255,255,255,0) 60%),
    radial-gradient(55% 45% at 85% 85%, rgba(191,210,255,.5), rgba(191,210,255,0) 70%);
}
.hero-3d {
  position: absolute; inset: 0;
  width: min(78%, 400px); height: 100%;
  margin: 0 auto;
  filter: drop-shadow(0 18px 26px rgba(64,102,196,.22));
}

/* ===== 登录卡：Web 同款白卡 ===== */
.login-main {
  position: relative; z-index: 2;
  padding: 18px 18px 0;
  display: flex; flex-direction: column; align-items: center;
}
.card {
  width: 100%; max-width: 380px; min-width: 0;
  background: #fff; border-radius: 12px;
  box-shadow: 0 14px 38px -16px rgba(28,52,108,.18), 0 0 0 1px rgba(28,52,108,.04);
  padding: 22px 20px 24px;
  box-sizing: border-box;
}
.welcome {
  margin: 0 0 14px; font-size: 18px; font-weight: 600;
  color: #1e2a3f; text-align: center; letter-spacing: .5px;
}

/* Tab：Web 同款底线样式 */
.tabs {
  display: flex; gap: 24px; margin: 0 0 18px;
  padding: 0 2px; border-bottom: 1px solid #eef1f6;
}
.tab {
  position: relative; padding: 0 2px 10px;
  font-size: 14px; color: #8b95ac;
}
.tab.active { color: #3b6fe0; font-weight: 600; }
.tab.active::after {
  content: ''; position: absolute; left: 0; right: 0; bottom: -1px; height: 2px;
  background: #3b6fe0; border-radius: 2px;
}

/* 表单：浅灰底 + 细边框，聚焦 #3b6fe0 描边 + 3px 光晕（Web 同款） */
.form { display: flex; flex-direction: column; }
.fld {
  display: flex; align-items: center; gap: 10px;
  height: 48px; padding: 0 13px; margin-bottom: 12px;
  background: #f5f7fb;
  border-radius: 6px;
  border: none; box-shadow: 0 0 0 1px #e1e6f0 inset;
  transition: box-shadow .2s, background-color .2s;
}
.fld:focus-within {
  box-shadow: 0 0 0 1px #3b6fe0 inset, 0 0 0 3px rgba(59,111,224,.12);
  background: #fff;
}
.fld-ico { color: #8b95ac; flex: none; font-size: 16px; }
.fld input {
  flex: 1 1 auto; min-width: 0;
  border: none; outline: none; background: transparent;
  /* 16px 起：低于该值 iOS 聚焦会强制放大页面 */
  font-size: 16px; color: #1e2a3f; font-family: inherit;
  height: 100%;
}
.fld input::placeholder { color: #9aa5bd; }
.fld-eye {
  border: none; background: transparent; color: #8b95ac;
  padding: 6px; cursor: pointer; display: inline-flex;
}
.fld-eye:active { color: #3b6fe0; }

.err {
  display: flex; align-items: center; gap: 6px;
  color: #dc2626; font-size: 13px; margin: 2px 2px 10px;
}

/* 主按钮：Web 同款实心蓝 + 字距 */
.login-btn {
  margin-top: 4px; height: 46px; font-size: 15px; letter-spacing: 2px;
  background: #3b6fe0; border: none; color: #fff;
  border-radius: 6px;
  transition: background .2s;
}
.login-btn:active { background: #2e5fc8; }
.login-btn:disabled { opacity: .72; }
.rot { animation: kb-rot 1s linear infinite; }
@keyframes kb-rot { to { transform: rotate(360deg); } }

/* 其他登录方式：Web 同款分隔线 + 三色图标 */
.divider {
  display: flex; align-items: center; gap: 12px;
  font-size: 12px; color: #9aa5bd;
  margin-top: 18px; margin-bottom: 12px;
}
.divider::before, .divider::after { content: ''; flex: 1; height: 1px; background: #eef1f6; }
.other-icons { display: flex; justify-content: center; gap: 14px; }
.oa {
  width: 36px; height: 36px; border-radius: 8px;
  display: grid; place-items: center; cursor: pointer;
  color: #fff; font-size: 18px;
}
.oa:active { transform: scale(.94); }
.oa-wechat { background: #07c160; box-shadow: 0 6px 14px -4px rgba(7,193,96,.45); }
.oa-qq     { background: #12B7F5; box-shadow: 0 6px 14px -4px rgba(18,183,245,.45); }
.oa-wecom  { background: #2f87f5; box-shadow: 0 6px 14px -4px rgba(47,135,245,.45); }

.footer-tip { margin-top: 16px; text-align: center; font-size: 12px; }
.footer-tip span { cursor: pointer; color: #6b7a99; }
.footer-tip span:active { color: #3b6fe0; }

.page-tip { margin-top: 16px; text-align: center; font-size: 12px; color: #9aa5bd; }

.captcha-sheet { padding-bottom: calc(env(safe-area-inset-bottom) + 18px); }

/* ===== 暗色：Web 登录页同款深蓝系 ===== */
html.dark .login { background: #0c1428; }
html.dark .hero {
  background: linear-gradient(160deg, #0f1a3a 0%, #15224a 55%, #0c1530 100%);
}
html.dark .hero-bg {
  background:
    radial-gradient(60% 60% at 20% 35%, rgba(80,120,220,.22), rgba(80,120,220,0) 60%),
    radial-gradient(55% 45% at 85% 85%, rgba(40,80,180,.25), rgba(40,80,180,0) 70%);
}
html.dark .hero-3d { filter: drop-shadow(0 18px 26px rgba(0,0,0,.55)) brightness(1.05); }
html.dark .card {
  background: #14213b; border: 1px solid #1f2d4d;
  box-shadow: 0 14px 38px -16px rgba(0,0,0,.7);
}
html.dark .welcome { color: #e6ecf7; }
html.dark .tabs { border-bottom-color: #1f2d4d; }
html.dark .tab { color: #6e7a96; }
html.dark .tab.active { color: #6ea0ff; }
html.dark .tab.active::after { background: #6ea0ff; }
html.dark .fld { background: #0e1830; box-shadow: 0 0 0 1px #25344f inset; }
html.dark .fld:focus-within {
  box-shadow: 0 0 0 1px #4a78c8 inset, 0 0 0 3px rgba(74,120,200,.18);
  background: #12203c;
}
html.dark .fld-ico { color: #6e7a96; }
html.dark .fld input { color: #e6ecf7; }
html.dark .fld input::placeholder { color: #5d6985; }
html.dark .login-btn { background: #2f6fd6; }
html.dark .login-btn:active { background: #4a85e6; }
html.dark .divider { color: #6e7a96; }
html.dark .divider::before, html.dark .divider::after { background: #1f2d4d; }
html.dark .footer-tip span { color: #8ea0c5; }
html.dark .page-tip { color: #5d6985; }

/* ===== 小屏收敛 ===== */
@media (max-width: 350px) {
  .hero { height: 130px; }
  .card { padding: 18px 14px 20px; }
}
</style>
