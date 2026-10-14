<script setup>
/**
 * 移动端「徽章成就」：徽章墙（已获得/未获得 + 进度）+ 佩戴
 * 复用 api/kb.js 的 listBadges / wearBadge
 * 后端 /badges 返回：{ badges:[{id,name,desc,icon(emoji),color,tier,metric,threshold,current,earned,progress,worn}], earned, total }
 */
import { onMounted, ref, computed } from 'vue'
import { listBadges, wearBadge } from '../../api/kb'

const loading = ref(true)
const badges = ref([])
const earnedCount = ref(0)
const total = ref(0)
const wearing = ref(false)

const earnedList = computed(() => badges.value.filter((b) => b.earned))
const lockedList = computed(() => badges.value.filter((b) => !b.earned))

async function load() {
  loading.value = true
  try {
    const r = await listBadges().then((x) => x.data || x).catch(() => null)
    badges.value = (r && r.badges) || []
    earnedCount.value = r?.earned || 0
    total.value = r?.total || badges.value.length
  } finally { loading.value = false }
}

async function toggleWear(b) {
  if (wearing.value) return
  wearing.value = true
  const next = b.worn ? '' : b.id
  try {
    await wearBadge(next)
    badges.value = badges.value.map((x) => ({ ...x, worn: x.id === b.id ? !b.worn : false }))
    window.__kbMsg?.success(next ? '已佩戴' : '已卸下')
  } catch (e) {
    window.__kbMsg?.error('操作失败')
  } finally { wearing.value = false }
}

onMounted(load)
</script>

<template>
  <div class="bg">
    <div class="m-header">
      <div>
        <div class="m-title">徽章成就</div>
        <div class="m-sub">已点亮 {{ earnedCount }} / {{ total }}</div>
      </div>
    </div>

    <div class="body">
      <div v-if="loading" class="m-card"><div style="padding:14px"><div class="m-skel" style="height:60px"></div></div></div>

      <template v-else>
        <!-- 已获得 -->
        <div class="m-sec-title" v-if="earnedList.length">已获得（{{ earnedList.length }}）</div>
        <div class="grid" v-if="earnedList.length">
          <button v-for="b in earnedList" :key="b.id" class="badge" type="button" :class="{ worn: b.worn }" @click="toggleWear(b)">
            <span class="bi" :style="{ background: (b.color || '#f59e0b') + '22' }">{{ b.icon }}</span>
            <span class="bn">{{ b.name }}</span>
            <span class="bd">{{ b.desc }}</span>
            <span class="tag" :class="{ on: b.worn }">{{ b.worn ? '已佩戴' : '点击佩戴' }}</span>
          </button>
        </div>

        <!-- 未获得 -->
        <div class="m-sec-title" v-if="lockedList.length">待点亮（{{ lockedList.length }}）</div>
        <div class="grid" v-if="lockedList.length">
          <div v-for="b in lockedList" :key="b.id" class="badge lock">
            <span class="bi gray"><el-icon :size="24"><Lock /></el-icon></span>
            <span class="bn">{{ b.name }}</span>
            <span class="bd">{{ b.desc }}</span>
            <span class="pg">
              <i :style="{ width: (b.progress || 0) + '%' }"></i>
            </span>
            <span class="ptxt">{{ b.current || 0 }} / {{ b.threshold }}</span>
          </div>
        </div>

        <div v-if="!badges.length" class="m-card"><div class="m-empty"><div>暂无徽章</div></div></div>
      </template>
    </div>
    <div style="height:12px"></div>
  </div>
</template>

<style scoped>
.bg { min-height: 100%; }
.body { padding: 0 16px; }
.grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.badge { display: flex; flex-direction: column; align-items: center; gap: 5px; padding: 16px 10px 12px;
  border: none; background: var(--m-card); border-radius: var(--m-radius); box-shadow: var(--m-shadow); cursor: pointer; text-align: center; }
.badge.worn { box-shadow: 0 0 0 2px var(--m-primary) inset; }
.badge.lock { cursor: default; opacity: .92; }
.bi { width: 46px; height: 46px; border-radius: 50%; display: grid; place-items: center; font-size: 24px; }
.bi.gray { background: var(--m-card-2); color: var(--m-text-dim); }
.bn { font-size: 14px; font-weight: 600; color: var(--m-text); }
.bd { font-size: 11px; color: var(--m-text-dim); line-height: 1.4; min-height: 30px; }
.tag { font-size: 10.5px; padding: 1px 8px; border-radius: 999px; background: var(--m-card-2); color: var(--m-text-dim); }
.tag.on { background: var(--m-primary-soft); color: var(--m-primary); }
.pg { width: 80%; height: 5px; border-radius: 999px; background: var(--m-border); overflow: hidden; margin-top: 2px; }
.pg i { display: block; height: 100%; background: var(--m-primary); border-radius: 999px; }
.ptxt { font-size: 10.5px; color: var(--m-text-dim); font-variant-numeric: tabular-nums; }
</style>
