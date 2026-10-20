<script setup>
import { ref, reactive, computed, onMounted, onUnmounted, nextTick, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../../store/auth'
import {
  adminListModels, adminModelPlatforms, adminSaveModel, adminDeleteModel,
  adminToggleModel, adminSetDefaultModel, adminTestModel, adminUsage
} from '../../api/ai'
import { loadEcharts, chartTxt, chartTip } from '../../utils/echartsLoader'
import api from '../../api/request'
import ProviderLogo from '../../components/ProviderLogo.vue'

// ---- 消息设置：高消耗发送前确认（存 SysSetting 的 ai_cost_guard，跨设备生效）----
const guard = reactive({ enabled: true, imageThreshold: 2, textThreshold: 8000 })
const guardSaving = ref(false)
async function loadGuard() {
  try {
    const { data } = await api.get('/admin/settings/ai_cost_guard')
    const raw = data && data.value
    if (raw) {
      const v = JSON.parse(raw)
      if (typeof v.enabled === 'boolean') guard.enabled = v.enabled
      if (Number(v.imageThreshold) > 0) guard.imageThreshold = Number(v.imageThreshold)
      if (Number(v.textThreshold) > 0) guard.textThreshold = Number(v.textThreshold)
    }
  } catch { /* 没配过就用默认值 */ }
}
async function saveGuard() {
  guardSaving.value = true
  try {
    await api.put('/admin/settings/ai_cost_guard', { value: JSON.stringify({ ...guard }) })
    ElMessage.success('已保存')
  } catch {
    ElMessage.error('保存失败')
  } finally {
    guardSaving.value = false
  }
}

const router = useRouter()
const auth = useAuthStore()
// 双保险：路由守卫已按 AI_MANAGE 权限码拦截，这里再判一次，防直接改路由配置漏人。
// 注意：必须同时放行细粒度权限码 AI_MANAGE（2026-10-06 起该页可从角色管理授权），
// 只判 isAdmin 会把「有 AI_MANAGE 的演示号/子管理员」也踢走（曾经的真凶）。
if (!auth.isAdmin && !auth.hasPerm('AI_MANAGE')) {
  ElMessage.error('需要「AI 模型管理」权限')
  router.replace('/')
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

// 视图：market=供应商视图（看市场 / 谁没配）· list=配置列表（运营：测试/默认/启停/用量）
const viewMode = ref('market')
const search = ref('')

// 各供应商能力标签（本系统当前用到的是「对话」与「视觉/OCR」）。
// 与后端 PLATFORMS 的内置模型列表对应：含多模态版本的标「视觉」。
const CAPS = {
  OPENAI: ['对话', '视觉'],
  DASHSCOPE: ['对话'],
  DEEPSEEK: ['对话', '视觉'],
  ZHIPU: ['对话', '视觉'],
  OPENCODE: ['对话'],
  MIMO: ['对话', '视觉'],
  CUSTOM: ['对话'],
}
const capsOf = (key) => CAPS[key] || ['对话']

const providerConfigured = (key) => models.value.some(m => m.provider === key)
const providerConfigs = (key) => models.value.filter(m => m.provider === key)
const providerHost = (p) => {
  if (p.key === 'CUSTOM') return '任意 OpenAI 兼容端点'
  try { return new URL(p.baseUrl).host } catch { return p.baseUrl }
}
// 卡片标题：自定义平台后端 label 很长（带括号说明），卡片上只显示「自定义」，完整说明放副标题
const providerName = (p) => (p.key === 'CUSTOM' ? '自定义' : p.label)

// 顶部 KPI：配置总量 / 启用数 / 覆盖平台数
const kpis = computed(() => [
  { label: '已配置模型', value: models.value.length, unit: '个', icon: 'Cpu', color: '#6366f1' },
  { label: '启用中', value: models.value.filter(m => m.enabled).length, unit: '个', icon: 'Check', color: '#10b981' },
  { label: '覆盖平台', value: new Set(models.value.map(m => m.provider)).size, unit: '个', icon: 'Collection', color: '#0ea5e9' },
])

const q = computed(() => search.value.trim().toLowerCase())
const filteredProviders = computed(() => {
  if (!q.value) return platforms.value
  return platforms.value.filter(p =>
    p.label.toLowerCase().includes(q.value) ||
    (p.models || []).some(m => `${m.id} ${m.label}`.toLowerCase().includes(q.value)))
})
const filteredModels = computed(() => {
  if (!q.value) return models.value
  return models.value.filter(m =>
    `${m.name} ${m.model} ${providerLabel(m.provider)}`.toLowerCase().includes(q.value))
})

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

// 切换「供应商视图 / 配置列表」时重新拉一次，保证两个视图看到的是同一份最新数据
watch(viewMode, () => { load() })

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

/**
 * 换「模型版本」时让显示名称跟着变。
 * 判定口径：名称还是「跟着模型走」的状态（为空，或正好等于上一个模型名）就同步成新模型名；
 * 管理员手填过的自定义名称不动，避免被覆盖。
 */
watch(() => form.model, (val, old) => {
  if (!val) return
  const n = form.name.trim()
  if (!n || n === (old || '')) form.name = val
})
/** 从指定平台开「新增」（供应商卡片的「密钥设置」走这里） */
const openAddFor = (key) => {
  Object.assign(form, {
    id: null, provider: key || 'DEEPSEEK', name: '', baseUrl: '', model: '', apiKey: '',
    enabled: true, isDefault: false, keyMasked: ''
  })
  onProviderChange()
  dialog.value = true
}
const openAdd = () => openAddFor('DEEPSEEK')
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
    const { data: res } = await adminSaveModel({ ...form, apiKey: (form.apiKey || '').trim() })
    ElMessage.success('已保存')
    dialog.value = false
    // 乐观更新：先把这条写进本地列表，供应商卡片的「已配置」立刻跟着变，
    // 不用等第二次拉取（曾经出现「配置列表已经有了、供应商视图还是未配置」）。
    const row = {
      id: res?.id ?? form.id,
      provider: String(form.provider || '').toUpperCase(),
      name: form.name, baseUrl: form.baseUrl, model: form.model,
      enabled: !!form.enabled, isDefault: !!form.isDefault,
      hasKey: true, keyMasked: form.keyMasked || '',
    }
    const idx = models.value.findIndex(m => m.id === row.id)
    if (idx >= 0) models.value.splice(idx, 1, { ...models.value[idx], ...row })
    else models.value.push(row)
    await load()
  } catch (e) {
    ElMessage.error('保存失败')
  } finally {
    saving.value = false
  }
}

// ---------- 模型详情（供应商卡片的「模型详情」） ----------
const detailOpen = ref(false)
const detailProvider = ref(null)
const openDetail = (p) => { detailProvider.value = p; detailOpen.value = true }
const configFromDetail = () => {
  const key = detailProvider.value?.key
  detailOpen.value = false
  openAddFor(key)
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

// ---------- 用量统计（对标平台官方控制台的「用量信息」页） ----------
const usageDays = ref(30)
const usageModelId = ref(null)
const usage = ref(null)
const usageLoading = ref(false)
const usageChartEl = ref(null)
let usageChart = null
let echartsLib = null
let themeObserver = null

const usageKpis = computed(() => {
  const s = usage.value?.summary || {}
  const okRate = s.requests ? Math.round((s.success / s.requests) * 100) : null
  return [
    { label: 'API 请求次数', value: s.requests ?? 0, sub: `成功 ${s.success ?? 0} · 失败 ${s.failed ?? 0}` },
    { label: 'Tokens', value: s.tokens ?? 0, sub: `输入 ${s.promptTokens ?? 0} · 输出 ${s.completionTokens ?? 0}` },
    { label: '成功率', value: okRate == null ? '—' : okRate + '%', sub: `平均耗时 ${(s.avgLatencyMs ?? 0)}ms` },
  ]
})

const loadUsage = async () => {
  usageLoading.value = true
  try {
    const { data } = await adminUsage({
      days: usageDays.value,
      modelId: usageModelId.value || undefined,
    })
    usage.value = data
    await nextTick()
    renderUsageChart()
  } catch (e) {
    ElMessage.error('加载用量统计失败：' + (e.response?.status || '网络异常'))
  } finally {
    usageLoading.value = false
  }
}

async function renderUsageChart() {
  if (!usage.value?.daily?.length) return
  if (!echartsLib) {
    try { echartsLib = await loadEcharts() } catch { return } // 本地 vendor 缺失时静默降级为纯数字
  }
  if (!usageChartEl.value) return
  if (!usageChart) usageChart = echartsLib.init(usageChartEl.value)
  const dark = document.documentElement.classList.contains('dark')
  const d = usage.value.daily
  usageChart.setOption({
    tooltip: { trigger: 'axis', ...chartTip(dark) },
    legend: { top: 0, textStyle: chartTxt(dark) },
    grid: { left: 48, right: 48, top: 32, bottom: 26 },
    xAxis: { type: 'category', data: d.map(e => e.date.slice(5)), axisLabel: chartTxt(dark) },
    yAxis: [
      { type: 'value', name: 'Tokens', axisLabel: chartTxt(dark), splitLine: { lineStyle: { color: dark ? '#2a2a35' : '#eef2f7' } } },
      { type: 'value', name: '次数', axisLabel: chartTxt(dark), splitLine: { show: false } },
    ],
    series: [
      { name: 'Tokens', type: 'bar', data: d.map(e => e.tokens), barMaxWidth: 26,
        itemStyle: { color: '#6366f1', borderRadius: [4, 4, 0, 0] } },
      { name: '请求次数', type: 'line', yAxisIndex: 1, data: d.map(e => e.requests),
        itemStyle: { color: '#10b981' }, smooth: true, symbolSize: 6 },
    ],
  })
}

const onThemeChange = () => renderUsageChart()
const onResize = () => usageChart && usageChart.resize()

onMounted(() => {
  load()
  loadUsage()
  loadGuard()
  // 主题切换时图表跟随重绘；窗口缩放时自适应
  themeObserver = new MutationObserver(onThemeChange)
  themeObserver.observe(document.documentElement, { attributes: true, attributeFilter: ['class'] })
  window.addEventListener('resize', onResize)
})
onUnmounted(() => {
  if (themeObserver) themeObserver.disconnect()
  window.removeEventListener('resize', onResize)
  if (usageChart) { usageChart.dispose(); usageChart = null }
})
</script>

<template>
  <div class="ai-manage">
    <!-- 页头：标题 + 视图切换 + 新增入口 -->
    <div class="page-head">
      <div class="ph-left">
        <div class="ph-icon"><el-icon><Cpu /></el-icon></div>
        <div>
          <div class="ph-title">AI 模型管理</div>
          <div class="ph-sub">统一接入各平台模型 · API Key 加密存储 · 仅系统管理员可见</div>
        </div>
      </div>
      <div class="ph-right">
        <el-radio-group v-model="viewMode">
          <el-radio-button value="market">供应商视图</el-radio-button>
          <el-radio-button value="list">配置列表</el-radio-button>
        </el-radio-group>
        <el-button type="primary" @click="openAdd">＋ 新增模型</el-button>
      </div>
    </div>

    <!-- 搜索 -->
    <div class="filter-row">
      <el-input v-model="search" clearable placeholder="搜索供应商 / 模型名称" class="fr-input">
        <template #prefix><el-icon><Search /></el-icon></template>
      </el-input>
      <span class="fr-count">
        {{ viewMode === 'market'
          ? `共 ${filteredProviders.length} 个供应商 · 已配置 ${new Set(models.map(m => m.provider)).size} 个`
          : `共 ${filteredModels.length} 个配置` }}
      </span>
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

    <!-- ===== 供应商视图：列出全部平台，谁已配置一眼可见 ===== -->
    <div v-if="viewMode === 'market'" v-loading="loading" class="p-cards">
      <div v-for="p in filteredProviders" :key="p.key" class="p-card" :class="{ unset: !providerConfigured(p.key) }">
        <div class="pc-top">
          <div class="pc-logo"><ProviderLogo :provider="p.key" :size="26" /></div>
          <div class="pc-name">
            <div class="n">{{ providerName(p) }}</div>
            <div class="h">{{ providerHost(p) }}</div>
          </div>
          <span class="pc-badge" :class="providerConfigured(p.key) ? 'on' : 'off'">
            {{ providerConfigured(p.key) ? '已配置' : '未配置' }}
          </span>
        </div>

        <div class="pc-caps">
          <span v-for="c in capsOf(p.key)" :key="c" class="cap">{{ c }}</span>
        </div>

        <div class="pc-meta">
          <template v-if="providerConfigured(p.key)">
            已接入 <b>{{ providerConfigs(p.key).length }}</b> 个配置
            <div class="mono ellip" :title="providerConfigs(p.key).map(m => m.model).join(' · ')">
              {{ providerConfigs(p.key).map(m => m.model).join(' · ') }}
            </div>
          </template>
          <template v-else>
            推荐 <span class="mono">{{ p.defaultModel || '自行填写' }}</span>
            <template v-if="(p.models || []).length"> · 内置 {{ p.models.length }} 个可选版本</template>
          </template>
        </div>

        <div class="pc-actions">
          <el-button size="small" type="primary" plain @click="openAddFor(p.key)">密钥设置</el-button>
          <el-button size="small" @click="openDetail(p)">模型详情</el-button>
        </div>
      </div>
    </div>

    <!-- ===== 配置列表：运营视角（测试 / 默认 / 启停 / 删除） ===== -->
    <div v-else v-loading="loading" class="cards">
      <div v-for="m in filteredModels" :key="m.id" class="m-card" :class="{ off: !m.enabled }">
        <div class="mc-top">
          <div class="mc-logo"><ProviderLogo :provider="m.provider" :size="24" /></div>
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

    <el-empty v-if="!loading && ((viewMode === 'market' && !filteredProviders.length) || (viewMode === 'list' && !filteredModels.length))"
              :description="search ? '没有匹配的供应商 / 模型' : '还没有模型配置 —— 点右上角「新增模型」，填入平台 API Key 即可接入'" />

    <!-- 用量统计（对标 DeepSeek 官方控制台「用量信息」页） -->
    <section class="usage-sec">
      <div class="u-head">
        <div>
          <div class="u-title">用量统计</div>
          <div class="u-sub">调用流水实时聚合 · 含对话与测试连接，失败请求也会计入</div>
        </div>
        <div class="u-ctrl">
          <el-select v-model="usageDays" style="width: 110px" @change="loadUsage">
            <el-option :value="1" label="今天" />
            <el-option :value="7" label="近 7 天" />
            <el-option :value="30" label="近 30 天" />
            <el-option :value="90" label="近 90 天" />
          </el-select>
          <el-select v-model="usageModelId" style="width: 180px" placeholder="全部模型" clearable @change="loadUsage">
            <el-option v-for="m in models" :key="m.id" :label="m.name" :value="m.id" />
          </el-select>
          <el-button :loading="usageLoading" @click="loadUsage">刷新</el-button>
        </div>
      </div>

      <div class="u-kpis">
        <div v-for="k in usageKpis" :key="k.label" class="u-kpi">
          <div class="uk-label">{{ k.label }}</div>
          <div class="uk-value">{{ k.value }}</div>
          <div class="uk-sub">{{ k.sub }}</div>
        </div>
      </div>

      <div class="u-chart-wrap">
        <div class="u-chart-title">每日 Tokens 与请求次数</div>
        <div v-if="usage && usage.summary.requests === 0" class="u-empty">
          统计范围内还没有调用记录 —— 去「AI 问答」问一句，或点上方「测试」产生一条流水
        </div>
        <div v-show="usage && usage.summary.requests > 0" ref="usageChartEl" class="u-chart"></div>
      </div>

      <el-table v-if="usage && usage.models.length" v-stack-table :data="usage.models" border stripe size="small"
                style="margin-top: 12px;">
        <el-table-column label="模型" min-width="200">
          <template #default="{ row }">
            <span style="font-weight: 600;">{{ row.modelName }}</span>
            <span class="mono muted" style="margin-left: 8px;">{{ row.model }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="requests" label="请求次数" width="100" align="center" />
        <el-table-column prop="success" label="成功" width="80" align="center" />
        <el-table-column prop="failed" label="失败" width="80" align="center">
          <template #default="{ row }">
            <span :style="row.failed > 0 ? 'color:#ef4444;font-weight:600;' : ''">{{ row.failed }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="tokens" label="Tokens" width="110" align="center" />
        <el-table-column label="平均耗时" width="100" align="center">
          <template #default="{ row }">{{ row.avgLatencyMs }}ms</template>
        </el-table-column>
      </el-table>
    </section>

    <!-- 消息设置：高消耗发送前确认（AI 页读取，跨设备生效） -->
    <section class="guard-sec">
      <h3 class="sec-title">消息设置</h3>
      <div class="guard-card">
        <div class="guard-row">
          <div class="guard-info">
            <div class="guard-name">高消耗发送前确认</div>
            <div class="guard-desc">
              AI 页发送多张图片或超长文本前先弹窗确认，避免一次误发就烧掉大量额度
              （图片约 850 tokens/张，文本约 1.5 tokens/字）
            </div>
          </div>
          <el-switch v-model="guard.enabled" :loading="guardSaving" @change="saveGuard" />
        </div>
        <div v-if="guard.enabled" class="guard-thresh">
          <label class="guard-field">
            <span>图片 ≥</span>
            <el-input-number v-model="guard.imageThreshold" :min="1" :max="10" size="small" @change="saveGuard" />
            <span>张时确认</span>
          </label>
          <label class="guard-field">
            <span>文本 ≥</span>
            <el-input-number v-model="guard.textThreshold" :min="1000" :max="100000" :step="1000" size="small" @change="saveGuard" />
            <span>字时确认</span>
          </label>
        </div>
      </div>
    </section>

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

    <!-- 模型详情抽屉：列出该平台内置的可用版本（数据来自 /platforms，无需后端改动） -->
    <el-drawer v-model="detailOpen" :title="(detailProvider?.label || '') + ' · 可用模型'" size="440px">
      <div v-if="detailProvider" class="dwrap">
        <div class="drow"><span class="dk">接口地址</span><span class="dv mono">{{ detailProvider.baseUrl }}</span></div>
        <div v-if="detailProvider.doc && detailProvider.doc.startsWith('http')" class="drow">
          <span class="dk">获取 Key</span>
          <a class="dv dlink" :href="detailProvider.doc" target="_blank" rel="noopener">打开平台密钥页</a>
        </div>
        <div v-else-if="detailProvider.doc" class="drow">
          <span class="dk">说明</span><span class="dv">{{ detailProvider.doc }}</span>
        </div>

        <div class="dtitle">可用模型版本（{{ (detailProvider.models || []).length }}）</div>
        <el-table v-if="(detailProvider.models || []).length" :data="detailProvider.models" size="small" border>
          <el-table-column prop="label" label="模型" />
          <el-table-column prop="id" label="模型 ID" width="150" />
        </el-table>
        <div v-else class="dempty">该平台需自行填写模型名（如 gpt-4o-mini）。</div>

        <el-button type="primary" style="margin-top: 16px; width: 100%;" @click="configFromDetail">
          去配置该平台
        </el-button>
      </div>
    </el-drawer>

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
.page-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 14px; }
.ph-left { display: flex; align-items: center; gap: 12px; min-width: 0; }
.ph-right { display: flex; align-items: center; gap: 10px; flex-shrink: 0; }
.ph-icon {
  width: 42px; height: 42px; border-radius: 12px; flex-shrink: 0;
  display: flex; align-items: center; justify-content: center;
  font-size: 21px; color: #6366f1; background: rgba(99, 102, 241, .10);
}
html.dark .ph-icon { background: rgba(129, 140, 248, .20); color: #a5b4fc; }
.ph-title { font-size: 18px; font-weight: 700; color: #0f172a; line-height: 1.3; }
html.dark .ph-title { color: #f1f5f9; }
.ph-sub { font-size: 12px; color: #94a3b8; margin-top: 2px; }

/* ===== 搜索行 ===== */
.filter-row { display: flex; align-items: center; gap: 12px; margin-bottom: 14px; flex-wrap: wrap; }
.fr-input { max-width: 300px; }
.fr-count { font-size: 12px; color: #94a3b8; }
html.dark .fr-count { color: #9aa0ad; }

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

/* ===== 供应商视图 ===== */
.p-cards { display: grid; grid-template-columns: repeat(auto-fill, minmax(300px, 1fr)); gap: 14px; }
.p-card {
  background: #fff; border: 1px solid #eef2f7; border-radius: 14px;
  padding: 14px 16px;
  box-shadow: 0 1px 3px rgba(16, 24, 40, .06);
  transition: box-shadow .22s, transform .22s;
  display: flex; flex-direction: column; gap: 10px;
}
.p-card:hover { transform: translateY(-3px); box-shadow: 0 10px 24px rgba(16, 24, 40, .10); }
.p-card.unset { background: #fcfcfd; }
html.dark .p-card { background: #17171f; border-color: #2a2a35; }
html.dark .p-card.unset { background: #14141b; }
html.dark .p-card:hover { box-shadow: 0 10px 24px rgba(0, 0, 0, .5); }

.pc-top { display: flex; align-items: center; gap: 12px; }
.pc-logo {
  width: 46px; height: 46px; border-radius: 12px; flex-shrink: 0;
  display: flex; align-items: center; justify-content: center;
  background: #f5f7fa; border: 1px solid #eef2f7;
}
/* 深色主题下 logo chip 保持浅底：品牌色（如 OpenAI 近黑 #0f172a）是为浅底设计的，
   放深底会看不见；浅底 chip 两种主题下都能正确显色 */
html.dark .pc-logo { background: #e8ecf2; border-color: #cfd6e0; }
.pc-name { flex: 1; min-width: 0; }
.pc-name .n { font-size: 15px; font-weight: 700; color: #0f172a; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
html.dark .pc-name .n { color: #f1f5f9; }
.pc-name .h { font-size: 12px; color: #94a3b8; margin-top: 1px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.pc-badge {
  font-size: 12px; padding: 2px 9px; border-radius: 999px; flex-shrink: 0; font-weight: 600;
  background: #ecfdf5; color: #059669;
}
.pc-badge.off { background: #f1f5f9; color: #94a3b8; font-weight: 400; }
html.dark .pc-badge { background: rgba(16, 185, 129, .16); color: #34d399; }
html.dark .pc-badge.off { background: #26262f; color: #8b8f9a; }

.pc-caps { display: flex; gap: 6px; flex-wrap: wrap; }
.pc-caps .cap {
  font-size: 11px; padding: 1px 8px; border-radius: 6px; font-weight: 600;
  background: rgba(99, 102, 241, .10); color: #6366f1;
}
html.dark .pc-caps .cap { background: rgba(129, 140, 248, .18); color: #a5b4fc; }
.p-card.unset .pc-caps .cap { background: #f1f5f9; color: #94a3b8; font-weight: 400; }
html.dark .p-card.unset .pc-caps .cap { background: #26262f; color: #8b8f9a; }

.pc-meta { font-size: 12px; color: #64748b; line-height: 1.6; }
html.dark .pc-meta { color: #9aa0ad; }
.pc-meta b { color: #0f172a; }
html.dark .pc-meta b { color: #e7e7ee; }
.pc-meta .ellip { max-width: 100%; }
.pc-actions { display: flex; gap: 8px; margin-top: auto; }
.pc-actions .el-button { margin-left: 0; flex: 1; }

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
  background: #f5f7fa; border: 1px solid #eef2f7;
}
html.dark .mc-logo { background: #e8ecf2; border-color: #cfd6e0; }
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
.muted { color: #94a3b8; }
.hint { color: #909399; font-size: 12px; line-height: 1.6; }
.hint.inline { margin-left: 10px; }

/* ===== 模型详情抽屉 ===== */
.dwrap { display: flex; flex-direction: column; gap: 8px; }
.drow { display: flex; gap: 10px; font-size: 12px; }
.dk { color: #94a3b8; flex-shrink: 0; width: 64px; }
.dv { color: #334155; word-break: break-all; }
html.dark .dv { color: #d5d7e0; }
.dlink { color: #6366f1; text-decoration: none; }
.dtitle { font-size: 13px; font-weight: 600; color: #334155; margin: 10px 0 2px; }
html.dark .dtitle { color: #d5d7e0; }
.dempty { color: #98a2b3; font-size: 13px; padding: 24px 0; text-align: center; }

/* ===== 消息设置（高消耗确认） ===== */
.guard-sec { margin-top: 16px; }
.guard-card {
  margin-top: 10px; background: #fff; border: 1px solid #eef2f7;
  border-radius: 14px; padding: 14px 16px 16px;
  box-shadow: 0 1px 3px rgba(16, 24, 40, .06);
}
html.dark .guard-card { background: #17171f; border-color: #2a2a35; }
.guard-row { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.guard-name { font-size: 14px; font-weight: 600; color: #1e293b; }
html.dark .guard-name { color: #e7e7ee; }
.guard-desc { margin-top: 4px; font-size: 12.5px; line-height: 1.6; color: #94a3b8; max-width: 640px; }
.guard-thresh { display: flex; gap: 24px; flex-wrap: wrap; margin-top: 14px; padding-top: 14px; border-top: 1px dashed #eef2f7; }
html.dark .guard-thresh { border-color: #2a2a35; }
.guard-field { display: inline-flex; align-items: center; gap: 8px; font-size: 13px; color: #64748b; }
html.dark .guard-field { color: #9aa3b2; }

/* ===== 用量统计 ===== */
.usage-sec {
  margin-top: 16px;
  background: #fff; border: 1px solid #eef2f7; border-radius: 14px;
  padding: 14px 16px 16px;
  box-shadow: 0 1px 3px rgba(16, 24, 40, .06);
}
html.dark .usage-sec { background: #17171f; border-color: #2a2a35; }
.u-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; margin-bottom: 12px; }
.u-title { font-size: 15px; font-weight: 700; color: #1e293b; }
html.dark .u-title { color: #e7e7ee; }
.u-sub { font-size: 12px; color: #94a3b8; margin-top: 2px; }
.u-ctrl { display: flex; gap: 8px; flex-wrap: wrap; }

.u-kpis { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 12px; }
.u-kpi {
  background: #f8fafc; border: 1px solid #eef2f7; border-radius: 12px;
  padding: 12px 14px;
}
html.dark .u-kpi { background: #1b1b24; border-color: #2a2a35; }
.uk-label { font-size: 12px; color: #94a3b8; }
html.dark .uk-label { color: #9aa0ad; }
.uk-value { font-size: 24px; font-weight: 700; color: #0f172a; font-variant-numeric: tabular-nums; line-height: 1.3; }
html.dark .uk-value { color: #f1f5f9; }
.uk-sub { font-size: 12px; color: #98a2b3; }

.u-chart-wrap {
  margin-top: 12px;
  background: #f8fafc; border: 1px solid #eef2f7; border-radius: 12px;
  padding: 12px 14px;
}
html.dark .u-chart-wrap { background: #1b1b24; border-color: #2a2a35; }
.u-chart-title { font-size: 13px; font-weight: 600; color: #334155; margin-bottom: 6px; }
html.dark .u-chart-title { color: #d5d7e0; }
.u-chart { width: 100%; height: 260px; }
.u-empty { color: #98a2b3; font-size: 13px; text-align: center; padding: 60px 0; }

/* 响应式（断点与全局一致：≤1024 平板 / ≤640 手机） */
@media (max-width: 1024px) {
  .p-cards { grid-template-columns: repeat(auto-fill, minmax(260px, 1fr)); }
  .cards { grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); }
  .test-box { margin-top: 10px; }
}

@media (max-width: 640px) {
  .page-head { flex-direction: column; align-items: stretch; }
  .ph-right { flex-direction: column; align-items: stretch; }
  .ph-right .el-button { width: 100%; }
  .fr-input { max-width: 100%; }
  .kpi-row { grid-template-columns: repeat(auto-fit, minmax(140px, 1fr)); gap: 10px; }
  .p-cards, .cards { grid-template-columns: 1fr; }
  .u-ctrl { width: 100%; }
  .u-ctrl .el-select { flex: 1; }
  .u-chart { height: 200px; }
  .hint { font-size: 11px; }
  .mono { font-size: 11px; }
}
</style>
