<template>
  <div class="page">
    <div class="hd">
      <div>
        <h1>举报与反馈</h1>
        <p class="sub">待处理 {{ pending }} 条</p>
      </div>
      <el-radio-group v-model="status" size="small" @change="load">
        <el-radio-button value="PENDING">待处理</el-radio-button>
        <el-radio-button value="HANDLED">已处理</el-radio-button>
        <el-radio-button value="REJECTED">已驳回</el-radio-button>
        <el-radio-button value="">全部</el-radio-button>
      </el-radio-group>
    </div>

    <div v-if="loading" class="tip">加载中…</div>
    <div v-else-if="!list.length" class="tip">没有记录</div>
    <div v-else class="list">
      <div v-for="r in list" :key="r.id" class="card">
        <div class="c-hd">
          <el-tag size="small" effect="plain">{{ TYPE_CN[r.targetType] || r.targetType }}</el-tag>
          <span class="rid">#{{ r.id }}</span>
          <span class="who">{{ r.reporterName || ('用户 ' + r.reporterId) }}</span>
          <span class="spacer" />
          <el-tag size="small" :type="r.status === 'PENDING' ? 'warning' : (r.status === 'HANDLED' ? 'success' : 'info')">
            {{ STATUS_CN[r.status] }}
          </el-tag>
        </div>
        <div class="c-reason">{{ r.reason }}<span v-if="r.targetId"> · 对象 #{{ r.targetId }}</span></div>
        <div v-if="r.detail" class="c-detail">{{ r.detail }}</div>
        <div class="c-foot">
          <span class="time">{{ (r.createdAt || '').replace('T', ' ').slice(0, 16) }}</span>
          <span class="spacer" />
          <template v-if="r.status === 'PENDING'">
            <el-button size="small" @click="handle(r, 'REJECTED')">驳回</el-button>
            <el-button size="small" type="primary" @click="handle(r, 'HANDLED')">标记已处理</el-button>
          </template>
          <span v-else class="note">{{ r.handleNote || '—' }}</span>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminReports, handleReport } from '../../api/report'

const TYPE_CN = { POST: '文章', COMMENT: '评论', DOC: '文档', DM: '私信', USER: '用户', FEEDBACK: '反馈' }
const STATUS_CN = { PENDING: '待处理', HANDLED: '已处理', REJECTED: '已驳回' }

const status = ref('PENDING')
const list = ref([])
const pending = ref(0)
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const { data } = await adminReports(status.value)
    list.value = data.items || []
    pending.value = data.pending || 0
  } catch (e) {
    ElMessage.error(e?.response?.data?.error || '加载失败')
  } finally {
    loading.value = false
  }
}

async function handle(r, st) {
  let note = ''
  try {
    const { value } = await ElMessageBox.prompt(
      st === 'HANDLED' ? '处理说明（可选，会记录在案）' : '驳回理由（可选）',
      st === 'HANDLED' ? '标记已处理' : '驳回',
      { inputPlaceholder: '例如：已删除违规内容 / 内容正常，不构成违规', confirmButtonText: '确定', cancelButtonText: '取消' }
    )
    note = value || ''
  } catch (e) { return }
  try {
    await handleReport(r.id, st, note)
    ElMessage.success('已更新')
    load()
  } catch (e) {
    ElMessage.error(e?.response?.data?.error || '操作失败')
  }
}

onMounted(load)
</script>

<style scoped>
.page { max-width: 900px; margin: 0 auto; padding: 20px 16px 60px; }
.hd { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.hd h1 { font-size: 20px; margin: 0 0 4px; }
.sub { margin: 0; font-size: 13px; color: #6b7280; }
.tip { padding: 40px; text-align: center; color: #6b7280; }
.list { display: flex; flex-direction: column; gap: 10px; margin-top: 14px; }
.card { background: #fff; border: 1px solid #ebeef5; border-radius: 10px; padding: 12px 14px; }
.c-hd { display: flex; align-items: center; gap: 8px; font-size: 12.5px; color: #6b7280; }
.rid { color: #9ca3af; }
.who { color: #374151; }
.spacer { flex: 1; }
.c-reason { margin-top: 6px; font-size: 14px; font-weight: 600; color: #1f2937; }
.c-detail { margin-top: 4px; font-size: 13px; color: #4b5563; line-height: 1.7; white-space: pre-wrap; }
.c-foot { display: flex; align-items: center; gap: 8px; margin-top: 8px; }
.time { font-size: 12px; color: #9ca3af; }
.note { font-size: 12px; color: #6b7280; }
</style>
