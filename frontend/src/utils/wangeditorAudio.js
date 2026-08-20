/**
 * wangEditor 5 自定义元素：audio 语音条
 *
 * 背景：wangEditor 5 核心不支持 <audio>（只有 video 模块），insertHtml('<audio>') 会把
 * 未知标签解析成空段落，导致「音频文件上传成功但插不进帖子正文」。这里通过
 * Boot.registerModule 注册自定义元素，让编辑器真正承载 <audio>：
 *   - renderElems：audio slate 节点 → <audio controls> DOM（编辑器内显示播放条）
 *   - parseElemsHtml：正文 HTML 里已有 <audio> → audio 节点（编辑回显）
 *   - elemsToHtml：audio 节点 → <audio> HTML（保存 / getHtml 输出）
 *
 * 注意：本文件是副作用注册，必须在创建编辑器之前被 import（放 PostEdit 顶部即可）。
 */
import { Boot } from '@wangeditor/editor'
import { h } from 'snabbdom'

Boot.registerModule({
  renderElems: [
    {
      type: 'audio',
      renderElem: (elem) =>
        h('audio', { attrs: { controls: 'controls', src: elem.src || '', preload: 'metadata' } }, []),
    },
  ],
  parseElemsHtml: [
    {
      selector: 'audio',
      parseElemHtml: (elem) => {
        const src = elem.getAttribute('src') || ''
        if (!src) return null
        return { type: 'audio', src }
      },
    },
  ],
  elemsToHtml: [
    {
      type: 'audio',
      elemToHtml: (elem) => {
        const src = elem.src || ''
        return src ? `<audio controls src="${src}" style="max-width:100%"></audio>` : '<p></p>'
      },
    },
  ],
})
