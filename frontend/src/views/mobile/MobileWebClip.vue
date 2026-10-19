<script setup>
/**
 * 移动端「网页剪藏」：贴一个链接 → 服务端抓正文 → 预览确认 → 存成文档。
 *
 * 为什么分两步（抓取 / 保存）而不是一键存：
 *   动态渲染页、要登录的页经常抽不准正文，用户得先看一眼再决定存不存；
 *   标题也能顺手改掉，比直接塞一篇乱码文档好。
 *
 * 保存复用 createShareDoc，权限/配额/空间归属都由那条路径统一处理。
 */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { clipPreview } from '../../api/clip'
import { createShareDoc, setShareDocTags } from '../../api/docShare'
import { mySpaces, resolveDefaultSpaceId, lastSpaceId, rememberSpace } from '../../utils/spaceResolver'

const router = useRouter()

const url = ref('')
const loading = ref(false)
const saving = ref(false)
const result = ref(null)        // { url, title, site, excerpt, content, length }
const title = ref('')
const spaces = ref([])
const targetSpace = ref(null)
const toInbox = ref(true)
const savedId = ref(null)

const canFetch = computed(() => url.value.trim().length > 3 && !loading.value)
const canSave = computed(() => !!result.value && title.value.trim().length > 0 && !saving.value)

function errText(e) {
  return e?.response?.data?.error || e?.message || '操作失败'
}

async function fetchPage() {
  const u = url.value.trim()
  if (!u || loading.value) return
  loading.value = true
  result.value = null
  savedId.value = null
  try {
    const { data } = await clipPreview(u)
    result.value = data
    title.value = data?.title || ''
    url.value = data?.url || u
  } catch (e) {
    window.__kbMsg?.error(errText(e))
  } finally {
    loading.value = false
  }
}

async function save() {
  if (!canSave.value) return
  saving.value = true
  try {
    const spaceId = targetSpace.value || await resolveDefaultSpaceId()
    const body = {
      title: title.value.trim(),
      // 末尾附来源，方便日后回查原文
      content: `${result.value.content}<p>—— 来源：<a href="${result.value.url}" target="_blank" rel="noopener">${result.value.url}</a></p>`,
    }
    const { data } = await createShareDoc(spaceId, body, toInbox.value)
    const site = result.value.site
    if (site && data?.id) {
      // 自动打个来源标签，方便按站点筛
      try { await setShareDocTags(spaceId, data.id, [site]) } catch { /* 标签失败不影响保存 */ }
    }
    savedId.value = data?.id || null
    window.__kbMsg?.success(toInbox.value ? '已存入待整理箱' : '已保存为文档')
  } catch (e) {
    window.__kbMsg?.error(errText(e))
  } finally {
    saving.value = false
  }
}

function reset() {
  url.value = ''
  result.value = null
  title.value = ''
  savedId.value = null
}

function openDoc() {
  if (savedId.value && targetSpace.value) router.push(`/doc/${targetSpace.value}/${savedId.value}`)
  else router.push('/inbox')
}

function onSpaceChange(v) {
  targetSpace.value = v
  if (v) rememberSpace(v)
}

onMounted(async () => {
  try {
    spaces.value = await mySpaces(true)
    const def = lastSpaceId() || (await resolveDefaultSpaceId())
    targetSpace.value = spaces.value.some((s) => String(s.id) === String(def)) ? def : (spaces.value[0]?.id ?? null)
  } catch { /* 离线时下拉为空，保存会落默认空间 */ }
})
</script>

<template>
  <div class="clip">
    <header class="m-header solid">
      <button class="m-header-act" type="button" aria-label="返回" @click="router.back()">
        <el-icon :size="20"><ArrowLeft /></el-icon>
      </button>
      <div class="h-title">网页剪藏</div>
      <button v-if="result" class="m-header-act" type="button" aria-label="重新开始" @click="reset">
        <el-icon :size="18"><RefreshLeft /></el-icon>
      </button>
    </header>

    <div class="body">
      <div class="m-card url-card">
        <input
          v-model="url"
          class="url-input"
          type="url"
          inputmode="url"
          autocapitalize="off"
          autocorrect="off"
          placeholder="粘贴网页链接，如 https://example.com/article"
          @keyup.enter="fetchPage"
        />
        <button class="fetch-btn" type="button" :disabled="!canFetch" @click="fetchPage">
          {{ loading ? '抓取中…' : '抓取' }}
        </button>
      </div>
      <div class="hint-line">只抓取正文，去掉广告和导航；需要登录或纯前端渲染的页面可能抓不到。</div>

      <div v-if="loading" class="m-card skel-card">
        <div class="m-skel" style="height:16px;width:66%"></div>
        <div class="m-skel" style="height:12px;width:40%;margin-top:10px"></div>
        <div class="m-skel" style="height:12px;width:88%;margin-top:14px"></div>
        <div class="m-skel" style="height:12px;width:80%;margin-top:8px"></div>
      </div>

      <template v-if="result && !loading">
        <div class="m-sec-title">预览</div>
        <div class="m-card">
          <input v-model="title" class="title-input" placeholder="标题" />
          <div class="meta-line">
            <span class="site">{{ result.site }}</span>
            <span class="dot">·</span>
            <span>{{ result.length }} 字</span>
          </div>
          <div v-if="result.excerpt" class="excerpt">{{ result.excerpt }}</div>
        </div>

        <div class="m-card row-card">
          <div class="row-left">归入空间</div>
          <select class="row-select" :value="targetSpace || ''" aria-label="选择空间" @change="onSpaceChange(Number($event.target.value) || null)">
            <option v-if="!spaces.length" :value="targetSpace || ''">默认空间</option>
            <option v-for="s in spaces" :key="s.id" :value="s.id">{{ s.name }}</option>
          </select>
        </div>
        <div class="m-card row-card">
          <div class="row-left">先收进待整理箱</div>
          <label class="switch">
            <input v-model="toInbox" type="checkbox" />
            <span class="track"><span class="thumb"></span></span>
          </label>
        </div>

        <div class="actions">
          <button class="save-btn" type="button" :disabled="!canSave" @click="save">
            {{ saving ? '保存中…' : '保存为文档' }}
          </button>
          <button v-if="savedId" class="ghost-btn" type="button" @click="openDoc">查看</button>
        </div>
      </template>

      <div class="bottom-space"></div>
    </div>
  </div>
</template>

<style scoped>
.clip { min-height: 100vh; background: var(--m-bg); }
.body { padding: 12px 14px 0; }

.url-card { display: flex; align-items: center; gap: 10px; padding: 8px 8px 8px 14px; }
.url-input { flex: 1; min-width: 0; height: 36px; border: 0; outline: 0; background: transparent; font: inherit; font-size: 14px; color: var(--m-text); }
.url-input::placeholder { color: var(--m-text-dim, #a8a8b3); }
.fetch-btn { flex: none; height: 34px; padding: 0 16px; border: 0; border-radius: 999px; background: var(--m-primary, #1677ff); color: #fff; font-size: 13.5px; }
.fetch-btn:disabled { opacity: .45; }

.hint-line { margin: 8px 4px 0; font-size: 11.5px; line-height: 1.6; color: var(--m-text-dim, #86909c); }

.skel-card { padding: 14px; margin-top: 12px; }
.title-input { width: 100%; border: 0; outline: 0; background: transparent; font: inherit; font-size: 16px; font-weight: 500; color: var(--m-text); }
.meta-line { display: flex; align-items: center; gap: 6px; margin-top: 6px; font-size: 11.5px; color: var(--m-text-dim, #86909c); }
.site { color: var(--m-primary, #1677ff); }
.excerpt { margin-top: 10px; font-size: 13px; line-height: 1.6; color: var(--m-text-dim, #4e5969); }

.row-card { display: flex; align-items: center; justify-content: space-between; padding: 0 14px; height: 48px; margin-top: 10px; }
.row-left { font-size: 13.5px; color: var(--m-text); }
.row-select { max-width: 55%; border: 0; outline: 0; background: transparent; text-align: right; font: inherit; font-size: 13.5px; color: var(--m-text-dim, #4e5969); }

.switch { position: relative; display: inline-flex; }
.switch input { position: absolute; opacity: 0; width: 0; height: 0; }
.track { width: 44px; height: 26px; border-radius: 999px; background: #d8dade; display: inline-flex; align-items: center; padding: 3px; transition: background .18s; }
.thumb { width: 20px; height: 20px; border-radius: 50%; background: #fff; transition: transform .18s; }
.switch input:checked + .track { background: var(--m-primary, #1677ff); }
.switch input:checked + .track .thumb { transform: translateX(18px); }

.actions { display: flex; gap: 10px; margin-top: 16px; }
.save-btn { flex: 1; height: 44px; border: 0; border-radius: 12px; background: var(--m-primary, #1677ff); color: #fff; font-size: 15px; }
.save-btn:disabled { opacity: .45; }
.ghost-btn { flex: none; width: 84px; height: 44px; border: 1px solid var(--m-border, #e5e6eb); border-radius: 12px; background: transparent; color: var(--m-text); font-size: 14px; }

.bottom-space { height: 28px; }
</style>
