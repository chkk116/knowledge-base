<script setup>
/**
 * 移动端「导入分享」：把系统分享过来的 PDF / 图片 / 文本 导入成空间文档。
 *
 * 数据来自原生 ShareReceiver 插件（冷启动 getPending / 热启动事件）。
 * 每个文件：读缓存 → 上传(uploadFile) → 取 URL → 组装正文 → createShareDoc。
 */
import { onMounted, ref, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { uploadFile } from '../../api/file'
import { createShareDoc } from '../../api/docShare'
import { mySpaces, resolveDefaultSpaceId, rememberSpace, lastSpaceId, personalSpace } from '../../utils/spaceResolver'
import { getPendingShares, clearPendingShares, shareItemToFile, isImage } from '../../utils/shareReceiver'

const route = useRoute()
const router = useRouter()

const files = ref([])
const spaces = ref([])
const spaceId = ref(null)
const importing = ref(false)
const done = ref(0)

const canImport = computed(() => files.value.length > 0 && spaceId.value && !importing.value)

function fmtSize(n) {
  const u = ['B', 'KB', 'MB', 'GB']
  let i = 0, v = Number(n || 0)
  while (v >= 1024 && i < u.length - 1) { v /= 1024; i++ }
  return (i === 0 ? v : v.toFixed(1)) + u[i]
}
function esc(s) {
  return String(s || '').replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]))
}
function iconOf(f) {
  if (isImage(f.mime, f.name)) return 'Picture'
  if ((f.mime || '').includes('pdf') || /\.pdf$/i.test(f.name)) return 'Document'
  return 'Files'
}
function titleOf(f) {
  return String(f.name || '分享的文档').replace(/\.[^.]+$/, '') || '分享的文档'
}

/** 看起来像压缩包？（MIME 或扩展名任一命中即可，真实类型由原生按魔数判断） */
function isArchive(f) {
  const m = String(f?.mime || '').toLowerCase()
  const n = String(f?.name || '').toLowerCase()
  return /zip|rar|7z|compressed/.test(m) || /\.(zip|rar|7z)$/.test(n)
}

/**
 * 展开压缩包：部分平台（含微信某些转发路径）会把 PDF/图片先打包再分享。
 * 原生按魔数识别，ZIP 就地解压成多个条目；真 RAR 会抛错，这里提示用户先手动解压。
 */
async function expandArchives(list) {
  const P = window.Capacitor?.Plugins?.ShareReceiver
  if (!P?.extract) return list
  const out = []
  for (const f of list) {
    if (!isArchive(f) || !f.path) { out.push(f); continue }
    try {
      const r = await P.extract({ path: f.path })
      const inner = Array.isArray(r?.files) ? r.files : []
      if (inner.length) out.push(...inner)
      else out.push(f)
    } catch (e) {
      window.__kbMsg?.warning(String(f.name || '压缩包') + '：' + (e?.message || '解压失败'))
    }
  }
  return out
}

async function load() {
  // 优先用路由传入的（热启动跳转时带过来），否则问原生取
  const passed = window.__kbShareFiles
  const raw = Array.isArray(passed) && passed.length ? passed : await getPendingShares()
  window.__kbShareFiles = null
  files.value = await expandArchives(raw)
  try {
    spaces.value = await mySpaces()
    const def = lastSpaceId() || (await resolveDefaultSpaceId())
    spaceId.value = spaces.value.some((s) => String(s.id) === String(def)) ? def : (spaces.value[0]?.id ?? null)
  } catch { /* 离线/失败时留空，导入按钮会禁用 */ }
}

function buildContent(f, url) {
  if (isImage(f.mime, f.name)) return `<p><img src="${url}" alt="${esc(f.name)}" /></p>`
  if (f.text != null && (!f.mime || f.mime.startsWith('text/'))) return `<p>${esc(f.text).replace(/\n/g, '<br>')}</p>`
  return `<p><a href="${url}" target="_blank" rel="noopener">${esc(f.name)}</a></p>`
}

async function doImport() {
  if (!canImport.value) return
  importing.value = true
  done.value = 0
  const sid = spaceId.value
  let ok = 0, fail = 0
  for (const f of files.value) {
    try {
      const file = await shareItemToFile(f)
      const up = await uploadFile(file)
      const url = up?.data?.url || up?.data?.data?.url
      if (!url) throw new Error('上传失败')
      await createShareDoc(sid, { title: titleOf(f), content: buildContent(f, url) })
      ok++
      done.value = ok
    } catch (e) {
      fail++
    }
  }
  try { rememberSpace(sid) } catch { /* ignore */ }
  await clearPendingShares().catch(() => {})
  importing.value = false
  if (ok > 0) {
    window.__kbMsg?.success(`已导入 ${ok} 篇${fail ? `，${fail} 篇失败` : ''}`)
    router.replace(`/space/${sid}`)
  } else {
    window.__kbMsg?.error('导入失败，请重试')
  }
}

function cancel() {
  clearPendingShares().catch(() => {})
  router.replace('/docs')
}

/** 收进待整理箱（2026-10-15）：免选空间免填信息，快速收纳，之后再集中整理 */
async function toInbox() {
  if (!files.value.length || importing.value) return
  importing.value = true
  try {
    const p = await personalSpace()
    if (!p?.id) throw new Error('没有可用的个人空间')
    let ok = 0, fail = 0
    for (const f of files.value) {
      try {
        const file = await shareItemToFile(f)
        const up = await uploadFile(file)
        const url = up?.data?.url || up?.data?.data?.url
        if (!url) throw new Error('上传失败')
        await createShareDoc(p.id, { title: titleOf(f), content: buildContent(f, url) }, true)
        ok++
      } catch { fail++ }
    }
    await clearPendingShares().catch(() => {})
    if (ok > 0) {
      window.__kbMsg?.success(`已收进待整理箱 ${ok} 个${fail ? `，${fail} 个失败` : ''}`)
      router.replace('/inbox')
    } else {
      window.__kbMsg?.error('收箱失败，请重试')
    }
  } catch (e) {
    window.__kbMsg?.error(e.message || '操作失败')
  } finally {
    importing.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="si">
    <header class="m-header solid">
      <button class="m-header-act" @click="cancel"><el-icon :size="20"><ArrowLeft /></el-icon></button>
      <div class="h-title">导入分享</div>
      <span class="h-ph"></span>
    </header>

    <div class="body">
      <template v-if="files.length">
        <div class="tip"><el-icon><InfoFilled /></el-icon><span>收到 {{ files.length }} 个文件，选择空间后导入为文档。</span></div>

        <div class="m-card">
          <div v-for="(f, i) in files" :key="i" class="frow">
            <span class="m-ico blue"><el-icon><component :is="iconOf(f)" /></el-icon></span>
            <span class="m-row-body">
              <span class="m-row-title">{{ f.name || '未命名' }}</span>
              <span class="m-row-desc">{{ fmtSize(f.size) }}<template v-if="f.mime"> · {{ f.mime }}</template></span>
            </span>
          </div>
        </div>

        <div class="m-sec-title">导入到</div>
        <div class="spaces">
          <button v-for="s in spaces" :key="s.id" class="sp" :class="{ on: String(spaceId) === String(s.id) }"
                  type="button" @click="spaceId = s.id">
            <el-icon :size="14"><component :is="s.type === 'PERSONAL' ? 'User' : 'DocumentCopy'" /></el-icon>
            {{ s.name }}
          </button>
          <div v-if="!spaces.length" class="none">读取空间失败（可能离线）</div>
        </div>

        <button class="m-btn" :disabled="!canImport" @click="doImport">
          {{ importing ? `导入中…（${done}/${files.length}）` : '导入' }}
        </button>
        <button class="m-btn ghost" style="margin-top:8px" :disabled="!files.length || importing" @click="toInbox">先收进待整理箱</button>
        <button class="m-btn ghost" style="margin-top:8px" :disabled="importing" @click="cancel">取消</button>
      </template>

      <div v-else class="m-card">
        <div class="m-empty">
          <span class="ico"><el-icon :size="30"><Upload /></el-icon></span>
          <div>没有待导入的内容</div>
          <button class="m-btn sm" style="margin-top:4px" @click="router.replace('/docs')">返回文档库</button>
        </div>
      </div>
    </div>
    <div style="height:12px"></div>
  </div>
</template>

<style scoped>
.si { min-height: 100%; }
.h-title { flex: 1 1 auto; text-align: center; font-size: 16.5px; font-weight: 600; }
.h-ph { width: 38px; }
.body { padding: 0 16px; }
.tip { display: flex; gap: 6px; align-items: flex-start; font-size: 12.5px; color: var(--m-text-sub);
  background: var(--m-primary-soft); border-radius: 10px; padding: 10px 12px; margin: 8px 0 4px; line-height: 1.5; }
.frow { display: flex; align-items: center; gap: 10px; padding: 12px 4px; border-bottom: 1px solid var(--m-border); }
.frow:last-child { border-bottom: none; }
.spaces { display: flex; flex-wrap: wrap; gap: 8px; padding: 4px 0 14px; }
.sp { display: inline-flex; align-items: center; gap: 5px; border: 1px solid var(--m-border); background: var(--m-card-2);
  color: var(--m-text-sub); font-size: 13px; padding: 8px 14px; border-radius: 999px; cursor: pointer; }
.sp.on { border-color: var(--m-primary); color: var(--m-primary); background: var(--m-primary-soft); font-weight: 600; }
.none { font-size: 12.5px; color: var(--m-text-dim); }
.m-btn { width: 100%; }
</style>
