<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { dmConversations } from '../../api/dm'
import { refreshDmUnread } from '../../store/dm'

/**
 * 移动端私信会话列表。
 * 入口：消息页顶部「私信」卡片 / 个人主页。
 * 点击进入 /dm/:peerId 聊天页（全屏，隐藏底部 Tab）。
 */
const router = useRouter()
const loading = ref(true)
const convs = ref([])

function fmtTime(t) {
  if (!t) return ''
  const d = new Date(t)
  if (Number.isNaN(d.getTime())) return ''
  const diff = Date.now() - d.getTime()
  if (diff < 60000) return '刚刚'
  if (diff < 3600000) return Math.floor(diff / 60000) + ' 分钟前'
  if (diff < 86400000) return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
  if (diff < 86400000 * 7) return Math.floor(diff / 86400000) + ' 天前'
  return `${d.getMonth() + 1}/${d.getDate()}`
}

const preview = (s) => String(s || '').replace(/\[\[(e|t):[A-Za-z0-9_-]+\]\]/g, '[表情]')

function open(c) {
  router.push('/dm/' + c.peerId)
}

onMounted(async () => {
  try {
    const { data } = await dmConversations()
    convs.value = data || []
  } catch (e) {
    convs.value = []
  } finally {
    loading.value = false
  }
  refreshDmUnread().catch(() => {})
})
</script>

<template>
  <div class="dm-list">
    <div class="m-header">
      <button class="back" type="button" aria-label="返回" @click="router.back()">
        <el-icon :size="18"><ArrowLeft /></el-icon>
      </button>
      <div class="m-title">私信</div>
      <span class="spacer" />
    </div>

    <div class="body">
      <div v-if="loading" class="m-card">
        <div v-for="i in 5" :key="i" style="padding:14px;display:flex;gap:12px">
          <div class="m-skel" style="width:42px;height:42px;border-radius:12px;flex:none"></div>
          <div style="flex:1">
            <div class="m-skel" style="height:15px;width:45%"></div>
            <div class="m-skel" style="height:12px;width:75%;margin-top:8px"></div>
          </div>
        </div>
      </div>

      <div v-else-if="convs.length" class="m-card">
        <button v-for="c in convs" :key="c.peerId" class="m-row conv" type="button" @click="open(c)">
          <el-avatar :size="42" :src="c.avatar || undefined">{{ (c.name || '?').slice(0, 1) }}</el-avatar>
          <span class="m-row-body">
            <span class="conv-top">
              <span class="conv-name">{{ c.name }}</span>
              <span class="conv-time">{{ fmtTime(c.lastAt) }}</span>
            </span>
            <span class="conv-bot">
              <span class="conv-msg">{{ c.lastFromMe ? '我：' : '' }}{{ preview(c.lastMessage) }}</span>
              <span v-if="c.unread" class="conv-badge">{{ c.unread > 99 ? '99+' : c.unread }}</span>
            </span>
          </span>
        </button>
      </div>

      <div v-else class="m-card">
        <div class="m-empty">
          <span class="ico"><el-icon :size="30"><ChatDotRound /></el-icon></span>
          <div>还没有私信</div>
          <p class="tip">在文章作者卡片上可以发起私信</p>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.dm-list { min-height: 100%; background: var(--m-bg); }
.m-header { display: flex; align-items: center; gap: 6px; }
.back {
  width: 32px; height: 32px; border: 0; background: transparent; color: var(--m-text);
  display: inline-flex; align-items: center; justify-content: center; border-radius: 10px; cursor: pointer;
}
.back:active { background: var(--m-border); }
.spacer { flex: 1; }
.body { padding: 0 16px 16px; }

.conv { align-items: center; }
.conv-top { display: flex; align-items: baseline; gap: 8px; }
.conv-name { flex: 1; font-size: 15px; font-weight: 600; color: var(--m-text); }
.conv-time { flex: none; font-size: 11.5px; color: var(--m-text-dim); }
.conv-bot { display: flex; align-items: center; gap: 8px; margin-top: 3px; }
.conv-msg {
  flex: 1; min-width: 0; font-size: 13px; color: var(--m-text-sub);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.conv-badge {
  flex: none; min-width: 17px; height: 17px; padding: 0 5px; border-radius: 9px;
  background: #ef4444; color: #fff; font-size: 10.5px; line-height: 17px; font-weight: 600; text-align: center;
}
.tip { margin: 6px 0 0; font-size: 12px; color: var(--m-text-dim); }
</style>
