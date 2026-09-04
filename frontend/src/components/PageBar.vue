<script setup>
import { ref, computed, watch } from 'vue'

const props = defineProps({
  total: { type: Number, required: true },
  pageSize: { type: Number, default: 10 },
  modelValue: { type: Number, default: 1 },
})
const emit = defineEmits(['update:modelValue'])

const currentPage = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v),
})

const totalPages = computed(() =>
  Math.max(1, Math.ceil((props.total || 0) / props.pageSize))
)

// 紧凑页码：≤7 页全显示；否则首尾 + 当前页 ±1 + 省略号
const pages = computed(() => {
  const tp = totalPages.value
  const cur = currentPage.value
  if (tp <= 7) {
    return Array.from({ length: tp }, (_, i) => i + 1)
  }
  const arr = [1]
  if (cur > 3) arr.push('...')
  const start = Math.max(2, cur - 1)
  const end = Math.min(tp - 1, cur + 1)
  for (let i = start; i <= end; i++) arr.push(i)
  if (cur < tp - 2) arr.push('...')
  arr.push(tp)
  return arr
})

const jump = ref('')

const go = (p) => {
  const n = Math.min(totalPages.value, Math.max(1, p))
  if (n !== currentPage.value) currentPage.value = n
}
const prev = () => { if (currentPage.value > 1) go(currentPage.value - 1) }
const next = () => { if (currentPage.value < totalPages.value) go(currentPage.value + 1) }
const onJump = () => {
  const n = parseInt(jump.value, 10)
  if (!isNaN(n)) { go(n); jump.value = '' }
}

// total / pageSize 变化后，若当前页越界则回退到最后一页
watch(() => [props.total, props.pageSize], () => {
  if (props.modelValue > totalPages.value) emit('update:modelValue', totalPages.value)
})
</script>

<template>
  <div class="pagebar">
    <span class="total">共 {{ total }} 条</span>

    <div class="nav">
      <button class="arrow" :disabled="currentPage === 1" aria-label="上一页" @click="prev">‹</button>
      <button
        v-for="p in pages"
        :key="String(p)"
        :class="['pill', { active: p === currentPage, ellipsis: p === '...' }]"
        :disabled="p === '...'"
        @click="p !== '...' && go(p)"
      >{{ p === '...' ? '···' : p }}</button>
      <button class="arrow" :disabled="currentPage === totalPages" aria-label="下一页" @click="next">›</button>
    </div>

    <div class="jump">
      跳至
      <input v-model="jump" type="number" min="1" :max="totalPages" @keyup.enter="onJump" />
      页
    </div>
  </div>
</template>

<style scoped>
.pagebar {
  display: flex;
  align-items: center;
  gap: 14px;
  flex-wrap: wrap;
  margin-top: 16px;
  justify-content: flex-end;
  font-size: 13px;
  color: #475569;
}
.total { color: #94a3b8; white-space: nowrap; }
.nav { display: flex; align-items: center; gap: 6px; }

.pill,
.arrow {
  min-width: 32px;
  height: 32px;
  padding: 0 8px;
  border: 1px solid #e2e8f0;
  border-radius: 999px;
  background: #fff;
  color: #475569;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  line-height: 1;
  transition: .15s;
}
.pill:hover:not(.active):not(:disabled),
.arrow:hover:not(:disabled) {
  border-color: #c7d2fe;
  color: #4f46e5;
  background: #eef2ff;
}
.pill.active {
  background: linear-gradient(135deg, #6366f1, #4f46e5);
  border-color: transparent;
  color: #fff;
  font-weight: 600;
  box-shadow: 0 2px 8px rgba(79, 70, 229, .35);
}
.arrow:disabled,
.pill:disabled { opacity: .45; cursor: not-allowed; }
.ellipsis {
  border-color: transparent;
  background: transparent;
  cursor: default;
  min-width: 20px;
  padding: 0;
}

.jump {
  display: flex;
  align-items: center;
  gap: 6px;
  color: #94a3b8;
  white-space: nowrap;
}
.jump input {
  width: 52px;
  height: 30px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  text-align: center;
  color: #1e293b;
  background: #fff;
  font-size: 13px;
}
.jump input:focus { outline: none; border-color: #6366f1; }

/* 暗色模式适配 */
html.dark .pagebar { color: #c2c6d2; }
html.dark .total,
html.dark .jump { color: var(--el-text-color-secondary); }
html.dark .pill,
html.dark .arrow {
  background: #1c1c26;
  border-color: #2c2c36;
  color: #c2c6d2;
}
html.dark .pill:hover:not(.active):not(:disabled),
html.dark .arrow:hover:not(:disabled) {
  border-color: #4f46e5;
  color: #a5b4fc;
  background: #232347;
}
html.dark .pill.active {
  background: linear-gradient(135deg, #6366f1, #4f46e5);
  color: #fff;
  border-color: transparent;
}
html.dark .jump input {
  background: #1c1c26;
  border-color: #2c2c36;
  color: #e7e9ee;
}
html.dark .jump input:focus { border-color: #6366f1; }

@media (max-width: 640px) {
  .pagebar { justify-content: center; gap: 10px; }
  .jump { display: none; }
}
</style>
