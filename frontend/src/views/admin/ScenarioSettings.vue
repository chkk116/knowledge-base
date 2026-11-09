<script setup>
/**
 * 场景模板（站点设置 → 场景模板）
 *
 * 「自由选择组件」：部署单位按自己的场景选一个模板，全站高频入口的叫法随之切换
 * （侧栏菜单 / 首页入口 / AI 推荐问题）。只改「叫法」，不动数据结构，切换零迁移。
 *
 * 规则：
 *   - 4 个内置模板；未选择 / 选「通用」时一切回落默认文案，行为与从前完全一致；
 *   - 术语可微调（高级区直接编辑 JSON），保存前统一 normalize（非法值丢弃、超长截断）；
 *   - 写接口需 CORE_MAINTAIN 权限（后端 PUT /admin/settings/{key} 已校验）。
 */
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Collection } from '@element-plus/icons-vue'
import { useScenarioStore } from '../../store/scenario'
import { SCENARIOS, MAX_PROMPTS } from '../../utils/scenarioPacks'

const scenario = useScenarioStore()
const saving = ref(false)

/** 正在编辑的副本：key + 术语 + 推荐问题（避免直接改 store，保存成功才落地） */
const draftKey = ref('general')
const draftTerms = ref({})
const draftPrompts = ref('')

/** 高级区 JSON 是否合法（保存前强校验，坏 JSON 不落库） */
const termsJsonOk = computed(() => {
  try {
    const v = JSON.parse(termsText.value)
    return v && typeof v === 'object' && !Array.isArray(v)
  } catch { return false }
})
const termsText = ref('{}')

function loadDraft() {
  draftKey.value = scenario.key
  draftTerms.value = { ...(scenario.pack?.terms || {}) }
  draftPrompts.value = (scenario.pack?.quickPrompts || []).join('\n')
  termsText.value = JSON.stringify(draftTerms.value, null, 2)
}

onMounted(async () => {
  await scenario.load()
  loadDraft()
})

function choose(key) {
  draftKey.value = key
  const builtin = SCENARIOS.find((s) => s.key === key)
  // 切模板时带出该模板的预置术语与推荐问题（用户可再微调）
  if (builtin && builtin.key !== 'general') {
    draftTerms.value = { ...(builtin.terms || {}) }
    termsText.value = JSON.stringify(draftTerms.value, null, 2)
    draftPrompts.value = (builtin.quickPrompts || []).join('\n')
  } else {
    draftTerms.value = {}
    termsText.value = '{}'
    draftPrompts.value = ''
  }
}

function syncTermsFromText() {
  if (!termsJsonOk.value) return
  try { draftTerms.value = JSON.parse(termsText.value) || {} } catch { /* 保持旧值 */ }
}

async function save() {
  if (!termsJsonOk.value) { ElMessage.error('术语 JSON 格式不合法，请检查后重试'); return }
  syncTermsFromText()
  saving.value = true
  try {
    await scenario.save({
      key: draftKey.value,
      terms: draftTerms.value,
      quickPrompts: draftPrompts.value.split('\n').map((s) => s.trim()).filter(Boolean).slice(0, MAX_PROMPTS),
    })
    ElMessage.success('已保存，全站术语即刻生效')
  } catch (e) {
    ElMessage.error(e?.response?.data?.error || '保存失败：' + (e?.message || '请检查网络'))
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div class="sf-page">
    <div class="page-head">
      <div class="head-icon"><el-icon :size="22"><Collection /></el-icon></div>
      <div class="head-text">
        <h2>场景模板</h2>
        <p class="sub">
          按部署单位的场景选择一个模板，侧栏菜单等高频入口的叫法随之切换（如「在线考试」→「在线考核」）。
          只改叫法、不动数据，随时可切回通用。
        </p>
      </div>
    </div>

    <el-card class="cfg" shadow="never">
      <template #header><span class="card-title">选择模板</span></template>
      <div class="sc-grid">
        <div
          v-for="s in SCENARIOS"
          :key="s.key"
          class="sc-card"
          :class="{ on: draftKey === s.key }"
          role="button"
          tabindex="0"
          @click="choose(s.key)"
          @keydown.enter.prevent="choose(s.key)"
        >
          <div class="sc-name">{{ s.name }}<span v-if="draftKey === s.key" class="sc-check">✓</span></div>
          <div class="sc-desc">{{ s.desc }}</div>
          <div class="sc-points">
            <span v-for="pt in s.points" :key="pt">{{ pt }}</span>
          </div>
        </div>
      </div>
    </el-card>

    <el-card class="cfg" shadow="never">
      <template #header>
        <div class="card-head">
          <span class="card-title">术语微调（高级）</span>
          <span class="hint">JSON 格式，键为功能域，值为展示叫法；留空表示用默认叫法</span>
        </div>
      </template>
      <el-input
        v-model="termsText"
        type="textarea" :rows="10" spellcheck="false"
        class="terms-json"
        :class="{ bad: !termsJsonOk }"
        placeholder='{"exams": "在线考核", "docShares": "科室制度库"}'
      />
      <div class="hint" :style="termsJsonOk ? '' : 'color:#f53f3f'">
        {{ termsJsonOk ? 'JSON 合法 ✓ · 可用键：home / write / myPosts / recycle / subscriptions / favorites / history / badges / files / ranking / aiChat / messages / courses / exams / practice / docShares / allDocs' : 'JSON 格式不合法，保存前请修正' }}
      </div>
    </el-card>

    <el-card class="cfg" shadow="never">
      <template #header>
        <div class="card-head">
          <span class="card-title">AI 推荐问题（每行一条，最多 {{ MAX_PROMPTS }} 条）</span>
          <span class="hint">将展示在 AI 问答的空状态，作为该场景的高频提问</span>
        </div>
      </template>
      <el-input
        v-model="draftPrompts"
        type="textarea" :rows="4"
        placeholder="「三查七对」的具体要求是什么？&#10;根据最新制度出 5 道考核题"
      />
    </el-card>

    <el-card class="cfg" shadow="never">
      <div class="save-row">
        <el-button type="primary" :loading="saving" @click="save">保存并生效</el-button>
        <el-button @click="loadDraft">放弃修改</el-button>
        <span class="hint">保存后全站（网页 + 移动端）立即生效，无需重启</span>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.sf-page { display: flex; flex-direction: column; gap: 14px; }
.page-head { display: flex; align-items: center; gap: 12px; }
.head-icon { width: 44px; height: 44px; border-radius: 12px; background: var(--el-color-primary-light-9);
  color: var(--el-color-primary); display: grid; place-items: center; }
.head-text h2 { margin: 0; font-size: 18px; }
.head-text .sub { margin: 3px 0 0; font-size: 12.5px; color: var(--el-text-color-secondary); line-height: 1.6; }
.card-title { font-weight: 600; }
.card-head { display: flex; align-items: baseline; gap: 10px; }
.hint { font-size: 12px; color: var(--el-text-color-secondary); }

.sc-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); gap: 10px; }
.sc-card { border: 1.5px solid var(--el-border-color); border-radius: 12px; padding: 12px 14px;
  cursor: pointer; transition: all .15s; outline: none; }
.sc-card:hover { border-color: var(--el-color-primary-light-5); }
.sc-card.on { border-color: var(--el-color-primary); background: var(--el-color-primary-light-9); }
.sc-name { font-size: 14.5px; font-weight: 600; display: flex; align-items: center; justify-content: space-between; }
.sc-check { color: var(--el-color-primary); font-weight: 700; }
.sc-desc { margin-top: 6px; font-size: 12px; color: var(--el-text-color-secondary); line-height: 1.55; }
.sc-points { margin-top: 8px; display: flex; flex-direction: column; gap: 3px; }
.sc-points span { font-size: 11.5px; color: var(--el-text-color-regular); }
.sc-points span::before { content: '· '; color: var(--el-color-primary); font-weight: 700; }

.terms-json :deep(textarea) { font-family: Consolas, Menlo, monospace; font-size: 12.5px; }
.terms-json.bad :deep(.el-textarea__inner) { border-color: var(--el-color-danger); }
.save-row { display: flex; align-items: center; gap: 12px; }
</style>
