<script setup>
/**
 * 移动端原生「AI 模型管理」页（替代 iframe 嵌桌面版 AiModelManage.vue）
 *
 * 为什么重写：桌面版是「ECharts 图表 + 三张 el-table + 表单弹窗」，手机上表格横向溢出、
 * 图表压扁、弹窗挤成一团，基本不可用。这里按手机习惯重排为三段：
 *   ① 模型：卡片列表（平台 / 默认标记 / 启用状态），点卡片弹底部操作层
 *           —— 启停 / 设为默认 / 测试连接 / 编辑 / 删除
 *   ② 用量：时间范围 + 汇总卡 + 按模型明细（用进度条代替表格）
 *   ③ 设置：消息设置（高消耗确认开关 + 图片张数 / 文本字数阈值）
 *
 * 接口沿用 api/ai.js 里已有的 admin* 系列，不改后端。
 */
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import api from '../../api/request'
import {
  adminListModels, adminModelPlatforms, adminSaveModel, adminDeleteModel,
  adminToggleModel, adminSetDefaultModel, adminTestModel, adminUsage,
} from '../../api/ai'

const router = useRouter()

const tab = ref('models')          // models | usage | settings
const models = ref([])
const platforms = ref([])
const loading = ref(false)
const busy = ref(false)

const TABS = [
  { key: 'models', label: '模型' },
  { key: 'usage', label: '用量' },
  { key: 'settings', label: '设置' },
]

// ---------------- 模型 ----------------
async function loadModels() {
  loading.value = true
  try {
    const { data } = await adminListModels()
    models.value = Array.isArray(data) ? data : (data.content || [])
  } catch (e) {
    window.__kbMsg?.error(e?.response?.data?.error || '加载失败')
  } finally {
    loading.value = false
  }
}

async function loadPlatforms() {
  try {
    const { data } = await adminModelPlatforms()
    platforms.value = Array.isArray(data) ? data : []
  } catch { /* 平台列表拿不到不阻塞主流程 */ }
}

// 卡片上的平台名（后端可能给 platformKey，也可能只在 baseUrl 上体现）
const platformLabel = (m) => {
  const p = platforms.value.find((x) => x.key === m.platform)
  return p?.label || m.platform || '自定义'
}

// ---------------- 卡片操作层 ----------------
const active = ref(null)
const sheetOpen = ref(false)
function openSheet(m) {
  active.value = m
  sheetOpen.value = true
}

async function run(fn, okMsg) {
  if (busy.value) return
  busy.value = true
  try {
    await fn()
    window.__kbMsg?.success(okMsg)
    sheetOpen.value = false
    await loadModels()
  } catch (e) {
    window.__kbMsg?.error(e?.response?.data?.error || '操作失败')
  } finally {
    busy.value = false
  }
}

const toggleModel = () => run(
  () => adminToggleModel(active.value.id, !active.value.enabled),
  active.value.enabled ? '已停用' : '已启用'
)
const setDefault = () => run(() => adminSetDefaultModel(active.value.id), '已设为默认模型')

const testing = ref(false)
async function testModel() {
  if (testing.value) return
  testing.value = true
  try {
    const { data } = await adminTestModel(active.value.id)
    window.__kbMsg?.success(`连接正常（${data.latencyMs || '?'}ms）`)
  } catch (e) {
    window.__kbMsg?.error('连接失败：' + (e?.response?.data?.error || e.message))
  } finally {
    testing.value = false
  }
}

function delModel() {
  const m = active.value
  ElMessageBox.confirm(`确定删除模型「${m.name}」？删除后不可恢复。`, '删除模型', {
    confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning',
  }).then(() => run(() => adminDeleteModel(m.id), '已删除')).catch(() => { /* 取消 */ })
}

// ---------------- 新增 / 编辑 ----------------
const formOpen = ref(false)
const form = ref({})
const isNew = computed(() => !form.value.id)

function openForm(m) {
  sheetOpen.value = false
  form.value = m
    ? { ...m, apiKey: '' }   // Key 一律留空：后端约定「空 = 不改动」
    : { platform: '', name: '', baseUrl: '', model: '', apiKey: '', enabled: true, isDefault: false }
  formOpen.value = true
}

/** 选平台时自动带出接口地址（与桌面版一致） */
function onPlatformChange(key) {
  const p = platforms.value.find((x) => x.key === key)
  if (p?.baseUrl) form.value.baseUrl = p.baseUrl
}

async function submitForm() {
  const f = form.value
  if (!f.name?.trim()) { window.__kbMsg?.warning('请填显示名称'); return }
  if (!f.model?.trim()) { window.__kbMsg?.warning('请填模型版本'); return }
  if (isNew.value && !f.apiKey?.trim()) { window.__kbMsg?.warning('新建模型必须填 API Key'); return }
  if (busy.value) return
  busy.value = true
  try {
    await adminSaveModel({
      id: f.id || null,
      platform: f.platform || null,
      name: f.name.trim(),
      baseUrl: f.baseUrl?.trim() || null,
      model: f.model.trim(),
      apiKey: f.apiKey || '',
      enabled: !!f.enabled,
      isDefault: !!f.isDefault,
    })
    window.__kbMsg?.success(isNew.value ? '已新增' : '已保存')
    formOpen.value = false
    await loadModels()
  } catch (e) {
    window.__kbMsg?.error(e?.response?.data?.error || '保存失败')
  } finally {
    busy.value = false
  }
}

// ---------------- 用量 ----------------
const usageDays = ref(7)
const usage = ref(null)
const usageLoading = ref(false)
const DAY_OPTS = [1, 7, 30, 90]

async function loadUsage() {
  usageLoading.value = true
  try {
    const { data } = await adminUsage({ days: usageDays.value })
    usage.value = data
  } catch (e) {
    window.__kbMsg?.error(e?.response?.data?.error || '用量加载失败')
  } finally {
    usageLoading.value = false
  }
}
function pickDays(d) { usageDays.value = d; loadUsage() }

// 明细里请求数的最大值，用于算进度条宽度
const usageMax = computed(() => {
  const rows = usage.value?.models || []
  return Math.max(1, ...rows.map((r) => Number(r.requests) || 0))
})
const pct = (n) => Math.round(((Number(n) || 0) / usageMax.value) * 100)

// ---------------- 消息设置（高消耗确认） ----------------
const guard = ref({ enabled: true, imageThreshold: 2, textThreshold: 8000 })
const guardSaving = ref(false)

async function loadGuard() {
  try {
    const { data } = await api.get('/admin/settings/ai_cost_guard')
    if (data?.value) Object.assign(guard.value, JSON.parse(data.value))
  } catch { /* 用默认值 */ }
}
async function saveGuard() {
  if (guardSaving.value) return
  guardSaving.value = true
  try {
    await api.put('/admin/settings/ai_cost_guard', { value: JSON.stringify(guard.value) })
    window.__kbMsg?.success('已保存')
  } catch (e) {
    window.__kbMsg?.error(e?.response?.data?.error || '保存失败')
  } finally {
    guardSaving.value = false
  }
}

onMounted(async () => {
  await Promise.all([loadPlatforms(), loadGuard()])
  loadModels()
  loadUsage()
})
</script>

<template>
  <div class="page">
    <header class="m-header solid">
      <button class="m-header-act" @click="router.back()"><el-icon :size="20"><ArrowLeft /></el-icon></button>
      <div class="h-title">AI 模型管理</div>
      <button v-if="tab === 'models'" class="m-header-act" @click="openForm(null)"><el-icon :size="20"><Plus /></el-icon></button>
      <span v-else class="h-ph"></span>
    </header>

    <!-- 分段 Tab -->
    <div class="seg">
      <button v-for="t in TABS" :key="t.key" class="seg-btn" :class="{ on: tab === t.key }"
              @click="tab = t.key">{{ t.label }}</button>
    </div>

    <!-- ① 模型 -->
    <div v-if="tab === 'models'" class="body">
      <div class="m-card">
        <button v-for="m in models" :key="m.id" class="m-row" type="button" @click="openSheet(m)">
          <span class="ai-ava" :class="{ off: !m.enabled }"><el-icon :size="18"><MagicStick /></el-icon></span>
          <span class="m-row-body">
            <span class="m-row-title">
              {{ m.name }}
              <span v-if="m.isDefault" class="m-chip green df">默认</span>
            </span>
            <span class="m-row-desc">{{ platformLabel(m) }} · {{ m.model || '—' }}</span>
          </span>
          <span class="m-chip" :class="m.enabled ? 'gray' : 'red'">{{ m.enabled ? '启用' : '停用' }}</span>
        </button>
        <div v-if="!models.length && !loading" class="empty">还没有配置模型</div>
      </div>
      <button class="m-btn ghost add" @click="openForm(null)">
        <el-icon><Plus /></el-icon> 新增模型
      </button>
    </div>

    <!-- ② 用量 -->
    <div v-else-if="tab === 'usage'" class="body">
      <div class="chips">
        <button v-for="d in DAY_OPTS" :key="d" class="chip" :class="{ on: usageDays === d }" @click="pickDays(d)">
          {{ d === 1 ? '今天' : '近 ' + d + ' 天' }}
        </button>
      </div>

      <div class="stat-row">
        <div class="stat">
          <div class="stat-n">{{ usage?.summary?.requests ?? 0 }}</div>
          <div class="stat-l">请求</div>
        </div>
        <div class="stat">
          <div class="stat-n">{{ usage?.summary?.success ?? 0 }}</div>
          <div class="stat-l">成功</div>
        </div>
        <div class="stat">
          <div class="stat-n">{{ usage?.summary?.failed ?? 0 }}</div>
          <div class="stat-l">失败</div>
        </div>
        <div class="stat">
          <div class="stat-n">{{ usage?.summary?.tokens ?? 0 }}</div>
          <div class="stat-l">Tokens</div>
        </div>
      </div>

      <div class="m-card sec">
        <div class="sec-h">按模型</div>
        <div v-for="r in (usage?.models || [])" :key="r.id || r.name" class="u-row">
          <div class="u-top">
            <span class="u-name">{{ r.name || r.model || '未知' }}</span>
            <span class="u-num">{{ r.requests || 0 }} 次</span>
          </div>
          <div class="u-bar"><i :style="{ width: pct(r.requests) + '%' }"></i></div>
          <div class="u-sub">成功 {{ r.success || 0 }} · 失败 {{ r.failed || 0 }} · {{ r.tokens || 0 }} tokens</div>
        </div>
        <div v-if="!(usage?.models || []).length && !usageLoading" class="empty">该时间段没有调用记录</div>
      </div>
    </div>

    <!-- ③ 设置 -->
    <div v-else class="body">
      <div class="m-card sec">
        <div class="sec-h">消息设置</div>
        <div class="set-row">
          <div class="set-txt">
            <div class="set-t">高消耗操作先确认</div>
            <div class="set-d">发送图片较多或文字很长时，先弹确认再调用模型</div>
          </div>
          <el-switch v-model="guard.enabled" />
        </div>
        <div class="set-row" :class="{ off: !guard.enabled }">
          <div class="set-txt"><div class="set-t">图片张数阈值</div></div>
          <input v-model.number="guard.imageThreshold" class="set-in" type="number" min="1" :disabled="!guard.enabled" />
        </div>
        <div class="set-row" :class="{ off: !guard.enabled }">
          <div class="set-txt"><div class="set-t">文本字数阈值</div></div>
          <input v-model.number="guard.textThreshold" class="set-in" type="number" min="100" :disabled="!guard.enabled" />
        </div>
        <button class="m-btn" :disabled="guardSaving" style="margin:12px 0 0" @click="saveGuard">
          {{ guardSaving ? '保存中…' : '保存设置' }}
        </button>
      </div>
    </div>

    <!-- 模型操作层 -->
    <transition name="sheet-up">
      <div v-if="sheetOpen" class="m-sheet-mask" @click.self="sheetOpen = false">
        <div class="m-sheet">
          <div class="m-sheet-grip"></div>
          <div class="m-sheet-title">{{ active?.name }}</div>
          <button class="m-sheet-item" :disabled="busy" @click="toggleModel()">
            <el-icon><component :is="active?.enabled ? 'CircleCloseFilled' : 'CircleCheckFilled'" /></el-icon>
            {{ active?.enabled ? '停用该模型' : '启用该模型' }}
          </button>
          <button class="m-sheet-item" :disabled="busy || active?.isDefault" @click="setDefault()">
            <el-icon><Star /></el-icon> {{ active?.isDefault ? '已是默认模型' : '设为默认模型' }}
          </button>
          <button class="m-sheet-item" :disabled="testing" @click="testModel()">
            <el-icon><Refresh /></el-icon> {{ testing ? '测试中…' : '测试连接' }}
          </button>
          <button class="m-sheet-item" @click="openForm(active)">
            <el-icon><EditPen /></el-icon> 编辑配置
          </button>
          <button class="m-sheet-item danger" :disabled="busy" @click="delModel()">
            <el-icon><Delete /></el-icon> 删除模型
          </button>
          <button class="m-sheet-item cancel" @click="sheetOpen = false">取消</button>
        </div>
      </div>
    </transition>

    <!-- 新增 / 编辑表单 -->
    <transition name="sheet-up">
      <div v-if="formOpen" class="m-sheet-mask" @click.self="formOpen = false">
        <div class="m-sheet form-sheet">
          <div class="m-sheet-grip"></div>
          <div class="m-sheet-title">{{ isNew ? '新增模型' : '编辑模型' }}</div>

          <label class="f-label">平台</label>
          <select v-model="form.platform" class="f-in" @change="onPlatformChange(form.platform)">
            <option value="">自定义</option>
            <option v-for="p in platforms" :key="p.key" :value="p.key">{{ p.label }}</option>
          </select>

          <label class="f-label">显示名称</label>
          <input v-model="form.name" class="f-in" placeholder="如：DeepSeek 主力" />

          <label class="f-label">接口地址</label>
          <input v-model="form.baseUrl" class="f-in" placeholder="https://api.example.com/v1" />

          <label class="f-label">模型版本</label>
          <input v-model="form.model" class="f-in" placeholder="如：deepseek-chat" />

          <label class="f-label">API Key</label>
          <input v-model="form.apiKey" class="f-in" type="password"
                 :placeholder="isNew ? '必填' : '留空表示不修改'" autocomplete="new-password" />

          <div class="f-switch">
            <span>启用</span>
            <el-switch v-model="form.enabled" />
          </div>
          <div class="f-switch">
            <span>设为默认模型</span>
            <el-switch v-model="form.isDefault" />
          </div>

          <button class="m-btn" :disabled="busy" style="margin-top:14px" @click="submitForm">
            {{ busy ? '保存中…' : (isNew ? '新增' : '保存') }}
          </button>
          <button class="m-sheet-item cancel" @click="formOpen = false">取消</button>
        </div>
      </div>
    </transition>
  </div>
</template>

<style scoped>
.page { min-height: 100%; display: flex; flex-direction: column; }
.h-title { flex: 1 1 auto; text-align: center; font-size: 16.5px; font-weight: 600; }
.h-ph { width: 38px; }

/* 分段 Tab */
.seg { display: flex; gap: 6px; margin: 6px 16px 10px; padding: 3px; background: var(--m-card-2, #f2f3f5); border-radius: 11px; }
.seg-btn {
  flex: 1 1 0; height: 32px; border: none; border-radius: 9px; background: transparent;
  color: var(--m-text-sub); font-size: 13.5px; cursor: pointer; transition: all .16s;
}
.seg-btn.on { background: var(--m-card, #fff); color: var(--m-primary); font-weight: 600; }

.body { flex: 1 1 auto; padding: 0 16px 20px; }
.ai-ava {
  flex: none; width: 40px; height: 40px; border-radius: 12px;
  display: grid; place-items: center;
  background: var(--m-primary-weak, #e8eefe); color: var(--m-primary);
}
.ai-ava.off { background: var(--m-card-2); color: var(--m-text-sub); }
.m-chip.df { margin-left: 6px; }
.add { width: 100%; margin-top: 12px; }
.empty { padding: 30px 0; text-align: center; color: var(--m-text-sub); font-size: 13.5px; }

.chips { display: flex; gap: 8px; padding: 0 0 12px; }
.chip {
  height: 30px; padding: 0 14px; border-radius: 15px; border: none;
  background: var(--m-card); color: var(--m-text-sub); font-size: 13px;
}
.chip.on { background: var(--m-primary); color: #fff; font-weight: 500; }

/* 汇总 */
.stat-row { display: grid; grid-template-columns: repeat(4, 1fr); gap: 8px; margin-bottom: 12px; }
.stat { background: var(--m-card); border-radius: 12px; padding: 12px 6px; text-align: center; }
.stat-n { font-size: 17px; font-weight: 700; color: var(--m-text); font-variant-numeric: tabular-nums; }
.stat-l { font-size: 11.5px; color: var(--m-text-sub); margin-top: 3px; }

.sec { padding: 14px; }
.sec-h { font-size: 13px; font-weight: 600; color: var(--m-text-sub); margin-bottom: 10px; }
.u-row { padding: 10px 0; border-bottom: 1px solid var(--m-border); }
.u-row:last-child { border-bottom: none; }
.u-top { display: flex; align-items: baseline; justify-content: space-between; gap: 8px; }
.u-name { font-size: 14px; color: var(--m-text); font-weight: 500; }
.u-num { font-size: 13px; color: var(--m-primary); font-variant-numeric: tabular-nums; }
.u-bar { height: 6px; border-radius: 3px; background: var(--m-card-2); margin: 7px 0 5px; overflow: hidden; }
.u-bar i { display: block; height: 100%; background: var(--m-primary); border-radius: 3px; }
.u-sub { font-size: 11.5px; color: var(--m-text-sub); }

/* 设置 */
.set-row {
  display: flex; align-items: center; gap: 12px;
  padding: 12px 0; border-bottom: 1px solid var(--m-border);
}
.set-row:last-of-type { border-bottom: none; }
.set-row.off { opacity: .5; }
.set-txt { flex: 1 1 auto; min-width: 0; }
.set-t { font-size: 14px; color: var(--m-text); }
.set-d { font-size: 11.5px; color: var(--m-text-sub); margin-top: 2px; line-height: 1.5; }
.set-in {
  flex: none; width: 92px; height: 36px; padding: 0 10px; text-align: right;
  border: 1px solid var(--m-border); border-radius: 9px;
  background: var(--m-bg); color: var(--m-text); font-size: 15px; font-family: inherit;
}

/* 表单 */
.form-sheet { max-height: 88vh; overflow-y: auto; }
.f-label { display: block; font-size: 12.5px; color: var(--m-text-sub); margin: 12px 0 6px; }
.f-in {
  width: 100%; height: 44px; padding: 0 12px; box-sizing: border-box;
  border: 1px solid var(--m-border); border-radius: 10px;
  background: var(--m-bg); color: var(--m-text); font-size: 16px; font-family: inherit;
}
.f-switch {
  display: flex; align-items: center; justify-content: space-between;
  padding: 13px 0; border-bottom: 1px solid var(--m-border); font-size: 14px; color: var(--m-text);
}
.f-switch:last-of-type { border-bottom: none; }
</style>
