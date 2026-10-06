<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore, permLabel } from './store/auth'
import { useWatermarkStore, buildContent } from './store/watermark'
import { useSiteInfoStore } from './store/siteInfo'
import WornBadgeStrip from './components/WornBadgeStrip.vue'
import SiteFooter from './components/SiteFooter.vue'
import { useNotificationStore } from './store/notification'
import { reader, closeReader, startDrag, startResize, clampToViewport } from './store/reader'
import { enhanceVideos, bindImgLightbox } from './utils/mediaEnhance'
import { featureMap } from './api/features'
import { appearanceLogo as siteLogo, loadAppearance, resetAppearance } from './utils/appearance'
import { applyTheme } from './utils/theme'
import zhCn from 'element-plus/es/locale/lang/zh-cn.mjs'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const notification = useNotificationStore()
const wm = useWatermarkStore()
const siteInfo = useSiteInfoStore()

// 核心维护：功能开关状态（featureKey → { enabled, closeMode, maintenanceMsg, expectedRestoreAt }）
const featureFlags = ref({})
async function loadFlags() {
  try {
    const { data } = await featureMap()
    featureFlags.value = data || {}
  } catch { featureFlags.value = {} }
}
// 该功能入口是否在侧边栏展示：
//  - 未配置 / 开启 → 展示
//  - 关闭 + MAINTENANCE（维护界面模式）→ 展示（点进去看维护页）
//  - 关闭 + HIDDEN（默认）→ 隐藏
function featureOpen(key) {
  if (!key) return true
  const st = featureFlags.value[key]
  if (!st) return true            // 无记录视为开放
  if (st.enabled) return true
  return st.closeMode === 'MAINTENANCE'
}

// 全局水印：启用且已登录时，按当前用户身份构建内容；否则空数组（不绘制）
const wmContent = computed(() => {
  if (!wm.config.enabled || !auth.loggedIn) return []
  return buildContent(auth.user, wm.config, new Date(), wm.clientInfo)
})
const wmFont = computed(() => wm.font)
const wmGap = computed(() => wm.gap)

// 启动时用本地令牌恢复会话；登录态则建立 SSE 实时通知长连接（替代路由切换轮询未读）
onMounted(async () => {
  await auth.fetchMe()
  wm.dark = isDark.value   // 初始化水印主题状态（与当前页面主题一致）
  // 页脚配置走公开接口，**登录前也要拉**（登录页底部同样要展示备案信息）
  siteInfo.load()
  // 水印配置与 client-info 都要求登录态（匿名访问会 401），仅登录后拉取；
  // 匿名用户本就不显示水印，无需请求。等 fetchMe 恢复登录态/令牌后再调，避免 401 噪音。
  if (auth.loggedIn) wm.load()
  if (auth.loggedIn) { notification.connect(); loadFlags(); loadAppearance() }
})

// 外观配置见 utils/appearance.js（共享缓存：Logo + 资料页背景，一次拉取全站使用）

// 核心维护开关变更后即时刷新侧边栏菜单（无需整页刷新）：
// 监听全局事件 + 路由守卫缓存重置，保证普通用户立即看到入口变化。
function onFlagsChanged() {
  loadFlags()
}
window.addEventListener('kb:feature-flags-changed', onFlagsChanged)
onUnmounted(() => window.removeEventListener('kb:feature-flags-changed', onFlagsChanged))

// 登录态变化（登录 / 登出 / 令牌过期）时同步 SSE 连接与未读
// 登录成功后补拉水印配置（覆盖「本页登录」场景，否则水印参数仍是 localStorage 兜底值）
watch(() => auth.loggedIn, (v) => {
  if (v) { notification.connect(); wm.load(); loadAppearance() }   // SPA 登录不刷新页面也要拉自定义 Logo
  else { notification.disconnect(); notification.setUnread(0); resetAppearance() }   // 登出恢复内置默认，与匿名态一致
})

// 浏览器窗口缩放 / 全屏切换时，把阅读浮窗夹回可视区内（避免被挤出屏幕外）
window.addEventListener('resize', clampToViewport)

// 令牌过期时由 axios 拦截器广播，自动退出
function onUnauthorized() {
  if (auth.loggedIn) {
    auth.logout()
    ElMessage.warning('登录已过期，请重新登录')
    // 只清登录态不跳转的话，页面会停在原地直到用户点别的菜单
    router.push('/login')
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

// ---------- 响应式骨架 ----------
// ≤1024px 视为窄屏：侧边栏改为抽屉（覆盖式滑出 + 遮罩），不再占文档流宽度；
// >1024px 保持原来的"折叠/展开"两态。
const MOBILE_MQ = '(max-width: 1024px)'
const isNarrow = ref(window.matchMedia ? window.matchMedia(MOBILE_MQ).matches : false)
const drawerOpen = ref(false)
let mq = null
function onMqChange(e) {
  isNarrow.value = e.matches
  if (!e.matches) drawerOpen.value = false      // 回到桌面宽度时收起抽屉
}
onMounted(() => {
  if (!window.matchMedia) return
  mq = window.matchMedia(MOBILE_MQ)
  mq.addEventListener('change', onMqChange)
})
onUnmounted(() => { if (mq) mq.removeEventListener('change', onMqChange) })

// 顶栏菜单按钮：窄屏开合抽屉，宽屏折叠侧栏
function toggleMenu() {
  if (isNarrow.value) drawerOpen.value = !drawerOpen.value
  else toggleSidebar()
}
// 窄屏下点菜单跳转后自动收起抽屉（否则菜单挡住内容）
watch(() => route.fullPath, () => { drawerOpen.value = false })

// 小窗阅读：图片点开放大 + 视频增强（16:9 占位/倍速/全屏/画中画），样式在全局 responsive.css
const rfRef = ref(null)
const readerLightbox = ref('')
watch(() => reader.content, async () => {
  await nextTick()
  if (!reader.open || !rfRef.value) return
  enhanceVideos(rfRef.value)
  bindImgLightbox(rfRef.value, (src) => { if (src) readerLightbox.value = src })
})

// 夜间模式切换（B站风格：月亮/太阳 + 旋转淡入 + 过渡）
const isDark = ref(document.documentElement.classList.contains('dark'))
const spinning = ref(false)
function toggleTheme() {
  const next = !isDark.value
  applyTheme(next)          // 整页柔和淡入（不支持则回退全局颜色过渡）
  isDark.value = next
  wm.dark = next            // 同步水印暗色状态，使水印颜色按主题自适应（暗色下用浅色，保证可见）
  spinning.value = true     // 图标旋转一次
  setTimeout(() => (spinning.value = false), 520)
}

// 菜单项：按角色个性化
//  - 所有登录用户：写文章、我的帖子
//  - 管理类按细粒度权限归入「用户与权限」「内容管理」「站点设置」三个二级分组（ADMIN 隐式拥有全部，可见全部分组）
const menus = computed(() => {
  const base = [{ index: '/', title: '知识库首页', icon: 'HomeFilled' }]
  if (auth.loggedIn) {
    // 普通功能入口：受核心维护开关控制，关闭则不在侧边栏展示
    if (featureOpen('docs')) base.push(
      { index: '/write', title: '写文章', icon: 'EditPen' },
      { index: '/my/posts', title: '我的帖子', icon: 'Files' },
      { index: '/recycle', title: '回收站', icon: 'Delete' },
      { index: '/subscriptions', title: '我的订阅', icon: 'Notification' },
    )
    if (featureOpen('profile')) base.push(
      { index: '/favorites', title: '我的收藏', icon: 'Star' },
      { index: '/history', title: '浏览历史', icon: 'Clock' },
      { index: '/badges', title: '我的勋章', icon: 'Medal' },
    )
    if (featureOpen('ranking')) base.push({ index: '/ranking', title: '积分排行榜', icon: 'Medal' })
    if (featureOpen('ai_chat')) base.push({ index: '/ai/chat', title: 'AI 问答', icon: 'ChatDotRound' })
    if (featureOpen('courses')) base.push({ index: '/study/courses', title: '培训', icon: 'Reading' })
    if (featureOpen('quiz')) base.push({ index: '/quiz', title: '答题', icon: 'Trophy' })
    if (featureOpen('doc_shares')) base.push({ index: '/doc-shares', title: '共享文档', icon: 'DocumentCopy' })
  }

  // 二级分组：用户与权限（用户管理 / 黑名单 / 操作审计）——账号、权限、黑名单与「谁操作了什么」归人域
  const userMgmt = []
  if (auth.hasPerm('USER_MANAGE')) userMgmt.push({ index: '/admin/users', title: '用户管理', icon: 'User' })
  if (auth.hasPerm('USER_MANAGE')) userMgmt.push({ index: '/admin/roles', title: '角色管理', icon: 'Suitcase' })
  if (auth.hasPerm('USER_MANAGE')) userMgmt.push({ index: '/admin/departments', title: '部门管理', icon: 'OfficeBuilding' })
  if (auth.isAdmin || auth.hasPerm('ADMIN_VIEW') || auth.hasPerm('AUDIT_VIEW') || auth.hasPerm('LOGIN_LOG_VIEW')) {
    // 黑名单：无对应后端接口，仅 ADMIN 角色可见
    if (auth.isAdmin) userMgmt.push({ index: '/admin/blacklist', title: '黑名单', icon: 'CircleClose' })
    // 操作审计 / 登录日志：严格按各自独立权限码显示（不回退 ADMIN_VIEW，与后端判定一致）
    if (auth.isAdmin || auth.hasPerm('AUDIT_VIEW')) {
      userMgmt.push({ index: '/admin/audit', title: '操作审计', icon: 'Clock' })
    }
    if (auth.isAdmin || auth.hasPerm('LOGIN_LOG_VIEW')) {
      userMgmt.push({ index: '/admin/login-logs', title: '登录日志', icon: 'Key' })
    }
  }
  if (userMgmt.length) base.push({ index: 'g-user', title: '用户与权限', icon: 'User', children: userMgmt })

  // 二级分组：内容管理（帖子 / 分类标签 / 公告 / 附件 / 数据看板）
  // 原「数据中心」单独成组已并入此处，避免「仅一项」的空壳分组；内容资源 + 内容数据聚同一域
  // 站点级配置（功能开关 / 水印 / 登录二维码 / 外观）已移至下方「站点设置」组
  const contentMgmt = []
  if (auth.hasPerm('DOC_REVIEW')) contentMgmt.push({ index: '/admin/review', title: '文章审核', icon: 'Finished' })
  if (auth.hasPerm('POST_MANAGE')) contentMgmt.push({ index: '/admin/docs', title: '帖子管理', icon: 'Document' })
  if (auth.hasPerm('TAXONOMY_MANAGE')) contentMgmt.push({ index: '/admin/taxonomy', title: '分类标签', icon: 'Collection' })
  if (auth.hasPerm('ANNOUNCEMENT_MANAGE')) contentMgmt.push({ index: '/admin/announcements', title: '公告管理', icon: 'Notification' })
  if (auth.hasPerm('BANNED_WORDS_MANAGE')) contentMgmt.push({ index: '/admin/banned-words', title: '违禁词管理', icon: 'Warning' })
  if (auth.isAdmin || auth.hasPerm('ADMIN_VIEW')) contentMgmt.push({ index: '/admin/files', title: '附件管理', icon: 'Folder' })
  if (auth.hasPerm('STATS_VIEW')) contentMgmt.push({ index: '/admin/stats', title: '数据看板', icon: 'DataLine' })
  if (auth.hasPerm('COURSE_MANAGE')) contentMgmt.push({ index: '/admin/courses', title: '培训管理', icon: 'Notebook' })
  if (auth.hasPerm('EXAM_MANAGE')) contentMgmt.push({ index: '/admin/exams', title: '题库与试卷', icon: 'Trophy' })
  if (auth.hasPerm('SHARE_VIEW')) contentMgmt.push({ index: '/admin/doc-shares', title: '共享文档管理', icon: 'DocumentCopy' })
  if (auth.hasPerm('ASSET_MANAGE')) contentMgmt.push({ index: '/admin/assets', title: '资产管理', icon: 'Box' })
  // 检索测试：管理员诊断搜索质量（搜得到 / 搜得准）；演示账号（ADMIN_VIEW）也可只读探测
  if (auth.isAdmin || auth.hasPerm('ADMIN_VIEW')) contentMgmt.push({ index: '/admin/search-test', title: '检索测试', icon: 'Search' })
  // AI 模型管理：细粒度权限 AI_MANAGE（列表对 Key 脱敏，演示号勾上后只读）
  if (auth.isAdmin || auth.hasPerm('AI_MANAGE')) contentMgmt.push({ index: '/admin/ai-models', title: 'AI 模型管理', icon: 'Cpu' })
  if (contentMgmt.length) base.push({ index: 'g-content', title: '内容管理', icon: 'Document', children: contentMgmt })

  // 二级分组：站点设置（功能开关 / 水印 / 登录页二维码 / 外观）
  // 站点级配置，与「内容资源」分离：调的是整站的开关与呈现，不涉及具体条目
  const siteConfig = []
  if (auth.hasPerm('CORE_MAINTAIN')) siteConfig.push({ index: '/admin/maintain', title: '功能开关', icon: 'Setting' })
  if (auth.hasPerm('CORE_MAINTAIN')) siteConfig.push({ index: '/admin/watermark', title: '水印设置', icon: 'Tools' })
  if (auth.hasPerm('CORE_MAINTAIN')) siteConfig.push({ index: '/admin/login-qr', title: '登录页二维码', icon: 'Grid' })
  if (auth.hasPerm('CORE_MAINTAIN')) siteConfig.push({ index: '/admin/appearance', title: '外观设置', icon: 'Brush' })
  if (auth.hasPerm('CORE_MAINTAIN')) siteConfig.push({ index: '/admin/site-footer', title: '页脚设置', icon: 'Document' })
  if (siteConfig.length) base.push({ index: 'g-site', title: '站点设置', icon: 'Setting', children: siteConfig })

  if (auth.loggedIn) {
    // 个人资料放在最底部，符合常见布局习惯
    base.push({ index: '/profile', title: '个人资料', icon: 'UserFilled' })
  }
  return base
})

function onLogout() {
  auth.logout()
  ElMessage.success('已退出登录')
  // 直接去登录页：push('/') 在首页退出时是重复导航会被忽略，且还要经守卫转一道
  router.push('/login')
}

function goHome() { router.push('/') }

function goProfile() { router.push('/profile') }

// 头像下拉：将权限码映射为中文名展示（ADMIN 已在上文单独显示「管理员」，此处只列普通用户细粒度权限）
const permText = computed(() =>
  auth.permissions.map(c => permLabel(c)).join('、')
)

// ---- 帮助中心：操作手册与规范（内联展示，同时作为知识库文档供 AI 检索）----
const helpVisible = ref(false)
const helpActive = ref('start')
const helpData = [
  { key: 'start', title: '快速开始', html: `<h3>快速开始</h3>
<p>嗨，欢迎来到知识库 👋 第一次来？先登录就好：</p>
<ul>
  <li>点右上角「登录」，输入账号密码，滑一下<strong>滑动验证码</strong>就能进来啦。</li>
  <li>还没账号？点「注册」就能开一个；密码忘了也不怕，登录页有「修改密码」。</li>
</ul>
<p>登录后你就到了知识库首页，这儿能看到「最新」「最热」的文章，顶部搜索框还能搜全站内容。</p>` },
  { key: 'write', title: '写文章与发布', html: `<h3>写文章与发布</h3>
<ul>
  <li>左侧菜单「写文章」进入富文本编辑器（支持标题、加粗、列表、图片、表格、代码块等）。</li>
  <li>填写<strong>标题</strong>，选择<strong>分类</strong>与<strong>标签</strong>，便于他人检索。</li>
  <li>点击「提交」进入<strong>待审核</strong>状态，管理员审核通过后公开；也可「保存草稿」稍后继续。</li>
  <li>「我的帖子」可查看自己文章的状态（已发布 / 待审核 / 草稿 / 已封禁）。</li>
</ul>` },
  { key: 'read', title: '阅读与互动', html: `<h3>阅读与互动</h3>
<ul>
  <li><strong>搜索</strong>：顶部搜索框或「搜索结果」页，按关键词检索文章标题与正文。</li>
  <li><strong>收藏 / 点赞 / 评论</strong>：在文章详情页操作；收藏的文章见于「我的收藏」。</li>
  <li><strong>浏览历史</strong>：自动记录你读过的文章，见于「浏览历史」。</li>
  <li>文章内「小窗阅读」可在悬浮窗中阅读，不影响继续浏览其他页面。</li>
</ul>` },
  { key: 'ai', title: 'AI 智能问答', html: `<h3>AI 智能问答</h3>
<p>有啥想问的，来「AI 问答」跟我聊～</p>
<ul>
  <li>两种模式：<strong>知识库</strong>（我会先在本站已发布的文档里找答案，还会给你标出来源，点一下就能看原文）；<strong>通用</strong>（就是跟大模型闲聊、聊啥都行）。</li>
  <li>你可以开好几个对话，还能设置「对话条例」和「个人习惯」，让我更懂你的口味。</li>
  <li>小提醒：每小时每位用户最多问 20 次哦。</li>
  <li>要是看到「AI 问答暂不可用」，那是管理员还没把 AI 服务打开（需要配置智谱 API Key），找管理员帮帮忙就好。</li>
</ul>` },
  { key: 'share', title: '共享文档协作', html: `<h3>共享文档协作</h3>
<p>想跟同事一起写文档？进「共享文档」建个团队空间就好啦。</p>
<ul>
  <li>新建的空间默认给你 <strong>2GB</strong> 容量，够日常用了。</li>
  <li>容量不够了？在空间里点「申请扩容」，填好想要的容量和理由，交给管理员审批通过就生效～ 创建之后容量只能由管理员帮你调，自己改不了哦。</li>
  <li>空间里支持<strong>多人实时一起编辑</strong>（用的是 CRDT 技术，你能看到别人正停在哪儿、敲了啥）。</li>
  <li>在空间里你会被赋予不同的操作权限：有的能只看、有的能一起改、有的能帮忙管理——具体能做什么，空间主人会帮你安排好，你按提示操作就行，不用记那些角色名。</li>
</ul>` },
  { key: 'course', title: '培训与学习', html: `<h3>培训与学习</h3>
<ul>
  <li>进入「培训」浏览课程；点开课程查看章节列表。</li>
  <li>每章为<strong>独立学习页</strong>，支持正文阅读与<strong>章节笔记</strong>（自动保存）。</li>
  <li>学完点击「标记完成」，进度自动累积。</li>
</ul>` },
  { key: 'exam', title: '考试与答题', html: `<h3>考试与答题</h3>
<ul>
  <li>「在线考试」：参加测评、提交后查看成绩与解析。</li>
  <li>「答题」：闯关模式，趣味答题积累积分。</li>
</ul>` },
  { key: 'points', title: '积分体系', html: `<h3>积分体系</h3>
<ul>
  <li>发帖、评论、获得点赞等行为可累积<strong>积分</strong>。</li>
  <li>「积分排行榜」查看全站排名，激励优质内容创作。</li>
</ul>` },
  { key: 'profile', title: '个人中心', html: `<h3>个人中心</h3>
<ul>
  <li>「个人资料」：修改头像、简介、登录密码。</li>
  <li>「我的收藏」「浏览历史」「通知」：管理你的内容与消息。</li>
</ul>` },
  { key: 'rule', title: '使用规范', html: `<h3>使用规范与社区公约</h3>
<ul>
  <li>发布内容须<strong>合法合规</strong>，禁止传播违法违规、谣言、色情、暴力等信息。</li>
  <li>尊重<strong>知识产权</strong>，转载请注明出处，勿上传侵权内容。</li>
  <li>文明互动：禁止人身攻击、刷屏、发布垃圾广告。</li>
  <li>账号安全：妥善保管密码，不共享账号；发现异常及时修改密码或联系管理员。</li>
</ul>` },
]
const currentHelp = computed(() => helpData.find(s => s.key === helpActive.value) || helpData[0])
function openHelp() { helpVisible.value = true }
</script>

<template>
  <!-- 全局中文 locale（Element Plus 已改按需注册，locale 由 ConfigProvider 提供） -->
  <el-config-provider :locale="zhCn">
  <!-- 登录页 / 独立阅读窗口：独立整页，不带菜单 -->
  <router-view v-if="barePage" />

  <!-- 其余页面：左侧菜单 + 顶栏 -->
  <el-watermark v-else
    :content="wmContent"
    :font="wmFont"
    :rotate="wm.config.rotate"
    :gap="wmGap"
    :z-index="wm.config.zIndex">
  <el-container class="layout" :class="{ 'is-narrow': isNarrow }">
    <!-- 窄屏抽屉遮罩：点击关闭 -->
    <div v-if="isNarrow && drawerOpen" class="aside-mask" @click="drawerOpen = false"></div>
    <el-aside :width="isNarrow ? '210px' : (collapsed ? '64px' : '210px')"
              class="aside"
              :class="{ collapsed: collapsed && !isNarrow, 'is-drawer': isNarrow, 'drawer-open': drawerOpen }">
      <div class="brand" :title="collapsed ? '知识库' : ''" @click="goHome">
        <img class="brand-ico" :src="siteLogo || '/logo.png'" alt="" />
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
          <el-button class="fold-btn" text
                     :title="isNarrow ? '打开菜单' : (collapsed ? '展开菜单' : '收起菜单')"
                     @click="toggleMenu">
            <el-icon size="18"><component :is="(isNarrow ? !drawerOpen : collapsed) ? 'Expand' : 'Fold'" /></el-icon>
          </el-button>
          <div class="crumb">{{ route.meta.title || '知识库' }}</div>
        </div>
        <div class="right">
          <el-button class="help-btn" text circle title="帮助中心" @click="openHelp">
            <el-icon size="18"><InfoFilled /></el-icon>
          </el-button>
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
            <!-- 头像 + 佩戴勋章：组成 inline-flex 单元，整组在 .right 中垂直居中，
                 与左侧铃铛/月亮/帮助等按钮同一基线（修复合并后基线漂移问题） -->
            <span class="avatar-cluster">
              <WornBadgeStrip v-if="auth.user?.wornBadges?.length"
                              :badge-ids="auth.user.wornBadges" :size="20" class="cluster-badges" />
              <el-dropdown trigger="click">
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
            </span>
          </template>
          <el-button v-else type="primary" @click="router.push('/login')">登录</el-button>
        </div>
      </el-header>

      <el-main class="main">
        <!-- 演示账号横幅：全局提示当前处于「只读演示」状态，写操作会被拒绝 -->
        <div v-if="auth.isDemo" class="demo-banner">
          <el-icon><InfoFilled /></el-icon>
          <span>当前为<strong>演示账户</strong>：可浏览全部页面，但不能保存、删除或发布数据。如需操作请使用正式账号登录。</span>
        </div>
        <router-view :key="$route.fullPath" />
        <!-- 站点页脚：备案信息（合规要求网站底部必须可见）+ 自定义内容 + 版本。
             吸底布局见下方 .main 的 CSS 注释（内容短时贴底、内容长时随内容滚动）。
             /login 走 barePage 分支不经过 el-main，其页脚由 Login.vue 自行渲染 -->
        <SiteFooter />
      </el-main>
    </el-container>

    <!-- 文章「小窗阅读」浮窗：渲染于路由之外，切换页面后依然存在，关闭前内容保留 -->
    <div v-if="reader.open" class="reader-float"
         :style="{ left: reader.x + 'px', top: reader.y + 'px', width: reader.w + 'px', height: reader.h + 'px' }">
      <div class="rf-bar" @mousedown="startDrag">
        <span class="rf-title">{{ reader.title }}</span>
        <button class="rf-close" type="button" @click="closeReader" @mousedown.stop title="关闭">×</button>
      </div>
      <div ref="rfRef" class="rf-body content" v-html="reader.content"></div>
      <div class="rf-resize" @mousedown.stop.prevent="startResize" title="拖动右下角缩放"></div>
    </div>

    <!-- 小窗阅读里的图片灯箱（点正文图片全屏看原图） -->
    <div v-if="readerLightbox" class="kb-lightbox" @click="readerLightbox = ''">
      <img :src="readerLightbox" alt="" @click.stop />
      <button class="lb-x" type="button" @click="readerLightbox = ''">×</button>
    </div>

    <!-- 帮助中心：操作手册与规范 -->
    <el-dialog v-model="helpVisible" title="帮助中心 · 操作手册与规范" width="820px" top="6vh" class="help-dialog">
      <div class="help-wrap">
        <aside class="help-nav">
          <button v-for="s in helpData" :key="s.key" type="button"
                  class="help-nav-item" :class="{ active: helpActive === s.key }" @click="helpActive = s.key">
            {{ s.title }}
          </button>
        </aside>
        <div class="help-content" v-html="currentHelp.html"></div>
      </div>
      <template #footer>
        <span class="help-tip">仍有疑问？可在「AI 问答」中提问，或联系系统管理员。</span>
      </template>
    </el-dialog>
  </el-container>
  </el-watermark>
  </el-config-provider>
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
.header { display: flex; align-items: center; justify-content: space-between; background: #fff; border-bottom: 1px solid #e2e8f0; overflow: visible; }
/* 顶栏右侧所有元素统一垂直居中（含不同尺寸的圆形按钮、弹层、头像、徽章组），
   避免「铃铛 32px + 头像 38px + 徽章 20px」混排时各按自己的 inline-baseline 漂移 */
.right { display: inline-flex; align-items: center; gap: 4px; }
.head-left { display: flex; align-items: center; gap: 8px; }
.fold-btn { padding: 6px; }
.crumb { font-weight: 600; color: #0f172a; }
/* 头像 + 佩戴徽章组成的 inline-flex 单元，与其它按钮同一基线；
   没佩戴时 cluster 内只剩头像一个子节点，结构稳定 */
.avatar-cluster { display: inline-flex; align-items: center; gap: 6px; vertical-align: middle; flex: none; }
.avatar-cluster .cluster-badges { vertical-align: middle; }
.avatar {
  /* 36x36 圆形头像，与左侧 el-button text-circle（约 32px）视觉等齐 */
  width: 36px; height: 36px; flex: none; border-radius: 50%;
  background: #eef2ff; color: #6366f1;
  display: inline-flex; align-items: center; justify-content: center; cursor: pointer;
  font-weight: 600; outline: none; overflow: hidden; border: 2px solid #fff;
  box-shadow: 0 0 0 1px #cbd5e1; vertical-align: middle;
}
.avatar-img { width: 100%; height: 100%; object-fit: cover; display: block; }
/* ===== 页脚吸底（2026-10-06） =====
   布局：.main 为纵向 flex；页面根元素 flex:1 1 0% 占满「内容区减页脚」的剩余空间，
   页脚 margin-top:auto 沉底。内容短 → 页脚贴到内容区底部；内容长 → 随内容滚动（在最后）。
   ⚠️ 关键是 flex-basis 必须是 0%：
     - basis 0% 在 flex 布局里覆盖页面根元素的 height:100%，根元素实际高度由 flex 分配
       （= 容器 − 页脚），是**确定值**，内部依赖百分比高度的编辑器/图块链条照常解析；
     - 2026-10-05 那次「整页被压扁」的真凶是根元素走默认 flex:0 1 auto（basis=height:100%），
       根元素假想高度 100% + 页脚超出容器 → 触发 flex **收缩**，卡片/表格被压缩。
       basis 0% 时假想总高恰好占满、永不触发收缩，故不会再压扁；min-height:auto 兜底内容高度。
   ⚠️ 别把页脚挪到 el-main 外面常驻底部：内容区可用高度被砍掉约 90px，观感像"压缩"。 */
.main { padding: 20px; display: flex; flex-direction: column; }
/* width:100% 必须加：flex 纵向容器的子项若只靠 stretch，遇到根元素自带
   max-width + margin:0 auto 的页面（收藏/排行/勋章/考试等）会被**收缩成内容宽**
   （实测 /favorites 根被压到 311px），表现为「页面被压扁/从手机宽度往外延伸」。
   width:100% + max-width + auto 外边距 = 恢复与块级布局一致的居中收窄行为。 */
.main > :not(.site-footer):not(.demo-banner) { flex: 1 1 0%; width: 100%; }
.main > .site-footer { margin-top: auto; }

/* 演示账号横幅：全局只读提示，粘在内容区顶部 */
.demo-banner {
  display: flex; align-items: center; gap: 8px;
  margin: -6px 0 16px; padding: 10px 14px;
  border-radius: 10px; font-size: 13px; line-height: 1.5;
  color: #9a6b00; background: #fff8e6; border: 1px solid #ffe1a6;
}
.demo-banner strong { color: #b45309; }
.demo-banner .el-icon { flex-shrink: 0; font-size: 16px; color: #d97706; }
html.dark .demo-banner { color: #f0c469; background: #2c2410; border-color: #5c4a1a; }
html.dark .demo-banner strong { color: #fbbf24; }

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

/* 帮助中心按钮 */
.help-btn { padding: 7px; color: #6366f1; }
.help-btn:hover { background: #eef2ff; }
html.dark .help-btn { color: #c7c8d4; }
html.dark .help-btn:hover { background: #232347; }

/* 帮助中心弹窗 */
.help-wrap { display: flex; gap: 16px; height: 60vh; }
.help-nav { width: 132px; flex-shrink: 0; overflow: auto; border-right: 1px solid #e2e8f0; padding-right: 8px; }
.help-nav-item { display: block; width: 100%; text-align: left; border: none; background: transparent; padding: 8px 10px; border-radius: 8px; cursor: pointer; color: #475569; font-size: 13px; transition: background-color .15s, color .15s; }
.help-nav-item:hover { background: #f1f5f9; color: #4f46e5; }
.help-nav-item.active { background: #eef2ff; color: #4f46e5; font-weight: 600; }
.help-content { flex: 1; overflow: auto; padding-right: 8px; line-height: 1.7; color: #334155; }
.help-content h3 { margin: 4px 0 10px; color: #1e293b; font-size: 16px; }
.help-content h4 { margin: 14px 0 6px; color: #334155; font-size: 14px; }
.help-content p { margin: 6px 0; }
.help-content ul { margin: 6px 0; padding-left: 20px; }
.help-content li { margin: 4px 0; }
.help-content code { background: #f1f5f9; padding: 1px 5px; border-radius: 4px; font-size: 12px; }
.help-tip { font-size: 12px; color: #94a3b8; }
html.dark .help-nav { border-right-color: #2a2a35; }
html.dark .help-nav-item { color: #b7b8cd; }
html.dark .help-nav-item:hover { background: #23234a; color: #a5b4fc; }
html.dark .help-nav-item.active { background: #23234a; color: #a5b4fc; }
html.dark .help-content { color: #c3c4d8; }
html.dark .help-content h3, html.dark .help-content h4 { color: #e7e7ee; }
html.dark .help-content code { background: #23234a; }

/* ============================================================
   响应式骨架
   ≤1024：侧边栏从"占文档流"改为"覆盖式抽屉"，主内容占满整宽
   ≤ 640：顶栏压缩、面包屑省略、帮助弹窗竖排
   ============================================================ */
.aside-mask { position: fixed; inset: 0; background: rgba(15, 23, 42, .45); z-index: 1999; }

@media (max-width: 1024px) {
  .layout .aside {
    position: fixed !important;
    left: 0; top: 0; bottom: 0;
    z-index: 2000;
    transform: translateX(-100%);
    transition: transform .26s cubic-bezier(.4, 0, .2, 1);
    box-shadow: 0 8px 30px rgba(0, 0, 0, .18);
  }
  .layout .aside.drawer-open { transform: translateX(0); }
  .header { padding: 0 10px; }
  .crumb { font-size: 15px; }
}

@media (max-width: 640px) {
  .el-header.header { height: 52px; padding: 0 8px; }
  .brand { font-size: 16px; padding: 12px 16px; }
  .brand-ico { width: 30px; height: 30px; }
  /* 面包屑：窄屏最多占 40% 宽，超出省略，别把右侧图标挤出去 */
  .crumb {
    font-size: 14px;
    max-width: 40vw;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .avatar { width: 30px; height: 30px; }
  .bell, .theme-toggle, .help-btn { padding: 5px; }
  /* 帮助中心：左侧导航改横向滚动条，正文在下 */
  .help-wrap { flex-direction: column; gap: 10px; height: 56vh; }
  .help-nav {
    width: auto; flex: none; overflow-x: auto;
    display: flex; gap: 6px;
    border-right: none; padding: 0 0 8px;
    border-bottom: 1px solid #e2e8f0;
  }
  html.dark .help-nav { border-bottom-color: #2a2a35; }
  .help-nav-item { white-space: nowrap; }
  .help-content { padding-right: 0; }
}
</style>
