<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'

const route = useRoute()
const router = useRouter()
// 通过 ?feature=xxx 或 /coming-soon/xxx 传入功能名；通配路由进入时显示默认文案
const feature = computed(() => route.query.feature || route.params.feature || '这个页面')
</script>

<template>
  <div class="cs">
    <div class="cs-emoji">🚧</div>
    <h2>{{ feature }}</h2>
    <p class="cs-sub">前面的区域，以后再来探索～</p>
    <el-button type="primary" @click="router.push('/')">返回首页</el-button>
  </div>
</template>

<style scoped>
.cs {
  min-height: 60vh;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  gap: 14px;
}
.cs-emoji { font-size: 64px; line-height: 1; }
.cs h2 { margin: 0; font-size: 22px; color: #0f172a; }
html.dark .cs h2 { color: #e7e7ee; }
.cs-sub { margin: 0; color: #94a3b8; font-size: 15px; }
</style>
