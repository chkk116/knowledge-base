<script setup>
/**
 * 移动端登录页 v3（2026-10-06）：与 Web 版「同设计语言、不同构图」
 *
 * 与 Web 版的相似感（设计语言一致）：
 *   - 粒子连线动画铺满整页（移动端纯漂移，无鼠标吸引）
 *   - 同一 3D 等距插画（笔记本/平板/服务器）做品牌符号
 *   - 同一套蓝色系（#3b6fe0 族）、同样的「其他登录方式」三图标与忘记密码弹窗
 * 与 Web 版的差异（移动端自己的构图）：
 *   - 不要白色大卡片：表单直接落在柔和渐变底上，输入框用玻璃拟态胶囊（圆角全圆 + 背景模糊）
 *   - 主按钮用品牌渐变胶囊，文案「登 录」大字距（web 是方正实心蓝块）
 *   - 标题左对齐「欢迎回来」+ 轻副标题（web 是居中「欢迎登录」+ Tab）
 *   - 顶部只保留小尺寸插画 + 应用名，给表单留足纵向空间
 * 功能不变：滑动验证码底部弹层、16px 输入框防 iOS 缩放、safe-area、100dvh、暗色模式。
 */
import { ref, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../../store/auth'
import SlideCaptcha from '../../components/SlideCaptcha.vue'
import SiteFooter from '../../components/SiteFooter.vue'

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
  // 与桌面端保持一致：管理员在「用户管理」生成一次性重置链接（30 分钟有效），或后台配好 SMTP 后由邮件发送
  window.__kbMsgBox?.alert(
    '请联系管理员在「用户管理」中为你生成一次性重置链接（30 分钟内有效），打开链接即可自己设置新密码。',
    '忘记密码',
    { confirmButtonText: '知道了' }
  )
}

// ---------- 粒子连线动画（移动端漂移版；prefers-reduced-motion 只画静态一帧） ----------
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
    <canvas ref="particleCanvas" class="particles" aria-hidden="true"></canvas>

    <!-- 顶部：小尺寸品牌区（插画 + 应用名），构图与 Web 的「左半屏大插画」区分开 -->
    <div class="brand">
      <svg viewBox="0 0 520 360" class="brand-3d" preserveAspectRatio="xMidYMid meet" aria-hidden="true">
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
      <div class="brand-name">知识库</div>
    </div>

    <!-- 表单区：无卡片、玻璃胶囊输入、渐变主按钮 -->
    <div class="zone">
      <h2 class="hi">欢迎回来</h2>
      <p class="sub">登录你的账号，继续沉淀知识</p>

      <label class="pill">
        <el-icon class="p-ico"><User /></el-icon>
        <input v-model="username" type="text" placeholder="请输入账号" autocomplete="username"
               @keyup.enter="startLogin" />
      </label>
      <label class="pill">
        <el-icon class="p-ico"><Lock /></el-icon>
        <input v-model="password" :type="showPwd ? 'text' : 'password'" placeholder="请输入密码"
               autocomplete="current-password" @keyup.enter="startLogin" />
        <button class="p-eye" type="button" @click="showPwd = !showPwd">
          <el-icon><component :is="showPwd ? 'View' : 'Close'" /></el-icon>
        </button>
      </label>

      <div v-if="errMsg" class="err">
        <el-icon><WarningFilled /></el-icon>{{ errMsg }}
      </div>

      <button class="cta" :disabled="busy" @click="startLogin">
        <el-icon v-if="busy" class="rot"><Loading /></el-icon>
        <span>{{ busy ? '登录中…' : '登  录' }}</span>
      </button>

      <div class="divider"><span>其他登录方式</span></div>
      <div class="socials">
        <a class="so so-wechat" title="微信登录"><el-icon><ChatDotRound /></el-icon></a>
        <a class="so so-qq" title="QQ 登录"><el-icon><ChatLineRound /></el-icon></a>
        <a class="so so-wecom" title="企业微信登录">
          <svg viewBox="0 0 24 24" width="19" height="19"><path fill="#fff" d="M5 7h14a1 1 0 0 1 1 1v8a1 1 0 0 1-1 1h-5l-3 3-3-3H5a1 1 0 0 1-1-1V8a1 1 0 0 1 1-1zm2 2v2h4V9H7zm6 0v2h4V9h-4zM7 13v2h4v-2H7zm6 0v2h4v-2h-4z"/></svg>
        </a>
      </div>
      <div class="forgot"><span @click="onForgot">忘记密码？</span></div>
    </div>

    <!-- 备案号统一展示：手机浏览器与 App 内都显示。
         工信部要求网站底部展示备案号；App 里多显示一次不算违规（少显示才有风险），
         用户也要求两端一致。 -->
    <SiteFooter compact />

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
.login {
  position: relative;
  min-height: 100vh; min-height: 100dvh;
  background: var(--m-bg);
  padding-bottom: calc(env(safe-area-inset-bottom) + 22px);
  box-sizing: border-box; overflow: hidden;
  display: flex; flex-direction: column;
}
.particles { position: absolute; inset: 0; width: 100%; height: 100%; z-index: 1; pointer-events: none; }

/* 品牌区：插画缩小并居中，应用名用渐变字 */
.brand { position: relative; z-index: 2; text-align: center; padding-top: clamp(14px, 4vh, 34px); }
.brand-3d {
  width: min(58%, 250px); display: block; margin: 0 auto;
  filter: drop-shadow(0 14px 20px rgba(64,102,196,.2));
}
.brand-name {
  margin-top: -6px; font-size: 21px; font-weight: 700; letter-spacing: 6px;
  color: var(--m-primary);
}

/* 表单区：左对齐标题 + 玻璃胶囊，构图与 Web 白卡明显区分 */
.zone { position: relative; z-index: 2; flex: 1 1 auto; padding: clamp(10px, 3vh, 26px) 26px 0; }
.hi { margin: 0; font-size: 25px; font-weight: 700; color: #1e2a3f; letter-spacing: 1px; }
.sub { margin: 6px 0 22px; font-size: 13.5px; color: #8b95ac; }

.pill {
  display: flex; align-items: center; gap: 11px;
  height: 52px; padding: 0 18px; margin-bottom: 14px;
  background: rgba(255, 255, 255, .72);
  backdrop-filter: blur(10px); -webkit-backdrop-filter: blur(10px);
  border-radius: 999px;
  box-shadow: 0 0 0 1px rgba(59, 111, 224, .1) inset, 0 8px 22px -12px rgba(28, 52, 108, .25);
  transition: box-shadow .2s, background-color .2s;
}
.pill:focus-within {
  background: rgba(255, 255, 255, .92);
  box-shadow: 0 0 0 1.5px #3b6fe0 inset, 0 0 0 4px rgba(59, 111, 224, .12);
}
.p-ico { color: #8b95ac; flex: none; font-size: 17px; }
.pill input {
  flex: 1 1 auto; min-width: 0; height: 100%;
  border: none; outline: none; background: transparent;
  /* 16px 起：低于该值 iOS 聚焦会强制放大页面 */
  font-size: 16px; color: #1e2a3f; font-family: inherit;
}
.pill input::placeholder { color: #9aa5bd; }
.p-eye { border: none; background: transparent; color: #8b95ac; padding: 6px; display: inline-flex; }
.p-eye:active { color: #3b6fe0; }

.err { display: flex; align-items: center; gap: 6px; color: #dc2626; font-size: 13px; margin: 0 6px 12px; }

/* 渐变胶囊主按钮（web 是方正实心蓝块，这里刻意不同） */
.cta {
  display: flex; align-items: center; justify-content: center; gap: 8px;
  width: 100%; height: 52px; margin-top: 6px;
  border: none; border-radius: 999px; color: #fff;
  font-size: 16px; font-weight: 600; letter-spacing: 6px; text-indent: 6px;
  background: var(--m-primary);
  box-shadow: 0 12px 24px -10px rgba(22, 119, 255, .45);
  transition: transform .15s, box-shadow .2s;
}
.cta:active { transform: scale(.98); box-shadow: 0 8px 18px -10px rgba(22, 119, 255, .45); }
.cta:disabled { opacity: .75; }
.rot { animation: kb-rot 1s linear infinite; }
@keyframes kb-rot { to { transform: rotate(360deg); } }

.divider { display: flex; align-items: center; gap: 12px; font-size: 12px; color: #9aa5bd; margin: 24px 0 14px; }
.divider::before, .divider::after { content: ''; flex: 1; height: 1px; background: rgba(59, 111, 224, .12); }
.socials { display: flex; justify-content: center; gap: 18px; }
.so {
  width: 42px; height: 42px; border-radius: 50%;
  display: grid; place-items: center; color: #fff; font-size: 19px;
}
.so:active { transform: scale(.93); }
.so-wechat { background: #07c160; box-shadow: 0 8px 16px -6px rgba(7, 193, 96, .5); }
.so-qq     { background: #12B7F5; box-shadow: 0 8px 16px -6px rgba(18, 183, 245, .5); }
.so-wecom  { background: #2f87f5; box-shadow: 0 8px 16px -6px rgba(47, 135, 245, .5); }

.forgot { margin-top: 18px; text-align: center; font-size: 12.5px; }
.forgot span { color: #6b7a99; cursor: pointer; }
.forgot span:active { color: #3b6fe0; }

.captcha-sheet { padding-bottom: calc(env(safe-area-inset-bottom) + 18px); }

/* ===== 暗色：同 Web 登录页深蓝系 ===== */
html.dark .login { background: var(--m-bg); }
html.dark .brand-name { color: var(--m-primary); }
html.dark .hi { color: #e6ecf7; }
html.dark .sub { color: #7688ab; }
html.dark .pill {
  background: rgba(18, 30, 56, .72);
  box-shadow: 0 0 0 1px #25344f inset, 0 8px 22px -12px rgba(0, 0, 0, .6);
}
html.dark .pill:focus-within {
  background: rgba(22, 36, 66, .92);
  box-shadow: 0 0 0 1.5px #4a78c8 inset, 0 0 0 4px rgba(74, 120, 200, .16);
}
html.dark .pill input { color: #e6ecf7; }
html.dark .pill input::placeholder { color: #5d6985; }
html.dark .p-ico, html.dark .p-eye { color: #6e7a96; }
html.dark .divider::before, html.dark .divider::after { background: #1f2d4d; }
html.dark .divider { color: #6e7a96; }
html.dark .forgot span { color: #8ea0c5; }

/* 小屏收敛 */
@media (max-width: 350px) {
  .zone { padding-left: 18px; padding-right: 18px; }
  .brand-3d { width: 68%; }
}
</style>
