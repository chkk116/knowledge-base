<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore, permLabel } from './store/auth'
import { useNotificationStore } from './store/notification'
import { reader, closeReader, startDrag, startResize, clampToViewport } from './store/reader'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const notification = useNotificationStore()

// 启动时用本地令牌恢复会话；登录态则建立 SSE 实时通知长连接（替代路由切换轮询未读）
onMounted(async () => {
  await auth.fetchMe()
  if (auth.loggedIn) notification.connect()
})

// 登录态变化（登录 / 登出 / 令牌过期）时同步 SSE 连接与未读
watch(() => auth.loggedIn, (v) => {
  if (v) notification.connect()
  else { notification.disconnect(); notification.setUnread(0) }
})

// 浏览器窗口缩放 / 全屏切换时，把阅读浮窗夹回可视区内（避免被挤出屏幕外）
window.addEventListener('resize', clampToViewport)

// 令牌过期时由 axios 拦截器广播，自动退出
function onUnauthorized() {
  if (auth.loggedIn) {
    auth.logout()
    ElMessage.warning('登录已过期，请重新登录')
  }
}
window.addEventListener('kb-unauthorized', onUnauthorized)
onUnmounted(() => window.removeEventListener('kb-unauthorized', onUnauthorized))

// 登录页 / 独立阅读窗口：独立整页，不带侧边栏与顶栏
const barePage = computed(() => route.path === '/login' || route.path.startsWith('/reader/'))

// 未读通知数（顶栏铃铛红点，来自 SSE 实时推送，详见 store/notification）
function goNotifications() { router.push('/notifications') }

// 侧边菜单折叠/展开状态（持久化到 localStorage，刷新后保持）
const collapsed = ref(localStorage.getItem('kb_sidebar_collapsed') === '1')
function toggleSidebar() {
  collapsed.value = !collapsed.value
  localStorage.setItem('kb_sidebar_collapsed', collapsed.value ? '1' : '0')
}

// 夜间模式切换（B站风格：月亮/太阳 + 旋转淡入 + 过渡）
const isDark = ref(document.documentElement.classList.contains('dark'))
const spinning = ref(false)
function toggleTheme() {
  const html = document.documentElement
  const next = !html.classList.contains('dark')
  html.classList.add('theme-anim')           // 开启全局过渡
  html.classList.toggle('dark', next)         // 切换暗色
  isDark.value = next
  localStorage.setItem('kb_theme', next ? 'dark' : 'light')
  spinning.value = true                       // 图标旋转一次
  setTimeout(() => html.classList.remove('theme-anim'), 340)
  setTimeout(() => (spinning.value = false), 480)
}

// 菜单项：按角色个性化
//  - 所有登录用户：写文章、我的帖子
//  - 管理类按细粒度权限归入「用户与权限」「内容管理」两个二级分组（ADMIN 隐式拥有全部，可见全部分组）
const menus = computed(() => {
  const base = [{ index: '/', title: '知识库首页', icon: 'HomeFilled' }]
  if (auth.loggedIn) {
    base.push(
      { index: '/write', title: '写文章', icon: 'EditPen' },
      { index: '/my/posts', title: '我的帖子', icon: 'Files' },
      { index: '/favorites', title: '我的收藏', icon: 'Star' },
      { index: '/history', title: '浏览历史', icon: 'Clock' },
      { index: '/ranking', title: '积分排行榜', icon: 'Medal' },
    )
  }

  // 二级分组：用户与权限（用户管理 / 黑名单 / 操作审计）——账号、权限、黑名单与「谁操作了什么」归人域
  const userMgmt = []
  if (auth.hasPerm('USER_MANAGE')) userMgmt.push({ index: '/admin/users', title: '用户管理', icon: 'User' })
  if (auth.isAdmin) {
    userMgmt.push({ index: '/admin/blacklist', title: '黑名单', icon: 'CircleClose' })
    userMgmt.push({ index: '/admin/audit', title: '操作审计', icon: 'Clock' })
  }
  if (userMgmt.length) base.push({ index: 'g-user', title: '用户与权限', icon: 'User', children: userMgmt })

  // 二级分组：内容管理（帖子 / 分类标签 / 公告 / 附件 / 数据看板）
  // 原「数据中心」单独成组已并入此处，避免「仅一项」的空壳分组；内容资源 + 内容数据聚同一域
  const contentMgmt = []
  if (auth.hasPerm('DOC_REVIEW')) contentMgmt.push({ index: '/admin/docs', title: '帖子管理', icon: 'Document' })
  if (auth.hasPerm('TAXONOMY_MANAGE')) contentMgmt.push({ index: '/admin/taxonomy', title: '分类标签', icon: 'Collection' })
  if (auth.hasPerm('ANNOUNCEMENT_MANAGE')) contentMgmt.push({ index: '/admin/announcements', title: '公告管理', icon: 'Notification' })
  if (auth.isAdmin) contentMgmt.push({ index: '/admin/files', title: '附件管理', icon: 'Files' })
  if (auth.hasPerm('STATS_VIEW')) contentMgmt.push({ index: '/admin/stats', title: '数据看板', icon: 'DataLine' })
  if (contentMgmt.length) base.push({ index: 'g-content', title: '内容管理', icon: 'Document', children: contentMgmt })

  if (auth.loggedIn) {
    // 个人资料放在最底部，符合常见布局习惯
    base.push({ index: '/profile', title: '个人资料', icon: 'UserFilled' })
  }
  return base
})

function onLogout() {
  auth.logout()
  ElMessage.success('已退出登录')
  router.push('/')
}

function goHome() { router.push('/') }

function goProfile() { router.push('/profile') }

// 头像下拉：将权限码映射为中文名展示（ADMIN 已在上文单独显示「管理员」，此处只列普通用户细粒度权限）
const permText = computed(() =>
  auth.permissions.map(c => permLabel(c)).join('、')
)
</script>

<template>
  <!-- 登录页 / 独立阅读窗口：独立整页，不带菜单 -->
  <router-view v-if="barePage" />

  <!-- 其余页面：左侧菜单 + 顶栏 -->
  <el-container v-else class="layout">
    <el-aside :width="collapsed ? '64px' : '210px'" class="aside" :class="{ collapsed }">
      <div class="brand" :title="collapsed ? '知识库' : ''" @click="goHome">
        <img class="brand-ico" src="/logo.gif" alt="" />
        <span v-show="!collapsed" class="brand-txt">知识库</span>
      </div>
      <el-menu :default-active="route.path" router class="menu"
               :collapse="collapsed" :collapse-transition="false">
        <template v-for="m in menus" :key="m.index">
          <el-sub-menu v-if="m.children" :index="m.index">
            <template #title>
              <el-icon><component :is="m.icon" /></el-icon>
              <span>{{ m.title }}</span>
            </template>
            <el-menu-item v-for="c in m.children" :key="c.index" :index="c.index" class="sub-item">
              <el-icon><component :is="c.icon" /></el-icon>
              <span>{{ c.title }}</span>
            </el-menu-item>
          </el-sub-menu>
          <el-menu-item v-else :index="m.index">
            <el-icon><component :is="m.icon" /></el-icon>
            <span>{{ m.title }}</span>
          </el-menu-item>
        </template>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="header">
        <div class="head-left">
          <el-button class="fold-btn" text :title="collapsed ? '展开菜单' : '收起菜单'" @click="toggleSidebar">
            <el-icon size="18"><component :is="collapsed ? 'Expand' : 'Fold'" /></el-icon>
          </el-button>
          <div class="crumb">{{ route.meta.title || '知识库' }}</div>
        </div>
        <div class="right">
          <el-button class="theme-toggle" text circle
                     :title="isDark ? '切换到日间模式' : '切换到夜间模式'"
                     @click="toggleTheme">
            <el-icon :size="18" :class="{ spinning }">
              <component :is="isDark ? 'Sunny' : 'Moon'" />
            </el-icon>
          </el-button>
          <template v-if="auth.loggedIn">
            <el-badge :value="notification.unread" :hidden="notification.unread === 0" :max="99">
              <el-button class="bell" text circle title="通知" @click="goNotifications">
                <el-icon size="18"><Bell /></el-icon>
              </el-button>
            </el-badge>
            <el-dropdown>
              <span class="avatar">
                <img v-if="auth.user?.avatar" :src="auth.user.avatar" alt="" class="avatar-img" />
                <span v-else>{{ (auth.user?.displayName || auth.user?.username || '?').charAt(0) }}</span>
              </span>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item disabled>
                    {{ auth.user?.displayName || auth.user?.username }}
                  </el-dropdown-item>
                  <el-dropdown-item disabled>角色：{{ auth.user?.role === 'ADMIN' ? '管理员' : '普通用户' }}</el-dropdown-item>
                  <el-dropdown-item v-if="!auth.isAdmin && auth.permissions.length" disabled>
                    权限：{{ permText }}
                  </el-dropdown-item>
                  <el-dropdown-item disabled>积分：{{ auth.user?.points || 0 }}</el-dropdown-item>
                  <el-dropdown-item divided @click="goProfile">个人资料</el-dropdown-item>
                  <el-dropdown-item @click="onLogout">退出登录</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </template>
          <el-button v-else type="primary" @click="router.push('/login')">登录</el-button>
        </div>
      </el-header>

      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>

    <!-- 文章「小窗阅读」浮窗：渲染于路由之外，切换页面后依然存在，关闭前内容保留 -->
    <div v-if="reader.open" class="reader-float"
         :style="{ left: reader.x + 'px', top: reader.y + 'px', width: reader.w + 'px', height: reader.h + 'px' }">
      <div class="rf-bar" @mousedown="startDrag">
        <span class="rf-title">{{ reader.title }}</span>
        <button class="rf-close" type="button" @click="closeReader" @mousedown.stop title="关闭">×</button>
      </div>
      <div class="rf-body content" v-html="reader.content"></div>
      <div class="rf-resize" @mousedown.stop.prevent="startResize" title="拖动右下角缩放"></div>
    </div>
  </el-container>
</template>

<style>
body { margin: 0; background: #f8fafc; font-family: system-ui, "PingFang SC", "Microsoft YaHei", sans-serif; }
.layout { height: 100vh; }
.aside { background: #fff; border-right: 1px solid #e2e8f0; }
.brand { font-weight: 700; font-size: 18px; color: #4f46e5; padding: 16px 20px; cursor: pointer; display: flex; align-items: center; gap: 8px; }
.brand-ico { width: 36px; height: 36px; object-fit: contain; display: block; flex-shrink: 0; }
/* 折叠态：把 logo 放在一个浅灰圆角按钮容器里（与菜单项同尺寸/圆角），
   logo 再缩到 22px：实心图要比 24px 线型图标小半号才视觉等重，
   避免彩色实心 logo 直接压在灰色线型图标上造成的不协调 */
.aside.collapsed .brand {
  justify-content: center;
  padding: 12px 0;
  margin: 8px 6px;
  border-radius: 8px;
  background: #f1f5f9;
  transition: background-color .15s;
}
.aside.collapsed .brand:hover { background: #e2e8f0; }
html.dark .aside.collapsed .brand { background: #23234a; }
html.dark .aside.collapsed .brand:hover { background: #2a2a35; }
.aside.collapsed .brand-ico { width: 22px; height: 22px; }
.menu { border-right: none; padding: 8px; background: transparent; }
html.dark .aside { background: #14142b; border-right-color: #26264a; }

/* 二级分组（el-sub-menu）标题：圆角 pill + 加粗，作为层级锚点 */
.menu .el-sub-menu__title { height: 40px; line-height: 40px; margin: 2px 0; border-radius: 8px; color: #334155; font-weight: 600; transition: background-color .15s, color .15s; }
html.dark .menu .el-sub-menu__title { color: #c3c4d8; }
.menu .el-sub-menu__title:hover { background: #f1f5f9; color: #4f46e5; }
html.dark .menu .el-sub-menu__title:hover { background: #23234a; color: #a5b4fc; }

/* 菜单项：圆角 pill。过渡只用 background/color，无重排重绘 */
.menu .el-menu-item { height: 38px; line-height: 38px; margin: 2px 0; border-radius: 8px; color: #475569; position: relative; transition: background-color .15s, color .15s; }
html.dark .menu .el-menu-item { color: #b7b8cd; }
.menu .el-menu-item:hover { background: #f1f5f9; color: #4f46e5; }
html.dark .menu .el-menu-item:hover { background: #23234a; color: #a5b4fc; }

/* 激活项：浅蓝 pill + 左侧指示条（深蓝） */
.menu .el-menu-item.is-active { background: #eef2ff; color: #4f46e5; font-weight: 600; }
html.dark .menu .el-menu-item.is-active { background: #23234a; color: #a5b4fc; }
.menu .el-menu-item.is-active::before { content: ''; position: absolute; left: 0; top: 50%; transform: translateY(-50%); width: 3px; height: 16px; border-radius: 0 3px 3px 0; background: #4f46e5; }

/* 二级子项：缩进 + 左侧引导线，制造树形层次 */
.menu .el-sub-menu .el-menu-item { padding-left: 44px !important; min-width: auto; }
.menu .el-sub-menu .el-menu-item::after { content: ''; position: absolute; left: 28px; top: 0; bottom: 0; width: 1px; background: #e2e8f0; }
html.dark .menu .el-sub-menu .el-menu-item::after { background: #2b2c4d; }

/* 整栏折叠(64px) */
.aside.collapsed .menu { padding: 8px 6px; }
.header { display: flex; align-items: center; justify-content: space-between; background: #fff; border-bottom: 1px solid #e2e8f0; }
.head-left { display: flex; align-items: center; gap: 8px; }
.fold-btn { padding: 6px; }
.crumb { font-weight: 600; color: #0f172a; }
.avatar {
  width: 38px; height: 38px; border-radius: 50%; background: #eef2ff; color: #6366f1;
  display: inline-flex; align-items: center; justify-content: center; cursor: pointer;
  font-weight: 600; outline: none; overflow: hidden; border: 2px solid #fff;
  box-shadow: 0 0 0 1px #cbd5e1;
}
.avatar-img { width: 100%; height: 100%; object-fit: cover; }
.main { padding: 20px; }

/* 通知铃铛 */
.bell { padding: 7px; color: #6366f1; }
.bell:hover { background: #eef2ff; }
/* 夜间模式切换按钮 */
.theme-toggle { padding: 7px; color: #6366f1; }
.theme-toggle:hover { background: #eef2ff; }
.theme-toggle .el-icon { display: inline-flex; }
.theme-toggle.spinning .el-icon { animation: kb-theme-spin .46s ease; }
@keyframes kb-theme-spin {
  from { transform: rotate(-130deg) scale(.5); opacity: 0; }
  to   { transform: rotate(0) scale(1); opacity: 1; }
}
html.dark .theme-toggle { color: #c7c8d4; }
html.dark .theme-toggle:hover { background: #232347; }
</style>
