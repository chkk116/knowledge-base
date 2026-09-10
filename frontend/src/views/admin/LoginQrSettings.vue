<script setup>
import { ref, onMounted, watch } from 'vue'
import { ElMessage } from 'element-plus'
import QRCode from 'qrcode'
import api from '../../api/request'
import { uploadFile } from '../../api/file'

const KEY = 'login_qrcodes'
const wxUrl = ref('')
const supportUrl = ref('')
// 图片模式：直接上传现成二维码图片（如微信群码截图），登录页优先显示图片；不上传才按链接生成
const wxImage = ref('')
const supportImage = ref('')
const wxPreview = ref('')
const supportPreview = ref('')
const loaded = ref(false)
const saving = ref(false)

// 防抖生成预览
let timer = null
function regen() {
  clearTimeout(timer)
  timer = setTimeout(async () => {
    const opt = { type: 'svg', margin: 1, width: 132, errorCorrectionLevel: 'H' }
    try { wxPreview.value = wxUrl.value ? await QRCode.toString(wxUrl.value, opt) : '' } catch { wxPreview.value = '' }
    try { supportPreview.value = supportUrl.value ? await QRCode.toString(supportUrl.value, opt) : '' } catch { supportPreview.value = '' }
  }, 250)
}
watch([wxUrl, supportUrl], regen)

// ---- 上传 / 更换二维码图片（走通用 /api/files，返回 /uploads/... 直链）----
const wxImgInput = ref(null)
const supImgInput = ref(null)
const imgUploading = ref(false)
function pickImg(which) {
  const el = which === 'wx' ? wxImgInput.value : supImgInput.value
  if (el) el.click()
}
async function onImgPick(which, e) {
  const file = e.target.files && e.target.files[0]
  e.target.value = ''
  if (!file) return
  if (!/^image\/(png|jpe?g|webp)$/i.test(file.type)) { ElMessage.warning('仅支持 PNG / JPG / WebP 图片'); return }
  if (file.size > 5 * 1024 * 1024) { ElMessage.warning('图片不能超过 5MB'); return }
  imgUploading.value = true
  try {
    // /api/files 返回 wangEditor 兼容格式 { errno:0, data:{ url } }
    const { data } = await uploadFile(file)
    if (data?.errno !== 0) throw new Error(data?.message || '上传失败')
    const url = data.data?.url
    if (which === 'wx') wxImage.value = url
    else supportImage.value = url
    ElMessage.success('图片已上传，保存后登录页生效')
  } catch (err) {
    ElMessage.error(err.response?.data?.error || err.message || '图片上传失败')
  } finally {
    imgUploading.value = false
  }
}
function clearImg(which) {
  if (which === 'wx') wxImage.value = ''
  else supportImage.value = ''
}

// 下载二维码（对标 TDesign「下载二维码」用法）：SVG 序列化成文件
function download(svgStr, name) {
  if (!svgStr) return
  const blob = new Blob([svgStr], { type: 'image/svg+xml;charset=utf-8' })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = name
  a.click()
  URL.revokeObjectURL(a.href)
}

onMounted(async () => {
  try {
    const { data } = await api.get(`/admin/settings/${KEY}`)
    if (data?.value) {
      const cfg = JSON.parse(data.value)
      wxUrl.value = cfg.wxUrl || ''
      supportUrl.value = cfg.supportUrl || ''
      wxImage.value = cfg.wxImage || ''
      supportImage.value = cfg.supportImage || ''
    }
  } catch (e) { /* 无配置时保持空，用登录页默认值兜底 */ }
  loaded.value = true
  regen()
})

async function save() {
  // 保存空值 = 恢复登录页内置默认；格式上做个宽松 URL 校验防手滑
  for (const [label, v] of [['微信码', wxUrl.value], ['技术支持码', supportUrl.value]]) {
    if (v && !/^https?:\/\//i.test(v)) {
      ElMessage.warning(`${label}地址需以 http(s):// 开头`)
      return
    }
  }
  saving.value = true
  try {
    await api.put(`/admin/settings/${KEY}`, {
      value: JSON.stringify({
        wxUrl: wxUrl.value.trim(),
        supportUrl: supportUrl.value.trim(),
        wxImage: wxImage.value.trim(),
        supportImage: supportImage.value.trim(),
      })
    })
    ElMessage.success('已保存，登录页二维码即时生效')
  } catch (e) {
    ElMessage.error(e.response?.data?.error || '保存失败')
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div class="lq-page">
    <div class="page-head">
      <h2>登录页二维码</h2>
      <p class="sub">配置登录页左下角两个二维码的扫码内容（微信体验版 / 技术支持）。保存后即时生效，无需重启。留空则使用系统内置默认地址。</p>
    </div>

    <div class="lq-grid">
      <el-card shadow="never" class="cfg">
        <template #header><span class="card-title">扫码内容</span></template>

        <div class="row col">
          <span class="label">微信扫码体验移动版（URL 或任意文本）</span>
          <el-input v-model="wxUrl" placeholder="https://… 留空使用默认地址" clearable />
        </div>
        <div class="row col">
          <span class="label">技术支持（URL 或任意文本）</span>
          <el-input v-model="supportUrl" placeholder="https://… 留空使用默认地址" clearable />
        </div>

        <el-alert class="tip" type="info" :closable="false" show-icon
                  title="两种方式任选" description="① 直接上传现成的二维码图片（如微信群码截图），登录页原样显示，随时可换；② 或填链接由系统实时生成二维码。上传图片后图片优先。" />

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
          <div class="qr-item">
            <div class="qr-box">
              <img v-if="wxImage" :src="wxImage" class="qr-real qr-img" alt="微信二维码" />
              <div v-else-if="wxPreview" class="qr-real" v-html="wxPreview"></div>
              <div v-else class="qr-empty">未配置</div>
              <div class="qr-badge wx">微</div>
            </div>
            <div class="qr-cap">微信扫码体验移动版</div>
            <div class="mode-tag">{{ wxImage ? '图片模式 · 登录页直接显示此图' : '链接生成模式' }}</div>
            <div class="img-acts">
              <el-button size="small" text type="primary" :loading="imgUploading" @click="pickImg('wx')">上传图片</el-button>
              <el-button v-if="wxImage" size="small" text type="danger" @click="clearImg('wx')">清除图片</el-button>
              <el-button v-else size="small" text type="primary" :disabled="!wxPreview"
                         @click="download(wxPreview, '登录页-微信体验版.svg')">下载</el-button>
            </div>
            <input ref="wxImgInput" type="file" accept="image/png,image/jpeg,image/webp" hidden @change="onImgPick('wx', $event)" />
          </div>
          <div class="qr-item">
            <div class="qr-box">
              <img v-if="supportImage" :src="supportImage" class="qr-real qr-img" alt="技术支持二维码" />
              <div v-else-if="supportPreview" class="qr-real" v-html="supportPreview"></div>
              <div v-else class="qr-empty">未配置</div>
              <div class="qr-badge sup">
                <svg viewBox="0 0 24 24" width="18" height="18"><path d="M4 12l5 5L20 6" stroke="#fff" stroke-width="2.5" fill="none" stroke-linecap="round" stroke-linejoin="round"/></svg>
              </div>
            </div>
            <div class="qr-cap">技术支持</div>
            <div class="mode-tag">{{ supportImage ? '图片模式 · 登录页直接显示此图' : '链接生成模式' }}</div>
            <div class="img-acts">
              <el-button size="small" text type="primary" :loading="imgUploading" @click="pickImg('sup')">上传图片</el-button>
              <el-button v-if="supportImage" size="small" text type="danger" @click="clearImg('sup')">清除图片</el-button>
              <el-button v-else size="small" text type="primary" :disabled="!supportPreview"
                         @click="download(supportPreview, '登录页-技术支持.svg')">下载</el-button>
            </div>
            <input ref="supImgInput" type="file" accept="image/png,image/jpeg,image/webp" hidden @change="onImgPick('sup', $event)" />
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
.card-title { font-weight: 700; color: #0f172a; }
.hint { margin-left: 8px; color: #94a3b8; font-size: 12px; }
.row.col { display: flex; flex-direction: column; gap: 8px; margin: 12px 0; }
.label { font-size: 13px; color: #475569; }
.tip { margin-top: 14px; }
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
  color: #fff; font-size: 16px; font-weight: 700;
  box-shadow: 0 0 0 2.5px #fff;
}
.qr-badge.wx { background: #22c55e; }
.qr-badge.sup { background: #3b82f6; }
.qr-cap { font-size: 12px; color: #6b7a99; }

:deep(html.dark) .page-head h2, :deep(html.dark) .card-title { color: var(--el-text-color-primary); }
:deep(html.dark) .sub, :deep(html.dark) .label, :deep(html.dark) .qr-cap { color: var(--el-text-color-regular); }
:deep(html.dark) .hint { color: var(--el-text-color-secondary); }

@media (max-width: 1024px) {
  .lq-grid { grid-template-columns: 1fr; }
}
@media (max-width: 640px) {
  .lq-page { padding: 12px; }
  .actions { flex-direction: column; }
  .actions .el-button { width: 100%; margin-left: 0 !important; }
}
</style>
