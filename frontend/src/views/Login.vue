<script setup>
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { User, Lock } from '@element-plus/icons-vue'
import { useAuthStore } from '../store/auth'
import SlideCaptcha from '../components/SlideCaptcha.vue'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

const username = ref('')
const password = ref('')
const captchaToken = ref('')
const slidePassed = ref(false)
const loading = ref(false)
const captchaOpen = ref(false) // 是否展开验证码
const captchaNonce = ref(0)    // 强制重置验证码组件（失败重验时递增）

// 滑动验证通过（服务端已确认对齐）：组件返回本次票据 token，直接用于登录
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

// 真正的登录请求
async function doLogin() {
  loading.value = true
  try {
    await auth.login(username.value.trim(), password.value, captchaToken.value, '')
    captchaOpen.value = false // 仅成功才关闭验证码弹窗
    ElMessage.success('登录成功')
    const redirect = route.query.redirect || (auth.isAdmin ? '/admin/docs' : '/')
    router.push(redirect)
  } catch (e) {
    const msg = e.response?.data?.error || e.response?.data?.message || '登录失败'
    slidePassed.value = false
    // 账号/密码错误：退出验证码弹窗，回到账号密码输入界面
    const isCredential = msg.includes('用户名或密码') || msg.includes('密码错误') || msg.includes('禁用')
    if (isCredential) {
      captchaOpen.value = false
      ElMessage.error(msg)
    } else {
      // 验证码类错误：强制重挂组件，重新拉取并验证
      captchaNonce.value++
      captchaOpen.value = true
      ElMessage.error(msg)
    }
  } finally {
    loading.value = false
  }
}

// 点「登录」：校验账号密码 → 未验证则弹出滑块验证码，验证通过后自动登录
async function submit() {
  if (!username.value || !password.value) {
    ElMessage.warning('请输入账号和密码')
    return
  }
  if (slidePassed.value) {
    doLogin()
    return
  }
  captchaOpen.value = true   // 组件自托管拉取拼图，无需此处预拉
}

// 忘记密码：无 SMTP 邮件找回，引导联系管理员（管理员可在后台重置）
function onForgot() {
  ElMessageBox.alert(
    '请联系系统管理员重置密码。',
    { confirmButtonText: '知道了', type: 'info' }
  )
}
</script>

<template>
  <div class="login-page">
    <el-card class="login-card">
      <div class="title"><img class="login-logo" src="/logo.gif" alt="" /> 知识库 · 登录</div>
      <el-form @submit.prevent="submit" label-position="top">
        <el-form-item>
          <el-input v-model="username" placeholder="用户名" :prefix-icon="User" size="large" />
        </el-form-item>
        <el-form-item>
          <el-input v-model="password" type="password" show-password placeholder="密码"
                    :prefix-icon="Lock" size="large" @keyup.enter="submit" />
        </el-form-item>
        <el-form-item>
          <span class="captcha-state" :class="{ passed: slidePassed }" v-if="slidePassed">✓ 已通过安全验证</span>
        </el-form-item>
        <el-button type="primary" size="large" class="submit" :loading="loading" @click="submit">
          登 录
        </el-button>
      </el-form>
      <p class="fp"><span @click="onForgot">忘记密码？</span></p>
    </el-card>
    <!-- 点登录后弹出的滑块验证码 -->
    <div v-if="captchaOpen" class="captcha-mask" @click.self="onCaptchaClose">
      <div class="captcha-pop">
        <SlideCaptcha :key="captchaNonce" @success="onSlideSuccess" @close="onCaptchaClose" />
      </div>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  height: 100vh; display: flex; align-items: center; justify-content: center;
  background: linear-gradient(135deg, #eef2ff 0%, #f8fafc 100%);
}
.login-card { width: 360px; padding: 12px 8px; border-radius: 14px; }
.title { text-align: center; font-size: 20px; font-weight: 700; color: #4f46e5; margin: 8px 0 20px; display: flex; align-items: center; justify-content: center; gap: 10px; }
.login-logo { width: 40px; height: 40px; object-fit: contain; }
.submit { width: 100%; }
.fp { margin: 12px 0 4px; text-align: center; }
.fp span { color: #6366f1; cursor: pointer; font-size: 13px; }
.fp span:hover { text-decoration: underline; }
.tip { margin-top: 14px; text-align: center; color: #94a3b8; font-size: 12px; }

/* 验证触发按钮（截图样式） */
.captcha-trigger {
  display: inline-flex; align-items: center; gap: 8px;
  padding: 6px 14px; border-radius: 4px; cursor: pointer;
  border: 1px solid #cbd5e1; background: #fff;
  transition: all .2s; user-select: none;
}
.captcha-trigger:hover { border-color: #94a3b8; background: #f8fafc; }
.captcha-trigger.passed { border-color: #22c55e; background: #f0fdf4; color: #15803d; }
.ct-dot {
  width: 10px; height: 10px; border-radius: 50%;
  background: #9ca3af; box-shadow: inset 0 1px 2px rgba(0,0,0,.25);
}
.ct-icon {
  width: 14px; height: 14px; border-radius: 50%;
  background: #22c55e; color: #fff; font-size: 10px;
  display: flex; align-items: center; justify-content: center;
}
.ct-text { font-size: 13px; }
.captcha-state { font-size: 13px; color: #94a3b8; }
.captcha-state.passed { color: #22c55e; font-weight: 600; }
.captcha-mask {
  position: fixed; inset: 0; z-index: 1000;
  background: rgba(15,23,42,0.45);
  display: flex; align-items: center; justify-content: center;
}
.captcha-pop { width: 348px; }

/* 暗色模式适配：theme.css 已覆盖 .login-page/.title/.captcha-img/.tip，此处补两处次要文字与 hover 底色 */
html.dark .captcha-trigger:hover { border-color: #8b8fa0; background: #23232e; }
html.dark .captcha-state { color: #8b8fa0; }

/* ===================== 响应式布局（追加在末尾） ===================== */
@media (max-width: 1024px) {
  .login-card { width: 380px; max-width: calc(100vw - 32px); }
}

@media (max-width: 640px) {
  .login-card { width: 100%; max-width: calc(100vw - 32px); padding: 4px 0; }
  .title { font-size: 18px; gap: 8px; margin: 6px 0 14px; }
  .login-logo { width: 32px; height: 32px; }
  .fp { margin: 10px 0 4px; }

  /* 滑块验证码：固定 320px 宽，窄屏跟随弹层一起收 */
  .captcha-pop { width: calc(100vw - 24px); max-width: 348px; }
  .captcha-pop :deep(.slide-captcha) { width: 100%; max-width: 100%; padding: 12px 12px 10px; }
  .captcha-pop :deep(.sc-stage) { width: 100%; max-width: 320px; height: 160px; }
  .captcha-pop :deep(.sc-bg) { width: 100%; height: 100%; }
  .captcha-pop :deep(.sc-bar) { width: 100%; max-width: 320px; }
}
</style>
