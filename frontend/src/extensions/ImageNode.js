// 正文图片：Tiptap 的 StarterKit **不含 Image 扩展**，而 App 文档页（MobileDocView）是用
// Tiptap 渲染正文的 —— 结果就是正文里的 <img> 被 ProseMirror 的 schema 直接丢弃，App 里看不到图片
// （网页端走 v-html 所以正常）。这里补一个最简的 inline 图片节点，只负责「原样还原 <img>」。
//
// 为什么用 inline 而不是 block：正文（wangEditor 产出）的图片通常嵌在 <p> 里，
// 设成 block 会被提到段落外甚至丢掉。
//
// data-full 保留原图地址：utils/mediaEnhance 的灯箱靠它放大看原图，绝对化也会一并处理。
import { Node, mergeAttributes } from '@tiptap/core'

export default Node.create({
  name: 'kbImage',
  inline: true,
  group: 'inline',
  atom: true,
  selectable: true,
  draggable: false,

  addAttributes() {
    return {
      src: {
        default: '',
        parseHTML: (el) => el.getAttribute('src') || '',
      },
      alt: {
        default: '',
        parseHTML: (el) => el.getAttribute('alt') || '',
        renderHTML: (attrs) => (attrs.alt ? { alt: attrs.alt } : {}),
      },
      title: {
        default: '',
        parseHTML: (el) => el.getAttribute('title') || '',
        renderHTML: (attrs) => (attrs.title ? { title: attrs.title } : {}),
      },
      width: {
        default: null,
        parseHTML: (el) => el.getAttribute('width') || null,
        renderHTML: (attrs) => (attrs.width ? { width: attrs.width } : {}),
      },
      // 灯箱原图：HTML 里是 data-full，属性名带短横线，需要显式指定读写的属性名
      full: {
        default: '',
        parseHTML: (el) => el.getAttribute('data-full') || '',
        renderHTML: (attrs) => (attrs.full ? { 'data-full': attrs.full } : {}),
      },
    }
  },

  parseHTML() {
    return [{ tag: 'img[src]' }]
  },

  renderHTML({ HTMLAttributes }) {
    return ['img', mergeAttributes(HTMLAttributes)]
  },
})
