<script setup>
/**
 * 移动端「我的文件」文件中心
 *
 * 与网页端 FileCenter.vue 共用 api/file.js 的三个接口，行为一致：
 * 上传即登记归属 → 列表 → 预览 / 复制链接 / 删除。
 * 视觉走移动端语言（m-* 类 + --m-* 变量），不做 iframe 嵌套。
 */
import { ref, reactive, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { listMyFiles, uploadManagedFile, deleteMyFile } from '../../api/file'

const router = useRouter()
const files = ref([])
const loading = ref(false)
let uploadSeq = 0
const uploading = ref([])
const inputRef = ref(null)
// 分批渲染：列表一次性全画出来在手机上会卡（每项一个 <img>），先画 40 条，点「加载更多」继续
const BATCH = 40
const visible = ref(BATCH)
const paged = computed(() => files.value.slice(0, visible.value))

function fmtSize(n) {
  const v = Number(n || 0)
  if (!v) return '—'
  const u = ['B', 'KB', 'MB', 'GB']
  let i = 0, x = v
  while (x >= 1024 && i < u.length - 1) { x /= 1024; i++ }
  return (i === 0 ? x : x.toFixed(1)) + u[i]
}

function fmtTime(s) {
  if (!s) return ''
  return String(s).replace('T', ' ').slice(0, 16)
}

function isImage(f) {
  return /^image\//i.test(f.contentType || '') || /\.(png|jpe?g|gif|webp|bmp)$/i.test(f.url || '')
}

async function load() {
  loading.value = true
  try {
    const { data } = await listMyFiles()
    files.value = Array.isArray(data) ? data : []
  } catch {
    files.value = []
    window.__kbMsg?.error('文件列表加载失败，请下拉重试')
  } finally {
    loading.value = false
  }
}

async function uploadAll(list) {
  for (const f of list) {
    const item = reactive({ id: ++uploadSeq, name: f.name || '文件', percent: 0 })
    uploading.value.push(item)
    try {
      const { data } = await uploadManagedFile(f, (p) => { item.percent = p })
      window.__kbMsg?.success(data && data.deduped
        ? `${item.name}：内容已存在，直接复用`
        : `${item.name} 上传成功`)
    } catch (e) {
      const msg = (e && e.response && e.response.data && (e.response.data.message || e.response.data.error)) || (e && e.message) || '上传失败'
      window.__kbMsg?.error(msg)
    } finally {
      uploading.value = uploading.value.filter((x) => x !== item)
      await load()
    }
  }
}

function onPick(e) {
  const list = Array.from(e.target.files || [])
  e.target.value = ''
  if (list.length) uploadAll(list)
}

async function copyLink(f) {
  const url = location.origin + f.url
  try {
    await navigator.clipboard.writeText(url)
    window.__kbMsg?.success('链接已复制')
  } catch {
    window.__kbMsg?.info(url)
  }
}

async function remove(f) {
  try {
    await window.__kbMsgBox?.confirm(
      `确定删除「${f.name || '该文件'}」吗？若文档正文仍在引用，只会从文件中心移除，不会删掉原文件。`,
      '删除文件', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch { return }
  try {
    await deleteMyFile(f.id)
    window.__kbMsg?.success('已删除')
    await load()
  } catch (e) {
    const msg = (e && e.response && e.response.data && (e.response.data.message || e.response.data.error)) || '删除失败'
    window.__kbMsg?.error(msg)
  }
}

onMounted(load)
</script>

<template>
  <div class="mfiles">
    <header class="mf-head">
      <button class="m-header-act" @click="router.back()"><el-icon :size="20"><ArrowLeft /></el-icon></button>
      <div class="mf-title">我的文件</div>
      <button class="m-header-act" @click="inputRef?.click()"><el-icon :size="20"><Upload /></el-icon></button>
    </header>

    <div class="body">
      <button class="mf-upload" type="button" @click="inputRef?.click()">
        <span class="mf-upload-ico"><el-icon><Upload /></el-icon></span>
        <span class="mf-upload-txt">点击上传文件（图片 / 音频 / 视频 / PDF，≤50MB）</span>
      </button>
      <input ref="inputRef" type="file" multiple hidden @change="onPick" />

      <div v-if="uploading.length" class="mf-prog">
        <div v-for="u in uploading" :key="u.id" class="mf-prog-row">
          <span class="mf-prog-name">{{ u.name }}</span>
          <span class="mf-prog-bar"><i :style="{ width: u.percent + '%' }"></i></span>
          <span class="mf-prog-num">{{ u.percent }}%</span>
        </div>
      </div>

      <div class="m-sec-title">全部文件（{{ files.length }}）</div>

      <div v-if="loading && !files.length" class="m-empty">加载中…</div>
      <div v-else-if="!files.length" class="m-empty">
        <el-icon class="ico" :size="26"><FolderOpened /></el-icon>
        还没有文件，点上方按钮上传
      </div>

      <div v-else class="m-card">
        <div v-for="f in paged" :key="f.id" class="mf-row">
          <span class="mf-thumb">
            <img v-if="isImage(f)" :src="f.url" :alt="f.name" />
            <el-icon v-else><Document /></el-icon>
          </span>
          <span class="mf-body">
            <span class="mf-name">{{ f.name || '未命名' }}</span>
            <span class="mf-dim">{{ fmtSize(f.size) }} · {{ fmtTime(f.createdAt) }}</span>
          </span>
          <span class="mf-acts">
            <button class="mf-act" type="button" @click="copyLink(f)">复制</button>
            <button class="mf-act danger" type="button" @click="remove(f)">删除</button>
          </span>
        </div>
        <div v-if="files.length > visible" class="mf-more">
          <button type="button" @click="visible += BATCH">加载更多（还有 {{ files.length - visible }} 个）</button>
        </div>
      </div>
    </div>

    <div style="height:20px"></div>
  </div>
</template>

<style scoped>
.mfiles { min-height: 100%; }
.body { padding: 0 16px; }
.mf-head {
  display: flex; align-items: center; gap: 6px;
  padding: calc(env(safe-area-inset-top) + 8px) 8px 8px;
}
.mf-title { flex: 1 1 auto; text-align: center; font-size: 16.5px; font-weight: 600; }

.mf-upload {
  width: 100%; margin-top: 6px;
  display: flex; align-items: center; gap: 12px;
  padding: 16px 14px;
  background: var(--m-card); border-radius: var(--m-radius);
  box-shadow: var(--m-shadow); cursor: pointer; text-align: left;
}
.mf-upload-ico {
  flex: none; width: 38px; height: 38px; border-radius: 11px;
  background: var(--m-primary-soft); color: var(--m-primary);
  display: inline-flex; align-items: center; justify-content: center;
}
.mf-upload-txt { flex: 1 1 auto; min-width: 0; font-size: 13.5px; color: var(--m-text-sub); }

.mf-prog { margin-top: 10px; display: flex; flex-direction: column; gap: 8px; }
.mf-prog-row { display: flex; align-items: center; gap: 8px; font-size: 12px; }
.mf-prog-name { flex: 0 0 32%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--m-text-sub); }
.mf-prog-bar { flex: 1 1 auto; height: 6px; border-radius: 3px; background: var(--m-border); overflow: hidden; }
.mf-prog-bar i { display: block; height: 100%; background: var(--m-primary); transition: width .2s; }
.mf-prog-num { flex: none; width: 40px; text-align: right; color: var(--m-text-dim); }

.mf-row { display: flex; align-items: center; gap: 11px; padding: 11px 13px; }
.mf-row + .mf-row { border-top: 1px solid var(--m-border); }
.mf-thumb {
  flex: none; width: 44px; height: 44px; border-radius: 10px;
  background: var(--m-card-2); color: var(--m-text-dim);
  display: inline-flex; align-items: center; justify-content: center; overflow: hidden;
}
.mf-thumb img { width: 100%; height: 100%; object-fit: cover; }
.mf-body { flex: 1 1 auto; min-width: 0; }
.mf-name, .mf-dim { display: block; }
.mf-name { font-size: 14.5px; font-weight: 600; color: var(--m-text); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.mf-dim { font-size: 12px; color: var(--m-text-dim); margin-top: 2px; }
.mf-acts { flex: none; display: flex; gap: 6px; }
.mf-act {
  border: 1px solid var(--m-border); background: transparent;
  color: var(--m-text-sub); border-radius: 9px;
  padding: 6px 10px; font-size: 12.5px; cursor: pointer;
}
.mf-act.danger { color: var(--m-danger); border-color: var(--m-danger); }
.mf-more { padding: 12px; text-align: center; border-top: 1px solid var(--m-border); }
.mf-more button {
  border: 1px solid var(--m-border); background: transparent; color: var(--m-text-sub);
  border-radius: 999px; padding: 8px 18px; font-size: 13px; cursor: pointer;
}
</style>
