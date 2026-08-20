import { defineStore } from 'pinia'
import { ref } from 'vue'

// 界面级临时状态：存在内存（Pinia）而非 sessionStorage。
// 特点：SPA 内路由切换不丢；整页刷新（F5）即重置为默认值。
export const useUiStore = defineStore('ui', () => {
  // 首页右侧公告卡片是否被用户关闭（关闭后整块侧栏收起，帖子列表撑满该位置）
  const annCardClosed = ref(false)

  function closeAnnCard() { annCardClosed.value = true }
  function openAnnCard() { annCardClosed.value = false }

  return { annCardClosed, closeAnnCard, openAnnCard }
})
