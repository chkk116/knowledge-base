<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { searchTest } from '../../api/admin'

/**
 * 检索测试（仅系统管理员）：
 * 输入关键词 → 走与用户搜索完全相同的后端链路 → 展示命中明细 + 实际引擎 + 耗时。
 * 用途：发完文章快速验证「搜得到、搜得准」；排查「AI 答非所问」时先看检索质量。
 */
const q = ref('')
const category = ref('')
const loading = ref(false)
const ran = ref(false)
const result = ref(null) // { engine, meiliEnabled, tookMs, total, hits }

// 支持 ?q= 直达：AI 反馈页「高频未命中 → 检索测试」会带问题跳过来自动执行
const route = useRoute()
onMounted(() => {
  const q0 = String(route.query.q || '').trim()
  if (q0) { q.value = q0; run() }
})

const run = async () => {
  if (!q.value.trim()) return ElMessage.warning('输入要测试的关键词')
  loading.value = true
  try {
    const { data } = await searchTest({ q: q.value.trim(), category: category.value.trim() || undefined })
    result.value = data
    ran.value = true
  } catch (e) {
    ElMessage.error('检索测试失败：' + (e.response?.data?.error || e.response?.status || '网络异常'))
  } finally {
    loading.value = false
  }
}

const engineText = computed(() => {
  if (!result.value) return ''
  return result.value.engine === 'meili' ? 'Meilisearch' : 'MySQL LIKE'
})
const engineTagType = computed(() => (result.value && result.value.engine === 'meili' ? 'success' : 'info'))

// 摘要高亮：snippet 是纯文本，先转义再包 <mark>，避免把正文里的尖括号当标签渲染
const escapeHtml = (s) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
const highlight = (text) => {
  if (!text) return ''
  const kw = q.value.trim()
  if (!kw) return escapeHtml(text)
  const safe = kw.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  return escapeHtml(text).replace(new RegExp(safe.replace(/&amp;/g, '&amp;'), 'gi'), (m) => `<mark>${m}</mark>`)
}

const fmtTime = (t) => {
  if (!t) return '-'
  const d = new Date(t)
  return `${d.getMonth() + 1}-${d.getDate()} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}
</script>

<template>
  <div>
    <el-alert type="info" :closable="false" show-icon style="margin-bottom: 12px;">
      <template #title>
        用与用户搜索<b>完全相同的链路</b>跑一次查询，验证「搜得到、搜得准」。当前 Meilisearch
        {{ result && !result.meiliEnabled ? '未启用' : '状态见下方引擎标签' }}，未启用时自动走 MySQL LIKE（标题×5 / 正文×1 / 标签+3 / 分类+2）。
      </template>
    </el-alert>

    <el-card shadow="never">
      <template #header>
        <div class="head"><span>检索测试</span></div>
      </template>

      <div class="bar">
        <el-input v-model="q" placeholder="输入关键词，如：服务器初始化" clearable
                  style="max-width: 420px" @keyup.enter="run" />
        <el-input v-model="category" placeholder="分类（可留空）" clearable style="max-width: 180px" @keyup.enter="run" />
        <el-button type="primary" :loading="loading" @click="run">搜索</el-button>
      </div>

      <div v-if="result" class="meta">
        <el-tag size="small" :type="engineTagType" effect="dark">引擎：{{ engineText }}</el-tag>
        <el-tag size="small" effect="plain">命中 {{ result.total }} 条</el-tag>
        <el-tag size="small" effect="plain">耗时 {{ result.tookMs }}ms</el-tag>
        <span v-if="!result.meiliEnabled" class="hint">Meilisearch 未启用（application.yml 里 kb.search.meili.host 为空）</span>
      </div>

      <el-empty v-if="ran && result && !result.total" description="没有命中 —— 换个关键词，或检查文章是否已发布" />

      <div v-if="result && result.hits.length" class="hits">
        <div v-for="(h, i) in result.hits" :key="h.id" class="hit">
          <div class="hit-head">
            <span class="rank">#{{ i + 1 }}</span>
            <span class="score" :title="'打分：标题×5 / 正文×1 / 标签+3 / 分类+2'">
              {{ h.score == null ? '-' : Number(h.score).toFixed(2) }}
            </span>
            <span class="title">{{ h.title }}</span>
            <el-tag v-if="h.category" size="small" effect="plain">{{ h.category }}</el-tag>
          </div>
          <div class="snippet" v-html="highlight(h.snippet)"></div>
          <div class="hit-foot">
            <span>{{ h.ownerName || '佚名' }}</span>
            <span>更新于 {{ fmtTime(h.updatedAt) }}</span>
            <a :href="'/post/' + h.id" target="_blank">打开文档 ↗</a>
          </div>
        </div>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.head { display: flex; align-items: center; justify-content: space-between; font-weight: 600; }
.bar { display: flex; gap: 10px; flex-wrap: wrap; align-items: center; }
.meta { display: flex; gap: 8px; align-items: center; margin-top: 12px; flex-wrap: wrap; }
.hint { color: #909399; font-size: 12px; }

.hits { margin-top: 14px; display: flex; flex-direction: column; gap: 10px; }
.hit { border: 1px solid var(--el-border-color-lighter); border-radius: 8px; padding: 10px 14px; }
.hit-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.rank { color: #909399; font-size: 12px; font-family: ui-monospace, Consolas, monospace; }
.score {
  font-size: 12px; font-family: ui-monospace, Consolas, monospace;
  background: rgb(var(--accent, 96 165 250) / .12); color: rgb(var(--accent, 96 165 250));
  padding: 1px 7px; border-radius: 10px;
}
.title { font-weight: 600; }
.snippet { margin-top: 6px; color: #606266; font-size: 13px; line-height: 1.7; }
.snippet :deep(mark) { background: rgb(var(--accent, 96 165 250) / .2); color: inherit; padding: 0 2px; border-radius: 2px; }
.hit-foot { margin-top: 6px; display: flex; gap: 14px; color: #909399; font-size: 12px; }
.hit-foot a { color: rgb(var(--accent, 96 165 250)); text-decoration: none; }

@media (max-width: 640px) {
  .bar { flex-direction: column; align-items: stretch; }
  .bar .el-input, .bar .el-input :deep(.el-input__wrapper) { max-width: none; width: 100%; }
}
</style>
