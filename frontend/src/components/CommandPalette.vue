<script setup>
/**
 * 全局命令面板（桌面端）。
 *
 * 用途：按 Ctrl/⌘ + K 唤起，用键盘在「页面 / 文档 / 动作」之间快速跳转，
 * 省得在左侧菜单里一层层点（后台菜单 30+ 项尤其明显）。
 *
 * 设计要点：
 *  - 只做桌面端：手机小屏 + 虚拟键盘下体验差，那种场景「全部文档」页的搜索已够用。
 *    父组件只在非 barePage（登录页 / 独立阅读窗）下挂载本组件。
 *  - 不做全文检索：只匹配标题/页面名/别名，要的是「快」，不是「全」。
 *    正文搜索由「全部文档」页负责。
 *  - 权限：后台页面按 auth.hasPerm 过滤，普通用户搜不到自己进不去的页面。
 *  - 不改数据模型、不引新依赖。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../store/auth'
import { listAllShareDocs } from '../api/docShare'

const router = useRouter()
const auth = useAuthStore()

const open = ref(false)
const query = ref('')
const cursor = ref(0)
const inputEl = ref(null)
const panelEl = ref(null)
const rowEls = ref([])

const docs = ref([])
const docsTried = ref(false)   // 只拉一次，失败也不再反复重试

/** 页面清单：perm / adminOnly 与路由 meta 保持一致，未授权的不出现在结果里 */
const PAGES = [
  { label: '首页', path: '/', kw: 'home shouye' },
  { label: '共享文档', path: '/doc-shares', kw: 'gongxiang kongjian space' },
  { label: '全部文档', path: '/doc-shares/all', kw: 'quanbu all docs' },
  { label: '导入本地文档', path: '/doc-shares/import', kw: 'daoru import' },
  { label: '写新文章', path: '/write', kw: 'xie wenzhang new post' },
  { label: '我的文章', path: '/my/posts', kw: 'wode wenzhang' },
  { label: 'AI 问答', path: '/ai/chat', kw: 'ai chat wenti' },
  { label: '培训课程', path: '/study/courses', kw: 'peixun kecheng course' },
  { label: '在线考试', path: '/exams', kw: 'kaoshi exam' },
  { label: '答题闯关', path: '/quiz', kw: 'dati quiz' },
  { label: '我的收藏', path: '/favorites', kw: 'shoucang favorite' },
  { label: '浏览历史', path: '/history', kw: 'liulan history' },
  { label: '我的订阅', path: '/subscriptions', kw: 'dingyue subscribe' },
  { label: '我的文件', path: '/files', kw: 'wenjian file upload' },
  { label: '我的勋章', path: '/badges', kw: 'xunzhang badge' },
  { label: '积分排行', path: '/ranking', kw: 'paihang rank' },
  { label: '积分明细', path: '/points/log', kw: 'jifen points log' },
  { label: '通知中心', path: '/notifications', kw: 'tongzhi notice' },
  { label: '回收站', path: '/recycle', kw: 'huishouzhan recycle trash' },
  { label: '搜索', path: '/search', kw: 'sousuo search' },
  { label: '个人资料', path: '/profile', kw: 'geren ziliao profile' },

  { label: '数据看板', path: '/admin/stats', perm: 'STATS_VIEW', kw: 'shuju kanban dashboard' },
  { label: '用户管理', path: '/admin/users', perm: 'USER_MANAGE', kw: 'yonghu user' },
  { label: '角色管理', path: '/admin/roles', perm: 'USER_MANAGE', kw: 'juese role permission' },
  { label: '部门管理', path: '/admin/departments', perm: 'USER_MANAGE', kw: 'bumen department org' },
  { label: '帖子管理', path: '/admin/docs', perm: 'POST_MANAGE', kw: 'tiezi post' },
  { label: '文章审核', path: '/admin/review', perm: 'DOC_REVIEW', kw: 'shenhe review' },
  { label: '分类标签', path: '/admin/taxonomy', perm: 'TAXONOMY_MANAGE', kw: 'fenlei biaoqian taxonomy' },
  { label: '公告管理', path: '/admin/announcements', perm: 'ANNOUNCEMENT_MANAGE', kw: 'gonggao announce' },
  { label: '违禁词管理', path: '/admin/banned-words', perm: 'BANNED_WORDS_MANAGE', kw: 'weijinci banned words' },
  { label: '黑名单', path: '/admin/blacklist', perm: 'BLACKLIST_MANAGE', kw: 'heimingdan blacklist' },
  { label: '共享文档管理', path: '/admin/doc-shares', perm: 'SHARE_VIEW', kw: 'gongxianguanli share admin' },
  { label: '培训管理', path: '/admin/courses', perm: 'COURSE_MANAGE', kw: 'peixun course' },
  { label: '考试管理', path: '/admin/exams', perm: 'EXAM_MANAGE', kw: 'kaoshi exam' },
  { label: '资产管理', path: '/admin/assets', perm: 'ASSET_MANAGE', kw: 'zichan asset' },
  { label: '附件管理', path: '/admin/files', adminOnly: true, kw: 'fujian attachment' },
  { label: 'AI 模型管理', path: '/admin/ai-models', perm: 'AI_MANAGE', kw: 'ai moxing model key' },
  { label: '操作审计', path: '/admin/audit', adminOnly: true, kw: 'shenji audit log' },
  { label: '登录日志', path: '/admin/login-logs', adminOnly: true, kw: 'denglu login log' },
  { label: '检索测试', path: '/admin/search-test', adminOnly: true, kw: 'jiansuo ceshi search test' },

  { label: '功能开关', path: '/admin/maintain', perm: 'CORE_MAINTAIN', kw: 'gongneng maintain switch' },
  { label: '外观设置', path: '/admin/appearance', perm: 'CORE_MAINTAIN', kw: 'waiguan appearance theme' },
  { label: '水印设置', path: '/admin/watermark', perm: 'CORE_MAINTAIN', kw: 'shuiyin watermark' },
  { label: '通知外发', path: '/admin/notify-webhook', perm: 'CORE_MAINTAIN', kw: 'webhook tongzhi notify 企微 钉钉' },
  { label: '页脚设置', path: '/admin/site-footer', perm: 'CORE_MAINTAIN', kw: 'yejiao footer' },
  { label: '登录页二维码', path: '/admin/login-qr', perm: 'CORE_MAINTAIN', kw: 'erweima qrcode' }
]

const ACTIONS = [
  { label: '导出全部文档', hint: '打包为 zip 下载（需导出权限）', kw: 'daochu export zip', go: () => router.push('/doc-shares/all') },
  { label: '进入共享空间列表', hint: '选择或创建一个协作空间', kw: 'kongjian space create', go: () => router.push('/doc-shares') }
]

const visiblePages = computed(() =>
  PAGES.filter((p) => (!p.adminOnly || auth.isAdmin) && (!p.perm || auth.hasPerm(p.perm))))

const allItems = computed(() => {
  const items = []
  for (const p of visiblePages.value) {
    items.push({ group: '页面', label: p.label, hint: p.path, kw: p.kw, run: () => router.push(p.path) })
  }
  for (const a of ACTIONS) {
    items.push({ group: '动作', label: a.label, hint: a.hint, kw: a.kw, run: a.go })
  }
  for (const d of docs.value) {
    const tags = Array.isArray(d.tags)
      ? d.tags.join(' ')
      : String(d.tags || '')
    items.push({
      group: '文档',
      label: d.title || '未命名文档',
      hint: [d.spaceName || '文档空间', tags].filter(Boolean).join(' · '),
      kw: '',
      run: () => router.push(`/doc-shares/${d.shareId}?doc=${d.id}`)
    })
  }
  return items
})

const results = computed(() => {
  const q = query.value.trim().toLowerCase()
  if (!q) return allItems.value.slice(0, 10)
  const hit = allItems.value.filter((i) =>
    i.label.toLowerCase().includes(q) || i.hint.toLowerCase().includes(q) || (i.kw || '').includes(q))
  // 有搜索词时，文档的结果通常更想要，提到前面来
  return hit.sort((a, b) => (a.group === '文档' ? 0 : 1) - (b.group === '文档' ? 0 : 1)).slice(0, 20)
})

/** 打开时拉一次文档清单（不带正文，只要标题/标签/空间名） */
async function ensureDocs() {
  if (docsTried.value) return
  docsTried.value = true
  try {
    const { data } = await listAllShareDocs(false)
    docs.value = Array.isArray(data) ? data : []
  } catch {
    docs.value = []   // 拉不到也不阻塞：页面和动作照样能用
  }
}

function enter() {
  query.value = ''
  cursor.value = 0
  ensureDocs()
  nextTick(() => inputEl.value?.focus())
}

function close() {
  open.value = false
  query.value = ''
}

function pick(it) {
  if (!it) return
  close()
  it.run()
}

function onKeydown(e) {
  if ((e.ctrlKey || e.metaKey) && (e.key === 'k' || e.key === 'K')) {
    e.preventDefault()
    open.value = !open.value
    if (open.value) enter()
    return
  }
  if (!open.value) return
  if (e.key === 'Escape') { e.preventDefault(); close() }
  else if (e.key === 'ArrowDown') { e.preventDefault(); cursor.value = Math.min(cursor.value + 1, results.value.length - 1) }
  else if (e.key === 'ArrowUp') { e.preventDefault(); cursor.value = Math.max(cursor.value - 1, 0) }
  else if (e.key === 'Enter') { e.preventDefault(); pick(results.value[cursor.value]) }
}

/** 光标移动时把选中行滚进可视区（长列表时键盘选择才看得见） */
watch(cursor, (i) => {
  nextTick(() => {
    const el = rowEls.value[i]
    if (el && el.scrollIntoView) el.scrollIntoView({ block: 'nearest' })
  })
})

/** 点了面板外面就关 */
function onDocClick(e) {
  if (!open.value) return
  if (panelEl.value && !panelEl.value.contains(e.target)) close()
}

onMounted(() => {
  document.addEventListener('keydown', onKeydown, true)
  document.addEventListener('click', onDocClick, true)
  // 顶栏「搜索」按钮 / 其他入口：派发 window 事件即可唤起
  window.addEventListener('kb-open-command-palette', onExternalOpen)
})
onBeforeUnmount(() => {
  document.removeEventListener('keydown', onKeydown, true)
  document.removeEventListener('click', onDocClick, true)
  window.removeEventListener('kb-open-command-palette', onExternalOpen)
})

function onExternalOpen() {
  if (open.value) { close(); return }
  open.value = true
  enter()
}

// 路由变化后自动收起，避免面板挂在旧页面上
watch(() => router.currentRoute.value.fullPath, () => { if (open.value) close() })
</script>

<template>
  <div v-if="open" class="cp-mask">
    <div ref="panelEl" class="cp-panel" role="dialog" aria-label="命令面板">
      <div class="cp-input">
        <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
          <circle cx="11" cy="11" r="7" /><path d="M20 20l-3.5-3.5" />
        </svg>
        <input
          ref="inputEl"
          v-model="query"
          type="text"
          placeholder="搜索页面、文档，或执行动作…"
          aria-label="命令面板搜索"
          @keydown.down.prevent="cursor = Math.min(cursor + 1, results.length - 1)"
          @keydown.up.prevent="cursor = Math.max(cursor - 1, 0)"
        />
        <span class="cp-esc">esc 关闭</span>
      </div>

      <div v-if="results.length" class="cp-list" role="listbox">
        <template v-for="(it, i) in results" :key="it.group + it.label + i">
          <div v-if="i === 0 || results[i - 1].group !== it.group" class="cp-group">{{ it.group }}</div>
          <div
            :ref="(el) => { if (el) rowEls[i] = el }"
            class="cp-row"
            :class="{ on: i === cursor }"
            role="option"
            :aria-selected="i === cursor"
            @mouseenter="cursor = i"
            @click="pick(it)"
          >
            <span class="cp-tag">{{ it.group[0] }}</span>
            <span class="cp-label">{{ it.label }}</span>
            <span class="cp-hint">{{ it.hint }}</span>
          </div>
        </template>
      </div>
      <div v-else class="cp-empty">没有匹配的结果</div>

      <div class="cp-foot">
        <span><b>↑</b> <b>↓</b> 选择</span>
        <span><b>↵</b> 打开</span>
        <span><b>Ctrl/⌘ + K</b> 唤起或收起</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.cp-mask {
  position: fixed; inset: 0; z-index: 3000;
  background: rgba(28, 32, 38, .32);
  display: flex; align-items: flex-start; justify-content: center;
  padding: 12vh 16px 16px;
}
.cp-panel {
  width: min(580px, 100%);
  background: var(--el-bg-color, #fff);
  border: 1px solid var(--el-border-color, #e5e6eb);
  border-radius: 12px; overflow: hidden;
  box-shadow: 0 12px 32px rgba(31, 35, 41, .12);
}
.cp-input { display: flex; align-items: center; gap: 10px; padding: 11px 14px; border-bottom: 1px solid var(--el-border-color-lighter, #eef0f3); color: var(--el-text-color-placeholder, #909095); }
.cp-input input { flex: 1; min-width: 0; border: 0; outline: 0; background: transparent; font-size: 14px; color: var(--el-text-color-primary, #1d2129); }
.cp-input input::placeholder { color: var(--el-text-color-placeholder, #a8a8b3); }
.cp-esc { font-size: 11px; color: var(--el-text-color-placeholder, #a8a8b3); white-space: nowrap; }

.cp-list { max-height: 46vh; overflow-y: auto; padding: 6px; }
.cp-group { font-size: 11px; color: var(--el-text-color-secondary, #86909c); margin: 8px 0 3px 10px; }
.cp-row { display: flex; align-items: center; gap: 9px; padding: 7px 10px; border-radius: 8px; cursor: pointer; }
.cp-row.on { background: var(--el-color-primary-light-9, #eef4ff); }
.cp-tag { flex: none; width: 22px; height: 22px; border-radius: 6px; background: var(--el-fill-color, #f2f3f5); color: var(--el-text-color-secondary, #4e5969); font-size: 12px; display: flex; align-items: center; justify-content: center; }
.cp-row.on .cp-tag { background: var(--el-color-primary, #1677ff); color: #fff; }
.cp-label { font-size: 13px; color: var(--el-text-color-primary, #1d2129); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.cp-hint { flex: 1; min-width: 0; font-size: 11px; color: var(--el-text-color-placeholder, #a8a8b3); font-family: var(--el-font-family-mono, monospace); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; text-align: right; }
.cp-empty { padding: 26px; text-align: center; font-size: 13px; color: var(--el-text-color-secondary, #86909c); }
.cp-foot { display: flex; gap: 16px; padding: 8px 14px; border-top: 1px solid var(--el-border-color-lighter, #eef0f3); font-size: 11px; color: var(--el-text-color-secondary, #86909c); }
.cp-foot b { font-weight: 500; padding: 0 4px; border-radius: 3px; background: var(--el-fill-color, #f2f3f5); }
</style>
