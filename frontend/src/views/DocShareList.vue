<script setup>
import { ref, onMounted, computed } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listMyShares, createShare, deleteShare, getStorage, searchShareUsers,
  getMyShareQuota, submitCreateRequest, listMyCreateRequests } from '../api/docShare'
import { useAuthStore } from '../store/auth'

const router = useRouter()
const auth = useAuthStore()
const shares = ref([])
const loading = ref(false)
const dialog = ref(false)
const saving = ref(false)
const form = ref({ name: '', description: '', color: 'indigo', members: [], memberPermission: 'WRITE' })

// ---- 主题色板（创建时可选；未选/旧数据按 id 兜底） ----
const PALETTE = [
  { key: 'indigo', grad: 'linear-gradient(135deg,#818cf8,#4f46e5)' },
  { key: 'blue',   grad: 'linear-gradient(135deg,#60a5fa,#2563eb)' },
  { key: 'teal',   grad: 'linear-gradient(135deg,#2dd4bf,#0d9488)' },
  { key: 'green',  grad: 'linear-gradient(135deg,#4ade80,#16a34a)' },
  { key: 'amber',  grad: 'linear-gradient(135deg,#fbbf24,#d97706)' },
  { key: 'rose',   grad: 'linear-gradient(135deg,#fb7185,#e11d48)' },
  { key: 'violet', grad: 'linear-gradient(135deg,#a78bfa,#7c3aed)' },
  { key: 'slate',  grad: 'linear-gradient(135deg,#94a3b8,#475569)' },
]
const colorOf = (s) => PALETTE.find(p => p.key === s.color) || PALETTE[(s.id || 0) % PALETTE.length]

// ---- 选人（远程搜索） ----
const userOptions = ref([])
const userLoading = ref(false)
const searchUsers = async (kw) => {
  userLoading.value = true
  try {
    const { data } = await searchShareUsers(kw || '')
    // 保留已选中但不在当前搜索结果里的人，避免 tag 闪没
    const chosen = userOptions.value.filter(o => form.value.members.includes(o.id))
    const merged = [...(data || [])]
    for (const c of chosen) if (!merged.some(m => m.id === c.id)) merged.push(c)
    userOptions.value = merged
  } catch { /* 静默 */ } finally { userLoading.value = false }
}

// 个人存储用量（有效配额 = 管理员分配或默认 200MB）
const storage = ref({ used: 0, quota: 200 * 1024 * 1024 })
const storagePercent = computed(() => Math.min(100, Math.round((storage.value.used / storage.value.quota) * 100)))

// 个人空间 / 团队空间 两栏；团队空间再细分「我创建的」与「我加入的」
const personalShares = computed(() => shares.value.filter(s => s.type === 'PERSONAL'))
const teamShares = computed(() => shares.value.filter(s => s.type !== 'PERSONAL'))
const createdShares = computed(() => teamShares.value.filter(s => s.myPermission === 'OWNER'))
const joinedShares = computed(() => teamShares.value.filter(s => s.myPermission !== 'OWNER'))
const storageText = computed(() => `我的用量 ${formatSize(storage.value.used)} / ${formatSize(storage.value.quota)}`)

// 创建上限配额：有 SHARE_CREATE 权限的用户最多拥有 teamLimit 个团队空间，超出只能走申请
const myQuota = ref({ teamLimit: 2, ownedTeamCount: 0, canCreate: true })
const quotaText = computed(() => `已建团队空间 ${myQuota.value.ownedTeamCount} / 上限 ${myQuota.value.teamLimit}`)
const loadQuota = async () => {
  if (!auth.hasPerm('SHARE_CREATE')) return
  try { const { data } = await getMyShareQuota(); if (data) myQuota.value = data } catch { /* 静默 */ }
}

function formatSize(n) {
  if (n < 1024) return n + ' B'
  const u = ['KB', 'MB', 'GB', 'TB']; let i = -1, v = n
  do { v /= 1024; i++ } while (v >= 1024 && i < u.length - 1)
  return v.toFixed(2) + ' ' + u[i]
}

const avatarBg = ['linear-gradient(135deg,#fda4af,#e11d48)', 'linear-gradient(135deg,#93c5fd,#2563eb)',
  'linear-gradient(135deg,#86efac,#16a34a)', 'linear-gradient(135deg,#fcd34d,#d97706)',
  'linear-gradient(135deg,#c4b5fd,#7c3aed)', 'linear-gradient(135deg,#5eead4,#0d9488)']
const avatarStyle = (m, idx) => m.avatar
  ? { backgroundImage: `url(${m.avatar})`, backgroundSize: 'cover' }
  : { background: avatarBg[(m.userId || idx) % avatarBg.length] }
const initial = (m) => (m.displayName || '?').slice(0, 1).toUpperCase()

// 头像组：超过 MAX_AVATARS 个折叠为 “+N”，hover 展开被折叠的成员
// 注意：collapse-avatars 必须开启，否则 max-collapse-avatars / collapse-avatars-tooltip 无效
const MAX_AVATARS = 5
const AVATAR_POPPER = 'share-avatars-popper'
// 无头像时的渐变底色（el-avatar 有 src 时由 img 覆盖，无需 backgroundImage）
const avatarBgStyle = (m, idx) => ({ background: avatarBg[(m.userId || idx) % avatarBg.length], color: '#fff' })

const load = async () => {
  loading.value = true
  try {
    const [{ data: list }, { data: st }] = await Promise.all([listMyShares(), getStorage()])
    shares.value = list || []
    if (st) storage.value = st
  } catch (e) {
    ElMessage.error('加载失败')
  } finally {
    loading.value = false
  }
  loadQuota()
}

const openCreate = () => {
  // 已达创建上限：引导走申请流程，而非直接创建
  if (auth.hasPerm('SHARE_CREATE') && !myQuota.value.canCreate) {
    openApply()
    return
  }
  form.value = { name: '', description: '', color: 'indigo', members: [], memberPermission: 'WRITE' }
  userOptions.value = []
  searchUsers('')
  dialog.value = true
}

// ---- 创建空间申请（已达上限时） ----
const applyDialog = ref(false)
const applyForm = ref({ requestedName: '', requestedDescription: '', requestedColor: 'indigo', reason: '' })
const applySaving = ref(false)
const applyList = ref([])
const openApply = () => {
  applyForm.value = { requestedName: '', requestedDescription: '', requestedColor: 'indigo', reason: '' }
  loadMyApply()
  applyDialog.value = true
}
const loadMyApply = async () => {
  try { const { data } = await listMyCreateRequests(); applyList.value = data || [] } catch { applyList.value = [] }
}
const submitApply = async () => {
  if (!applyForm.value.requestedName.trim()) {
    ElMessage.warning('请填写拟创建的空间名称')
    return
  }
  applySaving.value = true
  try {
    await submitCreateRequest({
      requestedName: applyForm.value.requestedName,
      requestedDescription: applyForm.value.requestedDescription,
      requestedColor: applyForm.value.requestedColor,
      reason: applyForm.value.reason,
    })
    ElMessage.success('创建申请已提交，等待管理员审批')
    applyDialog.value = false
    await loadMyApply()
  } catch (e) {
    ElMessage.error(e.response?.data?.error || '提交失败')
  } finally {
    applySaving.value = false
  }
}

// 团队空间用量百分比（后端 listMine 已注入 used）；不限容量（capacity=null）返回 -1
const spacePercent = (s) => {
  if (s.type === 'PERSONAL' || s.capacity == null) return -1
  const used = s.used || 0
  return Math.min(100, Math.round((used / s.capacity) * 100))
}
const spaceUsageText = (s) => {
  if (s.type === 'PERSONAL') return ''
  const used = s.used || 0
  return s.capacity != null
    ? `${formatSize(used)} / ${formatSize(s.capacity)}`
    : `${formatSize(used)} / 不限`
}

const save = async () => {
  if (!form.value.name.trim()) {
    ElMessage.warning('请输入空间名称')
    return
  }
  saving.value = true
  try {
    // 新建空间容量固定 2GB（其他容量需创建后走扩容审批）
    const capacity = 2 * 1024 * 1024 * 1024
    await createShare({
      name: form.value.name,
      description: form.value.description,
      color: form.value.color,
      members: form.value.members,
      memberPermission: form.value.memberPermission,
      capacity,
    })
    ElMessage.success('已创建')
    dialog.value = false
    await load()
  } catch (e) {
    ElMessage.error(e.response?.data?.error || '创建失败')
  } finally {
    saving.value = false
  }
}

const enter = (id) => router.push('/doc-shares/' + id)

const remove = async (row) => {
  try {
    await ElMessageBox.confirm('确定删除「' + row.name + '」？空间内所有文档将一并删除。', '提示', { type: 'warning' })
  } catch { return }
  try {
    await deleteShare(row.id)
    ElMessage.success('已删除')
    await load()
  } catch (e) {
    ElMessage.error(e.response?.data?.error || '删除失败')
  }
}

onMounted(load)
</script>

<template>
  <div>
    <div class="bar">
      <h3 class="title">共享文档</h3>
      <span class="spacer" />
      <div class="storage" :title="'已用 ' + formatSize(storage.used) + ' / 共 ' + formatSize(storage.quota)">
        <el-progress :percentage="storagePercent" :stroke-width="8" :show-text="false" :color="storagePercent > 90 ? '#ef4444' : '#6366f1'" style="width: 120px" />
        <span class="storage-text">{{ storageText }}</span>
      </div>
      <el-button type="primary" :disabled="!auth.hasPerm('SHARE_CREATE')" @click="openCreate"
                 :title="auth.hasPerm('SHARE_CREATE') ? (myQuota.canCreate ? '' : '已达创建上限，点击提交创建申请') : '需要「创建团队空间」权限，请联系系统管理员开通'">
        {{ !auth.hasPerm('SHARE_CREATE') ? '暂无创建权限' : (myQuota.canCreate ? '新建共享空间' : '申请创建空间') }}
      </el-button>
    </div>

    <div class="section">
      <div class="section-head">
        <span class="sec-title">个人空间</span>
        <span class="sec-desc">仅你自己可见，自动创建</span>
      </div>
      <el-row :gutter="16" v-loading="loading">
        <el-col :xs="24" :sm="12" :md="8" :lg="6" v-for="s in personalShares" :key="s.id" class="card-col">
          <el-card shadow="hover" class="share-card personal" @click="enter(s.id)">
            <div class="card-head">
              <div class="space-icon" :style="{ background: colorOf(s).grad }"><el-icon size="18" color="#fff"><User /></el-icon></div>
              <span class="name">{{ s.name }}</span>
            </div>
            <div class="desc">{{ s.description || '暂无描述' }}</div>
            <div class="cap-row" @click.stop>
              <el-progress :percentage="storagePercent" :show-text="false" :stroke-width="6"
                           :color="storagePercent > 90 ? '#ef4444' : '#6366f1'" style="flex: 1" />
              <span class="cap-text">{{ storageText }}</span>
            </div>
            <div class="ops" @click.stop>
              <el-button link type="primary" @click="enter(s.id)">进入</el-button>
            </div>
          </el-card>
        </el-col>
      </el-row>
      <el-empty v-if="!loading && personalShares.length === 0" description="暂无个人空间" :image-size="60" />
    </div>

    <div class="section">
      <div class="section-head">
        <span class="sec-title">团队空间</span>
        <span class="sec-desc">可邀请成员实时协同</span>
      </div>

      <!-- 我创建的团队空间 -->
      <div class="subsection">
        <div class="sub-head">
          <span class="sub-title">我创建的</span>
          <span class="sub-count">{{ createdShares.length }}</span>
        </div>
        <el-row :gutter="16" v-loading="loading">
          <el-col :xs="24" :sm="12" :md="8" :lg="6" v-for="s in createdShares" :key="s.id" class="card-col">
            <el-card shadow="hover" class="share-card" @click="enter(s.id)">
              <div class="card-head">
                <div class="space-icon" :style="{ background: colorOf(s).grad }"><el-icon size="18" color="#fff"><Folder /></el-icon></div>
                <span class="name">{{ s.name }}</span>
              </div>
              <div class="desc">{{ s.description || '暂无描述' }}</div>
              <div class="cap-row" @click.stop>
                <el-progress :percentage="spacePercent(s) < 0 ? 100 : spacePercent(s)"
                             :show-text="false" :stroke-width="6"
                             :color="spacePercent(s) > 90 ? '#ef4444' : (spacePercent(s) < 0 ? '#cbd5e1' : '#6366f1')"
                             style="flex: 1" />
                <span class="cap-text">{{ spaceUsageText(s) }}</span>
              </div>
              <div class="card-foot">
                <div class="avatars">
                  <el-avatar-group collapse-avatars collapse-avatars-tooltip :max-collapse-avatars="MAX_AVATARS"
                                   :size="24" collapse-class="collapse-ava" :popper-class="AVATAR_POPPER">
                    <el-avatar v-for="(m, i) in (s.members || [])" :key="m.userId" size="small" fit="cover"
                               :src="m.avatar || undefined" :style="avatarBgStyle(m, i)" :title="m.displayName">{{ m.avatar ? '' : initial(m) }}</el-avatar>
                  </el-avatar-group>
                  <span v-if="(s.memberCount || 0) > (s.members || []).length" class="more">+{{ s.memberCount - (s.members || []).length }}</span>
                  <span class="perm" :class="'perm-' + (s.myPermission || 'READ').toLowerCase()">
                    {{ s.myPermission === 'OWNER' ? '所有者' : s.myPermission === 'ADMIN' ? '管理员' : s.myPermission === 'WRITE' ? '可编辑' : '只读' }}
                  </span>
                </div>
                <div class="ops" @click.stop>
                  <el-button link type="primary" @click="enter(s.id)">进入</el-button>
                  <el-button link type="danger" @click="remove(s)">删除</el-button>
                </div>
              </div>
            </el-card>
          </el-col>
        </el-row>
        <el-empty v-if="!loading && createdShares.length === 0" description="你还没有创建团队空间" :image-size="60" />
      </div>

      <!-- 我加入的团队空间 -->
      <div class="subsection">
        <div class="sub-head">
          <span class="sub-title">我加入的</span>
          <span class="sub-count">{{ joinedShares.length }}</span>
        </div>
        <el-row :gutter="16" v-loading="loading">
          <el-col :xs="24" :sm="12" :md="8" :lg="6" v-for="s in joinedShares" :key="s.id" class="card-col">
            <el-card shadow="hover" class="share-card" @click="enter(s.id)">
              <div class="card-head">
                <div class="space-icon" :style="{ background: colorOf(s).grad }"><el-icon size="18" color="#fff"><Folder /></el-icon></div>
                <span class="name">{{ s.name }}</span>
              </div>
              <div class="desc">{{ s.description || '暂无描述' }}</div>
              <div class="cap-row" @click.stop>
                <el-progress :percentage="spacePercent(s) < 0 ? 100 : spacePercent(s)"
                             :show-text="false" :stroke-width="6"
                             :color="spacePercent(s) > 90 ? '#ef4444' : (spacePercent(s) < 0 ? '#cbd5e1' : '#6366f1')"
                             style="flex: 1" />
                <span class="cap-text">{{ spaceUsageText(s) }}</span>
              </div>
              <div class="card-foot">
                <div class="avatars">
                  <el-avatar-group collapse-avatars collapse-avatars-tooltip :max-collapse-avatars="MAX_AVATARS"
                                   :size="24" collapse-class="collapse-ava" :popper-class="AVATAR_POPPER">
                    <el-avatar v-for="(m, i) in (s.members || [])" :key="m.userId" size="small" fit="cover"
                               :src="m.avatar || undefined" :style="avatarBgStyle(m, i)" :title="m.displayName">{{ m.avatar ? '' : initial(m) }}</el-avatar>
                  </el-avatar-group>
                  <span v-if="(s.memberCount || 0) > (s.members || []).length" class="more">+{{ s.memberCount - (s.members || []).length }}</span>
                  <span class="perm" :class="'perm-' + (s.myPermission || 'READ').toLowerCase()">
                    {{ s.myPermission === 'OWNER' ? '所有者' : s.myPermission === 'ADMIN' ? '管理员' : s.myPermission === 'WRITE' ? '可编辑' : '只读' }}
                  </span>
                </div>
                <div class="ops" @click.stop>
                  <el-button v-if="s.myPermission === 'ADMIN' || s.myPermission === 'WRITE'" link type="primary" @click="enter(s.id)">进入</el-button>
                  <el-button v-if="!(s.myPermission === 'ADMIN' || s.myPermission === 'WRITE')" link type="primary" @click="enter(s.id)">查看</el-button>
                </div>
              </div>
            </el-card>
          </el-col>
        </el-row>
        <el-empty v-if="!loading && joinedShares.length === 0" description="还没有加入他人的团队空间" :image-size="60" />
      </div>

      <el-empty v-if="!loading && teamShares.length === 0" description="暂无团队空间，点击右上角新建" />
    </div>

    <el-dialog v-model="dialog" width="620px" class="create-dialog" :close-on-click-modal="false">
      <template #header>
        <div class="dlg-head">
          <div class="space-icon lg" :style="{ background: PALETTE.find(p => p.key === form.color)?.grad }">
            <el-icon size="22" color="#fff"><Folder /></el-icon>
          </div>
          <div>
            <div class="dlg-title">新建团队空间</div>
            <div class="dlg-sub">邀请伙伴一起实时协同编辑，光标与内容实时同步</div>
          </div>
        </div>
      </template>

      <el-form label-position="top" class="create-form">
        <el-form-item label="空间名称">
          <el-input v-model="form.name" placeholder="如：技术方案评审、产品需求库" maxlength="120" show-word-limit size="large" />
        </el-form-item>
        <el-form-item label="空间描述">
          <el-input v-model="form.description" type="textarea" :rows="2" placeholder="简要说明空间用途（选填）" maxlength="500" show-word-limit />
        </el-form-item>
        <el-form-item label="主题色">
          <div class="palette">
            <div v-for="p in PALETTE" :key="p.key" class="swatch" :class="{ active: form.color === p.key }" :style="{ background: p.grad }" @click="form.color = p.key">
              <el-icon v-if="form.color === p.key" size="12" color="#fff"><Check /></el-icon>
            </div>
          </div>
        </el-form-item>
        <el-form-item label="空间容量">
          <span class="cap-fixed">默认 2GB</span>
          <span class="hint">空间容量固定为 2GB；如需更大容量，创建后请在空间内「申请扩容」，由系统管理员审批生效</span>
        </el-form-item>
        <el-form-item label="添加成员">
          <el-select v-model="form.members" multiple filterable remote :remote-method="searchUsers" :loading="userLoading"
                     placeholder="搜索用户名或昵称邀请成员（选填）" style="width: 100%" size="large" no-data-text="未找到用户">
            <el-option v-for="u in userOptions" :key="u.id" :value="u.id" :label="u.displayName + '（' + u.username + '）'">
              <div class="user-option">
                <span class="opt-avatar" :style="avatarStyle({ ...u, userId: u.id })">{{ u.avatar ? '' : initial(u) }}</span>
                <span class="opt-name">{{ u.displayName }}</span>
                <span class="opt-username">{{ u.username }}</span>
              </div>
            </el-option>
          </el-select>
        </el-form-item>
        <el-form-item v-if="form.members.length" label="新成员权限">
          <el-radio-group v-model="form.memberPermission">
            <el-radio-button value="WRITE">可编辑</el-radio-button>
            <el-radio-button value="READ">只读</el-radio-button>
            <el-radio-button value="ADMIN">可管理</el-radio-button>
          </el-radio-group>
          <span class="perm-hint">对所有新成员统一生效，创建后可在成员管理中单独调整</span>
        </el-form-item>
        <div class="quota-hint">
          <el-icon size="13" color="#94a3b8"><InfoFilled /></el-icon>
          团队空间默认容量 2GB，空间不足时如需扩容，请联系系统管理员
        </div>
      </el-form>

      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">创建空间</el-button>
      </template>
    </el-dialog>

    <!-- 创建空间申请（已达上限时） -->
    <el-dialog v-model="applyDialog" width="620px" :close-on-click-modal="false" title="申请创建团队空间">
      <el-alert type="info" :closable="false" show-icon
                :title="'您已创建 ' + myQuota.ownedTeamCount + ' 个团队空间（上限 ' + myQuota.teamLimit + ' 个），超过上限需管理员审批通过后才会创建'"
                style="margin-bottom: 16px" />
      <el-form label-position="top" class="create-form">
        <el-form-item label="拟创建空间名称">
          <el-input v-model="applyForm.requestedName" placeholder="如：技术方案评审、产品需求库" maxlength="120" show-word-limit size="large" />
        </el-form-item>
        <el-form-item label="空间描述">
          <el-input v-model="applyForm.requestedDescription" type="textarea" :rows="2" placeholder="简要说明空间用途（选填）" maxlength="500" show-word-limit />
        </el-form-item>
        <el-form-item label="主题色">
          <div class="palette">
            <div v-for="p in PALETTE" :key="p.key" class="swatch" :class="{ active: applyForm.requestedColor === p.key }" :style="{ background: p.grad }" @click="applyForm.requestedColor = p.key">
              <el-icon v-if="applyForm.requestedColor === p.key" size="12" color="#fff"><Check /></el-icon>
            </div>
          </div>
        </el-form-item>
        <el-form-item label="申请理由">
          <el-input v-model="applyForm.reason" type="textarea" :rows="2" placeholder="说明为何需要额外空间（选填）" maxlength="300" show-word-limit />
        </el-form-item>
      </el-form>
      <div v-if="applyList.length" class="apply-hist">
        <div class="ah-title">我的创建申请</div>
        <div v-for="r in applyList" :key="r.id" class="ah-item">
          <span class="ah-name">{{ r.requestedName }}</span>
          <el-tag size="small" :type="r.status === 'APPROVED' ? 'success' : (r.status === 'REJECTED' ? 'danger' : 'warning')">
            {{ r.status === 'APPROVED' ? '已批准' : (r.status === 'REJECTED' ? '已驳回' : '待审批') }}
          </el-tag>
        </div>
      </div>
      <template #footer>
        <el-button @click="applyDialog = false">取消</el-button>
        <el-button type="primary" :loading="applySaving" @click="submitApply">提交申请</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.bar { display: flex; align-items: center; margin-bottom: 16px; }
.title { margin: 0; font-size: 18px; color: #0f172a; }
.spacer { flex: 1; }
.storage { display: flex; align-items: center; gap: 8px; margin-right: 4px; }
.storage-text { font-size: 12.5px; color: #64748b; white-space: nowrap; }
.card-col { margin-bottom: 16px; }
.section { margin-bottom: 8px; }
.section-head { display: flex; align-items: baseline; gap: 10px; margin: 8px 0 14px; }
.sec-title { font-size: 15px; font-weight: 600; color: #0f172a; }
.sec-desc { font-size: 12.5px; color: #94a3b8; }
.subsection { margin-bottom: 18px; }
.sub-head { display: flex; align-items: center; gap: 8px; margin: 4px 0 12px; padding-left: 2px; }
.sub-title { font-size: 13.5px; font-weight: 600; color: #334155; }
.sub-count { font-size: 12px; font-weight: 600; color: #6366f1; background: #eef2ff; border-radius: 10px; padding: 1px 9px; min-width: 20px; text-align: center; }
html.dark .sub-count { background: #2b2c4d; color: #a5b4fc; }
.share-card { cursor: pointer; transition: transform .15s, box-shadow .15s; border-radius: 10px; }
.share-card.personal { border-color: #c7d2fe; background: linear-gradient(180deg, #eef2ff 0%, #ffffff 60%); }
.share-card:hover { transform: translateY(-2px); box-shadow: 0 8px 24px rgba(0,0,0,.08); }
.card-head { display: flex; align-items: center; gap: 10px; margin-bottom: 10px; }
.space-icon { width: 40px; height: 40px; border-radius: 10px; display: inline-flex; align-items: center; justify-content: center; flex-shrink: 0; box-shadow: 0 2px 8px rgba(79,70,229,.25); }
.space-icon.lg { width: 46px; height: 46px; }
.name { font-weight: 600; font-size: 15px; color: #1e293b; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.desc { color: #64748b; font-size: 13px; height: 38px; line-height: 1.45; overflow: hidden; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; margin-bottom: 10px; }
.card-foot { display: flex; align-items: center; justify-content: space-between; }
.avatars { display: flex; align-items: center; min-width: 0; }
/* 头像组：EP 自带重叠间距与跟随主题的描边，这里只收字号 + 折叠块配色 */
.avatars :deep(.el-avatar) { font-size: 11px; font-weight: 600; }
.avatars :deep(.collapse-ava) { --el-avatar-bg-color: #eef2ff; --el-avatar-text-color: #4f46e5; }
html.dark .avatars :deep(.collapse-ava) { --el-avatar-bg-color: #2b2c4d; --el-avatar-text-color: #a5b4fc; }
.more { margin-left: 5px; font-size: 12px; color: #94a3b8; }
.perm { margin-left: 10px; font-size: 11.5px; padding: 1px 8px; border-radius: 10px; }
.perm-owner { background: #fef3c7; color: #b45309; }
.perm-admin { background: #ede9fe; color: #6d28d9; }
.perm-write { background: #dcfce7; color: #15803d; }
.perm-read { background: #f1f5f9; color: #64748b; }
.ops { display: flex; gap: 8px; }
.dlg-head { display: flex; align-items: center; gap: 14px; padding: 4px 0 2px; }
.dlg-title { font-size: 17px; font-weight: 600; color: #0f172a; }
.dlg-sub { font-size: 12.5px; color: #94a3b8; margin-top: 2px; }
.create-form { margin-top: 6px; }
.palette { display: flex; gap: 10px; flex-wrap: wrap; }
.swatch { width: 32px; height: 32px; border-radius: 8px; cursor: pointer; display: inline-flex; align-items: center; justify-content: center; border: 2px solid transparent; transition: transform .12s, border-color .12s; }
.swatch:hover { transform: scale(1.1); }
.swatch.active { border-color: #0f172a; }
.user-option { display: flex; align-items: center; gap: 8px; }
.opt-avatar { width: 24px; height: 24px; border-radius: 50%; display: inline-flex; align-items: center; justify-content: center; font-size: 11px; color: #fff; flex-shrink: 0; background-size: cover; }
.opt-name { font-size: 13.5px; color: #1e293b; }
.opt-username { font-size: 12px; color: #94a3b8; }
.perm-hint { font-size: 12px; color: #94a3b8; margin-left: 12px; }
.hint { color: #94a3b8; font-size: 12px; margin-left: 8px; }
.cap-presets { display: flex; flex-wrap: wrap; gap: 8px; }
.cap-chip { border: 1px solid #e2e8f0; background: #fff; color: #475569; padding: 6px 14px; border-radius: 8px; font-size: 13px; cursor: pointer; transition: all .15s; }
html.dark .cap-chip { background: #1c1c38; border-color: #2e2e52; color: #c7cbf5; }
.cap-chip:hover { border-color: #6366f1; }
.cap-chip.active { background: #eef2ff; border-color: #6366f1; color: #4f46e5; font-weight: 600; }
html.dark .cap-chip.active { background: #2b2c4d; color: #a5b4fc; }
.cap-row { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.cap-text { font-size: 11.5px; color: #94a3b8; white-space: nowrap; }
.quota-hint { display: flex; align-items: center; gap: 5px; font-size: 12.5px; color: #94a3b8; background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 8px; padding: 8px 12px; }
.apply-hist { margin-top: 4px; border-top: 1px solid #eef0f4; padding-top: 10px; }
.ah-title { font-size: 13px; font-weight: 600; color: #334155; margin-bottom: 8px; }
.ah-item { display: flex; align-items: center; justify-content: space-between; padding: 6px 0; }
.ah-name { font-size: 13px; color: #475569; }

/* ===================== 响应式布局（追加在末尾） ===================== */
@media (max-width: 1024px) {
  .bar { flex-wrap: wrap; gap: 8px 10px; }
  .title { font-size: 17px; }
  .storage { flex: 1 1 240px; min-width: 0; }
  .section-head { flex-wrap: wrap; gap: 4px 10px; }
  .sub-head { flex-wrap: wrap; }
  .share-card :deep(.el-card__body) { padding: 14px; }
  .palette { gap: 8px; }
}

@media (max-width: 640px) {
  .bar { align-items: flex-start; gap: 10px; }
  .title { width: 100%; font-size: 16px; }
  .spacer { display: none; }
  .storage { width: 100%; flex: none; order: 3; }
  .storage :deep(.el-progress) { width: 100% !important; }
  .storage-text { font-size: 12px; }
  .share-card :deep(.el-card__body) { padding: 12px; }
  .desc { height: auto; -webkit-line-clamp: 3; margin-bottom: 8px; }
  .card-foot { flex-wrap: wrap; gap: 8px; }
  .avatars { flex-wrap: wrap; }
  .ops { flex-wrap: wrap; }
  .cap-row { flex-wrap: wrap; }
  .perm-hint { margin-left: 0; display: block; }
  .hint { margin-left: 0; display: block; }
  .dlg-head { gap: 10px; }
  .dlg-sub { font-size: 12px; }
  .quota-hint { padding: 8px 10px; font-size: 12px; }
  .ah-name { max-width: 60%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
}
</style>

<!-- 折叠头像 hover 展开始终 teleport 到 body，scoped 选择器够不到，故用 popper-class 全局锚定 -->
<style>
.share-avatars-popper .el-avatar-group__collapse-avatars {
  display: flex; flex-wrap: wrap; gap: 6px; max-width: 216px;
  --el-avatar-group-item-gap: 0;
}
.share-avatars-popper .el-avatar-group__collapse-avatars .el-avatar { margin-left: 0; font-size: 11px; font-weight: 600; }
</style>
