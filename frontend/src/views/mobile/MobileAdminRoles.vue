<script setup>
/**
 * 移动端原生「角色管理」页（替代 iframe 嵌桌面版）
 *
 * 列表：全部角色 + 权限数量；点开进入权限编辑（内置角色只读）。
 * 编辑：权限码按 PERM_GROUPS 分组渲染成可点选的 chip，保存走 PUT /api/admin/roles/{id}。
 * 注意：改角色权限会令持有该角色用户的旧 token 失效（tokenVersion 递增），需重新登录。
 */
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import api from '../../api/request'
import { PERM_GROUPS } from '../../utils/permCodes'

const router = useRouter()

const roles = ref([])
const loading = ref(false)
const saving = ref(false)
const editing = ref(null)            // 当前编辑的角色（null = 列表态）
const selected = ref(new Set())      // 已勾选权限码
const dirty = ref(false)

async function load() {
  loading.value = true
  try {
    const { data } = await api.get('/admin/roles')
    roles.value = Array.isArray(data) ? data : (data.content || [])
  } catch (e) {
    window.__kbMsg?.error(e?.response?.data?.error || '加载失败')
  } finally {
    loading.value = false
  }
}
load()

const permCount = (r) => String(r.permissions || '').split(',').map(s => s.trim()).filter(Boolean).length

function openRole(r) {
  editing.value = r
  selected.value = new Set(String(r.permissions || '').split(',').map(s => s.trim()).filter(Boolean))
  dirty.value = false
}

function back() {
  if (editing.value) { editing.value = null; return }
  router.back()
}

function togglePerm(code) {
  if (editing.value?.builtin) return
  const next = new Set(selected.value)
  next.has(code) ? next.delete(code) : next.add(code)
  selected.value = next
  dirty.value = true
}

const selectedCount = computed(() => selected.value.size)

async function save() {
  const r = editing.value
  if (!r || r.builtin) return
  saving.value = true
  try {
    await api.put(`/admin/roles/${r.id}`, {
      name: r.name, code: r.code, description: r.description,
      permissions: [...selected.value].join(',')
    })
    window.__kbMsg?.success('已保存，该角色用户需重新登录生效')
    editing.value = null
    load()
  } catch (e) {
    window.__kbMsg?.error(e?.response?.data?.error || '保存失败')
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div class="page">
    <header class="m-header solid">
      <button class="m-header-act" @click="back"><el-icon :size="20"><ArrowLeft /></el-icon></button>
      <div class="h-title">
        {{ editing ? (editing.builtin ? '角色权限（内置只读）' : '编辑权限') : '角色管理' }}
      </div>
      <button v-if="editing && !editing.builtin" class="h-save" :disabled="saving || !dirty"
              @click="save">{{ saving ? '保存中…' : '保存' }}</button>
      <span v-else class="h-ph"></span>
    </header>

    <!-- 角色列表 -->
    <div v-if="!editing" class="body">
      <div class="m-card">
        <button v-for="r in roles" :key="r.id" class="m-row" type="button" @click="openRole(r)">
          <span class="ric"><el-icon><Avatar /></el-icon></span>
          <span class="m-row-body">
            <span class="m-row-title">
              {{ r.name }}
              <span v-if="r.builtin" class="tag">内置</span>
              <span v-else-if="r.code === 'DEMO'" class="tag demo">演示</span>
            </span>
            <span class="m-row-desc">{{ r.description || r.code }}</span>
          </span>
          <span class="pcount">{{ permCount(r) }} 项</span>
          <el-icon class="m-row-extra"><Right /></el-icon>
        </button>
      </div>
      <div class="tip">提示：修改角色权限后，该角色下的用户需重新登录才会拿到新权限。</div>
    </div>

    <!-- 权限编辑 -->
    <div v-else class="body">
      <div v-if="editing.builtin" class="builtin-tip">
        内置角色的权限由系统定义，不可修改；如需自定义权限请创建新角色（Web 端操作）。
      </div>
      <div v-for="g in PERM_GROUPS" :key="g.title" class="pgroup">
        <div class="m-sec-title">{{ g.title }}</div>
        <div class="m-card chips">
          <button v-for="p in g.perms" :key="p.value" class="pchip" type="button"
                  :class="{ on: selected.has(p.value), lock: editing.builtin }"
                  @click="togglePerm(p.value)">
            <el-icon class="tick"><component :is="selected.has(p.value) ? 'Check' : 'Plus'" /></el-icon>
            {{ p.label }}
          </button>
        </div>
      </div>
      <div class="count-bar">已选 {{ selectedCount }} 项权限</div>
    </div>
  </div>
</template>

<style scoped>
.page { min-height: 100%; display: flex; flex-direction: column; }
.h-title { flex: 1 1 auto; text-align: center; font-size: 16.5px; font-weight: 600; }
.h-ph { width: 38px; }
.h-save {
  width: 56px; height: 32px; border: none; border-radius: 16px;
  background: var(--m-primary); color: #fff; font-size: 13.5px;
}
.h-save:disabled { opacity: .45; }
.body { flex: 1 1 auto; padding: 10px 16px 20px; }
.ric {
  flex: none; width: 38px; height: 38px; border-radius: 11px;
  display: grid; place-items: center;
  background: var(--m-primary-weak, #e8eefe); color: var(--m-primary); font-size: 19px;
}
.tag {
  font-size: 10.5px; padding: 1px 6px; border-radius: 8px;
  background: var(--m-card-2); color: var(--m-text-sub); vertical-align: 2px;
}
.tag.demo { background: #fff3e3; color: #d97706; }
.pcount { flex: none; font-size: 12.5px; color: var(--m-text-sub); }
.tip { margin-top: 12px; font-size: 12.5px; color: var(--m-text-sub); line-height: 1.6; }
.builtin-tip {
  padding: 12px 14px; border-radius: 12px; font-size: 13px; line-height: 1.6;
  background: #fff8e6; color: #9a6b00; border: 1px solid #ffe1a6; margin-bottom: 12px;
}
html.dark .builtin-tip { background: #2c2410; color: #f0c469; border-color: #5c4a1a; }
.chips { display: flex; flex-wrap: wrap; gap: 8px; padding: 12px; }
.pchip {
  display: inline-flex; align-items: center; gap: 4px;
  height: 32px; padding: 0 12px; border-radius: 16px; border: 1px solid var(--m-border);
  background: var(--m-card); color: var(--m-text-sub); font-size: 13px;
}
.pchip.on { background: var(--m-primary); border-color: var(--m-primary); color: #fff; }
.pchip.lock { opacity: .75; }
.tick { font-size: 13px; }
.count-bar { margin-top: 14px; text-align: center; font-size: 12.5px; color: var(--m-text-sub); }
</style>
