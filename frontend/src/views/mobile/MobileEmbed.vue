<script setup>
/**
 * 移动端「我的收藏 / 浏览历史 / AI 问答 / 积分排行」等二级功能的统一容器页。
 *
 * 说明：这些功能在移动端暂时以「网页版原页面嵌入」的方式提供 ——
 * 因为它们在手机上的使用频率远低于「文档管理」，独立重做 UI 的收益很低。
 * 用 iframe 挂载桌面版对应路由，用户可以正常使用，同时不阻塞后续按需重做。
 * 一旦某个功能在手机上用得多了，再把它单独拆成移动端原生页面即可。
 */
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { resolveUrl } from '../../utils/runtimeBase'

const route = useRoute()
const router = useRouter()

const loading = ref(true)

const CONF = {
  favorites: { title: '我的收藏', path: '/favorites' },
  history: { title: '浏览历史', path: '/history' },
  ai: { title: 'AI 问答', path: '/ai/chat' },
  ranking: { title: '积分排行', path: '/ranking' },
  post: { title: '文章', path: null }
}
const conf = computed(() => CONF[route.params.kind] || { title: '页面', path: '/' })
// iframe 内嵌桌面版页面：App 端必须用绝对地址，否则 Capacitor 会当成本地文件而 404
const src = computed(() => resolveUrl(conf.value.path || '/'))
</script>

<template>
  <div class="wrap">
    <header class="w-head">
      <button class="m-header-act" @click="router.back()"><el-icon :size="20"><ArrowLeft /></el-icon></button>
      <div class="w-title">{{ conf.title }}</div>
      <button class="m-header-act" style="visibility:hidden"><el-icon><Close /></el-icon></button>
    </header>
    <div class="w-body">
      <div v-if="loading" class="w-load"><el-icon class="rot" :size="24"><Loading /></el-icon></div>
      <iframe :src="src" class="w-frame" @load="loading = false" />
    </div>
  </div>
</template>

<style scoped>
.wrap { height: 100%; display: flex; flex-direction: column; }
.w-head {
  flex: none; display: flex; align-items: center; gap: 6px;
  padding: calc(env(safe-area-inset-top) + 8px) 8px 8px;
  background: var(--m-bg);
}
.w-title { flex: 1 1 auto; text-align: center; font-size: 16.5px; font-weight: 600; }
.w-body { flex: 1 1 auto; min-height: 0; position: relative; }
.w-frame { width: 100%; height: 100%; border: none; display: block; background: var(--m-bg); }
.w-load {
  position: absolute; inset: 0; display: flex; align-items: center; justify-content: center;
  color: var(--m-text-dim); pointer-events: none;
}
.rot { animation: kb-rot 1s linear infinite; }
@keyframes kb-rot { to { transform: rotate(360deg); } }
</style>
