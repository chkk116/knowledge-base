<script setup>
/**
 * 客户端下载 · 后台设置（站点设置 → 客户端下载）
 *
 * 控制网站页脚「客户端下载」入口与 /download 下载页：
 *   - 总开关：关掉后页脚入口与下载页一起隐藏
 *   - 分平台开关：可单独隐藏 Android App / Windows 桌面端
 *   - 标题/说明：下载页顶部文案
 * 版本信息不在这里填：由各版本通道（app_version / app_version_native / app_version_desktop）决定，
 * 本页只做只读展示，便于确认线上是否已发布安装包。
 */
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Download } from '@element-plus/icons-vue'
import api from '../../api/request'
import { useDownloadStore } from '../../store/download'

const dl = useDownloadStore()
const saving = ref(false)
const toggling = ref(false)
const desktopVer = ref(null)
const androidVer = ref(null)

async function loadVersions() {
  try {
    const [d, a] = await Promise.all([
      api.get('/public/app-version?channel=desktop'),
      api.get('/public/app-version?channel=native')
    ])
    desktopVer.value = d.data
    androidVer.value = a.data
  } catch (e) {
    // 拉不到版本不影响开关配置
  }
}

/** 重新读取：开关文案与「当前版本」一起刷新，避免发版后只读卡片仍是旧值 */
async function reloadAll() {
  await dl.load()
  await loadVersions()
}

/** 总开关拨动即保存，失败回滚 */
async function onToggleEnabled(v) {
  toggling.value = true
  try {
    await dl.save()
    ElMessage.success(v ? '已开启下载中心，全站生效' : '已关闭下载中心，页脚入口与下载页已隐藏')
  } catch (e) {
    dl.enabled = !v
    ElMessage.error('保存失败：' + (e?.response?.data?.error || e?.message || '请检查网络'))
  } finally {
    toggling.value = false
  }
}

async function save() {
  saving.value = true
  try {
    await dl.save()
    ElMessage.success('已保存，全站生效')
  } catch (e) {
    ElMessage.error(e?.response?.data?.error || '保存失败')
  } finally {
    saving.value = false
  }
}

function verText(v) {
  if (!v || !v.versionCode) return '未发布'
  return `v${v.versionName || ''}（Build ${v.versionCode}）${v.url ? '' : ' · 无安装包地址'}`
}

function openDownloadPage() {
  window.open('/download', '_blank', 'noopener')
}

onMounted(() => {
  if (!dl.loaded) dl.load()
  loadVersions()
})
</script>

<template>
  <div class="dl-page">
    <div class="page-head">
      <div class="head-icon"><el-icon :size="22"><Download /></el-icon></div>
      <div class="head-text">
        <h2>客户端下载</h2>
        <p class="sub">
          控制网站页脚的「客户端下载」入口与 <code>/download</code> 下载页。关闭后入口与页面一起隐藏；
          版本号与安装包地址由各版本通道决定，这里只做展示。
        </p>
      </div>
    </div>

    <el-card class="cfg" shadow="never">
      <template #header><span class="card-title">总开关</span></template>
      <el-form label-width="120px" label-position="left">
        <el-form-item label="启用下载中心">
          <el-switch v-model="dl.enabled" :loading="toggling" @change="onToggleEnabled" />
          <span class="hint">关闭后页脚不再显示「客户端下载」，直接访问 /download 也会提示未开放。开关拨动即保存</span>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card class="cfg" shadow="never">
      <template #header><span class="card-title">展示平台与文案</span></template>
      <el-form label-width="120px" label-position="left">
        <el-form-item label="Android App">
          <el-switch v-model="dl.app" />
          <span class="hint">关闭后下载页不显示 Android 卡片（不影响已安装用户）</span>
        </el-form-item>
        <el-form-item label="Windows 桌面端">
          <el-switch v-model="dl.desktop" />
          <span class="hint">关闭后下载页不显示桌面端卡片</span>
        </el-form-item>
        <el-form-item label="下载页标题">
          <el-input v-model="dl.title" maxlength="30" show-word-limit placeholder="客户端下载" />
        </el-form-item>
        <el-form-item label="下载页说明">
          <el-input v-model="dl.desc" type="textarea" :rows="2" maxlength="120" show-word-limit placeholder="下载知识库客户端，随时随地使用" />
        </el-form-item>
        <div class="actions">
          <el-button type="primary" :loading="saving" @click="save">保存配置</el-button>
          <el-button text @click="reloadAll">重新读取</el-button>
        </div>
      </el-form>
    </el-card>

    <el-card class="cfg" shadow="never">
      <template #header><span class="card-title">当前版本（只读）</span></template>
      <div class="ver-row">
        <span class="ver-label">Windows 桌面端</span>
        <span class="ver-val" :class="{ off: !desktopVer || !desktopVer.url }">{{ verText(desktopVer) }}</span>
      </div>
      <div class="ver-row">
        <span class="ver-label">Android App</span>
        <span class="ver-val" :class="{ off: !androidVer || !androidVer.url }">{{ verText(androidVer) }}</span>
      </div>
      <div class="hint" style="margin-top:10px">
        发新版请在对应版本通道更新 versionCode / url / checksum，本页刷新即可看到。
      </div>
    </el-card>

    <el-card class="cfg" shadow="never">
      <template #header><span class="card-title">预览</span></template>
      <div class="actions">
        <el-button @click="openDownloadPage">打开下载页（新窗口）</el-button>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.dl-page { max-width: 900px; }
.page-head { display: flex; align-items: center; gap: 14px; margin-bottom: 18px; flex-wrap: wrap; }
.head-icon { width: 46px; height: 46px; border-radius: 12px; flex-shrink: 0; display: flex; align-items: center; justify-content: center; background: #e0f2fe; color: #0284c7; }
html.dark .head-icon { background: rgb(2 132 199 / .2); color: #7dd3fc; }
.head-text { flex: 1; min-width: 220px; }
.head-text h2 { margin: 0; font-size: 20px; font-weight: 700; color: var(--el-text-color-primary); }
.head-text .sub { margin: 6px 0 0; font-size: 13px; line-height: 1.7; color: var(--el-text-color-secondary); }
.head-text code { background: var(--el-fill-color-light); padding: 1px 5px; border-radius: 4px; }
.card-title { font-weight: 600; }
.cfg { margin-bottom: 16px; }
.cfg :deep(.el-form-item) { margin-bottom: 18px; }
.hint { margin-left: 10px; font-size: 12px; line-height: 1.6; color: var(--el-text-color-secondary); }
.actions { display: flex; gap: 8px; padding-top: 4px; }
.ver-row { display: flex; align-items: center; gap: 12px; padding: 7px 0; border-bottom: 1px dashed var(--el-border-color-lighter); font-size: 13px; }
.ver-row:last-of-type { border-bottom: none; }
.ver-label { width: 120px; color: var(--el-text-color-secondary); flex: none; }
.ver-val { color: var(--el-text-color-primary); font-weight: 500; }
.ver-val.off { color: var(--el-text-color-placeholder); font-weight: 400; }
</style>
