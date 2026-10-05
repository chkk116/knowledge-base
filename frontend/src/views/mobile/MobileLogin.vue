<script setup>
/**
 * 移动端登录页
 *
 * 视觉向 Web 端登录页靠拢（用户 2026-10-05 反馈）：
 *   - 白底卡片居中，而不是整页铺色
 *   - 标题「欢迎登录」+ 主色强调
 *   - 输入框浅灰底 + 细边框 + 圆角，聚焦时主色描边
 *   - 主按钮实心主色、宽按钮、稍收敛的圆角（比原先 14px 更贴近 Web 的硬朗感）
 * 功能不变：竖排大表单（拇指易点）、滑动验证码复用同一组件、
 * 不提供注册入口（企业内知识库，账号由管理员开通）。
 */
import { ref } from 'vue'
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
</script>

<template>
  <div class="login">
    <div class="login-card">
      <div class="login-head">
        <div class="logo">知</div>
        <h1>欢迎登录</h1>
        <p>登录后即可管理你的文档</p>
      </div>

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

        <div class="tip">
          账号由管理员开通，忘记密码请联系管理员
        </div>
      </div>
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
/* 页面底色比卡片略灰，让白卡浮起来 */
.login {
  min-height: 100vh; min-height: 100dvh;
  background: var(--m-bg);
  padding: calc(env(safe-area-inset-top) + 56px) 22px 30px;
  box-sizing: border-box;
}
.login-card {
  background: var(--m-card);
  border: 1px solid var(--m-border);
  border-radius: 18px;
  padding: 30px 22px 26px;
  box-shadow: 0 1px 2px rgba(16, 24, 40, .04), 0 12px 32px rgba(16, 24, 40, .06);
}
.login-head { text-align: center; margin-bottom: 26px; }
.logo {
  width: 58px; height: 58px; margin: 0 auto 16px;
  border-radius: 16px;
  background: linear-gradient(135deg, #6366f1, #4f46e5);
  color: #fff; font-size: 26px; font-weight: 700;
  display: flex; align-items: center; justify-content: center;
  box-shadow: 0 8px 20px rgba(79, 70, 229, .28);
}
.login-head h1 { font-size: 22px; margin: 0; letter-spacing: -.3px; color: var(--m-text); }
.login-head p { font-size: 13px; color: var(--m-text-dim); margin: 8px 0 0; }

/* 输入框：浅灰底 + 细边框（Web 端观感），聚焦时主色描边 */
.fld {
  display: flex; align-items: center; gap: 10px;
  height: 50px; padding: 0 14px; margin-bottom: 12px;
  background: var(--m-card-2);
  border-radius: 10px;
  border: 1px solid var(--m-border);
  transition: border-color .16s, background-color .16s;
}
.fld:focus-within { border-color: var(--m-primary); background: var(--m-card); }
.fld-ico { color: var(--m-text-dim); flex: none; font-size: 16px; }
.fld input {
  flex: 1 1 auto; min-width: 0;
  border: none; outline: none; background: transparent;
  font-size: 15.5px; color: var(--m-text); font-family: inherit;
  height: 100%;
}
.fld input::placeholder { color: var(--m-text-dim); }
.fld-eye { border: none; background: transparent; color: var(--m-text-dim); padding: 6px; cursor: pointer; display: inline-flex; }

.err {
  display: flex; align-items: center; gap: 6px;
  color: #dc2626; font-size: 13px; margin: 2px 2px 12px;
}

/* 主按钮：贴近 Web 的实心蓝 + 稍收敛圆角 */
.login-btn {
  margin-top: 8px; height: 50px; font-size: 16px; letter-spacing: 1px;
  border-radius: 10px;
}
.tip { text-align: center; font-size: 12.5px; color: var(--m-text-dim); margin-top: 18px; }
.rot { animation: kb-rot 1s linear infinite; }
@keyframes kb-rot { to { transform: rotate(360deg); } }

.captcha-sheet { padding-bottom: calc(env(safe-area-inset-bottom) + 18px); }
</style>
