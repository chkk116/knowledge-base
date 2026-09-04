<script setup>
import { computed } from 'vue'
import WornBadge from './WornBadge.vue'
import { badgeColor, badgeDef } from '../utils/badgeIcons'

const props = defineProps({
  badge: { type: Object, default: null },     // 完整对象（来自后端 listBadges / userBadges）
  badgeId: { type: String, default: '' },     // 纯 id（前端 catalog 还原，节省一次接口）
  wearing: { type: Boolean, default: true },  // 是否当前佩戴中（false 时显示"查看详情"）
})

// 统一为视图对象：优先用对象 prop，否则查前端 catalog
const view = computed(() => {
  if (props.badge && props.badge.id) return props.badge
  return badgeDef(props.badgeId || (props.badge && props.badge.id))
})
const colors = computed(() => badgeColor(view.value?.id))
</script>

<template>
  <div v-if="view" class="bdp">
    <div class="bdp-hd">
      <WornBadge :badge-id="view.id" :size="42" />
      <div class="bdp-meta">
        <div class="bdp-name">{{ view.name }}</div>
        <span class="bdp-tier" :style="{ background: colors[0] + '22', color: colors[1], borderColor: colors[1] + '55' }">{{ view.tier }}</span>
      </div>
    </div>
    <div class="bdp-desc">{{ view.desc }}</div>
    <div class="bdp-foot" v-if="wearing">
      <span class="dot"></span> 佩戴中 · 展示于头像与昵称旁
    </div>
    <div class="bdp-foot plain" v-else>点击查看详情</div>
  </div>
  <div v-else class="bdp-empty">徽章详情不可用</div>
</template>

<style scoped>
.bdp { min-width: 220px; max-width: 280px; }
.bdp-hd { display: flex; align-items: center; gap: 12px; }
.bdp-meta { display: flex; flex-direction: column; gap: 5px; }
.bdp-name { font-size: 15px; font-weight: 700; color: #0f172a; }
.bdp-tier {
  align-self: flex-start; font-size: 11px; font-weight: 600; padding: 1px 9px;
  border-radius: 999px; border: 1px solid; line-height: 1.6;
}
.bdp-desc { margin-top: 12px; font-size: 13px; line-height: 1.6; color: #475569; }
.bdp-foot {
  margin-top: 12px; padding-top: 10px; border-top: 1px solid #eef2f7;
  font-size: 12px; color: #94a3b8; display: flex; align-items: center; gap: 6px;
}
.bdp-foot.plain { font-style: italic; }
.bdp-foot .dot {
  width: 7px; height: 7px; border-radius: 50%; background: #16a34a;
  box-shadow: 0 0 0 3px rgba(22, 163, 74, .18);
}
.bdp-empty { font-size: 13px; color: #94a3b8; }
html.dark .bdp-name { color: #e8eaf2; }
html.dark .bdp-desc { color: #aab2c5; }
html.dark .bdp-foot { color: #7c8597; border-top-color: #2a2a3a; }
</style>
