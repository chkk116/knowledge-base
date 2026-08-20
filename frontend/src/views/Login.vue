<script setup>
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { User, Lock } from '@element-plus/icons-vue'
import { useAuthStore } from '../store/auth'
import { captcha as apiCaptcha, captchaSlide } from '../api/auth'
import SlideCaptcha from '../components/SlideCaptcha.vue'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

const username = ref('')
const password = ref('')
const captchaToken = ref('')
const captchaImage = ref('')
const slidePassed = ref(false)
const loading = ref(false)
const captchaOpen = ref(false) // 是否展开验证码
const captchaNonce = ref(0)    // 强制重置验证码组件（失败重验时递增）

// 拉取滑动拼图背景图（token 与 base64 图片）
async function loadCaptcha() {
  try {
    const { data } = await apiCaptcha()
    captchaToken.value = data.token
    captchaImage.value = data.image
    slidePassed.value = false
  } catch (e) {
    captchaImage.value = ''
  }
}

// 滑动成功：前端已对齐。向后端标记票据（best-effort），再自动登录。
// 注意：这里不立即关闭弹窗——等 doLogin 真正成功才关；失败则重新弹出重验。
async function onSlideSuccess() {
  slidePassed.value = true
  try {
    await captchaSlide(captchaToken.value)
  } catch (e) {
    // 后端标记失败（如后端尚未重启到滑动版）也先视为前端通过，由 doLogin 兜底
  }
  doLogin()
}
const onSlideRefresh = () => loadCaptcha()
function openCaptcha() {
  if (slidePassed.value) return
  captchaOpen.value = true
}
function onCaptchaClose() {
  captchaOpen.value = false
}

onMounted(loadCaptcha)

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
      // 验证码类错误：刷新图案并继续验证
      captchaNonce.value++
      captchaOpen.value = true
      await loadCaptcha() // 刷新后端票据，防止重放
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
  await loadCaptcha()        // 重新拉取最新验证码票据，避免首屏加载时后端未就绪导致 token 为空
  captchaOpen.value = true
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
        <SlideCaptcha :key="captchaNonce" @success="onSlideSuccess" @refresh="onSlideRefresh" @close="onCaptchaClose" />
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
</style>
