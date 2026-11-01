<template>
  <div class="myai">
    <div class="hd">
      <div class="hd-text">
        <h2>我的 AI 模型</h2>
        <p class="sub">仅自己可见，与管理员配置的模型互不影响。对话时可选择自己的模型，也可继续用管理员的。</p>
      </div>
      <el-button type="primary" @click="openCreate">新增模型</el-button>
    </div>

    <el-empty v-if="!list.length" description="还没有配置自己的模型" />
    <div v-else class="list">
      <div v-for="m in list" :key="m.id" class="item">
        <div class="info">
          <div class="nm">
            {{ m.name }}
            <el-tag size="small" effect="plain">{{ m.provider }}</el-tag>
            <el-tag v-if="!m.enabled" size="small" type="info" effect="plain">已停用</el-tag>
          </div>
          <div class="meta">{{ m.model }} · {{ m.baseUrl }}</div>
          <div class="key" v-if="m.keySet">Key：{{ m.keyMasked }}</div>
        </div>
        <div class="acts">
          <el-switch :model-value="m.enabled" @change="(v) => toggle(m, v)" />
          <el-button text size="small" @click="openEdit(m)">编辑</el-button>
          <el-button text size="small" type="danger" @click="remove(m)">删除</el-button>
        </div>
      </div>
    </div>

    <el-dialog v-model="dlg" :title="form.id ? '编辑模型' : '新增模型'" width="480px">
      <el-form label-width="90px">
        <el-form-item label="名称">
          <el-input v-model="form.name" placeholder="如 我的 DeepSeek" />
        </el-form-item>
        <el-form-item label="平台">
          <el-select v-model="form.provider" style="width: 100%">
            <el-option v-for="p in PROVIDERS" :key="p.value" :label="p.label" :value="p.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="接口地址">
          <el-input v-model="form.baseUrl" placeholder="https://api.deepseek.com/v1" />
        </el-form-item>
        <el-form-item label="API Key">
          <el-input v-model="form.apiKey" type="password" show-password
                    :placeholder="form.id ? '留空表示不修改' : 'sk-...'" />
        </el-form-item>
        <el-form-item label="模型名">
          <el-input v-model="form.model" placeholder="deepseek-chat" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dlg = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listMyAiModels, createMyAiModel, updateMyAiModel, deleteMyAiModel } from '../api/ai'

const PROVIDERS = [
  { value: 'CUSTOM', label: '自定义（OpenAI 兼容）' },
  { value: 'OPENAI', label: 'OpenAI' },
  { value: 'DASHSCOPE', label: '阿里百炼' },
  { value: 'DEEPSEEK', label: 'DeepSeek' },
  { value: 'ZHIPU', label: '智谱' },
]

const list = ref([])
const dlg = ref(false)
const saving = ref(false)
const form = ref(blank())

function blank() {
  return { id: null, name: '', provider: 'CUSTOM', baseUrl: '', apiKey: '', model: '', enabled: true }
}

async function load() {
  try {
    const { data } = await listMyAiModels()
    list.value = Array.isArray(data) ? data : []
  } catch (e) { /* 未登录等静默 */ }
}

function openCreate() {
  form.value = blank()
  dlg.value = true
}

function openEdit(m) {
  form.value = { ...m, apiKey: '' }
  dlg.value = true
}

async function save() {
  const f = form.value
  if (!f.name || !f.model || !f.baseUrl) { ElMessage.warning('请填写名称、模型名、接口地址'); return }
  if (!f.id && !f.apiKey) { ElMessage.warning('请填写 API Key'); return }
  saving.value = true
  try {
    if (f.id) await updateMyAiModel(f.id, f)
    else await createMyAiModel(f)
    ElMessage.success('已保存')
    dlg.value = false
    await load()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || e?.response?.data?.error || '保存失败')
  } finally {
    saving.value = false
  }
}

async function toggle(m, v) {
  try {
    await updateMyAiModel(m.id, { ...m, enabled: v, apiKey: '' })
    m.enabled = v
  } catch (e) {
    ElMessage.error('操作失败')
  }
}

async function remove(m) {
  try {
    await ElMessageBox.confirm('删除后不可恢复，确定？', '删除模型', { type: 'warning' })
    await deleteMyAiModel(m.id)
    ElMessage.success('已删除')
    await load()
  } catch (e) { /* 取消 */ }
}

onMounted(load)
</script>

<style scoped>
.myai { max-width: 820px; margin: 0 auto; padding: 24px 16px 40px; }
.hd { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 16px; }
.hd h2 { margin: 0; font-size: 18px; }
.sub { margin: 4px 0 0; font-size: 13px; color: #86909c; line-height: 1.6; }
.list { display: flex; flex-direction: column; gap: 10px; }
.item { display: flex; align-items: center; justify-content: space-between; gap: 12px;
  padding: 12px 14px; border: 1px solid #e5e6eb; border-radius: 10px; background: #fff; }
.nm { display: flex; align-items: center; gap: 8px; font-size: 14px; font-weight: 600; }
.meta { font-size: 12px; color: #86909c; margin-top: 4px; word-break: break-all; }
.key { font-size: 12px; color: #b6bdc9; margin-top: 2px; }
.acts { display: flex; align-items: center; gap: 4px; flex-shrink: 0; }
</style>
