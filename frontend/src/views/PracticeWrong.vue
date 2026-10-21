<template>
  <div class="page">
    <div class="hd">
      <div>
        <h1>错题本</h1>
        <p class="sub">练习里答错的题自动收进来，答对一次就移出</p>
      </div>
      <el-button text @click="$router.push('/exams')">返回考试列表</el-button>
    </div>

    <div class="stats">
      <div class="st"><b>{{ stats.wrong ?? list.length }}</b><span>待复习</span></div>
      <div class="st"><b>{{ stats.total ?? 0 }}</b><span>累计答题</span></div>
      <div class="st"><b>{{ stats.rate ?? 0 }}%</b><span>正确率</span></div>
      <el-button size="small" plain :disabled="!list.length" @click="reload">刷新</el-button>
    </div>

    <div v-if="loading" class="tip">加载中…</div>
    <div v-else-if="!list.length" class="empty">
      <div class="empty-ico">✓</div>
      <div class="empty-t">还没有错题</div>
      <div class="empty-s">去练几道题，答错的会自动出现在这里</div>
      <el-button type="primary" @click="$router.push('/exams/practice')">开始练习</el-button>
    </div>

    <div v-else class="list">
      <div v-for="q in list" :key="q.id" class="card">
        <div class="c-hd">
          <el-tag size="small" effect="plain">{{ TYPE_CN[q.type] || '单选' }}</el-tag>
          <span v-if="q.category" class="cat">{{ q.category }}</span>
          <span class="spacer" />
          <el-button size="small" text type="primary" @click="markKnown(q)">已掌握</el-button>
        </div>
        <div class="c-q" v-html="q.content"></div>
        <div class="c-ans">
          <div class="line bad">
            <span class="lab">我的答案</span>
            <span>{{ q.lastAnswer || '未作答' }}</span>
          </div>
          <div class="line ok">
            <span class="lab">正确答案</span>
            <div class="opts-inline">
              <span v-for="c in q.correctAnswer" :key="c.label"><b>{{ c.label }}</b> {{ strip(c.content) }}</span>
            </div>
          </div>
          <div v-if="q.analysis" class="line ana">
            <span class="lab">解析</span>
            <span v-html="q.analysis"></span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
/** 错题本（2026-10-21）：练习流水派生，答对自动移出，也可手动「已掌握」 */
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { practiceWrongList, practiceWrongClear, practiceStats } from '../api/training'

const TYPE_CN = { SINGLE: '单选', MULTI: '多选', JUDGE: '判断', FILL: '填空', ESSAY: '简答' }
const list = ref([])
const stats = ref({})
const loading = ref(true)

const strip = (html) => String(html || '').replace(/<[^>]+>/g, '').slice(0, 60)

async function reload() {
  loading.value = true
  try {
    const [a, b] = await Promise.all([
      practiceWrongList().catch(() => ({ data: [] })),
      practiceStats().catch(() => ({ data: {} })),
    ])
    list.value = a.data || []
    stats.value = b.data || {}
  } finally {
    loading.value = false
  }
}

async function markKnown(q) {
  try {
    await practiceWrongClear(q.id)
    list.value = list.value.filter((x) => x.id !== q.id)
    ElMessage.success('已移出错题本')
    practiceStats().then((r) => { stats.value = r.data || {} }).catch(() => {})
  } catch (e) {
    ElMessage.error(e?.response?.data?.error || '操作失败')
  }
}

onMounted(reload)
</script>

<style scoped>
.page { max-width: 860px; margin: 0 auto; padding: 24px 16px 60px; }
.hd { display: flex; justify-content: space-between; align-items: flex-start; gap: 12px; }
.hd h1 { margin: 0; font-size: 22px; }
.hd .sub { color: #64748b; font-size: 13px; margin: 6px 0 0; }
.stats { display: flex; align-items: center; gap: 22px; padding: 14px 16px; margin: 16px 0;
  background: #fff; border: 1px solid #eef2f7; border-radius: 14px; }
.st { display: flex; flex-direction: column; gap: 2px; }
.st b { font-size: 20px; color: #0f172a; }
.st span { font-size: 12px; color: #94a3b8; }
.stats .el-button { margin-left: auto; }
.tip { color: #94a3b8; padding: 40px 0; text-align: center; }
.empty { text-align: center; padding: 60px 20px; display: flex; flex-direction: column; align-items: center; gap: 8px; }
.empty-ico { width: 54px; height: 54px; border-radius: 50%; background: #dcfce7; color: #16a34a;
  display: grid; place-items: center; font-size: 24px; font-weight: 700; }
.empty-t { font-size: 16px; font-weight: 600; color: #0f172a; }
.empty-s { font-size: 13px; color: #94a3b8; margin-bottom: 8px; }
.list { display: flex; flex-direction: column; gap: 12px; }
.card { background: #fff; border: 1px solid #eef2f7; border-radius: 14px; padding: 16px; }
.c-hd { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.cat { font-size: 12px; color: #94a3b8; }
.spacer { flex: 1; }
.c-q { font-size: 15px; line-height: 1.75; color: #0f172a; }
.c-q :deep(img) { max-width: 100%; }
.c-ans { margin-top: 12px; display: flex; flex-direction: column; gap: 8px; }
.line { display: flex; gap: 10px; font-size: 13px; line-height: 1.6; }
.lab { flex: none; width: 62px; color: #94a3b8; }
.line.bad span:last-child { color: #b91c1c; }
.line.ok { color: #15803d; }
.opts-inline { display: flex; flex-direction: column; gap: 3px; }
.line.ana { color: #475569; }
@media (max-width: 640px) {
  .stats { flex-wrap: wrap; gap: 14px; }
}
:deep(html.dark) .stats, :deep(html.dark) .card { background: #1b1b2e; border-color: #2c2c44; }
:deep(html.dark) .c-q, :deep(html.dark) .st b, :deep(html.dark) .empty-t { color: #e5e7eb; }
</style>
