<template>
  <div class="mail-page">
    <div class="page-head">
      <div class="head-icon"><el-icon :size="22"><Message /></el-icon></div>
      <div class="head-text">
        <h2>邮件设置</h2>
        <p class="sub">配置 SMTP 后可发送密码重置、邮箱验证与通知邮件；支持手动发送与自定义模板。密码经加密存储，本页不回显明文。</p>
      </div>
    </div>

    <el-tabs v-model="tab" class="mail-tabs">
      <!-- ============ SMTP 配置 ============ -->
      <el-tab-pane label="SMTP 配置" name="smtp">
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
      </el-tab-pane>

      <!-- ============ 手动发送 ============ -->
      <el-tab-pane label="发送邮件" name="send">
        <el-card class="cfg" shadow="never">
          <el-form label-width="90px" class="form">
            <el-form-item label="收件人">
              <el-input v-model="sendForm.to" placeholder="someone@example.com，多个用逗号分隔" />
            </el-form-item>
            <el-form-item label="主题">
              <el-input v-model="sendForm.subject" placeholder="邮件主题" maxlength="120" show-word-limit />
            </el-form-item>
            <el-form-item label="正文">
              <el-input v-model="sendForm.body" type="textarea" :rows="9"
                        placeholder="纯文本正文，换行会原样保留" />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="sending" @click="doSend">发送邮件</el-button>
              <span class="hint">正文按纯文本发送，HTML 会被转义，不会被执行</span>
            </el-form-item>
            <el-alert v-if="sendMsg" :type="sendMsgType" :closable="false" show-icon :title="sendMsg" class="result" />
          </el-form>
        </el-card>
      </el-tab-pane>

      <!-- ============ 邮件模板 ============ -->
      <el-tab-pane label="邮件模板" name="templates">
        <el-card class="cfg" shadow="never">
          <div class="tpl-vars">
            <span class="tv-label">可用变量</span>
            <el-tag v-for="(desc, key) in tplVars" :key="key" size="small" effect="plain" class="tv-tag">
              {{ '{' + key + '}' }}<span class="tv-desc">{{ desc }}</span>
            </el-tag>
          </div>

          <div v-if="tplReady">
            <el-tabs v-model="tplTab" type="border-card" class="inner-tabs">
              <el-tab-pane v-for="t in TPL_META" :key="t.key" :label="t.label" :name="t.key">
                <el-form label-width="70px" class="form">
                  <el-form-item label="主题">
                    <el-input v-model="tpls[t.key].subject" maxlength="120" show-word-limit />
                  </el-form-item>
                  <el-form-item label="正文">
                    <el-input v-model="tpls[t.key].body" type="textarea" :rows="11" />
                  </el-form-item>
                  <el-form-item>
                    <el-button type="primary" :loading="savingTpl" @click="saveTpls">保存模板</el-button>
                    <el-button @click="resetTpl(t.key)">恢复默认</el-button>
                  </el-form-item>
                </el-form>
                <p class="tpl-tip">正文支持 HTML 与上面的变量；外层会自动套统一页脚。</p>
              </el-tab-pane>
            </el-tabs>
          </div>
          <el-skeleton v-else :rows="6" animated />
        </el-card>
      </el-tab-pane>

      <!-- ============ 密码重置 ============ -->
      <el-tab-pane label="密码重置" name="reset">
        <el-card class="cfg" shadow="never">
          <el-alert type="info" :closable="false" show-icon class="mb"
                    title="给用户发送密码重置邮件：链接 30 分钟内有效、仅可使用一次。用户点开链接自己设置新密码，管理员不接触新密码。" />
          <el-form label-width="100px" class="form">
            <el-form-item label="用户名/邮箱">
              <el-input v-model="resetForm.account" placeholder="输入用户名或邮箱" @keyup.enter="doResetMail" />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="resetting" @click="doResetMail">发送重置邮件</el-button>
            </el-form-item>
            <el-alert v-if="resetMsg" :type="resetMsgType" :closable="false" show-icon :title="resetMsg" class="result" />
            <div v-if="resetLink" class="reset-link">
              <div class="rl-t">备用链接（邮件失败时可直接复制给用户）：</div>
              <el-input :model-value="resetLink" readonly>
                <template #append>
                  <el-button @click="copy(resetLink)">复制</el-button>
                </template>
              </el-input>
            </div>
          </el-form>
        </el-card>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { Message } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import api from '../../api/request'
import { errMsg } from '../../utils/errmsg'

const tab = ref('smtp')

/* ---------- SMTP ---------- */
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

/* ---------- 手动发送 ---------- */
const sendForm = ref({ to: '', subject: '', body: '' })
const sending = ref(false)
const sendMsg = ref('')
const sendMsgType = ref('info')

/* ---------- 模板 ---------- */
const TPL_META = [
  { key: 'reset', label: '密码重置' },
  { key: 'verify', label: '邮箱验证码' },
  { key: 'notify', label: '通知抄送' },
]
const tplTab = ref('reset')
const tpls = ref({})
const defaults = ref({})
const tplVars = ref({})
const tplReady = ref(false)
const savingTpl = ref(false)

/* ---------- 密码重置 ---------- */
const resetForm = ref({ account: '' })
const resetting = ref(false)
const resetMsg = ref('')
const resetMsgType = ref('info')
const resetLink = ref('')

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

async function loadTemplates() {
  try {
    const { data } = await api.get('/admin/mail/templates')
    tpls.value = data.templates || {}
    defaults.value = data.defaults || {}
    tplVars.value = data.vars || {}
    // 补齐缺失项，避免模板 tab 渲染时取到 undefined
    for (const t of TPL_META) {
      if (!tpls.value[t.key]) tpls.value[t.key] = { subject: '', body: '' }
    }
    tplReady.value = true
  } catch (e) {
    ElMessage.error(errMsg(e, '模板加载失败'))
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

async function doSend() {
  if (!sendForm.value.to.trim()) { ElMessage.warning('请填写收件人'); return }
  if (!sendForm.value.subject.trim()) { ElMessage.warning('请填写主题'); return }
  if (!sendForm.value.body.trim()) { ElMessage.warning('请填写正文'); return }
  sending.value = true
  sendMsg.value = ''
  try {
    await api.post('/admin/mail/send', {
      to: sendForm.value.to.trim(),
      subject: sendForm.value.subject.trim(),
      body: sendForm.value.body,
    })
    sendMsg.value = '邮件已发送'
    sendMsgType.value = 'success'
    ElMessage.success('已发送')
  } catch (e) {
    sendMsg.value = errMsg(e, '发送失败')
    sendMsgType.value = 'error'
  } finally {
    sending.value = false
  }
}

function resetTpl(key) {
  if (!defaults.value[key]) return
  tpls.value[key] = { ...defaults.value[key] }
  ElMessage.info('已恢复默认，记得点「保存模板」生效')
}

async function saveTpls() {
  savingTpl.value = true
  try {
    await api.put('/admin/mail/templates', tpls.value)
    ElMessage.success('模板已保存')
  } catch (e) {
    ElMessage.error(errMsg(e, '保存失败'))
  } finally {
    savingTpl.value = false
  }
}

async function doResetMail() {
  if (!resetForm.value.account.trim()) { ElMessage.warning('请填写用户名或邮箱'); return }
  resetting.value = true
  resetMsg.value = ''
  resetLink.value = ''
  try {
    const { data } = await api.post('/admin/mail/reset-mail', { account: resetForm.value.account.trim() })
    resetMsg.value = '重置邮件已发送至 ' + (data.email || '用户邮箱')
    resetMsgType.value = 'success'
    resetLink.value = data.link || ''
    ElMessage.success('已发送重置邮件')
  } catch (e) {
    resetMsg.value = errMsg(e, '发送失败')
    resetMsgType.value = 'error'
  } finally {
    resetting.value = false
  }
}

async function copy(text) {
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('已复制')
  } catch (e) {
    ElMessage.warning('复制失败，请手动选择复制')
  }
}

onMounted(() => {
  load()
  loadTemplates()
})
</script>

<style scoped>
.mail-page { max-width: 860px; margin: 0 auto; padding: 24px 16px 40px; }
.page-head { display: flex; gap: 12px; align-items: flex-start; margin-bottom: 12px; }
.head-icon { width: 40px; height: 40px; border-radius: 10px; background: #eef4ff; color: #1677ff;
  display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
.head-text h2 { margin: 0; font-size: 18px; }
.sub { margin: 4px 0 0; font-size: 13px; color: #86909c; line-height: 1.6; }
.form { padding: 4px 8px; }
.result { margin-top: 8px; }
.hint { margin-left: 10px; font-size: 12px; color: #86909c; }
.mb { margin-bottom: 14px; }

.tpl-vars { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; margin: 2px 4px 14px; }
.tv-label { font-size: 12px; color: #86909c; }
.tv-tag { display: inline-flex; align-items: center; gap: 4px; }
.tv-desc { color: #86909c; font-size: 11px; }
.inner-tabs { box-shadow: none; }
.tpl-tip { margin: 0 0 6px; font-size: 12px; color: #86909c; }

.reset-link { margin-top: 14px; }
.rl-t { font-size: 12px; color: #86909c; margin-bottom: 6px; }

@media (max-width: 640px) {
  .mail-page { padding: 16px 10px 32px; }
  .hint { display: block; margin: 6px 0 0; }
}
</style>
