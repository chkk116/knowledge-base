<script setup>
/**
 * 供应商真实品牌 logo。
 * 图标取自 lobehub icons（单色 currentColor、viewBox 24×24），内嵌到包里，离线可用；
 * 用 CSS color 上各家品牌色，所以既能整体调色也便于深色主题。
 * 自定义平台没有品牌，用齿轮兜底。
 */
import { computed } from 'vue'
import openaiSvg from '../assets/brands/openai.svg?raw'
import deepseekSvg from '../assets/brands/deepseek.svg?raw'
import qwenSvg from '../assets/brands/qwen.svg?raw'
import zhipuSvg from '../assets/brands/zhipu.svg?raw'
import opencodeSvg from '../assets/brands/opencode.svg?raw'

const props = defineProps({
  provider: { type: String, default: '' },
  size: { type: [Number, String], default: 40 },
})

const GEAR = '<svg viewBox="0 0 24 24" fill="currentColor" xmlns="http://www.w3.org/2000/svg"><path d="M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58a.4.4 0 0 0 .1-.5l-1.92-3.32a.4.4 0 0 0-.45-.19l-2.39.96a7.03 7.03 0 0 0-1.62-.94l-.36-2.54a.4.4 0 0 0-.4-.34h-3.84a.4.4 0 0 0-.4.34l-.36 2.54c-.58.24-1.13.56-1.62.94l-2.39-.96a.4.4 0 0 0-.45.19L2.74 8.6a.4.4 0 0 0 .1.5l2.03 1.58c-.05.3-.07.62-.07.94s.02.64.07.94L2.84 14.14a.4.4 0 0 0-.1.5l1.92 3.32c.09.16.28.23.45.19l2.39-.96c.5.38 1.04.7 1.62.94l.36 2.54c.04.19.2.33.4.34h3.84c.2 0 .37-.15.4-.34l.36-2.54c.58-.24 1.13-.56 1.62-.94l2.39.96c.17.06.36-.01.45-.19l1.92-3.32a.4.4 0 0 0-.1-.5l-2.03-1.58zM12 15.6A3.6 3.6 0 1 1 12 8.4a3.6 3.6 0 0 1 0 7.2z"/></svg>'

const LOGO = {
  OPENAI: openaiSvg,
  DEEPSEEK: deepseekSvg,
  DASHSCOPE: qwenSvg,
  ZHIPU: zhipuSvg,
  OPENCODE: opencodeSvg,
}
const BRAND = {
  OPENAI: '#0f172a',
  DEEPSEEK: '#4d6bfe',
  DASHSCOPE: '#615ced',
  ZHIPU: '#3859ff',
  OPENCODE: '#1f2937',
  MIMO: '#ff6900',
  CUSTOM: '#64748b',
}

const svg = computed(() => LOGO[props.provider] || GEAR)
const color = computed(() => BRAND[props.provider] || BRAND.CUSTOM)
const px = computed(() => (typeof props.size === 'number' ? `${props.size}px` : props.size))
</script>

<template>
  <span class="provider-logo" :style="{ width: px, height: px, color }" v-html="svg" />
</template>

<style scoped>
.provider-logo { display: inline-flex; align-items: center; justify-content: center; flex-shrink: 0; }
.provider-logo :deep(svg) { width: 100%; height: 100%; display: block; }
</style>
