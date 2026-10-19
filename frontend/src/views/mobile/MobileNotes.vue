<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import { useRouter } from 'vue-router'
import { listMyAnnotations, updateDocAnnotation, deleteDocAnnotation } from '../../api/docShare'
import { createFlashcard } from '../../api/flashcard'

const router = useRouter()
const loading = ref(true)
const items = ref([])
const keyword = ref('')

// ---------- 摘录 → 复习卡片 ----------
const cardOpen = ref(false)
const cardSaving = ref(false)
const cardForm = ref({ front: '', back: '' })
const cardMeta = ref({ quote: '', shareId: null, docId: null })

function openCard(a) {
  // 背面预填摘录（有笔记就一起带上），正面留空让用户写问题——
  // 卡片的价值在「提问」，直接拿原文当正面就成了重读，记不住
  cardForm.value = {
    front: '',
    back: a.note ? `${a.quote}\n\n${a.note}` : a.quote,
  }
  cardMeta.value = { quote: a.quote, shareId: a.shareId, docId: a.docId }
  cardOpen.value = true
}

async function saveCard() {
  const front = cardForm.value.front.trim()
  const back = cardForm.value.back.trim()
  if (!front || !back) { window.__kbMsg?.warning('正面和背面都要填'); return }
  cardSaving.value = true
  try {
    await createFlashcard({
      front, back,
      source: cardMeta.value.quote,
      shareId: cardMeta.value.shareId,
      docId: cardMeta.value.docId,
    })
    cardOpen.value = false
    window.__kbMsg?.success('已生成卡片，可在「复习卡片」里复习')
  } catch (e) {
    window.__kbMsg?.error(e?.response?.data?.error || '生成失败')
  } finally {
    cardSaving.value = false
  }
}

const shown = computed(() => {
  const q = keyword.value.trim().toLowerCase()
  if (!q) return items.value
  return items.value.filter((a) =>
    String(a.docTitle || '').toLowerCase().includes(q) ||
    String(a.spaceName || '').toLowerCase().includes(q) ||
    String(a.quote || '').toLowerCase().includes(q) ||
    String(a.note || '').toLowerCase().includes(q))
})

function fmtDate(value) {
  if (!value) return ''
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return ''
  const diff = Date.now() - d.getTime()
  if (diff < 86400000) return `${Math.max(1, Math.floor(diff / 3600000))} 小时前`
  if (diff < 86400000 * 30) return `${Math.floor(diff / 86400000)} 天前`
  return `${d.getFullYear()}年${d.getMonth() + 1}月${d.getDate()}日`
}

async function load() {
  loading.value = true
  try {
    const { data } = await listMyAnnotations(200)
    items.value = Array.isArray(data) ? data : []
  } catch (e) {
    items.value = []
    window.__kbMsg?.error('摘录加载失败：' + (e.response?.data?.error || e.message || '请稍后重试'))
  } finally {
    loading.value = false
  }
}

function openDoc(a) { router.push(`/doc/${a.shareId}/${a.docId}`) }

async function editNote(a) {
  let value
  try {
    const r = await ElMessageBox.prompt('修改这条笔记', '编辑笔记', {
      inputValue: a.note || '', inputType: 'textarea',
      confirmButtonText: '保存', cancelButtonText: '取消',
    })
    value = r.value
  } catch { return }
  try {
    const { data } = await updateDocAnnotation(a.shareId, a.docId, a.id, value || '')
    const i = items.value.findIndex((x) => x.id === a.id)
    if (i >= 0) items.value[i] = data
    window.__kbMsg?.success('已更新')
  } catch (e) {
    window.__kbMsg?.error('更新失败：' + (e.response?.data?.error || e.message || '请稍后重试'))
  }
}

async function remove(a) {
  try {
    await ElMessageBox.confirm('删除这条摘录？', '删除摘录', {
      type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消',
      confirmButtonClass: 'el-button--danger',
    })
  } catch { return }
  try {
    await deleteDocAnnotation(a.shareId, a.docId, a.id)
    items.value = items.value.filter((x) => x.id !== a.id)
    window.__kbMsg?.success('已删除')
  } catch (e) {
    window.__kbMsg?.error('删除失败：' + (e.response?.data?.error || e.message || '请稍后重试'))
  }
}

onMounted(load)
</script>

<template>
  <div class="notes">
    <header class="m-header solid">
      <button class="m-header-act" type="button" aria-label="返回" @click="router.back()"><el-icon :size="20"><ArrowLeft /></el-icon></button>
      <div class="h-title">我的摘录</div>
      <button class="m-header-act" type="button" aria-label="刷新" @click="load"><el-icon><Refresh /></el-icon></button>
    </header>

    <div class="body">
      <label class="searchbox">
        <el-icon><Search /></el-icon>
        <input v-model="keyword" type="search" placeholder="搜索笔记、摘录、文档标题" />
        <button v-if="keyword" class="clear-search" type="button" aria-label="清除搜索" @click.prevent="keyword = ''"><el-icon><Close /></el-icon></button>
      </label>
      <div class="summary">共 {{ shown.length }} 条摘录<template v-if="keyword"> · 已筛选</template></div>

      <div v-if="loading" class="m-card loading-card">
        <div v-for="i in 4" :key="i" class="skeleton-row">
          <div class="m-skel" style="height:14px;width:58%"></div>
          <div class="m-skel" style="height:12px;width:36%;margin-top:9px"></div>
        </div>
      </div>

      <div v-else-if="shown.length" class="m-card note-list">
        <div v-for="a in shown" :key="a.id" class="note-item">
          <button class="note-main" type="button" @click="openDoc(a)">
            <div v-if="a.note" class="note-text">{{ a.note }}</div>
            <div class="note-quote">{{ a.quote }}</div>
            <div class="note-meta">
              <span class="note-doc">{{ a.docTitle || '文档' }}</span>
              <span class="dot-sep">·</span>
              <span>{{ a.spaceName || '' }}</span>
              <span class="dot-sep">·</span>
              <span>{{ fmtDate(a.createdAt) }}</span>
            </div>
          </button>
          <div class="note-ops">
            <button type="button" class="card-op" @click="openCard(a)">做卡片</button>
            <button type="button" @click="editNote(a)">编辑</button>
            <button type="button" class="danger" @click="remove(a)">删除</button>
          </div>
        </div>
      </div>

      <div v-else class="m-card empty">
        <div class="empty-icon"><el-icon :size="30"><Notebook /></el-icon></div>
        <div>{{ keyword ? '没有匹配的摘录' : '还没有摘录。阅读文档时选中文字即可保存片段和笔记。' }}</div>
        <button v-if="!keyword" class="m-btn sm" type="button" style="margin-top:10px" @click="router.push('/docs')">去文档库</button>
      </div>
      <div class="bottom-space"></div>
    </div>

    <!-- 摘录 → 卡片：正面留空让用户写问题，背面预填摘录 -->
    <transition name="sheet-up">
      <div v-if="cardOpen" class="sheet-mask" @click.self="cardOpen = false">
        <div class="sheet">
          <div class="sheet-title">做成复习卡片</div>
          <div class="sheet-quote">{{ cardMeta.quote }}</div>
          <div class="field">
            <label>正面（问题）· 必填</label>
            <textarea v-model="cardForm.front" rows="2" maxlength="500" placeholder="想让自己记住什么？例如：这条规则的适用场景是什么？"></textarea>
          </div>
          <div class="field">
            <label>背面（答案）· 已预填摘录</label>
            <textarea v-model="cardForm.back" rows="4" maxlength="5000"></textarea>
          </div>
          <div class="sheet-actions">
            <button class="ghost-btn" type="button" @click="cardOpen = false">取消</button>
            <button class="primary-btn" type="button" :disabled="cardSaving" @click="saveCard">{{ cardSaving ? '保存中…' : '生成卡片' }}</button>
          </div>
        </div>
      </div>
    </transition>
  </div>
</template>

<style scoped>
.notes { min-height: 100%; }
.body { padding: 0 16px; }
.searchbox {
  display: flex; align-items: center; gap: 8px; min-height: 44px; box-sizing: border-box;
  padding: 0 12px; border-radius: 13px; background: var(--m-card); color: var(--m-text-dim);
  box-shadow: 0 1px 2px rgba(0, 0, 0, .04), 0 6px 18px rgba(15, 23, 42, .04);
}
.searchbox input { flex: 1; min-width: 0; height: 42px; border: 0; outline: 0; background: transparent; color: var(--m-text); font: inherit; font-size: 14px; }
.clear-search { display: inline-grid; place-items: center; width: 30px; height: 30px; border: 0; background: transparent; color: var(--m-text-dim); }
.summary { padding: 10px 2px 8px; font-size: 12px; color: var(--m-text-dim); }
.note-item { display: flex; align-items: stretch; }
.note-item + .note-item { border-top: 1px solid var(--m-border); }
.note-main {
  flex: 1 1 auto; min-width: 0; padding: 12px 6px 12px 14px; text-align: left;
  border: 0; background: transparent; color: var(--m-text); cursor: pointer; font-family: inherit;
}
.note-main:active { background: var(--m-card-2); }
.note-text {
  font-size: 14px; font-weight: 600; line-height: 1.55; color: var(--m-primary);
  display: -webkit-box; -webkit-line-clamp: 3; -webkit-box-orient: vertical; overflow: hidden;
}
.note-quote {
  margin-top: 5px; font-size: 12.5px; line-height: 1.6; color: var(--m-text-dim);
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden;
}
.note-meta { display: flex; flex-wrap: wrap; align-items: center; gap: 5px; margin-top: 7px; font-size: 11px; color: var(--m-text-dim); }
.note-doc { max-width: 150px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.dot-sep { opacity: .55; }
.note-ops { flex: none; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 8px; padding: 0 10px; }
.note-ops button {
  border: none; background: transparent; color: var(--m-text-sub);
  font-size: 11.5px; font-family: inherit; cursor: pointer; padding: 2px 4px;
}
.note-ops button.danger { color: #e5484d; }
.empty { padding: 30px 16px; text-align: center; font-size: 13px; line-height: 1.7; color: var(--m-text-dim); }
.empty-icon { margin-bottom: 6px; color: var(--m-primary); }
.loading-card { padding: 2px 14px; }
.skeleton-row { padding: 13px 0; }
.skeleton-row + .skeleton-row { border-top: 1px solid var(--m-border); }
.bottom-space { height: 22px; }
/* 摘录 → 卡片 */
.card-op { color: var(--m-primary, #1677ff) !important; }
.sheet-mask { position: fixed; inset: 0; z-index: 60; background: rgba(0, 0, 0, .35); display: flex; align-items: flex-end; }
.sheet { width: 100%; background: var(--m-bg, #f5f6f7); border-radius: 16px 16px 0 0; padding: 16px 14px calc(16px + env(safe-area-inset-bottom)); }
.sheet-title { font-size: 16px; font-weight: 500; color: var(--m-text); margin-bottom: 10px; }
.sheet-quote { padding: 10px 12px; margin-bottom: 12px; border-left: 3px solid var(--m-primary, #1677ff); border-radius: 6px; background: #fff; font-size: 12.5px; line-height: 1.6; color: var(--m-text-dim, #4e5969); display: -webkit-box; -webkit-line-clamp: 3; -webkit-box-orient: vertical; overflow: hidden; }
.field { margin-bottom: 12px; }
.field label { display: block; font-size: 12px; color: var(--m-text-dim, #86909c); margin-bottom: 6px; }
.field textarea { width: 100%; padding: 10px 12px; border: 1px solid var(--m-border, #e5e6eb); border-radius: 10px; background: #fff; font: inherit; font-size: 14px; line-height: 1.6; color: var(--m-text); resize: none; }
.sheet-actions { display: flex; gap: 10px; }
.primary-btn { flex: 1; height: 44px; border: 0; border-radius: 12px; background: var(--m-primary, #1677ff); color: #fff; font-size: 15px; }
.primary-btn:disabled { opacity: .45; }
.ghost-btn { flex: none; width: 88px; height: 44px; border: 1px solid var(--m-border, #e5e6eb); border-radius: 12px; background: transparent; color: var(--m-text); font-size: 14px; }
</style>
