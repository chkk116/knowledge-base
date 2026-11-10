<script setup>
/**
 * 空间邀请链接落地页。
 *
 * 路由守卫会先要求登录并带 redirect 回来，所以这里拿到的必定是真实账号；
 * 后端兑换时才把该账号加为空间 READ 成员，链接本身不带任何权限。
 */
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { previewSpaceInvite, redeemSpaceInvite } from '../api/docShare'

const route = useRoute()
const router = useRouter()
const token = String(route.params.token || '')
const info = ref(null)
const loading = ref(true)
const joining = ref(false)
const error = ref('')

const fmt = (s) => String(s || '').slice(0, 16).replace('T', ' ')

onMounted(async () => {
  try {
    const { data } = await previewSpaceInvite(token)
    info.value = data
  } catch (e) {
    error.value = e?.response?.data?.error || '邀请链接无效或已失效'
  } finally {
    loading.value = false
  }
})

async function join() {
  joining.value = true
  try {
    const { data } = await redeemSpaceInvite(token)
    ElMessage.success(data?.alreadyMember ? '你已经是该空间成员' : '已加入空间')
    router.replace('/doc-shares/' + data.spaceId)
  } catch (e) {
    const msg = e?.response?.data?.error || '加入失败，链接可能已失效'
    ElMessage.error(msg)
    error.value = msg
    info.value = null
  } finally {
    joining.value = false
  }
}
</script>

<template>
  <div class="join-page">
    <div class="join-card">
      <div class="join-tag">团队空间邀请</div>

      <div v-if="loading" class="join-tip">正在核验邀请链接…</div>

      <template v-else-if="info">
        <h2>{{ info.spaceName }}</h2>
        <p class="join-tip">{{ info.creatorName }} 邀请你加入这个团队空间。加入后可查看空间内的共享文档。</p>
        <div class="join-meta">
          <span>有效期至 {{ fmt(info.expiresAt) }}</span>
          <span v-if="info.maxUses">名额 {{ info.usedCount }} / {{ info.maxUses }}</span>
          <span v-if="info.alreadyMember" class="joined">你已是成员</span>
        </div>
        <el-button v-if="!info.alreadyMember" type="primary" :loading="joining" @click="join">加入空间</el-button>
        <el-button v-else type="primary" @click="router.replace('/doc-shares/' + info.spaceId)">进入空间</el-button>
        <p class="join-note">通过链接加入的权限固定为「只读」；需要编辑权限请联系空间管理员。</p>
      </template>

      <div v-else>
        <h2>链接无法使用</h2>
        <p class="join-tip">{{ error }}</p>
        <el-button @click="router.replace('/')">返回首页</el-button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.join-page { min-height: 60vh; display: flex; align-items: center; justify-content: center; padding: 24px; }
.join-card { width: 100%; max-width: 460px; background: #fff; border: 1px solid #ebeef5; border-radius: 14px; padding: 28px; box-shadow: 0 6px 24px rgba(15, 23, 42, .06); }
.join-tag { display: inline-block; font-size: 12px; color: #4f46e5; background: #eef2ff; border-radius: 6px; padding: 3px 10px; margin-bottom: 16px; }
.join-card h2 { margin: 0 0 10px; font-size: 22px; color: #1e293b; }
.join-tip { color: #64748b; font-size: 14px; line-height: 1.7; margin: 0 0 16px; }
.join-meta { display: flex; flex-wrap: wrap; gap: 10px; font-size: 12px; color: #94a3b8; margin-bottom: 18px; }
.join-meta .joined { color: #15803d; }
.join-note { font-size: 12px; color: #94a3b8; margin: 14px 0 0; line-height: 1.6; }
:deep(html.dark) .join-card { background: #1f2133; border-color: #2f2f4a; }
:deep(html.dark) .join-card h2 { color: #e5e7f3; }
</style>
