<script setup>
import { Stamp } from '@element-plus/icons-vue'
import { computed } from 'vue'
import { ElMessage } from 'element-plus'
import { useWatermarkStore, buildContent } from '../../store/watermark'

const wm = useWatermarkStore()

// 预览用的示例身份：让管理员在保存前就能看到「用户名 + 时间 + 自定义文本」的实际效果
const sampleUser = { displayName: '张三', username: 'zhangsan' }
const previewContent = computed(() => buildContent(sampleUser, wm.config, new Date(), wm.clientInfo))

function save() {
  wm.save().then(() => ElMessage.success('水印配置已保存，全站已生效'))
}
function reset() {
  wm.reset()
  ElMessage.info('已恢复默认配置')
}
</script>

<template>
  <div class="wm-page">
    <div class="page-head">
      <div class="head-icon"><el-icon :size="22"><Stamp /></el-icon></div>
      <div class="head-text">
        <h2>全局水印设置</h2>
      <p class="sub">开启后，全站页面将叠加半透明水印，显示当前登录用户与时间，用于内部资料防泄密。配置由管理员统一设置、全员生效。</p>
      </div>
    </div>

    <div class="wm-grid">
      <!-- 左：参数配置 -->
      <el-card class="cfg" shadow="never">
        <template #header><span class="card-title">参数配置</span></template>

        <div class="row">
          <span class="label">启用全局水印</span>
          <el-switch v-model="wm.config.enabled" />
        </div>

        <el-divider />

        <div class="row col">
          <span class="label">自定义文本</span>
          <el-input v-model="wm.config.text" placeholder="如：内部资料 请勿外传" maxlength="40" show-word-limit />
        </div>

        <div class="row">
          <span class="label">叠加登录用户名</span>
          <el-switch v-model="wm.config.showUser" />
        </div>
        <div class="row">
          <span class="label">叠加当前时间</span>
          <el-switch v-model="wm.config.showTime" />
        </div>
        <div class="row">
          <span class="label">叠加当前电脑主机名</span>
          <el-switch v-model="wm.config.showHost" />
        </div>
        <p class="host-hint" v-if="wm.config.showHost">
          将叠加查看者主机名：<code>{{ wm.clientInfo.host || '（解析中或不可用）' }}</code>
        </p>

        <el-divider />

        <div class="row col">
          <span class="label">字号：{{ wm.config.fontSize }}px</span>
          <el-slider v-model="wm.config.fontSize" :min="10" :max="40" />
        </div>
        <div class="row col">
          <span class="label">颜色（含透明度）</span>
          <el-color-picker v-model="wm.config.color" show-alpha />
          <code class="color-val">{{ wm.config.color }}</code>
        </div>
        <div class="row col">
          <span class="label">暗色模式下的颜色（含透明度）</span>
          <el-color-picker v-model="wm.config.darkColor" show-alpha />
          <code class="color-val">{{ wm.config.darkColor }}</code>
        </div>
        <div class="row col">
          <span class="label">旋转角度：{{ wm.config.rotate }}°</span>
          <el-slider v-model="wm.config.rotate" :min="-90" :max="0" />
        </div>
        <div class="row col">
          <span class="label">横向间距：{{ wm.config.gapX }}px</span>
          <el-slider v-model="wm.config.gapX" :min="60" :max="400" />
        </div>
        <div class="row col">
          <span class="label">纵向间距：{{ wm.config.gapY }}px</span>
          <el-slider v-model="wm.config.gapY" :min="60" :max="400" />
        </div>
        <div class="row col">
          <span class="label">层级 z-index</span>
          <el-input-number v-model="wm.config.zIndex" :min="1" :max="2147483647" />
        </div>

        <div class="actions">
          <el-button type="primary" @click="save">保存配置</el-button>
          <el-button @click="reset">恢复默认</el-button>
        </div>
      </el-card>

      <!-- 右：实时预览 -->
      <el-card class="prev" shadow="never">
        <template #header>
          <span class="card-title">实时预览</span>
          <span class="hint">（以下为效果示意，保存后全站应用当前登录用户身份）</span>
        </template>
        <el-watermark
          :content="previewContent"
          :font="wm.font"
          :rotate="wm.config.rotate"
          :gap="wm.gap"
          :z-index="wm.config.zIndex">
          <div class="preview-doc">
            <h3>知识库文档示例</h3>
            <p>这是一段示例正文，用于预览水印在真实内容上的叠加效果。水印会以平铺方式覆盖整块区域，半透明、不可选中、不阻挡点击。</p>
            <p>当发生截图外泄时，水印上的用户名与时间可以帮助追溯来源，起到警示与追责作用。</p>
            <p>你可以在左侧调整文本、字号、颜色、角度与间距，右侧会即时反映变化。</p>
            <p v-for="n in 4" :key="n">示例段落 {{ n }}：内部资料仅限授权人员阅读，请勿转发或外传至公共渠道。</p>
          </div>
        </el-watermark>
      </el-card>
    </div>
  </div>
</template>

<style scoped>
.wm-page { max-width: 1080px; margin: 0 auto; padding: 20px; }
.page-head h2 { margin: 0 0 6px; font-size: 20px; color: #1e293b; }
.sub { margin: 0 0 16px; color: #64748b; font-size: 13px; line-height: 1.6; }
.wm-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; align-items: start; }
.card-title { font-weight: 700; color: #0f172a; }
.hint { margin-left: 8px; color: #94a3b8; font-size: 12px; }
.row { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin: 10px 0; }
.row.col { flex-direction: column; align-items: stretch; }
.label { font-size: 13px; color: #475569; }
.color-val { font-size: 12px; color: #94a3b8; }
.host-hint { margin: -4px 0 6px; font-size: 12px; color: #64748b; line-height: 1.6; }
.host-hint code { font-size: 12px; color: #4f46e5; background: #eef2ff; padding: 1px 6px; border-radius: 4px; }
:deep(html.dark) .host-hint { color: #a5a7bd; }
:deep(html.dark) .host-hint code { background: #23234a; color: #a5b4fc; }
.actions { margin-top: 18px; display: flex; gap: 12px; }
/* 暗色适配：标题/副标题/标签文字改用 Element Plus 变量，随主题自动翻转 */
:deep(html.dark) .page-head h2,
:deep(html.dark) .card-title { color: var(--el-text-color-primary); }
:deep(html.dark) .sub,
:deep(html.dark) .label,
:deep(html.dark) .host-hint { color: var(--el-text-color-regular); }
:deep(html.dark) .hint,
:deep(html.dark) .color-val { color: var(--el-text-color-secondary); }
.preview-doc { height: 320px; overflow: auto; padding: 16px; border: 1px dashed #cbd5e1; border-radius: 8px; background: #fff; }
.preview-doc h3 { margin-top: 0; color: #1e293b; }
.preview-doc p { color: #475569; line-height: 1.8; font-size: 14px; }
:deep(html.dark) .preview-doc { background: #1b1b2e; border-color: #2b2c4d; }
:deep(html.dark) .preview-doc h3 { color: #e2e8f0; }
:deep(html.dark) .preview-doc p { color: #a5a7bd; }

/* 响应式：窄屏单列，避免配置项与预览区各挤到 ~45vw 相互压扁 */
@media (max-width: 1024px) {
  .wm-grid { grid-template-columns: 1fr; }
}
@media (max-width: 640px) {
  .wm-page { padding: 12px; }
  .actions { flex-direction: column; }
  .actions .el-button { width: 100%; margin-left: 0 !important; }
}

/* 页头彩色图标徽章（与用户管理页统一） */
.page-head { display: flex; align-items: flex-start; gap: 14px; }
.head-text { flex: 1; min-width: 0; }
.head-icon { width: 46px; height: 46px; border-radius: 12px; flex-shrink: 0; display: flex; align-items: center; justify-content: center; background: #e2e8f0; color: #475569; }
html.dark .head-icon { background: rgb(71 85 105 / .25); color: #cbd5e1; }
</style>
