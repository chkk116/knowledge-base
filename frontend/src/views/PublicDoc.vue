<template>
  <div class="pub-wrap" :class="{ nocopy: noCopy }">
    <div v-if="loading" class="pub-card"><el-skeleton :rows="6" animated /></div>

    <div v-else-if="error" class="pub-card err">
      <div class="err-icon">🔒</div>
      <h2>{{ error }}</h2>
      <p>链接可能已被关闭、已过期，或地址不完整。请联系分享者重新获取。</p>
    </div>

    <div v-else-if="needPwd && !doc" class="pub-card pwd">
      <h2>{{ meta.title || '受保护的文档' }}</h2>
      <p>该文档需要访问密码</p>
      <el-input v-model="pwd" type="password" show-password placeholder="请输入访问密码"
                style="width: 260px" @keyup.enter="open" />
      <div class="pwd-actions">
        <el-button type="primary" :loading="opening" @click="open">打开文档</el-button>
      </div>
      <p v-if="pwdErr" class="pwd-err">{{ pwdErr }}</p>
    </div>

    <template v-else-if="doc">
      <header class="pub-head">
        <div class="ph-space">{{ doc.spaceName || '共享文档' }}</div>
        <h1>{{ doc.title }}</h1>
        <div class="ph-meta">只读分享 · {{ fmt(doc.updatedAt) }} 更新</div>
      </header>
      <article class="pub-body doc-html" v-html="doc.content || '<p>（本文暂无内容）</p>'"></article>
      <footer class="pub-foot">本文由「知识库」分享，内容以原作者发布为准</footer>
      <div v-if="noCopy" class="wm-layer" aria-hidden="true">
        <span v-for="i in 24" :key="i" class="wm">{{ wmText }}</span>
      </div>
    </template>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { fetchPublicDoc, fetchPublicMeta } from '../api/docShare'

const route = useRoute()
const token = String(route.params.token || '')

const loading = ref(true)
const opening = ref(false)
const error = ref('')
const meta = ref({})
const doc = ref(null)
const pwd = ref('')
const pwdErr = ref('')

const needPwd = computed(() => !!meta.value.needPassword)
const noCopy = computed(() => !!(doc.value ? doc.value.noCopy : meta.value.noCopy))
const wmText = computed(() => '仅供查阅 · ' + (doc.value?.title || meta.value.title || '') )

function fmt(v) {
  if (!v) return ''
  const d = new Date(v)
  if (Number.isNaN(d.getTime())) return String(v)
  return d.toLocaleString('zh-CN', { hour12: false })
}

async function open() {
  pwdErr.value = ''
  opening.value = true
  try {
    doc.value = await fetchPublicDoc(token, pwd.value)
    document.title = doc.value.title || '共享文档'
  } catch (e) {
    if (e && e.status === 403) pwdErr.value = e.message || '密码不正确'
    else error.value = (e && e.message) || '打不开该文档'
  } finally {
    opening.value = false
  }
}

onMounted(async () => {
  try {
    meta.value = await fetchPublicMeta(token)
    document.title = meta.value.title || '共享文档'
    if (!meta.value.needPassword) await open()
  } catch (e) {
    error.value = e.status === 410 ? '链接已过期' : '链接不存在或已失效'
  } finally {
    loading.value = false
  }
})

// 「禁止复制」：拦住选中/右键/复制。只是提高门槛，不是安全边界（截图、保存网页仍可获取）
const BLOCKED_EVENTS = ['contextmenu', 'copy', 'cut']
function block(e) { if (noCopy.value) e.preventDefault() }
onMounted(() => BLOCKED_EVENTS.forEach((ev) => document.addEventListener(ev, block)))
onUnmounted(() => BLOCKED_EVENTS.forEach((ev) => document.removeEventListener(ev, block)))
</script>

<style scoped>
.pub-wrap {
  min-height: 100vh;
  background: #f6f7fb;
  padding: 32px 16px 60px;
  position: relative;
}
.pub-wrap.nocopy { user-select: none; -webkit-user-select: none; }
.pub-card {
  max-width: 560px; margin: 8vh auto; background: #fff; border-radius: 14px;
  padding: 28px; box-shadow: 0 8px 28px rgba(23, 26, 51, .07); text-align: center;
}
.pub-card h2 { margin: 0 0 6px; font-size: 18px; }
.pub-card p { color: #8a8f9c; font-size: 13px; line-height: 1.7; margin: 6px 0; }
.err-icon { font-size: 34px; margin-bottom: 6px; }
.pwd .pwd-actions { margin-top: 14px; }
.pwd-err { color: #e5484d !important; }
.pub-head {
  max-width: 860px; margin: 0 auto 14px; background: transparent;
}
.ph-space { font-size: 12px; color: #6d7280; margin-bottom: 6px; }
.pub-head h1 { margin: 0 0 6px; font-size: 26px; line-height: 1.35; color: #16181d; }
.ph-meta { font-size: 12px; color: #9aa0ac; }
.pub-body {
  max-width: 860px; margin: 0 auto; background: #fff; border-radius: 12px;
  padding: 34px 40px; box-shadow: 0 6px 22px rgba(23, 26, 51, .06);
  color: #24262c; font-size: 15px; line-height: 1.8; overflow-wrap: break-word;
}
.pub-foot { max-width: 860px; margin: 18px auto 0; text-align: center; color: #a3a8b4; font-size: 12px; }
/* 水印层：铺满正文区域，不拦截操作 */
.wm-layer {
  position: fixed; inset: 0; pointer-events: none; z-index: 9;
  display: flex; flex-wrap: wrap; align-content: space-around; justify-content: space-around;
  overflow: hidden;
}
.wm {
  transform: rotate(-24deg); color: rgba(80, 86, 110, .085);
  font-size: 15px; white-space: nowrap; padding: 26px 18px;
}
@media (max-width: 640px) {
  .pub-body { padding: 22px 18px; font-size: 15px; }
  .pub-head h1 { font-size: 21px; }
}
/* 正文内的公共排版（内容来自服务端已消毒的 HTML） */
.doc-html :deep(img) { max-width: 100%; height: auto; border-radius: 6px; }
.doc-html :deep(h1), .doc-html :deep(h2), .doc-html :deep(h3) { margin: 1.2em 0 .5em; line-height: 1.4; }
.doc-html :deep(p) { margin: .6em 0; }
.doc-html :deep(pre) { background: #f5f6fa; padding: 12px 14px; border-radius: 8px; overflow: auto; }
.doc-html :deep(code) { background: #f1f2f6; padding: 1px 5px; border-radius: 4px; font-size: .92em; }
.doc-html :deep(blockquote) { margin: .8em 0; padding: 6px 14px; border-left: 3px solid #d7dae5; color: #5b616e; background: #fafbfd; }
.doc-html :deep(table) { border-collapse: collapse; width: 100%; }
.doc-html :deep(td), .doc-html :deep(th) { border: 1px solid #e6e8ef; padding: 6px 10px; }
</style>
