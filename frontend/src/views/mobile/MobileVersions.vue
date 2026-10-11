<script setup>
/**
 * 移动端「历史版本」
 *
 * 空间文档的版本快照（后端 /api/doc-shares/{sid}/docs/{docId}/versions）：
 * 可手动保存快照、恢复到任意历史版本、删除快照。
 * 入口：文档阅读页 → 更多 → 历史版本。
 */
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { listShareDocVersions, saveShareDocVersion, restoreShareDocVersion, deleteShareDocVersion } from '../../api/docShare'

const route = useRoute()
const router = useRouter()
const spaceId = route.params.spaceId
const docId = route.params.docId

const list = ref([])
const loading = ref(true)
const saving = ref(false)

function excerpt(html) {
  const t = String(html || '').replace(/<[^>]*>/g, ' ').replace(/&nbsp;/g, ' ').replace(/\s+/g, ' ').trim()
  return t.length > 70 ? t.slice(0, 70) + '…' : t
}

function fmt(s) {
  if (!s) return ''
  const d = new Date(s)
  if (Number.isNaN(d.getTime())) return ''
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

async function load() {
  loading.value = true
  try {
    const { data } = await listShareDocVersions(spaceId, docId)
    list.value = data || []
  } catch (e) {
    window.__kbMsg?.error('读取历史版本失败：' + (e.response?.data?.error || e.message || ''))
  } finally { loading.value = false }
}

async function snapshot() {
  if (saving.value) return
  saving.value = true
  try {
    await saveShareDocVersion(spaceId, docId)
    window.__kbMsg?.success('已保存当前版本')
    await load()
  } catch (e) {
    window.__kbMsg?.error('保存失败：' + (e.response?.data?.error || e.message || ''))
  } finally { saving.value = false }
}

const restoring = ref(false)
const removing = ref(false)
async function restore(v) {
  if (restoring.value) return
  try {
    await ElMessageBox.confirm(`将文档恢复到 ${fmt(v.createdAt)} 的版本？当前内容会被替换`, '恢复历史版本', {
      confirmButtonText: '恢复', cancelButtonText: '取消', type: 'warning',
    })
  } catch { return }
  restoring.value = true
  try {
    await restoreShareDocVersion(spaceId, docId, v.id)
    window.__kbMsg?.success('已恢复')
    router.back()
  } catch (e) {
    window.__kbMsg?.error('恢复失败：' + (e.response?.data?.error || e.message || ''))
  } finally { restoring.value = false }
}

async function remove(v) {
  if (removing.value) return
  try {
    await ElMessageBox.confirm('删除这个历史版本快照？不影响文档当前内容', '删除快照', {
      confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning',
    })
  } catch { return }
  removing.value = true
  try {
    await deleteShareDocVersion(spaceId, docId, v.id)
    await load()
  } catch (e) {
    window.__kbMsg?.error('删除失败：' + (e.response?.data?.error || e.message || ''))
  } finally { removing.value = false }
}

const empty = computed(() => !loading.value && !list.value.length)

onMounted(load)
</script>

<template>
  <div class="page">
    <header class="m-header solid">
      <button class="m-header-act" @click="router.back()"><el-icon :size="20"><ArrowLeft /></el-icon></button>
      <div class="h-title">历史版本</div>
      <span class="h-ph"></span>
    </header>

    <div class="body">
      <div class="top">
        <div class="top-t">共 {{ list.length }} 个历史版本</div>
        <button class="top-btn" :disabled="saving" @click="snapshot">
          <el-icon v-if="saving" class="rot"><Loading /></el-icon>
          <el-icon v-else><Plus /></el-icon>
          {{ saving ? '保存中…' : '保存当前版本' }}
        </button>
      </div>

      <div v-if="loading" class="m-card sec">
        <div v-for="i in 3" :key="i" style="padding:12px 14px">
          <div class="m-skel" style="height:16px;width:52%"></div>
          <div class="m-skel" style="height:12px;width:78%;margin-top:9px"></div>
        </div>
      </div>

      <div v-else-if="empty" class="m-card sec">
        <div class="m-empty">
          <span class="ico"><el-icon :size="30"><Clock /></el-icon></span>
          <div>还没有历史版本</div>
          <div style="font-size:12.5px;color:var(--m-text-dim)">点上方按钮保存一份快照，之后可随时恢复</div>
        </div>
      </div>

      <div v-else class="m-card sec">
        <div v-for="(v, i) in list" :key="v.id" class="ver">
          <div class="ver-top">
            <span class="ver-idx">v{{ list.length - i }}</span>
            <span class="ver-time">{{ fmt(v.createdAt) }}</span>
            <span v-if="i === 0" class="m-chip green">最新</span>
          </div>
          <div class="ver-title">{{ v.title || '未命名文档' }}</div>
          <div v-if="excerpt(v.content)" class="ver-ex">{{ excerpt(v.content) }}</div>
          <div class="ver-acts">
            <button class="act primary" @click="restore(v)">恢复此版本</button>
            <button class="act" @click="remove(v)">删除</button>
          </div>
        </div>
      </div>

      <div class="tip">恢复后文档内容会替换为该版本，当前内容请先「保存当前版本」以免丢失</div>
    </div>
  </div>
</template>

<style scoped>
.page { min-height: 100%; }
.h-title { flex: 1 1 auto; text-align: center; font-size: 16.5px; font-weight: 600; }
.h-ph { width: 38px; }
.body { padding: 4px 16px 28px; }
.rot { animation: kb-rot 1s linear infinite; }
@keyframes kb-rot { to { transform: rotate(360deg); } }

.top {
  display: flex; align-items: center; justify-content: space-between;
  padding: 4px 2px 12px;
}
.top-t { font-size: 13px; color: var(--m-text-dim); }
.top-btn {
  display: inline-flex; align-items: center; gap: 5px; height: 34px; padding: 0 14px;
  border-radius: 17px; border: none; background: var(--m-primary); color: #fff;
  font-size: 13.5px; cursor: pointer;
}
.top-btn:disabled { opacity: .6; }

.sec { padding: 0 14px; }
.ver { padding: 13px 0; }
.ver + .ver { border-top: 1px solid var(--m-border); }
.ver-top { display: flex; align-items: center; gap: 8px; }
.ver-idx { font-size: 12px; font-weight: 700; color: var(--m-primary); }
.ver-time { font-size: 12.5px; color: var(--m-text-dim); }
.ver-title { margin-top: 6px; font-size: 14.5px; font-weight: 600; color: var(--m-text); }
.ver-ex { margin-top: 4px; font-size: 12.5px; line-height: 1.6; color: var(--m-text-dim); }
.ver-acts { display: flex; gap: 8px; margin-top: 10px; }
.act {
  height: 32px; padding: 0 14px; border-radius: 9px; cursor: pointer; font-size: 13px;
  border: 1px solid var(--m-border); background: var(--m-card-2); color: var(--m-text-sub);
}
.act.primary { background: var(--m-primary-soft); color: var(--m-primary); border-color: transparent; font-weight: 600; }
.tip { margin-top: 14px; font-size: 12px; color: var(--m-text-dim); line-height: 1.7; }
</style>
