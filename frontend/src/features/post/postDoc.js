/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
/**
 * 文章正文的目录与颜色规则。纯函数可单测；只有 buildDocToc 需要浏览器 DOM。
 * 从 PostDetail.vue 抽离，行为与原实现保持一致。
 */

/** 判断颜色是否接近纯黑（清理富文本残留的纯黑内联字色，暗色模式下会看不见） */
export function isNearBlack(color) {
  if (!color) return false
  const c = color.trim().toLowerCase()
  if (c === 'black' || c === '#000' || c === '#000000') return true
  const rgb = c.match(/^rgba?\(([^)]+)\)$/)
  if (rgb) {
    const [r, g, b] = rgb[1].split(',').map(v => parseFloat(v.trim()))
    if (Number.isFinite(r) && Number.isFinite(g) && Number.isFinite(b)) return r < 30 && g < 30 && b < 30
  }
  const hex3 = c.match(/^#([0-9a-f])([0-9a-f])([0-9a-f])$/)
  if (hex3) {
    return parseInt(hex3[1] + hex3[1], 16) < 30 && parseInt(hex3[2] + hex3[2], 16) < 30 && parseInt(hex3[3] + hex3[3], 16) < 30
  }
  const hex6 = c.match(/^#([0-9a-f]{2})([0-9a-f]{2})([0-9a-f]{2})$/)
  if (hex6) {
    return parseInt(hex6[1], 16) < 30 && parseInt(hex6[2], 16) < 30 && parseInt(hex6[3], 16) < 30
  }
  return false
}

/** 没点标题样式的段落，按编号推断目录层级；不匹配的正文返回 null */
export function pseudoLevel(text) {
  // 中文数字顶层：一、二、…（覆盖到千/百等）
  if (/^[一二三四五六七八九十百千零两]+、/.test(text)) return 1
  // 大纲数字：1.1 / 2.3.4（至少一个点，且后面跟空格或中文/冒号，避免误伤「1.1GB」这类正文）
  const m = /^(\d+(?:\.\d+)+)(?=[\s\u4e00-\u9fa5：:])/.exec(text)
  if (m) return Math.min((m[1].match(/\./g) || []).length + 1, 6)
  return null
}

/** 按文档顺序构建可折叠的嵌套树（大标题包裹小标题） */
export function buildTocTree(candidates) {
  const tree = []
  const stack = []
  candidates.forEach((node) => {
    while (stack.length && stack[stack.length - 1].level >= node.level) stack.pop()
    if (stack.length) stack[stack.length - 1].children.push(node)
    else tree.push(node)
    stack.push(node)
  })
  return tree
}

/** 把树压平成有序数组（滚动时高亮当前章节用） */
export function flattenToc(tree) {
  const out = []
  const walk = (arr) => {
    for (const n of arr) {
      out.push(n)
      if (n.children && n.children.length) walk(n.children)
    }
  }
  walk(tree)
  return out
}

/** 解析正文 HTML、给标题打 id、构建目录树；只在浏览器环境调用 */
export function buildDocToc(html) {
  if (!html) return { html: '', toc: [] }
  const div = document.createElement('div')
  div.innerHTML = html
  const candidates = []
  Array.from(div.querySelectorAll('h1, h2, h3, h4, h5, h6, p')).forEach((el) => {
    const tag = el.tagName.toLowerCase()
    const text = (el.textContent || '').trim()
    if (!text) return
    let level = null
    if (/^h[1-6]$/.test(tag)) level = Number(tag.substring(1))
    else if (tag === 'p') level = pseudoLevel(text)
    if (level == null) return
    const id = 'toc-h-' + candidates.length
    el.id = id
    candidates.push({ id, text, level, children: [], open: true })
  })
  return { html: div.innerHTML, toc: buildTocTree(candidates) }
}
