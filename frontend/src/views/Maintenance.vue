<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../store/auth'

const route = useRoute()
const router = useRouter()
const feature = computed(() => route.query.feature || '该功能')
const msg = computed(() => route.query.msg || '')
const restore = computed(() => (route.query.restore || '').toString().replace('T', ' '))

/**
 * 返回上一跳页面。
 * 维护模式下前进/后退的落点也可能被守卫拦回本页，所以只在「上一跳不是维护页/敬请期待页」
 * 时才走浏览器后退；否则（例如直接粘地址打开、或上一跳同样被关闭）退回一个稳妥落点：
 * 有核心维护权限的回到「功能开关」，其他人回登录页。
 */
function goBack() {
  const prev = window.history.state && window.history.state.back
  const isClosedPage = typeof prev === 'string' && (prev.startsWith('/maintenance') || prev.startsWith('/coming-soon'))
  if (prev && !isClosedPage && prev !== route.fullPath) {
    router.back()
    return
  }
  const auth = useAuthStore()
  router.replace(auth.hasPerm && auth.hasPerm('CORE_MAINTAIN') ? '/admin/maintain' : '/login')
}
</script>

<template>
  <div class="mt-wrap">
    <!-- 左上角返回：维护模式下从后台/其他页跳来后，一键回到刚才的页面 -->
    <button class="mt-back" type="button" @click="goBack">
      <el-icon><ArrowLeft /></el-icon>
      <span>返回上一页</span>
    </button>
      <div class="mt">
      <div class="mt-emoji">🛠️</div>
      <h2>{{ feature }} · 维护中</h2>
      <p class="mt-sub">该功能暂时不可用，我们正在努力恢复，给您带来不便敬请谅解。</p>
      <div v-if="msg" class="mt-msg">📌 {{ msg }}</div>
      <div v-if="restore" class="mt-restore">⏰ 预计恢复时间：{{ restore }}</div>
      <el-button type="primary" @click="router.push('/')">返回首页</el-button>
    </div>
  </div>
</template>

<style scoped>
.mt-wrap { position: relative; }
/* 左上角返回按钮：幽灵样式，不抢维护提示的视觉重心 */
.mt-back {
  position: absolute; top: 2px; left: 0;
  display: inline-flex; align-items: center; gap: 6px;
  padding: 7px 14px 7px 11px;
  border: 1px solid var(--el-border-color, #dcdfe6);
  border-radius: 999px;
  background: var(--el-bg-color, #fff);
  color: var(--el-text-color-regular, #606266);
  font-size: 13px; line-height: 1; cursor: pointer;
  transition: all .18s ease;
}
.mt-back:hover {
  color: var(--el-color-primary, #4f46e5);
  border-color: var(--el-color-primary, #4f46e5);
  background: rgba(79, 70, 229, .06);
}
.mt-back:active { transform: translateY(1px); }
.mt-back .el-icon { font-size: 14px; }
html.dark .mt-back { background: #151a2e; border-color: #2a3858; color: #b9c4dc; }
html.dark .mt-back:hover { background: rgba(129, 140, 248, .14); border-color: #6366f1; color: #a5b4fc; }

.mt {
  min-height: 60vh;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  gap: 14px;
}
.mt-emoji { font-size: 64px; line-height: 1; }
.mt h2 { margin: 0; font-size: 22px; color: #0f172a; }
html.dark .mt h2 { color: #e7e7ee; }
.mt-sub { margin: 0; color: #94a3b8; font-size: 15px; }
.mt-msg {
  max-width: 520px;
  background: #fffbeb;
  border: 1px solid #fde68a;
  color: #b45309;
  padding: 10px 14px;
  border-radius: 10px;
  font-size: 14px;
  line-height: 1.6;
}
html.dark .mt-msg { background: #3a2f12; border-color: #6b551f; color: #fbbf24; }
.mt-restore { color: #64748b; font-size: 13px; }
html.dark .mt-restore { color: #94a3b8; }

/* ===================== 响应式布局（追加在末尾） ===================== */
@media (max-width: 640px) {
  .mt-back { padding: 6px 12px 6px 9px; font-size: 12.5px; }
  .mt { min-height: 50vh; padding: 40px 16px 24px; gap: 10px; }
  .mt-emoji { font-size: 48px; }
  .mt h2 { font-size: 19px; line-height: 1.4; overflow-wrap: anywhere; }
  .mt-sub { font-size: 14px; }
  .mt-msg { max-width: 100%; padding: 10px 12px; font-size: 13px; }
  .mt-restore { font-size: 12.5px; }
}
</style>
