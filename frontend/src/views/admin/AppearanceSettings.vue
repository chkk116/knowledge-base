<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../../api/request'
import { uploadFile } from '../../api/file'
import { errMsg } from '../../utils/errMsg'

// 外观设置：站点 Logo（侧栏顶部图标）+ 个人资料页背景图（全站默认）。
// 存 SysSetting key=appearance，value 为 JSON { logo, profileBg }，均为 /uploads/... 直链。
// 写权限 CORE_MAINTAIN（后端 AdminSettingsController 兜底）。
const KEY = 'appearance'
const logo = ref('')
const profileBg = ref('')
const loaded = ref(false)
const saving = ref(false)

// ---- 上传（走通用 /api/files，返回 /uploads/... 直链，与登录页二维码同款） ----
const logoInput = ref(null)
const bgInput = ref(null)
const uploading = ref(false)
function pick(which) {
  const el = which === 'logo' ? logoInput.value : bgInput.value
  if (el) el.click()
}
async function onPick(which, e) {
  const file = e.target.files && e.target.files[0]
  e.target.value = ''
  if (!file) return
  if (!/^image\/(png|jpe?g|webp|gif)$/i.test(file.type)) { ElMessage.warning('仅支持 PNG / JPG / WebP / GIF 图片'); return }
  if (file.size > 5 * 1024 * 1024) { ElMessage.warning('图片不能超过 5MB'); return }
  uploading.value = true
  try {
    const { data } = await uploadFile(file)
    if (data?.errno !== 0) throw new Error(data?.message || '上传失败')
    const url = data.data?.url
    if (which === 'logo') logo.value = url
    else profileBg.value = url
    ElMessage.success('图片已上传，保存后全站生效')
  } catch (err) {
    ElMessage.error(err.response?.data?.error || err.message || '图片上传失败')
  } finally {
    uploading.value = false
  }
}
function clear(which) {
  if (which === 'logo') logo.value = ''
  else profileBg.value = ''
}

onMounted(async () => {
  try {
    const { data } = await api.get(`/admin/settings/${KEY}`)
    if (data?.value) {
      const cfg = JSON.parse(data.value)
      logo.value = cfg.logo || ''
      profileBg.value = cfg.profileBg || ''
    }
  } catch (e) { /* 无配置时保持空，前端用内置默认兜底 */ }
  loaded.value = true
})

async function save() {
  saving.value = true
  try {
    await api.put(`/admin/settings/${KEY}`, {
      value: JSON.stringify({ logo: logo.value.trim(), profileBg: profileBg.value.trim() })
    })
    ElMessage.success('已保存，刷新页面后全站生效')
  } catch (e) {
    ElMessage.error(errMsg(e, '保存失败'))
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div class="ap-page">
    <div class="page-head">
      <h2>外观设置</h2>
      <p class="sub">自定义站点 Logo（侧栏顶部的知识库图标）与个人资料页背景图，全站生效。清除后恢复系统内置默认。</p>
    </div>

    <div class="ap-grid">
      <el-card shadow="never">
        <template #header><span class="card-title">站点 Logo</span></template>
        <div class="item-preview">
          <div class="logo-box">
            <img v-if="logo" :src="logo" alt="站点 Logo" />
            <img v-else src="/logo.gif" alt="默认 Logo" />
          </div>
          <p class="preview-cap">{{ logo ? '自定义 Logo · 显示于侧栏顶部' : '当前为系统内置默认' }}</p>
          <p class="preview-tip">建议正方形图片，≥ 64×64，支持 PNG / JPG / WebP / GIF</p>
        </div>
        <div class="actions">
          <el-button type="primary" text :loading="uploading" @click="pick('logo')">上传 / 更换</el-button>
          <el-button v-if="logo" text type="danger" @click="clear('logo')">恢复默认</el-button>
        </div>
        <input ref="logoInput" type="file" accept="image/png,image/jpeg,image/webp,image/gif" hidden @change="onPick('logo', $event)" />
      </el-card>

      <el-card shadow="never">
        <template #header><span class="card-title">个人资料页背景图</span></template>
        <div class="item-preview">
          <div class="bg-box">
            <template v-if="profileBg">
              <div class="bg-blur" :style="{ backgroundImage: 'url(' + profileBg + ')' }"></div>
              <img class="bg-fit" :src="profileBg" alt="资料页背景" />
            </template>
            <div v-else class="bg-empty">未设置 · 使用内置默认背景</div>
          </div>
          <p class="preview-cap">{{ profileBg ? '自定义背景 · 显示于个人资料页顶部' : '当前为系统内置默认' }}</p>
          <p class="preview-tip">建议宽幅横图，≥ 1200×280，会被等比裁切铺满</p>
        </div>
        <div class="actions">
          <el-button type="primary" text :loading="uploading" @click="pick('bg')">上传 / 更换</el-button>
          <el-button v-if="profileBg" text type="danger" @click="clear('bg')">恢复默认</el-button>
        </div>
        <input ref="bgInput" type="file" accept="image/png,image/jpeg,image/webp" hidden @change="onPick('bg', $event)" />
      </el-card>
    </div>

    <div class="save-bar">
      <el-button type="primary" :loading="saving" :disabled="!loaded" @click="save">保存配置</el-button>
    </div>
  </div>
</template>

<style scoped>
.ap-page { max-width: 1080px; margin: 0 auto; padding: 20px; }
.page-head h2 { margin: 0 0 6px; font-size: 20px; color: #1e293b; }
.sub { margin: 0 0 16px; color: #64748b; font-size: 13px; line-height: 1.6; }
.ap-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; align-items: start; }
.card-title { font-weight: 700; color: #0f172a; }
.item-preview { display: flex; flex-direction: column; align-items: center; gap: 8px; padding: 8px 0; }
.logo-box { width: 96px; height: 96px; display: grid; place-items: center; background: #f8fafc; border-radius: 12px; box-shadow: 0 0 0 1px rgba(28,52,108,.06); }
.logo-box img { width: 64px; height: 64px; object-fit: contain; border-radius: 8px; }
.bg-box { position: relative; width: 100%; height: 132px; border-radius: 10px; overflow: hidden; background: #f8fafc; box-shadow: 0 0 0 1px rgba(28,52,108,.06); }
/* 与实际页面同款双层适配：底层模糊铺满 + 上层原图 contain，预览=实际效果 */
.bg-blur { position: absolute; inset: -16px; background: center / cover no-repeat; filter: blur(14px) brightness(1.06); }
.bg-fit { position: absolute; inset: 0; width: 100%; height: 100%; object-fit: contain; }
.bg-empty { height: 100%; display: grid; place-items: center; color: #94a3b8; font-size: 12px; }
.preview-cap { font-size: 13px; color: #475569; }
.preview-tip { font-size: 12px; color: #94a3b8; margin: 0; }
.actions { display: flex; justify-content: center; gap: 4px; margin-top: 6px; }
.save-bar { margin-top: 18px; display: flex; justify-content: center; }

@media (max-width: 1024px) {
  .ap-grid { grid-template-columns: 1fr; }
}
@media (max-width: 640px) {
  .ap-page { padding: 12px; }
}
</style>
