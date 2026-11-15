<script setup>
import { ref, computed } from 'vue'
import { ElMessage } from 'element-plus'
import * as XLSX from 'xlsx'
import { importUsers } from '../../api/admin'

/**
 * 批量导入用户（企业部署第一步）。
 * CSV/XLSX 在前端解析成行数组再提交，后端不引 Excel 依赖；
 * 支持「先校验（dryRun）再导入」，逐行返回成功/跳过/失败原因。
 */
const rows = ref([])
const fileName = ref('')
const defaultPwd = ref('')
const checking = ref(false)
const importing = ref(false)
const result = ref(null)

const preview = computed(() => rows.value.slice(0, 100))

const STATUS_META = {
  created: { text: '已创建', type: 'success' },
  ok: { text: '校验通过', type: 'success' },
  skipped: { text: '已跳过', type: 'warning' },
  failed: { text: '失败', type: 'danger' },
}

/** 表头归一化：去空白、转小写，兼容中英文列名 */
function normKey(k) {
  return String(k || '').trim().toLowerCase().replace(/\s+/g, '')
}
function pick(obj, keys) {
  const map = {}
  for (const [k, v] of Object.entries(obj || {})) map[normKey(k)] = v
  for (const k of keys) {
    const v = map[normKey(k)]
    if (v !== undefined && v !== null && String(v).trim() !== '') return String(v).trim()
  }
  return ''
}

async function onFile(e) {
  const f = e.target.files && e.target.files[0]
  if (!f) return
  fileName.value = f.name
  result.value = null
  try {
    const wb = XLSX.read(await f.arrayBuffer(), { type: 'array' })
    const sheet = wb.Sheets[wb.SheetNames[0]]
    const raw = XLSX.utils.sheet_to_json(sheet, { defval: '' })
    rows.value = raw.map((r) => ({
      username: pick(r, ['用户名', '账号', 'username', 'user', '登录名']),
      displayName: pick(r, ['姓名', '昵称', 'displayName', 'name', '真实姓名']),
      email: pick(r, ['邮箱', 'email', 'mail', '电子邮件']),
      password: pick(r, ['密码', 'password', 'pwd', '初始密码']),
      role: pick(r, ['角色', 'role']),
      // 企微 / 钉钉 / 飞书导出的成员表常见部门列名，统一映射到部门名称或编码
      department: pick(r, ['部门', '所属部门', '部门名称', '部门编码', 'department', 'dept']),
    })).filter((r) => r.username)
    if (!rows.value.length) ElMessage.warning('没有解析到有效行（至少要有「用户名」列）')
    else ElMessage.success(`已解析 ${rows.value.length} 行`)
  } catch (err) {
    rows.value = []
    ElMessage.error('文件解析失败：' + (err?.message || '请确认是 CSV / XLSX'))
  } finally {
    e.target.value = ''   // 允许重复选择同一文件
  }
}

async function run(dryRun) {
  if (!rows.value.length) return ElMessage.warning('请先选择文件')
  const payload = { rows: rows.value, defaultPassword: defaultPwd.value || undefined, dryRun }
  const setBusy = dryRun ? checking : importing
  setBusy.value = true
  try {
    const { data } = await importUsers(payload)
    result.value = data
    if (dryRun) {
      ElMessage.success(`校验完成：可创建 ${data.created}，跳过 ${data.skipped}，失败 ${data.failed}`)
    } else {
      ElMessage.success(`导入完成：成功 ${data.created}，跳过 ${data.skipped}，失败 ${data.failed}`)
      if (data.created > 0) rows.value = []
    }
  } catch (e) {
    ElMessage.error((e.response?.data?.error || e.response?.status || '导入失败'))
  } finally {
    setBusy.value = false
  }
}

function downloadTemplate() {
  const csv = '\uFEFF用户名,姓名,邮箱,密码,角色,部门\nzhangsan,张三,zhangsan@example.com,Init@123456,USER,\n'
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = '用户导入模板.csv'
  document.body.appendChild(a)
  a.click()
  a.remove()
  setTimeout(() => URL.revokeObjectURL(url), 3000)
}

const statusMeta = (s) => STATUS_META[s] || { text: s || '-', type: 'info' }
</script>

<template>
  <div class="imp-page">
    <div class="hd">
      <div>
        <h1>批量导入用户</h1>
        <p class="sub">支持 CSV / XLSX；先校验再导入，逐行反馈结果，失败不影响其他行</p>
      </div>
      <el-button size="small" @click="downloadTemplate">下载 CSV 模板</el-button>
    </div>

    <!-- 1. 选择文件 -->
    <section class="card">
      <div class="steps">
        <span class="step on">1 选择文件</span>
        <span class="step">2 预览校验</span>
        <span class="step">3 确认导入</span>
      </div>

      <div class="row">
        <label class="file-btn">
          <input type="file" accept=".csv,.xlsx,.xls" @change="onFile" />
          <span>选择 CSV / XLSX 文件</span>
        </label>
        <span v-if="fileName" class="file-name">{{ fileName }}</span>
        <span v-if="rows.length" class="file-cnt">共 {{ rows.length }} 行</span>
      </div>

      <div class="row opts">
        <span class="lab">默认密码</span>
        <el-input v-model="defaultPwd" size="small" placeholder="留空则要求每行都填密码" style="max-width: 280px" show-password />
        <span class="hint">列名需包含「用户名」，可选「姓名 / 邮箱 / 密码 / 角色 / 部门」；部门填系统里已建好的名称或编码</span>
      </div>
    </section>

    <!-- 2. 预览 -->
    <section v-if="rows.length" class="card">
      <div class="p-hd">
        预览
        <span class="p-sub">前 {{ preview.length }} 行 / 共 {{ rows.length }} 行</span>
      </div>
      <el-table :data="preview" size="small" max-height="360" border>
        <el-table-column type="index" label="#" width="52" />
        <el-table-column prop="username" label="用户名" min-width="130" />
        <el-table-column prop="displayName" label="姓名" min-width="110" />
        <el-table-column prop="email" label="邮箱" min-width="170" />
        <el-table-column label="密码" width="110">
          <template #default="{ row }">{{ row.password ? '已填' : (defaultPwd ? '用默认' : '缺失') }}</template>
        </el-table-column>
        <el-table-column prop="role" label="角色" width="90">
          <template #default="{ row }">{{ row.role || 'USER' }}</template>
        </el-table-column>
        <el-table-column label="部门" min-width="110">
          <template #default="{ row }">{{ row.department || '未分配' }}</template>
        </el-table-column>
      </el-table>

      <div class="acts">
        <el-button :loading="checking" @click="run(true)">校验（不写入）</el-button>
        <el-button type="primary" :loading="importing" @click="run(false)">确认导入</el-button>
      </div>
    </section>

    <!-- 3. 结果 -->
    <section v-if="result" class="card">
      <div class="p-hd">
        {{ result.dryRun ? '校验结果' : '导入结果' }}
        <span class="p-sub">
          成功 {{ result.created }} · 跳过 {{ result.skipped }} · 失败 {{ result.failed }} / 共 {{ result.total }}
        </span>
      </div>
      <el-table :data="result.results" size="small" max-height="420" border>
        <el-table-column prop="line" label="行号" width="70" />
        <el-table-column prop="username" label="用户名" min-width="130" />
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="statusMeta(row.status).type" effect="plain">{{ statusMeta(row.status).text }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="message" label="说明" min-width="200" />
      </el-table>
      <div v-if="!result.dryRun && result.created > 0" class="acts">
        <el-button type="primary" plain @click="$router.push('/admin/users')">去用户管理查看</el-button>
      </div>
    </section>
  </div>
</template>

<style scoped>
.imp-page { max-width: 1080px; margin: 0 auto; padding: 4px 2px 40px; }
.hd { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 16px; }
.hd h1 { margin: 0; font-size: 22px; }
.hd .sub { margin: 6px 0 0; font-size: 13.5px; color: #64748b; }

.card { background: #fff; border: 1px solid #eef2f7; border-radius: 14px; padding: 16px 18px; margin-bottom: 14px; }
.steps { display: flex; gap: 8px; margin-bottom: 14px; }
.step { font-size: 12.5px; color: #94a3b8; background: #f1f5f9; border-radius: 999px; padding: 4px 12px; }
.step.on { color: #2563eb; background: #eef2ff; font-weight: 600; }

.row { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.row + .row { margin-top: 12px; }
.file-btn { position: relative; display: inline-flex; align-items: center; padding: 8px 16px; border-radius: 9px; background: #2563eb; color: #fff; font-size: 13.5px; cursor: pointer; }
.file-btn input { position: absolute; inset: 0; opacity: 0; cursor: pointer; }
.file-name { font-size: 13px; color: #334155; }
.file-cnt { font-size: 12.5px; color: #2563eb; background: #eef2ff; border-radius: 6px; padding: 2px 8px; }
.opts .lab { font-size: 13px; color: #475569; }
.opts .hint { font-size: 12px; color: #94a3b8; }

.p-hd { font-size: 15px; font-weight: 600; color: #1e293b; margin-bottom: 12px; display: flex; align-items: baseline; gap: 10px; }
.p-sub { font-size: 12.5px; font-weight: 400; color: #94a3b8; }
.acts { display: flex; gap: 10px; margin-top: 14px; }

:deep(html.dark) .card { background: #1b1b2e; border-color: #2c2c44; }
:deep(html.dark) .p-hd { color: #e5e7f0; }
:deep(html.dark) .step { background: #26263e; color: #8b8da6; }
:deep(html.dark) .step.on { background: #2a2a46; color: #a9c2ff; }
:deep(html.dark) .file-name { color: #d5d7e5; }
</style>
