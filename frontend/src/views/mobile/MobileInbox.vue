<script setup>
/**
 * 移动端「待整理箱」：快速收进来的资料集中整理（2026-10-15）。
 *
 * 来源：系统分享导入 / 拍摄扫描 勾选「收进待整理箱」——先落个人空间并打 inbox 标记，
 * 不占空间文档列表；在这里补标题、选归档空间后正式入库。
 */
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { ArrowLeft, Delete, DocumentChecked, InfoFilled } from '@element-plus/icons-vue'
import { listInbox, archiveInboxDoc, deleteShareDoc } from '../../api/docShare'
import { mySpaces, lastSpaceId, rememberSpace } from '../../utils/spaceResolver'
import { fmtDateTime } from '../../utils/time'
import { errMsg } from '../../utils/errMsg'

const router = useRouter()

const docs = ref([])
const spaces = ref([])
const loading = ref(false)
const busy = ref(false)

// 整理面板：当前项 + 草稿标题 + 目标空间
const cur = ref(null)
const draftTitle = ref('')
const targetId = ref(null)

function fmtSize(n) {
  const u = ['B', 'KB', 'MB', 'GB']
  let i = 0, v = Number(n || 0)
  while (v >= 1024 && i < u.length - 1) { v /= 1024; i++ }
  return (i === 0 ? v : v.toFixed(1)) + u[i]
}

async function load() {
  loading.value = true
  try {
    docs.value = (await listInbox()).data || []
  } catch { docs.value = [] } finally { loading.value = false }
  try { spaces.value = await mySpaces() } catch { spaces.value = [] }
}

function openCur(d) {
  cur.value = d
  draftTitle.value = d.title || ''
  // 默认目标：上次用过的空间（spaceResolver 统一管 key），否则个人空间/第一个
  const last = spaces.value.find((s) => String(s.id) === String(lastSpaceId() ?? ''))
  targetId.value = (last || spaces.value.find((s) => s.type === 'PERSONAL') || spaces.value[0])?.id ?? null
}

async function doArchive() {
  if (!cur.value || !targetId.value || busy.value) return
  busy.value = true
  try {
    await archiveInboxDoc(cur.value.id, targetId.value, draftTitle.value)
    try { rememberSpace(targetId.value) } catch { /* ignore */ }
    window.__kbMsg?.success('已归档')
    cur.value = null
    await load()
  } catch (e) {
    window.__kbMsg?.error(errMsg(e, '归档失败'))
  } finally { busy.value = false }
}

async function doDelete(d) {
  try {
    await ElMessageBox.confirm(`删除「${d.title || '未命名'}」？删除后不可恢复`, '删除待整理项', { type: 'warning' })
  } catch { return }
  try {
    await deleteShareDoc(d.shareId, d.id)
    window.__kbMsg?.success('已删除')
    await load()
  } catch (e) {
    window.__kbMsg?.error(errMsg(e, '删除失败'))
  }
}

onMounted(load)
</script>

<template>
  <div class="ib">
    <header class="m-header solid">
      <button class="m-header-act" @click="router.replace('/docs')"><el-icon :size="20"><ArrowLeft /></el-icon></button>
      <div class="h-title">待整理箱</div>
      <span class="h-ph"></span>
    </header>

    <div class="body">
      <div class="tip"><el-icon><InfoFilled /></el-icon><span>分享 / 扫描时「先收进来」的资料都在这里，补个标题、选个空间就归档。</span></div>

      <div class="m-card">
        <div v-for="d in docs" :key="d.id" class="irow">
          <span class="i-main" @click="openCur(d)">
            <span class="i-title">{{ d.title || '未命名' }}</span>
            <span class="i-meta">{{ fmtSize(d.size) }} · {{ fmtDateTime(d.updatedAt) }}</span>
          </span>
          <button class="i-act" type="button" @click="openCur(d)"><el-icon :size="16"><DocumentChecked /></el-icon>整理</button>
          <button class="i-act danger" type="button" @click="doDelete(d)"><el-icon :size="16"><Delete /></el-icon>删除</button>
        </div>
        <div v-if="!loading && !docs.length" class="none">待整理箱是空的</div>
      </div>
    </div>

    <!-- 整理弹层 -->
    <transition name="sheet-up">
      <div v-if="cur" class="m-sheet-mask" @click.self="cur = null">
        <div class="m-sheet">
          <div class="sheet-title">整理「{{ cur.title || '未命名' }}」</div>

          <div class="f-label">标题</div>
          <input v-model="draftTitle" class="t-input" placeholder="文档标题" />

          <div class="f-label">归档到</div>
          <div class="spaces">
            <button v-for="s in spaces" :key="s.id" class="sp" :class="{ on: String(targetId) === String(s.id) }"
                    type="button" @click="targetId = s.id">
              {{ s.name }}
            </button>
            <div v-if="!spaces.length" class="none">空间列表读取失败（可能离线）</div>
          </div>

          <div class="sheet-actions">
            <button class="m-btn ghost" @click="cur = null" :disabled="busy">取消</button>
            <button class="m-btn" @click="doArchive" :disabled="busy || !targetId">
              {{ busy ? '归档中…' : '归档' }}
            </button>
          </div>
        </div>
      </div>
    </transition>
  </div>
</template>

<style scoped>
.tip { display: flex; gap: 6px; align-items: flex-start; font-size: 12.5px; color: var(--m-text-sub);
  background: var(--m-primary-soft); border-radius: 10px; padding: 10px 12px; margin: 8px 0 4px; line-height: 1.5; }
.irow { display: flex; align-items: center; gap: 10px; padding: 12px 4px; border-bottom: 1px solid var(--m-border); }
.irow:last-child { border-bottom: none; }
.i-main { flex: 1 1 auto; min-width: 0; display: flex; flex-direction: column; gap: 2px; cursor: pointer; }
.i-title { font-size: 14.5px; color: var(--m-text); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.i-meta { font-size: 12px; color: var(--m-text-dim); }
.i-act { display: inline-flex; align-items: center; gap: 3px; flex: none; font-size: 12.5px;
  color: var(--m-primary); background: transparent; border: 0.5px solid var(--m-border);
  border-radius: 8px; padding: 6px 10px; cursor: pointer; }
.i-act.danger { color: #e5484d; }
.none { font-size: 12.5px; color: var(--m-text-dim); text-align: center; padding: 22px 0; }
.f-label { font-size: 12.5px; color: var(--m-text-dim); margin: 12px 0 6px; }
.t-input { width: 100%; box-sizing: border-box; font-size: 14.5px; color: var(--m-text);
  background: var(--m-card-2); border: 1px solid var(--m-border); border-radius: 10px; padding: 10px 12px; }
.t-input:focus { outline: none; border-color: var(--m-primary); }
.spaces { display: flex; flex-wrap: wrap; gap: 8px; padding: 2px 0; }
.sp { display: inline-flex; align-items: center; border: 1px solid var(--m-border); background: var(--m-card-2);
  color: var(--m-text-sub); font-size: 13px; padding: 8px 14px; border-radius: 999px; cursor: pointer; }
.sp.on { border-color: var(--m-primary); color: var(--m-primary); background: var(--m-primary-soft); font-weight: 600; }
.sheet-title { font-size: 15.5px; font-weight: 600; color: var(--m-text); }
.sheet-actions { display: flex; gap: 10px; margin-top: 18px; }
</style>
