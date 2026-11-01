<template>
  <div class="mail-page">
    <div class="page-head">
      <div class="head-icon"><el-icon :size="22"><Message /></el-icon></div>
      <div class="head-text">
        <h2>邮件设置</h2>
        <p class="sub">配置 SMTP 后，系统可向用户邮箱发送密码重置链接与通知邮件。密码经加密存储，本页不回显明文。</p>
      </div>
    </div>

    <el-card class="cfg" shadow="never">
      <el-form label-width="110px" class="form">
        <el-form-item label="启用邮件发送">
          <el-switch v-model="form.enabled" />
        </el-form-item>
        <el-form-item label="SMTP 主机">
          <el-input v-model="form.host" placeholder="如 smtp.qq.com / smtp.163.com" />
        </el-form-item>
        <el-form-item label="端口">
          <el-input v-model.number="form.port" placeholder="465 / 587" style="max-width:160px" />
        </el-form-item>
        <el-form-item label="加密方式">
          <el-select v-model="form.encryption" style="max-width:200px">
            <el-option label="SSL（465）" value="SSL" />
            <el-option label="TLS（587）" value="TLS" />
            <el-option label="无加密（25）" value="NONE" />
          </el-select>
        </el-form-item>
        <el-form-item label="用户名">
          <el-input v-model="form.username" placeholder="通常为发件邮箱或登录账号" />
        </el-form-item>
        <el-form-item label="密码 / 授权码">
          <el-input v-model="form.password" type="password" show-password
                    :placeholder="pwSet ? '已设置，留空表示不修改' : '邮箱服务商提供的授权码'" />
        </el-form-item>
        <el-form-item label="发件人名称">
          <el-input v-model="form.fromName" placeholder="如 知识库" style="max-width:240px" />
        </el-form-item>
        <el-form-item label="发件人地址">
          <el-input v-model="form.fromAddress" placeholder="留空则使用用户名" style="max-width:280px" />
        </el-form-item>

        <el-form-item>
          <el-button type="primary" :loading="saving" @click="save">保存配置</el-button>
          <el-button :loading="testing" @click="test" :disabled="!testTo">发送测试邮件</el-button>
        </el-form-item>
        <el-form-item label="测试收件人">
          <el-input v-model="testTo" placeholder="填写一个邮箱以验证配置" style="max-width:280px" />
        </el-form-item>

        <el-alert v-if="msg" :type="msgType" :closable="false" show-icon :title="msg" class="result" />
      </el-form>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { Message } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import api from '../../api/request'
import { errMsg } from '../../utils/errmsg'
const saving = ref(false)
const testing = ref(false)
const pwSet = ref(false)
const testTo = ref('')
const msg = ref('')
const msgType = ref('info')

const form = ref({
  enabled: false,
  host: '',
  port: 465,
  encryption: 'SSL',
  username: '',
  password: '',
  fromName: '',
  fromAddress: '',
})

async function load() {
  try {
    const r = await api.get('/admin/mail/config')
    const d = r.data || {}
    form.value.enabled = !!d.enabled
    form.value.host = d.host || ''
    form.value.port = d.port || 465
    form.value.encryption = d.encryption || 'SSL'
    form.value.username = d.username || ''
    form.value.fromName = d.fromName || ''
    form.value.fromAddress = d.fromAddress || ''
    pwSet.value = !!d.passwordSet
  } catch (e) {
    ElMessage.error(errMsg(e, '加载失败'))
  }
}

async function save() {
  saving.value = true
  msg.value = ''
  try {
    await api.put('/admin/mail/config', {
      enabled: form.value.enabled,
      host: form.value.host.trim(),
      port: form.value.port,
      encryption: form.value.encryption,
      username: form.value.username.trim(),
      password: form.value.password,
      fromName: form.value.fromName.trim(),
      fromAddress: form.value.fromAddress.trim(),
    })
    pwSet.value = form.value.password ? true : pwSet.value
    form.value.password = ''
    msg.value = '邮件配置已保存'
    msgType.value = 'success'
    ElMessage.success('已保存')
  } catch (e) {
    msg.value = errMsg(e, '保存失败')
    msgType.value = 'error'
  } finally {
    saving.value = false
  }
}

async function test() {
  if (!testTo.value.trim()) { ElMessage.warning('请先填写测试收件人'); return }
  testing.value = true
  msg.value = ''
  try {
    await api.post('/admin/mail/test', { to: testTo.value.trim() })
    msg.value = '测试邮件已发送，请检查收件箱（含垃圾邮件）'
    msgType.value = 'success'
    ElMessage.success('已发送测试邮件')
  } catch (e) {
    msg.value = errMsg(e, '测试发送失败')
    msgType.value = 'error'
  } finally {
    testing.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.mail-page { max-width: 760px; margin: 0 auto; padding: 24px 16px 40px; }
.page-head { display: flex; gap: 12px; align-items: flex-start; margin-bottom: 16px; }
.head-icon { width: 40px; height: 40px; border-radius: 10px; background: #eef4ff; color: #1677ff;
  display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
.head-text h2 { margin: 0; font-size: 18px; }
.sub { margin: 4px 0 0; font-size: 13px; color: #86909c; line-height: 1.6; }
.form { padding: 4px 8px; }
.result { margin-top: 8px; }
</style>
