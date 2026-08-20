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
</style>
