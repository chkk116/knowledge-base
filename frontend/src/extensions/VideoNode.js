// 正文视频：同 ImageNode 的原因 —— Tiptap StarterKit 不含 Video 扩展，App 文档页用 Tiptap 渲染
// 会把 <video> 整段丢掉。这里补一个 inline 节点原样还原 <video>。
//
// 两种写法都要兼容：
//   ① <video src="..."> —— 直接取 src
//   ② <video><source src="..."></video> —— 取内部第一个 <source> 的 src
//      （<source> 不是 Tiptap 节点，解析时会被丢，所以必须在解析 video 时就把 src 捞出来，
//        否则只剩一个空壳 video 标签）
//
// 播放控制交给 utils/mediaEnhance.enhanceVideos()（16:9 占位、倍速/全屏/画中画控制条），
// 这里只保证标签和地址还原正确。
import { Node, mergeAttributes } from '@tiptap/core'

export default Node.create({
  name: 'kbVideo',
  inline: true,
  group: 'inline',
  atom: true,
  selectable: true,
  draggable: false,

  addAttributes() {
    return {
      src: {
        default: '',
        parseHTML: (el) =>
          el.getAttribute('src') ||
          (el.querySelector && el.querySelector('source')
            ? el.querySelector('source').getAttribute('src')
            : '') ||
          '',
        // 空 src 省略属性：否则 Tiptap 输出 src="" 会让浏览器对当前页发起伪请求
        renderHTML: (attrs) => (attrs.src ? { src: attrs.src } : {}),
      },
      poster: {
        default: '',
        parseHTML: (el) => el.getAttribute('poster') || '',
        renderHTML: (attrs) => (attrs.poster ? { poster: attrs.poster } : {}),
      },
    }
  },

  parseHTML() {
    return [{ tag: 'video' }]
  },

  renderHTML({ HTMLAttributes }) {
    return [
      'video',
      mergeAttributes(
        {
          controls: 'true',
          preload: 'metadata',
          playsinline: 'true',
          'webkit-playsinline': 'true',
        },
        HTMLAttributes
      ),
    ]
  },
})
