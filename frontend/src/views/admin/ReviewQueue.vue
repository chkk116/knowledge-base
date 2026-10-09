<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Check, Close, Document, UserFilled } from '@element-plus/icons-vue'
import { useRouter } from 'vue-router'
import { listPending, approveDoc, rejectDoc, listPendingAvatars, approveAvatar, rejectAvatar } from '../../api/admin'
import { errMsg } from '../../utils/errMsg'

const router = useRouter()
const activeTab = ref('docs')

/* ---------- 文章审核 ---------- */
const items = ref([])
const loading = ref(false)

const fmt = (t) => (t ? String(t).replace('T', ' ').slice(0, 16) : '—')
const loadDocs = async () => {
  loading.value = true
  try {
    const { data } = await listPending()
    items.value = Array.isArray(data) ? data : []
  } catch (e) {
    ElMessage.error(errMsg(e, '加载失败'))
  } finally {
    loading.value = false
  }
}

const approve = async (d) => {
  try {
    await approveDoc(d.id)
    ElMessage.success('已通过，帖子已公开')
    await loadDocs()
  } catch (e) {
    ElMessage.error(errMsg(e, '操作失败'))
  }
}
const reject = async (d) => {
  try {
    const r = await ElMessageBox.prompt('请填写驳回理由（将退回作者草稿，作者可见，必填）', '驳回审核 · ' + (d.title || ''),
      { confirmButtonText: '确定驳回', cancelButtonText: '取消', inputType: 'textarea',
        inputPlaceholder: '例如：内容不完整 / 含敏感词 / 需补充来源',
        inputValidator: (v) => (v && v.trim()) ? true : '请填写驳回理由后再提交' })
    const reason = (r && r.value) ? r.value.trim() : ''
    await rejectDoc(d.id, reason)
    ElMessage.success('已驳回，退回作者草稿')
    await loadDocs()
  } catch (e) {
    if (e === 'cancel' || e?.action === 'cancel') return
    ElMessage.error(errMsg(e, '操作失败'))
  }
}

/* ---------- 头像审核 ---------- */
const avatars = ref([])
const loadingAv = ref(false)

const loadAvatars = async () => {
  loadingAv.value = true
  try {
    const { data } = await listPendingAvatars()
    avatars.value = Array.isArray(data) ? data : []
  } catch (e) {
    ElMessage.error(errMsg(e, '加载失败'))
  } finally {
    loadingAv.value = false
  }
}

const approveAv = async (row) => {
  try {
    await approveAvatar(row.id)
    ElMessage.success('已通过，新头像已生效')
    await loadAvatars()
  } catch (e) {
    ElMessage.error(errMsg(e, '操作失败'))
  }
}
const rejectAv = async (row) => {
  try {
    await ElMessageBox.confirm(
      '驳回后该用户沿用原头像，其重新提交会再次进入待审。确定驳回 ' + (row.displayName || row.username) + ' 的新头像？',
      '驳回头像',
      { confirmButtonText: '确定驳回', cancelButtonText: '取消', type: 'warning' })
    await rejectAvatar(row.id)
    ElMessage.success('已驳回，沿用原头像')
    await loadAvatars()
  } catch (e) {
    if (e === 'cancel' || e?.action === 'cancel') return
    ElMessage.error(errMsg(e, '操作失败'))
  }
}

const onTabChange = (tab) => {
  if (tab === 'docs' && !items.value.length) loadDocs()
  if (tab === 'avatars' && !avatars.value.length) loadAvatars()
}

onMounted(loadDocs)
</script>

<template>
  <div class="rq-page">
    <div class="page-head">
      <div class="head-icon"><el-icon :size="22"><Document /></el-icon></div>
      <div class="head-text">
        <h2>审核中心</h2>
        <p>帖子审核：通过后公开可见；头像审核：通过后新头像才对外展示</p>
      </div>
      <el-tag v-if="activeTab === 'docs'" type="warning" effect="plain" round>待审核 {{ items.length }} 篇</el-tag>
      <el-tag v-else type="warning" effect="plain" round>待审核 {{ avatars.length }} 个头像</el-tag>
    </div>

    <el-card shadow="never" class="panel">
      <el-tabs v-model="activeTab" @tab-change="onTabChange">
        <!-- 文章审核 -->
        <el-tab-pane name="docs">
          <template #label>
            <span class="tab-label"><el-icon><Document /></el-icon> 帖子审核</span>
          </template>
          <el-table v-stack-table :data="items" v-loading="loading" empty-text="没有待审核的帖子 🎉">
            <el-table-column prop="id" label="ID" width="70" />
            <el-table-column label="标题" min-width="260">
              <template #default="{ row }">
                <span class="link" @click="router.push('/post/' + row.id)">{{ row.title }}</span>
              </template>
            </el-table-column>
            <el-table-column label="作者" width="140">
              <template #default="{ row }">{{ row.ownerName || ('#' + row.ownerId) }}</template>
            </el-table-column>
            <el-table-column label="提交时间" width="170">
              <template #default="{ row }">{{ fmt(row.createdAt) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="180">
              <template #default="{ row }">
                <el-button size="small" type="success" :icon="Check" @click="approve(row)">通过</el-button>
                <el-button size="small" type="danger" plain :icon="Close" @click="reject(row)">驳回</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <!-- 头像审核 -->
        <el-tab-pane name="avatars" lazy>
          <template #label>
            <span class="tab-label"><el-icon><UserFilled /></el-icon> 头像审核</span>
          </template>
          <el-table :data="avatars" v-loading="loadingAv" empty-text="没有待审核的头像 🎉">
            <el-table-column prop="id" label="ID" width="70" />
            <el-table-column label="用户" width="150">
              <template #default="{ row }">
                <span>{{ row.displayName }}</span>
                <span class="sub">{{ row.username }}</span>
              </template>
            </el-table-column>
            <el-table-column label="当前头像" width="110" align="center">
              <template #default="{ row }">
                <img v-if="row.avatar" :src="row.avatar" class="av-thumb old" alt="当前头像" />
                <span v-else class="av-default">默认</span>
              </template>
            </el-table-column>
            <el-table-column label="待审新头像" width="110" align="center">
              <template #default="{ row }">
                <img :src="row.avatarPending" class="av-thumb new" alt="待审头像" />
              </template>
            </el-table-column>
            <el-table-column label="待审地址" min-width="200">
              <template #default="{ row }">
                <span class="av-url">{{ row.avatarPending }}</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="180">
              <template #default="{ row }">
                <el-button size="small" type="success" :icon="Check" @click="approveAv(row)">通过</el-button>
                <el-button size="small" type="danger" plain :icon="Close" @click="rejectAv(row)">驳回</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </el-card>
  </div>
</template>

<style scoped>
.page-head { display: flex; align-items: center; gap: 14px; margin-bottom: 18px; flex-wrap: wrap; }
.head-icon {
  width: 46px; height: 46px; border-radius: 12px; display: flex; align-items: center; justify-content: center;
  background: #fef3c7; color: #d97706; flex-shrink: 0;
}
.head-text { flex: 1; min-width: 220px; }
.head-text h2 { margin: 0; font-size: 20px; font-weight: 700; color: #1e293b; }
.head-text p { margin: 4px 0 0; font-size: 13px; color: #94a3b8; }
.panel { border-radius: 14px; }
.link { color: #4f46e5; cursor: pointer; }
.link:hover { text-decoration: underline; }
.tab-label { display: inline-flex; align-items: center; gap: 6px; }
.sub { margin-left: 6px; font-size: 12px; color: #94a3b8; }
.av-thumb { width: 52px; height: 52px; border-radius: 50%; object-fit: cover; }
.av-thumb.old { opacity: .65; }
.av-thumb.new { box-shadow: 0 0 0 2px #6366f1; }
.av-default {
  display: inline-block; padding: 2px 10px; border-radius: 999px; background: #f1f5f9; color: #64748b; font-size: 12px;
}
.av-url { font-size: 12px; color: #94a3b8; word-break: break-all; }

html.dark .head-text h2 { color: var(--el-text-color-primary); }
html.dark .link { color: #a5b4fc; }
html.dark .av-default { background: #334155; color: #94a3b8; }
html.dark .head-icon { background: rgb(217 119 6 / .2); color: #fcd34d; }
</style>
