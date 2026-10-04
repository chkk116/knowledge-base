<script setup>
import '@wangeditor/editor/dist/css/style.css'
import { ref, shallowRef, watch, onMounted, onBeforeUnmount } from 'vue'
import { createEditor, createToolbar, Boot } from '@wangeditor/editor'
import markdownModule from '@wangeditor/plugin-md'
import { ElMessage } from 'element-plus'
import { uploadFile } from '../api/file'

// 注册 Markdown 插件（必须在创建编辑器之前，且只注册一次；用 window 标记防止 HMR 重复注册）
if (!window.__kbMdRegistered) {
  Boot.registerModule(markdownModule)
  window.__kbMdRegistered = true
}

const props = defineProps({
  modelValue: { type: String, default: '' },
})
const emit = defineEmits(['update:modelValue'])

const editorRef = shallowRef()
const editorBox = ref(null)
const toolbarBox = ref(null)
// 记录上一次编辑器自己 emit 出去的 html，避免 watch 回灌造成 setHtml 把节点树冲掉（Ctrl+Z 报 Cannot find a descendant 的根因）
const lastHtml = ref('')

const toolbarConfig = { excludeKeys: ['fullScreen'] } // 禁用内置全屏（会令下方按钮错位），全屏由 PostEdit 自管

const editorConfig = {
  placeholder: '输入正文，支持 Markdown 语法：# 标题、- 列表、> 引用、``` 代码、--- 分割线',
  MENU_CONF: {
    // 图片上传复用 /api/files，返回 { errno:0, data:{ url } }
    uploadImage: {
      customUpload(file, insertFn) {
        if (!file.type.startsWith('image/')) {
          ElMessage.warning('请选择图片文件')
          return
        }
        uploadFile(file)
          .then(({ data }) => {
            if (data && data.data && data.data.url) insertFn(data.data.url, '', '')
            else ElMessage.error('图片上传失败')
          })
          .catch(() => {
            ElMessage.error('图片上传失败，请重试（需登录且为 png/jpg/gif/webp/bmp）')
          })
      },
    },
  },
}

onMounted(() => {
  const editor = createEditor({
    selector: editorBox.value,
    html: props.modelValue || '',
    config: {
      ...editorConfig,
      onChange: (e) => {
        const html = e.getHtml()
        lastHtml.value = html
        emit('update:modelValue', html)
      },
    },
    mode: 'default',
  })
  editorRef.value = editor
  if (toolbarBox.value) {
    createToolbar({ editor, selector: toolbarBox.value, config: toolbarConfig, mode: 'default' })
  }
})

// 父组件回填（切换 / 编辑已有文章）。
// 关键：自己 emit 出去的 html 会用 lastHtml 标记，watch 命中时直接跳过，不回灌 setHtml，
// 这样撤销(Ctrl+Z)、输入都不会被强行重置节点树。
watch(
  () => props.modelValue,
  (v) => {
    const e = editorRef.value
    if (!e) return
    if (v === lastHtml.value) return
    if (v !== e.getHtml()) e.setHtml(v || '')
  }
)

// 供父组件（PostEdit）插入任意 HTML，例如表情 <img class="kb-emoji" src="/emojis/...">
// wangEditor 通过 dangerouslyInsertHtml 插到当前光标处，并会触发 onChange 回写 modelValue
function insertHtml(html) {
  const e = editorRef.value
  if (!e) return
  e.dangerouslyInsertHtml(html)
}

onBeforeUnmount(() => {
  editorRef.value?.destroy()
})

defineExpose({ insertHtml })
</script>

<template>
  <div class="rich">
    <!-- 编辑器主体：wangEditor 自带工具栏（加粗/标题/列表/图片/链接等）+ Markdown 快捷输入 -->
    <div class="editor-wrap">
      <div ref="toolbarBox" class="w-bar"></div>
      <div ref="editorBox" class="w-editor"></div>
    </div>
  </div>
</template>

<style scoped>
.rich { position: relative; margin-bottom: 4px; }
.editor-wrap { border: 1px solid #dcdfe6; border-radius: 8px; overflow: hidden; }
.w-editor { height: 460px; }
/* 文章编辑区内插入的贴吧表情：限制高度，避免按原图尺寸把整行撑高导致文字溢出 */
.w-editor :deep(img.kb-emoji) { height: 20px; vertical-align: middle; margin: 0 1px; }
.w-editor :deep(img) { max-width: 100%; }

/* ============================================================
   移动端适配（断点沿用全站约定：≤1024 平板 / ≤640 手机）
   ------------------------------------------------------------
   编辑器高度改为按视口计算，避免固定 460px 在手机上占掉大半个屏幕，
   并让工具栏在窄屏可横向滚动而不是换行成 3~4 行吃掉编辑区高度。
   ============================================================ */
@media (max-width: 1024px) {
  .w-editor { height: 380px; }
  /* 工具栏：单行横向滚动，不换行堆高。
     注意：wangEditor 自带的 .w-e-toolbar{flex-wrap:wrap} 与本规则特异性相同，
     且其样式表注入在后，故必须用 !important 才能压住（否则手机上工具栏仍会堆成 4 行）。 */
  .w-bar :deep(.w-e-toolbar) {
    flex-wrap: nowrap !important;
    overflow-x: auto;
    overflow-y: hidden;
    -webkit-overflow-scrolling: touch;
    scrollbar-width: none;          /* Firefox 隐藏滚动条 */
  }
  .w-bar :deep(.w-e-toolbar)::-webkit-scrollbar { display: none; }
  .w-bar :deep(.w-e-bar-item) { flex: 0 0 auto; }   /* 不压缩，靠滚动查看 */
}

@media (max-width: 640px) {
  /* 高度按视口算：整屏减去顶栏/工具栏/底部余量，键盘弹出时 dwvh 会自适应 */
  .w-editor { height: calc(100dvh - 300px); min-height: 260px; }
  /* 工具栏按钮略小，一屏能多放几个；触控目标仍保持 ≥36px 可点 */
  .w-bar :deep(.w-e-bar-item) { height: 36px; padding: 2px; }
  .w-bar :deep(.w-e-bar-item button) { min-width: 30px; }
  .w-bar :deep(.w-e-bar-divider) { margin: 0 2px; }
}

/* 兜底：不支持 dvh 的旧 WebView / 浏览器回退到 vh */
@supports not (height: 100dvh) {
  @media (max-width: 640px) {
    .w-editor { height: calc(100vh - 300px); }
  }
}
</style>
