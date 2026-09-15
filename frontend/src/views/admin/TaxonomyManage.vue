<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getCategoryTree, createCategory, updateCategory, deleteCategory,
  listTags, createTag, deleteTag, categories as docCategories, allTags as docTags
} from '../../api/kb'
import { useAuthStore } from '../../store/auth'
import { errMsg } from '../../utils/errMsg'

const auth = useAuthStore()
const tree = ref([])
const tags = ref([])
const selectedCat = ref(null)

const newCatName = ref('')
const newTag = ref('')

const err = (e) => ElMessage.error(errMsg(e, '操作失败'))

async function load() {
  try { tree.value = (await getCategoryTree()).data || [] } catch (e) {}
  try { tags.value = (await listTags()).data || [] } catch (e) {}
}

function onNodeClick(node) { selectedCat.value = node }

async function addRoot() {
  const name = newCatName.value.trim()
  if (!name) { ElMessage.warning('请输入分类名'); return }
  try { await createCategory({ name }); newCatName.value = ''; ElMessage.success('已添加根分类'); load() }
  catch (e) { err(e) }
}

async function addChild() {
  if (!selectedCat.value) { ElMessage.warning('请先在左侧选择父分类'); return }
  const name = newCatName.value.trim()
  if (!name) { ElMessage.warning('请输入子分类名'); return }
  try { await createCategory({ name, parentId: selectedCat.value.id }); newCatName.value = ''; ElMessage.success('已添加子分类'); load() }
  catch (e) { err(e) }
}

async function renameCat() {
  if (!selectedCat.value) return
  const name = newCatName.value.trim()
  if (!name) { ElMessage.warning('请输入新名称'); return }
  try { await updateCategory(selectedCat.value.id, { name }); newCatName.value = ''; ElMessage.success('已重命名'); load() }
  catch (e) { err(e) }
}

async function delCat() {
  if (!selectedCat.value) return
  try {
    await ElMessageBox.confirm('确定删除该分类？有子分类时会被拒绝', '提示', { type: 'warning' })
    await deleteCategory(selectedCat.value.id)
    selectedCat.value = null
    ElMessage.success('已删除')
    load()
  } catch (e) { if (e !== 'cancel') err(e) }
}

async function addTag() {
  const name = newTag.value.trim()
  if (!name) return
  try { await createTag(name); newTag.value = ''; ElMessage.success('已添加标签'); load() }
  catch (e) { err(e) }
}

async function delTag(id) {
  try {
    await ElMessageBox.confirm('确定删除该标签？', '提示', { type: 'warning' })
    await deleteTag(id); ElMessage.success('已删除'); load()
  } catch (e) { if (e !== 'cancel') err(e) }
}

// 从现有文档去重同步分类 / 标签到规范表（已存在自动跳过），避免规范表为空
async function syncFromDocs() {
  try {
    const cats = (await docCategories()).data || []
    const ts = (await docTags()).data || []
    for (const c of cats) { try { await createCategory({ name: c }) } catch (_) {} }
    for (const t of ts) { try { await createTag(t.tag || t.name) } catch (_) {} }
    ElMessage.success('已从文档同步 ' + (cats.length + ts.length) + ' 项到规范表（已存在自动跳过）')
    load()
  } catch (e) { ElMessage.error('同步失败') }
}

onMounted(load)
</script>

<template>
  <div class="tax">
    <h2>分类与标签管理</h2>
    <p class="tip">分类支持层级（树）；标签用于正文聚合与筛选。规范后的分类/标签会出现在发帖选择与首页筛选中。</p>
    <div class="cols">
      <section class="card">
        <div class="hd">分类树</div>
        <el-input v-model="newCatName" placeholder="分类名称" size="small" style="margin-bottom:8px" />
        <div class="cat-actions">
          <el-button size="small" @click="addRoot">新建根分类</el-button>
          <el-button size="small" :disabled="!selectedCat" @click="addChild">加子分类</el-button>
          <el-button size="small" :disabled="!selectedCat" @click="renameCat">重命名</el-button>
          <el-button size="small" type="danger" :disabled="!selectedCat" @click="delCat">删除</el-button>
        </div>
        <div class="sel" v-if="selectedCat">已选：<b>{{ selectedCat.name }}</b></div>
        <el-tree v-if="tree.length" :data="tree" node-key="id"
          :props="{ label: 'name', children: 'children' }"
          @node-click="onNodeClick" default-expand-all highlight-current class="ctree" />
        <p v-else class="empty">暂无分类，点击「新建根分类」或「从文档同步」</p>
        <el-button class="sync" size="small" plain @click="syncFromDocs">从现有文档同步</el-button>
      </section>

      <section class="card">
        <div class="hd">标签（{{ tags.length }}）</div>
        <div class="tag-add">
          <el-input v-model="newTag" placeholder="新标签名" size="small" @keyup.enter="addTag" />
          <el-button size="small" type="primary" @click="addTag">添加</el-button>
        </div>
        <div class="tag-list">
          <span v-for="t in tags" :key="t.id" class="tg">
            {{ t.name }}<i v-if="t.count">·{{ t.count }}</i>
            <button class="x" @click="delTag(t.id)">×</button>
          </span>
          <p v-if="!tags.length" class="empty">暂无标签</p>
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.tax { max-width: 920px; margin: 0 auto; color: #0f172a; padding: 24px; }
.tax h2 { font-size: 20px; margin: 0 0 4px; }
.tip { color: #64748b; font-size: 13px; margin: 0 0 16px; }
.cols { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
.card { background: #fff; border: 1px solid #e2e8f0; border-radius: 14px; padding: 16px; }
.hd { font-weight: 700; font-size: 15px; margin-bottom: 10px; }
.cat-actions { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 8px; }
.sel { font-size: 13px; color: #6366f1; margin-bottom: 8px; }
.ctree { border: 1px solid #eef2f7; border-radius: 10px; padding: 8px; max-height: 360px; overflow: auto; }
.empty { color: #94a3b8; font-size: 13px; padding: 8px 0; }
.sync { margin-top: 10px; }
.tag-add { display: flex; gap: 8px; margin-bottom: 10px; }
.tag-list { display: flex; flex-wrap: wrap; gap: 8px; }
.tg { background: #eef2ff; color: #4f46e5; border-radius: 999px; padding: 4px 10px; font-size: 13px; display: inline-flex; align-items: center; gap: 4px; }
.tg i { font-style: normal; opacity: .7; }
.tg .x { border: none; background: none; color: #6366f1; cursor: pointer; font-size: 14px; line-height: 1; padding: 0 2px; }
.tg .x:hover { color: #ef4444; }

/* 暗色模式适配（卡片背景/边框、树容器、说明文字、标签 chip 等硬编码浅色） */
html.dark .tax { color: var(--el-text-color-primary); }
html.dark .tip { color: var(--el-text-color-secondary); }
html.dark .card { background: var(--el-bg-color-overlay); border-color: var(--el-border-color); }
html.dark .sel { color: #a5b4fc; }
html.dark .ctree { border-color: var(--el-border-color-lighter); background: rgba(255,255,255,.02); }
html.dark .empty { color: var(--el-text-color-placeholder); }
html.dark .tg { background: rgba(99,102,241,.18); color: #a5b4fc; }
html.dark .tg .x { color: #a5b4fc; }
html.dark .tg .x:hover { color: #f87171; }

/* ===== 响应式（统一断点 1024 / 640，只写布局与尺寸） ===== */
@media (max-width: 1024px) {
  .tax { padding: 18px; }
  .cols { gap: 12px; }
  .card { padding: 14px; }
  .ctree { max-height: 300px; }
}
@media (max-width: 640px) {
  .tax { padding: 12px; }
  .tax h2 { font-size: 18px; }
  .tip { font-size: 12px; margin-bottom: 12px; }
  .cols { grid-template-columns: 1fr; }
  .card { padding: 12px; }
  .cat-actions { gap: 8px; }
  .ctree { max-height: 260px; padding: 6px; }
  .tag-add { flex-wrap: wrap; }
  .tag-add :deep(.el-input) { width: 100%; }
}
</style>
