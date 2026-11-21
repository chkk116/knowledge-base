<script setup>
/**
 * 集成中心：可选第三方能力（第一单品：TRTC Chat 离线推送）。
 * 消息本体走自有后端，TRTC 只在 App 离线时触发厂商推送提醒。
 * 未配置 / 未启用时对主流程零影响，私有部署可以完全不接。
 */
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../../api/request'

const loading = ref(false)
const saving = ref(false)
const testing = ref(false)
const cfg = reactive({
  enabled: false,
  sdkAppId: '',
  secretKey: '',
  adminId: 'administrator',
  region: 'console.tim.qq.com',
  configured: false,
})

const load = async () => {
  loading.value = true
  try {
    const { data } = await api.get('/admin/integrations/trtc-chat')
    cfg.enabled = !!data.enabled
    cfg.sdkAppId = data.sdkAppId || ''
    cfg.secretKey = data.secretKey || ''
    cfg.adminId = data.adminId || 'administrator'
    cfg.configured = !!data.configured
  } catch (e) {
    ElMessage.error('加载集成配置失败：' + (e.response ? e.response.status : ''))
  } finally {
    loading.value = false
  }
}

const save = async () => {
  if (!cfg.sdkAppId || !/^\d+$/.test(String(cfg.sdkAppId).trim())) {
    ElMessage.warning('SDKAppID 是控制台分配的一串纯数字')
    return
  }
  if (!cfg.configured && !cfg.secretKey) {
    ElMessage.warning('首次配置必须填写 SecretKey')
    return
  }
  saving.value = true
  try {
    await api.put('/admin/integrations/trtc-chat', {
      enabled: cfg.enabled,
      sdkAppId: cfg.sdkAppId,
      secretKey: cfg.secretKey,
      adminId: cfg.adminId,
    })
    ElMessage.success('已保存')
    await load()
  } catch (e) {
    ElMessage.error(e.response?.data?.error || '保存失败')
  } finally {
    saving.value = false
  }
}

const test = async () => {
  testing.value = true
  try {
    const { data } = await api.post('/admin/integrations/trtc-chat/test')
    ElMessage.success('测试消息已发送，TRTC 账号 ' + data.trtcUserId)
  } catch (e) {
    ElMessage.error(e.response?.data?.error || '测试失败（详见后端日志）')
  } finally {
    testing.value = false
  }
}

onMounted(load)
</script>

<template>
  <div style="max-width: 720px">
    <el-alert type="info" :closable="false" style="margin-bottom: 16px"
              title="TRTC Chat 离线推送（可选集成）"
              description="消息本体永远走本站后端。开启后，App 用户离线时通过腾讯厂商通道收到「你有新私信」的系统通知；未配置或私有部署不启用时完全无影响。" />

    <el-card shadow="never">
      <template #header>
        <div style="display:flex;align-items:center;gap:8px;">
          <b>腾讯云 Chat（TRTC）</b>
          <el-tag size="small" :type="cfg.enabled ? 'success' : 'info'" effect="plain">
            {{ cfg.enabled ? '已启用' : (cfg.configured ? '未启用' : '未配置') }}
          </el-tag>
          <span class="muted" style="margin-left:auto;font-size:12px;">
            免费版：1000 月活 / 每日 1000 条 REST
          </span>
        </div>
      </template>

      <el-form label-width="110px" v-loading="loading">
        <el-form-item label="启用">
          <el-switch v-model="cfg.enabled" />
        </el-form-item>
        <el-form-item label="SDKAppID">
          <el-input v-model="cfg.sdkAppId" placeholder="控制台应用信息页的一串数字" style="max-width:320px" />
        </el-form-item>
        <el-form-item label="SecretKey">
          <el-input v-model="cfg.secretKey" type="password" show-password
                    :placeholder="cfg.configured ? '留空 = 不修改原值' : '控制台「显示密钥」，32 位字符串'"
                    style="max-width:320px" />
        </el-form-item>
        <el-form-item label="管理员账号">
          <el-input v-model="cfg.adminId" placeholder="administrator（默认）" style="max-width:320px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="saving" @click="save">保存</el-button>
          <el-button :loading="testing" :disabled="!cfg.configured" @click="test">测试连接</el-button>
          <span class="muted" style="margin-left:10px;font-size:12px;">
            测试会给当前管理员的 TRTC 账号（kb_{{ $route.params?.id || 'me' }}）发一条自定义消息
          </span>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<style scoped>
.muted { color: #94a3b8; }
</style>
