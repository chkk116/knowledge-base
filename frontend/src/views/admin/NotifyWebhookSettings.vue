<template>
  <div class="page">
    <el-card shadow="never">
      <template #header>
        <div class="hd">
          <span>通知外发（Webhook）</span>
          <el-tag v-if="cfg.enabled" type="success" size="small">已启用</el-tag>
          <el-tag v-else type="info" size="small">未启用</el-tag>
        </div>
      </template>

      <p class="tip">
        站内通知（顶栏红点 + SSE 实时推送）之外，把需要人看的通知再推到群里：
        企业微信群机器人 / 钉钉群机器人 / 自定义 JSON 接收端。
        点赞、订阅刷新这类高频事件不外发。
      </p>

      <el-form label-width="120px" style="max-width: 680px">
        <el-form-item label="启用外发">
          <el-switch v-model="form.enabled" />
        </el-form-item>
        <el-form-item label="通道类型">
          <el-select v-model="form.type" style="width: 220px">
            <el-option value="wecom" label="企业微信群机器人" />
            <el-option value="dingtalk" label="钉钉群机器人" />
            <el-option value="generic" label="自定义 JSON" />
          </el-select>
        </el-form-item>
        <el-form-item label="Webhook 地址">
          <el-input v-model="form.url" :placeholder="cfg.hasUrl ? cfg.urlMasked + '（留空则不改）' : 'https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=...'" />
          <div class="hint">含 access_token / key，属敏感信息：保存后只回显脱敏值，不再回传原文。</div>
        </el-form-item>
        <el-form-item label="加签密钥">
          <el-input v-model="form.secret" type="password" show-password
                    :placeholder="cfg.hasSecret ? '已配置（留空则不改）' : '钉钉机器人可选；企业微信不需要'" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="saving" @click="save">保存</el-button>
          <el-button :loading="testing" @click="test">发送测试消息</el-button>
        </el-form-item>
      </el-form>

      <el-alert v-if="testResult" :type="testResult.ok ? 'success' : 'error'" :closable="false"
                :title="testResult.message" show-icon style="max-width: 680px" />
    </el-card>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../../api/request'
import { errMsg } from '../../utils/errMsg'

const cfg = ref({ enabled: false, type: 'wecom', hasUrl: false, urlMasked: '', hasSecret: false })
const form = ref({ enabled: false, type: 'wecom', url: '', secret: '' })
const saving = ref(false)
const testing = ref(false)
const testResult = ref(null)

async function load() {
  try {
    const { data } = await api.get('/admin/notify/webhook')
    cfg.value = data || cfg.value
    form.value.enabled = !!cfg.value.enabled
    form.value.type = cfg.value.type || 'wecom'
    form.value.url = ''
    form.value.secret = ''
  } catch (e) {
    ElMessage.error(errMsg(e, '读取配置失败'))
  }
}

async function save() {
  saving.value = true
  try {
    const { data } = await api.put('/admin/notify/webhook', {
      enabled: form.value.enabled,
      type: form.value.type,
      url: form.value.url || null,
      secret: form.value.secret || null
    })
    cfg.value = data
    form.value.url = ''
    form.value.secret = ''
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
    const { data } = await api.post('/admin/notify/webhook/test', {})
    testResult.value = data
  } catch (e) {
    testResult.value = { ok: false, message: errMsg(e, '发送失败') }
  } finally {
    testing.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.page { padding: 4px 0; }
.hd { display: flex; align-items: center; gap: 10px; font-weight: 600; }
.tip { color: #6b7280; font-size: 13px; line-height: 1.8; margin: 0 0 18px; }
.hint { color: #9aa0ac; font-size: 12px; margin-top: 4px; }
</style>
