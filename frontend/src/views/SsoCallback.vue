<template>
  <div class="sso-wrap">
    <div class="sso-card">
      <template v-if="error">
        <div class="ico">⚠️</div>
        <h2>登录未完成</h2>
        <p>{{ error }}</p>
        <el-button type="primary" @click="router.replace('/login')">返回登录页</el-button>
      </template>
      <template v-else>
        <div class="ico">🔐</div>
        <h2>正在完成登录…</h2>
        <p>请稍候，正在为你建立会话。</p>
      </template>
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '../store/auth'
import { me } from '../api/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const error = ref('')

onMounted(async () => {
  const { token, error: err, bound } = route.query
  if (err) { error.value = String(err); return }
  // 绑定流程：后端回调后跳 /sso?bound=微信（此时会话已存在，不需要再落 token）
  if (bound) {
    ElMessage.success(`已绑定${String(bound)}，下次可以直接扫码登录`)
    router.replace('/profile')
    return
  }
  if (!token) { error.value = '缺少登录令牌，请重新登录'; return }
  try {
    auth.applySession({ token: String(token) })
    // 拉一次用户信息补齐权限/昵称（applySession 只落了 token 与缓存字段）
    await auth.fetchMe()
    ElMessage.success('登录成功')
    router.replace(auth.isAdmin ? '/admin/docs' : '/')
  } catch (e) {
    error.value = (e && e.message) || '登录信息校验失败'
  }
})
</script>

<style scoped>
.sso-wrap { min-height: 100vh; display: flex; align-items: center; justify-content: center; background: #f6f7fb; padding: 20px; }
.sso-card { background: #fff; border-radius: 14px; padding: 36px 32px; text-align: center; box-shadow: 0 8px 28px rgba(23,26,51,.07); max-width: 420px; }
.ico { font-size: 34px; margin-bottom: 8px; }
.sso-card h2 { margin: 0 0 8px; font-size: 18px; }
.sso-card p { color: #8a8f9c; font-size: 13px; line-height: 1.7; margin: 0 0 16px; word-break: break-word; }
</style>
