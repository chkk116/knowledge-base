<script setup>
/**
 * 登录页二维码设置（2026-10-06 改版：items 个性化列表）
 *
 * 二维码不再固定为「微信 / 技术支持」两个：管理员可添加任意数量，
 * 每项可自定义——① 下方文字（label）② 扫码内容（链接或任意文本，由系统生成二维码）
 * ③ 或直接上传现成二维码图片（image，存在时优先显示）。支持排序与删除。
 * 兼容：旧字段 wxUrl/supportUrl/wxImage/supportImage 原样保留在存储 JSON 里，
 * 旧版本前端仍能读到；新前端读 items 优先。
 */
import { ref, onMounted, watch } from 'vue'
import { ElMessage } from 'element-plus'
import QRCode from 'qrcode'
import api from '../../api/request'
import { uploadFile } from '../../api/file'
import { errMsg } from '../../utils/errMsg'

const KEY = 'login_qrcodes'
const items = ref([])          // [{ label, content, image, preview }]
const loaded = ref(false)
const saving = ref(false)
const imgUploading = ref(false)

function norm(it) {
  return { label: String(it.label ?? it.text ?? '').trim(), content: String(it.content ?? it.url ?? '').trim(), image: String(it.image ?? '').trim(), preview: '', _imgInput: null }
}

function addItem(prefill) {
  items.value.push(norm(prefill || { label: '', content: '' }))
}

function removeItem(i) {
  items.value.splice(i, 1)
}

function move(i, d) {
  const j = i + d
  if (j < 0 || j >= items.value.length) return
  const arr = items.value
  ;[arr[i], arr[j]] = [arr[j], arr[i]]
}

// ---- 预览：content 变化时生成二维码 SVG ----
let timer = null
function regen() {
  clearTimeout(timer)
  timer = setTimeout(async () => {
    const opt = { type: 'svg', margin: 1, width: 132, errorCorrectionLevel: 'H' }
    for (const it of items.value) {
      if (it.image || !it.content) { if (it.preview) it.preview = ''; continue }
      try {
        const svg = await QRCode.toString(it.content, opt)
        if (it.preview !== svg) it.preview = svg   // 不变不赋值：避免 deep watch 自触发循环
      } catch { if (it.preview) it.preview = '' }
    }
  }, 250)
}
watch(items, regen, { deep: true })

// ---- 上传现成二维码图片 ----
async function onImgPick(it, e) {
  const file = e.target.files && e.target.files[0]
  e.target.value = ''
  if (!file) return
  if (!/^image\/(png|jpe?g|webp)$/i.test(file.type)) { ElMessage.warning('仅支持 PNG / JPG / WebP 图片'); return }
  if (file.size > 5 * 1024 * 1024) { ElMessage.warning('图片不能超过 5MB'); return }
  imgUploading.value = true
  try {
    const { data } = await uploadFile(file)
    if (data?.errno !== 0) throw new Error(data?.message || '上传失败')
    it.image = data.data?.url
    ElMessage.success('图片已上传，保存后登录页生效')
  } catch (err) {
    ElMessage.error(err.response?.data?.error || err.message || '图片上传失败')
  } finally {
    imgUploading.value = false
  }
}

function download(it) {
  if (!it.preview) return
  const blob = new Blob([it.preview], { type: 'image/svg+xml;charset=utf-8' })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = '登录页-' + (it.label || '二维码') + '.svg'
  a.click()
  URL.revokeObjectURL(a.href)
}

onMounted(async () => {
  try {
    const { data } = await api.get(`/admin/settings/${KEY}`)
    if (data?.value) {
      const cfg = JSON.parse(data.value)
      if (Array.isArray(cfg.items) && cfg.items.length) {
        items.value = cfg.items.map(norm)
      } else {
        // 兼容旧配置：把两个固定码迁移成列表项
        if (cfg.wxUrl || cfg.wxImage) addItem({ label: '微信扫码体验移动版', content: cfg.wxUrl || '', image: cfg.wxImage || '' })
        if (cfg.supportUrl || cfg.supportImage) addItem({ label: '技术支持', content: cfg.supportUrl || '', image: cfg.supportImage || '' })
      }
    }
  } catch (e) { /* 无配置时保持空 */ }
  if (!items.value.length) {
    addItem({ label: '微信扫码体验移动版', content: '' })
    addItem({ label: '技术支持', content: '' })
  }
  loaded.value = true
  regen()
})

async function save() {
  for (const [i, it] of items.value.entries()) {
    if (!it.label.trim()) { ElMessage.warning(`第 ${i + 1} 项请填写下方文字`); return }
    if (!it.image && !it.content.trim()) { ElMessage.warning(`第 ${i + 1} 项「${it.label}」需填写扫码内容或上传图片`); return }
    if (it.content && !/^https?:\/\//i.test(it.content)) {
      // 允许任意文本生成二维码（如 Wifi 配置、名片文本），仅提示非链接
      ElMessage.info(`第 ${i + 1} 项内容不是 http(s) 链接，将按纯文本生成二维码（也可用）`)
      break
    }
  }
  saving.value = true
  try {
    // 保留旧字段（旧版前端兼容），新增 items
    const legacy = { wxUrl: '', supportUrl: '', wxImage: '', supportImage: '' }
    try {
      const { data } = await api.get(`/admin/settings/${KEY}`)
      if (data?.value) Object.assign(legacy, JSON.parse(data.value))
    } catch { /* 无旧配置 */ }
    await api.put(`/admin/settings/${KEY}`, {
      value: JSON.stringify({
        ...legacy,
        items: items.value.map((it) => ({ label: it.label.trim(), content: it.content.trim(), image: it.image.trim() })),
      })
    })
    ElMessage.success('已保存，登录页二维码即时生效')
  } catch (e) {
    ElMessage.error(errMsg(e, '保存失败'))
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div class="lq-page">
    <div class="page-head">
      <h2>登录页二维码</h2>
      <p class="sub">登录页左下角的二维码区完全可个性化：数量不限，每项可自定义下方文字、扫码内容（链接或任意文本），或直接上传现成二维码图片。保存后即时生效，无需重启。</p>
    </div>

    <div class="lq-grid">
      <el-card shadow="never" class="cfg">
        <template #header>
          <div class="card-head">
            <span class="card-title">二维码列表（{{ items.length }} 项）</span>
            <el-button size="small" type="primary" plain @click="addItem()">+ 添加一项</el-button>
          </div>
        </template>

        <div v-for="(it, i) in items" :key="i" class="item-card">
          <div class="item-head">
            <span class="idx">{{ i + 1 }}</span>
            <el-input v-model="it.label" class="label-input" maxlength="20" placeholder="下方文字，如：微信扫码体验移动版" />
            <div class="ops">
              <el-button size="small" text :disabled="i === 0" @click="move(i, -1)">↑</el-button>
              <el-button size="small" text :disabled="i === items.length - 1" @click="move(i, 1)">↓</el-button>
              <el-button size="small" text type="danger" @click="removeItem(i)">删除</el-button>
            </div>
          </div>
          <div class="row col">
            <span class="label">扫码内容（URL 或任意文本；上传图片后可留空）</span>
            <el-input v-model="it.content" placeholder="https://… 或任意文本" clearable />
          </div>
          <div class="img-row">
            <el-button size="small" text type="primary" :loading="imgUploading"
                       @click="it._imgInput && it._imgInput.click()">上传图片替代</el-button>
            <el-button v-if="it.image" size="small" text type="danger" @click="it.image = ''">清除图片</el-button>
            <span v-if="it.image" class="mode-tag">图片模式</span>
            <input :ref="(el) => { it._imgInput = el }" type="file" accept="image/png,image/jpeg,image/webp" hidden @change="onImgPick(it, $event)" />
          </div>
        </div>

        <div class="actions">
          <el-button type="primary" :loading="saving" @click="save">保存配置</el-button>
        </div>
      </el-card>

      <el-card shadow="never" class="prev">
        <template #header>
          <span class="card-title">实时预览</span>
          <span class="hint">（与登录页渲染一致）</span>
        </template>
        <div class="qr-row">
          <div v-for="(it, i) in items" :key="i" class="qr-item">
            <div class="qr-box">
              <img v-if="it.image" :src="it.image" class="qr-real qr-img" alt="" />
              <div v-else-if="it.preview" class="qr-real" v-html="it.preview"></div>
              <div v-else class="qr-empty">未配置</div>
              <div class="qr-badge">{{ (it.label || '?').charAt(0) }}</div>
            </div>
            <div class="qr-cap">{{ it.label || '（未填文字）' }}</div>
            <div class="mode-tag">{{ it.image ? '图片模式 · 登录页直接显示此图' : (it.preview ? '链接/文本生成模式' : '未配置') }}</div>
            <div class="img-acts">
              <el-button size="small" text type="primary" :disabled="!it.preview" @click="download(it)">下载</el-button>
            </div>
          </div>
        </div>
      </el-card>
    </div>
  </div>
</template>

<style scoped>
.lq-page { max-width: 1080px; margin: 0 auto; padding: 20px; }
.page-head h2 { margin: 0 0 6px; font-size: 20px; color: #1e293b; }
.sub { margin: 0 0 16px; color: #64748b; font-size: 13px; line-height: 1.6; }
.lq-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; align-items: start; }
.card-head { display: flex; align-items: center; justify-content: space-between; }
.card-title { font-weight: 700; color: #0f172a; }
.hint { margin-left: 8px; color: #94a3b8; font-size: 12px; }
.item-card {
  border: 1px solid #e5e9f2; border-radius: 10px; padding: 12px; margin-bottom: 12px;
}
.item-head { display: flex; align-items: center; gap: 10px; margin-bottom: 4px; }
.idx {
  flex: none; width: 22px; height: 22px; border-radius: 6px;
  display: grid; place-items: center;
  background: #eef4ff; color: #3b6fe0; font-size: 12px; font-weight: 700;
}
.label-input { flex: 1 1 auto; }
.ops { flex: none; display: flex; }
.ops .el-button { margin-left: 0; padding: 5px 6px; }
.row.col { display: flex; flex-direction: column; gap: 8px; margin: 10px 0; }
.label { font-size: 13px; color: #475569; }
.img-row { display: flex; align-items: center; gap: 8px; }
.mode-tag { font-size: 12px; color: #94a3b8; }
.actions { margin-top: 18px; display: flex; gap: 12px; }
.qr-row { display: flex; gap: 24px; justify-content: center; flex-wrap: wrap; }
.qr-item { display: flex; flex-direction: column; align-items: center; gap: 8px; }
.qr-box {
  position: relative; width: 132px; height: 132px; padding: 6px;
  background: #fff; border-radius: 8px;
  box-shadow: 0 8px 18px -8px rgba(28,52,108,.25), 0 0 0 1px rgba(28,52,108,.04);
}
.qr-real, .qr-real :deep(svg) { width: 100%; height: 100%; display: block; }
.qr-img { object-fit: contain; border-radius: 4px; }
.mode-tag { font-size: 12px; color: #94a3b8; }
.img-acts { display: flex; align-items: center; gap: 4px; }
.qr-empty { height: 100%; display: grid; place-items: center; color: #94a3b8; font-size: 12px; background: #f8fafc; border-radius: 4px; }
.qr-badge {
  position: absolute; left: 50%; top: 50%; transform: translate(-50%, -50%);
  width: 28px; height: 28px; border-radius: 6px;
  display: grid; place-items: center;
  color: #fff; font-size: 14px; font-weight: 700;
  background: #3b6fe0;
  box-shadow: 0 0 0 2.5px #fff;
}
.qr-cap { font-size: 12px; color: #6b7a99; text-align: center; max-width: 140px; }

:deep(html.dark) .page-head h2, :deep(html.dark) .card-title { color: var(--el-text-color-primary); }
:deep(html.dark) .sub, :deep(html.dark) .label, :deep(html.dark) .qr-cap { color: var(--el-text-color-regular); }
:deep(html.dark) .hint { color: var(--el-text-color-secondary); }
:deep(html.dark) .item-card { border-color: var(--el-border-color); }

@media (max-width: 1024px) {
  .lq-grid { grid-template-columns: 1fr; }
}
@media (max-width: 640px) {
  .lq-page { padding: 12px; }
  .actions { flex-direction: column; }
  .actions .el-button { width: 100%; margin-left: 0 !important; }
}
</style>
