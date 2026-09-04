<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'

const route = useRoute()
const router = useRouter()
const feature = computed(() => route.query.feature || '该功能')
const msg = computed(() => route.query.msg || '')
const restore = computed(() => (route.query.restore || '').toString().replace('T', ' '))
</script>

<template>
  <div class="mt">
    <div class="mt-emoji">🛠️</div>
    <h2>{{ feature }} · 维护中</h2>
    <p class="mt-sub">该功能暂时不可用，我们正在努力恢复，给您带来不便敬请谅解。</p>
    <div v-if="msg" class="mt-msg">📌 {{ msg }}</div>
    <div v-if="restore" class="mt-restore">⏰ 预计恢复时间：{{ restore }}</div>
    <el-button type="primary" @click="router.push('/')">返回首页</el-button>
  </div>
</template>

<style scoped>
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
  .mt { min-height: 50vh; padding: 24px 16px; gap: 10px; }
  .mt-emoji { font-size: 48px; }
  .mt h2 { font-size: 19px; line-height: 1.4; overflow-wrap: anywhere; }
  .mt-sub { font-size: 14px; }
  .mt-msg { max-width: 100%; padding: 10px 12px; font-size: 13px; }
  .mt-restore { font-size: 12.5px; }
}
</style>
