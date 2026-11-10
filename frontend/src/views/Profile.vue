<script setup>
import { ref, computed, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '../store/auth'
import { useHistoryStore } from '../store/history'
import { updateProfile, pointsLogPaged, exportMyData, emailStatus, sendEmailCode, verifyEmailCode, unbindEmailApi, me } from '../api/auth'
import { listMyDocs, myLikedDocs, myCommentedDocs, myFavorites, changePassword, userBadges } from '../api/kb'
import { BADGE_ICONS, DEFAULT_BADGE_ICON, tierGradient } from '../utils/badgeIcons'
const g0 = (b) => tierGradient(b.tier)[0]
const g1 = (b) => tierGradient(b.tier)[1]
import { uploadFile } from '../api/file'
import { myCertificates } from '../api/training'
import { getLevel } from '../utils/level'
import { DEFAULT_AVATARS } from '../utils/defaultAvatars'
import { saveBinaryFile } from '../utils/shareOut'
import CertDialog from '../components/CertDialog.vue'
import WornBadgeStrip from '../components/WornBadgeStrip.vue'
import { errMsg } from '../utils/errMsg'

const auth = useAuthStore()
const router = useRouter()
const history = useHistoryStore()

const displayName = ref('')
const email = ref('')
const emailVerified = ref(false)
const emailNotifyEnabled = ref(false)
const mailConfigured = ref(false)
const code = ref('')
const codeSent = ref(false)
const sendingCode = ref(false)
const verifying = ref(false)
const unbinding = ref(false)
const bio = ref('')
const gender = ref('')
const avatar = ref('')
const saving = ref(false)
const uploading = ref(false)

const myDocs = ref([])
const loadingStats = ref(false)

// 我已获得的徽章（资料页展示）
const myBadges = ref([])
const loadingBadges = ref(false)
async function loadBadges() {
  if (!auth.user?.id) return
  loadingBadges.value = true
  try {
    const { data } = await userBadges(auth.user.id)
    myBadges.value = Array.isArray(data) ? data : []
  } catch (e) {
    myBadges.value = []
  } finally {
    loadingBadges.value = false
  }
}
// 当前佩戴的徽章 id 列表（供昵称旁展示，最多 3 枚）
const wornIds = computed(() => auth.user?.wornBadges || [])

// 积分明细：资料卡只预览最近 10 条，更多去独立明细页 /points/log（带分页）
const logs = ref([])
const logTotal = ref(0)
const loadingLog = ref(false)
async function loadLog() {
  loadingLog.value = true
  try {
    const { data } = await pointsLogPaged(0, 10)
    logs.value = Array.isArray(data.content) ? data.content : []
    logTotal.value = data.total || 0
  } catch (e) {
    logs.value = []
    logTotal.value = 0
  } finally {
    loadingLog.value = false
  }
}

// 点赞 / 评论过的帖子（个人资料左下角）
const likedDocs = ref([])
const commentedDocs = ref([])
const favoriteDocs = ref([])
const loadingMine = ref(false)
const showAvatarPicker = ref(false)
const avatarPending = ref(null)   // 待审新头像 URL（null=无待审）——用于「审核中」持久提示与角标

// 个人资料页背景图：管理员在外观设置里统一配置（utils/appearance 共享缓存），空则用内置默认
import { appearanceProfileBg as profileBg, loadAppearance } from '../utils/appearance'
// url() 内的引号在 JS 里生成，避免在模板属性值里写裸双引号破坏 HTML 解析
const coverBlurStyle = computed(() => profileBg.value ? { backgroundImage: 'url("' + profileBg.value + '")' } : null)

onMounted(() => {
  displayName.value = auth.user?.displayName || auth.user?.username || ''
  email.value = auth.user?.email || ''
  bio.value = auth.user?.bio || ''
  gender.value = auth.user?.gender || ''
  avatar.value = auth.user?.avatar || ''
  avatarPending.value = auth.user?.avatarPending || null
  loadAppearance()
  loadEmailStatus()
  loadDocs()
  loadLog()
  loadMine()
  loadCerts()
  loadBadges()
})

// 我的结业证书（学完章节 + 考试及格后由后端派生）
const certs = ref([])
const loadingCerts = ref(false)
const certVisible = ref(false)
const activeCert = ref(null)
async function loadCerts() {
  if (!auth.loggedIn) return
  loadingCerts.value = true
  try {
    const { data } = await myCertificates()
    certs.value = Array.isArray(data) ? data : []
  } catch (e) {
    certs.value = []
  } finally {
    loadingCerts.value = false
  }
}
function viewCert(c) {
  activeCert.value = c
  certVisible.value = true
}

// 积分变动类型 → 中文标签
const typeText = (t) => ({
  LOGIN: '每日登录', PUBLISH: '发布文章', HOT: '热帖奖励',
  ADJUST: '管理员调整', SYSTEM: '系统调整',
}[t] || t || '变动')

async function loadDocs() {
  loadingStats.value = true
  try {
    const { data } = await listMyDocs()
    myDocs.value = Array.isArray(data) ? data : []
  } catch (e) {
    myDocs.value = []
  } finally {
    loadingStats.value = false
  }
}

const preview = computed(() => avatar.value || '')
const initial = computed(() => (displayName.value || auth.user?.username || '?').charAt(0).toUpperCase())

// 帖子统计
const publishedCount = computed(() => myDocs.value.filter(d => d.status === 'PUBLISHED').length)
const draftCount = computed(() => myDocs.value.filter(d => d.status === 'DRAFT').length)

// 等级信息（由积分计算）
const levelInfo = computed(() => getLevel(auth.user?.points || 0))

// 注册时长
const memberDays = computed(() => {
  const c = auth.user?.createdAt
  if (!c) return null
  const t = new Date(c.replace('T', ' ').replace(/Z$/, '')).getTime()
  if (isNaN(t)) return null
  return Math.max(1, Math.floor((Date.now() - t) / 86400000))
})

function fmtDate(s) {
  if (!s) return '—'
  const d = new Date(String(s).replace('T', ' ').replace(/Z$/, ''))
  if (isNaN(d.getTime())) return String(s)
  const p = n => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

// 选择图片 → 上传到文件系统，只存短 URL（不再把 base64 塞进 avatar 字段，避免越库列长度限制）
async function onFile(e) {
  const input = e.target
  const file = input.files && input.files[0]
  input.value = ''   // 清空，允许再次选择同一文件也能触发 change
  if (!file) return
  if (!file.type.startsWith('image/')) {
    ElMessage.warning('请选择图片文件')
    return
  }
  uploading.value = true
  try {
    const { data } = await uploadFile(file)
    avatar.value = data.data.url   // 形如 /uploads/2026/08/14/xxxx.jpg
    ElMessage.success('头像已上传，记得点「保存修改」')
  } catch (err) {
    ElMessage.error('头像上传失败：' + (err.response?.data?.message || '请重试'))
  } finally {
    uploading.value = false
  }
}

function resetAvatar() {
  avatar.value = ''
}

async function save() {
  saving.value = true
  try {
    const { data } = await updateProfile({
      displayName: displayName.value.trim(),
      avatar: avatar.value,
      bio: bio.value.trim(),
      gender: gender.value,
      emailNotifyEnabled: emailNotifyEnabled.value,
    })
    auth.setProfile(data)
    avatarPending.value = data.avatarPending || null
    if (data.avatarPending) {
      // 弹提示 + 页面持久「审核中」横幅/角标双保险，避免用户误以为没保存而反复提交
      ElMessage({ type: 'warning', message: '新头像已提交审核，通过前对外仍展示原头像，请勿重复提交', duration: 6000 })
    } else {
      ElMessage.success('资料已更新')
    }
  } catch (e) {
    ElMessage.error(errMsg(e, '更新失败'))
  } finally {
    saving.value = false
  }
}

// 加载邮箱绑定状态（验证/通知开关/邮件服务是否可用）
async function loadEmailStatus() {
  try {
    const { data } = await emailStatus()
    email.value = data.email || ''
    emailVerified.value = !!data.verified
    emailNotifyEnabled.value = !!data.notifyEnabled
    mailConfigured.value = !!data.mailConfigured
  } catch (e) { /* 忽略，默认未绑定 */ }
}

async function sendCode() {
  if (!email.value.trim()) { ElMessage.warning('请先输入邮箱'); return }
  sendingCode.value = true
  try {
    await sendEmailCode(email.value.trim())
    codeSent.value = true
    ElMessage.success('验证码已发送，请查收邮箱')
  } catch (e) {
    ElMessage.error(errMsg(e, '发送失败'))
  } finally {
    sendingCode.value = false
  }
}

async function verifyEmail() {
  if (!code.value.trim()) { ElMessage.warning('请输入验证码'); return }
  verifying.value = true
  try {
    const { data } = await verifyEmailCode(email.value.trim(), code.value.trim())
    const { data: meData } = await me()
    auth.setProfile(meData)
    emailVerified.value = true
    email.value = data.email || email.value
    emailNotifyEnabled.value = !!meData.emailNotifyEnabled
    codeSent.value = false
    code.value = ''
    ElMessage.success('邮箱绑定成功')
  } catch (e) {
    ElMessage.error(errMsg(e, '验证失败'))
  } finally {
    verifying.value = false
  }
}

async function unbindEmail() {
  unbinding.value = true
  try {
    await unbindEmailApi()
    const { data: meData } = await me()
    auth.setProfile(meData)
    emailVerified.value = false
    emailNotifyEnabled.value = false
    email.value = ''
    codeSent.value = false
    code.value = ''
    ElMessage.success('已解绑邮箱')
  } catch (e) {
    ElMessage.error(errMsg(e, '解绑失败'))
  } finally {
    unbinding.value = false
  }
}

async function saveNotify() {
  try {
    const { data } = await updateProfile({ emailNotifyEnabled: emailNotifyEnabled.value })
    auth.setProfile(data)
  } catch (e) {
    ElMessage.error(errMsg(e, '保存失败'))
    emailNotifyEnabled.value = !emailNotifyEnabled.value   // 失败回滚
  }
}

function goBack() { router.push('/') }
function goEdit(id) { router.push(`/write/edit/${id}`) }
function goMyPosts() { router.push('/my/posts') }
function goMyHome() {
  const id = auth.user?.id
  if (!id) { ElMessage.warning('用户信息未就绪，请刷新页面后重试'); return }
  router.push('/user/' + id)
}

const statusMeta = {
  PUBLISHED: { text: '已发布', type: 'success' },
  DRAFT: { text: '草稿', type: 'info' },
  BLOCKED: { text: '已封禁', type: 'danger' },
}
const recentDocs = computed(() => myDocs.value.slice(0, 5))

// 点赞 / 评论过的帖子（个人资料左下角填充，限量展示保持页面协调）
async function loadMine() {
  if (!auth.loggedIn) return
  loadingMine.value = true
  try {
    const [lk, cm, fv] = await Promise.all([myLikedDocs(), myCommentedDocs(), myFavorites()])
    likedDocs.value = Array.isArray(lk.data) ? lk.data : []
    commentedDocs.value = Array.isArray(cm.data) ? cm.data : []
    favoriteDocs.value = Array.isArray(fv.data) ? fv.data : []
  } catch (e) {
    likedDocs.value = []
    commentedDocs.value = []
  } finally {
    loadingMine.value = false
  }
}

// 点赞 / 评论过的帖子：默认只显示前 4 条，数量多时可展开（展开后列表限高滚动，避免侧栏无限拉长）
const mineOpen = reactive({ liked: false, commented: false, fav: false })
const recentLiked = computed(() => mineOpen.liked ? likedDocs.value : likedDocs.value.slice(0, 4))
const recentCommented = computed(() => mineOpen.commented ? commentedDocs.value : commentedDocs.value.slice(0, 4))
const recentFavorites = computed(() => mineOpen.fav ? favoriteDocs.value : favoriteDocs.value.slice(0, 4))

// 浏览历史：与左栏点赞/评论/收藏保持一致的交互（默认 4 条，可展开；带「查看完整历史」跳转 /history）
const historyOpen = ref(false)
const recentHistory = computed(() => historyOpen.value ? history.items : history.items.slice(0, 4))

function selectDefaultAvatar(url) {
  avatar.value = url
  showAvatarPicker.value = false
  ElMessage.success('已选默认头像，记得点「保存修改」')
}

function goPost(id) { router.push('/post/' + id) }

// 修改密码
const pwdDialog = ref(false)
const oldPwd = ref('')
const newPwd = ref('')
const newPwd2 = ref('')
const changing = ref(false)
async function changePwd() {
  if (newPwd.value.length < 6) { ElMessage.warning('新密码至少 6 位'); return }
  if (newPwd.value !== newPwd2.value) { ElMessage.warning('两次新密码不一致'); return }
  changing.value = true
  try {
    await changePassword(oldPwd.value, newPwd.value)
    ElMessage.success('密码已修改，下次登录请使用新密码')
    pwdDialog.value = false
    oldPwd.value = ''; newPwd.value = ''; newPwd2.value = ''
  } catch (e) {
    ElMessage.error(errMsg(e, '修改失败'))
  } finally {
    changing.value = false
  }
}

/* ---------- 个人数据导出（隐私政策「你的权利」） ---------- */
const exporting = ref(false)
async function doExport() {
  if (exporting.value) return
  exporting.value = true
  try {
    const { data } = await exportMyData()
    const blob = data instanceof Blob ? data : new Blob([data], { type: 'application/json' })
    await saveBinaryFile({
      filename: `kb-导出-${auth.user?.username || 'me'}-${new Date().toISOString().slice(0, 10)}.json`,
      blob,
      mime: 'application/json',
    })
    ElMessage.success('已开始下载，请查看浏览器下载列表')
  } catch (e) {
    ElMessage.error(errMsg(e, '导出失败，请稍后重试'))
  } finally {
    exporting.value = false
  }
}
</script>

<template>
  <div class="profile-page">
    <!-- 封面 -->
    <div class="cover" :class="{ 'has-bg': profileBg }">
      <div v-if="profileBg" class="cover-bg">
        <div class="cover-blur" :style="coverBlurStyle"></div>
        <img class="cover-fit" :src="profileBg" alt="" />
      </div>
      <div class="cover-avatar">
        <img v-if="preview" :src="preview" alt="" />
        <span v-else>{{ initial }}</span>
        <label class="cam" title="更换头像">
          <input ref="fileInput" type="file" accept="image/*" hidden @change="onFile" />
          <span>{{ uploading ? '⏳' : '📷' }}</span>
        </label>
      </div>
    </div>

    <div class="body">
      <!-- 左侧信息栏 -->
      <aside class="side">
        <div class="name">
          {{ displayName || auth.user?.username }}
          <WornBadgeStrip :badge-ids="wornIds" :size="22" />
        </div>
        <div class="username">@{{ auth.user?.username }}</div>
        <div class="role-row">
          <el-tag :type="auth.isAdmin ? 'danger' : 'info'" size="small">
            {{ auth.isAdmin ? '管理员' : '普通用户' }}
          </el-tag>
          <el-tag v-if="auth.user?.departmentName" size="small" effect="plain" type="primary">
            {{ auth.user.departmentName }}
          </el-tag>
        </div>

        <!-- 积分 / 等级 -->
        <div class="lv-card">
          <div class="lv-top">
            <span class="lv-badge">Lv{{ levelInfo.level }}</span>
            <span class="lv-title">{{ levelInfo.title }}</span>
            <span class="lv-points">{{ auth.user?.points || 0 }} 积分</span>
          </div>
          <el-progress :percentage="levelInfo.progress"
                       :stroke-width="8"
                       :show-text="false"
                       color="#6366f1" />
          <div class="lv-tip" v-if="levelInfo.next != null">
            还需 {{ levelInfo.next - (auth.user?.points || 0) }} 积分升级到 {{ levelInfo.title }} 下一阶
          </div>
          <div class="lv-tip" v-else>已达最高等级 🎉</div>
        </div>

        <p class="bio" v-if="bio">{{ bio }}</p>
        <p class="bio muted" v-else>这个人很懒，还没有填写简介～</p>

        <ul class="meta">
          <li v-if="auth.user?.departmentName"><span>部门</span><b>{{ auth.user.departmentName }}</b></li>
          <li v-if="gender"><span>性别</span><b>{{ gender }}</b></li>
          <li><span>注册于</span><b>{{ fmtDate(auth.user?.createdAt) }}</b></li>
          <li v-if="memberDays"><span>已加入</span><b>{{ memberDays }} 天</b></li>
        </ul>

        <div class="stats">
          <div class="stat" @click="goMyPosts" style="cursor:pointer">
            <div class="num">{{ loadingStats ? '…' : myDocs.length }}</div>
            <div class="lbl">帖子</div>
          </div>
          <div class="stat">
            <div class="num">{{ loadingStats ? '…' : publishedCount }}</div>
            <div class="lbl">已发布</div>
          </div>
          <div class="stat">
            <div class="num">{{ loadingStats ? '…' : draftCount }}</div>
            <div class="lbl">草稿</div>
          </div>
        </div>

        <div class="side-actions">
          <el-button class="go-posts" plain @click="goMyPosts">
            <span class="go-txt">查看我的全部帖子</span><span class="go-arrow">→</span>
          </el-button>
          <el-button class="go-posts" plain @click="goMyHome">
            <span class="go-txt">查看我的主页</span><span class="go-arrow">→</span>
          </el-button>
        </div>

        <!-- 我的互动概览：协调左栏高度，且为有用信息 -->
        <div class="interact" v-if="auth.loggedIn">
          <div class="ic"><div class="n">👍 {{ likedDocs.length }}</div><div class="l">点赞</div></div>
          <div class="ic"><div class="n">💬 {{ commentedDocs.length }}</div><div class="l">评论</div></div>
          <div class="ic"><div class="n">⭐ {{ favoriteDocs.length }}</div><div class="l">收藏</div></div>
        </div>

        <!-- 我点赞的 / 我评论的 / 我的收藏：填充左下角，默认 4 条，可展开 -->
        <div class="mine-block" v-if="auth.loggedIn">
          <div class="mine-hd">我点赞的 <span class="cnt">👍 {{ likedDocs.length }}</span></div>
          <ul v-if="recentLiked.length" class="mine-list" :class="{ open: mineOpen.liked }">
            <li v-for="d in recentLiked" :key="'l'+d.id" @click="goPost(d.id)" :title="d.title">
              <span class="t">{{ d.title }}</span>
            </li>
          </ul>
          <div v-else class="mine-empty">还没有点赞的帖子</div>
          <button v-if="likedDocs.length > 4" class="mine-more" @click="mineOpen.liked = !mineOpen.liked">
            {{ mineOpen.liked ? '收起' : '展开全部 ' + likedDocs.length + ' 条' }}
          </button>
        </div>

        <div class="mine-block" v-if="auth.loggedIn">
          <div class="mine-hd">我评论的 <span class="cnt">💬 {{ commentedDocs.length }}</span></div>
          <ul v-if="recentCommented.length" class="mine-list" :class="{ open: mineOpen.commented }">
            <li v-for="d in recentCommented" :key="'c'+d.id" @click="goPost(d.id)" :title="d.title">
              <span class="t">{{ d.title }}</span>
            </li>
          </ul>
          <div v-else class="mine-empty">还没有评论的帖子</div>
          <button v-if="commentedDocs.length > 4" class="mine-more" @click="mineOpen.commented = !mineOpen.commented">
            {{ mineOpen.commented ? '收起' : '展开全部 ' + commentedDocs.length + ' 条' }}
          </button>
        </div>

        <div class="mine-block" v-if="auth.loggedIn">
          <div class="mine-hd">我的收藏 <span class="cnt">⭐ {{ favoriteDocs.length }}</span></div>
          <ul v-if="recentFavorites.length" class="mine-list" :class="{ open: mineOpen.fav }">
            <li v-for="d in recentFavorites" :key="'f'+d.id" @click="goPost(d.id)" :title="d.title">
              <span class="t">{{ d.title }}</span>
            </li>
          </ul>
          <div v-else class="mine-empty">还没有收藏的帖子</div>
          <button v-if="favoriteDocs.length > 4" class="mine-more" @click="mineOpen.fav = !mineOpen.fav">
            {{ mineOpen.fav ? '收起' : '展开全部 ' + favoriteDocs.length + ' 条' }}
          </button>
        </div>

        <!-- 浏览历史：左下角组件，与上方点赞/评论/收藏交互一致，并和右侧卡片风格协调 -->
        <div class="mine-block" v-if="auth.loggedIn">
          <div class="mine-hd">浏览历史 <span class="cnt">🕘 {{ history.items.length }}</span></div>
          <ul v-if="recentHistory.length" class="mine-list" :class="{ open: historyOpen }">
            <li v-for="h in recentHistory" :key="h.id" @click="goPost(h.id)" :title="h.title">
              <span class="t">{{ h.title }}</span>
            </li>
          </ul>
          <div v-else class="mine-empty">还没有浏览记录</div>
          <button v-if="history.items.length > 4" class="mine-more" @click="historyOpen = !historyOpen">
            {{ historyOpen ? '收起' : '展开全部 ' + history.items.length + ' 条' }}
          </button>
          <button v-if="history.items.length" class="mine-more link" @click="router.push('/history')">查看完整历史 →</button>
        </div>
      </aside>

      <!-- 右侧主区 -->
      <main class="profile-main">
        <!-- 资料编辑 -->
        <section class="card">
          <div class="card-hd">
            <span class="card-title">账号资料</span>
            <span class="card-sub">完善信息，让大家更了解你</span>
          </div>
          <el-form label-width="72px" class="form">
            <el-form-item label="用户名">
              <span class="ro">{{ auth.user?.username }}</span>
            </el-form-item>
            <el-form-item label="昵称">
              <el-input v-model="displayName" placeholder="显示名称" />
            </el-form-item>
            <el-form-item label="邮箱">
              <template v-if="emailVerified">
                <div style="display:flex;gap:8px;align-items:center">
                  <el-tag type="success" size="small" effect="light">已绑定</el-tag>
                  <span>{{ email || auth.user?.email }}</span>
                  <el-button text type="danger" size="small" :loading="unbinding" @click="unbindEmail">解绑</el-button>
                </div>
              </template>
              <template v-else>
                <div>
                  <div style="display:flex;gap:8px;margin-bottom:8px">
                    <el-input v-model="email" placeholder="输入邮箱后获取验证码" style="flex:1" :disabled="sendingCode" />
                    <el-button :loading="sendingCode" :disabled="!email" @click="sendCode">{{ codeSent ? '重新发送' : '获取验证码' }}</el-button>
                  </div>
                  <div v-if="codeSent" style="display:flex;gap:8px;margin-bottom:8px">
                    <el-input v-model="code" placeholder="请输入收到的 6 位验证码" style="flex:1" maxlength="6" />
                    <el-button type="primary" :loading="verifying" :disabled="!code" @click="verifyEmail">绑定</el-button>
                  </div>
                  <p v-if="!mailConfigured" style="font-size:12px;color:#e37318;margin:2px 0 0">管理员尚未配置邮件服务，暂无法绑定</p>
                  <p v-else style="font-size:12px;color:#86909c;margin:2px 0 0">绑定后可用于接收密码重置链接与通知邮件</p>
                </div>
              </template>
            </el-form-item>
            <el-form-item label="邮件通知">
              <div style="display:flex;gap:8px;align-items:center">
                <el-switch v-model="emailNotifyEnabled" :disabled="!emailVerified" @change="saveNotify" />
                <span style="font-size:12px;color:#86909c">开启后站内通知同步发到邮箱（需先绑定邮箱）</span>
              </div>
            </el-form-item>
            <el-form-item label="AI 模型">
              <el-button text type="primary" @click="router.push('/my-ai-models')">管理我的 AI 模型</el-button>
              <span style="font-size:12px;color:#86909c;margin-left:8px">配置自己的模型，与管理员模型互不影响</span>
            </el-form-item>
            <el-form-item label="简介">
              <el-input v-model="bio" type="textarea" :rows="3" maxlength="200" show-word-limit
                        placeholder="一句话介绍自己…" />
            </el-form-item>
            <el-form-item label="性别">
              <el-select v-model="gender" placeholder="不填写" style="width: 160px">
                <el-option label="男" value="男" />
                <el-option label="女" value="女" />
                <el-option label="保密" value="保密" />
                <el-option label="不填写" value="" />
              </el-select>
              <span class="gender-tip">选「保密」即不对外公开性别</span>
            </el-form-item>
            <!-- 头像待审持久提示（比一次性 Message 更可靠，刷新页面仍在） -->
            <el-alert v-if="avatarPending" type="warning" :closable="false" show-icon
                      title="您的新头像正在审核中"
                      description="审核通过前，对外仍显示当前头像；无需重复上传，审核结果会通过站内通知告知您。"
                      class="avatar-pending-alert" />
            <el-form-item label="头像">
              <div class="avatar-edit">
                <div class="av-prev-wrap">
                  <img v-if="preview" :src="preview" class="av-prev" alt="" />
                  <span v-else class="av-prev av-initial">{{ initial }}</span>
                  <span v-if="avatarPending" class="av-pending-tag">审核中</span>
                </div>
                <div class="av-actions">
                  <label class="av-up">
                    上传图片
                    <input ref="fileInput2" type="file" accept="image/*" hidden @change="onFile" />
                  </label>
                  <el-button text size="small" @click="resetAvatar">重置</el-button>
                  <el-button text size="small" type="primary" @click="showAvatarPicker = !showAvatarPicker">
                    {{ showAvatarPicker ? '收起' : '选择默认头像' }}
                  </el-button>
                </div>
              </div>
              <div v-if="showAvatarPicker" class="av-grid">
                <button v-for="a in DEFAULT_AVATARS" :key="a.url" type="button"
                        class="av-opt" :class="{ on: avatar === a.url }" :title="a.label"
                        @click="selectDefaultAvatar(a.url)">
                  <img :src="a.url" alt="" />
                </button>
              </div>
            </el-form-item>
          </el-form>
          <div class="footer">
            <el-button text type="info" @click="resetAvatar">重置头像</el-button>
            <el-button text type="warning" @click="pwdDialog = true">修改密码</el-button>
            <!-- 隐私政策「你的权利」：自助导出个人数据（JSON） -->
            <el-button text type="info" :loading="exporting" @click="doExport">导出我的数据</el-button>
            <span class="spacer" />
            <el-button @click="goBack">返回</el-button>
            <el-button type="primary" :loading="saving" @click="save">保存修改</el-button>
          </div>
        </section>

        <!-- 我的帖子 -->
        <section class="card">
          <div class="card-hd">
            <span class="card-title">我的帖子</span>
            <span class="card-sub">最近发布的内容</span>
          </div>
          <div v-if="loadingStats" class="empty">加载中…</div>
          <div v-else-if="recentDocs.length === 0" class="empty">
            还没有帖子，去 <a @click="goBack">写第一篇</a> 吧～
          </div>
          <ul v-else class="doc-list">
            <li v-for="d in recentDocs" :key="d.id" @click="goEdit(d.id)">
              <div class="doc-main">
                <div class="doc-title">{{ d.title }}</div>
                <div class="doc-meta">
                  <span class="cat" v-if="d.category">{{ d.category }}</span>
                  <el-tag :type="statusMeta[d.status]?.type || 'info'" size="small" effect="plain">
                    {{ statusMeta[d.status]?.text || d.status }}
                  </el-tag>
                  <span class="time">{{ fmtDate(d.updatedAt) }}</span>
                </div>
              </div>
              <span class="arrow">›</span>
            </li>
          </ul>
        </section>

        <!-- 我的结业证书 -->
        <section class="card" v-if="certs.length">
          <div class="card-hd">
            <span class="card-title">🏆 我的结业证书</span>
            <span class="card-sub">完成课程全部章节并通过结业考试获得</span>
          </div>
          <ul class="cert-list">
            <li v-for="c in certs" :key="c.courseId" @click="viewCert(c)">
              <div class="cert-main">
                <div class="cert-title">{{ c.courseTitle }}</div>
                <div class="cert-meta">
                  <el-tag size="small" type="success" effect="plain">{{ c.score }} 分</el-tag>
                  <span class="cert-no">{{ c.certNo }}</span>
                  <span class="cert-time">{{ fmtDate(c.issuedAt) }}</span>
                </div>
              </div>
              <span class="cert-view">查看 ›</span>
            </li>
          </ul>
        </section>

        <!-- 账号概况 -->
        <section class="card">
          <div class="card-hd">
            <span class="card-title">账号概况</span>
          </div>
          <ul class="overview">
            <li><span>用户角色</span><b>{{ auth.isAdmin ? '管理员' : '普通用户' }}</b></li>
            <li><span>当前等级</span><b>Lv{{ levelInfo.level }} · {{ levelInfo.title }}</b></li>
            <li><span>累计积分</span><b>{{ auth.user?.points || 0 }}</b></li>
            <li><span>注册时间</span><b>{{ fmtDate(auth.user?.createdAt) }}</b></li>
            <li v-if="memberDays"><span>加入时长</span><b>{{ memberDays }} 天</b></li>
            <li><span>帖子总数</span><b>{{ myDocs.length }}</b></li>
          </ul>
        </section>

        <!-- 我的勋章 -->
        <section class="card">
          <div class="card-hd">
            <span class="card-title">🏅 我的勋章</span>
            <span class="card-sub">由你的积分 / 文章 / 获赞等数据自动解锁</span>
            <button v-if="myBadges.length" class="card-link" type="button" @click="router.push('/badges')">
              查看全部 →
            </button>
          </div>
          <div v-if="loadingBadges" class="empty">加载中…</div>
          <div v-else-if="myBadges.length === 0" class="empty">还没有勋章，多发布优质内容即可解锁～</div>
          <div v-else class="badge-strip">
            <div v-for="b in myBadges" :key="b.id" class="b-chip" :title="b.desc"
                 :style="{ '--c1': g0(b), '--c2': g1(b) }">
              <span class="b-ic"><component :is="BADGE_ICONS[b.id] || DEFAULT_BADGE_ICON" /></span>
              <span class="b-name">{{ b.name }}</span>
            </div>
          </div>
        </section>

        <!-- 积分明细 -->
        <section class="card">
          <div class="card-hd">
            <span class="card-title">积分明细</span>
            <span class="card-sub">积分的每一笔增减记录</span>
            <button v-if="logTotal > 10" class="card-link" type="button" @click="router.push('/points/log')">
              查看完整明细 →
            </button>
          </div>
          <div v-if="loadingLog" class="empty">加载中…</div>
          <div v-else-if="logs.length === 0" class="empty">暂无积分变动记录</div>
          <ul v-else class="plog">
            <li v-for="l in logs" :key="l.id">
              <span class="pl-type">{{ typeText(l.type) }}</span>
              <span class="pl-reason">{{ l.reason || '—' }}</span>
              <span class="pl-delta" :class="l.delta >= 0 ? 'plus' : 'minus'">
                {{ l.delta >= 0 ? '+' : '' }}{{ l.delta }}
              </span>
              <span class="pl-bal">余 {{ l.balance }}</span>
              <span class="pl-time">{{ fmtDate(l.createdAt) }}</span>
            </li>
          </ul>
        </section>
      </main>
    </div>

    <el-dialog v-model="pwdDialog" title="修改密码" width="420px">
      <el-form label-width="84px">
        <el-form-item label="原密码">
          <el-input v-model="oldPwd" type="password" show-password placeholder="请输入当前密码" />
        </el-form-item>
        <el-form-item label="新密码">
          <el-input v-model="newPwd" type="password" show-password placeholder="至少 6 位" />
        </el-form-item>
        <el-form-item label="确认新密码">
          <el-input v-model="newPwd2" type="password" show-password placeholder="再次输入新密码" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="pwdDialog = false">取消</el-button>
        <el-button type="primary" :loading="changing" @click="changePwd">确定修改</el-button>
      </template>
    </el-dialog>

    <!-- 结业证书弹窗 -->
    <CertDialog :visible="certVisible" :cert="activeCert" @update:visible="certVisible = $event" />
  </div>
</template>

<style scoped>
.profile-page { max-width: 920px; margin: 0 auto; }
.cover {
  height: 170px; border-radius: 16px 16px 0 0;
  background: url('../assets/profile-cover.jpg') no-repeat top center / cover;
  position: relative;
}
/* 自定义背景：裁剪只在 .cover-bg 内做（.cover 不能 overflow:hidden，否则骑边头像被裁半截） */
.cover-bg { position: absolute; inset: 0; overflow: hidden; border-radius: 16px 16px 0 0; }
/* 双层适配：底层同图模糊铺满兜底，上层原图 contain 完整显示——任何比例不裁切不变形 */
.cover-blur { position: absolute; inset: -30px; background: center / cover no-repeat; filter: blur(22px) brightness(1.06); }
.cover-fit { position: absolute; inset: 0; width: 100%; height: 100%; object-fit: contain; }
.cover.has-bg { background: #eef2f7; }
.cover-avatar {
  position: absolute; left: 28px; bottom: -42px;
  width: 96px; height: 96px; border-radius: 50%;
  background: #fff; padding: 4px; box-shadow: 0 6px 20px rgba(0,0,0,.18);
}
.cover-avatar img, .cover-avatar span {
  width: 100%; height: 100%; border-radius: 50%; object-fit: cover;
  display: flex; align-items: center; justify-content: center;
  font-size: 36px; font-weight: 700; color: #6366f1; background: #eef2ff;
}
.cam {
  position: absolute; right: -2px; bottom: 2px; width: 28px; height: 28px;
  border-radius: 50%; background: #6366f1; color: #fff; cursor: pointer;
  display: flex; align-items: center; justify-content: center; font-size: 14px;
  border: 2px solid #fff;
}
.body { display: grid; grid-template-columns: 280px 1fr; gap: 18px; margin-top: 58px; }
.side {
  background: #fff; border: 1px solid #eef2f7; border-radius: 16px;
  padding: 18px; align-self: stretch;
}
.interact { display: flex; gap: 8px; margin: 14px 0; }
.interact .ic { flex: 1; background: #f8fafc; border: 1px solid #eef2f7; border-radius: 12px; padding: 10px 4px; text-align: center; }
.interact .n { font-size: 15px; font-weight: 700; color: #6366f1; }
.interact .l { font-size: 12px; color: #64748b; margin-top: 2px; }
.name { font-size: 19px; font-weight: 700; color: #0f172a; display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.name-badge { margin-left: 2px; }
.username { color: #94a3b8; font-size: 13px; margin-top: 2px; }
.role-row { display: flex; gap: 8px; margin: 12px 0; flex-wrap: wrap; }
.bio { font-size: 13px; color: #475569; line-height: 1.6; margin: 10px 0; }
.bio.muted { color: #cbd5e1; }
.meta { list-style: none; padding: 0; margin: 12px 0; border-top: 1px dashed #eef2f7; padding-top: 12px; }
.meta li { display: flex; justify-content: space-between; font-size: 13px; color: #64748b; padding: 4px 0; }
.meta li b { color: #334155; font-weight: 600; }
.stats { display: flex; gap: 10px; margin: 12px 0; }
.stat { flex: 1; background: #f8fafc; border: 1px solid #eef2f7; border-radius: 12px; padding: 12px 6px; text-align: center; }
.stat .num { font-size: 20px; font-weight: 700; color: #6366f1; }
.stat .lbl { font-size: 12px; color: #64748b; margin-top: 2px; }
/* 用 grid 的 1fr 单列 + stretch 强制两个按钮等宽（比 flex 更可靠），
   并清掉 Element Plus 相邻按钮的 margin-left，避免第二个按钮被右推/错位 */
.side-actions { display: grid; grid-template-columns: 1fr; gap: 8px; margin-top: 6px; }
.side-actions .go-posts { width: 100%; margin: 0; }
.side-actions .go-posts + .go-posts { margin-left: 0; }
/* 用 :deep 穿透 scoped 改 Element Plus 内部 .el-button__content 为两端对齐：
   文字一律靠左、箭头 → 一律靠右，无论文字长短箭头都竖向对齐 */
.side-actions .go-posts :deep(.el-button__content) {
  display: flex;
  justify-content: space-between;
  align-items: center;
  width: 100%;
}

/* 默认头像选择器 */
.avatar-edit { display: flex; align-items: center; gap: 12px; }
.av-prev { width: 48px; height: 48px; border-radius: 50%; object-fit: cover; border: 1px solid #eef2f7; }
.av-prev-wrap { position: relative; flex: none; width: 48px; }
.av-pending-tag {
  position: absolute; bottom: -6px; left: 50%; transform: translateX(-50%);
  white-space: nowrap; font-size: 10px; line-height: 1; padding: 3px 6px;
  border-radius: 999px; color: #854F0B; background: #fef3c7; border: 1px solid #fcd34d;
}
.avatar-pending-alert { margin-bottom: 14px; }
html.dark .avatar-pending-alert { --el-alert-bg-color: #2a2312; }
html.dark .cover.has-bg { background: #1e2430; }
.av-prev.av-initial { display: flex; align-items: center; justify-content: center; font-size: 20px; font-weight: 700; color: #6366f1; background: #eef2ff; }
.av-actions { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.av-up { font-size: 13px; color: #6366f1; cursor: pointer; background: #eef2ff; padding: 6px 12px; border-radius: 8px; }
.av-grid { display: grid; grid-template-columns: repeat(8, 1fr); gap: 8px; justify-content: start; margin-top: 12px; }
.av-opt { padding: 0; border: 2px solid transparent; border-radius: 50%; background: #f8fafc; cursor: pointer; overflow: hidden; aspect-ratio: 1 / 1; }
.av-opt img { width: 100%; height: 100%; display: block; }
.av-opt:hover { border-color: #c7d2fe; }
.av-opt.on { border-color: #6366f1; }

/* 左下角：点赞 / 评论 列表 */
.mine-block { margin-top: 16px; background: #fff; border: 1px solid #eef2f7; border-radius: 14px; padding: 14px; }
.mine-hd { font-size: 13px; font-weight: 700; color: #334155; display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
.mine-hd .cnt { font-weight: 600; color: #94a3b8; font-size: 12px; }
.mine-list { list-style: none; padding: 0; margin: 0; }
.mine-list li { display: flex; align-items: center; gap: 6px; padding: 7px 0; border-top: 1px dashed #f1f5f9; cursor: pointer; }
.mine-list li:first-child { border-top: none; }
.mine-list li:hover .t { color: #6366f1; }
.mine-list .t { flex: 1; font-size: 13px; color: #475569; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
/* 展开后限高滚动，避免侧栏被超长列表无限拉长 */
.mine-list.open { max-height: 220px; overflow: auto; }
.mine-more {
  margin-top: 8px; width: 100%; border: none; background: #f8fafc; cursor: pointer;
  font-size: 12px; color: #6366f1; padding: 6px 0; border-radius: 8px;
}
.mine-more:hover { background: #eef2ff; }
.mine-more.link { background: transparent; color: #6366f1; font-weight: 600; }
.mine-more.link:hover { background: #eef2ff; }
.mine-empty { font-size: 12px; color: #cbd5e1; padding: 4px 0; }

.lv-card { background: #f7f9fc; border: 1px solid #e7e9ee; border-radius: 8px; padding: 14px; margin: 14px 0; }
.lv-top { display: flex; align-items: center; gap: 8px; margin-bottom: 10px; }
.lv-badge { background: #6366f1; color: #fff; font-weight: 700; font-size: 13px; padding: 1px 8px; border-radius: 8px; }
.lv-title { font-size: 14px; font-weight: 700; color: #4f46e5; }
.lv-points { margin-left: auto; font-size: 13px; color: #64748b; font-weight: 600; }
.lv-tip { font-size: 12px; color: #94a3b8; margin-top: 8px; }

/* ⚠️ 不要叫 .main：App.vue 骨架有全局 .main{padding:20px ...}，会把整列往下推 20px，
   导致左侧资料卡与右侧第一张卡顶部不齐（实测 355 vs 375）。 */
.profile-main { display: flex; flex-direction: column; gap: 18px; }
.card { background: #fff; border: 1px solid #eef2f7; border-radius: 16px; padding: 18px 20px; }
.card-hd { display: flex; align-items: baseline; gap: 10px; margin-bottom: 14px; }
.card-title { font-size: 16px; font-weight: 700; color: #0f172a; }
.card-sub { font-size: 12px; color: #94a3b8; }
.card-link {
  margin-left: auto; border: none; background: none; cursor: pointer;
  font-size: 13px; color: #6366f1; font-weight: 600; padding: 0;
}
.card-link:hover { color: #4f46e5; text-decoration: underline; }
.ro { color: #475569; }
.gender-tip { margin-left: 16px; font-size: 12px; color: #94a3b8; }
.form { margin-top: 0; }
.footer { display: flex; align-items: center; gap: 12px; margin-top: 6px; }
.footer .spacer { flex: 1; }

.empty { color: #94a3b8; font-size: 14px; padding: 10px 0; }
.empty a { color: #6366f1; cursor: pointer; }
.doc-list { list-style: none; padding: 0; margin: 0; }
.doc-list li {
  display: flex; align-items: center; justify-content: space-between;
  padding: 12px 4px; border-bottom: 1px solid #f1f5f9; cursor: pointer;
}
.doc-list li:last-child { border-bottom: none; }
.doc-list li:hover { background: #fafbff; }
.doc-title { font-size: 15px; font-weight: 600; color: #1e293b; }
.doc-meta { display: flex; align-items: center; gap: 8px; margin-top: 6px; }
.cat { font-size: 12px; color: #6366f1; background: #eef2ff; padding: 1px 8px; border-radius: 6px; }
.time { font-size: 12px; color: #94a3b8; }
.arrow { color: #cbd5e1; font-size: 22px; }

.overview { list-style: none; padding: 0; margin: 0; }
.overview li { display: flex; justify-content: space-between; padding: 9px 0; border-bottom: 1px solid #f1f5f9; font-size: 14px; }
.overview li:last-child { border-bottom: none; }
.overview li span { color: #64748b; }
.overview li b { color: #334155; font-weight: 600; }
.overview .ok { color: #16a34a !important; }

/* 积分明细 */
.plog { list-style: none; padding: 0; margin: 0; }

/* 我的结业证书 */
.cert-list { list-style: none; padding: 0; margin: 0; }

/* 我的勋章：已获徽章横向 chips */
.badge-strip { display: flex; flex-wrap: wrap; gap: 10px; }
.b-chip {
  display: inline-flex; align-items: center; gap: 7px;
  padding: 5px 12px 5px 5px; border-radius: 999px;
  background: color-mix(in srgb, var(--c1, #6366f1) 12%, #fff);
  border: 1px solid color-mix(in srgb, var(--c1, #6366f1) 38%, #fff);
}
.b-ic {
  width: 22px; height: 22px; border-radius: 50%; flex: none;
  display: flex; align-items: center; justify-content: center;
  background: linear-gradient(135deg, var(--c1, #6366f1), var(--c2, #8b5cf6));
  color: #fff;
}
.b-ic :deep(svg) { width: 13px; height: 13px; }
.b-chip .b-name { font-size: 13px; font-weight: 700; color: #334155; }
html.dark .b-chip { background: color-mix(in srgb, var(--c1, #6366f1) 18%, #1b1b26); border-color: color-mix(in srgb, var(--c1, #6366f1) 45%, #2c2c3a); }
html.dark .b-chip .b-name { color: #cbd5e1; }
.cert-list li {
  display: flex; align-items: center; justify-content: space-between;
  padding: 12px 4px; border-bottom: 1px solid #f1f5f9; cursor: pointer;
}
.cert-list li:last-child { border-bottom: none; }
.cert-list li:hover { background: #fafbff; }
.cert-list li:hover .cert-view { color: #4f46e5; }
.cert-title { font-size: 15px; font-weight: 600; color: #1e293b; }
.cert-meta { display: flex; align-items: center; gap: 8px; margin-top: 6px; }
.cert-no { font-size: 12px; color: #94a3b8; font-family: ui-monospace, Consolas, monospace; }
.cert-time { font-size: 12px; color: #94a3b8; }
.cert-view { font-size: 13px; color: #6366f1; font-weight: 600; flex: none; }.plog li {
  display: flex; align-items: center; gap: 10px;
  padding: 10px 4px; border-bottom: 1px solid #f1f5f9; font-size: 13px;
}
.plog li:last-child { border-bottom: none; }
.pl-type {
  flex: none; width: 76px; text-align: center; font-size: 12px; font-weight: 600;
  color: #4f46e5; background: #eef2ff; border-radius: 6px; padding: 2px 0;
}
.pl-reason { flex: 1; color: #475569; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.pl-delta { flex: none; font-weight: 700; font-variant-numeric: tabular-nums; }
.pl-delta.plus { color: #16a34a; }
.pl-delta.minus { color: #ef4444; }
.pl-bal { flex: none; color: #94a3b8; }
.pl-time { flex: none; color: #cbd5e1; font-size: 12px; }
.log-more { text-align: center; margin-top: 6px; }

/* 暗色模式适配：theme.css 已覆盖 .side/.name/.bio/.meta/.stat/.card/.overview/.pl-* 等，
   此处补它未覆盖的：头像选择器（浅底圆/边框）、性别提示、证书编号与时间 */
html.dark .av-prev { border-color: #2a2a35; }
html.dark .av-opt { background: #23232e; }
html.dark .gender-tip { color: #8b8fa0; }
html.dark .cert-no { color: #8b8fa0; }
html.dark .cert-time { color: #8b8fa0; }

/* ============================================================
   响应式：≤1024 单列（主内容在上，侧边资料移到下方）
          ≤ 640 手机：头像居中、内边距与字号收紧
   ============================================================ */
@media (max-width: 1024px) {
  .body { grid-template-columns: 1fr; margin-top: 54px; }
  .profile-main { order: 1; }
  .side { order: 2; }
  /* 侧栏落到下方后列表可以放宽限高 */
  .mine-list.open { max-height: 260px; }
}

@media (max-width: 640px) {
  .cover { height: 140px; }
  .cover-avatar { left: 50%; transform: translateX(-50%); width: 80px; height: 80px; }
  .body { margin-top: 48px; gap: 14px; }
  .side { padding: 14px; }
  .card { padding: 14px; }
  .name { font-size: 17px; }
  .card-hd { flex-wrap: wrap; gap: 6px; }
  .card-title { font-size: 15px; }
  .card-link { margin-left: 0; width: 100%; text-align: left; }
  .footer { flex-wrap: wrap; gap: 8px; }
  .footer .spacer { flex-basis: 100%; }
  .gender-tip { display: block; margin-left: 0; margin-top: 4px; }
  .av-grid { grid-template-columns: repeat(auto-fill, minmax(48px, 1fr)); gap: 6px; }
  .doc-title, .cert-title { font-size: 14px; }
  /* 积分明细行：时间换行到第二行，避免几列互相挤压 */
  .plog li { flex-wrap: wrap; row-gap: 2px; }
  .pl-time { flex-basis: 100%; }
}
</style>
