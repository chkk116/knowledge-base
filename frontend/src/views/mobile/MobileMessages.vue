<script setup>
/**
 * 移动端「消息」Tab
 *
 * 数据来源同桌面端通知页（store/notification + api/notification），
 * 但呈现改成 App 习惯：头像/图标 + 标题 + 摘要 + 时间，点击进入对应详情。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useNotificationStore } from '../../store/notification'
import { notificationList, notificationRead, notificationReadAll } from '../../api/kb'
import { dlActive, dlPercent, dlDone } from '../../utils/updateProgress'
import { dmUnreadCount } from '../../store/dm'

const router = useRouter()
const n = useNotificationStore()

const loading = ref(true)
const items = ref([])
const filter = ref('all')   // all | unread

// 应用更新下载：置顶一条本地条目，方便随时看到进度（不依赖服务端通知）
const dlShow = computed(() => dlActive.value || dlDone.value)
const dlDesc = computed(() =>
  dlActive.value ? (dlPercent.value >= 0 ? `正在下载 ${dlPercent.value}%` : '正在下载…')
                 : '下载完成，请在弹出的安装界面完成安装')
function dlTap() { router.push('/about') }

const shown = computed(() =>
  filter.value === 'unread' ? items.value.filter((i) => !i.read) : items.value
)

function fmtTime(s) {
  if (!s) return ''
  const d = new Date(s)
  if (Number.isNaN(d.getTime())) return ''
  const diff = Date.now() - d.getTime()
  if (diff < 60000) return '刚刚'
  if (diff < 3600000) return Math.floor(diff / 60000) + ' 分钟前'
  if (diff < 86400000) return Math.floor(diff / 3600000) + ' 小时前'
  if (diff < 86400000 * 7) return Math.floor(diff / 86400000) + ' 天前'
  return `${d.getMonth() + 1}/${d.getDate()}`
}

/** 通知类型 → 图标与配色 */
function iconOf(t) {
  const k = String(t || '').toUpperCase()
  if (k.includes('LIKE')) return { i: 'Star', c: 'red' }
  if (k.includes('COMMENT')) return { i: 'ChatDotRound', c: 'blue' }
  if (k.includes('FOLLOW')) return { i: 'User', c: 'green' }
  if (k.includes('AVATAR')) return { i: 'UserFilled', c: 'orange' }
  if (k.includes('REVIEW')) return { i: 'CircleCheckFilled', c: 'orange' }
  if (k.includes('SHARE') || k.includes('DOC')) return { i: 'DocumentCopy', c: '' }
  if (k.includes('SECURITY')) return { i: 'Lock', c: 'red' }
  return { i: 'Bell', c: 'gray' }
}

/** 通知 → 标题文案（后端只有 type/actor/summary，标题在此派生） */
function titleOf(it) {
  const k = String(it.type || '').toUpperCase()
  const who = it.actor || '有人'
  if (k === 'LIKE') return `${who} 赞了你的文档`
  if (k === 'DOC_MENTION') return `${who} 在文档里 @ 了你`
  if (k === 'COMMENT' || k === 'COMMENT_MENTION') return `${who} 评论了你的文档`
  if (k === 'COMMENT_REPLY') return `${who} 回复了你的评论`
  if (k.includes('SHARE')) return '共享文档空间有更新'
  if (k.includes('AVATAR')) return '头像审核结果'
  if (k.includes('REVIEW')) return '内容审核结果'
  if (k.includes('SECURITY')) return '账号安全提醒'
  return k ? k.replace(/_/g, ' ').toLowerCase() : '通知'
}

async function load() {
  loading.value = true
  try {
    const { data } = await notificationList()
    items.value = (Array.isArray(data) ? data : [])
      .sort((a, b) => new Date(b.createdAt || 0) - new Date(a.createdAt || 0))
  } catch {
    items.value = []
  } finally {
    loading.value = false
  }
}

/** 通知 → 移动端路由（尽量落到手机端页面，落不到给提示） */
function routeOf(it) {
  const t = String(it.type || '')
  if (t.startsWith('SHARE_')) return '/docs'
  // 共享文档 @提及：直达那篇文档（手机端路由是 /doc/:shareId/:docId）
  if (t === 'DOC_MENTION') return it.shareId && it.docId ? `/doc/${it.shareId}/${it.docId}` : null
  if (t === 'COMMENT' || t === 'COMMENT_MENTION' || t === 'COMMENT_REPLY' || t === 'LIKE') {
    // 直接 deep-link 到文章详情页（外部审查 2026-10-12：原跳 /embed/post 是 path:null
    // 的死页，用户点开是空白）
    return it.docId ? `/post/${it.docId}` : null
  }
  if (t.startsWith('AVATAR_') || t === 'CERTIFICATE') return '/profile'
  return null
}

async function openItem(it) {
  // 乐观更新失败回退：接口失败就不置本地已读，避免「显示已读但服务端仍推未读」的不一致
  if (!it.read) { try { await notificationRead(it.id); it.read = true } catch { /* 失败保持未读 */ } }
  const to = routeOf(it)
  if (to) router.push(to)
  else window.__kbMsg?.info(it.summary || titleOf(it))
}

async function readAll() {
  // 失败不本地置已读（外部审查 2026-10-12）
  try { await notificationReadAll() } catch { return }
  items.value.forEach((i) => { i.read = true })
  n.setUnread(0)
  window.__kbMsg?.success('已全部标记为已读')
}

onMounted(load)
// SSE 推送到达（version 自增）时刷新列表与未读
watch(() => n.version, load)
</script>

<template>
  <div class="msgs">
    <div class="m-header">
      <div class="m-title" style="flex:1 1 auto">消息</div>
      <button v-if="items.some(i => !i.read)" class="hdr-act" @click="readAll">
        <el-icon><CircleCheckFilled /></el-icon><span>全读</span>
      </button>
    </div>

    <!-- 分段筛选 -->
    <div class="body">
      <div class="seg">
        <button :class="{ on: filter === 'all' }" @click="filter = 'all'">全部</button>
        <button :class="{ on: filter === 'unread' }" @click="filter = 'unread'">
          未读<span v-if="n.unread" class="seg-badge">{{ n.unread > 99 ? '99+' : n.unread }}</span>
        </button>
      </div>
    </div>

    <div class="body">
      <div v-if="loading" class="m-card">
        <div v-for="i in 5" :key="i" style="padding:14px;display:flex;gap:12px">
          <div class="m-skel" style="width:38px;height:38px;border-radius:11px;flex:none"></div>
          <div style="flex:1">
            <div class="m-skel" style="height:15px;width:50%"></div>
            <div class="m-skel" style="height:12px;width:80%;margin-top:8px"></div>
          </div>
        </div>
      </div>

      <!-- 私信入口：与通知分开，点进独立的会话列表 -->
      <div class="m-card" style="margin-bottom:10px">
        <button class="m-row msg" type="button" @click="router.push('/dm')">
          <span class="m-ico blue"><el-icon><ChatDotRound /></el-icon></span>
          <span class="m-row-body">
            <span class="mr-top">
              <span class="m-row-title">私信</span>
              <span class="mr-time">{{ dmUnreadCount ? dmUnreadCount + ' 条未读' : '' }}</span>
            </span>
            <span class="m-row-desc">与作者、同事一对一聊天</span>
          </span>
          <el-icon class="m-row-extra"><Right /></el-icon>
        </button>
      </div>

      <!-- 应用更新：置顶（本地状态，不走服务端通知） -->
      <div v-if="dlShow" class="m-card" style="margin-bottom:10px">
        <button class="m-row msg" type="button" @click="dlTap">
          <span class="m-ico blue"><el-icon><component :is="dlDone ? 'CircleCheckFilled' : 'Download'" /></el-icon></span>
          <span class="m-row-body">
            <span class="mr-top">
              <span class="m-row-title">应用更新</span>
              <span class="mr-time">{{ dlActive ? '进行中' : '已完成' }}</span>
            </span>
            <span class="m-row-desc">{{ dlDesc }}</span>
          </span>
        </button>
      </div>

      <div v-else-if="shown.length" class="m-card">
        <button v-for="it in shown" :key="it.id" class="m-row msg" type="button" @click="openItem(it)">
          <span class="m-ico" :class="iconOf(it.type).c">
            <el-icon><component :is="iconOf(it.type).i" /></el-icon>
          </span>
          <span class="m-row-body">
            <span class="mr-top">
              <span class="m-row-title" :class="{ unread: !it.read }">{{ titleOf(it) }}</span>
              <span class="mr-time">{{ fmtTime(it.createdAt) }}</span>
            </span>
            <span class="m-row-desc">{{ it.summary || '' }}</span>
          </span>
          <i v-if="!it.read" class="unread-dot"></i>
        </button>
      </div>

      <div v-else class="m-card">
        <div class="m-empty">
          <span class="ico"><el-icon :size="30"><Bell /></el-icon></span>
          <div>{{ filter === 'unread' ? '没有未读消息' : '还没有收到消息' }}</div>
        </div>
      </div>
    </div>

    <div style="height:12px"></div>
  </div>
</template>

<style scoped>
.msgs { min-height: 100%; }
.body { padding: 0 16px; }
.hdr-act {
  border: none; background: transparent; color: var(--m-primary);
  font-size: 13.5px; font-weight: 600; display: inline-flex; align-items: center; gap: 4px;
  cursor: pointer; padding: 6px 4px;
}

.seg {
  display: flex; gap: 4px; background: var(--m-border);
  padding: 3px; border-radius: 11px; margin-bottom: 12px;
}
.seg button {
  flex: 1 1 0; height: 33px; border: none; border-radius: 9px;
  background: transparent; color: var(--m-text-sub);
  font-size: 13.5px; font-weight: 500; cursor: pointer;
  display: inline-flex; align-items: center; justify-content: center; gap: 4px;
  transition: background-color .16s, color .16s;
}
.seg button.on { background: var(--m-card); color: var(--m-text); font-weight: 600; box-shadow: var(--m-shadow); }
.seg-badge {
  min-width: 16px; height: 16px; padding: 0 4px; border-radius: 8px;
  background: #ef4444; color: #fff; font-size: 10px; line-height: 16px; font-weight: 600;
}

.msg { align-items: flex-start; }
.mr-top { display: flex; align-items: baseline; gap: 8px; }
.mr-top .m-row-title { flex: 1 1 auto; }
.m-row-title.unread { font-weight: 700; }
.mr-time { flex: none; font-size: 11.5px; color: var(--m-text-dim); }
.unread-dot {
  position: absolute; right: 12px;
  width: 7px; height: 7px; border-radius: 50%; background: #ef4444;
  margin-top: 22px;
}
.msg { position: relative; }
</style>
