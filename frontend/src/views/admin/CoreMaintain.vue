<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listFeatures, updateFeature } from '../../api/features'
import { resetFeatureFlags } from '../../router'

const router = useRouter()
const items = ref([])
const loading = ref(false)
const savingKey = ref('')

// 分组展示：groupKey → 中文组名
const GROUP_LABELS = {
  ai: 'AI 能力',
  learning: '培训与考试',
  collaboration: '协作空间',
  core: '核心功能',
}
const groups = computed(() => {
  const map = {}
  for (const it of items.value) {
    const g = it.groupKey || 'core'
    if (!map[g]) map[g] = []
    map[g].push(it)
  }
  return Object.keys(map).map((k) => ({ key: k, label: GROUP_LABELS[k] || k, list: map[k] }))
})

const load = async () => {
  loading.value = true
  try {
    const { data } = await listFeatures()
    items.value = data || []
  } catch (e) {
    ElMessage.error('加载功能开关失败')
  } finally {
    loading.value = false
  }
}

// 单开关切换（带二次确认）
const onToggle = async (row) => {
  const turningOff = !row.enabled
  if (turningOff) {
    try {
      await ElMessageBox.confirm(
        '关闭后该功能入口将对所有用户隐藏，访问时跳转「敬请期待」。确定关闭「' + row.label + '」？',
        '关闭功能',
        { type: 'warning', confirmButtonText: '确定关闭', cancelButtonText: '取消' },
      )
    } catch {
      row.enabled = true // 取消则回滚 switch 视觉状态
      return
    }
  }
  savingKey.value = row.featureKey
  try {
    const { data } = await updateFeature(row.featureKey, { enabled: row.enabled })
    row.enabled = data.enabled
    row.updatedAt = data.updatedAt
    ElMessage.success((data.enabled ? '已开启' : '已关闭') + '「' + row.label + '」')
    // 开关变更即时生效：重置路由缓存并通知侧边栏菜单刷新（无需整页刷新）
    resetFeatureFlags()
    window.dispatchEvent(new Event('kb:feature-flags-changed'))
  } catch (e) {
    row.enabled = !row.enabled // 失败回滚
    ElMessage.error('操作失败')
  } finally {
    savingKey.value = ''
  }
}

// ===== 编辑对话框：管理维护状态 / 公告 / 恢复时间 / 跳转后台页 =====
const editVisible = ref(false)
const editing = ref(null)
const form = ref({ enabled: true, closeMode: 'HIDDEN', maintenanceMsg: '', expectedRestoreAt: '', adminPage: '' })
const saving = ref(false)

function openEdit(row) {
  editing.value = row
  form.value = {
    enabled: row.enabled,
    closeMode: row.closeMode || 'HIDDEN',
    maintenanceMsg: row.maintenanceMsg || '',
    // 后端 LocalDateTime 形如 2026-08-23T18:00，el-date-picker 的 datetime 格式可直接用
    expectedRestoreAt: row.expectedRestoreAt || '',
    adminPage: row.adminPage || '',
  }
  editVisible.value = true
}

async function saveEdit() {
  if (!editing.value) return
  saving.value = true
  try {
    const { data } = await updateFeature(editing.value.featureKey, {
      enabled: form.value.enabled,
      closeMode: form.value.closeMode,
      maintenanceMsg: form.value.maintenanceMsg,
      expectedRestoreAt: form.value.expectedRestoreAt || null,
      adminPage: form.value.adminPage || null,
    })
    Object.assign(editing.value, {
      enabled: data.enabled,
      closeMode: data.closeMode,
      maintenanceMsg: data.maintenanceMsg,
      expectedRestoreAt: data.expectedRestoreAt,
      adminPage: data.adminPage,
      updatedAt: data.updatedAt,
    })
    ElMessage.success('已保存「' + editing.value.label + '」维护信息')
    editVisible.value = false
    // 维护状态变更即时生效：重置路由缓存并通知侧边栏菜单刷新（无需整页刷新）
    resetFeatureFlags()
    window.dispatchEvent(new Event('kb:feature-flags-changed'))
  } catch (e) {
    ElMessage.error('保存失败')
  } finally {
    saving.value = false
  }
}

// 跳转到对应后台管理页
function goAdminPage(row) {
  if (row.adminPage) router.push(row.adminPage)
}

const enabledCount = computed(() => items.value.filter((i) => i.enabled).length)

onMounted(load)
</script>

<template>
  <div class="maintain">
    <div class="head">
      <div>
        <h2>功能开关</h2>
        <p class="sub">逐功能管理对外开放状态与维护信息。关闭后入口隐藏并跳转「敬请期待」，可随时重新开启或编辑维护公告。</p>
      </div>
      <el-tag type="info" effect="plain">已开启 {{ enabledCount }} / {{ items.length }}</el-tag>
    </div>

    <el-skeleton :loading="loading" :rows="6" animated>
      <template #default>
        <div v-for="g in groups" :key="g.key" class="group">
          <div class="group-title">{{ g.label }}</div>
          <el-table v-stack-table :data="g.list" border stripe>
            <el-table-column prop="label" label="功能" min-width="150">
              <template #default="{ row }">
                <span class="fname">{{ row.label }}</span>
                <code class="fkey">{{ row.featureKey }}</code>
              </template>
            </el-table-column>
            <el-table-column label="说明 / 维护信息" min-width="280">
              <template #default="{ row }">
                <div class="desc">{{ row.description }}</div>
                <div v-if="!row.enabled && row.maintenanceMsg" class="mmsg">
                  <el-icon><Warning /></el-icon> {{ row.maintenanceMsg }}
                </div>
                <div v-if="!row.enabled && row.expectedRestoreAt" class="mmsg restore">
                  预计恢复：{{ row.expectedRestoreAt.replace('T', ' ') }}
                </div>
                <div v-if="row.adminPage" class="admin-link" @click="goAdminPage(row)">
                  <el-icon><Setting /></el-icon> 前往后台管理
                </div>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="110" align="center">
              <template #default="{ row }">
                <el-tag :type="row.enabled ? 'success' : 'danger'" effect="light" size="small">
                  {{ row.enabled ? '已开启' : '维护中' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="170" align="center">
              <template #default="{ row }">
                <el-switch
                  :model-value="row.enabled"
                  :loading="savingKey === row.featureKey"
                  @change="(v) => { row.enabled = v; onToggle(row) }"
                  active-text="开"
                  inactive-text="关"
                />
                <el-button link type="primary" size="small" style="margin-left:8px" @click="openEdit(row)">
                  编辑
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </template>
    </el-skeleton>

    <!-- 编辑对话框 -->
    <el-dialog v-model="editVisible" title="编辑功能维护状态" width="520px" destroy-on-close>
      <template v-if="editing">
        <div class="dlg-feature">
          <span class="fname">{{ editing.label }}</span>
          <code class="fkey">{{ editing.featureKey }}</code>
        </div>
        <el-form label-width="96px">
          <el-form-item label="开放状态">
            <el-switch v-model="form.enabled" active-text="开启" inactive-text="关闭" />
          </el-form-item>
          <template v-if="!form.enabled">
            <el-form-item label="关闭时行为">
              <el-radio-group v-model="form.closeMode">
                <el-radio value="HIDDEN">隐藏入口（直接访问跳「敬请期待」）</el-radio>
                <el-radio value="MAINTENANCE">保留入口，展示「维护中」界面</el-radio>
              </el-radio-group>
            </el-form-item>
            <el-form-item label="维护公告">
              <el-input
                v-model="form.maintenanceMsg"
                type="textarea"
                :rows="2"
                maxlength="255"
                show-word-limit
                placeholder="展示给用户的说明，如「AI 模型训练中，预计今日恢复」"
              />
            </el-form-item>
            <el-form-item label="预计恢复时间">
              <el-date-picker
                v-model="form.expectedRestoreAt"
                type="datetime"
                format="YYYY-MM-DD HH:mm"
                value-format="YYYY-MM-DDTHH:mm"
                placeholder="可选，维护界面展示"
                style="width:100%"
              />
            </el-form-item>
          </template>
          <el-form-item label="后台管理页">
            <el-input v-model="form.adminPage" placeholder="如 /admin/courses，留空则不显示跳转" />
          </el-form-item>
        </el-form>
      </template>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveEdit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.maintain { padding: 4px 4px 40px; }
.head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 18px; }
.head h2 { margin: 0 0 6px; font-size: 20px; color: #0f172a; }
html.dark .head h2 { color: #e7e7ee; }
.sub { margin: 0; color: #94a3b8; font-size: 13px; max-width: 640px; line-height: 1.6; }
.group { margin-bottom: 22px; }
.group-title { font-size: 14px; font-weight: 600; color: #4f46e5; margin: 0 0 8px 4px; }
html.dark .group-title { color: #a5b4fc; }
.fname { font-weight: 600; color: #0f172a; }
html.dark .fname { color: #e7e7ee; }
.fkey { display: block; font-size: 11px; color: #94a3b8; margin-top: 2px; }
.desc { color: #475569; font-size: 13px; line-height: 1.5; }
html.dark .desc { color: #b7b8cd; }
.mmsg { display: flex; align-items: center; gap: 4px; color: #b45309; font-size: 12px; margin-top: 4px; }
html.dark .mmsg { color: #fbbf24; }
.mmsg.restore { color: #64748b; }
html.dark .mmsg.restore { color: #94a3b8; }
.admin-link { display: inline-flex; align-items: center; gap: 4px; color: #4f46e5; font-size: 12px; margin-top: 6px; cursor: pointer; }
.admin-link:hover { text-decoration: underline; }
html.dark .admin-link { color: #a5b4fc; }
.dlg-feature { margin-bottom: 14px; }

/* ===================== 响应式布局（追加在末尾） ===================== */
@media (max-width: 1024px) {
  .maintain { padding: 2px 0 32px; }
  .head { flex-wrap: wrap; gap: 8px 10px; margin-bottom: 14px; }
  .head h2 { font-size: 18px; }
  .sub { max-width: 100%; }
  .group { margin-bottom: 18px; }
}

@media (max-width: 640px) {
  .maintain { padding: 0 0 24px; }
  .head h2 { font-size: 17px; }
  .sub { font-size: 12.5px; line-height: 1.5; }
  .group-title { font-size: 13px; margin-left: 0; }
  .fname { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .desc { font-size: 12.5px; }
  .mmsg { font-size: 11.5px; }
  .group :deep(.el-table .cell) { padding-left: 6px; padding-right: 6px; }
  .group :deep(.el-table .cell > .el-button) { margin-left: 4px; }
}
</style>
