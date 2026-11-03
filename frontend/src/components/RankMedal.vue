<template>
  <svg :width="size" :height="size" viewBox="0 0 24 24" class="rank-medal" aria-hidden="true">
    <defs>
      <linearGradient :id="gid" x1="0" y1="0" x2="0.55" y2="1">
        <stop offset="0" :stop-color="c.light" />
        <stop offset="0.55" :stop-color="c.main" />
        <stop offset="1" :stop-color="c.dark" />
      </linearGradient>
      <linearGradient :id="rid" x1="0" y1="0" x2="1" y2="1">
        <stop offset="0" :stop-color="c.ribbon1" />
        <stop offset="1" :stop-color="c.ribbon2" />
      </linearGradient>
    </defs>

    <!-- 缎带：左右两条斜带压在牌面上方 -->
    <path d="M6.2 2.2h3.4l2.5 6.4-3.2 1.2z" :fill="`url(#${rid})`" />
    <path d="M17.8 2.2h-3.4l-2.5 6.4 3.2 1.2z" :fill="`url(#${rid})`" opacity=".88" />

    <!-- 奖牌主体 -->
    <circle cx="12" cy="14.6" r="7" :fill="`url(#${gid})`" />
    <circle cx="12" cy="14.6" r="5.55" fill="none" stroke="rgba(255,255,255,.55)" stroke-width="1.05" />
    <!-- 顶部高光 -->
    <path d="M6.6 11.2a7 7 0 0 1 4.2-3.2" fill="none" stroke="rgba(255,255,255,.72)"
          stroke-width="1.3" stroke-linecap="round" />
    <!-- 五角星 -->
    <path d="M12 10.85l1.12 2.24 2.47.36-1.79 1.74.42 2.46L12 16.49l-2.22 1.16.42-2.46-1.79-1.74 2.47-.36z"
          fill="#fff" opacity=".93" />
  </svg>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  rank: { type: Number, required: true },
  size: { type: Number, default: 26 },
})

const PALETTE = {
  1: { light: '#ffe08a', main: '#f5b21b', dark: '#c47b06', ribbon1: '#ef5a5f', ribbon2: '#b92b31' },
  2: { light: '#f4f7fb', main: '#b9c6d6', dark: '#7f92a8', ribbon1: '#7c8ba1', ribbon2: '#4b5a6e' },
  3: { light: '#f7c79a', main: '#cd7f32', dark: '#8f4a1c', ribbon1: '#b45309', ribbon2: '#8a3e07' },
}

const c = computed(() => PALETTE[props.rank] || PALETTE[3])
const gid = computed(() => `rm-g-${props.rank}`)
const rid = computed(() => `rm-r-${props.rank}`)
</script>

<style scoped>
.rank-medal { display: block; }
</style>
