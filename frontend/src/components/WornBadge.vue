<script setup>
import { computed } from 'vue'
import { BADGE_ICONS, DEFAULT_BADGE_ICON, badgeColor } from '../utils/badgeIcons'

// 佩戴徽章的"小六边形"——纯视觉组件，悬停/弹层由父级 el-popover 控制
const props = defineProps({
  badgeId: { type: String, default: '' },
  size: { type: Number, default: 22 },
})

const icon = computed(() => BADGE_ICONS[props.badgeId] || DEFAULT_BADGE_ICON)
const colors = computed(() => badgeColor(props.badgeId))
const gid = computed(() => 'wb-' + (props.badgeId || 'x') + '-' + Math.random().toString(36).slice(2, 8))
const vb = 100
// 略小于墙体的六边形，保证小尺寸下图标仍居中清晰
const OUTER = '50,4 91,28 91,72 50,96 9,72 9,28'
const INNER = '50,14 83,33 83,67 50,86 17,67 17,33'
const iconSize = computed(() => Math.round(props.size * 0.46))
</script>

<template>
  <span v-if="badgeId" class="worn-badge" :title="'佩戴：' + badgeId"
        :style="{ width: size + 'px', height: size + 'px' }">
    <svg :viewBox="`0 0 ${vb} ${vb}`" class="wb-svg" aria-hidden="true">
      <defs>
        <linearGradient :id="gid" x1="0" y1="0" x2="1" y2="1">
          <stop offset="0%" :stop-color="colors[0]" />
          <stop offset="100%" :stop-color="colors[1]" />
        </linearGradient>
      </defs>
      <polygon :points="OUTER" :fill="`url(#${gid})`" stroke="rgba(255,255,255,.85)" stroke-width="3" stroke-linejoin="round" />
      <polygon :points="INNER" fill="rgba(255,255,255,.18)" />
    </svg>
    <component :is="icon" class="wb-ic" :style="{ width: iconSize + 'px', height: iconSize + 'px' }" />
  </span>
</template>

<style scoped>
.worn-badge {
  position: relative; display: inline-flex; align-items: center; justify-content: center;
  vertical-align: middle; flex: none;
  filter: drop-shadow(0 2px 5px rgba(79, 70, 229, .35));
}
.wb-svg { width: 100%; height: 100%; display: block; }
.wb-ic {
  position: absolute; left: 50%; top: 50%; transform: translate(-50%, -50%);
  color: #fff; z-index: 1;
}
</style>
