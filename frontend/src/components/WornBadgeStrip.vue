<script setup>
import { computed } from 'vue'
import WornBadge from './WornBadge.vue'
import BadgeDetailPopover from './BadgeDetailPopover.vue'

// 一排「佩戴的勋章」——统一对齐与悬浮详情，供顶栏 / 资料页 / 作者卡复用。
// 关键：始终以 inline-flex 渲染同一结构，避免「有/无佩戴」分支导致的基线不齐。
const props = defineProps({
  badgeIds: { type: Array, default: () => [] },   // 佩戴的徽章 id 列表（最多 3 枚）
  size: { type: Number, default: 20 },            // 单枚边长
  gap: { type: Number, default: 5 },              // 徽章间距
  showPopover: { type: Boolean, default: true },   // 是否悬浮展示详情（纯展示位可关）
  wearing: { type: Boolean, default: true },       // 详情里是否标记「佩戴中」
})

const ids = computed(() =>
  (props.badgeIds || []).filter(Boolean).map(String)
)
</script>

<template>
  <span v-if="ids.length" class="worn-strip" :style="{ gap: gap + 'px' }">
    <el-popover v-for="id in ids" :key="id" trigger="hover" placement="bottom"
                :width="240" :show-arrow="true" :disabled="!showPopover">
      <template #reference>
        <WornBadge :badge-id="id" :size="size" class="strip-badge" />
      </template>
      <BadgeDetailPopover :badge-id="id" :wearing="wearing" />
    </el-popover>
  </span>
</template>

<style scoped>
.worn-strip {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  vertical-align: middle;
  line-height: 1;
  flex: none;
}
.strip-badge {
  display: inline-flex;
  transition: transform .15s ease;
}
.strip-badge:hover {
  transform: translateY(-2px);
}
</style>
