<template>
  <div class="sys">
    <!-- Redis 运行状态 -->
    <el-card shadow="never">
      <template #header>
        <div class="hd">
          <span>Redis 状态</span>
          <el-tag v-if="redis.connected" type="success" size="small">已连接</el-tag>
          <el-tag v-else-if="redis.backend === 'memory'" type="warning" size="small">内存兜底</el-tag>
          <el-tag v-else type="danger" size="small">未连接</el-tag>
          <el-button size="small" style="margin-left: auto" :loading="loadingRedis" @click="loadRedis">刷新</el-button>
        </div>
      </template>

      <el-alert type="info" :closable="false" show-icon style="margin-bottom: 16px">
        Redis 只用于「限流 / 第三方登录 state / 练习判分会话」。
        <b>它挂了不会影响登录和使用</b> —— 会自动降级成内存存储，只是重启后计数与会话不保留、多实例不共享。
      </el-alert>

      <el-descriptions v-loading="loadingRedis" :column="3" border>
        <el-descriptions-item label="当前后端">
          <el-tag :type="redis.backend === 'redis' ? 'success' : 'warning'" size="small">
            {{ redis.backend === 'redis' ? 'Redis' : '内存（降级）' }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="版本">{{ redis.version || '—' }}</el-descriptions-item>
        <el-descriptions-item label="运行天数">{{ redis.uptimeDays ?? '—' }}</el-descriptions-item>
        <el-descriptions-item label="已用内存">{{ redis.usedMemory || '—' }}</el-descriptions-item>
        <el-descriptions-item label="峰值内存">{{ redis.peakMemory || '—' }}</el-descriptions-item>
        <el-descriptions-item label="键总数">{{ redis.dbSize ?? '—' }}</el-descriptions-item>
      </el-descriptions>

      <el-alert v-if="redis.error" type="error" :closable="false" show-icon style="margin-top: 12px"
                :title="redis.error" />
      <el-alert v-if="redis.message" type="warning" :closable="false" show-icon style="margin-top: 12px"
                :title="redis.message" />

      <template v-if="redis.keys">
        <div class="sub-title">键分布</div>
        <el-row :gutter="12">
          <el-col :span="6" v-for="k in keyCards" :key="k.name">
            <div class="kpi">
              <div class="kpi-num">{{ k.value }}</div>
              <div class="kpi-lbl">{{ k.name }}</div>
            </div>
          </el-col>
        </el-row>
        <p class="hint">
          限流键带 TTL（窗口到期自动消失）；登录 state 10 分钟过期；练习会话 2 小时过期。
          所以这些数字是「当前活跃」的数量，不是历史累计。
        </p>
      </template>
      <p v-else-if="redis.keysNote" class="hint">{{ redis.keysNote }}</p>
    </el-card>

    <!-- 第三方登录连通性 -->
    <el-card shadow="never" style="margin-top: 16px">
      <template #header>
        <div class="hd">
          <span>第三方登录连通性</span>
          <el-button size="small" style="margin-left: auto" :loading="diagLoading" @click="runDiag">开始诊断</el-button>
        </div>
      </template>
      <p class="tip">
        用<b>服务器自己的网络</b>去访问各家接口（不是你的浏览器）。登录报「超时/失败」时先点这里，
        能立刻分清是网络不通、还是应用侧的问题。
      </p>
      <el-table v-if="diagRows.length" :data="diagRows" size="small" border>
        <el-table-column prop="name" label="接口" width="220" />
        <el-table-column prop="url" label="地址" show-overflow-tooltip />
        <el-table-column label="结果" width="100">
          <template #default="{ row }">
            <el-tag :type="row.ok ? 'success' : 'danger'" size="small">{{ row.ok ? '可达' : '失败' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="detail" label="详情" width="220" />
      </el-table>
      <el-empty v-else description="点右上角「开始诊断」" :image-size="60" />
    </el-card>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../../api/request'
import { errMsg } from '../../utils/errMsg'

const redis = ref({ backend: 'memory', connected: false })
const loadingRedis = ref(false)
const diagLoading = ref(false)
const diagRows = ref([])

const keyCards = computed(() => {
  const k = redis.value.keys || {}
  return [
    { name: '限流（rl:*）', value: k.rateLimit ?? 0 },
    { name: '登录 state（sso:state:*）', value: k.loginState ?? 0 },
    { name: '练习会话（practice:session:*）', value: k.practiceSession ?? 0 },
    { name: '其它', value: k.other ?? 0 }
  ]
})

const LABELS = {
  github_api: 'GitHub · 用户信息',
  github_oauth: 'GitHub · 换 token',
  gitee_api: 'Gitee · 用户信息',
  gitee_oauth: 'Gitee · 换 token',
  wechat_api: '微信 · 接口',
  wechat_open: '微信 · 授权页',
  qq_graph: 'QQ · 开放接口'
}

async function loadRedis() {
  loadingRedis.value = true
  try {
    const { data } = await api.get('/admin/system/redis')
    redis.value = data || {}
  } catch (e) {
    ElMessage.error(errMsg(e, '读取 Redis 状态失败'))
  } finally {
    loadingRedis.value = false
  }
}

async function runDiag() {
  diagLoading.value = true
  diagRows.value = []
  try {
    const { data } = await api.get('/admin/sso/diag', { params: { target: 'all' } })
    diagRows.value = Object.entries(data || {}).map(([key, v]) => ({
      name: LABELS[key] || key,
      url: v.url || '',
      ok: !!v.ok,
      detail: v.ok ? `HTTP ${v.status} · ${v.millis}ms` : (v.error || '失败')
    }))
  } catch (e) {
    ElMessage.error(errMsg(e, '诊断失败'))
  } finally {
    diagLoading.value = false
  }
}

onMounted(loadRedis)
</script>

<style scoped>
.sys { max-width: 1100px; }
.hd { display: flex; align-items: center; gap: 10px; font-weight: 600; }
.tip { color: #6b7280; font-size: 13px; line-height: 1.8; margin: 0 0 14px; }
.hint { color: #9aa0ac; font-size: 12px; margin-top: 10px; line-height: 1.7; }
.sub-title { font-size: 13px; font-weight: 600; color: #4b5563; margin: 18px 0 10px; }
.kpi {
  background: #f7f8fa; border-radius: 10px; padding: 14px 12px; text-align: center;
}
.kpi-num { font-size: 20px; font-weight: 700; color: #1d2129; font-variant-numeric: tabular-nums; }
.kpi-lbl { font-size: 12px; color: #86909c; margin-top: 2px; }
html.dark .kpi { background: #26272c; }
html.dark .kpi-num { color: #e5e6eb; }
</style>
