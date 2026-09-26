<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { MoreFilled, Document, Grid, Share, Connection, ArrowDown } from '@element-plus/icons-vue'
import {
  getShare, updateShare, listShareDocs, createShareDoc,
  getShareDoc, updateShareDoc, deleteShareDoc, moveShareDoc,
  importShareDoc, getShareStorage,
  listMyShares, listMembers, addMember, updateMember, removeMember,
  listDocMembers, setDocMember, clearDocMember,
  submitCapacityRequest, listCapacityRequests,
  searchShareUsers,
} from '../api/docShare'
import { useAuthStore } from '../store/auth'
import DocShareEditor from './DocShareEditor.vue'
import { errMsg } from '../utils/errMsg'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const shareId = computed(() => Number(route.params.id))
const myId = computed(() => auth.user?.id)

const share = ref(null)
const shareType = ref('TEAM')   // PERSONAL / TEAM；个人空间不展示成员管理
const docs = ref([])
const members = ref([])
const loading = ref(false)
const shareStorage = ref(null)  // 空间容量 { used, capacity, hasCapacity, ownerQuota, ownerUsed }

const activeDoc = ref(null)
const editorTitle = ref('')
const saving = ref(false)

const memberDialog = ref(false)
const allUsers = ref([])
const memberForm = ref({ userId: '', permission: 'READ' })
const memberLoading = ref(false)
const userLoading = ref(false)

// 文档管理弹窗
const renameDialog = ref(false)
const renameForm = ref({ id: null, title: '' })
const moveDialog = ref(false)
const moveForm = ref({ id: null, targetId: '' })
const moveTargets = ref([])
const moveLoading = ref(false)

const canWrite = computed(() => {
  const p = share.value?.myPermission
  return p === 'OWNER' || p === 'ADMIN' || p === 'WRITE'
})
const canAdmin = computed(() => {
  const p = share.value?.myPermission
  return p === 'OWNER' || p === 'ADMIN'
})
const isOwner = computed(() => share.value?.myPermission === 'OWNER')

/** 可管理某文档：空间管理员、文档级 ADMIN、或文档所有者（创建者） */
const canManageDoc = (d) => canAdmin.value || d.myPerm === 'ADMIN' || d.ownerId === myId.value

/** 当前激活文档是否可编辑：空间级可写 且 文档级未被降权为只读 */
const docCanWrite = computed(() => {
  if (!canWrite.value) return false
  const p = activeDoc.value?.myPerm
  return p === 'OWNER' || p === 'ADMIN' || p === 'WRITE'
})

const loadShare = async () => {
  try {
    const { data } = await getShare(shareId.value)
    share.value = data
    shareType.value = data.type || 'TEAM'
  } catch (e) {
    ElMessage.error(errMsg(e, '加载空间失败'))
    router.push('/doc-shares')
  }
}

const loadDocs = async () => {
  const { data } = await listShareDocs(shareId.value)
  docs.value = data || []
}

const loadMembers = async () => {
  const { data } = await listMembers(shareId.value)
  members.value = data || []
}

const load = async () => {
  loading.value = true
  await loadShare()
  await Promise.all([loadDocs(), loadMembers(), loadStorage()])
  if (!activeDoc.value) {
    // 支持 ?doc=<文档id> 深链：从导入页「打开文档」跳进来时，直接定位到那篇文档；
    // 无深链或文档不存在（已删除/无权限）则回退打开列表第一篇
    const want = Number(route.query.doc)
    const target = (want && docs.value.find(d => d.id === want)) || docs.value[0]
    if (target) selectDoc(target)
  }
  loading.value = false
}

// 空间容量：已用 / 上限；不限容量时 capacity 为 null
const loadStorage = async () => {
  try {
    const { data } = await getShareStorage(shareId.value)
    shareStorage.value = data
  } catch { shareStorage.value = null }
}
// 空间容量百分比：-1 表示不限
const spaceCapPercent = computed(() => {
  const st = shareStorage.value
  if (!st || st.capacity == null) return -1
  return Math.min(100, Math.round(((st.used || 0) / st.capacity) * 100))
})
const spaceCapText = computed(() => {
  const st = shareStorage.value
  if (!st) return ''
  const used = fmtSize(st.used || 0)
  return st.capacity != null ? `${used} / ${fmtSize(st.capacity)}` : `${used} / 不限`
})

const selectDoc = async (doc) => {
  if (!doc) return
  try {
    const { data } = await getShareDoc(shareId.value, doc.id)
    activeDoc.value = data
    editorTitle.value = data.title
    syncDocToUrl(doc.id)
  } catch (e) {
    ElMessage.error('读取文档失败')
  }
}

/** 图块专属文档判定：content 快照恰好是单个导图/流程图/电子表格块（无其他正文）
 *  时，右侧全屏渲染对应组件（新建导图/流程图/电子表格即此类）；点了「转为普通文档」
 *  或手动加过文字后不再匹配，自动回到通用编辑器。 */
const DIAGRAM_RE = /^<div data-type="(mindmap|flowchart|sheet)-block"[\s\S]*?><\/div>$/
const diagramKind = computed(() => {
  const c = (activeDoc.value?.content || '').trim()
  const m = DIAGRAM_RE.exec(c)
  return m ? m[1] : ''
})

// 图块专属文档点「转为普通文档」后刷新当前文档（content 已变，切回通用编辑器）
const onDocReload = async () => {
  if (activeDoc.value) await selectDoc(activeDoc.value)
}

/** 把当前打开的文档 id 写进地址栏（?doc=<id>）。
 *  旧行为：URL 永远停在空间首页，切换文档不反映到地址，
 *  导致「想把这篇文档发给同事」只能发空间首页，对方还得自己找。 */
const syncDocToUrl = (docId) => {
  if (String(route.query.doc ?? '') === String(docId)) return
  router.replace({ path: `/doc-shares/${shareId.value}`, query: { ...route.query, doc: docId } })
}

/** 新建文档类型种子：content 写进快照列，编辑器首次协同时由 seedIfEmpty 种入 Yjs 文档。
 *  导图/流程图/电子表格是 Tiptap 原子节点（parseHTML 按 data-type 识别），空 data 时组件内
 *  自带预置结构（中心主题 / 开始节点 / 空表），所以只需一个空 div。
 *  「整篇文档 = 单个图块」时工作区会全屏渲染该组件（见 diagramKind）。 */
const DOC_SEEDS = {
  doc: { title: '未命名文档', content: '' },
  sheet: {
    title: '未命名电子表格',
    content: '<div data-type="sheet-block" title="电子表格"></div>',
  },
  mindmap: { title: '未命名导图', content: '<div data-type="mindmap-block" title="思维导图"></div>' },
  flowchart: { title: '未命名流程图', content: '<div data-type="flowchart-block" title="流程图"></div>' },
}

const createDoc = async (kind = 'doc') => {
  if (!canWrite.value) return
  const seed = DOC_SEEDS[kind] || DOC_SEEDS.doc
  try {
    const { data } = await createShareDoc(shareId.value, { title: seed.title, content: seed.content })
    await loadDocs()
    await selectDoc(data)
  } catch (e) {
    ElMessage.error(errMsg(e, '创建失败'))
  }
}

// 从外部文件导入为共享文档（.md/.txt/.html/.docx/.pdf）
const importFileInput = ref(null)
const importing = ref(false)
const openImport = () => { if (canWrite.value && importFileInput.value) importFileInput.value.click() }
const onImportFile = async (e) => {
  const file = e.target.files && e.target.files[0]
  e.target.value = '' // 允许重复选择同一文件
  if (!file) return
  if (!canWrite.value) return
  importing.value = true
  try {
    const { data } = await importShareDoc(shareId.value, file)
    ElMessage.success('导入成功：' + data.title)
    await loadDocs()
    await selectDoc(data)
  } catch (err) {
    const msg = err.response?.data?.error || ''
    ElMessage.error(msg || '导入失败（支持 .md/.txt/.html/.docx/.pdf，≤20MB）')
  } finally {
    importing.value = false
  }
}

const saveTitle = async () => {
  if (!canWrite.value || !activeDoc.value) return
  if (editorTitle.value === activeDoc.value.title) return
  saving.value = true
  try {
    await updateShareDoc(shareId.value, activeDoc.value.id, { title: editorTitle.value })
    await loadDocs()
  } catch (e) {
    ElMessage.error(errMsg(e, '标题保存失败'))
  } finally {
    saving.value = false
  }
}

const removeDoc = async (doc) => {
  if (!canManageDoc(doc)) return
  try {
    await ElMessageBox.confirm('确定删除「' + doc.title + '」？删除后不可恢复。', '提示', { type: 'warning' })
  } catch { return }
  try {
    await deleteShareDoc(shareId.value, doc.id)
    ElMessage.success('已删除')
    if (activeDoc.value?.id === doc.id) {
      activeDoc.value = null
      editorTitle.value = ''
    }
    await loadDocs()
  } catch (e) {
    ElMessage.error(errMsg(e, '删除失败'))
  }
}

// ---------- 重命名 ----------
const openRename = (doc) => {
  renameForm.value = { id: doc.id, title: doc.title }
  renameDialog.value = true
}
const doRename = async () => {
  if (!renameForm.value.title.trim()) { ElMessage.warning('请输入标题'); return }
  saving.value = true
  try {
    await updateShareDoc(shareId.value, renameForm.value.id, { title: renameForm.value.title.trim() })
    ElMessage.success('已重命名')
    renameDialog.value = false
    await loadDocs()
    if (activeDoc.value?.id === renameForm.value.id) {
      activeDoc.value.title = renameForm.value.title.trim()
      editorTitle.value = renameForm.value.title.trim()
    }
  } catch (e) {
    ElMessage.error(errMsg(e, '重命名失败'))
  } finally {
    saving.value = false
  }
}

// ---------- 移动 ----------
const openMove = async (doc) => {
  moveForm.value = { id: doc.id, targetId: '' }
  moveLoading.value = true
  moveDialog.value = true
  try {
    const { data } = await listMyShares()
    // 目标空间：我能写入且非当前空间
    moveTargets.value = (data || []).filter(s => s.id !== shareId.value &&
      (s.myPermission === 'OWNER' || s.myPermission === 'ADMIN' || s.myPermission === 'WRITE'))
  } catch (e) {
    ElMessage.error('加载可移动空间失败')
  } finally {
    moveLoading.value = false
  }
}
const doMove = async () => {
  if (!moveForm.value.targetId) { ElMessage.warning('请选择目标空间'); return }
  saving.value = true
  try {
    await moveShareDoc(shareId.value, moveForm.value.id, Number(moveForm.value.targetId))
    ElMessage.success('已移动')
    moveDialog.value = false
    await loadDocs()
  } catch (e) {
    ElMessage.error(errMsg(e, '移动失败'))
  } finally {
    saving.value = false
  }
}

// ---------- 成员 ----------
const openMembers = async () => {
  memberDialog.value = true
  memberLoading.value = false
  await searchMemberUsers('')
}
// 选人搜索：复用后端 /doc-shares/users（登录即可用，无需 USER_MANAGE 管理员权限）
const searchMemberUsers = async (kw) => {
  userLoading.value = true
  try {
    const { data } = await searchShareUsers(kw || '')
    allUsers.value = (data || []).filter(u => u.id !== share.value?.ownerId)
  } catch (e) {
    allUsers.value = []
  } finally {
    userLoading.value = false
  }
}

const invite = async () => {
  if (!memberForm.value.userId) {
    ElMessage.warning('请选择用户')
    return
  }
  try {
    await addMember(shareId.value, { userId: memberForm.value.userId, permission: memberForm.value.permission })
    ElMessage.success('已添加')
    memberForm.value = { userId: '', permission: 'READ' }
    await loadMembers()
  } catch (e) {
    ElMessage.error(errMsg(e, '添加失败'))
  }
}

const changePerm = async (row, perm) => {
  try {
    await updateMember(shareId.value, row.userId, { permission: perm })
    ElMessage.success('权限已更新')
    await loadMembers()
  } catch (e) {
    ElMessage.error('更新失败')
  }
}

const kick = async (row) => {
  try {
    await ElMessageBox.confirm('确定移除「' + (row.displayName || row.username) + '」？', '提示', { type: 'warning' })
  } catch { return }
  try {
    await removeMember(shareId.value, row.userId)
    ElMessage.success('已移除')
    await loadMembers()
  } catch (e) {
    ElMessage.error('移除失败')
  }
}

const updateSpace = async () => {
  try {
    await updateShare(shareId.value, { name: share.value.name, description: share.value.description })
    ElMessage.success('已更新')
  } catch (e) {
    ElMessage.error('更新失败')
  }
}

// ---------- 扩容申请（空间 OWNER/ADMIN 提交，完全管理员审批） ----------
const capReqDialog = ref(false)
const capReqGb = ref(5)
const capReqReason = ref('')
const capReqSaving = ref(false)
const capReqList = ref([])              // 本空间历史申请
const capReqLoading = ref(false)
const CAP_PRESETS = [1, 2, 5, 10, 20]  // GB 预设
const pendingReq = computed(() => capReqList.value.find(r => r.status === 'PENDING') || null)

const openCapReq = async () => {
  capReqDialog.value = true
  capReqGb.value = 5
  capReqReason.value = ''
  capReqLoading.value = true
  try {
    const { data } = await listCapacityRequests(shareId.value)
    capReqList.value = data || []
  } catch (e) {
    capReqList.value = []
  } finally {
    capReqLoading.value = false
  }
}
const submitCapReq = async () => {
  if (!capReqGb.value || capReqGb.value < 1) { ElMessage.warning('请选择目标容量'); return }
  capReqSaving.value = true
  try {
    await submitCapacityRequest(shareId.value, Math.round(capReqGb.value * 1024 * 1024 * 1024), capReqReason.value.trim())
    ElMessage.success('扩容申请已提交，等待系统管理员审批')
    capReqDialog.value = false
    await openCapReq()
  } catch (e) {
    ElMessage.error(errMsg(e, '提交失败'))
  } finally {
    capReqSaving.value = false
  }
}

// ---------- 文档级权限 ----------
const docPermDialog = ref(false)
const docPermTarget = ref(null)   // 当前操作的文档
const docPermMembers = ref([])
const docPermLoading = ref(false)
const openDocPerm = async (doc) => {
  docPermTarget.value = doc
  docPermDialog.value = true
  docPermLoading.value = true
  try {
    const { data } = await listDocMembers(shareId.value, doc.id)
    docPermMembers.value = data || []
  } catch (e) {
    ElMessage.error('加载文档权限失败')
  } finally {
    docPermLoading.value = false
  }
}
// 调整某成员在该文档的权限；与空间权限相同则由后端自动清除单独设置（回退继承）
const setDocPerm = async (row, perm) => {
  try {
    const { data } = await setDocMember(shareId.value, docPermTarget.value.id, row.userId, perm)
    if (data.cleared) {
      row.override = false
      row.permission = row.spacePermission || perm
      ElMessage.success('已回退为继承空间权限')
    } else {
      row.override = true
      ElMessage.success('文档权限已更新')
    }
    // 同步刷新列表里该文档的 myPerm（若当前用户就是被改者则本端也生效，但通常管理员改别人）
    await loadDocs()
  } catch (e) {
    // 失败回滚 select 显示
    row.permission = row.override ? row.permission : (row.spacePermission || 'READ')
    ElMessage.error(errMsg(e, '更新失败'))
  }
}
// 清除某成员在该文档的单独权限（回退继承）
const clearDocPerm = async (row) => {
  try {
    await clearDocMember(shareId.value, docPermTarget.value.id, row.userId)
    row.override = false
    row.permission = row.spacePermission || 'READ'
    ElMessage.success('已清除单独设置，回退继承空间权限')
    await loadDocs()
  } catch (e) {
    ElMessage.error('清除失败')
  }
}

// ---------- 展示辅助 ----------
function fmtSize(n) {
  if (!n) return '0 B'
  if (n < 1024) return n + ' B'
  const u = ['KB', 'MB', 'GB', 'TB']; let i = -1, v = n
  do { v /= 1024; i++ } while (v >= 1024 && i < u.length - 1)
  return v.toFixed(2) + ' ' + u[i]
}
function fmtTime(s) {
  if (!s) return ''
  const d = new Date(s.replace(' ', 'T'))
  if (isNaN(d)) return s
  const p = (x) => String(x).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}
function permLabel(p) {
  if (p === 'OWNER') return '所有者'
  if (p === 'ADMIN') return '管理员'
  if (p === 'WRITE') return '可编辑'
  if (p === 'READ') return '只读'
  return '—'
}

onMounted(load)

// 切到不同空间（/doc-shares/:id 复用同一组件实例）时，按新 id 重新加载整页数据
watch(() => route.params.id, () => {
  activeDoc.value = null
  editorTitle.value = ''
  load()
})

// 地址栏 ?doc= 变化（浏览器前进/后退、外部深链、路径式别名跳转）时切到对应文档，不整页重载。
// selectDoc 内部会 syncDocToUrl，但值相同时是 no-op，不会形成循环。
watch(() => route.query.doc, async (want) => {
  if (!want || !docs.value.length) return
  const id = Number(want)
  if (activeDoc.value?.id === id) return
  const target = docs.value.find(d => d.id === id)
  if (target) await selectDoc(target)
})
</script>

<template>
  <div class="wrap" v-loading="loading">
    <div class="topbar" v-if="share">
      <div class="left">
        <el-button text @click="router.push('/doc-shares')"><el-icon><Document /></el-icon> 返回</el-button>
        <span class="name">{{ share.name }}</span>
        <span class="desc">{{ share.description }}</span>
      </div>
      <div class="right">
        <div class="cap-box" v-if="shareType !== 'PERSONAL' && shareStorage" :title="'已用 ' + spaceCapText + (spaceCapPercent >= 0 ? '（' + spaceCapPercent + '%）' : '')">
          <el-progress :percentage="spaceCapPercent < 0 ? 100 : spaceCapPercent" :show-text="false"
                       :stroke-width="8" style="width: 130px"
                       :color="spaceCapPercent > 90 ? '#ef4444' : (spaceCapPercent < 0 ? '#cbd5e1' : '#6366f1')" />
          <span class="cap-text">{{ spaceCapText }}</span>
        </div>
        <el-button v-if="canAdmin && shareType !== 'PERSONAL'" text @click="openMembers">成员管理</el-button>
        <el-button v-if="canAdmin && shareType !== 'PERSONAL'" text type="primary" @click="openCapReq">申请扩容</el-button>
        <el-tag size="small" :type="isOwner ? 'warning' : canAdmin ? 'success' : canWrite ? '' : 'info'">
          {{ isOwner ? '所有者' : canAdmin ? '管理员' : canWrite ? '可编辑' : '只读' }}
        </el-tag>
      </div>
    </div>

    <div class="workspace">
      <aside class="doc-list">
        <div class="list-head">
          <span>文档 ({{ docs.length }})</span>
          <div class="head-actions" v-if="canWrite">
            <el-dropdown trigger="click" @command="(c) => createDoc(c)">
              <el-button type="primary" size="small">
                新建 <el-icon class="el-icon--right"><ArrowDown /></el-icon>
              </el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="doc"><el-icon><Document /></el-icon> 空白文档</el-dropdown-item>
                  <el-dropdown-item command="sheet"><el-icon><Grid /></el-icon> 电子表格</el-dropdown-item>
                  <el-dropdown-item command="mindmap"><el-icon><Share /></el-icon> 思维导图</el-dropdown-item>
                  <el-dropdown-item command="flowchart"><el-icon><Connection /></el-icon> 流程图</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
            <el-button size="small" :loading="importing" @click="openImport">导入</el-button>
            <input ref="importFileInput" type="file" accept=".md,.markdown,.txt,.text,.html,.htm,.docx,.pdf"
                   style="display:none" @change="onImportFile" />
          </div>
        </div>
        <div class="list-body">
          <div v-for="d in docs" :key="d.id" class="doc-item"
               :class="{ active: activeDoc?.id === d.id }" @click="selectDoc(d)">
            <el-icon size="14" class="doc-ic"><Document /></el-icon>
            <span class="doc-title">{{ d.title }}</span>
            <!-- 飞书式 ⋯ 菜单：重命名 / 移动 / 删除 / 详情（文档所有者或空间管理员可管理） -->
            <el-dropdown v-if="canManageDoc(d)" trigger="click" @command="(c) => {
              if (c === 'rename') openRename(d)
              else if (c === 'move') openMove(d)
              else if (c === 'perm') openDocPerm(d)
              else if (c === 'delete') removeDoc(d)
            }" @click.stop>
              <span class="doc-menu" @click.stop><el-icon><MoreFilled /></el-icon></span>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="rename">重命名</el-dropdown-item>
                  <el-dropdown-item command="move">移动到…</el-dropdown-item>
                  <el-dropdown-item command="perm">文档权限</el-dropdown-item>
                  <el-dropdown-item command="delete" divided>{{ canAdmin ? '删除' : '删除（我的文档）' }}</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
            <div class="doc-meta">
              <span class="ava" v-if="d.ownerAvatar"><img :src="d.ownerAvatar" :alt="d.ownerName" /></span>
              <span class="ava ava-tx" v-else>{{ (d.ownerName || '?').slice(0, 1) }}</span>
              <span class="doc-owner">{{ d.ownerName || '未知' }}</span>
              <span class="dot">·</span>
              <span class="doc-size">{{ fmtSize(d.size) }}</span>
              <span class="dot">·</span>
              <span class="doc-time">{{ fmtTime(d.updatedAt) }}</span>
            </div>
          </div>
          <el-empty v-if="!docs.length" description="暂无文档" :image-size="60" />
        </div>
      </aside>

      <main class="editor" v-if="activeDoc">
        <div class="title-bar">
          <input v-model="editorTitle" class="title-input" placeholder="无标题文档" :disabled="!canWrite" @change="saveTitle" />
        </div>
        <DocShareEditor :key="activeDoc.id" :share-id="shareId" :doc-id="activeDoc.id"
                        :can-write="docCanWrite" :legacy-content="activeDoc.content"
                        :diagram="diagramKind" @reload-document="onDocReload" />
      </main>

      <div v-else class="editor empty">
        <el-empty description="选择左侧文档开始协作，或点击新建" />
      </div>
    </div>

    <!-- 成员管理 -->
    <el-dialog v-model="memberDialog" title="成员管理" width="560px">
      <div v-loading="memberLoading">
        <div class="invite-row">
          <el-select v-model="memberForm.userId" filterable remote reserve-keyword
                     :remote-method="searchMemberUsers" :loading="userLoading"
                     placeholder="搜索用户名或昵称" style="width: 220px">
            <el-option v-for="u in allUsers" :key="u.id" :label="u.displayName || u.username" :value="u.id" />
          </el-select>
          <el-select v-model="memberForm.permission" style="width: 140px">
            <el-option label="只读" value="READ" />
            <el-option label="可编辑" value="WRITE" />
            <el-option label="管理员" value="ADMIN" />
          </el-select>
          <el-button type="primary" @click="invite">邀请</el-button>
        </div>
        <el-table v-stack-table :data="members" border stripe size="small">
          <el-table-column label="用户" min-width="140">
            <template #default="{ row }">
              <el-avatar v-if="row.avatar" :size="20" :src="row.avatar" style="vertical-align: middle; margin-right: 6px" />
              <span class="ava ava-tx sm" v-else>{{ (row.displayName || row.username || '?').slice(0, 1) }}</span>
              {{ row.displayName || row.username }}
            </template>
          </el-table-column>
          <el-table-column label="权限" width="160">
            <template #default="{ row }">
              <el-select v-model="row.permission" size="small" @change="(v) => changePerm(row, v)" :disabled="!canAdmin">
                <el-option label="只读" value="READ" />
                <el-option label="可编辑" value="WRITE" />
                <el-option label="管理员" value="ADMIN" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="90" v-if="isOwner">
            <template #default="{ row }">
              <el-button link type="danger" size="small" @click="kick(row)">移除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </el-dialog>

    <!-- 重命名 -->
    <el-dialog v-model="renameDialog" title="重命名文档" width="420px">
      <el-input v-model="renameForm.title" placeholder="文档标题" maxlength="200" show-word-limit @keyup.enter="doRename" />
      <template #footer>
        <el-button @click="renameDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="doRename">确定</el-button>
      </template>
    </el-dialog>

    <!-- 移动 -->
    <el-dialog v-model="moveDialog" title="移动到其它空间" width="420px">
      <div v-loading="moveLoading">
        <el-select v-model="moveForm.targetId" placeholder="选择目标空间" style="width: 100%">
          <el-option v-for="t in moveTargets" :key="t.id" :label="t.name + '（' + (t.myPermission === 'OWNER' ? '所有者' : t.myPermission === 'ADMIN' ? '管理员' : '可编辑') + '）'" :value="t.id" />
        </el-select>
        <p class="tip">仅列出你有写入权限的空间。移动后文档仍归属于原创建者，占用其个人配额。</p>
      </div>
      <template #footer>
        <el-button @click="moveDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="doMove">移动</el-button>
      </template>
    </el-dialog>

    <!-- 文档权限 -->
    <el-dialog v-model="docPermDialog" :title="'文档权限 · ' + (docPermTarget?.title || '')" width="560px">
      <div v-loading="docPermLoading">
        <p class="tip">默认成员继承空间权限；可在此对单篇文档单独收窄或提升。设为与空间权限相同即自动回退继承。</p>
        <el-table v-stack-table :data="docPermMembers" border stripe size="small">
          <el-table-column label="成员" min-width="150">
            <template #default="{ row }">
              <el-avatar v-if="row.avatar" :size="20" :src="row.avatar" style="vertical-align: middle; margin-right: 6px" />
              <span class="ava ava-tx sm" v-else>{{ (row.displayName || row.username || '?').slice(0, 1) }}</span>
              {{ row.displayName || row.username }}
            </template>
          </el-table-column>
          <el-table-column label="空间权限" width="100">
            <template #default="{ row }">
              <el-tag size="small" effect="plain">{{ permLabel(row.spacePermission) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="文档权限" width="160">
            <template #default="{ row }">
              <el-select v-model="row.permission" size="small" @change="(v) => setDocPerm(row, v)">
                <el-option label="只读" value="READ" />
                <el-option label="可编辑" value="WRITE" />
                <el-option label="管理员" value="ADMIN" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="单独设置" width="90">
            <template #default="{ row }">
              <el-tag v-if="row.override" size="small" type="warning">是</el-tag>
              <el-tag v-else size="small" type="info">继承</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="90" v-if="docPermTarget">
            <template #default="{ row }">
              <el-button v-if="row.override" link type="primary" size="small" @click="clearDocPerm(row)">回退</el-button>
              <span v-else class="tip">—</span>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </el-dialog>

    <!-- 扩容申请 -->
    <el-dialog v-model="capReqDialog" :title="'申请扩容 · ' + (share?.name || '')" width="460px">
      <div v-loading="capReqLoading">
        <p class="tip">默认空间容量为 2GB。如需更大容量，请提交申请，由系统管理员审批后方可生效。</p>

        <template v-if="pendingReq">
          <el-alert type="warning" :closable="false" show-icon style="margin-bottom: 14px">
            <template #title>已有一条待审批申请</template>
            <div style="font-size: 12.5px; margin-top: 4px">
              目标容量：<b>{{ fmtSize(pendingReq.requestedCapacity) }}</b>，提交于 {{ fmtTime(pendingReq.createdAt) }}
            </div>
          </el-alert>
        </template>

        <el-form label-width="72px" v-if="!pendingReq">
          <el-form-item label="目标容量">
            <div class="cap-presets">
              <button v-for="g in CAP_PRESETS" :key="g" type="button"
                      class="cap-chip" :class="{ active: capReqGb === g }" @click="capReqGb = g">
                {{ g }}GB
              </button>
            </div>
          </el-form-item>
          <el-form-item label="申请理由">
            <el-input v-model="capReqReason" type="textarea" :rows="3" maxlength="200" show-word-limit
                      placeholder="简要说明扩容用途（选填）" />
          </el-form-item>
        </el-form>

        <el-divider v-if="capReqList.length" content-position="left">申请记录</el-divider>
        <div v-for="r in capReqList" :key="r.id" class="cap-req-row">
          <div class="cr-top">
            <span class="cr-cap">{{ fmtSize(r.requestedCapacity) }}</span>
            <el-tag size="small" :type="r.status === 'PENDING' ? 'warning' : r.status === 'APPROVED' ? 'success' : 'info'">
              {{ r.status === 'PENDING' ? '待审批' : r.status === 'APPROVED' ? '已通过' : '已驳回' }}
            </el-tag>
          </div>
          <div class="cr-meta">
            {{ fmtTime(r.createdAt) }}
            <template v-if="r.reviewerName"> · 审批人 {{ r.reviewerName }}</template>
            <template v-if="r.reviewComment"> · {{ r.reviewComment }}</template>
          </div>
        </div>
      </div>
      <template #footer>
        <el-button @click="capReqDialog = false">关闭</el-button>
        <el-button v-if="!pendingReq" type="primary" :loading="capReqSaving" @click="submitCapReq">提交申请</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.wrap { height: calc(100vh - 84px); display: flex; flex-direction: column; }
.topbar { display: flex; align-items: center; justify-content: space-between; padding-bottom: 12px; border-bottom: 1px solid #e2e8f0; margin-bottom: 12px; }
.topbar .left { display: flex; align-items: center; gap: 10px; }
.topbar .name { font-weight: 600; font-size: 16px; color: #0f172a; }
.topbar .desc { color: #64748b; font-size: 13px; max-width: 320px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.topbar .right { display: flex; align-items: center; gap: 10px; }
.topbar .cap-box { display: flex; align-items: center; gap: 8px; padding: 4px 10px; background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 8px; }
.topbar .cap-text { font-size: 12px; color: #64748b; white-space: nowrap; }
.workspace { flex: 1; display: flex; min-height: 0; border: 1px solid #e2e8f0; border-radius: 8px; background: #fff; }
.doc-list { width: 264px; border-right: 1px solid #e2e8f0; display: flex; flex-direction: column; }
.list-head { padding: 10px 12px; border-bottom: 1px solid #e2e8f0; display: flex; align-items: center; justify-content: space-between; font-weight: 600; }
.list-head .head-actions { display: flex; gap: 6px; font-weight: normal; }
.list-body { flex: 1; overflow: auto; padding: 6px; }
.doc-item { padding: 9px 10px; border-radius: 6px; cursor: pointer; color: #334155; font-size: 13px; }
.doc-item:hover { background: #f1f5f9; }
.doc-item.active { background: #eef2ff; }
.doc-item.active .doc-title { color: #4f46e5; font-weight: 600; }
.doc-ic { color: #94a3b8; flex-shrink: 0; }
.doc-title { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.doc-item { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; }
.doc-menu { color: #94a3b8; padding: 0 2px; }
.doc-menu:hover { color: #4f46e5; }
.doc-meta { width: 100%; display: flex; align-items: center; gap: 5px; padding-left: 20px; font-size: 11.5px; color: #94a3b8; margin-top: 2px; }
.doc-meta .dot { color: #cbd5e1; }
.doc-owner { max-width: 90px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ava { width: 18px; height: 18px; border-radius: 50%; overflow: hidden; display: inline-flex; align-items: center; justify-content: center; flex-shrink: 0; }
.ava img { width: 100%; height: 100%; object-fit: cover; }
.ava-tx { background: #6366f1; color: #fff; font-size: 10px; font-weight: 600; }
.ava-tx.sm { width: 20px; height: 20px; font-size: 11px; vertical-align: middle; }
.editor { flex: 1; display: flex; flex-direction: column; min-width: 0; background: #f2f3f5; }
.editor.empty { align-items: center; justify-content: center; }
.title-bar { background: #f2f3f5; padding: 20px 0 0; }
.title-bar .title-input {
  display: block; width: min(860px, 100%); margin: 0 auto; box-sizing: border-box;
  padding: 0 64px 6px; border: none; outline: none; background: transparent;
  font-size: 26px; font-weight: 700; color: #0f172a; line-height: 1.35;
}
.title-bar .title-input::placeholder { color: #c3c9d4; font-weight: 600; }
.title-bar .title-input:disabled { color: #334155; cursor: not-allowed; }
.invite-row { display: flex; gap: 10px; margin-bottom: 14px; }
.tip { color: #94a3b8; font-size: 12px; margin: 10px 0 0; line-height: 1.6; }

/* 扩容申请 */
.cap-presets { display: flex; flex-wrap: wrap; gap: 8px; }
.cap-chip { border: 1px solid #e2e8f0; background: #fff; color: #475569; padding: 6px 16px; border-radius: 8px; cursor: pointer; font-size: 13px; transition: all .15s; }
.cap-chip:hover { border-color: #c7d2fe; color: #4f46e5; }
.cap-chip.active { background: #4f46e5; border-color: #4f46e5; color: #fff; font-weight: 600; }
.cap-req-row { padding: 8px 10px; border: 1px solid #eef0f4; border-radius: 8px; margin-bottom: 8px; }
.cap-req-row .cr-top { display: flex; align-items: center; justify-content: space-between; }
.cap-req-row .cr-cap { font-weight: 600; color: #0f172a; font-size: 14px; }
.cap-req-row .cr-meta { color: #94a3b8; font-size: 12px; margin-top: 4px; line-height: 1.5; }
html.dark .cap-chip { background: #1c1c28; border-color: #2a2a35; color: #c2c6d2; }
html.dark .cap-chip:hover { border-color: #6366f1; color: #a5b4fc; }
html.dark .cap-chip.active { background: #4f46e5; border-color: #4f46e5; color: #fff; }
html.dark .cap-req-row { border-color: #2a2a35; }
html.dark .cap-req-row .cr-cap { color: #e7e7ee; }

/* ---------- 暗黑模式 ---------- */
html.dark .topbar { border-bottom-color: #2a2a35; }
html.dark .topbar .name { color: #e7e7ee; }
html.dark .topbar .desc { color: #9aa0ad; }
html.dark .topbar .cap-box { background: #23232e; border-color: #2a2a35; }
html.dark .topbar .cap-text { color: #9aa0ad; }
html.dark .workspace { border-color: #2a2a35; background: #17171f; }
html.dark .doc-list { border-right-color: #2a2a35; }
html.dark .list-head { border-bottom-color: #2a2a35; color: #c7c8d4; }
html.dark .doc-item { color: #c2c6d2; }
html.dark .doc-item:hover { background: #23232e; }
html.dark .doc-item.active { background: #232347; }
html.dark .doc-item.active .doc-title { color: #a5b4fc; }
html.dark .doc-menu { color: #9aa0ad; }
html.dark .doc-menu:hover { color: #a5b4fc; }
html.dark .doc-meta { color: #7b8190; }
html.dark .doc-meta .dot { color: #3a3b45; }
html.dark .editor { background: #0e0e12; }
html.dark .title-bar { background: #0e0e12; }
html.dark .title-bar .title-input { color: #f1f5f9; }
html.dark .title-bar .title-input::placeholder { color: #4b5563; }
html.dark .title-bar .title-input:disabled { color: #c2c6d2; }

/* ===================== 响应式布局（追加在末尾） ===================== */
/* 平板 / 窄屏：顶栏换行，文档列表收窄（降列），标题输入区内边距收紧 */
@media (max-width: 1024px) {
  .topbar { flex-wrap: wrap; gap: 8px 10px; padding-bottom: 10px; margin-bottom: 10px; }
  .topbar .left { flex-wrap: wrap; min-width: 0; gap: 6px 8px; }
  .topbar .name { max-width: 100%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .topbar .desc { max-width: 100%; font-size: 12.5px; }
  .topbar .right { flex-wrap: wrap; gap: 6px 10px; }
  .topbar .cap-box { padding: 4px 8px; }
  .topbar .cap-box :deep(.el-progress) { width: 96px !important; }
  .doc-list { width: 210px; flex: 0 0 210px; }
  .list-head { flex-wrap: wrap; gap: 6px; padding: 8px 10px; }
  .title-bar { padding-top: 12px; }
  .title-bar .title-input { padding: 0 28px 6px; font-size: 22px; }
  .editor { min-height: 0; }
}

/* 手机：文档列表与编辑区改为上下单列，列表限高可滚 */
@media (max-width: 640px) {
  .wrap { height: auto; min-height: calc(100vh - 72px); }
  .topbar { padding-bottom: 8px; }
  .topbar .desc { flex: 1 1 100%; }
  .topbar .right { width: 100%; }
  .topbar .cap-box { flex: 1 1 100%; }
  .topbar .cap-box :deep(.el-progress) { width: 100% !important; }
  .workspace { flex-direction: column; min-height: 0; }
  .doc-list { width: 100%; flex: none; max-height: 42vh; border-right: 0; }
  .list-head { padding: 8px; }
  .doc-item { padding: 8px; }
  .doc-meta { padding-left: 0; }
  .doc-owner { max-width: 72px; }
  .editor { min-height: 60vh; }
  .editor.empty { min-height: 40vh; }
  .title-bar { padding-top: 8px; }
  .title-bar .title-input { width: 100%; padding: 0 14px 6px; font-size: 19px; }
  .invite-row { flex-wrap: wrap; gap: 8px; }
  .invite-row :deep(.el-select) { width: 100% !important; }
  .cap-req-row { flex-wrap: wrap; gap: 6px; }
}
</style>
