<script setup>
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { resetPwdCheck, resetPwd } from '../api/auth'

/**
 * 重置密码（公开页）。
 * 管理员在用户管理里生成一次性链接（30 分钟有效），用户打开本页自己设置新密码。
 */
const route = useRoute()
const router = useRouter()

const token = String(route.query.token || '')
const loading = ref(true)
const valid = ref(false)
const username = ref('')
const pwd = ref('')
const pwd2 = ref('')
const err = ref('')
const submitting = ref(false)
const done = ref(false)

onMounted(async () => {
  if (!token) { loading.value = false; valid.value = false; return }
  try {
    const { data } = await resetPwdCheck(token)
    valid.value = !!data?.valid
    username.value = data?.username || ''
  } catch (e) {
    valid.value = false
  } finally {
    loading.value = false
  }
})

async function submit() {
  err.value = ''
  if (pwd.value.length < 6) { err.value = '新密码至少 6 位'; return }
  if (pwd.value !== pwd2.value) { err.value = '两次输入的新密码不一致'; return }
  submitting.value = true
  try {
    await resetPwd(token, pwd.value)
    done.value = true
    setTimeout(() => router.push('/login'), 1800)
  } catch (e) {
    err.value = e.response?.data?.error || '重置失败，请重新获取链接'
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="rp-page">
    <div class="rp-card">
      <h1>重置密码</h1>

      <p v-if="loading" class="hint">正在校验链接…</p>

      <template v-else-if="!valid">
        <p class="err">链接已失效或已使用。</p>
        <p class="hint">请联系管理员在「用户管理」中重新生成一次性重置链接（30 分钟内有效）。</p>
        <button class="btn" type="button" @click="router.push('/login')">返回登录</button>
      </template>

      <template v-else-if="done">
        <p class="ok">密码已重置，即将跳转登录…</p>
        <button class="btn" type="button" @click="router.push('/login')">立即登录</button>
      </template>

      <template v-else>
        <p class="hint">正在为 <b>{{ username }}</b> 设置新密码</p>
        <input v-model="pwd" class="ipt" type="password" placeholder="新密码（至少 6 位）" autocomplete="new-password" />
        <input v-model="pwd2" class="ipt" type="password" placeholder="确认新密码" autocomplete="new-password" @keyup.enter="submit" />
        <p v-if="err" class="err">{{ err }}</p>
        <button class="btn" type="button" :disabled="submitting" @click="submit">
          {{ submitting ? '提交中…' : '确认重置' }}
        </button>
        <p class="hint small">重置后该账号的所有登录状态会立即失效。</p>
      </template>
    </div>
  </div>
</template>

<style scoped>
.rp-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background: linear-gradient(160deg, #eef4ff 0%, #f7faff 45%, #ffffff 100%);
}
.rp-card {
  width: 100%;
  max-width: 400px;
  background: #fff;
  border: 1px solid #e8eef7;
  border-radius: 16px;
  padding: 28px 26px;
  box-shadow: 0 18px 40px rgba(30, 64, 175, .10);
}
.rp-card h1 { margin: 0 0 14px; font-size: 22px; color: #1e293b; }
.hint { font-size: 13.5px; color: #64748b; line-height: 1.7; margin: 0 0 12px; }
.hint.small { font-size: 12px; color: #94a3b8; margin-top: 10px; }
.hint b { color: #1e293b; }
.ipt {
  width: 100%;
  box-sizing: border-box;
  padding: 11px 13px;
  margin-bottom: 10px;
  border: 1px solid #d5ddea;
  border-radius: 10px;
  font-size: 14.5px;
  outline: none;
  background: #fff;
  color: #1e293b;
}
.ipt:focus { border-color: #4f46e5; }
.btn {
  width: 100%;
  padding: 11px 0;
  margin-top: 4px;
  border: 0;
  border-radius: 10px;
  background: #2563eb;
  color: #fff;
  font-size: 15px;
  cursor: pointer;
}
.btn:hover { background: #1d4ed8; }
.btn:disabled { opacity: .6; cursor: default; }
.err { color: #dc2626; font-size: 13px; margin: 4px 0 10px; }
.ok { color: #16a34a; font-size: 14px; margin: 4px 0 14px; }

:deep(html.dark) .rp-page { background: #141426; }
:deep(html.dark) .rp-card { background: #1b1b2e; border-color: #2c2c44; box-shadow: none; }
:deep(html.dark) .rp-card h1 { color: #e5e7f0; }
:deep(html.dark) .ipt { background: #23233a; border-color: #34345a; color: #e5e7f0; }
:deep(html.dark) .hint { color: #a5a7bd; }
:deep(html.dark) .hint b { color: #e5e7f0; }
</style>
