<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Collection, CirclePlus, Upload, Delete, View, Hide } from '@element-plus/icons-vue'
import {
  listBannedLibraries, createBannedLibrary, updateBannedLibrary, deleteBannedLibrary,
  listBannedWords, addBannedWords, deleteBannedWord, importBannedWords
} from '../../api/kb'
import PageBar from '../../components/PageBar.vue'

const libraries = ref([])
const selectedId = ref(null)
const selectedLib = ref(null)

const words = ref([])
const loading = ref(false)
const page = ref(0)          // 0-based，与后端对齐
const size = ref(24)         // 标签流一页 24 个更饱满
const total = ref(0)

const input = ref('')
const adding = ref(false)
const dialogVisible = ref(false)
const newName = ref('')
const newDesc = ref('')

// ===== 脱敏展示：默认遮蔽词条明文，点击右上角眼睛切换（防截屏/投屏泄露词库）=====
const reveal = ref(false)
const maskWord = (w) => {
  if (!w) return ''
  if (w.length <= 1) return '*'
  // 保留首字，其余以 * 代替（最多 4 个，长度也不泄露）
  return w.slice(0, 1) + '*'.repeat(Math.min(w.length - 1, 4))
}

// 顶部统计
const totalWords = computed(() => libraries.value.reduce((s, l) => s + (l.wordCount || 0), 0))
const enabledCount = computed(() => libraries.value.filter(l => l.enabled).length)

const loadLibs = async () => {
  try {
    const { data } = await listBannedLibraries()
    libraries.value = data || []
    // 若当前选中的词库已被删，重置
    if (selectedId.value && !libraries.value.some(l => l.id === selectedId.value)) {
      selectedId.value = null
      selectedLib.value = null
      words.value = []
      total.value = 0
    }
    // 初次进入且已有词库时，默认选中第一个，避免右侧空白
    if (!selectedId.value && libraries.value.length) {
      selectLib(libraries.value[0])
    } else if (selectedId.value) {
      // 同步最新词数/启停状态到右侧头部
      selectedLib.value = libraries.value.find(l => l.id === selectedId.value) || selectedLib.value
    }
  } catch (e) {
    ElMessage.error(e.response?.data?.error || '加载词库失败')
  }
}

const selectLib = (row) => {
  if (selectedId.value === row.id) return
  selectedId.value = row.id
  selectedLib.value = row
  page.value = 0
  loadWords()
}

const loadWords = async () => {
  if (!selectedId.value) return
  loading.value = true
  try {
    const { data } = await listBannedWords(selectedId.value, page.value, size.value)
    words.value = data.content || []
    total.value = data.total || 0
  } catch (e) {
    ElMessage.error(e.response?.data?.error || '加载词条失败')
  } finally {
    loading.value = false
  }
}

const onPage = (p) => { page.value = p - 1; loadWords() }

const add = async () => {
  const raw = input.value.trim()
  if (!raw) { ElMessage.warning('请填写要添加的违禁词'); return }
  adding.value = true
  try {
    const { data } = await addBannedWords(selectedId.value, raw)
    const added = data.addedCount || 0
    const dup = (data.duplicated || []).length
    let msg = `已添加 ${added} 个`
    if (dup) msg += `，${dup} 个重复已跳过`
    ElMessage.success(msg)
    input.value = ''
    await loadLibs()
    await loadWords()
  } catch (e) {
    ElMessage.error(e.response?.data?.error || '添加失败')
  } finally {
    adding.value = false
  }
}

// 标签流删除：词粒度低风险，直接删并提示，不弹确认框（提示不回显明文，防截屏泄露）
const remove = async (w) => {
  try {
    await deleteBannedWord(w.id)
    ElMessage.success('词条已删除')
    if (words.value.length === 1 && page.value > 0) page.value--
    await loadLibs()
    await loadWords()
  } catch (e) {
    ElMessage.error(e.response?.data?.error || '删除失败')
  }
}

const toggleLib = async (row, val) => {
  try {
    await updateBannedLibrary(row.id, { enabled: val })
    row.enabled = val
    selectedLib.value = row
    ElMessage.success(val ? '已启用该词库（已加载到实时过滤）' : '已停用该词库（已下线）')
  } catch (e) {
    ElMessage.error(e.response?.data?.error || '操作失败')
  }
}

const openCreate = () => { newName.value = ''; newDesc.value = ''; dialogVisible.value = true }
const createLib = async () => {
  if (!newName.value.trim()) { ElMessage.warning('请填写词库名称'); return }
  try {
    await createBannedLibrary(newName.value.trim(), newDesc.value.trim())
    ElMessage.success('词库已创建（默认启用）')
    dialogVisible.value = false
    await loadLibs()
  } catch (e) {
    ElMessage.error(e.response?.data?.error || '创建失败')
  }
}

// ===== 离线词库导入（下载的在线词库文件 / 粘贴）=====
const importVisible = ref(false)
const importTarget = ref('existing')   // existing | new
const importLibId = ref(null)
const importNewName = ref('')
const importContent = ref('')
const importing = ref(false)
const fileInput = ref(null)

const importWordCount = computed(
  () => importContent.value.split(/[\r\n]+/).filter(s => s.trim()).length
)

const openImport = () => {
  importTarget.value = 'existing'
  importLibId.value = selectedId.value
  importNewName.value = ''
  importContent.value = ''
  importVisible.value = true
}
const pickFile = () => fileInput.value?.click()
const onFile = (e) => {
  const f = e.target.files?.[0]
  if (!f) return
  const reader = new FileReader()
  reader.onload = () => { importContent.value = String(reader.result || '') }
  reader.readAsText(f, 'utf-8')
  e.target.value = ''   // 允许重复选择同一文件
}
const doImport = async () => {
  let libId = importLibId.value
  if (importTarget.value === 'new') {
    if (!importNewName.value.trim()) { ElMessage.warning('请填写新词库名称'); return }
    try {
      const { data } = await createBannedLibrary(importNewName.value.trim(), '导入的离线词库')
      libId = data.id
    } catch (e) {
      ElMessage.error(e.response?.data?.error || '创建词库失败')
      return
    }
  }
  if (!libId) { ElMessage.warning('请选择目标词库'); return }
  if (!importContent.value.trim()) { ElMessage.warning('请选择文件或粘贴词库内容'); return }
  importing.value = true
  try {
    const { data } = await importBannedWords(libId, importContent.value)
    ElMessage.success(`导入完成：新增 ${data.addedCount} 个，重复跳过 ${data.duplicatedCount} 个`)
    importVisible.value = false
    await loadLibs()
    const lib = libraries.value.find(l => l.id === libId)
    if (lib) selectLib(lib)
  } catch (e) {
    ElMessage.error(e.response?.data?.error || '导入失败')
  } finally { importing.value = false }
}

const removeLib = async (row) => {
  try {
    await ElMessageBox.confirm(`确认删除词库「${row.name}」？其下 ${row.wordCount} 个词条将一并删除。`, '删除确认', { type: 'warning' })
  } catch { return }
  try {
    await deleteBannedLibrary(row.id)
    ElMessage.success('词库已删除')
    if (selectedId.value === row.id) { selectedId.value = null; selectedLib.value = null; words.value = []; total.value = 0 }
    await loadLibs()
  } catch (e) {
    ElMessage.error(e.response?.data?.error || '删除失败')
  }
}

onMounted(loadLibs)
</script>

<template>
  <div class="bw-page">
    <!-- 页头 -->
    <div class="page-head">
      <div class="head-icon"><el-icon :size="22"><Collection /></el-icon></div>
      <div class="head-text">
        <h2>违禁词管理</h2>
        <p>按词库分组，可启停「选择加载」；仅启用中的词库参与实时过滤（评论拦截 / 审核检查）</p>
      </div>
      <div class="head-stats">
        <div class="stat">
          <b>{{ libraries.length }}</b>
          <span>词库</span>
        </div>
        <div class="stat">
          <b>{{ totalWords }}</b>
          <span>违禁词</span>
        </div>
        <div class="stat">
          <b class="ok">{{ enabledCount }}</b>
          <span>启用中</span>
        </div>
      </div>
    </div>

    <el-row :gutter="16" class="layout">
      <!-- 左：词库 -->
      <el-col :xs="24" :sm="24" :md="9" :lg="8">
        <el-card shadow="never" class="panel lib-panel">
          <div class="panel-head">
            <span class="panel-title">
              <el-icon class="t-ico"><Collection /></el-icon>词库
            </span>
            <div class="head-btns">
              <el-button size="small" :icon="Upload" @click="openImport">导入</el-button>
              <el-button type="primary" size="small" :icon="CirclePlus" @click="openCreate">新建</el-button>
            </div>
          </div>

          <div class="lib-list">
            <div v-for="row in libraries" :key="row.id"
                 class="lib-item" :class="{ active: row.id === selectedId, off: !row.enabled }"
                 @click="selectLib(row)">
              <div class="li-row">
                <span class="li-name">{{ row.name }}</span>
                <el-switch size="small" :model-value="row.enabled"
                           @change="(v) => toggleLib(row, v)" @click.stop />
              </div>
              <div class="li-row sub">
                <span class="li-desc">{{ row.description || '暂无说明' }}</span>
                <span class="li-count">{{ row.wordCount }} 词</span>
              </div>
              <button class="li-del" title="删除词库" @click.stop="removeLib(row)">
                <el-icon :size="14"><Delete /></el-icon>
              </button>
            </div>
            <el-empty v-if="!libraries.length" description="还没有词库，点击右上角新建" :image-size="64" />
          </div>
        </el-card>
      </el-col>

      <!-- 右：词条 -->
      <el-col :xs="24" :sm="24" :md="15" :lg="16">
        <el-card shadow="never" class="panel word-panel">
          <div class="panel-head">
            <template v-if="selectedLib">
              <span class="panel-title">
                <el-icon class="t-ico" :class="selectedLib.enabled ? 'on' : ''"><Collection /></el-icon>
                {{ selectedLib.name }}
              </span>
              <el-tag :type="selectedLib.enabled ? 'success' : 'info'" size="small" effect="light" round>
                {{ selectedLib.enabled ? '已加载' : '已停用' }}
              </el-tag>
              <span class="word-total">{{ total }} 个词条</span>
              <div class="reveal-box" :class="{ on: reveal }" title="切换明文/脱敏显示"
                   @click="reveal = !reveal">
                <el-icon :size="15">
                  <View v-if="reveal" />
                  <Hide v-else />
                </el-icon>
                <span>{{ reveal ? '明文中' : '已脱敏' }}</span>
              </div>
            </template>
            <span v-else class="panel-title">词条管理</span>
          </div>

          <template v-if="selectedLib">
            <div class="add-row">
              <el-input v-model="input" type="textarea" :rows="2"
                :placeholder="'向「' + selectedLib.name + '」批量添加，每行一个，可粘贴多行'" class="add-input" />
              <el-button type="primary" :loading="adding" @click="add" class="add-btn">添加</el-button>
            </div>

            <div v-loading="loading" class="tag-cloud">
              <span v-for="w in words" :key="w.id" class="bw-tag" :class="{ masked: !reveal }">
                <span class="tag-word">{{ reveal ? w.word : maskWord(w.word) }}</span>
                <button class="tag-x" title="删除该词条" @click="remove(w)">×</button>
              </span>
              <el-empty v-if="!loading && !words.length" description="该词库暂无词条，请在上方添加" :image-size="72" />
            </div>

            <PageBar v-if="total > 0" :model-value="page + 1" :total="total" :page-size="size"
                     @update:model-value="onPage" />
          </template>

          <el-empty v-else description="请选择左侧词库以管理其词条" />
        </el-card>
      </el-col>
    </el-row>

    <el-dialog v-model="dialogVisible" title="新建词库" width="420px">
      <el-form label-width="64px">
        <el-form-item label="名称" required>
          <el-input v-model="newName" placeholder="如 广告导流 / 政治敏感 / 自定义" maxlength="64" />
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="newDesc" placeholder="可选，描述该词库用途" maxlength="255" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="createLib">创建</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="importVisible" title="导入离线词库" width="560px">
      <el-alert type="info" :closable="false" class="imp-tip">
        <template #title>
          支持导入「从网上下载的敏感词库文件」：每行一个词（txt / csv 单列 / 逗号分隔均可）。
          可在 GitHub 搜索 sensitive-words / 违禁词 / 广告词 等开源词表，下载后在此导入。
        </template>
      </el-alert>
      <el-form label-width="84px" class="imp-form">
        <el-form-item label="导入到">
          <el-radio-group v-model="importTarget">
            <el-radio value="existing">已有词库</el-radio>
            <el-radio value="new">新建词库</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="importTarget === 'existing'" label="目标词库">
          <el-select v-model="importLibId" placeholder="选择词库" style="width:100%">
            <el-option v-for="l in libraries" :key="l.id" :label="l.name" :value="l.id" />
          </el-select>
        </el-form-item>
        <el-form-item v-else label="新词库名">
          <el-input v-model="importNewName" placeholder="如 政治敏感 / 广告导流 / 自定义" maxlength="64" />
        </el-form-item>
        <el-form-item label="词库文件">
          <input ref="fileInput" type="file" accept=".txt,.csv,.json,.lst,.text" style="display:none" @change="onFile" />
          <el-button :icon="Upload" @click="pickFile">选择文件…</el-button>
          <span class="file-hint" v-if="importContent">已载入 {{ importWordCount }} 个词</span>
        </el-form-item>
        <el-form-item label="或粘贴">
          <el-input v-model="importContent" type="textarea" :rows="6" placeholder="也可直接粘贴词库内容，每行一个词" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="importVisible = false">取消</el-button>
        <el-button type="primary" :loading="importing" @click="doImport">开始导入</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
/* ===== 页头 ===== */
.page-head {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-bottom: 18px;
  flex-wrap: wrap;
}
.head-icon {
  width: 46px; height: 46px;
  border-radius: 12px;
  display: flex; align-items: center; justify-content: center;
  background: linear-gradient(135deg, #6366f1, #4f46e5);
  color: #fff;
  box-shadow: 0 4px 12px rgba(79, 70, 229, .3);
  flex-shrink: 0;
}
.head-text { flex: 1; min-width: 220px; }
.head-text h2 { margin: 0; font-size: 20px; font-weight: 700; color: #1e293b; }
.head-text p { margin: 4px 0 0; font-size: 13px; color: #94a3b8; }
.head-stats { display: flex; gap: 22px; margin-left: auto; }
.stat { text-align: center; min-width: 48px; }
.stat b {
  display: block; font-size: 22px; line-height: 1.1;
  font-weight: 700; color: #4f46e5;
  font-variant-numeric: tabular-nums;
}
.stat b.ok { color: #16a34a; }
.stat span { font-size: 12px; color: #94a3b8; }

/* ===== 面板通用 ===== */
.layout { align-items: stretch; }
.panel { border-radius: 12px; border-color: #e2e8f0; }
.panel-head {
  display: flex; align-items: center; gap: 10px;
  padding-bottom: 12px; margin-bottom: 12px;
  border-bottom: 1px dashed #e2e8f0;
}
.panel-title {
  display: inline-flex; align-items: center; gap: 6px;
  font-size: 15px; font-weight: 700; color: #1e293b;
}
.t-ico { color: #6366f1; }
.t-ico.on { color: #16a34a; }
.head-btns { margin-left: auto; display: flex; gap: 8px; }
.word-total { font-size: 12px; color: #94a3b8; margin-left: auto; }

/* 明文/脱敏切换按钮 */
.reveal-box {
  display: inline-flex; align-items: center; gap: 4px;
  padding: 3px 10px;
  border-radius: 999px;
  border: 1px solid #e2e8f0;
  background: #f8fafc;
  color: #94a3b8;
  font-size: 12px;
  cursor: pointer;
  user-select: none;
  transition: .15s;
  flex-shrink: 0;
}
.reveal-box:hover { border-color: #c7d2fe; color: #6366f1; }
.reveal-box.on {
  background: #fff7ed;
  border-color: #fdba74;
  color: #c2410c;
}
.reveal-box.on:hover { border-color: #fb923c; color: #ea580c; }

/* ===== 左：词库列表 ===== */
.lib-list { display: flex; flex-direction: column; gap: 8px; max-height: 560px; overflow-y: auto; padding-right: 2px; }
.lib-item {
  position: relative;
  padding: 10px 12px 9px;
  border-radius: 10px;
  border: 1px solid transparent;
  background: #f8fafc;
  cursor: pointer;
  transition: .15s;
}
.lib-item:hover { background: #f1f5f9; }
.lib-item.active {
  background: #eef2ff;
  border-color: #c7d2fe;
  box-shadow: 0 2px 8px rgba(99, 102, 241, .12);
}
.lib-item.active::before {
  content: '';
  position: absolute; left: 0; top: 10px; bottom: 10px;
  width: 3px; border-radius: 3px;
  background: linear-gradient(180deg, #6366f1, #4f46e5);
}
.lib-item.off { opacity: .62; }
.li-row { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.li-row.sub { margin-top: 4px; justify-content: flex-start; }
.li-name { font-weight: 600; font-size: 14px; color: #1e293b; }
.li-desc {
  font-size: 12px; color: #94a3b8;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.li-count {
  margin-left: auto; flex-shrink: 0;
  font-size: 11px; color: #6366f1; background: #e0e7ff;
  padding: 1px 8px; border-radius: 999px;
  font-variant-numeric: tabular-nums;
}
.li-del {
  position: absolute; right: 8px; bottom: 7px;
  width: 22px; height: 22px;
  display: none;
  align-items: center; justify-content: center;
  border: none; border-radius: 6px;
  background: transparent; color: #ef4444;
  cursor: pointer; transition: .15s;
}
.lib-item:hover .li-del { display: inline-flex; }
.li-del:hover { background: #fee2e2; }

/* ===== 右：词条 ===== */
.add-row { display: flex; gap: 12px; align-items: stretch; margin-bottom: 14px; }
.add-input { flex: 1; }
.add-btn { align-self: stretch; white-space: nowrap; }

.tag-cloud {
  display: flex; flex-wrap: wrap; gap: 9px;
  min-height: 120px;
  padding: 4px 2px;
}
.bw-tag {
  display: inline-flex; align-items: center; gap: 4px;
  padding: 4px 6px 4px 12px;
  border-radius: 999px;
  background: #fef2f2;
  border: 1px solid #fecaca;
  color: #b91c1c;
  font-size: 13px;
  line-height: 1.4;
  transition: .15s;
  animation: pop-in .18s ease;
}
.tag-x {
  width: 18px; height: 18px;
  display: inline-flex; align-items: center; justify-content: center;
  border: none; border-radius: 50%;
  background: transparent; color: #dc2626;
  font-size: 14px; line-height: 1;
  cursor: pointer; transition: .15s;
  opacity: .45;
}
.bw-tag:hover { background: #fee2e2; border-color: #fca5a5; }
.bw-tag:hover .tag-x { opacity: 1; }
.tag-x:hover { background: #dc2626; color: #fff; }

/* 脱敏态：词面弱化为占位符观感（灰字虚线风），暗示"非明文" */
.bw-tag.masked {
  background: #f8fafc;
  border: 1px dashed #cbd5e1;
  color: #64748b;
  letter-spacing: 1px;
  font-variant-numeric: tabular-nums;
}
.bw-tag.masked:hover { background: #f1f5f9; border-color: #94a3b8; }
.tag-word { user-select: none; }

@keyframes pop-in {
  from { transform: scale(.85); opacity: 0; }
  to { transform: scale(1); opacity: 1; }
}

.imp-tip { margin-bottom: 12px; }
.imp-form { margin-top: 4px; }
.file-hint { color: #94a3b8; font-size: 12px; margin-left: 8px; }

/* ===== 暗色模式 ===== */
html.dark .head-text h2 { color: #e7e9ee; }
html.dark .head-text p { color: #7c8290; }
html.dark .panel { border-color: #2c2c36; background: #1c1c26; }
html.dark .panel-title { color: #e7e9ee; }
html.dark .panel-head { border-bottom-color: #2c2c36; }
html.dark .lib-item { background: #232330; }
html.dark .lib-item:hover { background: #2a2a3a; }
html.dark .lib-item.active { background: #232347; border-color: #4f46e5; }
html.dark .li-name { color: #e7e9ee; }
html.dark .li-desc { color: #7c8290; }
html.dark .li-count { background: #2c2c52; color: #a5b4fc; }
html.dark .li-del:hover { background: #3a1d1d; }
html.dark .bw-tag { background: #2b1d1f; border-color: #4a2325; color: #f87171; }
html.dark .bw-tag:hover { background: #3a2224; }
html.dark .bw-tag.masked { background: #232330; border-color: #3a3a4a; border-style: dashed; color: #7c8290; }
html.dark .bw-tag.masked:hover { background: #2a2a3a; }
html.dark .tag-x { color: #f87171; }
html.dark .tag-x:hover { background: #dc2626; color: #fff; }
html.dark .reveal-box { background: #232330; border-color: #2c2c36; color: #7c8290; }
html.dark .reveal-box:hover { border-color: #4f46e5; color: #a5b4fc; }
html.dark .reveal-box.on { background: #3a2a1a; border-color: #a16207; color: #fbbf24; }

@media (max-width: 768px) {
  .layout { display: block; }
  .add-row { flex-direction: column; }
  .head-stats { margin-left: 0; }
}
</style>
