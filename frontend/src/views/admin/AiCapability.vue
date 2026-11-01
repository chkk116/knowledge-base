<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Picture, VideoCamera, Document } from '@element-plus/icons-vue'
import { aiCapabilities, saveAiCapabilities } from '../../api/admin'

/**
 * AI 能力配置：文生图 / 文生视频 / OCR 三段配置放在同一张卡片里。
 * 每段自己填「平台 + 接口地址 + API Key + 模型名」，与「AI 模型管理」的对话模型池完全独立，
 * 不会互相干扰。未配置时业务侧沿用原兜底（媒体取模型池、OCR 走视觉模型链）。
 */
const KEYS = ['image', 'video', 'ocr']
const SECTIONS = [
  { key: 'image', title: '文生图', icon: Picture, desc: 'AI 对话 / Agent 生成图片时使用' },
  { key: 'video', title: '文生视频', icon: VideoCamera, desc: '生成视频（提交任务 + 轮询）时使用' },
  { key: 'ocr', title: 'OCR 文字识别', icon: Document, desc: '图片转文字、文档导入识别时使用' },
]

const PROVIDER_BASE = {
  ZHIPU: 'https://open.bigmodel.cn/api/paas/v4',
  DASHSCOPE: 'https://dashscope.aliyuncs.com/compatible-mode/v1',
  CUSTOM: '',
}
const PRESETS = {
  image: ['cogview-3-flash', 'cogview-4', 'wanx2.1-t2i-turbo'],
  video: ['cogvideox-3', 'cogvideox-flash', 'wanx2.1-t2v-turbo'],
  ocr: ['glm-4.6v-flash', 'glm-4.1v-thinking-flash', 'qwen-vl-max', 'qwen-vl-plus'],
}

const loading = ref(false)
const saving = ref(false)
const cur = ref({})
// 必须先有默认值：模板在 load() 返回前就会渲染，form 为空会报 reading 'enabled'
const form = ref({ image: blankForm(), video: blankForm(), ocr: blankForm() })
const clearFlags = ref({})

function blankForm() {
  return { provider: 'ZHIPU', baseUrl: PROVIDER_BASE.ZHIPU, apiKey: '', model: '', enabled: false }
}

async function load() {
  loading.value = true
  try {
    const { data } = await aiCapabilities()
    cur.value = data || {}
    const next = {}
    for (const k of KEYS) {
      const d = data?.[k] || {}
      next[k] = {
        provider: d.provider || 'ZHIPU',
        baseUrl: d.baseUrl || PROVIDER_BASE[d.provider || 'ZHIPU'] || '',
        apiKey: '',
        model: d.model || '',
        // 未配置过的能力默认关闭：只配其中一项时，不会因另外两项为空被校验拦住
        enabled: d.configured ? d.enabled !== false : false,
      }
    }
    form.value = next
    clearFlags.value = {}
  } catch (e) {
    ElMessage.error(e.response?.data?.error || '加载配置失败')
    form.value = { image: blankForm(), video: blankForm(), ocr: blankForm() }
  } finally {
    loading.value = false
  }
}

function onProvider(key) {
  const p = form.value[key].provider
  form.value[key].baseUrl = PROVIDER_BASE[p] || ''
}

function clearKey(key) {
  form.value[key].apiKey = ''
  clearFlags.value[key] = true
  ElMessage.warning('保存后将清除该能力的 Key')
}

async function save() {
  saving.value = true
  try {
    const payload = {}
    for (const k of KEYS) {
      payload[k] = { ...form.value[k], clearKey: !!clearFlags.value[k] }
    }
    const { data } = await saveAiCapabilities(payload)
    cur.value = data || {}
    for (const k of KEYS) {
      form.value[k].apiKey = ''
      clearFlags.value[k] = false
    }
    ElMessage.success('已保存，立即生效')
  } catch (e) {
    ElMessage.error(e.response?.data?.error || '保存失败')
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="cap-page" v-loading="loading">
    <div class="hd">
      <div>
        <h1>AI 能力配置</h1>
        <p class="sub">
          文生图、文生视频、OCR 各自一套「平台 / 接口地址 / Key / 模型名」，与「AI 模型管理」的对话模型完全独立。
          未配置时沿用原兜底逻辑，不会影响现有功能。
        </p>
      </div>
      <el-button type="primary" :loading="saving" @click="save">保存配置</el-button>
    </div>

    <section class="card">
      <div v-for="(s, idx) in SECTIONS" :key="s.key" class="sec" :class="{ divided: idx > 0 }">
        <div class="sec-hd">
          <span class="ic"><el-icon><component :is="s.icon" /></el-icon></span>
          <span class="t">{{ s.title }}</span>
          <el-tag v-if="cur[s.key]?.configured" size="small" type="success" effect="plain">已配置</el-tag>
          <el-tag v-else size="small" type="info" effect="plain">未配置</el-tag>
          <span class="spacer" />
          <span class="sw-label">{{ form[s.key]?.enabled ? '启用' : '停用' }}</span>
          <el-switch v-model="form[s.key].enabled" size="small" />
        </div>
        <p class="sec-desc">{{ s.desc }}</p>

        <div class="grid">
          <label>平台</label>
          <el-select v-model="form[s.key].provider" size="small" @change="onProvider(s.key)">
            <el-option label="智谱 BigModel" value="ZHIPU" />
            <el-option label="通义 DashScope" value="DASHSCOPE" />
            <el-option label="自定义（OpenAI 兼容）" value="CUSTOM" />
          </el-select>

          <label>接口地址</label>
          <el-input v-model="form[s.key].baseUrl" size="small" placeholder="留空使用平台默认地址" />

          <label>API Key</label>
          <el-input
            v-model="form[s.key].apiKey" size="small" type="password" show-password
            :placeholder="cur[s.key]?.keyMasked ? `已配置 ${cur[s.key].keyMasked}，留空不修改` : '必填'" />

          <label>模型名</label>
          <el-select
            v-model="form[s.key].model" size="small" filterable allow-create default-first-option
            placeholder="选择或直接输入模型名">
            <el-option v-for="m in PRESETS[s.key]" :key="m" :label="m" :value="m" />
          </el-select>
        </div>

        <div class="sec-foot">
          <span v-if="cur[s.key]?.configured" class="ok">当前生效：{{ cur[s.key].model }}</span>
          <span v-else class="warn">未单独配置，沿用模型库兜底</span>
          <el-button v-if="cur[s.key]?.configured" text size="small" @click="clearKey(s.key)">清除 Key</el-button>
        </div>
      </div>

      <p class="tip">这里的 Key 只用于对应能力，不会出现在「AI 模型管理」的对话模型列表里。</p>
    </section>
  </div>
</template>

<style scoped>
.cap-page { max-width: 880px; margin: 0 auto; padding: 4px 2px 40px; }
.hd { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 16px; }
.hd h1 { margin: 0; font-size: 22px; }
.hd .sub { margin: 6px 0 0; max-width: 640px; font-size: 13px; line-height: 1.7; color: #64748b; }

.card { background: #fff; border: 1px solid #eef2f7; border-radius: 14px; padding: 4px 20px 14px; }
.sec { padding: 16px 0 14px; }
.sec.divided { border-top: 1px solid #f1f5f9; }
.sec-hd { display: flex; align-items: center; gap: 8px; }
.ic { width: 26px; height: 26px; border-radius: 7px; background: #eef2ff; color: #2563eb; display: inline-flex; align-items: center; justify-content: center; }
.t { font-size: 15px; font-weight: 600; color: #1e293b; }
.spacer { flex: 1; }
.sw-label { font-size: 12px; color: #94a3b8; }
.sec-desc { margin: 6px 0 12px; font-size: 12.5px; color: #94a3b8; }

.grid { display: grid; grid-template-columns: 84px 1fr; gap: 8px 12px; align-items: center; max-width: 620px; }
.grid label { font-size: 13px; color: #475569; text-align: right; }

.sec-foot { display: flex; align-items: center; gap: 10px; margin-top: 10px; font-size: 12.5px; }
.sec-foot .ok { color: #16a34a; }
.sec-foot .warn { color: #b45309; }
.tip { margin: 6px 0 0; font-size: 12px; color: #94a3b8; }

:deep(html.dark) .card { background: #1b1b2e; border-color: #2c2c44; }
:deep(html.dark) .sec.divided { border-top-color: #26263e; }
:deep(html.dark) .t { color: #e5e7f0; }
:deep(html.dark) .ic { background: #2a2a46; color: #a9c2ff; }
:deep(html.dark) .grid label { color: #a5a7bd; }
</style>
