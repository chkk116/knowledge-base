<template>
  <div class="page">
    <el-card shadow="never">
      <template #header>
        <div class="hd">
          <span>企业登录 · LDAP / AD</span>
          <el-tag v-if="cfg.enabled" type="success" size="small">已启用</el-tag>
          <el-tag v-else type="info" size="small">未启用</el-tag>
        </div>
      </template>

      <p class="tip">
        启用后登录页会出现「使用域账号登录」。流程：服务账号先 bind → 按过滤条件搜出用户 DN → 用该 DN + 用户密码再 bind 验证。
        本站不保存域密码，只保存服务账号口令（用于搜索）。
      </p>

      <el-form label-width="130px" style="max-width: 720px">
        <el-form-item label="启用域账号登录"><el-switch v-model="form.enabled" /></el-form-item>
        <el-form-item label="LDAP 地址">
          <el-input v-model="form.url" placeholder="ldap://172.16.0.10:389 或 ldaps://dc.corp.local:636" />
        </el-form-item>
        <el-form-item label="Base DN">
          <el-input v-model="form.baseDn" placeholder="DC=corp,DC=local" />
        </el-form-item>
        <el-form-item label="用户过滤">
          <el-input v-model="form.userFilter" placeholder="(sAMAccountName={0})　AD 用这个；OpenLDAP 用 (uid={0})" />
          <div class="hint">{0} 会被替换成转义后的登录名</div>
        </el-form-item>
        <el-form-item label="服务账号 DN">
          <el-input v-model="form.bindDn" placeholder="CN=svc-kb,OU=Service,DC=corp,DC=local" />
          <div class="hint">只读搜索用；留空表示匿名 bind（多数域控不允许）</div>
        </el-form-item>
        <el-form-item label="服务账号密码">
          <el-input v-model="form.bindPassword" type="password" show-password
                    :placeholder="cfg.hasBindPassword ? '已配置（留空则不改）' : '请输入'" />
        </el-form-item>
        <el-form-item label="显示名属性">
          <el-input v-model="form.attrName" placeholder="displayName（AD）/ cn（OpenLDAP）" />
        </el-form-item>
        <el-form-item label="邮箱属性">
          <el-input v-model="form.attrMail" placeholder="mail" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="saving" @click="save">保存</el-button>
          <el-button :loading="testing" @click="test">测试连接</el-button>
        </el-form-item>
      </el-form>

      <el-alert v-if="testResult" :type="testResult.ok ? 'success' : 'error'" :title="testResult.message"
                :closable="false" show-icon style="max-width: 720px" />
    </el-card>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../../api/request'
import { errMsg } from '../../utils/errMsg'

const cfg = ref({ enabled: false, hasBindPassword: false })
const form = ref({
  enabled: false, url: '', baseDn: '', userFilter: '(sAMAccountName={0})',
  bindDn: '', bindPassword: '', attrName: 'displayName', attrMail: 'mail'
})
const saving = ref(false)
const testing = ref(false)
const testResult = ref(null)

async function load() {
  try {
    const { data } = await api.get('/admin/sso/ldap')
    cfg.value = data || cfg.value
    form.value = {
      enabled: !!cfg.value.enabled,
      url: cfg.value.url || '',
      baseDn: cfg.value.baseDn || '',
      userFilter: cfg.value.userFilter || '(sAMAccountName={0})',
      bindDn: cfg.value.bindDn || '',
      bindPassword: '',
      attrName: cfg.value.attrName || 'displayName',
      attrMail: cfg.value.attrMail || 'mail'
    }
  } catch (e) {
    ElMessage.error(errMsg(e, '读取配置失败'))
  }
}

async function save() {
  saving.value = true
  try {
    const { data } = await api.put('/admin/sso/ldap', { ...form.value, bindPassword: form.value.bindPassword || null })
    cfg.value = data
    form.value.bindPassword = ''
    ElMessage.success('已保存')
  } catch (e) {
    ElMessage.error(errMsg(e, '保存失败'))
  } finally {
    saving.value = false
  }
}

async function test() {
  testing.value = true
  testResult.value = null
  try {
    const { data } = await api.post('/admin/sso/ldap/test', {})
    testResult.value = data
  } catch (e) {
    testResult.value = { ok: false, message: errMsg(e, '测试失败') }
  } finally {
    testing.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.hd { display: flex; align-items: center; gap: 10px; font-weight: 600; }
.tip { color: #6b7280; font-size: 13px; line-height: 1.8; margin: 0 0 18px; }
.hint { color: #9aa0ac; font-size: 12px; margin-top: 4px; }
</style>
