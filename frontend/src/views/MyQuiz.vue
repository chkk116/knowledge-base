<template>
  <div class="page">
    <div class="hd">
      <div>
        <h1>我的测验</h1>
        <p class="sub">用 AI 从你的资料里出题，只属于你自己，不进公共题库</p>
      </div>
      <div class="hd-acts">
        <el-button type="primary" :disabled="!list.length" @click="$router.push('/my-quiz/run')">开始刷题</el-button>
        <el-button plain @click="$router.push('/ai')">让 AI 出题</el-button>
      </div>
    </div>

    <div class="stats">
      <div class="st"><b>{{ stats.questions ?? 0 }}</b><span>我的题目</span></div>
      <div class="st"><b>{{ stats.limit ?? 300 }}</b><span>上限</span></div>
      <el-button size="small" plain @click="load">刷新</el-button>
    </div>

    <el-alert
      type="info" :closable="false" show-icon
      title="怎么加题：打开「AI 问答」，直接说「用我的《xxx》这篇文档出 5 道单选题」，Agent 会出题并存入这里。"
      class="tipbar" />

    <div v-if="loading" class="tip">加载中…</div>
    <div v-else-if="!list.length" class="empty">
      <div class="empty-ico">📝</div>
      <div class="empty-t">还没有自己的题目</div>
      <div class="empty-s">去 AI 问答里让它按你的文档出几道题</div>
      <el-button type="primary" @click="$router.push('/ai')">去出题</el-button>
    </div>

    <div v-else class="list">
      <div v-for="(q, i) in list" :key="q.id" class="card">
        <div class="c-hd">
          <el-tag size="small" effect="plain">{{ TYPE_CN[q.type] || q.type }}</el-tag>
          <span class="idx">#{{ i + 1 }}</span>
          <span class="spacer" />
          <el-button size="small" text type="danger" @click="remove(q)">删除</el-button>
        </div>
        <div class="c-q" v-html="q.content"></div>
        <el-collapse v-if="q.analysis" class="c-an">
          <el-collapse-item title="看解析" :name="q.id">
            <div v-html="q.analysis"></div>
          </el-collapse-item>
        </el-collapse>
      </div>
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { selfExamStats, selfExamQuestions, selfExamDeleteQuestion } from '../api/training'

const TYPE_CN = { SINGLE: '单选', MULTI: '多选', JUDGE: '判断', FILL: '填空', SUBJECTIVE: '主观' }
const loading = ref(false)
const list = ref([])
const stats = ref({})

async function load() {
  loading.value = true
  try {
    const [a, b] = await Promise.all([selfExamStats(), selfExamQuestions()])
    stats.value = a.data || {}
    list.value = b.data || []
  } catch (e) {
    ElMessage.error(e?.response?.data?.error || '加载失败')
  } finally {
    loading.value = false
  }
}

async function remove(q) {
  try {
    await ElMessageBox.confirm('删除后不可恢复，确定删除这道题吗？', '删除题目', { type: 'warning' })
    await selfExamDeleteQuestion(q.id)
    list.value = list.value.filter((x) => x.id !== q.id)
    stats.value = { ...stats.value, questions: Math.max(0, (stats.value.questions || 1) - 1) }
    ElMessage.success('已删除')
  } catch (e) {
    if (e !== 'cancel') ElMessage.error('删除失败')
  }
}

onMounted(load)
</script>

<style scoped>
.page { max-width: 900px; margin: 0 auto; padding: 20px 16px 60px; }
.hd { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.hd-acts { display: flex; gap: 8px; flex-shrink: 0; }
.hd h1 { font-size: 20px; margin: 0 0 4px; }
.sub { margin: 0; font-size: 13px; color: #6b7280; }
.stats { display: flex; align-items: center; gap: 18px; margin: 14px 0; }
.st { display: flex; align-items: baseline; gap: 6px; }
.st b { font-size: 20px; }
.st span { font-size: 12px; color: #6b7280; }
.tipbar { margin-bottom: 12px; }
.tip { padding: 24px; text-align: center; color: #6b7280; }
.empty { text-align: center; padding: 48px 16px; }
.empty-ico { font-size: 34px; }
.empty-t { font-size: 15px; font-weight: 600; margin-top: 8px; }
.empty-s { font-size: 13px; color: #6b7280; margin: 6px 0 14px; }
.list { display: flex; flex-direction: column; gap: 10px; }
.card { background: #fff; border: 1px solid #ebeef5; border-radius: 10px; padding: 12px 14px; }
.c-hd { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; }
.idx { font-size: 12px; color: #9ca3af; }
.spacer { flex: 1; }
.c-q { font-size: 14.5px; line-height: 1.7; color: #1f2937; }
.c-an { margin-top: 6px; }
</style>
