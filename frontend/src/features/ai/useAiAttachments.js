import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { compressImage } from '../../utils/imageCompress'
import { parseDocument, DOC_EXTS } from '../../utils/docParse'
import {
  MAX_ATTACHMENTS, ATT_TEXT_MAX, DOC_SIZE_LIMIT,
  attachmentSizeLimit, isPlainTextFile, truncateAttachmentText, makeAttachmentId
} from './attachmentRules'

/**
 * AI 问答附件：本地解析（图片压缩走多模态、文档转文本或转图、纯文本直读），随提问注入。
 * 从 AiChat.vue 抽出的内聚块；返回的状态在 <script setup> 里解构后模板变量名保持不变。
 */
export function useAiAttachments() {
  const attachments = ref([])   // [{ id, name, size, kind: 'image'|'text', text, dataUrl?, mode? }]
  const fileInput = ref(null)
  const attBusy = ref(false)

  function pickFiles() { fileInput.value?.click() }

  function removeAttachment(id) {
    attachments.value = attachments.value.filter((a) => a.id !== id)
  }

  /** 图片附件切换处理方式：看图（多模态）⇄ 识文字（本地 OCR，离线可用） */
  function toggleAttMode(id) {
    const a = attachments.value.find((x) => x.id === id)
    if (a && a.kind === 'image') a.mode = a.mode === 'vision' ? 'ocr' : 'vision'
  }

  function clearAttachments() { attachments.value = [] }

  function add(a) { attachments.value = [...attachments.value, a] }

  async function onFilesPicked(e) {
    const list = Array.from(e.target.files || [])
    e.target.value = ''
    if (!list.length || attBusy.value) return
    attBusy.value = true
    for (const f of list) {
      if (attachments.value.length >= MAX_ATTACHMENTS) { ElMessage.warning('一次最多带 4 个附件'); break }
      const isDoc = DOC_EXTS.test(f.name)
      const sizeLimit = attachmentSizeLimit(f.name, DOC_EXTS)
      if (f.size > sizeLimit) {
        ElMessage.warning('「' + f.name + '」超过 ' + (sizeLimit === DOC_SIZE_LIMIT ? 20 : 8) + 'MB，已跳过')
        continue
      }
      try {
        if (/^image\//i.test(f.type)) {
          // 图片：默认「看图」——压缩后多模态直接交给模型（图案/排版都能识别）；
          // 也可在附件 chip 上切「识文字」用本地 OCR 提取文字（离线可用）。
          const dataUrl = await compressImage(f)
          add({
            id: makeAttachmentId(), name: f.name, size: f.size,
            kind: 'image', dataUrl, text: '', file: f, mode: 'vision'
          })
          continue
        }
        if (isDoc) {
          // PDF / Word / PPT / Excel：本地解析成文本（PDF 扫描件页自动转图走多模态）
          ElMessage.info('正在解析「' + f.name + '」…')
          const r = await parseDocument(f)
          const txt = (r && r.text ? r.text : '').trim()
          if (txt) {
            add({
              id: makeAttachmentId(), name: f.name, size: f.size, kind: 'text',
              text: truncateAttachmentText(txt)
            })
          }
          const imgs = (r && r.images) || []
          for (let i = 0; i < Math.min(imgs.length, 2); i++) {
            if (attachments.value.length >= MAX_ATTACHMENTS) break
            add({
              id: makeAttachmentId('p' + i), name: f.name + ' 第' + (i + 1) + '页',
              size: 0, kind: 'image', dataUrl: imgs[i], text: '', mode: 'vision'
            })
          }
          if (!txt && !imgs.length) ElMessage.warning('「' + f.name + '」没解析出内容')
          continue
        }
        if (isPlainTextFile(f.name, f.type)) {
          if (f.size > ATT_TEXT_MAX) { ElMessage.warning('「' + f.name + '」超过 200KB，已跳过'); continue }
          const text = await new Promise((res, rej) => {
            const r = new FileReader()
            r.onload = () => res(String(r.result || ''))
            r.onerror = () => rej(new Error('读取失败'))
            r.readAsText(f, 'utf-8')
          })
          add({
            id: makeAttachmentId(), name: f.name, size: f.size, kind: 'text',
            text: truncateAttachmentText(text)
          })
          continue
        }
        ElMessage.warning('「' + f.name + '」暂不支持（支持图片、PDF、Word/PPT/Excel、文本类文件）')
      } catch (err) {
        ElMessage.error('解析「' + f.name + '」失败：' + ((err && err.message) || err))
      }
    }
    attBusy.value = false
  }

  return { attachments, fileInput, attBusy, pickFiles, removeAttachment, toggleAttMode, onFilesPicked, clearAttachments }
}
