<template>
  <el-dialog
    :model-value="modelValue"
    title="版本对比"
    width="min(920px, 94vw)"
    top="5vh"
    destroy-on-close
    @update:model-value="(v) => emit('update:modelValue', v)"
  >
    <div class="df-head">
      <span class="df-side old">{{ oldLabel || '旧版本' }}</span>
      <span class="df-arrow">→</span>
      <span class="df-side new">{{ newLabel || '当前内容' }}</span>
      <span class="df-grow"></span>
      <el-tag size="small" type="success">+{{ stats.added }} 段</el-tag>
      <el-tag size="small" type="danger">-{{ stats.removed }} 段</el-tag>
      <el-checkbox v-model="onlyDiff" size="small">只看差异</el-checkbox>
    </div>

    <div class="df-body">
      <p v-if="!ops.length" class="df-empty">两个版本没有差异。</p>
      <template v-else>
        <div v-for="(op, i) in shown" :key="i" class="df-row" :class="op.type">
          <span class="df-mark">{{ op.type === 'add' ? '+' : op.type === 'del' ? '−' : ' ' }}</span>
          <span class="df-text">{{ op.text }}</span>
        </div>
        <p v-if="onlyDiff && !shown.length" class="df-empty">除未改动的段落外没有差异。</p>
      </template>
    </div>
  </el-dialog>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { diffParagraphs, diffStats, htmlToParagraphs } from '../utils/textDiff'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  oldHtml: { type: String, default: '' },
  newHtml: { type: String, default: '' },
  oldLabel: { type: String, default: '' },
  newLabel: { type: String, default: '' }
})
const emit = defineEmits(['update:modelValue'])

const onlyDiff = ref(true)
const ops = ref([])

watch(
  () => [props.modelValue, props.oldHtml, props.newHtml],
  ([open]) => {
    if (!open) return
    ops.value = diffParagraphs(htmlToParagraphs(props.oldHtml), htmlToParagraphs(props.newHtml))
  },
  { immediate: true }
)

const stats = computed(() => diffStats(ops.value))
// 「只看差异」保留少量上下文（未变段落只显示首行前 60 字，避免全屏对照）
const shown = computed(() => (onlyDiff.value ? ops.value.filter((o) => o.type !== 'same') : ops.value))
</script>

<style scoped>
.df-head { display: flex; align-items: center; gap: 10px; margin-bottom: 10px; font-size: 12.5px; }
.df-side { padding: 2px 8px; border-radius: 6px; background: var(--el-fill-color-light); }
.df-side.old { color: var(--el-color-danger); }
.df-side.new { color: var(--el-color-success); }
.df-arrow { color: var(--el-text-color-secondary); }
.df-grow { flex: 1 1 auto; }
.df-body { max-height: 68vh; overflow: auto; background: #fff; border: 1px solid var(--el-border-color-lighter); border-radius: 10px; padding: 12px 14px; }
.df-row { display: flex; gap: 10px; font-size: 13.5px; line-height: 1.75; padding: 2px 0; border-radius: 4px; }
.df-mark { flex: none; width: 14px; text-align: center; font-weight: 700; }
.df-text { white-space: pre-wrap; word-break: break-word; }
.df-row.add { background: rgba(34, 197, 94, .1); color: #15803d; }
.df-row.del { background: rgba(239, 68, 68, .09); color: #b91c1c; text-decoration: line-through; text-decoration-thickness: 1px; }
.df-row.same { color: var(--el-text-color-regular); }
.df-empty { text-align: center; color: var(--el-text-color-secondary); font-size: 13px; padding: 40px 0; }
</style>
