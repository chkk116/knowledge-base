<script setup>
import { ref, onMounted, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listAllShares, adminDeleteShare, adminSetCapacity, getShareStorage,
  listPendingCapacityRequests, approveCapacityRequest, rejectCapacityRequest,
  listPendingCreateRequests, approveCreateRequest, rejectCreateRequest } from '../../api/docShare'
import { useAuthStore } from '../../store/auth'
import { errMsg } from '../../utils/errMsg'

const auth = useAuthStore()
const GB = 1024 * 1024 * 1024
const formatGb = (b) => ((b || 0) / GB).toFixed(1) + ' GB'

const shares = ref([])
const loading = ref(false)
const activeTab = ref('spaces')

// 创建审批（仅完全管理员）
const createReqList = ref([])
const createReqLoading = ref(false)
const loadCreateReq = async () => {
  createReqLoading.value = true
  try {
    const { data } = await listPendingCreateRequests()
    createReqList.value = data || []
  } catch (e) {
    ElMessage.error('加载创建审批列表失败')
    createReqList.value = []
  } finally {
    createReqLoading.value = false
  }
}
const doApproveCreate = async (r) => {
  if (approveCreateSaving.value) return
  approveCreateSaving.value = true
  try {
    await approveCreateRequest(r.id)
    ElMessage.success('已批准并创建空间：' + r.requestedName)
    await loadCreateReq()
  } catch (e) {
    ElMessage.error(errMsg(e, '审批失败'))
  } finally {
    approveCreateSaving.value = false
  }
}
const openRejectCreate = (r) => {
  rejectTarget.value = r
  rejectCreate.value = true
  rejectComment.value = ''
}
const doRejectCreate = async () => {
  if (!rejectTarget.value) return
  rejectSaving.value = true
  try {
    await rejectCreateRequest(rejectTarget.value.id, rejectComment.value.trim())
    ElMessage.success('已驳回')
    rejectCreate.value = false
    await loadCreateReq()
  } catch (e) {
    ElMessage.error(errMsg(e, '驳回失败'))
  } finally {
    rejectSaving.value = false
  }
}

// 容量设置弹窗
const capDialog = ref(false)
const capTarget = ref(null)
const capGb = ref(null)
const capInfo = ref(null)
const capSaving = ref(false)

const load = async () => {
  loading.value = true
  try {
    const { data } = await listAllShares()
    shares.value = data || []
  } catch (e) {
    ElMessage.error('加载失败')
  } finally {
    loading.value = false
  }
}

const openCap = (row) => {
  capTarget.value = row
  capGb.value = row.capacity ? Number((row.capacity / GB).toFixed(1)) : null
  capInfo.value = null
  getShareStorage(row.id).then(({ data }) => { capInfo.value = data }).catch(() => {})
  capDialog.value = true
}

const saveCap = async () => {
  capSaving.value = true
  try {
    const capacity = capGb.value ? Math.round(capGb.value * GB) : 0
    await adminSetCapacity(capTarget.value.id, capacity)
    ElMessage.success(capacity ? '已设置空间容量' : '已取消空间容量上限')
    capDialog.value = false
    await load()
  } catch (e) {
    ElMessage.error(errMsg(e, '设置失败'))
  } finally {
    capSaving.value = false
  }
}

const remove = async (row) => {
  try {
    await ElMessageBox.confirm('确定删除「' + row.name + '」？该空间下所有文档将被一并删除。', '提示', { type: 'warning' })
  } catch { return }
  try {
    await adminDeleteShare(row.id)
    ElMessage.success('已删除')
    await load()
  } catch (e) {
    ElMessage.error(errMsg(e, '删除失败'))
  }
}

// ---------- 扩容审批（仅完全管理员） ----------
const capReqList = ref([])
const capReqLoading = ref(false)
const rejectDialog = ref(false)
const rejectTarget = ref(null)
const rejectComment = ref('')
const rejectSaving = ref(false)
const rejectCreate = ref(false)
const approveCreateSaving = ref(false)
const approveCapSaving = ref(false)

function fmtSize(n) {
  if (!n && n !== 0) return '—'
  const GB = 1024 * 1024 * 1024
  if (n === 0) return '不限'
  return (n / GB).toFixed(1) + ' GB'
}
function fmtTime(s) {
  if (!s) return ''
  const d = new Date(s.replace(' ', 'T'))
  if (isNaN(d)) return s
  const p = (x) => String(x).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

const loadCapReq = async () => {
  capReqLoading.value = true
  try {
    const { data } = await listPendingCapacityRequests()
    capReqList.value = data || []
  } catch (e) {
    ElMessage.error('加载审批列表失败')
    capReqList.value = []
  } finally {
    capReqLoading.value = false
  }
}
const doApprove = async (r) => {
  if (approveCapSaving.value) return
  approveCapSaving.value = true
  try {
    await approveCapacityRequest(r.id)
    ElMessage.success('已批准并生效：' + r.shareName + ' 扩容至 ' + fmtSize(r.requestedCapacity))
    await loadCapReq()
  } catch (e) {
    ElMessage.error(errMsg(e, '审批失败'))
  } finally {
    approveCapSaving.value = false
  }
}
const openReject = (r) => {
  rejectTarget.value = r
  rejectComment.value = ''
  rejectDialog.value = true
}
const doReject = async () => {
  if (!rejectTarget.value) return
  rejectSaving.value = true
  try {
    await rejectCapacityRequest(rejectTarget.value.id, rejectComment.value.trim())
    ElMessage.success('已驳回')
    rejectDialog.value = false
    await loadCapReq()
  } catch (e) {
    ElMessage.error(errMsg(e, '驳回失败'))
  } finally {
    rejectSaving.value = false
  }
}

onMounted(() => {
  if (auth.hasPerm('SHARE_VIEW')) {
    load()
    if (auth.isAdmin) { loadCapReq(); loadCreateReq() }
  }
})

// 切到审批页签时刷新待审列表
watch(activeTab, (t) => {
  if (t === 'approval') loadCapReq()
  if (t === 'createApproval') loadCreateReq()
})
</script>

<template>
  <div v-if="auth.hasPerm('SHARE_VIEW')">
    <el-tabs v-model="activeTab" class="doc-tabs">
      <el-tab-pane label="空间列表" name="spaces">
        <el-table v-stack-table :data="shares" border stripe v-loading="loading">
          <el-table-column prop="id" label="ID" width="70"  class-name="stack-hide" />
          <el-table-column prop="name" label="空间名称" />
          <el-table-column prop="description" label="描述" />
          <el-table-column label="所有者" width="140">
            <template #default="{ row }">{{ row.ownerName || '-' }}</template>
          </el-table-column>
          <el-table-column prop="status" label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'">{{ row.status === 'ACTIVE' ? '正常' : '归档' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="类型" width="80">
            <template #default="{ row }">
              <el-tag :type="row.type === 'PERSONAL' ? 'info' : 'success'">{{ row.type === 'PERSONAL' ? '个人' : '团队' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="容量上限" width="110" class-name="stack-hide">
            <template #default="{ row }">
              <span>{{ row.capacity ? formatGb(row.capacity) : '不限' }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="createdAt" label="创建时间" width="170" />
          <el-table-column label="操作" width="240" fixed="right">
            <template #default="{ row }">
              <el-button v-if="auth.isAdmin" link type="primary" @click="openCap(row)">设容量</el-button>
              <el-button v-if="auth.isAdmin" link type="warning" @click="activeTab = 'approval'">扩容审批</el-button>
              <el-button v-if="auth.hasPerm('SHARE_DELETE')" link type="danger" @click="remove(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
      <el-tab-pane v-if="auth.isAdmin" name="approval">
        <template #label>扩容审批<el-badge v-if="capReqList.length" :value="capReqList.length" :max="99" class="tab-badge" /></template>
        <div v-loading="capReqLoading" class="cap-req-wrap">
          <el-empty v-if="!capReqList.length" description="暂无待审批的扩容申请" :image-size="60" />
          <div v-for="r in capReqList" :key="r.id" class="cr-item">
            <div class="cr-head">
              <span class="cr-space">{{ r.shareName || ('空间#' + r.shareId) }}</span>
              <el-tag size="small" type="warning">待审批</el-tag>
            </div>
            <div class="cr-body">
              <div class="cr-line"><span class="cr-k">申请人</span>{{ r.applicantName }}</div>
              <div class="cr-line"><span class="cr-k">当前容量</span>{{ fmtSize(r.currentCapacity) }}</div>
              <div class="cr-line"><span class="cr-k">申请扩容至</span><b>{{ fmtSize(r.requestedCapacity) }}</b></div>
              <div class="cr-line" v-if="r.reason"><span class="cr-k">理由</span>{{ r.reason }}</div>
              <div class="cr-line cr-sub">{{ fmtTime(r.createdAt) }}</div>
            </div>
            <div class="cr-actions">
              <el-button type="success" size="small" :loading="approveCapSaving" @click="doApprove(r)">批准并扩容</el-button>
              <el-button type="danger" size="small" plain @click="openReject(r)">驳回</el-button>
            </div>
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane v-if="auth.isAdmin" name="createApproval">
        <template #label>创建审批<el-badge v-if="createReqList.length" :value="createReqList.length" :max="99" class="tab-badge" /></template>
        <div v-loading="createReqLoading" class="cap-req-wrap">
          <el-empty v-if="!createReqList.length" description="暂无待审批的创建申请" :image-size="60" />
          <div v-for="r in createReqList" :key="r.id" class="cr-item">
            <div class="cr-head">
              <span class="cr-space">{{ r.requestedName || '未命名空间' }}</span>
              <el-tag size="small" type="warning">待审批</el-tag>
            </div>
            <div class="cr-body">
              <div class="cr-line"><span class="cr-k">申请人</span>{{ r.applicantName }}</div>
              <div class="cr-line" v-if="r.requestedDescription"><span class="cr-k">描述</span>{{ r.requestedDescription }}</div>
              <div class="cr-line" v-if="r.requestedColor"><span class="cr-k">主题色</span>{{ r.requestedColor }}</div>
              <div class="cr-line" v-if="r.reason"><span class="cr-k">理由</span>{{ r.reason }}</div>
              <div class="cr-line cr-sub">{{ fmtTime(r.createdAt) }}</div>
            </div>
            <div class="cr-actions">
              <el-button type="success" size="small" :loading="approveCreateSaving" @click="doApproveCreate(r)">批准并创建</el-button>
              <el-button type="danger" size="small" plain @click="openRejectCreate(r)">驳回</el-button>
            </div>
          </div>
        </div>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="capDialog" :title="'空间容量 · ' + (capTarget?.name || '')" width="420px">
      <el-form label-width="80px">
        <el-form-item label="当前已用">
          <span>{{ capInfo ? formatGb(capInfo.used) : '—' }}</span>
        </el-form-item>
        <el-form-item label="容量上限">
          <el-input-number v-model="capGb" :min="0" :max="1024" :step="1" :precision="1" style="width: 160px" />
          <span class="hint">GB；0 = 不限（仅受用户总配额约束）。新建团队空间默认 2GB</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="capDialog = false">取消</el-button>
        <el-button type="primary" :loading="capSaving" @click="saveCap">保存</el-button>
      </template>
    </el-dialog>

    <!-- 驳回理由 -->
    <el-dialog v-model="rejectDialog" :title="'驳回扩容申请 · ' + (rejectTarget?.shareName || '')" width="420px">
      <el-input v-model="rejectComment" type="textarea" :rows="3" maxlength="200" show-word-limit placeholder="驳回理由（选填）" />
      <template #footer>
        <el-button @click="rejectDialog = false">取消</el-button>
        <el-button type="danger" :loading="rejectSaving" @click="doReject">确认驳回</el-button>
      </template>
    </el-dialog>

    <!-- 驳回创建申请 -->
    <el-dialog v-model="rejectCreate" :title="'驳回创建申请 · ' + (rejectTarget?.requestedName || '')" width="420px">
      <el-input v-model="rejectComment" type="textarea" :rows="3" maxlength="200" show-word-limit placeholder="驳回理由（选填）" />
      <template #footer>
        <el-button @click="rejectCreate = false">取消</el-button>
        <el-button type="danger" :loading="rejectSaving" @click="doRejectCreate">确认驳回</el-button>
      </template>
    </el-dialog>
  </div>
  <el-empty v-else description="无权限访问：需要「共享文档管理（查看）」权限" />
</template>

<style scoped>
.bar { display: flex; align-items: center; margin-bottom: 16px; }
.title { margin: 0; font-size: 18px; color: #0f172a; }
.hint { color: #94a3b8; font-size: 12px; margin-left: 8px; }

/* 扩容审批列表 */
.cr-item { border: 1px solid #eef0f4; border-radius: 8px; padding: 12px 14px; margin-bottom: 12px; }
.cr-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 8px; }
.cr-space { font-weight: 600; color: #0f172a; font-size: 14px; }
.cr-body { display: flex; flex-wrap: wrap; gap: 4px 22px; }
.cr-line { font-size: 13px; color: #334155; }
.cr-line .cr-k { color: #94a3b8; margin-right: 6px; }
.cr-sub { width: 100%; color: #94a3b8; font-size: 12px; margin-top: 2px; }
.cr-actions { margin-top: 10px; display: flex; gap: 8px; }
html.dark .cr-item { border-color: #2a2a35; }
html.dark .cr-space { color: #e7e7ee; }
html.dark .cr-line { color: #c2c6d2; }

.doc-tabs { margin-bottom: 4px; }
.cap-req-wrap { min-height: 120px; padding: 4px 2px; }
.tab-badge { margin-left: 6px; }

/* ===== 响应式（统一断点 1024 / 640，只写布局与尺寸） ===== */
@media (max-width: 1024px) {
  .bar { flex-wrap: wrap; gap: 10px; }
  .cr-item { padding: 12px; }
  .cr-head { flex-wrap: wrap; gap: 6px; }
  .cr-body { gap: 4px 16px; }
  .el-tabs :deep(.el-table) { max-width: 100%; }
}
@media (max-width: 640px) {
  .bar { margin-bottom: 12px; }
  .cr-item { padding: 10px; margin-bottom: 10px; }
  .cr-space { font-size: 13px; overflow: hidden; white-space: nowrap; text-overflow: ellipsis; }
  .cr-body { gap: 4px 12px; }
  .cr-line { font-size: 12px; width: 100%; overflow: hidden; white-space: nowrap; text-overflow: ellipsis; }
  .cr-actions { flex-wrap: wrap; gap: 8px; }
  .cr-actions :deep(.el-button) { flex: 1 1 auto; margin-left: 0; }
  .hint { margin-left: 0; display: block; }
  .el-form :deep(.el-input-number) { width: 100% !important; }
}
</style>
