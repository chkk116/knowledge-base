<script setup>
import { ref, computed, watch } from 'vue'
import { Bell } from '@element-plus/icons-vue'

// 由父组件（Home）传入公告列表；本组件只负责渲染卡片 + 置顶居中弹窗
const props = defineProps({
  items: { type: Array, default: () => [] }
})
const emit = defineEmits(['close'])

const pinnedVisible = ref(false)

// 仅在「刚登录」时弹置顶公告：登录动作会写入 sessionStorage['kb_just_logged_in']，
// 首页公告组件读到该标记且存在置顶公告才弹，弹完即清该标记。
// 因此：刷新、新开标签直达、会话内任意跳转都不会再弹，只有真正走登录流程才会弹。
const JUST_LOGIN_KEY = 'kb_just_logged_in'

const pinnedList = computed(() => (props.items || []).filter((a) => a.pinned))

// 展开全部 / 收起：内容超过 3 行截断阈值（约 60 字）才显示该按钮
const expandedAll = ref(false)
const hasLongContent = computed(() =>
  (props.items || []).some((a) => (a.content || '').length > 60)
)

// 把公告内容里的裸 URL 抽出来变成按钮，其余文字原样保留。
// 按钮文案按链接类型区分：指向文件（按扩展名判断）→「下载」，普通网页 →「查看详情」
const URL_RE = /(https?:\/\/[^\s，。；、）)】》"'<>]+)/g
// 常见可直接下载的文件扩展名（只看路径部分，忽略 query）
const DL_EXT_RE = /\.(zip|rar|7z|tar|gz|tgz|bz2|xz|exe|msi|deb|rpm|dmg|pkg|apk|iso|img|gho|vsix|jar|war|pdf|docx?|xlsx?|pptx?|txt|md|whl|bin|cab|msp|msu)(?=[?#]|$)/i
// 下载站专用子域：download.xxx.com / dl.xxx.com / files.xxx.com / get.xxx.com
const DL_HOST_RE = /(^|\.)(download|dl|files|get)\d*\./i
function isDownloadUrl(u) {
  let host = '', path = u.split('?')[0]
  try { const p = new URL(u); host = p.hostname; path = p.pathname } catch { /* 非法 URL 就按原文判断 */ }
  if (DL_EXT_RE.test(path)) return true
  if (DL_HOST_RE.test(host)) return true
  // 网页形式的下载页（如 visualstudio.microsoft.com/zh-hans/downloads/），只看路径不看 query
  return /download|下载/i.test(path)
}
function segContent(text) {
  if (!text) return []
  const segs = []
  let last = 0, m, linkNo = 0
  URL_RE.lastIndex = 0
  while ((m = URL_RE.exec(text))) {
    if (m.index > last) segs.push({ t: 'x', v: text.slice(last, m.index) })
    segs.push({ t: 'a', v: m[0], no: ++linkNo, dl: isDownloadUrl(m[0]) })
    last = m.index + m[0].length
  }
  if (last < text.length) segs.push({ t: 'x', v: text.slice(last) })
  return segs
}

// 点 ×：通知父组件把整块侧栏收起（关闭状态由 Home 持久化到 sessionStorage），帖子列表随即撑满该位置
const onCloseCard = () => {
  emit('close')
}

// 数据到达后，若带「刚登录」标记且存在置顶公告则弹出居中窗（弹完清除标记，刷新不再弹）
watch(pinnedList, (list) => {
  if (!list.length) return
  if (sessionStorage.getItem(JUST_LOGIN_KEY)) {
    pinnedVisible.value = true
    sessionStorage.removeItem(JUST_LOGIN_KEY)
  } else {
    // 非登录进入：清掉可能残留的标记，避免之后误弹
    sessionStorage.removeItem(JUST_LOGIN_KEY)
  }
}, { immediate: true })

const closePinned = () => {
  pinnedVisible.value = false
  sessionStorage.removeItem(JUST_LOGIN_KEY)
}
</script>

<template>
  <div>
    <!-- 右侧边栏·公告卡片 -->
    <div v-if="items.length" class="ann-card">
      <div class="ann-card-head">
        <el-icon class="ann-head-ic"><Bell /></el-icon>
        <span>公告</span>
        <button v-if="hasLongContent" class="ann-expand" type="button" @click="expandedAll = !expandedAll">
          {{ expandedAll ? '收起' : '展开全部' }}
        </button>
        <button class="ann-close" type="button" title="关闭" @click="onCloseCard">×</button>
      </div>
      <div
        v-for="item in items"
        :key="item.id"
        :class="['announcement-item', { pinned: item.pinned }]"
      >
        <div class="ann-body">
          <div class="ann-title-row">
            <span v-if="item.pinned" class="ann-pin">置顶</span>
            <span class="ann-title">{{ item.title }}</span>
          </div>
          <div v-if="item.content" :class="['ann-content', { expanded: expandedAll }]">
            <template v-for="(sg, i) in segContent(item.content)" :key="i">
              <a v-if="sg.t === 'a'" class="ann-link" :class="{ dl: sg.dl }" :href="sg.v" target="_blank" rel="noopener"
                 :title="sg.v">{{ sg.v }}</a>
              <span v-else>{{ sg.v }}</span>
            </template>
          </div>
        </div>
      </div>
    </div>

    <!-- 置顶公告：居中悬浮弹窗 -->
    <el-dialog
      v-model="pinnedVisible"
      class="ann-pinned-dialog"
      width="480px"
      align-center
      :show-close="false"
    >
      <template #header>
        <div class="ann-pinned-head">
          <el-icon class="ann-pinned-bell"><Bell /></el-icon>
          <span>重要公告</span>
        </div>
      </template>
      <div class="ann-pinned-list">
        <div v-for="a in pinnedList" :key="a.id" class="ann-pinned-card">
          <div class="ann-pinned-title">{{ a.title }}</div>
          <div v-if="a.content" class="ann-pinned-content">{{ a.content }}</div>
        </div>
      </div>
      <template #footer>
        <el-button type="primary" @click="closePinned">我知道了</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.ann-card {
  border: 1px solid #e5e7eb;
  border-radius: 12px;
  background: #fff;
  overflow: hidden;
}
.ann-card-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 14px;
  font-weight: 700;
  font-size: 15px;
  color: #111827;
  border-bottom: 1px solid #f3f4f6;
  background: #f9fafb;
}
.ann-head-ic {
  color: #f59e0b;
}
.ann-close {
  margin-left: auto;
  border: none;
  background: transparent;
  font-size: 18px;
  line-height: 1;
  color: #9ca3af;
  cursor: pointer;
  padding: 0 2px;
  transition: color .15s;
}
.ann-close:hover { color: #ef4444; }
.announcement-item {
  padding: 12px 14px;
  border-bottom: 1px solid #f3f4f6;
}
.announcement-item:last-child {
  border-bottom: none;
}
.announcement-item.pinned {
  background: #fefce8;
}
.ann-body {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  line-height: 1.5;
  color: #374151;
}
.ann-pin {
  display: inline-block;
  padding: 0 6px;
  border-radius: 4px;
  background: #ef4444;
  color: #fff;
  font-size: 12px;
  font-weight: 600;
}
.ann-title-row {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 2px;
}
.ann-title {
  font-weight: 600;
  color: #111827;
}
.ann-content {
  color: #6b7280;
  font-size: 13px;
  width: 100%;
  white-space: pre-wrap;
  word-break: break-all;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
  line-height: 1.7;
}
.ann-content.expanded {
  -webkit-line-clamp: unset;
  overflow: visible;
}
/* 头部「展开全部 / 收起」小按钮 */
.ann-expand {
  margin-left: auto;
  border: none;
  background: transparent;
  font-size: 12px;
  color: #4f46e5;
  cursor: pointer;
  padding: 0;
  transition: color .15s;
}
.ann-expand:hover { color: #6366f1; text-decoration: underline; }
/* 展开按钮在场时，收掉关闭按钮的 auto 左边距，避免两个 auto 平分空间把按钮挤到中间 */
.ann-expand + .ann-close { margin-left: 10px; }
/* 链接直接展示 URL 原文，可点击；文件下载链接绿色、普通链接靛紫 */
.ann-link {
  color: #4f46e5;
  font-size: 12px;
  text-decoration: none;
  word-break: break-all;
}
.ann-link:hover { text-decoration: underline; }
.ann-link.dl { color: #059669; }
.ann-link.dl:hover { color: #047857; }

/* 居中弹窗 */
.ann-pinned-head {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 700;
  color: #b91c1c;
  font-size: 16px;
}
.ann-pinned-bell {
  color: #ef4444;
}
.ann-pinned-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.ann-pinned-card {
  border: 1px solid #fecaca;
  background: #fef2f2;
  border-radius: 10px;
  padding: 12px 14px;
}
.ann-pinned-title {
  font-weight: 700;
  color: #991b1b;
  font-size: 15px;
}
.ann-pinned-content {
  margin-top: 6px;
  color: #7f1d1d;
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
}

/* 暗色模式适配 */
html.dark .ann-card { border-color: var(--el-border-color); background: var(--el-bg-color); }
html.dark .ann-card-head { color: var(--el-text-color-primary); border-bottom-color: var(--el-border-color-lighter); background: var(--el-fill-color-light); }
html.dark .ann-head-ic { color: #fbbf24; }
html.dark .ann-close { color: var(--el-text-color-placeholder); }
html.dark .ann-close:hover { color: #f87171; }
html.dark .ann-expand { color: #a5b4fc; }
html.dark .ann-expand:hover { color: #c7d2fe; }
html.dark .announcement-item { border-bottom-color: var(--el-border-color-lighter); }
html.dark .announcement-item.pinned { background: rgba(250, 204, 21, .10); }
html.dark .ann-body { color: var(--el-text-color-regular); }
html.dark .ann-title { color: var(--el-text-color-primary); }
html.dark .ann-content { color: var(--el-text-color-secondary); }
html.dark .ann-link { color: #a5b4fc; }
html.dark .ann-link:hover { color: #c7d2fe; }
html.dark .ann-link.dl { color: #6ee7b7; }
html.dark .ann-link.dl:hover { color: #a7f3d0; }
html.dark .ann-pinned-head { color: #fca5a5; }
html.dark .ann-pinned-bell { color: #f87171; }
html.dark .ann-pinned-card { border-color: rgba(248, 113, 113, .35); background: rgba(239, 68, 68, .12); }
html.dark .ann-pinned-title { color: #fca5a5; }
html.dark .ann-pinned-content { color: #e8b4b4; }

/* 置顶弹窗为 el-dialog 内部 DOM（不带 scoped 属性），需 :deep() 命中 */
html.dark .ann-pinned-dialog :deep(.el-dialog__header) { background: var(--el-fill-color-light); border-bottom: 1px solid var(--el-border-color-lighter); margin-right: 0; }
html.dark .ann-pinned-dialog :deep(.el-dialog__body) { background: var(--el-bg-color-overlay); padding-top: 16px; }
html.dark .ann-pinned-dialog :deep(.el-dialog__footer) { background: var(--el-bg-color-overlay); border-top: 1px solid var(--el-border-color-lighter); }
</style>
