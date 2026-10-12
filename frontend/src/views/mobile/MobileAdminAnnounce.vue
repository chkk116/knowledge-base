<script setup>
/**
 * 移动端原生「公告管理」页（替代 iframe 嵌桌面 AnnouncementManage）
 *
 * 能力：列表（启用/停用、置顶状态徽标）/ 新建公告（底部弹层表单）/
 *   行点开弹层做高频操作——启用·停用、置顶·取消置顶、删除。
 * 数据接口：GET|POST|PUT|DELETE /api/admin/announcements（Map 透传）。
 */
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import {
  listAnnouncementsAdmin, createAnnouncement, updateAnnouncement, deleteAnnouncement,
} from '../../api/kb'

const router = useRouter()
const list = ref([])
const loading = ref(false)
const busy = ref(false)

async function load() {
  if (loading.value) return
  loading.value = true
  try {
    const { data } = await listAnnouncementsAdmin()
    const rows = Array.isArray(data) ? data : (data.content || [])
    list.value = rows
  } catch (e) {
    window.__kbMsg?.error(e?.response?.data?.error || '加载失败')
  } finally { loading.value = false }
}

// ---------- 行操作 ----------
const active = ref(null)
const sheetOpen = ref(false)
function openSheet(a) { active.value = a; sheetOpen.value = true }

async function run(fn, okMsg) {
  if (busy.value) return
  busy.value = true
  try { await fn(); window.__kbMsg?.success(okMsg); sheetOpen.value = false; load() }
  catch (e) { window.__kbMsg?.error(e?.response?.data?.error || '操作失败') }
  finally { busy.value = false }
}
// active.value 空值守卫（外部审查 2026-10-12：doDelete 有守卫，两个 toggle 原来没有）
const toggleEnabled = () => {
  if (!active.value) return
  run(
    () => updateAnnouncement(active.value.id, { enabled: !active.value.enabled }),
    active.value.enabled ? '已停用' : '已启用'
  )
}
const togglePin = () => {
  if (!active.value) return
  run(
    () => updateAnnouncement(active.value.id, { pinned: !active.value.pinned }),
    active.value.pinned ? '已取消置顶' : '已置顶'
  )
}
const doDelete = () => {
  if (busy.value || !active.value) return
  ElMessageBox.confirm(`确定删除公告《${active.value.title || ''}》？`, '删除公告', {
    confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning',
  }).then(() => run(() => deleteAnnouncement(active.value.id), '已删除')).catch(() => {})
}

// ---------- 新建公告 ----------
const createOpen = ref(false)
const form = ref({ title: '', content: '', pinned: false, enabled: true })
function openCreate() { form.value = { title: '', content: '', pinned: false, enabled: true }; createOpen.value = true }
async function submitCreate() {
  const t = (form.value.title || '').trim()
  if (!t) { window.__kbMsg?.warning('请填写标题'); return }
  if (busy.value) return
  busy.value = true
  try {
    await createAnnouncement({ ...form.value, title: t })
    window.__kbMsg?.success('已发布公告')
    createOpen.value = false
    load()
  } catch (e) {
    window.__kbMsg?.error(e?.response?.data?.error || '创建失败')
  } finally { busy.value = false }
}

const fmtDate = (s) => (s ? String(s).slice(0, 10) : '—')
const excerpt = (c) => { const t = (c || '').replace(/\s+/g, ' ').trim(); return t.length > 60 ? t.slice(0, 60) + '…' : t }
load()
</script>

<template>
  <div class="page">
    <header class="m-header solid">
      <button class="m-header-act" @click="router.back()"><el-icon :size="20"><ArrowLeft /></el-icon></button>
      <div class="h-title">公告管理</div>
      <button class="h-ph add" @click="openCreate()"><el-icon><Plus /></el-icon></button>
    </header>

    <div class="list">
      <div v-if="!list.length && !loading" class="empty">还没有公告，点右上角 + 新建</div>
      <div v-for="a in list" :key="a.id" class="m-card an">
        <div class="an-head">
          <span class="an-title">
            <el-icon v-if="a.pinned" class="pin"><StarFilled /></el-icon>
            {{ a.title || '未命名公告' }}
          </span>
          <span class="m-chip" :class="a.enabled ? 'green' : 'gray'">{{ a.enabled ? '已启用' : '已停用' }}</span>
        </div>
        <div class="an-excerpt" v-if="excerpt(a.content)">{{ excerpt(a.content) }}</div>
        <div class="an-meta">{{ fmtDate(a.createdAt) }}</div>
        <button class="an-edit" type="button" @click="openSheet(a)">管理</button>
      </div>
    </div>

    <!-- 行操作弹层 -->
    <transition name="sheet-up">
      <div v-if="sheetOpen" class="m-sheet-mask" @click.self="sheetOpen = false">
        <div class="m-sheet">
          <div class="m-sheet-grip"></div>
          <div class="m-sheet-title">{{ active?.title }}</div>
          <button class="m-sheet-item" :disabled="busy" @click="toggleEnabled()">
            <el-icon><component :is="active?.enabled ? 'Hide' : 'View'" /></el-icon>
            {{ active?.enabled ? '停用公告' : '启用公告' }}
          </button>
          <button class="m-sheet-item" :disabled="busy" @click="togglePin()">
            <el-icon><component :is="active?.pinned ? 'Star' : 'StarFilled'" /></el-icon>
            {{ active?.pinned ? '取消置顶' : '置顶' }}
          </button>
          <button class="m-sheet-item danger" :disabled="busy" @click="doDelete()">
            <el-icon><Delete /></el-icon> 删除公告
          </button>
          <button class="m-sheet-item cancel" @click="sheetOpen = false">取消</button>
        </div>
      </div>
    </transition>

    <!-- 新建公告弹层 -->
    <transition name="sheet-up">
      <div v-if="createOpen" class="m-sheet-mask" @click.self="createOpen = false">
        <div class="m-sheet wide">
          <div class="m-sheet-grip"></div>
          <div class="m-sheet-title">新建公告</div>
          <input class="f-input" v-model="form.title" maxlength="60" placeholder="公告标题" />
          <textarea class="f-area" v-model="form.content" maxlength="500" rows="4" placeholder="公告内容"></textarea>
          <label class="f-row">
            <span>立即启用</span>
            <input type="checkbox" v-model="form.enabled" />
          </label>
          <label class="f-row">
            <span>置顶</span>
            <input type="checkbox" v-model="form.pinned" />
          </label>
          <div class="f-actions">
            <button class="m-btn ghost" :disabled="busy" @click="createOpen = false">取消</button>
            <button class="m-btn" :disabled="busy" @click="submitCreate()">发布</button>
          </div>
        </div>
      </div>
    </transition>
  </div>
</template>

<style scoped>
.page { min-height: 100%; display: flex; flex-direction: column; background: var(--m-bg); }
.h-title { flex: 1 1 auto; text-align: center; font-size: 16.5px; font-weight: 600; }
.h-ph { width: 38px; }
.add { border: none; background: transparent; color: var(--m-primary); display: inline-flex; padding: 6px; font-size: 20px; }
.list { flex: 1 1 auto; padding: 12px 16px 24px; display: flex; flex-direction: column; gap: 12px; }
.empty { padding: 48px 0; text-align: center; color: var(--m-text-sub); font-size: 14px; }
.an { padding: 14px; position: relative; }
.an-head { display: flex; align-items: flex-start; gap: 8px; justify-content: space-between; }
.an-title { font-size: 15.5px; font-weight: 600; color: var(--m-text); line-height: 1.4; }
.pin { color: #f59e0b; font-size: 14px; margin-right: 2px; vertical-align: -1px; }
.an-excerpt { margin-top: 8px; font-size: 13.5px; color: var(--m-text-dim); line-height: 1.6; }
.an-meta { margin-top: 8px; font-size: 12.5px; color: var(--m-text-sub); }
.an-edit { position: absolute; right: 12px; bottom: 12px; border: none; background: var(--m-primary-weak, #e8eefe); color: var(--m-primary); font-size: 12.5px; padding: 5px 12px; border-radius: 14px; }
.m-sheet.wide { padding-bottom: 18px; }
.f-input, .f-area {
  width: 100%; box-sizing: border-box; border: 1px solid var(--m-border, #e5e6eb); border-radius: 10px;
  padding: 10px 12px; font-size: 15px; color: var(--m-text); background: var(--m-bg); font-family: inherit; outline: none;
}
.f-area { margin-top: 12px; resize: vertical; }
.f-row { display: flex; align-items: center; justify-content: space-between; margin-top: 14px; font-size: 14.5px; color: var(--m-text); }
.f-actions { display: flex; gap: 10px; margin-top: 18px; }
.f-actions .m-btn { flex: 1 1 0; }
</style>
