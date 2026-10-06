<script setup>
/**
 * 移动端原生「用户管理」页（替代 iframe 嵌桌面版：手机上桌面表格不可用）
 *
 * 能力：搜索 / 角色筛选 / 分页加载；行点开底部弹层做两个高频操作——
 *   ① 启用/禁用账号（POST /api/admin/users/{id}/toggle）
 *   ② 设为管理员 / 设为普通用户（PUT /api/admin/users/{id} {role}）
 * 其余低频操作（建号、配额、部门）仍去 Web 端做，手机上保持精简。
 */
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../../store/auth'
import api from '../../api/request'

const router = useRouter()
const auth = useAuthStore()

const keyword = ref('')
const filter = ref('all')          // all | admin | disabled
const users = ref([])
const page = ref(0)
const hasMore = ref(false)
const loading = ref(false)
const busy = ref(false)

const FILTERS = [
  { key: 'all', label: '全部' },
  { key: 'admin', label: '管理员' },
  { key: 'disabled', label: '已禁用' }
]

async function load(reset = false) {
  if (loading.value) return
  loading.value = true
  try {
    const p = reset ? 0 : page.value
    const params = { page: p, size: 20, sort: 'newest' }
    if (keyword.value.trim()) params.keyword = keyword.value.trim()
    if (filter.value === 'admin') params.role = 'ADMIN'
    if (filter.value === 'disabled') params.enabled = false
    const { data } = await api.get('/admin/users', { params })
    const rows = data.content || []
    users.value = reset ? rows : [...users.value, ...rows]
    page.value = p + 1
    hasMore.value = !data.last
  } catch (e) {
    window.__kbMsg?.error(e?.response?.data?.error || '加载失败')
  } finally {
    loading.value = false
  }
}

function doSearch() { load(true) }
function pickFilter(k) { filter.value = k; load(true) }

// ---------- 行操作（底部弹层） ----------
const active = ref(null)           // 当前操作的用户
const sheetOpen = ref(false)

function openSheet(u) {
  if (u.id === auth.user?.id) { window.__kbMsg?.info('不能对自己执行管理操作'); return }
  active.value = u
  sheetOpen.value = true
}

async function run(fn, okMsg) {
  if (busy.value) return
  busy.value = true
  try {
    await fn()
    window.__kbMsg?.success(okMsg)
    sheetOpen.value = false
    load(true)
  } catch (e) {
    window.__kbMsg?.error(e?.response?.data?.error || '操作失败')
  } finally {
    busy.value = false
  }
}

const toggleEnabled = () => run(
  () => api.post(`/admin/users/${active.value.id}/toggle`, { enabled: !active.value.enabled }),
  active.value.enabled ? '已禁用' : '已启用'
)
const setRole = (role) => run(
  () => api.put(`/admin/users/${active.value.id}`, { role }),
  role === 'ADMIN' ? '已设为管理员' : '已设为普通用户'
)

const initial = (u) => (u.displayName || u.username || '?').charAt(0).toUpperCase()
const fmtDate = (s) => (s ? String(s).slice(0, 10) : '—')
const roleChip = (u) => (u.role === 'ADMIN' ? '管理员' : '普通用户')
const nameOf = (u) => u.displayName || u.username

load()
</script>

<template>
  <div class="page">
    <header class="m-header solid">
      <button class="m-header-act" @click="router.back()"><el-icon :size="20"><ArrowLeft /></el-icon></button>
      <div class="h-title">用户管理</div>
      <span class="h-ph"></span>
    </header>

    <!-- 搜索 + 筛选 -->
    <div class="tools">
      <div class="searchbox">
        <el-icon><Search /></el-icon>
        <input v-model="keyword" placeholder="搜索用户名 / 昵称" @keyup.enter="doSearch" />
        <button v-if="keyword" class="sb-clear" @click="keyword = ''; doSearch()"><el-icon><Close /></el-icon></button>
      </div>
      <div class="chips">
        <button v-for="f in FILTERS" :key="f.key" class="chip" :class="{ on: filter === f.key }"
                @click="pickFilter(f.key)">{{ f.label }}</button>
      </div>
    </div>

    <!-- 用户列表 -->
    <div class="list">
      <div class="m-card">
        <button v-for="u in users" :key="u.id" class="m-row" type="button" @click="openSheet(u)">
          <span class="ava" :class="{ admin: u.role === 'ADMIN', off: !u.enabled }">{{ initial(u) }}</span>
          <span class="m-row-body">
            <span class="m-row-title">{{ nameOf(u) }} <span class="uname">@{{ u.username }}</span></span>
            <span class="m-row-desc">积分 {{ u.points || 0 }} · 注册 {{ fmtDate(u.createdAt) }}</span>
          </span>
          <span class="m-chip" :class="u.role === 'ADMIN' ? 'orange' : (u.enabled ? 'gray' : 'red')">
            {{ u.enabled ? roleChip(u) : '已禁用' }}
          </span>
        </button>
        <div v-if="!users.length && !loading" class="empty">没有匹配的用户</div>
      </div>
      <button v-if="hasMore" class="m-btn ghost more" :disabled="loading" @click="load()">
        {{ loading ? '加载中…' : '加载更多' }}
      </button>
    </div>

    <!-- 操作弹层 -->
    <transition name="sheet-up">
      <div v-if="sheetOpen" class="m-sheet-mask" @click.self="sheetOpen = false">
        <div class="m-sheet">
          <div class="m-sheet-grip"></div>
          <div class="m-sheet-title">{{ nameOf(active) }} · @{{ active?.username }}</div>
          <button class="m-sheet-item" :class="{ danger: active?.enabled }" :disabled="busy"
                  @click="active?.enabled ? toggleEnabled() : toggleEnabled()">
            <el-icon><component :is="active?.enabled ? 'CircleCloseFilled' : 'CircleCheckFilled'" /></el-icon>
            {{ active?.enabled ? '禁用该账号' : '启用该账号' }}
          </button>
          <button class="m-sheet-item" :disabled="busy"
                  @click="setRole(active?.role === 'ADMIN' ? 'USER' : 'ADMIN')">
            <el-icon><UserFilled /></el-icon>
            {{ active?.role === 'ADMIN' ? '设为普通用户' : '设为管理员' }}
          </button>
          <button class="m-sheet-item cancel" @click="sheetOpen = false">取消</button>
        </div>
      </div>
    </transition>
  </div>
</template>

<style scoped>
.page { min-height: 100%; display: flex; flex-direction: column; }
.h-title { flex: 1 1 auto; text-align: center; font-size: 16.5px; font-weight: 600; }
.h-ph { width: 38px; }
.tools { padding: 8px 16px 4px; }
.searchbox {
  display: flex; align-items: center; gap: 8px;
  height: 42px; padding: 0 14px;
  background: var(--m-card); border-radius: 21px; box-shadow: var(--m-shadow);
}
.searchbox input {
  flex: 1 1 auto; min-width: 0; border: none; outline: none; background: transparent;
  font-size: 16px; color: var(--m-text); font-family: inherit;
}
.sb-clear { border: none; background: transparent; color: var(--m-text-sub); display: inline-flex; padding: 4px; }
.chips { display: flex; gap: 8px; padding: 10px 0 8px; }
.chip {
  height: 30px; padding: 0 14px; border-radius: 15px; border: none;
  background: var(--m-card); color: var(--m-text-sub); font-size: 13px;
}
.chip.on { background: var(--m-primary); color: #fff; font-weight: 500; }
.list { flex: 1 1 auto; padding: 4px 16px 16px; }
.ava {
  flex: none; width: 40px; height: 40px; border-radius: 50%;
  display: grid; place-items: center;
  background: var(--m-primary-weak, #e8eefe); color: var(--m-primary); font-weight: 600;
}
.ava.admin { background: #fff3e3; color: #d97706; }
.ava.off { background: var(--m-card-2); color: var(--m-text-sub); }
.uname { font-size: 12px; color: var(--m-text-sub); font-weight: 400; }
.empty { padding: 34px 0; text-align: center; color: var(--m-text-sub); font-size: 13.5px; }
.more { width: 100%; margin-top: 12px; }
</style>
