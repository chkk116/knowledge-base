<template>
  <div class="myai">
    <header class="hd">
      <div>
        <h2>我的 AI 模型</h2>
        <p class="sub">仅自己可见，与管理员配置的模型互不影响。对话时可选择自己的模型，也可继续用管理员的。</p>
      </div>
      <el-button type="primary" round @click="openCreate">添加模型</el-button>
    </header>

    <div v-if="!list.length" class="empty">
      <div class="empty-mark">＋</div>
      <p class="empty-t">还没有自定义模型</p>
      <el-button text type="primary" @click="openCreate">添加第一个</el-button>
    </div>

    <div v-else class="cards">
      <div v-for="m in list" :key="m.id" class="card" :class="{ off: !m.enabled }">
        <ProviderLogo :provider="m.provider" :size="34" />
        <div class="meta">
          <div class="name">{{ m.name }}</div>
          <div class="model">{{ m.model }}</div>
        </div>
        <el-switch :model-value="m.enabled" @change="(v) => toggle(m, v)" />
        <el-dropdown trigger="click" @command="(c) => onCmd(c, m)">
          <el-button text circle class="more"><el-icon><MoreFilled /></el-icon></el-button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="edit">编辑</el-dropdown-item>
              <el-dropdown-item command="del" divided>删除</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </div>

    <el-dialog v-model="dlg" :title="form.id ? '编辑模型' : '添加模型'" width="460px" align-center>
      <el-form label-position="top">
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
        <el-form-item>
          <el-switch v-model="form.enabled" active-text="启用" />
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
import { MoreFilled } from '@element-plus/icons-vue'
import ProviderLogo from '../components/ProviderLogo.vue'
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

function onCmd(c, m) {
  if (c === 'edit') openEdit(m)
  else if (c === 'del') remove(m)
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
.myai { max-width: 720px; margin: 0 auto; padding: 28px 20px 48px; }
.hd { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-bottom: 20px; }
.hd h2 { margin: 0; font-size: 20px; font-weight: 600; color: #1d2129; }
.sub { margin: 4px 0 0; font-size: 13px; color: #86909c; }

.empty { display: flex; flex-direction: column; align-items: center; padding: 64px 0 72px; }
.empty-mark { width: 56px; height: 56px; border-radius: 16px; background: #f2f3f5;
  display: flex; align-items: center; justify-content: center; font-size: 26px; color: #c9cdd4; margin-bottom: 14px; }
.empty-t { font-size: 14px; color: #86909c; margin: 0 0 2px; }

.cards { display: flex; flex-direction: column; gap: 10px; }
.card { display: flex; align-items: center; gap: 12px; padding: 14px 16px; background: #fff;
  border: 1px solid #e5e6eb; border-radius: 12px; transition: box-shadow .15s, border-color .15s; }
.card:hover { box-shadow: 0 4px 16px rgba(15, 23, 42, .06); border-color: #d9dbe0; }
.card.off { opacity: .55; }
.meta { flex: 1; min-width: 0; }
.name { font-size: 14px; font-weight: 600; color: #1d2129; }
.model { font-size: 12px; color: #86909c; margin-top: 2px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.more { color: #86909c; }
</style>
