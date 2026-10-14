<script setup>
/**
 * 移动端「我的」Tab
 *
 * 参照主流 App 的个人中心：
 *   1. 顶部个人卡（头像 / 昵称 / 角色 / 积分）+ 浅色背景，不做整屏头图（手机小屏信息优先）
 *   2. 数据条（积分 / 文档 / 空间）
 *   3. 分组列表：内容管理 / 常用工具 / 设置 / 退出
 */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { useAuthStore } from '../../store/auth'
import { listMyShares, getStorage } from '../../api/docShare'
import { pendingCount } from '../../utils/offlineStore'
import { applyTheme } from '../../utils/theme'
import { resolveUrl } from '../../utils/runtimeBase'
import SiteFooter from '../../components/SiteFooter.vue'

const router = useRouter()
const auth = useAuthStore()

const spaceCount = ref(0)
const docCount = ref(0)
const storage = ref(null)
const pending = ref(0)
const dark = ref(document.documentElement.classList.contains('dark'))

const name = computed(() => auth.user?.displayName || auth.user?.username || '未登录')
const roleText = computed(() => (auth.isAdmin ? '管理员' : '普通用户'))

function fmtSize(n) {
  if (n == null) return '—'
  const u = ['B', 'KB', 'MB', 'GB']
  let i = 0, v = Number(n)
  while (v >= 1024 && i < u.length - 1) { v /= 1024; i++ }
  return (i === 0 ? v : v.toFixed(1)) + u[i]
}

onMounted(async () => {
  try {
    const { data } = await listMyShares()
    const list = Array.isArray(data) ? data : []
    spaceCount.value = list.length
    // 各空间文档数求和（并发，最多取前 8 个空间，避免请求过多）
    const counts = await Promise.all(
      list.slice(0, 8).map((s) =>
        import('../../api/docShare').then((m) =>
          m.listShareDocs(s.id).then((r) => (Array.isArray(r.data) ? r.data.length : 0)).catch(() => 0)
        )
      )
    )
    docCount.value = counts.reduce((a, b) => a + b, 0)
  } catch { /* 离线忽略 */ }
  getStorage().then((r) => { storage.value = r.data }).catch(() => {})
  pending.value = await pendingCount().catch(() => 0)
})

function toggleTheme() {
  const next = !dark.value
  applyTheme(next)
  dark.value = next
}

function go(p) { router.push(p) }

async function logout() {
  try {
    await ElMessageBox.confirm('确定要退出登录吗？', '提示', {
      type: 'warning', confirmButtonText: '退出', cancelButtonText: '取消'
    })
  } catch { return }
  auth.logout()
  router.replace('/login')
}

const MY_GROUP = {
  title: '我的内容',
  items: [
    { label: '我的文档', icon: 'FolderOpened', color: 'blue', to: '/docs' },
    { label: '我的收藏', icon: 'Star', color: 'orange', to: '/favorites' },
    { label: '我的订阅', icon: 'Bell', color: 'blue', to: '/subscriptions' },
    { label: '离线内容', icon: 'Cloudy', color: 'blue', to: '/offline' },
    { label: '浏览历史', icon: 'Clock', color: 'green', to: '/history' },
    { label: '回收站', icon: 'Delete', color: 'gray', to: '/recycle' },
    { label: '待同步文档', icon: 'Upload', color: 'orange', to: '/docs', badge: 'pending' }
  ]
}
const GROWTH_GROUP = {
  title: '我的成长',
  items: [
    { label: '积分明细', icon: 'Coin', color: 'orange', to: '/points' },
    { label: '徽章成就', icon: 'Medal', color: 'red', to: '/badges' },
    { label: '积分排行', icon: 'Trophy', color: 'orange', to: '/ranking' }
  ]
}
const TOOL_GROUP = {
  title: '工具',
  items: [
    { label: '搜索', icon: 'Search', color: '', to: '/search' },
    { label: '我的文件', icon: 'FolderOpened', color: 'blue', to: '/files' },
    { label: 'AI 问答', icon: 'ChatDotRound', color: 'green', to: '/ai' },
    { label: '发现文章', icon: 'Compass', color: 'blue', to: '/discover' },
    { label: '我的数据', icon: 'TrendCharts', color: 'green', to: '/stats' }
  ]
}
const SETTING_GROUP = {
  title: '设置',
  items: [
    { label: '账号与资料', icon: 'User', color: '', to: '/settings' },
    { label: '隐私政策', icon: 'Document', color: '', to: '/privacy' },
    { label: '关于', icon: 'InfoFilled', color: '', to: '/about' },
    { label: '外观设置', icon: 'Setting', color: 'gray', action: 'theme' },
    { label: '主题与字号', icon: 'Brush', color: 'gray', to: '/appearance' },
    { label: '应用锁', icon: 'Lock', color: 'gray', to: '/lock' }
  ]
}

// ---- 管理入口（App 端只保留「常用 + 核心维护」）----
// 取舍依据（2026-10-11 用户反馈「管理页面不用这么多在 app 上」）：
//   App 上只留「日常要点的」和「站点级核心配置」，其余重表格/低频管理页在桌面端用。
//   被移出 App 的入口见 HIDDEN_IN_APP —— 需要恢复时把它挪回 ADMIN_ITEMS 即可（MobileEmbed 的 CONF 仍保留全部映射）。
const ADMIN_ITEMS = [
  // 常用：日常最常看的
  { label: '数据看板', icon: 'DataLine', color: 'blue', perm: 'STATS_VIEW', to: '/embed/adminstats' },
  { label: '内容管理', icon: 'Files', color: 'orange', perm: 'POST_MANAGE', to: '/manage/docs' },
  { label: '文章审核', icon: 'CircleCheckFilled', color: 'green', perm: 'DOC_REVIEW', to: '/manage/review' },
  { label: '公告管理', icon: 'Bell', color: 'red', perm: 'ANNOUNCEMENT_MANAGE', to: '/manage/announce' },
  { label: '用户管理', icon: 'UserFilled', color: 'blue', perm: 'USER_MANAGE', to: '/manage/users' },
  { label: '角色管理', icon: 'Avatar', color: 'blue', perm: 'USER_MANAGE', to: '/manage/roles' },
  { label: 'AI 模型管理', icon: 'MagicStick', color: 'green', perm: 'AI_MANAGE', to: '/manage/ai' }
]

// 核心维护（CORE_MAINTAIN）：站点级配置，改完立刻影响全站，属于"必须能在手机上应急改"的一类
const MAINTAIN_ITEMS = [
  { label: '页脚设置', icon: 'Document', color: 'gray', perm: 'CORE_MAINTAIN', to: '/embed/adminfooter' },
  { label: '功能开关', icon: 'Setting', color: 'gray', perm: 'CORE_MAINTAIN', to: '/embed/adminmaintain' },
  { label: '外观设置', icon: 'Brush', color: 'gray', perm: 'CORE_MAINTAIN', to: '/embed/adminappearance' },
  { label: '水印设置', icon: 'EditPen', color: 'gray', perm: 'CORE_MAINTAIN', to: '/embed/adminwatermark' },
  { label: '登录页二维码', icon: 'Camera', color: 'gray', perm: 'CORE_MAINTAIN', to: '/embed/adminloginqr' }
]

// App 端不再展示的管理入口（桌面端照旧可用）。保留清单便于日后按需恢复。
const HIDDEN_IN_APP = [
  '培训管理(COURSE_MANAGE)', '考试管理(EXAM_MANAGE)', '分类标签(TAXONOMY_MANAGE)',
  '黑名单(BLACKLIST_MANAGE)', '资产管理(ASSET_MANAGE)', '违禁词管理(BANNED_WORDS_MANAGE)',
  '共享文档管理(SHARE_VIEW)', '操作审计(AUDIT_VIEW)', '登录日志(LOGIN_LOG_VIEW)',
  '附件管理(ADMIN_VIEW)', '检索测试(ADMIN_VIEW)'
]

const open = ref({})   // 折叠区展开状态（按标题存）
const byPerm = (list) => (auth.isAdmin ? list : list.filter((it) => auth.hasPerm(it.perm)))
const adminItems = computed(() => byPerm(ADMIN_ITEMS))
const maintainItems = computed(() => byPerm(MAINTAIN_ITEMS))

// 分组顺序：我的内容 / 工具 / 管理（折叠）/ 核心维护（折叠）/ 设置
const groups = computed(() => {
  const list = [MY_GROUP, GROWTH_GROUP, TOOL_GROUP]
  if (adminItems.value.length) list.push({ title: '管理', collapsible: true, items: adminItems.value, hint: '日常常用' })
  if (maintainItems.value.length) list.push({ title: '核心维护', collapsible: true, items: maintainItems.value, hint: '站点配置' })
  list.push(SETTING_GROUP)
  return list
})
</script>

<template>
  <div class="me">
    <!-- 个人卡 -->
    <div class="prof" @click="go('/settings')">
      <div class="prof-avatar">
        <img v-if="auth.user?.avatar" :src="resolveUrl(auth.user.avatar)" alt="" />
        <span v-else>{{ name.charAt(0) }}</span>
      </div>
      <div class="prof-body">
        <div class="prof-name">{{ name }}</div>
        <div class="prof-meta">
          <span class="m-chip" :class="auth.isAdmin ? 'orange' : 'gray'">{{ roleText }}</span>
          <span class="prof-id">@{{ auth.user?.username }}</span>
        </div>
      </div>
      <el-icon class="prof-arrow"><Right /></el-icon>
    </div>

    <!-- 数据条 -->
    <div class="body">
      <div class="stats">
        <div class="stat">
          <div class="st-num">{{ auth.user?.points || 0 }}</div>
          <div class="st-lbl">积分</div>
        </div>
        <div class="stat">
          <div class="st-num">{{ docCount }}</div>
          <div class="st-lbl">文档</div>
        </div>
        <div class="stat">
          <div class="st-num">{{ spaceCount }}</div>
          <div class="st-lbl">空间</div>
        </div>
        <div class="stat">
          <div class="st-num">{{ fmtSize(storage?.used) }}</div>
          <div class="st-lbl">已用存储</div>
        </div>
      </div>
    </div>

    <!-- 分组列表（管理组是折叠区：收起只占一行，且位置在设置组之前、远离退出登录按钮） -->
    <div v-for="g in groups" :key="g.title" class="body">
      <template v-if="g.collapsible">
        <button class="m-sec-title m-sec-btn" type="button" @click="open[g.title] = !open[g.title]">
          <span>{{ g.title }}</span>
          <span v-if="g.hint" class="m-sec-hint">{{ g.hint }}</span>
          <span class="m-sec-count">{{ g.items.length }}</span>
          <el-icon class="sec-arrow" :class="{ open: open[g.title] }"><Right /></el-icon>
        </button>
        <div v-show="open[g.title]" class="m-card">
          <button v-for="it in g.items" :key="it.label" class="m-row" type="button" @click="go(it.to)">
            <span class="m-ico" :class="it.color"><el-icon><component :is="it.icon" /></el-icon></span>
            <span class="m-row-body"><span class="m-row-title">{{ it.label }}</span></span>
            <el-icon class="m-row-extra"><Right /></el-icon>
          </button>
        </div>
      </template>
      <template v-else>
        <div class="m-sec-title">{{ g.title }}</div>
        <div class="m-card">
          <button v-for="it in g.items" :key="it.label" class="m-row" type="button"
                  @click="it.action === 'theme' ? toggleTheme() : go(it.to)">
            <span class="m-ico" :class="it.color"><el-icon><component :is="it.icon" /></el-icon></span>
            <span class="m-row-body"><span class="m-row-title">{{ it.label }}</span></span>
            <span v-if="it.badge === 'pending' && pending" class="m-chip orange">{{ pending }}</span>
            <span v-else-if="it.action === 'theme'" class="m-row-extra">
              {{ dark ? '夜间' : '日间' }}
              <el-icon><component :is="dark ? 'Moon' : 'Sunny'" /></el-icon>
            </span>
            <el-icon v-else class="m-row-extra"><Right /></el-icon>
          </button>
        </div>
      </template>
    </div>

    <div class="body">
      <button class="logout" @click="logout">
        <el-icon><SwitchButton /></el-icon> 退出登录
      </button>
    </div>

    <!-- 备案信息 + 仓库 + 版本（合规要求底部可见） -->
    <SiteFooter compact :filing="false" />
    <div style="height:16px"></div>
  </div>
</template>

<style scoped>
.me { min-height: 100%; }
.body { padding: 0 16px; }

/* 「管理」折叠区标题行：mobile.css 的 <button> 全局重置会清掉排版，这里补齐 */
.m-sec-btn {
  display: flex; align-items: center; gap: 6px;
  width: 100%; text-align: left; padding: 4px 2px;
  background: transparent; border: none; cursor: pointer;
}
.m-sec-hint { font-size: 11.5px; color: var(--m-text-dim); font-weight: 400; margin-left: 6px; }
.m-sec-count {
  min-width: 18px; height: 18px; padding: 0 5px;
  border-radius: 9px; background: var(--m-primary); color: #fff;
  font-size: 11px; line-height: 18px; text-align: center;
}
.sec-arrow { margin-left: auto; transition: transform .2s; color: var(--m-text-dim, #98a2b3); }
.sec-arrow.open { transform: rotate(90deg); }

.prof {
  display: flex; align-items: center; gap: 14px;
  padding: calc(env(safe-area-inset-top) + 20px) 18px 22px;
  background: var(--m-primary);
  border-radius: 0 0 24px 24px;
  color: #fff; cursor: pointer;
}
html.dark .prof { background: var(--m-primary-deep); }
.prof-avatar {
  flex: none; width: 58px; height: 58px; border-radius: 50%;
  background: rgba(255, 255, 255, .22); color: #fff;
  border: 2px solid rgba(255, 255, 255, .45);
  display: inline-flex; align-items: center; justify-content: center;
  font-size: 23px; font-weight: 700; overflow: hidden;
}
.prof-avatar img { width: 100%; height: 100%; object-fit: cover; }
.prof-body { flex: 1 1 auto; min-width: 0; }
.prof-name { font-size: 20px; font-weight: 700; letter-spacing: -.2px; }
.prof-meta { display: flex; align-items: center; gap: 8px; margin-top: 7px; }
.prof-meta .m-chip { background: rgba(255, 255, 255, .22); color: #fff; }
.prof-id { font-size: 12.5px; opacity: .78; }
.prof-arrow { flex: none; opacity: .7; }

.stats {
  display: grid; grid-template-columns: repeat(4, 1fr);
  background: var(--m-card); border-radius: var(--m-radius);
  box-shadow: var(--m-shadow); padding: 15px 4px; margin-top: -12px;
  position: relative; z-index: 2;
}
.stat { text-align: center; }
.stat + .stat { border-left: 1px solid var(--m-border); }
.st-num { font-size: 16.5px; font-weight: 700; color: var(--m-text); line-height: 1.2; }
.st-lbl { font-size: 11.5px; color: var(--m-text-dim); margin-top: 3px; }

.logout {
  width: 100%; height: 50px; margin-top: 20px;
  border: none; border-radius: var(--m-radius);
  background: var(--m-card); color: var(--m-danger);
  box-shadow: var(--m-shadow);
  font-size: 15.5px; font-weight: 600; cursor: pointer;
  display: inline-flex; align-items: center; justify-content: center; gap: 8px;
}
.logout:active { transform: scale(.99); }

.ver { text-align: center; font-size: 12px; color: var(--m-text-dim); margin-top: 16px; }
</style>
