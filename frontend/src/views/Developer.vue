<script setup>
/**
 * API 接入指南（开发者文档）
 *
 * 形态对标飞书开放平台文档：左侧两级树形目录 + 右侧正文 + 代码块一键复制 + 滚动联动高亮。
 * 内容直接读仓库根的 API.md（?raw 构建期内联），不在前端另存一份，避免文档与代码脱节。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import MarkdownIt from 'markdown-it'
// ⚠️ 顺序不可调整：utils/prism 先把 Prism 挂到 window，语言组件才能读到（见该文件注释）
import Prism from '../utils/prism'
import 'prismjs/components/prism-javascript'
import 'prismjs/components/prism-json'
import 'prismjs/components/prism-bash'
import 'prismjs/components/prism-python'
import apiRaw from '../../../API.md?raw'

const md = new MarkdownIt({ html: false, linkify: true, breaks: false })

function highlight(code, lang) {
  const l = lang === 'js' ? 'javascript'
    : (lang === 'sh' || lang === 'shell' || lang === 'zsh') ? 'bash'
      : lang
  if (l && Prism.languages[l]) {
    try { return Prism.highlight(code, Prism.languages[l], l) } catch (e) { /* 语言不可用回退纯文本 */ }
  }
  return md.utils.escapeHtml(code)
}

// 代码块：顶部语言标签 + 复制按钮（结构固定，复制走事件委托）
md.renderer.rules.fence = (tokens, idx) => {
  const info = (tokens[idx].info || '').trim()
  const lang = info ? info.toLowerCase().split(/[\s,;:]/)[0].replace(/[^a-z0-9_+#.-]/g, '') : ''
  const cls = lang ? ' language-' + lang : ''
  const code = highlight(tokens[idx].content, lang)
  return '<div class="code-block"><div class="code-bar"><span class="code-lang">' + (lang || 'text')
    + '</span><button type="button" class="code-copy">复制</button></div>'
    + '<pre class="code-pre' + cls + '"><code' + cls + '>' + code + '</code></pre></div>'
}

// 表格包一层，窄屏可横向滚动
md.renderer.rules.table_open = () => '<div class="tbl-wrap"><table class="doc-tbl">'
md.renderer.rules.table_close = () => '</table></div>'

// 去掉源文档自身的 h1，标题由页面头部统一渲染，避免重复
const source = apiRaw.replace(/^#\s+API 调用说明\s*\r?\n/, '')

const toc = []
const tokens = md.parse(source, {})
let seq = 0
tokens.forEach((t, i) => {
  if (t.type === 'heading_open' && /^h[23]$/.test(t.tag)) {
    const inline = tokens[i + 1]
    const text = inline ? inline.content : ''
    const id = 'api-sec-' + (seq++)
    t.attrSet('id', id)
    toc.push({ id, text, level: Number(t.tag.slice(1)) })
  }
})
const html = md.renderer.render(tokens, md.options, {})

const active = ref(toc.length ? toc[0].id : '')
const scroller = ref(null)
let scrollEl = null

function goTo(id) {
  const el = document.getElementById(id)
  if (el) el.scrollIntoView({ behavior: 'smooth', block: 'start' })
  active.value = id
}

function syncActive() {
  if (!toc.length) return
  let cur = toc[0].id
  for (const t of toc) {
    const el = document.getElementById(t.id)
    if (!el) continue
    // 顶部留 120px 余量：标题滚到接近顶栏时即认为进入该节
    if (el.getBoundingClientRect().top <= 120) cur = t.id
    else break
  }
  active.value = cur
}

function onCopy(e) {
  const btn = e.target.closest?.('.code-copy')
  if (!btn) return
  const code = btn.closest('.code-block')?.querySelector('code')?.textContent || ''
  const done = (txt) => {
    btn.textContent = txt
    setTimeout(() => { btn.textContent = '复制' }, 1500)
  }
  if (navigator.clipboard?.writeText) {
    navigator.clipboard.writeText(code).then(() => done('已复制')).catch(() => done('复制失败'))
  } else {
    done('复制失败')
  }
}

onMounted(() => {
  // 页面滚轮容器是 .el-main（不是 window），两处都挂，兼容不同布局
  scrollEl = document.querySelector('.el-main') || window
  scrollEl.addEventListener('scroll', syncActive, { passive: true })
  window.addEventListener('scroll', syncActive, { passive: true })
  nextTick(syncActive)
})

onBeforeUnmount(() => {
  if (scrollEl) scrollEl.removeEventListener('scroll', syncActive)
  window.removeEventListener('scroll', syncActive)
})

const topLevel = computed(() => toc.filter((t) => t.level === 2).length)
</script>

<template>
  <div class="dev-page">
    <!-- 左：两级树形目录 -->
    <aside class="dev-nav">
      <div class="nav-hd">
        <span class="nav-title">API 接入指南</span>
        <span class="nav-count">{{ topLevel }} 章</span>
      </div>
      <ul class="nav-list">
        <li
          v-for="t in toc"
          :key="t.id"
          class="nav-i"
          :class="['lv' + t.level, { on: active === t.id }]"
          @click="goTo(t.id)"
        >{{ t.text }}</li>
      </ul>
    </aside>

    <!-- 右：正文 -->
    <main class="dev-main">
      <header class="dev-hd">
        <h1>API 接入指南</h1>
        <p class="dev-sub">用个人 API 密钥在脚本或外部系统里调用知识库接口。密钥只在创建时显示一次，丢了重建。</p>
        <div class="dev-tags">
          <span class="tag">REST / JSON</span>
          <span class="tag">X-API-Key 认证</span>
          <span class="tag">前缀 /api</span>
        </div>
      </header>
      <article class="dev-body" @click="onCopy" v-html="html"></article>
    </main>
  </div>
</template>

<style scoped>
.dev-page {
  display: flex;
  gap: 28px;
  align-items: flex-start;
  max-width: 1180px;
  margin: 0 auto;
  padding: 24px 24px 80px;
}

/* ---- 左侧目录 ---- */
.dev-nav {
  position: sticky;
  top: 16px;
  flex: 0 0 232px;
  width: 232px;
  max-height: calc(100vh - 140px);
  overflow: auto;
  background: #fff;
  border: 1px solid #eef2f7;
  border-radius: 12px;
  padding: 14px 10px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, .04);
}
.nav-hd {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  padding: 0 8px 10px;
  border-bottom: 1px solid #f1f5f9;
}
.nav-title { font-weight: 700; color: #1e293b; font-size: 14px; }
.nav-count { color: #94a3b8; font-size: 12px; }
.nav-list { list-style: none; margin: 8px 0 0; padding: 0; }
.nav-i {
  padding: 7px 10px;
  border-radius: 7px;
  font-size: 13px;
  color: #475569;
  cursor: pointer;
  line-height: 1.45;
  transition: background .12s, color .12s;
}
.nav-i:hover { background: #f8fafc; color: #1e293b; }
.nav-i.lv3 { padding-left: 24px; font-size: 12.5px; color: #64748b; }
.nav-i.on { background: #eef4ff; color: #2563eb; font-weight: 600; }

/* ---- 右侧正文 ---- */
.dev-main {
  flex: 1;
  min-width: 0;
  max-width: 840px;
  background: #fff;
  border: 1px solid #eef2f7;
  border-radius: 14px;
  padding: 30px 38px 56px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, .04);
}
.dev-hd { padding-bottom: 18px; border-bottom: 1px solid #f1f5f9; margin-bottom: 8px; }
.dev-hd h1 { margin: 0 0 10px; font-size: 30px; color: #0f172a; letter-spacing: -.3px; }
.dev-sub { margin: 0 0 14px; color: #64748b; font-size: 14px; line-height: 1.7; }
.dev-tags { display: flex; flex-wrap: wrap; gap: 8px; }
.tag {
  padding: 3px 10px;
  border-radius: 999px;
  background: #f1f5f9;
  color: #475569;
  font-size: 12px;
}

.dev-body { color: #334155; font-size: 15px; line-height: 1.8; }
.dev-body :deep(h2) {
  margin: 36px 0 14px;
  padding-bottom: 9px;
  border-bottom: 1px solid #f1f5f9;
  font-size: 22px;
  color: #0f172a;
  scroll-margin-top: 90px;
}
.dev-body :deep(h3) {
  margin: 26px 0 10px;
  font-size: 17px;
  color: #1e293b;
  scroll-margin-top: 90px;
}
.dev-body :deep(p) { margin: 12px 0; }
.dev-body :deep(ul), .dev-body :deep(ol) { margin: 10px 0; padding-left: 22px; }
.dev-body :deep(li) { margin: 5px 0; }
.dev-body :deep(a) { color: #2563eb; text-decoration: none; }
.dev-body :deep(a:hover) { text-decoration: underline; }
.dev-body :deep(code) {
  padding: 1px 6px;
  border-radius: 5px;
  background: #f1f5f9;
  color: #be123c;
  font-size: 13px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}

/* 表格 */
.dev-body :deep(.tbl-wrap) { overflow-x: auto; margin: 14px 0; }
.dev-body :deep(.doc-tbl) { width: 100%; border-collapse: collapse; font-size: 14px; }
.dev-body :deep(.doc-tbl th) {
  background: #f8fafc;
  color: #334155;
  text-align: left;
  font-weight: 600;
}
.dev-body :deep(.doc-tbl th), .dev-body :deep(.doc-tbl td) {
  border: 1px solid #eef2f7;
  padding: 9px 12px;
  vertical-align: top;
}

/* 代码块 */
.dev-body :deep(.code-block) {
  margin: 16px 0;
  border: 1px solid #e8edf5;
  border-radius: 10px;
  overflow: hidden;
  background: #fbfcfe;
}
.dev-body :deep(.code-bar) {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 12px;
  border-bottom: 1px solid #eef2f7;
  background: #f6f8fb;
}
.dev-body :deep(.code-lang) {
  font-size: 11.5px;
  color: #94a3b8;
  text-transform: uppercase;
  letter-spacing: .4px;
}
.dev-body :deep(.code-copy) {
  border: none;
  background: none;
  color: #64748b;
  font-size: 12px;
  cursor: pointer;
  padding: 2px 6px;
  border-radius: 5px;
}
.dev-body :deep(.code-copy:hover) { background: #e9eef6; color: #2563eb; }
.dev-body :deep(.code-pre) {
  margin: 0;
  padding: 14px 16px;
  overflow-x: auto;
  font-size: 13px;
  line-height: 1.65;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}
.dev-body :deep(.code-pre code) { padding: 0; background: none; color: #1e293b; font-size: 13px; }

/* Prism 浅色配色（不引第三方主题，跟随项目白底卡风格） */
.dev-body :deep(.token.comment) { color: #94a3b8; font-style: italic; }
.dev-body :deep(.token.string) { color: #16a34a; }
.dev-body :deep(.token.keyword) { color: #7c3aed; }
.dev-body :deep(.token.function) { color: #2563eb; }
.dev-body :deep(.token.number) { color: #ea580c; }
.dev-body :deep(.token.property) { color: #0891b2; }
.dev-body :deep(.token.punctuation) { color: #64748b; }
.dev-body :deep(.token.operator) { color: #be123c; }
.dev-body :deep(.token.boolean) { color: #ea580c; }

/* ---- 暗色 ---- */
html.dark .dev-nav,
html.dark .dev-main { background: #23232e; border-color: #2f2f3c; box-shadow: none; }
html.dark .nav-hd { border-color: #2f2f3c; }
html.dark .nav-title,
html.dark .dev-hd h1,
html.dark .dev-body :deep(h2) { color: #e5e7eb; }
html.dark .dev-body :deep(h3) { color: #d1d5db; }
html.dark .nav-i { color: #9aa0ad; }
html.dark .nav-i:hover { background: #2a2a35; color: #e5e7eb; }
html.dark .nav-i.on { background: #2a3350; color: #93b4ff; }
html.dark .dev-hd { border-color: #2f2f3c; }
html.dark .dev-sub { color: #9aa0ad; }
html.dark .tag { background: #2f2f3c; color: #b3b8c4; }
html.dark .dev-body { color: #b3b8c4; }
html.dark .dev-body :deep(h2) { border-color: #2f2f3c; }
html.dark .dev-body :deep(code) { background: #2f2f3c; color: #f0a4b8; }
html.dark .dev-body :deep(.doc-tbl th) { background: #2a2a35; color: #d1d5db; }
html.dark .dev-body :deep(.doc-tbl th),
html.dark .dev-body :deep(.doc-tbl td) { border-color: #2f2f3c; }
html.dark .dev-body :deep(.code-block) { background: #1b1b24; border-color: #2f2f3c; }
html.dark .dev-body :deep(.code-bar) { background: #23232e; border-color: #2f2f3c; }
html.dark .dev-body :deep(.code-copy:hover) { background: #2f2f3c; color: #93b4ff; }
html.dark .dev-body :deep(.code-pre code) { color: #d1d5db; }

/* ---- 响应式：≤1024 目录收到顶部横向滚动 ---- */
@media (max-width: 1024px) {
  .dev-page { flex-direction: column; gap: 16px; padding: 16px 16px 60px; }
  .dev-nav {
    position: static;
    flex: none;
    width: 100%;
    max-height: none;
    overflow: visible;
    padding: 10px;
  }
  .nav-hd { border-bottom: none; padding-bottom: 4px; }
  .nav-list { display: flex; gap: 6px; overflow-x: auto; margin-top: 4px; padding-bottom: 2px; }
  .nav-i { white-space: nowrap; padding: 6px 12px; }
  .nav-i.lv3 { padding-left: 12px; }
  .dev-main { max-width: 100%; padding: 20px 18px 40px; }
  .dev-hd h1 { font-size: 24px; }
}
</style>
