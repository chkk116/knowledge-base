<script setup>
/**
 * 本地草稿页（离线创建的文档）
 *
 * 用户在这里：
 *  - 查看 / 修改待同步内容
 *  - 手动触发同步（或等待自动同步）
 *  - 放弃这篇草稿
 */
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import {
  getQueuedDoc, updateQueuedDoc, removeQueuedDoc, retryOne
} from '../../utils/offlineStore'
import { mySpaces, resolveDefaultSpaceId, rememberSpace } from '../../utils/spaceResolver'

const route = useRoute()
const router = useRouter()
const localId = route.params.localId

const item = ref(null)
const loading = ref(true)
const saving = ref(false)
const title = ref('')
const content = ref('')
const spaces = ref([])
const targetSpace = ref(null)
const sheetOpen = ref(false)
const online = ref(navigator.onLine)

const targetName = computed(() => {
  const s = spaces.value.find((x) => x.id === targetSpace.value)
  return s ? s.name : '我的文档'
})

function htmlToText(html) {
  const d = document.createElement('div')
  d.innerHTML = html || ''
  // innerText 会按块级元素补换行；textContent 会把 <p>a</p><p>b</p> 拼成 "ab"（段落合并/内容丢失）
  return (d.innerText || '').trim()
}
function textToHtml(text) {
  return (text || '').split(/\n{2,}/).map((p) =>
    '<p>' + p.split('\n').map((l) =>
      String(l).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]))
    ).join('<br>') + '</p>'
  ).join('')
}

async function load() {
  loading.value = true
  try {
    const it = await getQueuedDoc(localId)
    if (!it) { window.__kbMsg?.warning('草稿已被同步或删除'); router.replace('/docs'); return }
    item.value = it
    title.value = it.title || ''
    content.value = htmlToText(it.content)
    targetSpace.value = it.spaceId
    spaces.value = await mySpaces().catch(() => [])
    if (!targetSpace.value) targetSpace.value = await resolveDefaultSpaceId().catch(() => null)
  } finally {
    loading.value = false
  }
}

async function persist() {
  await updateQueuedDoc(localId, {
    title: title.value.trim() || '未命名文档',
    content: textToHtml(content.value),
    spaceId: targetSpace.value
  })
}

async function saveDraft() {
  saving.value = true
  try { await persist(); window.__kbMsg?.success('草稿已更新') }
  catch { window.__kbMsg?.error('更新失败') }
  finally { saving.value = false }
}

async function syncNow() {
  if (!online.value) { window.__kbMsg?.warning('当前没有网络'); return }
  saving.value = true
  try {
    await persist()
    if (targetSpace.value) rememberSpace(targetSpace.value)
    const n = await retryOne(localId)
    if (n > 0) { window.__kbMsg?.success('已同步到文档库'); router.replace('/docs') }
    else window.__kbMsg?.error('同步失败，请稍后再试')
  } catch (e) {
    window.__kbMsg?.error('同步失败：' + ((e && e.message) || e))
  } finally { saving.value = false }
}

async function discard() {
  try {
    await ElMessageBox.confirm('确定放弃这篇草稿吗？内容将无法找回。', '放弃草稿', {
      type: 'warning', confirmButtonText: '放弃', cancelButtonText: '取消',
      confirmButtonClass: 'el-button--danger'
    })
  } catch { return }
  await removeQueuedDoc(localId)
  window.__kbMsg?.success('已放弃')
  router.replace('/docs')
}

function chooseSpace(s) {
  targetSpace.value = s.id
  sheetOpen.value = false
}

function onOnline() { online.value = true }

onMounted(() => { load(); window.addEventListener('online', onOnline) })
onUnmounted(() => window.removeEventListener('online', onOnline))
</script>

<template>
  <div class="dr">
    <header class="dr-head">
      <button class="m-header-act" @click="router.back()"><el-icon :size="20"><ArrowLeft /></el-icon></button>
      <div class="dr-title">本地草稿</div>
      <button class="m-header-act" @click="discard"><el-icon><Delete /></el-icon></button>
    </header>

    <div v-if="loading" class="body">
      <div class="m-skel" style="height:26px;width:70%"></div>
      <div class="m-skel" style="height:15px;margin-top:16px"></div>
      <div class="m-skel" style="height:15px;width:85%;margin-top:10px"></div>
    </div>

    <template v-else-if="item">
      <div class="body">
        <div class="draft-note">
          <el-icon><Upload /></el-icon>
          <span>{{ online ? '可以同步了，点下方「同步到文档库」' : '当前离线，联网后会自动同步' }}</span>
        </div>
        <div v-if="item.lastError" class="draft-err">
          <el-icon><WarningFilled /></el-icon>{{ item.lastError }}（已尝试 {{ item.tries }} 次）
        </div>
      </div>

      <div class="body">
        <input v-model="title" class="t-input" placeholder="标题" />
        <textarea v-model="content" class="t-body" rows="13" placeholder="正文内容"></textarea>
      </div>

      <div class="body">
        <div class="field-row" @click="sheetOpen = true">
          <span class="fr-k">同步到</span>
          <span class="fr-v">{{ targetName }} <el-icon><Right /></el-icon></span>
        </div>
      </div>

      <div class="dr-actions">
        <button class="m-btn ghost" @click="saveDraft" :disabled="saving">保存草稿</button>
        <button class="m-btn" @click="syncNow" :disabled="saving || !online">
          <el-icon v-if="saving" class="rot"><Loading /></el-icon>
          同步到文档库
        </button>
      </div>
    </template>

    <transition name="sheet-up">
      <div v-if="sheetOpen" class="m-sheet-mask" @click.self="sheetOpen = false">
        <div class="m-sheet">
          <div class="m-sheet-grip"></div>
          <div class="m-sheet-title">同步到哪个空间</div>
          <button v-for="s in spaces" :key="s.id" class="m-sheet-item" @click="chooseSpace(s)">
            <el-icon><component :is="s.type === 'PERSONAL' ? 'User' : 'DocumentCopy'" /></el-icon>
            <span style="flex:1 1 auto;text-align:left">{{ s.name }}</span>
            <el-icon v-if="targetSpace === s.id" style="color:var(--m-primary)"><Check /></el-icon>
          </button>
          <div v-if="!spaces.length" style="padding:20px;text-align:center;color:var(--m-text-dim);font-size:13px">
            离线中，无法读取空间列表
          </div>
          <button class="m-sheet-item cancel" @click="sheetOpen = false">取消</button>
        </div>
      </div>
    </transition>
  </div>
</template>

<style scoped>
.dr { min-height: 100%; }
.body { padding: 0 16px; }
.dr-head {
  position: sticky; top: 0; z-index: 30;
  display: flex; align-items: center; gap: 6px;
  padding: calc(env(safe-area-inset-top) + 8px) 8px 8px;
  background: var(--m-bg);
}
.dr-title { flex: 1 1 auto; text-align: center; font-size: 16.5px; font-weight: 600; }

.draft-note, .draft-err {
  display: flex; align-items: center; gap: 6px;
  padding: 10px 12px; border-radius: 12px; font-size: 12.5px; margin-bottom: 10px;
}
.draft-note { background: #fff7ed; color: #9a3412; }
.draft-err { background: #fef2f2; color: #991b1b; }
html.dark .draft-note { background: #3b2411; color: #fdba74; }
html.dark .draft-err { background: #3f1d1d; color: #fca5a5; }

.t-input {
  width: 100%; box-sizing: border-box; border: none; outline: none; background: transparent;
  font-size: 21px; font-weight: 700; color: var(--m-text); font-family: inherit; padding: 6px 0 12px;
}
.t-body {
  width: 100%; box-sizing: border-box; border: none; outline: none; background: transparent;
  resize: vertical; font-size: 16px; line-height: 1.75; color: var(--m-text);
  font-family: inherit; min-height: 180px;
}
.field-row {
  display: flex; align-items: center; justify-content: space-between;
  background: var(--m-card); border-radius: var(--m-radius); box-shadow: var(--m-shadow);
  padding: 14px; font-size: 14.5px; cursor: pointer; margin-top: 16px;
}
.fr-k { color: var(--m-text-dim); font-size: 13.5px; }
.fr-v { color: var(--m-primary); font-weight: 600; display: inline-flex; align-items: center; gap: 2px; }
.dr-actions { display: flex; gap: 10px; padding: 22px 16px 30px; }
.dr-actions .m-btn { flex: 1 1 0; width: auto; }
.rot { animation: kb-rot 1s linear infinite; }
@keyframes kb-rot { to { transform: rotate(360deg); } }
</style>
