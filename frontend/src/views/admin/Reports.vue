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
        <div class="c-reason">{{ r.reason }}
          <span v-if="r.targetTitle"> · <a v-if="r.link" :href="r.link" target="_blank" class="t-link">{{ r.targetTitle }}</a><span v-else>{{ r.targetTitle }}</span></span>
          <span v-if="r.authorName" class="t-author">作者：{{ r.authorName }}</span>
        </div>
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
    <el-dialog v-model="actOpen.open" title="处理举报" width="480px">
      <div v-if="actOpen.r" class="act">
        <p class="act-target">
          {{ TYPE_CN[actOpen.r.targetType] || actOpen.r.targetType }}
          <template v-if="actOpen.r.targetTitle"> · {{ actOpen.r.targetTitle }}</template>
          <template v-if="actOpen.r.authorName">（作者：{{ actOpen.r.authorName }}）</template>
        </p>
        <el-radio-group v-model="actOpen.action" class="act-radios">
          <el-radio v-if="['POST','COMMENT','DM'].includes(actOpen.r.targetType)" value="DELETE_CONTENT">删除/下架内容</el-radio>
          <el-radio v-if="actOpen.r.targetType !== 'FEEDBACK' && actOpen.r.authorId" value="BLOCK_AUTHOR">禁用作者账号</el-radio>
          <el-radio v-if="actOpen.r.targetType !== 'FEEDBACK' && actOpen.r.authorId" value="WARN_AUTHOR">仅警告作者</el-radio>
          <el-radio value="NONE">仅记录，不处置</el-radio>
        </el-radio-group>
        <el-input v-model="actOpen.note" type="textarea" :rows="3" maxlength="500"
                  placeholder="处理备注（会随结果一起通知举报人）" />
        <p class="act-tip">提交后：举报人收到结果通知；选择删除/禁用/警告时，相关作者也会收到通知。动作会写入操作审计。</p>
      </div>
      <template #footer>
        <el-button @click="actOpen.open = false">取消</el-button>
        <el-button type="primary" :loading="actOpen.saving" @click="submitHandle">提交处理</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
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

const actOpen = reactive({ open: false, r: null, action: 'DELETE_CONTENT', note: '', saving: false })

function handle(r, st) {
  if (st === 'REJECTED') {
    ElMessageBox.prompt('驳回理由（会通知举报人）', '驳回', {
      inputPlaceholder: '例如：内容正常，不构成违规', confirmButtonText: '确定', cancelButtonText: '取消'
    }).then(async ({ value }) => {
      try { await handleReport(r.id, st, value || '', 'NONE'); ElMessage.success('已驳回并通知举报人'); load() }
      catch (e) { ElMessage.error(e?.response?.data?.error || '操作失败') }
    }).catch(() => {})
    return
  }
  actOpen.r = r; actOpen.action = 'DELETE_CONTENT'; actOpen.note = ''; actOpen.open = true
}

async function submitHandle() {
  const r = actOpen.r
  actOpen.saving = true
  try {
    await handleReport(r.id, 'HANDLED', actOpen.note, actOpen.action)
    ElMessage.success('已处理，结果将通知举报人' + (actOpen.action !== 'NONE' ? '与相关作者' : ''))
    actOpen.open = false
    load()
  } catch (e) {
    ElMessage.error(e?.response?.data?.error || '操作失败')
  } finally { actOpen.saving = false }
}

const ACTION_TEXT = {
  DELETE_CONTENT: '删除/下架内容', BLOCK_AUTHOR: '禁用相关账号', WARN_AUTHOR: '仅警告作者', NONE: '仅记录，不处置',
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

.t-link { color: #2563eb; text-decoration: none; }
.t-link:hover { text-decoration: underline; }
.t-author { color: #9ca3af; font-weight: 400; font-size: 12px; margin-left: 8px; }
.act-target { margin: 0 0 10px; font-size: 13px; color: #4b5563; }
.act-radios { display: flex; flex-direction: column; gap: 8px; margin-bottom: 10px; }
.act-tip { margin: 8px 0 0; font-size: 12px; color: #9ca3af; line-height: 1.6; }
</style>