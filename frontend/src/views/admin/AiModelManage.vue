<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useAuthStore } from '../../store/auth'
import {
  adminListModels, adminModelPlatforms, adminSaveModel, adminDeleteModel,
  adminToggleModel, adminSetDefaultModel, adminTestModel
} from '../../api/ai'

const auth = useAuthStore()
// 双保险：路由守卫已按 adminOnly 拦截，这里再判一次，防直接改路由配置漏人
if (!auth.isAdmin) {
  ElMessage.error('仅系统管理员可访问该页面')
}

const models = ref([])
const platforms = ref([])
const loading = ref(false)
const platformMap = computed(() => {
  const m = {}
  platforms.value.forEach(p => { m[p.key] = p })
  return m
})
const providerLabel = (key) => (platformMap.value[key] ? platformMap.value[key].label : key)

// 当前选中平台：平台自带官方端点与可选模型版本，供表单联动使用
const currentPlatform = computed(() => platformMap.value[form.provider] || null)
// 只有「自定义」平台才让管理员手填地址，其余平台官方端点固定，避免往这里填错东西
const isCustomPlatform = computed(() => form.provider === 'CUSTOM')
const modelOptions = ref([])

const load = async () => {
  loading.value = true
  try {
    const [{ data: list }, { data: plats }] = await Promise.all([adminListModels(), adminModelPlatforms()])
    models.value = list || []
    platforms.value = plats || []
  } catch (e) {
    ElMessage.error('加载模型配置失败：' + (e.response ? e.response.status : ''))
  } finally {
    loading.value = false
  }
}

// ---------- 新增 / 编辑 ----------
const dialog = ref(false)
const saving = ref(false)
const form = reactive({
  id: null, provider: 'DEEPSEEK', name: '', baseUrl: '', model: '', apiKey: '',
  enabled: true, isDefault: false, keyMasked: ''
})

/**
 * 换平台 = 一次把「地址 / 版本 / 名称」都带好：
 * - 接口地址用该平台官方端点（自定义平台除外，交管理员填）
 * - 模型版本默认选推荐项，管理员可在下拉里换别的版本
 * - 显示名称没填过时，直接沿用模型名，省得再编一个
 */
const onProviderChange = () => {
  const p = currentPlatform.value
  if (!p) return
  modelOptions.value = Array.isArray(p.models) ? p.models : []
  form.baseUrl = p.baseUrl || ''
  const dm = p.defaultModel || ''
  form.model = dm
  if (!form.name.trim()) form.name = dm
}
const openAdd = () => {
  Object.assign(form, {
    id: null, provider: 'DEEPSEEK', name: '', baseUrl: '', model: '', apiKey: '',
    enabled: true, isDefault: false, keyMasked: ''
  })
  onProviderChange()
  dialog.value = true
}
const openEdit = (row) => {
  Object.assign(form, {
    id: row.id, provider: row.provider, name: row.name, baseUrl: row.baseUrl,
    model: row.model, enabled: row.enabled, isDefault: row.isDefault,
    // 回显脱敏值：后端据此判断「Key 没改」，不会把原 Key 覆盖掉
    apiKey: row.keyMasked || '', keyMasked: row.keyMasked || ''
  })
  // 编辑时平台不可改，模型下拉仍要按当前平台渲染
  const p = currentPlatform.value
  modelOptions.value = p && Array.isArray(p.models) ? p.models : []
  dialog.value = true
}

const submit = async () => {
  // 显示名称留空 = 用模型名（换平台时已自动带好，这里是兜底）
  if (!form.name.trim()) form.name = form.model
  if (!form.name.trim()) return ElMessage.warning('请填写模型显示名称')
  if (!form.baseUrl.trim()) return ElMessage.warning('请填写接口地址（自定义平台）')
  if (!form.model.trim()) return ElMessage.warning('请填写模型名称')
  // 新增时必须给 Key；编辑时留空（含 **** 的脱敏回显）表示沿用原值
  if (!form.id && !form.apiKey.trim()) return ElMessage.warning('请填写该平台的 API Key')
  saving.value = true
  try {
    await adminSaveModel({ ...form, apiKey: (form.apiKey || '').trim() })
    ElMessage.success('已保存')
    dialog.value = false
    await load()
  } catch (e) {
    ElMessage.error('保存失败')
  } finally {
    saving.value = false
  }
}

// ---------- 行操作 ----------
const onToggle = async (row) => {
  try {
    await adminToggleModel(row.id, !row.enabled)
    row.enabled = !row.enabled
    ElMessage.success(row.enabled ? '已启用' : '已停用')
  } catch { ElMessage.error('操作失败') }
}

const onDefault = async (row) => {
  try {
    await adminSetDefaultModel(row.id)
    models.value.forEach(m => { m.isDefault = m.id === row.id })
    ElMessage.success('已设为默认模型')
  } catch { ElMessage.error('操作失败') }
}

const onDelete = async (row) => {
  try {
    await ElMessageBox.confirm(`确定删除「${row.name}」吗？`, '删除确认', { type: 'warning' })
  } catch { return }
  try {
    await adminDeleteModel(row.id)
    ElMessage.success('已删除')
    await load()
  } catch { ElMessage.error('删除失败') }
}

// ---------- 测试连接 ----------
const testing = ref(null)
const testResult = ref(null)
const onTest = async (row) => {
  testing.value = row.id
  testResult.value = null
  try {
    const { data } = await adminTestModel(row.id)
    testResult.value = data
    ElMessage[data.ok ? 'success' : 'warning'](data.ok
      ? `连接正常（${data.model || row.model}，${data.latencyMs}ms）`
      : (data.error || '连接失败'))
  } catch {
    testResult.value = { ok: false, error: '请求失败，请检查后端与网络' }
    ElMessage.error('测试请求失败')
  } finally {
    testing.value = null
  }
}

onMounted(load)
</script>

<template>
  <div>
    <!-- 顶部说明：把「Key 怎么存、谁能改」讲清楚，避免管理员误填明文风险 -->
    <el-alert type="info" :closable="false" show-icon style="margin-bottom: 12px;">
      <template #title>
        所有第三方平台均走 OpenAI 兼容协议（<b>POST {baseUrl}/chat/completions</b>），填入 API Key 即可直接调用。
        Key 以 AES-GCM 加密后落库，列表只显示 <b>****</b> 脱敏形式；该页面<b>仅系统管理员</b>可访问。
      </template>
    </el-alert>

    <el-card shadow="never">
      <template #header>
        <div class="head">
          <span>AI 模型配置</span>
          <el-button type="primary" size="small" @click="openAdd">+ 新增模型</el-button>
        </div>
      </template>

      <el-table v-loading="loading" :data="models" border stripe>
        <el-table-column label="平台" width="160">
          <template #default="{ row }">
            <el-tag size="small" effect="plain">{{ providerLabel(row.provider) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="name" label="显示名称" min-width="140" />
        <el-table-column prop="model" label="模型名" min-width="180" />
        <el-table-column label="API Key" min-width="150">
          <template #default="{ row }">
            <span v-if="row.hasKey" class="mono">{{ row.keyMasked }}</span>
            <span v-else class="muted">未配置</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.enabled ? 'success' : 'info'" effect="dark">
              {{ row.enabled ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="默认" width="90" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.isDefault" size="small" type="warning" effect="dark">默认</el-tag>
            <span v-else class="muted">-</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" min-width="300" fixed="right">
          <template #default="{ row }">
            <el-button size="small" @click="openEdit(row)">编辑</el-button>
            <el-button size="small" @click="onTest(row)" :loading="testing === row.id">测试</el-button>
            <el-button size="small" @click="onDefault(row)" :disabled="row.isDefault">设默认</el-button>
            <el-button size="small" type="danger" plain @click="onDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div v-if="testResult" class="test-box">
        <el-alert :type="testResult.ok ? 'success' : 'error'" :closable="false" show-icon>
          <template #title>
            {{ testResult.ok ? '连接成功' : '连接失败' }}
            <template v-if="testResult.ok">
              · 模型 <b>{{ testResult.model }}</b>
              · 耗时 <b>{{ testResult.latencyMs }}ms</b>
            </template>
          </template>
          <template v-if="testResult.reply">{{ testResult.reply }}</template>
          <template v-else>{{ testResult.error }}</template>
        </el-alert>
      </div>
    </el-card>

    <!-- 编辑弹窗：改 Key 只需重填一栏，其余字段不动即可 -->
    <el-dialog v-model="dialog" :title="form.id ? '编辑模型' : '新增模型'" width="560px">
      <el-form label-width="96px">
        <el-form-item label="平台">
          <el-select v-model="form.provider" style="width: 100%" @change="onProviderChange" :disabled="!!form.id">
            <el-option v-for="p in platforms" :key="p.key" :label="p.label" :value="p.key" />
          </el-select>
          <div class="hint">换平台会自动带出官方地址、推荐模型版本与显示名称。</div>
        </el-form-item>
        <el-form-item label="显示名称">
          <el-input v-model="form.name" placeholder="留空则自动用模型名" maxlength="40" />
        </el-form-item>
        <el-form-item label="接口地址">
          <el-input v-model="form.baseUrl" :disabled="!isCustomPlatform"
                    placeholder="https://api.deepseek.com/v1" />
          <div class="hint">
            官方端点由平台固定，<b>这里填地址而不是 API Key</b>；请求实际打
            <span class="mono">{{ form.baseUrl.replace(/\/$/, '') }}/chat/completions</span>。
            要改用自建网关/代理，把平台切到「自定义」后可自己填写。
          </div>
        </el-form-item>
        <el-form-item label="模型版本">
          <el-select v-model="form.model" filterable allow-create default-first-option
                     style="width: 100%" placeholder="选择该平台的模型版本，或直接输入">
            <el-option v-for="m in modelOptions" :key="m.id" :label="m.label" :value="m.id" />
          </el-select>
          <div class="hint">
            <template v-if="modelOptions.length">
              已内置 {{ modelOptions.length }} 个可选版本；选中的
              <span class="mono">{{ form.model || '（空）' }}</span> 就是实际调用的模型名。
            </template>
            <template v-else>自定义平台请直接输入模型名，如 gpt-4o-mini。</template>
          </div>
        </el-form-item>
        <el-form-item label="API Key">
          <el-input v-model="form.apiKey" type="password" show-password
                    :placeholder="form.keyMasked ? '留空或不改即保留原值（' + form.keyMasked + '）' : '填写该平台申请到的 API Key'" />
          <div class="hint">存库时自动加密；详情页只回显脱敏值。</div>
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" />
          <span class="hint inline">停用后用户端不再出现该模型</span>
        </el-form-item>
        <el-form-item label="设为默认">
          <el-switch v-model="form.isDefault" />
          <span class="hint inline">用户提问未指定模型时使用它</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-weight: 600;
}
.mono { font-family: ui-monospace, SFMono-Regular, Consolas, monospace; font-size: 12px; }
.muted { color: #909399; }
.hint { color: #909399; font-size: 12px; line-height: 1.6; }
.hint.inline { margin-left: 10px; }
.test-box { margin-top: 14px; }

/* 响应式（断点与全局一致：≤1024 平板 / ≤640 手机） */
@media (max-width: 1024px) {
  .test-box { margin-top: 10px; }
}

@media (max-width: 640px) {
  .hint { font-size: 11px; }
  .mono { font-size: 11px; }
}
</style>
