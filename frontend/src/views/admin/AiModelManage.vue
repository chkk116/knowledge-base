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

// 平台视觉：渐变徽章 + 缩写（白卡 + 彩色徽章，与数据看板同套视觉语言）
const PLATFORM_META = {
  OPENAI:    { mono: 'AI',  bg: 'linear-gradient(135deg, #10a37f, #0b7a5f)' },
  DASHSCOPE: { mono: '千',  bg: 'linear-gradient(135deg, #ff9f43, #ff6b35)' },
  DEEPSEEK:  { mono: 'DS',  bg: 'linear-gradient(135deg, #5b7cfa, #3b5bdb)' },
  ZHIPU:     { mono: 'GLM', bg: 'linear-gradient(135deg, #3859ff, #1e40ff)' },
  CUSTOM:    { mono: '⚙',   bg: 'linear-gradient(135deg, #94a3b8, #64748b)' },
}
const meta = (p) => PLATFORM_META[p] || PLATFORM_META.CUSTOM

// 顶部 KPI：配置总量 / 启用数 / 覆盖平台数
const kpis = computed(() => [
  { label: '已配置模型', value: models.value.length, unit: '个', icon: 'Cpu', color: '#6366f1' },
  { label: '启用中', value: models.value.filter(m => m.enabled).length, unit: '个', icon: 'Check', color: '#10b981' },
  { label: '覆盖平台', value: new Set(models.value.map(m => m.provider)).size, unit: '个', icon: 'Collection', color: '#0ea5e9' },
])

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
  <div class="ai-manage">
    <!-- 页头：标题 + 一句话说明 + 新增入口 -->
    <div class="page-head">
      <div class="ph-left">
        <div class="ph-icon"><el-icon><Cpu /></el-icon></div>
        <div>
          <div class="ph-title">AI 模型管理</div>
          <div class="ph-sub">统一接入各平台模型 · API Key 加密存储 · 仅系统管理员可见</div>
        </div>
      </div>
      <el-button type="primary" @click="openAdd">＋ 新增模型</el-button>
    </div>

    <!-- KPI 摘要（与数据看板同款卡片） -->
    <section class="kpi-row">
      <div v-for="k in kpis" :key="k.label" class="kpi-card" :style="{ '--k': k.color }">
        <div class="kpi-top">
          <div class="kpi-icon"><el-icon><component :is="k.icon" /></el-icon></div>
          <div class="kpi-meta">
            <div class="kpi-label">{{ k.label }}</div>
            <div class="kpi-value">{{ k.value }}<span class="unit">{{ k.unit }}</span></div>
          </div>
        </div>
      </div>
    </section>

    <!-- 模型卡片网格 -->
    <div v-loading="loading" class="cards">
      <div v-for="m in models" :key="m.id" class="m-card" :class="{ off: !m.enabled }">
        <div class="mc-top">
          <div class="mc-logo" :style="{ background: meta(m.provider).bg }">
            <span>{{ meta(m.provider).mono }}</span>
          </div>
          <div class="mc-name">
            <div class="n">
              {{ m.name }}
              <el-tag v-if="m.isDefault" size="small" type="warning" effect="dark">默认</el-tag>
            </div>
            <div class="p">{{ providerLabel(m.provider) }}</div>
          </div>
          <el-switch :model-value="m.enabled" @change="onToggle(m)" />
        </div>

        <div class="mc-rows">
          <div class="row">
            <span class="k">模型</span>
            <span class="v mono" :title="m.model">{{ m.model }}</span>
          </div>
          <div class="row">
            <span class="k">Key</span>
            <span v-if="m.hasKey" class="v mono">{{ m.keyMasked }}</span>
            <span v-else class="v none">未配置</span>
          </div>
          <div v-if="m.provider === 'CUSTOM'" class="row">
            <span class="k">地址</span>
            <span class="v mono ellip" :title="m.baseUrl">{{ m.baseUrl }}</span>
          </div>
        </div>

        <div class="mc-actions">
          <el-button size="small" @click="onTest(m)" :loading="testing === m.id">测试</el-button>
          <el-button size="small" @click="openEdit(m)">编辑</el-button>
          <el-button size="small" @click="onDefault(m)" :disabled="m.isDefault">设默认</el-button>
          <el-button size="small" type="danger" plain @click="onDelete(m)">删除</el-button>
        </div>
      </div>
    </div>

    <el-empty v-if="!loading && !models.length"
              description="还没有模型配置 —— 点右上角「新增模型」，填入平台 API Key 即可接入" />

    <!-- 测试连接结果 -->
    <div v-if="testResult" class="test-box">
      <el-alert :type="testResult.ok ? 'success' : 'error'" :closable="false" show-icon>
        <template #title>
          {{ testResult.ok ? '连接成功' : '连接失败' }}
          <template v-if="testResult.model"> · 模型 <b>{{ testResult.model }}</b></template>
          <template v-if="testResult.ok"> · 耗时 <b>{{ testResult.latencyMs }}ms</b></template>
        </template>
        <template v-if="testResult.reply">{{ testResult.reply }}</template>
        <template v-else>{{ testResult.error }}</template>
      </el-alert>
    </div>

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
/* ===== 页头 ===== */
.page-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 16px; }
.ph-left { display: flex; align-items: center; gap: 12px; min-width: 0; }
.ph-icon {
  width: 42px; height: 42px; border-radius: 12px; flex-shrink: 0;
  display: flex; align-items: center; justify-content: center;
  font-size: 21px; color: #6366f1; background: rgba(99, 102, 241, .10);
}
html.dark .ph-icon { background: rgba(129, 140, 248, .20); color: #a5b4fc; }
.ph-title { font-size: 18px; font-weight: 700; color: #0f172a; line-height: 1.3; }
html.dark .ph-title { color: #f1f5f9; }
.ph-sub { font-size: 12px; color: #94a3b8; margin-top: 2px; }

/* ===== KPI（对照数据看板 kpi-card） ===== */
.kpi-row { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 14px; margin-bottom: 16px; }
.kpi-card {
  position: relative; overflow: hidden;
  background: #fff; border: 1px solid #eef2f7; border-radius: 14px;
  padding: 14px 16px;
  box-shadow: 0 1px 3px rgba(16, 24, 40, .06);
  transition: box-shadow .22s, transform .22s;
}
.kpi-card::before {
  content: ''; position: absolute; inset: 0 0 auto 0; height: 3px;
  background: linear-gradient(90deg, var(--k), transparent);
}
.kpi-card:hover { transform: translateY(-3px); box-shadow: 0 10px 24px rgba(16, 24, 40, .10); }
html.dark .kpi-card { background: #17171f; border-color: #2a2a35; }
html.dark .kpi-card:hover { box-shadow: 0 10px 24px rgba(0, 0, 0, .5); }
.kpi-top { display: flex; align-items: center; gap: 12px; }
.kpi-icon {
  width: 38px; height: 38px; border-radius: 11px; flex-shrink: 0;
  display: flex; align-items: center; justify-content: center;
  font-size: 19px; color: var(--k); background: color-mix(in srgb, var(--k) 10%, transparent);
}
html.dark .kpi-icon { background: color-mix(in srgb, var(--k) 20%, transparent); }
.kpi-label { font-size: 12px; color: #94a3b8; white-space: nowrap; }
html.dark .kpi-label { color: #9aa0ad; }
.kpi-value { font-size: 25px; font-weight: 700; line-height: 1.15; color: #0f172a; font-variant-numeric: tabular-nums; letter-spacing: -.5px; }
html.dark .kpi-value { color: #f1f5f9; }
.kpi-value .unit { font-size: 13px; font-weight: 500; margin-left: 2px; color: #94a3b8; }

/* ===== 模型卡片网格 ===== */
.cards { display: grid; grid-template-columns: repeat(auto-fill, minmax(330px, 1fr)); gap: 14px; }
.m-card {
  background: #fff; border: 1px solid #eef2f7; border-radius: 14px;
  padding: 14px 16px;
  box-shadow: 0 1px 3px rgba(16, 24, 40, .06);
  transition: box-shadow .22s, transform .22s;
  display: flex; flex-direction: column; gap: 10px;
}
.m-card:hover { transform: translateY(-3px); box-shadow: 0 10px 24px rgba(16, 24, 40, .10); }
.m-card.off { opacity: .68; }
html.dark .m-card { background: #17171f; border-color: #2a2a35; }
html.dark .m-card:hover { box-shadow: 0 10px 24px rgba(0, 0, 0, .5); }

.mc-top { display: flex; align-items: center; gap: 12px; }
.mc-logo {
  width: 44px; height: 44px; border-radius: 12px; flex-shrink: 0;
  display: flex; align-items: center; justify-content: center;
  color: #fff; font-size: 15px; font-weight: 700; letter-spacing: .5px;
  box-shadow: 0 4px 10px rgba(16, 24, 40, .16);
}
.mc-name { flex: 1; min-width: 0; }
.mc-name .n { font-size: 15px; font-weight: 700; color: #0f172a; display: flex; align-items: center; gap: 6px; }
html.dark .mc-name .n { color: #f1f5f9; }
.mc-name .p { font-size: 12px; color: #94a3b8; margin-top: 1px; }

.mc-rows {
  background: #f8fafc; border: 1px solid #eef2f7; border-radius: 10px;
  padding: 8px 12px; display: flex; flex-direction: column; gap: 5px;
}
html.dark .mc-rows { background: #1b1b24; border-color: #2a2a35; }
.row { display: flex; align-items: baseline; gap: 10px; font-size: 12px; min-width: 0; }
.row .k { color: #94a3b8; flex-shrink: 0; width: 30px; }
.row .v { color: #334155; font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
html.dark .row .v { color: #d5d7e0; }
.row .v.none { color: #cbd5e1; font-weight: 400; }
.row .v.ellip { max-width: 100%; }

.mc-actions { display: flex; gap: 8px; margin-top: auto; }
.mc-actions .el-button { margin-left: 0; flex: 1; }

/* ===== 测试结果 / 弹窗提示 ===== */
.test-box { margin-top: 14px; }
.mono { font-family: ui-monospace, SFMono-Regular, Consolas, monospace; font-size: 12px; }
.hint { color: #909399; font-size: 12px; line-height: 1.6; }
.hint.inline { margin-left: 10px; }

/* 响应式（断点与全局一致：≤1024 平板 / ≤640 手机） */
@media (max-width: 1024px) {
  .cards { grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); }
  .test-box { margin-top: 10px; }
}

@media (max-width: 640px) {
  .page-head { flex-direction: column; align-items: stretch; }
  .page-head .el-button { width: 100%; }
  .kpi-row { grid-template-columns: repeat(auto-fit, minmax(140px, 1fr)); gap: 10px; }
  .cards { grid-template-columns: 1fr; }
  .hint { font-size: 11px; }
  .mono { font-size: 11px; }
}
</style>
