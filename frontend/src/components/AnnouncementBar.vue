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
        <button class="ann-close" type="button" title="关闭" @click="onCloseCard">×</button>
      </div>
      <div
        v-for="item in items"
        :key="item.id"
        :class="['announcement-item', { pinned: item.pinned }]"
      >
        <div class="ann-body">
          <span v-if="item.pinned" class="ann-pin">置顶</span>
          <span class="ann-title">{{ item.title }}</span>
          <span v-if="item.content" class="ann-content">{{ item.content }}</span>
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
.ann-title {
  font-weight: 600;
  color: #111827;
}
.ann-content {
  color: #6b7280;
  font-size: 13px;
  width: 100%;
  white-space: pre-wrap;
}

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
</style>
